package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.format.Structure
import net.justmcpe.pile.pnx.convert.BlockStates
import net.justmcpe.pile.pnx.convert.PnxNbt
import org.powernukkitx.level.structure.PNXStructure
import org.powernukkitx.nbt.tag.CompoundTag
import org.powernukkitx.nbt.tag.IntTag
import org.powernukkitx.nbt.tag.ListTag
import org.powernukkitx.nbt.tag.Tag
import java.nio.file.Files
import java.nio.file.Path
import org.powernukkitx.level.structure.Structure as PnxNativeStructure

/** Bridges a PILE structure into PNX's native placement representation. */
public object PnxStructures {
    public fun read(path: Path): PNXStructure = fromPile(PileReader.readStructure(Files.readAllBytes(path)))

    public fun fromPile(structure: Structure): PNXStructure {
        require(structure.sizeX.toLong() * structure.sizeY * structure.sizeZ <= Int.MAX_VALUE)
        val palette = ArrayList<Int>()
        val paletteByHash = HashMap<Int, Int>()
        val blocks = ByteArray(structure.sizeX * structure.sizeY * structure.sizeZ)
        val nx = (structure.sizeX + 15) / 16
        val ny = (structure.sizeY + 15) / 16
        val nz = (structure.sizeZ + 15) / 16

        fun stateHash(ref: Int): Int = BlockStates.resolve(structure.blockStates[ref]).blockStateHash()
        fun blockRef(x: Int, y: Int, z: Int): Int {
            val cx = x / 16;
            val cy = y / 16;
            val cz = z / 16
            val cell = structure.cells[(cx * nz + cz) * ny + cy] ?: return 0
            val section = cell
            val index = ((x and 15) shl 8) or ((z and 15) shl 4) or (y and 15)
            val ref = section.layers[0][index]
            val hash = stateHash(ref)
            val paletteIndex = paletteByHash.getOrPut(hash) { palette.add(hash); palette.lastIndex }
            require(paletteIndex < 255) { "PNX structure palette supports at most 255 states" }
            return paletteIndex + 1
        }

        var out = 0
        for (z in 0 until structure.sizeZ) for (y in 0 until structure.sizeY) for (x in 0 until structure.sizeX) {
            blocks[out++] = blockRef(x, y, z).toByte()
        }
        val paletteTags = palette.map { IntTag(it) }
        val nbt = CompoundTag()
            .putIntArray("size", intArrayOf(structure.sizeX, structure.sizeY, structure.sizeZ))
            .putList("palette", ListTag(Tag.TAG_Int.toInt(), paletteTags))
            .putByteArray("blocks", blocks)
            .putList("jigsaw", ListTag(Tag.TAG_Compound.toInt()))
        return PNXStructure.fromNbt(nbt)
    }

    /** Converts PILE collections as well as blocks to PNX's full native structure model. */
    public fun fromPileNative(structure: Structure): PnxNativeStructure {
        val volume = Math.multiplyExact(Math.multiplyExact(structure.sizeX, structure.sizeY), structure.sizeZ)
        val layers = Array(2) { ArrayList<IntTag>(volume) }
        val palette = ArrayList<org.powernukkitx.nbt.tag.CompoundTag>()
        val paletteByState = HashMap<net.justmcpe.pile.format.BlockState, Int>()
        val nx = (structure.sizeX + 15) / 16;
        val ny = (structure.sizeY + 15) / 16;
        val nz = (structure.sizeZ + 15) / 16
        fun paletteIndex(ref: Int): Int = paletteByState.getOrPut(structure.blockStates[ref]) {
            palette.add(
                org.powernukkitx.nbt.tag.CompoundTag.fromNetwork(
                    BlockStates.tagOf(
                        structure.blockStates[ref].name,
                        structure.blockStates[ref].properties
                    )
                )
            )
            palette.lastIndex
        }
        for (x in 0 until structure.sizeX) for (y in 0 until structure.sizeY) for (z in 0 until structure.sizeZ) {
            val cx = x / 16;
            val cy = y / 16;
            val cz = z / 16
            val cell = structure.cells[(cx * nz + cz) * ny + cy]
            val index = ((x and 15) shl 8) or ((z and 15) shl 4) or (y and 15)
            for (layer in 0..1) {
                val value = cell?.layers?.getOrNull(layer)?.get(index)?.let(::paletteIndex) ?: -1
                layers[layer].add(IntTag(value))
            }
        }
        val layerTags = ListTag(
            Tag.TAG_List.toInt(), listOf(
                ListTag(Tag.TAG_Int.toInt(), layers[0]),
                ListTag(Tag.TAG_Int.toInt(), layers[1]),
            )
        )
        val blockPositionData = CompoundTag()
        for (be in structure.blockEntities) {
            val flat = be.x * structure.sizeY * structure.sizeZ + be.y * structure.sizeZ + be.z
            val data = if (be.nbt.isEmpty()) CompoundTag() else PnxNbt.read(be.nbt)
            blockPositionData.putCompound(flat.toString(), CompoundTag().putCompound("block_entity_data", data))
        }
        val defaultPalette = CompoundTag()
            .putList("block_palette", ListTag(Tag.TAG_Compound.toInt(), palette))
            .putCompound("block_position_data", blockPositionData)
        val structureTag = CompoundTag()
            .putList("block_indices", layerTags)
            .putCompound("palette", CompoundTag().putCompound("default", defaultPalette))
            .putList("entities", ListTag(Tag.TAG_Compound.toInt(), structure.entities.map { PnxNbt.read(it) }))
        return PnxNativeStructure.fromNbt(
            CompoundTag()
                .putInt("format_version", 1)
                .putList(
                    "size",
                    ListTag(
                        Tag.TAG_Int.toInt(),
                        listOf(IntTag(structure.sizeX), IntTag(structure.sizeY), IntTag(structure.sizeZ))
                    )
                )
                .putCompound("structure", structureTag)
                .putList(
                    "structure_world_origin",
                    ListTag(
                        Tag.TAG_Int.toInt(),
                        listOf(IntTag(structure.originX), IntTag(structure.originY), IntTag(structure.originZ))
                    )
                )
        )
    }
}
