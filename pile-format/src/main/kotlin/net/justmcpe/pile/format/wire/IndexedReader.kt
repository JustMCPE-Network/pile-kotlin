package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*

/**
 * Reader for indexed mode (format.md §5).  The directory and palette frames
 * are loaded once; chunk frames remain random-access and are decoded on the
 * read call.  This is deliberately separate from the solid frame parser
 * because indexed footers carry non-zero directory/checkpoint fields.
 */
internal object IndexedReader {
    private data class Ref(val off: Long, val length: Int, val hash: Long)
    private data class Entry(val x: Int, val z: Int, val ref: Ref)
    private data class Footer(val at: Int, val hash: Long, val dirOff: Long, val dirLength: Long)

    fun read(file: ByteArray, options: DecodeOptions): World {
        if (file.size < Limits.HEADER_SIZE + Limits.FOOTER_SIZE) corrupt("file too short")
        if (file[6].toInt() and 0xFF != FileKind.WORLD.code || file[7].toInt() and 0xFF != 1) {
            throw UnsupportedModeException(file[7].toInt() and 0xFF)
        }
        val version = u16(file, 4)
        if (version != Limits.VERSION) throw UnsupportedVersionException(version)
        val flags = i32(file, 8)
        val blockVersion = i32(file, 12)
        if (flags and Flags.KNOWN.inv() != 0) throw UnknownFlagsException(flags)
        if (flags and (Flags.STATS or Flags.DEFAULT_BIOME) != 0 || flags ushr Flags.DEFAULT_BIOME_SHIFT != 0) {
            corrupt("flags 0x%08x are not valid for an indexed file".format(flags))
        }
        if (blockVersion == 0) corrupt("blockVersion is zero")
        val footer = findFooter(file)
        val footerAt = footer.at
        val wantHash = footer.hash
        val dirOff = footer.dirOff
        val dirLength = footer.dirLength
        val directoryStored = file.copyOfRange(dirOff.toInt(), (dirOff + dirLength).toInt())
        val footerControls = file.copyOfRange(footerAt + 8, footerAt + 44)
        if (XxHash.hash(file.copyOfRange(0, Limits.HEADER_SIZE), directoryStored, footerControls) != wantHash) {
            throw ChecksumMismatchException()
        }
        val directory = if (flags and Flags.UNCOMPRESSED != 0) directoryStored else ZstdCodec.decompress(
            directoryStored,
            0,
            directoryStored.size
        )
        val dr = ByteReader(directory)
        val dirKind = dr.u8();
        val dirMode = dr.u8();
        val dirFlags = dr.i32();
        val dirVersion = dr.i32()
        if (dirKind != FileKind.WORLD.code || dirMode != 1) corrupt("directory is not an indexed world")
        if (dirFlags != flags || dirVersion != blockVersion) corrupt("directory prologue disagrees with header")
        if (dirFlags and (Flags.STATS or Flags.DEFAULT_BIOME) != 0) corrupt("invalid indexed directory flags")

        val metaRef = readRef(dr, file, footerAt)
        val dictRef = readRef(dr, file, footerAt)
        val dictionary = if (dictRef.length == 0) null else frame(file, dictRef, flags, null)
        val metaBytes = if (metaRef.length == 0) ByteArray(0) else frame(file, metaRef, flags, dictionary)
        val mr = ByteReader(metaBytes)
        val meta = if (metaBytes.isEmpty()) PileReader.MetaBlobs(
            ByteArray(0),
            ByteArray(0),
            null
        ) else PileReader.readMetaBlobs(mr, PileHeader(FileKind.WORLD, flags, blockVersion)).also {
            if (mr.remaining != 0) corrupt("trailing bytes in indexed metadata")
        }

        val blockSegments = readRefs(dr, file, footerAt, "block palette segment")
        val biomeSegments = readRefs(dr, file, footerAt, "biome palette segment")
        val states = ArrayList<BlockState>()
        for (ref in blockSegments) {
            val body = frame(file, ref, flags, dictionary)
            val sr = ByteReader(body)
            val segmentVersion = sr.i32()
            val segment = Palettes.readBlockPalette(sr, segmentVersion)
            if (segment.isEmpty()) corrupt("empty block palette segment")
            if (sr.remaining != 0) corrupt("trailing bytes in block palette segment")
            states.addAll(segment)
        }
        val biomes = ArrayList<String>()
        for (ref in biomeSegments) {
            val body = frame(file, ref, flags, dictionary)
            val sr = ByteReader(body)
            val segment = Palettes.readBiomePalette(sr)
            if (segment.isEmpty()) corrupt("empty biome palette segment")
            biomes.addAll(segment)
            if (sr.remaining != 0) corrupt("trailing bytes in biome palette segment")
        }
        var defaultRef = biomes.indexOf(PileReader.PLAINS)
        if (defaultRef < 0) {
            biomes.add(PileReader.PLAINS); defaultRef = biomes.lastIndex
        }

        val chunkN = dr.count(Limits.MAX_CHUNKS, "directory chunk")
        val entries = ArrayList<Entry>(minOf(chunkN, Limits.MAX_PREALLOC))
        var x = 0L;
        var z = 0L;
        var off = 0L;
        var prevKey = 0L
        repeat(chunkN) {
            x += dr.svarint(); z += dr.svarint(); off += dr.svarint()
            if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) corrupt(
                "indexed chunk position out of range"
            )
            val length = dr.uvarint()
            val hash = dr.u64()
            if (length == 0L || length > Limits.MAX_BODY || off < Limits.HEADER_SIZE || off + length > footerAt) corrupt(
                "indexed chunk frame out of bounds"
            )
            val key = Morton.key(x.toInt(), z.toInt())
            if (entries.isNotEmpty() && Morton.compare(key, prevKey) <= 0) corrupt("indexed directory is out of order")
            prevKey = key
            entries.add(Entry(x.toInt(), z.toInt(), Ref(off, length.toInt(), hash)))
        }
        if (dr.remaining != 0) corrupt("${dr.remaining} trailing bytes in indexed directory")

        val used = DecodeBudget(options.ceiling)
        val columns = ArrayList<Column>(entries.size)
        for (entry in entries) {
            used.chargeColumns(1)
            val stored = file.copyOfRange(entry.ref.off.toInt(), entry.ref.off.toInt() + entry.ref.length)
            if (XxHash.hash(stored) != entry.ref.hash) throw ChecksumMismatchException()
            val body = if (flags and Flags.UNCOMPRESSED != 0) stored else ZstdCodec.decompress(
                stored,
                0,
                stored.size,
                dictionary
            )
            val rr = ByteReader(body)
            val raw = Records.parse(rr, InlineBlobSource(), flags and Flags.STORE_LIGHT != 0, entry.x, entry.z, used)
            if (rr.remaining != 0) corrupt("${rr.remaining} trailing bytes in indexed chunk record")
            columns.add(Records.apply(raw, states, biomes.size, defaultRef, false))
        }
        return World(blockVersion, meta.settings, meta.userData, states, biomes, columns)
    }

    /** Adopts the newest complete checkpoint, so bytes torn after a valid footer are harmless. */
    private fun findFooter(file: ByteArray): Footer {
        val magic = byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())
        for (at in file.size - Limits.FOOTER_SIZE downTo Limits.HEADER_SIZE) {
            if (!file.copyOfRange(at + 40, at + 44).contentEquals(magic)) continue
            val dirOff = u64(file, at + 8);
            val dirLength = u64(file, at + 16)
            if (dirOff < Limits.HEADER_SIZE || dirLength == 0L || dirLength > Limits.MAX_BODY || dirOff + dirLength > at) continue
            val previous = u64(file, at + 32)
            if (previous != 0L && (previous < Limits.HEADER_SIZE || previous >= at)) continue
            val stored = file.copyOfRange(dirOff.toInt(), (dirOff + dirLength).toInt())
            val controls = file.copyOfRange(at + 8, at + 44)
            if (XxHash.hash(file.copyOfRange(0, Limits.HEADER_SIZE), stored, controls) == u64(file, at)) {
                return Footer(at, u64(file, at), dirOff, dirLength)
            }
        }
        corrupt("no valid indexed footer found")
    }

    private fun readRefs(r: ByteReader, file: ByteArray, footerAt: Int, what: String): List<Ref> {
        val n = r.count(1 shl 20, what)
        val refs = ArrayList<Ref>(minOf(n, Limits.MAX_PREALLOC))
        repeat(n) {
            val ref = readRef(r, file, footerAt)
            if (ref.length == 0) corrupt("$what has zero length")
            if (refs.isNotEmpty() && ref.off <= refs.last().off) corrupt("$what references are not ascending")
            refs.add(ref)
        }
        return refs
    }

    private fun readRef(r: ByteReader, file: ByteArray, footerAt: Int): Ref {
        val off = r.uvarint();
        val length = r.uvarint();
        val hash = r.u64()
        if (length == 0L) {
            if (off != 0L || hash != 0L) corrupt("absent indexed frame reference is not zero")
            return Ref(0, 0, 0)
        }
        if (off < Limits.HEADER_SIZE || length > Limits.MAX_BODY || off + length > footerAt) corrupt("indexed frame reference out of bounds")
        val bytes = file.copyOfRange(off.toInt(), (off + length).toInt())
        if (XxHash.hash(bytes) != hash) throw ChecksumMismatchException()
        return Ref(off, length.toInt(), hash)
    }

    private fun frame(file: ByteArray, ref: Ref, flags: Int, dictionary: ByteArray?): ByteArray =
        if (flags and Flags.UNCOMPRESSED != 0) file.copyOfRange(ref.off.toInt(), ref.off.toInt() + ref.length) else {
            val stored = file.copyOfRange(ref.off.toInt(), ref.off.toInt() + ref.length)
            ZstdCodec.decompress(stored, 0, stored.size, dictionary)
        }

    private fun u16(b: ByteArray, p: Int): Int = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun i32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8) or ((b[p + 2].toInt() and 255) shl 16) or (b[p + 3].toInt() shl 24)

    private fun u64(b: ByteArray, p: Int): Long {
        var out = 0L
        for (i in 7 downTo 0) out = (out shl 8) or (b[p + i].toLong() and 255)
        return out
    }
}
