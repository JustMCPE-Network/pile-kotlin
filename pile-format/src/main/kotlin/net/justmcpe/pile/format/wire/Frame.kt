package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*

internal object Frame {
    private val HEADER_MAGIC = byteArrayOf('P'.code.toByte(), 'I'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte())
    private val FOOTER_MAGIC = byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())

    class Parsed(val header: PileHeader, val storedOffset: Int, val storedLength: Int)

    /** Validates header, footer and checkpoint hash; returns the header and the stored body's bounds. */
    fun parse(file: ByteArray): Parsed {
        if (file.size < Limits.HEADER_SIZE + Limits.FOOTER_SIZE) corrupt("file too short (${file.size} bytes)")
        if (!matches(file, 0, HEADER_MAGIC)) corrupt("bad header magic")
        val r = ByteReader(file, 4)
        val version = r.u16()
        if (version != Limits.VERSION) throw UnsupportedVersionException(version)
        val kind = r.u8()
        val mode = r.u8()
        val flags = r.i32()
        val blockVersion = r.i32()
        if (kind > FileKind.STRUCTURE.code) corrupt("file kind $kind is not defined")
        if (blockVersion == 0) corrupt("blockVersion is zero, which means \"the palette's own version\" and is not a version")
        if (flags and Flags.KNOWN.inv() != 0) throw UnknownFlagsException(flags)
        if (flags and Flags.DEFAULT_BIOME == 0 && flags ushr Flags.DEFAULT_BIOME_SHIFT != 0) {
            corrupt("default biome reference set without its flag")
        }
        if (mode != 0) throw UnsupportedModeException(mode)

        val footer = file.size - Limits.FOOTER_SIZE
        if (!matches(file, footer + 40, FOOTER_MAGIC)) corrupt("bad footer magic")
        val fr = ByteReader(file, footer)
        val wantHash = fr.u64()
        for ((off, name) in listOf(
            8 to "directory offset",
            16 to "directory length",
            24 to "generation",
            32 to "previous footer"
        )) {
            val v = ByteReader(file, footer + off).u64()
            if (v != 0L) corrupt("solid footer $name must be zero, got $v")
        }
        if (checkpointHash(file, Limits.HEADER_SIZE, footer - Limits.HEADER_SIZE) != wantHash) {
            throw ChecksumMismatchException()
        }
        return Parsed(
            PileHeader(if (kind == 0) FileKind.WORLD else FileKind.STRUCTURE, flags, blockVersion),
            Limits.HEADER_SIZE,
            footer - Limits.HEADER_SIZE,
        )
    }

    /** xxHash64 over header || stored body || footer bytes 8..44, all read from one contiguous file image. */
    private fun checkpointHash(file: ByteArray, bodyOff: Int, bodyLen: Int): Long {
        val h = XxHash.Streaming()
        h.update(file, 0, Limits.HEADER_SIZE)
        h.update(file, bodyOff, bodyLen)
        val footer = bodyOff + bodyLen
        h.update(file, footer + 8, Limits.FOOTER_SIZE - 8)
        return h.digest()
    }

    /** Assembles header, stored body and footer into one file image, hashing as format.md §2.4 says. */
    fun assemble(kind: FileKind, flags: Int, blockVersion: Int, stored: ByteArray): ByteArray {
        val out = ByteWriter(Limits.HEADER_SIZE + stored.size + Limits.FOOTER_SIZE)
        out.raw(HEADER_MAGIC)
        out.u16(Limits.VERSION)
        out.u8(kind.code)
        out.u8(0)
        out.i32(flags)
        out.i32(blockVersion)
        out.raw(stored)
        val hashAt = out.reserve(8)
        repeat(4) { out.u64(0) }
        out.raw(FOOTER_MAGIC)
        val image = out.toByteArray()
        val hash = checkpointHash(image, Limits.HEADER_SIZE, stored.size)
        var x = hash
        for (i in 0 until 8) {
            image[hashAt + i] = x.toByte()
            x = x ushr 8
        }
        return image
    }

    fun decompressBody(header: PileHeader, file: ByteArray, off: Int, len: Int): ByteReader {
        if (header.uncompressed) {
            if (len > Limits.MAX_BODY) corrupt("body is $len bytes, limit ${Limits.MAX_BODY}")
            return ByteReader(file, off, off + len)
        }
        return ByteReader(ZstdCodec.decompress(file, off, len))
    }

    private fun matches(b: ByteArray, off: Int, magic: ByteArray): Boolean {
        for (i in magic.indices) if (b[off + i] != magic[i]) return false
        return true
    }
}
