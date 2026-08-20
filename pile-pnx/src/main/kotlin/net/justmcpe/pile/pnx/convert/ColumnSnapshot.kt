package net.justmcpe.pile.pnx.convert

import net.justmcpe.pile.format.*
import net.justmcpe.pile.pnx.PileProviderOptions
import org.powernukkitx.level.format.ChunkSection
import org.powernukkitx.level.format.IChunk
import org.powernukkitx.nbt.tag.CompoundTag
import org.powernukkitx.utils.BlockUpdateEntry

/**
 * Extracts live PNX chunks into the format model. The palette lists only ever grow, so columns
 * snapshotted earlier stay valid against the current lists.
 */
internal class ColumnSnapshot(
    baseStates: List<BlockState>,
    baseBiomes: List<String>,
    private val options: PileProviderOptions = PileProviderOptions(),
) {
    constructor(world: World, options: PileProviderOptions = PileProviderOptions()) :
            this(world.blockStates, world.biomes, options)

    private val stateIds = LinkedHashMap<BlockState, Int>().apply {
        baseStates.forEachIndexed { index, state -> put(state, index) }
    }
    private val biomeIds = LinkedHashMap<String, Int>().apply {
        baseBiomes.forEachIndexed { index, name -> put(name, index) }
    }

    val paletteStates: List<BlockState>
        field = baseStates.toMutableList()
    val paletteBiomes: List<String>
        field = baseBiomes.toMutableList()

    fun snapshot(chunk: IChunk, base: Column?): Column {
        var result: Column? = null
        chunk.batchProcess { unsafe ->
            val dimension = unsafe.dimensionData
            val minSection = dimension.minSectionY
            val sections = arrayOfNulls<net.justmcpe.pile.format.Section>(dimension.chunkSectionCount)
            val biomeStorages = Array(dimension.chunkSectionCount) { Storage.uniform(biome("minecraft:plains")) }
            val lights = arrayOfNulls<LightData>(dimension.chunkSectionCount)
            var anyLight = false

            for (i in 0 until dimension.chunkSectionCount) {
                val section = unsafe.sections[i] ?: continue
                val layers = ArrayList<Storage>(ChunkSection.LAYER_COUNT)
                for (layer in 0 until ChunkSection.LAYER_COUNT) {
                    val refs = IntArray(4096)
                    var allAir = true
                    for (x in 0 until 16) for (z in 0 until 16) for (y in 0 until 16) {
                        val state = BlockStates.of(section.getBlockState(x, y, z, layer))
                        val ref = state(state)
                        refs[Storage.index(x, y, z)] = ref
                        if (!state.isAir) allAir = false
                    }
                    layers.add(Storage.of(refs))
                    if (!allAir) continue
                }
                while (layers.isNotEmpty() && layers.last().isUniform && paletteStates[layers.last().palette[0]].isAir) layers.removeAt(
                    layers.lastIndex
                )
                if (layers.isNotEmpty()) sections[i] = net.justmcpe.pile.format.Section(layers)

                val biomeRefs = IntArray(4096)
                if (!options.skipBiomes) for (x in 0 until 16) for (z in 0 until 16) for (y in 0 until 16) {
                    biomeRefs[Storage.index(x, y, z)] = biome(Biomes.name(section.getBiomeId(x, y, z)))
                }
                biomeStorages[i] = Storage.of(biomeRefs)

                val blockLight = section.blockLights().data.clone()
                val skyLight = section.skyLights().data.clone()
                if (blockLight.any { it.toInt() != 0 } || skyLight.any { it.toInt() != 0 }) {
                    lights[i] = LightData(blockLight, skyLight)
                    anyLight = true
                }
            }

            val blockEntities = ArrayList<BlockEntity>()
            if (!options.skipBlockEntities) {
                for (blockEntity in unsafe.blockEntities.values) {
                    val tag = blockEntity.getNbt().nbtForPile()
                    blockEntities.add(
                        BlockEntity(
                            blockEntity.floorX,
                            blockEntity.floorY,
                            blockEntity.floorZ,
                            PnxNbt.write(tag)
                        )
                    )
                }
            }
            val entities = ArrayList<Entity>()
            if (!options.skipEntities) {
                for (entity in unsafe.entities.values) {
                    val tag = entity.getNbt().copy().putLong("UniqueID", entity.id)
                    entities.add(Entity(entity.id, PnxNbt.write(tag)))
                }
            }
            // PNX keeps pending scheduled updates in the live chunk scheduler. Reading the
            // base column here would resurrect stale ticks after a chunk had been loaded and
            // processed, so always snapshot the live queue for loaded chunks.
            val scheduledUpdates =
                if (options.skipScheduledTicks) emptyList() else chunk.blockUpdateScheduler.getPendingBlockUpdates()
                    .map { update: BlockUpdateEntry ->
                        ScheduledUpdate(
                            x = update.pos.floorX,
                            y = update.pos.floorY,
                            z = update.pos.floorZ,
                            state = state(BlockStates.of(update.block.blockState)),
                            tick = update.delay,
                        )
                    }
            result = Column(
                chunk.x,
                chunk.z,
                minSection,
                sections,
                biomeStorages,
                if (options.storeLight && anyLight) lights else null,
                blockEntities,
                entities,
                chunk.level.currentTick,
                scheduledUpdates,
                if (options.skipUserData) ByteArray(0) else base?.userData ?: ByteArray(0),
            )
        }
        return result ?: error("PNX chunk snapshot did not execute")
    }

    private fun state(state: BlockState): Int = stateIds.getOrPut(state) {
        paletteStates.add(state)
        paletteStates.lastIndex
    }

    private fun biome(name: String): Int = biomeIds.getOrPut(name) {
        paletteBiomes.add(name)
        paletteBiomes.lastIndex
    }

    private fun CompoundTag.nbtForPile(): CompoundTag = copy().remove("x", "y", "z")
}
