package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.wire.*
import java.nio.file.Files
import java.nio.file.Path

/** Decodes solid world files (format.md §4). */
public object PileReader {
    public const val PLAINS: String = "minecraft:plains"

    public fun readMeta(path: Path): Meta = readMeta(Files.readAllBytes(path))

    /** Header fields and metadata blobs, with the body decompressed but no chunk decoded. */
    public fun readMeta(file: ByteArray): Meta {
        val f = Frame.parse(file)
        val r = Frame.decompressBody(f.header, file, f.storedOffset, f.storedLength)
        val m = readMetaBlobs(r, f.header)
        return Meta(f.header, m.settings, m.userData, m.stats)
    }

    public fun readWorld(path: Path, options: DecodeOptions = DecodeOptions.DEFAULT): World =
        readWorld(Files.readAllBytes(path), options)

    public fun readWorld(file: ByteArray, options: DecodeOptions = DecodeOptions.DEFAULT): World {
        if (file.size > 7 && file[7].toInt() and 0xFF == 1) return IndexedReader.read(file, options)
        val f = Frame.parse(file)
        if (f.header.kind != FileKind.WORLD) corrupt("file kind ${f.header.kind.code} is not a world")
        val h = f.header
        val r = Frame.decompressBody(h, file, f.storedOffset, f.storedLength)
        val meta = readMetaBlobs(r, h)
        val states = Palettes.readBlockPalette(r, h.blockVersion)
        val biomeNames = ArrayList(Palettes.readBiomePalette(r))
        val storedBiomes = biomeNames.size
        val blobs = Blobs.readTable(r)

        val defaultRef: Int
        if (h.hasDefaultBiome) {
            defaultRef = h.defaultBiomeRef
            if (defaultRef >= biomeNames.size) corrupt("default biome reference $defaultRef out of range")
        } else {
            var i = biomeNames.indexOf(PLAINS)
            if (i < 0) {
                biomeNames.add(PLAINS)
                i = biomeNames.size - 1
            }
            defaultRef = i
        }

        val chunkN = r.count(Limits.MAX_CHUNKS, "chunk")
        val budget = DecodeBudget(options.ceiling)
        val used = BooleanArray(blobs.size)
        val next = IntArray(1)
        val source = BlobSource(blobs, used, next)
        val records = ArrayList<RawRecord>(minOf(chunkN, r.remaining / 8 + 1, Limits.MAX_PREALLOC))
        var prevX = 0L
        var prevZ = 0L
        var prevKey = 0L
        repeat(chunkN) {
            budget.chargeColumns(1)
            val dx = r.svarint()
            val dz = r.svarint()
            val sx = prevX + dx
            val sz = prevZ + dz
            if (sx < Int.MIN_VALUE || sx > Int.MAX_VALUE || sz < Int.MIN_VALUE || sz > Int.MAX_VALUE) {
                corrupt("chunk position ($sx,$sz) out of int32 range")
            }
            val x = sx.toInt()
            val z = sz.toInt()
            val key = Morton.key(x, z)
            if (records.isNotEmpty() && Morton.compare(
                    key,
                    prevKey
                ) <= 0
            ) corrupt("chunk ($x,$z) is out of order or duplicated")
            prevKey = key
            records.add(Records.parse(r, source, h.storeLight, x, z, budget))
            prevX = x.toLong()
            prevZ = z.toLong()
        }
        if (r.remaining != 0) corrupt("${r.remaining} trailing bytes after last chunk")
        if (h.storeLight && records.none { rec -> rec.lightPresence!!.any { it.toInt() != 0 } }) {
            corrupt("StoreLight is set but no section carries light")
        }
        used.forEachIndexed { i, u -> if (!u) corrupt("blob $i is never referenced") }

        val columns = Records.applyAll(records, states, storedBiomes, defaultRef, h.hasDefaultBiome)
        return World(h.blockVersion, meta.settings, meta.userData, states, biomeNames, columns)
    }

    public fun readStructure(path: Path, options: DecodeOptions = DecodeOptions.DEFAULT): Structure =
        readStructure(Files.readAllBytes(path), options)

    /** Decodes a solid structure file (format.md §4). */
    public fun readStructure(file: ByteArray, options: DecodeOptions = DecodeOptions.DEFAULT): Structure {
        val f = Frame.parse(file)
        if (f.header.kind != FileKind.STRUCTURE) corrupt("file kind ${f.header.kind.code} is not a structure")
        val h = f.header
        if (h.flags and Flags.UNCOMPRESSED.inv() != 0) corrupt("flags 0x%08x are not valid for a structure".format(h.flags))
        val r = Frame.decompressBody(h, file, f.storedOffset, f.storedLength)
        val meta = readMetaBlobs(r, h)
        if (meta.settings.isNotEmpty()) corrupt("structure metadata must contain only user data")
        val states = Palettes.readBlockPalette(r, h.blockVersion)
        val biomeNames = Palettes.readBiomePalette(r)
        if (biomeNames.isNotEmpty()) corrupt("structure biome palette must be empty")
        val blobs = Blobs.readTable(r)

        val size = IntArray(3)
        for (i in 0..2) {
            val v = r.uvarint()
            if (v <= 0 || v > Limits.MAX_STRUCTURE_AXIS) corrupt("invalid structure size component $v")
            size[i] = v.toInt()
        }
        val nx = (size[0].toLong() + 15) / 16
        val ny = (size[1].toLong() + 15) / 16
        val nz = (size[2].toLong() + 15) / 16
        val cellCountLong = nx * ny * nz
        if (cellCountLong > Limits.MAX_STRUCTURE_CELLS) corrupt("structure has $cellCountLong cells")
        val cellCount = cellCountLong.toInt()
        if ((cellCount + 7) / 8 > r.remaining) corrupt("structure presence bitset does not fit")

        val origins = IntArray(3)
        for (i in 0..2) origins[i] = r.svarint32("structure origin")
        val used = BooleanArray(blobs.size)
        val next = IntArray(1)
        val source = BlobSource(blobs, used, next)
        val presence = r.bitset(cellCount)
        val cells = arrayOfNulls<Section>(cellCount)
        repeat(cellCount) { cell ->
            if (!presence.bit(cell)) return@repeat
            val layerN = r.count(Limits.MAX_LAYERS, "layer")
            if (layerN == 0) corrupt("cell $cell is present but declares no layers")
            val layers = ArrayList<Storage>(layerN)
            repeat(layerN) {
                val blob = source.next(r)
                for (ref in blob.refs) if (ref < 0 || ref >= states.size) corrupt("block palette reference $ref out of range")
                val storage = blob.storage()
                layers.add(storage)
            }
            val last = layers.last()
            if (last.isUniform && states[last.palette[0]].isAir) corrupt("cell $cell ends in an all-air layer")
            cells[cell] = Section(layers)
        }
        used.forEachIndexed { i, isUsed -> if (!isUsed) corrupt("blob $i is never referenced") }

        val beN = r.count(Limits.MAX_PER_CHUNK, "block entity")
        val blockEntities = ArrayList<BlockEntity>(minOf(beN, Limits.MAX_PREALLOC))
        var prevY = Long.MIN_VALUE
        var prevZ = -1
        var prevX = -1
        repeat(beN) {
            val x = r.uvarint().also { if (it >= size[0]) corrupt("block entity x $it outside structure") }.toInt()
            val y = r.uvarint().also { if (it >= size[1]) corrupt("block entity y $it outside structure") }.toInt()
            val z = r.uvarint().also { if (it >= size[2]) corrupt("block entity z $it outside structure") }.toInt()
            if (y.toLong() < prevY || (y.toLong() == prevY && (z < prevZ || (z == prevZ && x <= prevX)))) {
                corrupt("structure block entities are out of order or repeated")
            }
            prevY = y.toLong(); prevZ = z; prevX = x
            val nbt = r.blob()
            if (nbt.isNotEmpty()) wrapNbt("block entity") { Nbt.validate(nbt) }
            blockEntities.add(BlockEntity(x, y, z, nbt))
        }
        val entN = r.count(Limits.MAX_PER_CHUNK, "entity")
        val entities = ArrayList<ByteArray>(minOf(entN, Limits.MAX_PREALLOC))
        repeat(entN) {
            val nbt = r.blob()
            if (nbt.isNotEmpty()) wrapNbt("entity") { Nbt.validate(nbt) }
            entities.add(nbt)
        }
        if (r.remaining != 0) corrupt("${r.remaining} trailing bytes after structure record")
        return Structure(
            h.blockVersion,
            meta.userData,
            states,
            size[0],
            size[1],
            size[2],
            origins[0],
            origins[1],
            origins[2],
            cells,
            blockEntities,
            entities
        )
    }

    /**
     * Every block state in a solid file's palette, resolved against nothing: what a world uses
     * and what a registry can load are different questions, and only the second needs a registry.
     */
    public fun readBlockStates(file: ByteArray): List<BlockState> {
        val f = Frame.parse(file)
        val r = Frame.decompressBody(f.header, file, f.storedOffset, f.storedLength)
        readMetaBlobs(r, f.header)
        return Palettes.readBlockPalette(r, f.header.blockVersion)
    }

    internal class MetaBlobs(val settings: ByteArray, val userData: ByteArray, val stats: ByteArray?)

    internal fun readMetaBlobs(r: ByteReader, h: PileHeader): MetaBlobs {
        val settings = r.blob()
        if (settings.isNotEmpty()) wrapNbt("settings") { Nbt.validate(settings) }
        val userData = r.blob()
        var stats: ByteArray? = null
        if (h.hasStats) {
            stats = r.blob()
            if (stats.isNotEmpty()) wrapNbt("stats") { Nbt.validate(stats) }
            Schema.checkStats(stats)
        }
        Schema.checkSettings(settings)
        return MetaBlobs(settings, userData, stats)
    }

    private inline fun wrapNbt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CorruptFileException) {
            throw CorruptFileException("$what: ${e.message}", e)
        }
    }
}
