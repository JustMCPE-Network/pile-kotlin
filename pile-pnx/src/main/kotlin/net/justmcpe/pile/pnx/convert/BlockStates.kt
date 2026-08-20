package net.justmcpe.pile.pnx.convert

import net.justmcpe.pile.format.BlockState
import net.justmcpe.pile.format.PropertyValue
import org.cloudburstmc.nbt.NbtMap
import org.powernukkitx.block.BlockUnknown
import org.powernukkitx.level.updater.block.BlockStateUpdaters
import org.powernukkitx.network.NetworkConstants
import org.powernukkitx.registry.Registries
import org.powernukkitx.utils.HashUtils
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.powernukkitx.block.BlockState as PnxBlockState

/**
 * Resolves palette entries against PNX's block state registry the way PNX resolves a leveldb palette
 * entry: hash the (name, states) compound, fall back to the block state updater from the entry's
 * version, and keep an unresolvable state as PNX's unknown block with its tag preserved.
 */
public object BlockStates {
    private val log = LoggerFactory.getLogger(BlockStates::class.java)
    private val cache = ConcurrentHashMap<BlockState, PnxBlockState>()

    public fun resolve(state: BlockState): PnxBlockState = cache.computeIfAbsent(state) { resolveUncached(it) }

    /** The block-state version PNX stamps on its own states, and what a file written from PNX declares. */
    public val currentVersion: Int get() = NetworkConstants.BLOCK_STATE_VERSION_NO_REVISION

    private fun resolveUncached(state: BlockState): PnxBlockState {
        val unknown = BlockUnknown.PROPERTIES.defaultState
        val tag = tagOf(state.name, state.properties)
        val hash = HashUtils.fnv1a_32_nbt(tag)
        val direct = Registries.BLOCKSTATE.get(hash)
        if (direct != null && direct != unknown) return direct

        val versioned = tag.toBuilder().putInt("version", state.version).build()
        val updated = BlockStateUpdaters.updateBlockState(versioned, state.version)
        val states = TreeMap(updated.getCompound("states"))
        val updatedTag =
            NbtMap.builder().putString("name", updated.getString("name")).putCompound("states", NbtMap.fromMap(states))
                .build()
        val updatedHash = HashUtils.fnv1a_32_nbt(updatedTag)
        val resolved = Registries.BLOCKSTATE.get(updatedHash)
        if (resolved != null && resolved != unknown) return resolved

        log.debug("block state {} does not resolve in PNX, kept as unknown", state)
        return PnxBlockState.makeUnknownBlockState(
            updatedHash,
            updatedTag.toBuilder().putInt("version", state.version).build()
        )
    }

    /** The palette entry as PNX hashes it: sorted states, no version. */
    internal fun tagOf(name: String, properties: Map<String, PropertyValue>): NbtMap {
        val states = TreeMap<String, Any>()
        for ((k, v) in properties) {
            states[k] = when (v) {
                is PropertyValue.ByteValue -> v.value.toByte()
                is PropertyValue.IntValue -> v.value
                is PropertyValue.StringValue -> v.value
            }
        }
        return NbtMap.builder().putString("name", name).putCompound("states", NbtMap.fromMap(states)).build()
    }

    /** The palette entries PNX cannot resolve: what would load as unknown blocks. */
    public fun unresolved(states: List<BlockState>): List<BlockState> =
        states.filter { resolve(it).identifier == org.powernukkitx.block.BlockID.UNKNOWN }

    /** The inverse of [resolve]: a PNX state as a palette entry at [currentVersion]. */
    public fun of(state: PnxBlockState): BlockState {
        val tag = state.blockStateTag
        val states = tag.getCompound("states")
        val props = LinkedHashMap<String, PropertyValue>()
        for ((k, v) in states) {
            props[k] = when (v) {
                is Byte -> PropertyValue.ByteValue(v.toInt() and 0xFF)
                is Int -> PropertyValue.IntValue(v)
                is String -> PropertyValue.StringValue(v)
                is Boolean -> PropertyValue.of(v)
                else -> throw IllegalArgumentException("block state ${tag.getString("name")} property $k has unsupported type ${v.javaClass}")
            }
        }
        val version = if (tag.containsKey("version")) tag.getInt("version") else currentVersion
        return BlockState(tag.getString("name"), props, if (version == 0) currentVersion else version)
    }
}
