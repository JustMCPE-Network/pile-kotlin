package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtCompound
import net.justmcpe.pile.format.nbt.NbtInt

/** Baked light of one section. Either array may be absent; both are 2048-byte nibble arrays (format.md §1). */
public class LightData(public val blockLight: ByteArray?, public val skyLight: ByteArray?) {
    init {
        require(blockLight != null || skyLight != null)
        require(blockLight == null || blockLight.size == Limits.LIGHT_ARRAY_SIZE)
        require(skyLight == null || skyLight.size == Limits.LIGHT_ARRAY_SIZE)
    }
}

/** A present block section: its storage layers, layer 0 being the block and layer 1 Bedrock's waterlogging layer. */
public class Section(public val layers: List<Storage>) {
    init {
        require(layers.isNotEmpty()) { "a section has at least one layer" }
        require(layers.size <= Limits.MAX_LAYERS) { "a section has at most ${Limits.MAX_LAYERS} layers" }
    }
}

/** A block entity at an absolute position. [nbt] is the compound as stored, without its x/y/z keys. */
public class BlockEntity(public val x: Int, public val y: Int, public val z: Int, public val nbt: ByteArray) {
    /** The compound with `x`, `y`, `z` int tags injected, as a consumer expects it. */
    public fun decoded(): NbtCompound = Nbt.decode(nbt).with("x", NbtInt(x)).with("y", NbtInt(y)).with("z", NbtInt(z))

    public companion object {
        /** Strips `x`, `y`, `z` and encodes canonically. */
        public fun of(x: Int, y: Int, z: Int, data: NbtCompound): BlockEntity =
            BlockEntity(x, y, z, Nbt.encode(data.without("x", "y", "z")))
    }
}

/** An entity: its whole compound, including the `UniqueID` long the format keys it by. */
public class Entity(public val uniqueId: Long, public val nbt: ByteArray) {
    public fun decoded(): NbtCompound = Nbt.decode(nbt)

    public companion object {
        public fun of(data: NbtCompound): Entity {
            val id = data.getLong("UniqueID") ?: throw InvalidContentException("entity compound has no UniqueID long")
            return Entity(id, Nbt.encode(data))
        }
    }
}

/** A scheduled block update at an absolute position; [state] references the world's block state list. */
public data class ScheduledUpdate(val x: Int, val y: Int, val z: Int, val state: Int, val tick: Long)

/**
 * One chunk column. [sections] and [biomes] have [sectionCount] entries for section indices
 * [minSection] ..< [minSection] + [sectionCount]; a null section is all air. [biomes] is never null
 * after a decode, since absent biome sections are materialised as the file's default.
 */
public class Column(
    public val x: Int,
    public val z: Int,
    public val minSection: Int,
    public val sections: Array<Section?>,
    public val biomes: Array<Storage>,
    public val light: Array<LightData?>?,
    public val blockEntities: List<BlockEntity>,
    public val entities: List<Entity>,
    public val tick: Long,
    public val scheduledUpdates: List<ScheduledUpdate>,
    public val userData: ByteArray,
) {
    public val sectionCount: Int get() = sections.size

    init {
        require(sections.isNotEmpty() && sections.size <= Limits.MAX_SECTIONS) { "section count out of range" }
        require(biomes.size == sections.size) { "biomes must cover every section" }
        require(light == null || light.size == sections.size) { "light must cover every section" }
        require(minSection >= Limits.MIN_SECTION_INDEX && minSection + sections.size <= Limits.MAX_SECTION_INDEX + 1) {
            "section span outside the addressable range"
        }
    }

    public val minY: Int get() = minSection * 16
    public val maxY: Int get() = (minSection + sectionCount) * 16 - 1
}

/** A decoded world: the palettes every column references, the metadata blobs and the columns. */
public class World(
    public val blockVersion: Int,
    public val settings: ByteArray,
    public val userData: ByteArray,
    public val blockStates: List<BlockState>,
    public val biomes: List<String>,
    public val columns: List<Column>,
) {
    private val byPosition: Map<Long, Column> by lazy { columns.associateBy { key(it.x, it.z) } }

    public fun column(x: Int, z: Int): Column? = byPosition[key(x, z)]

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFF_FFFFL)
}

/** A decoded structure: fixed-size 16³ cells in x-major, z, y order. */
public class Structure(
    public val blockVersion: Int,
    public val userData: ByteArray,
    public val blockStates: List<BlockState>,
    public val sizeX: Int,
    public val sizeY: Int,
    public val sizeZ: Int,
    public val originX: Int,
    public val originY: Int,
    public val originZ: Int,
    public val cells: Array<Section?>,
    public val blockEntities: List<BlockEntity>,
    public val entities: List<ByteArray>,
) {
    public val cellCount: Int get() = cells.size
}

/** The cheap-to-read part of a file: header fields and metadata blobs, no chunk data. */
public class Meta(
    public val header: PileHeader,
    public val settings: ByteArray,
    public val userData: ByteArray,
    public val stats: ByteArray?,
)
