package net.justmcpe.pile.pnx.convert

import it.unimi.dsi.fastutil.objects.ReferenceArrayList
import net.justmcpe.pile.format.BlockState
import net.justmcpe.pile.format.Column
import net.justmcpe.pile.format.World
import org.powernukkitx.block.BlockAir
import org.powernukkitx.level.biome.BiomeID
import org.powernukkitx.level.format.*
import org.powernukkitx.level.format.bitarray.BitArrayVersion
import org.powernukkitx.level.format.leveldb.BDSEntityTranslator
import org.powernukkitx.level.format.palette.BlockPalette
import org.powernukkitx.level.util.NibbleArray
import org.powernukkitx.nbt.tag.CompoundTag
import org.powernukkitx.nbt.tag.Tag
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.powernukkitx.block.BlockState as PnxBlockState

/** Builds PNX chunks from decoded columns. One instance per palette, since resolution is cached per entry. */
internal class ChunkConverter(private val blockStates: List<BlockState>, biomes: List<String>) {
    constructor(world: World) : this(world.blockStates, world.biomes)

    private val log = LoggerFactory.getLogger(ChunkConverter::class.java)
    private val states: Array<PnxBlockState?> = arrayOfNulls(blockStates.size)
    private val biomeIds: IntArray = IntArray(biomes.size) { Biomes.id(biomes[it]) }
    private val warnedLayers = AtomicBoolean()
    private val warnedRange = AtomicBoolean()

    private fun state(ref: Int): PnxBlockState =
        states[ref] ?: BlockStates.resolve(blockStates[ref]).also { states[ref] = it }

    fun convert(provider: LevelProvider, column: Column): IChunk {
        val dim = provider.dimensionData
        val sections = arrayOfNulls<ChunkSection>(dim.chunkSectionCount)
        var light = column.light != null
        for (i in 0 until column.sectionCount) {
            val sectionY = column.minSection + i
            if (sectionY < dim.minSectionY || sectionY > dim.maxSectionY) {
                if (column.sections[i] != null && warnedRange.compareAndSet(false, true)) {
                    log.warn(
                        "column ({},{}) has blocks in section {}, outside the dimension's range {}..{}; they are dropped",
                        column.x, column.z, sectionY, dim.minSectionY, dim.maxSectionY
                    )
                }
                continue
            }
            val section = column.sections[i]
            val biomes = column.biomes[i]
            val biomeIsDefault = biomes.isUniform && biomeIds[biomes[0]] == BiomeID.PLAINS
            val sectionLight = column.light?.get(i)
            if (section == null && biomeIsDefault && sectionLight == null) continue

            val layers = arrayOfNulls<BlockPalette>(ChunkSection.LAYER_COUNT)
            if (section != null) {
                if (section.layers.size > ChunkSection.LAYER_COUNT && warnedLayers.compareAndSet(false, true)) {
                    log.warn(
                        "column ({},{}) section {} has {} layers; PNX keeps {} and the rest are dropped",
                        column.x, column.z, sectionY, section.layers.size, ChunkSection.LAYER_COUNT
                    )
                }
                for (l in 0 until minOf(section.layers.size, ChunkSection.LAYER_COUNT)) {
                    layers[l] = PnxPalettes.blocks(section.layers[l], ::state)
                }
            }
            for (l in layers.indices) if (layers[l] == null) layers[l] = airPalette()

            val blockLight = NibbleArray(ChunkSection.SIZE)
            val skyLight = NibbleArray(ChunkSection.SIZE)
            if (sectionLight != null) {
                sectionLight.blockLight?.let { blockLight.copyFrom(it) }
                sectionLight.skyLight?.let { skyLight.copyFrom(it) }
            } else {
                light = false
            }
            @Suppress("UNCHECKED_CAST")
            sections[sectionY - dim.minSectionY] = ChunkSection(
                sectionY.toByte(),
                layers as Array<BlockPalette>,
                PnxPalettes.biomes(biomes) { biomeIds[it] },
                blockLight,
                skyLight,
                AtomicLong(0),
            )
        }

        val blockEntities = ArrayList<CompoundTag>(column.blockEntities.size)
        for (be in column.blockEntities) {
            val tag = PnxNbt.read(be.nbt)
            tag.putInt("x", be.x).putInt("y", be.y).putInt("z", be.z)
            blockEntities.add(tag)
        }
        val entities = ArrayList<CompoundTag>(column.entities.size)
        for (e in column.entities) {
            val tag = PnxNbt.read(e.nbt)
            val translated = if (tag.containsList("Pos", Tag.TAG_Float)) BDSEntityTranslator.translate(tag) else tag
            if (translated != null) entities.add(translated)
        }

        val chunk = Chunk.builder()
            .chunkX(column.x)
            .chunkZ(column.z)
            .state(ChunkState.FINISHED)
            .levelProvider(provider)
            .sections(sections)
            .entities(entities)
            .blockEntities(blockEntities)
            .build()
        chunk.recalculateHeightMap()
        if (!light) chunk.populateSkyLight()
        chunk.setLightPopulated()
        chunk.setChanged(false)
        return chunk
    }

    private fun airPalette(): BlockPalette = BlockPalette(BlockAir.STATE, ReferenceArrayList(16), BitArrayVersion.V2)
}
