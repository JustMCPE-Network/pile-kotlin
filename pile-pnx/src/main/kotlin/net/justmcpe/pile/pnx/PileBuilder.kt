package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.*
import net.justmcpe.pile.pnx.convert.BlockStates
import net.justmcpe.pile.pnx.convert.PnxNbt
import org.powernukkitx.level.DimensionData
import org.powernukkitx.nbt.tag.CompoundTag
import java.nio.file.Path
import org.powernukkitx.block.BlockState as PnxBlockState

/**
 * Builds a pile world without starting a PNX level.
 *
 * This is the PNX equivalent of upstream PILE's Builder: callers can create arenas,
 * lobbies and generated maps in memory, then hand the resulting bytes to PNX or save
 * them directly as a `.pile` file.
 */
public class PileBuilder(
    public val dimension: DimensionData,
    public val name: String = "generated",
) {
    private class MutableColumn(val x: Int, val z: Int, sectionCount: Int) {
        val sections = Array(sectionCount) { IntArray(4096) }
        val touched = BooleanArray(sectionCount)
        val biomes = Array(sectionCount) { IntArray(4096) }
        val biomeTouched = BooleanArray(sectionCount)
        val blockEntities = ArrayList<BlockEntity>()
        val entities = ArrayList<Entity>()
        val scheduledUpdates = ArrayList<ScheduledUpdate>()
        var userData: ByteArray = ByteArray(0)
    }

    private val states = ArrayList<BlockState>().apply { add(BlockState.air(BlockStates.currentVersion)) }
    private val stateIds = LinkedHashMap<BlockState, Int>().apply { put(states[0], 0) }
    private val biomeNames = ArrayList<String>().apply { add("minecraft:plains") }
    private val biomeIds = LinkedHashMap<String, Int>().apply { put(biomeNames[0], 0) }
    private val columns = LinkedHashMap<Long, MutableColumn>()
    private var worldUserData: ByteArray = ByteArray(0)

    private fun column(x: Int, z: Int): MutableColumn {
        val key = (x.toLong() shl 32) or (z.toLong() and 0xFFFF_FFFFL)
        return columns.getOrPut(key) { MutableColumn(x, z, dimension.chunkSectionCount) }
    }

    private fun sectionIndex(y: Int): Int = Math.floorDiv(y, 16) - dimension.minSectionY

    private fun requireY(y: Int): Int {
        val index = sectionIndex(y)
        require(index in 0 until dimension.chunkSectionCount) {
            "y=$y is outside ${dimension.minSectionY * 16}..${(dimension.maxSectionY + 1) * 16 - 1}"
        }
        return index
    }

    /** Places a PNX block state at an absolute world position. */
    public fun setBlock(x: Int, y: Int, z: Int, state: PnxBlockState): PileBuilder {
        val section = requireY(y)
        val cx = x shr 4
        val cz = z shr 4
        val col = column(cx, cz)
        val ref = state(BlockStates.of(state))
        col.sections[section][Storage.index(x and 15, y and 15, z and 15)] = ref
        col.touched[section] = true
        return this
    }

    /** Fills the inclusive box with one PNX block state. */
    public fun fill(
        minX: Int,
        minY: Int,
        minZ: Int,
        maxX: Int,
        maxY: Int,
        maxZ: Int,
        state: PnxBlockState
    ): PileBuilder {
        for (x in minOf(minX, maxX)..maxOf(minX, maxX)) {
            for (y in minOf(minY, maxY)..maxOf(minY, maxY)) {
                for (z in minOf(minZ, maxZ)..maxOf(minZ, maxZ)) setBlock(x, y, z, state)
            }
        }
        return this
    }

    /** Sets a biome name for one absolute world position. */
    public fun setBiome(x: Int, y: Int, z: Int, biome: String): PileBuilder {
        val section = requireY(y)
        require(biome.isNotBlank()) { "biome must not be blank" }
        val id = biomeIds.getOrPut(biome) { biomeNames.add(biome); biomeNames.lastIndex }
        val col = column(x shr 4, z shr 4)
        col.biomes[section][Storage.index(x and 15, y and 15, z and 15)] = id
        col.biomeTouched[section] = true
        return this
    }

    /** Adds a block entity using the same coordinate-stripped NBT convention as PILE. */
    public fun addBlockEntity(x: Int, y: Int, z: Int, tag: CompoundTag): PileBuilder {
        requireY(y)
        column(x shr 4, z shr 4).blockEntities.add(BlockEntity(x, y, z, PnxNbt.write(tag.copy().remove("x", "y", "z"))))
        return this
    }

    /** Adds an entity. The id is written as PILE's required UniqueID key. */
    public fun addEntity(id: Long, tag: CompoundTag): PileBuilder {
        val copy = tag.copy().putLong("UniqueID", id)
        val x = copy.getDouble("PosX").toInt()
        val z = copy.getDouble("PosZ").toInt()
        return addEntity(id, x, z, copy)
    }

    /** Adds an entity with an explicit chunk placement, useful when the NBT has Bedrock Pos lists. */
    public fun addEntity(id: Long, x: Int, z: Int, tag: CompoundTag): PileBuilder {
        val copy = tag.copy().putLong("UniqueID", id)
        column(x shr 4, z shr 4).entities.add(Entity(id, PnxNbt.write(copy)))
        return this
    }

    /** Stores opaque world-level data for applications using the PILE userData field. */
    public fun setWorldUserData(data: ByteArray): PileBuilder {
        worldUserData = data.clone()
        return this
    }

    /** Stores opaque per-column data for applications using the PILE userData field. */
    public fun setColumnUserData(x: Int, z: Int, data: ByteArray): PileBuilder {
        column(x, z).userData = data.clone()
        return this
    }

    /** Adds a PILE scheduled update at the supplied absolute tick. */
    public fun scheduleUpdate(x: Int, y: Int, z: Int, state: PnxBlockState, tick: Long): PileBuilder {
        requireY(y)
        column(x shr 4, z shr 4).scheduledUpdates.add(
            ScheduledUpdate(x, y, z, this.state(BlockStates.of(state)), tick),
        )
        return this
    }

    /** Encodes the current in-memory map into the world model the writer encodes. */
    public fun build(): World {
        val result = columns.values.map { col ->
            val sections = arrayOfNulls<Section>(dimension.chunkSectionCount)
            val biomes = Array(dimension.chunkSectionCount) { i ->
                if (col.biomeTouched[i]) Storage.of(col.biomes[i]) else Storage.uniform(0)
            }
            for (i in sections.indices) {
                if (col.touched[i] && col.sections[i].any { it != 0 }) sections[i] =
                    Section(listOf(Storage.of(col.sections[i])))
            }
            Column(
                x = col.x,
                z = col.z,
                minSection = dimension.minSectionY,
                sections = sections,
                biomes = biomes,
                light = null,
                blockEntities = col.blockEntities.toList(),
                entities = col.entities.toList(),
                tick = 0,
                scheduledUpdates = col.scheduledUpdates.toList(),
                userData = col.userData.clone(),
            )
        }
        val worldSettings = WorldSettings(name = name, spawnY = 64)
        return World(
            BlockStates.currentVersion,
            worldSettings.encode(),
            worldUserData.clone(),
            states.toList(),
            biomeNames.toList(),
            result
        )
    }

    /** Atomically writes the current map to one `.pile` file. */
    public fun save(file: Path, compression: Compression = Compression.BEST): Path {
        PileWriter.writeWorld(file, build(), WriteOptions(compression))
        return file
    }

    private fun state(state: BlockState): Int = stateIds.getOrPut(state) { states.add(state); states.lastIndex }
}
