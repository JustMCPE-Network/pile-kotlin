package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes indexed files (format.md §5): one record frame per column, palettes in first-seen order,
 * a directory frame and a checkpoint footer. Indexed bytes are history-dependent, never canonical.
 */
internal object IndexedEncoder {
    private val FOOTER_MAGIC = byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())

    private class Ref(val off: Long, val length: Int, val hash: Long) {
        companion object {
            val ABSENT = Ref(0, 0, 0)
        }
    }

    fun encode(world: World, compression: Compression, storeLightLayout: Boolean? = null): ByteArray {
        if (world.blockVersion == 0) throw InvalidContentException("blockVersion is zero")
        WorldEncoder.checkMeta(world)
        val columns = world.columns.sortedWith { a, b -> Morton.compare(Morton.key(a.x, a.z), Morton.key(b.x, b.z)) }
        for (i in 1 until columns.size) {
            if (columns[i - 1].x == columns[i].x && columns[i - 1].z == columns[i].z) {
                throw InvalidContentException("duplicate chunk (${columns[i].x},${columns[i].z})")
            }
        }
        val blocks = BlockPaletteBuilder(world.blockVersion)
        val biomes = BiomePaletteBuilder()
        val prepared = columns.map { RecordEncoder.prepare(it, world, blocks, biomes, storeLight = true) }
        // In indexed mode the flag is a layout decision fixed at creation (format.md 2.3), so a
        // caller keeping a file's layout passes it explicitly.
        val storeLight = storeLightLayout ?: prepared.any { c -> c.light?.any { it != null } == true }
        var flags = if (storeLight) Flags.STORE_LIGHT else 0
        if (compression == Compression.NONE) flags = flags or Flags.UNCOMPRESSED

        val blockPalette = ByteWriter()
        val blockRemap = blocks.finalizeFirstSeen(blockPalette)
        val biomePalette = ByteWriter()
        val biomeRemap = biomes.finalizeFirstSeen(biomePalette)

        val frames = ArrayList<ByteArray>()
        var offset = Limits.HEADER_SIZE.toLong()
        fun frame(body: ByteArray): Ref {
            if (body.size > Limits.MAX_BODY) throw InvalidContentException("indexed frame is ${body.size} bytes, limit ${Limits.MAX_BODY}")
            val stored = if (compression == Compression.NONE) body else ZstdCodec.compress(body, compression)
            val ref = Ref(offset, stored.size, XxHash.hash(stored))
            frames.add(stored)
            offset += stored.size
            return ref
        }

        val blockRef =
            if (blocks.size == 0) Ref.ABSENT else frame(ByteWriter().apply { i32(world.blockVersion); raw(blockPalette.toByteArray()) }
                .toByteArray())
        val biomeRef = if (biomes.size == 0) Ref.ABSENT else frame(biomePalette.toByteArray())
        val metaRef = if (world.settings.isEmpty() && world.userData.isEmpty()) Ref.ABSENT else {
            frame(ByteWriter().apply { blob(world.settings); blob(world.userData) }.toByteArray())
        }
        val entries = prepared.map { c ->
            val record = ByteWriter()
            RecordEncoder.encode(record, c, blockRemap, biomeRemap, -1, storeLight) { w, blob -> w.raw(blob) }
            Triple(c.x, c.z, frame(record.toByteArray()))
        }

        val directory = ByteWriter()
        directory.u8(FileKind.WORLD.code)
        directory.u8(1)
        directory.i32(flags)
        directory.i32(world.blockVersion)
        writeRef(directory, metaRef)
        writeRef(directory, Ref.ABSENT)
        directory.uvarint(if (blockRef === Ref.ABSENT) 0 else 1)
        if (blockRef !== Ref.ABSENT) writeRef(directory, blockRef)
        directory.uvarint(if (biomeRef === Ref.ABSENT) 0 else 1)
        if (biomeRef !== Ref.ABSENT) writeRef(directory, biomeRef)
        directory.uvarint(entries.size)
        var px = 0
        var pz = 0
        var poff = 0L
        for ((x, z, ref) in entries) {
            directory.svarint(x.toLong() - px)
            directory.svarint(z.toLong() - pz)
            directory.svarint(ref.off - poff)
            directory.uvarint(ref.length)
            directory.u64(ref.hash)
            px = x
            pz = z
            poff = ref.off
        }
        val directoryStored = if (compression == Compression.NONE) directory.toByteArray() else ZstdCodec.compress(
            directory.toByteArray(),
            compression
        )

        val header = ByteWriter().apply {
            raw(byteArrayOf('P'.code.toByte(), 'I'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte()))
            u16(Limits.VERSION)
            u8(FileKind.WORLD.code)
            u8(1)
            i32(flags)
            i32(world.blockVersion)
        }.toByteArray()
        val controls = ByteWriter().apply {
            u64(offset)
            u64(directoryStored.size.toLong())
            u64(1)
            u64(0)
        }.toByteArray()
        val checkpoint = XxHash.hash(header, directoryStored, controls, FOOTER_MAGIC)
        val out = ByteWriter(Limits.HEADER_SIZE + frames.sumOf { it.size } + directoryStored.size + Limits.FOOTER_SIZE)
        out.raw(header)
        frames.forEach(out::raw)
        out.raw(directoryStored)
        out.u64(checkpoint)
        out.raw(controls)
        out.raw(FOOTER_MAGIC)
        return out.toByteArray()
    }

    /**
     * Appends the world as a new checkpoint: every column becomes a fresh record frame and the old
     * checkpoint's frames become garbage, recoverable through prevFooter. Palettes and metadata must
     * be unchanged; a caller whose palette grew rewrites with [encode] instead.
     */
    fun append(path: Path, world: World) {
        val old = Files.readAllBytes(path)
        val base = PileReader.readWorld(old)
        require(base.blockVersion == world.blockVersion && base.blockStates == world.blockStates && base.biomes == world.biomes) {
            "append requires unchanged palettes"
        }
        require(base.settings.contentEquals(world.settings) && base.userData.contentEquals(world.userData)) {
            "append requires unchanged metadata"
        }
        require(old.size >= Limits.HEADER_SIZE + Limits.FOOTER_SIZE && old[7].toInt() and 0xFF == 1) { "not an indexed file" }
        val flags = ByteReader(old, 8).i32()
        val compressed = flags and Flags.UNCOMPRESSED == 0
        val footerAt = old.size - Limits.FOOTER_SIZE
        val fr = ByteReader(old, footerAt + 8)
        val dirOff = fr.u64()
        val dirLen = fr.u64()
        val generation = fr.u64()
        val directoryStored = old.copyOfRange(dirOff.toInt(), (dirOff + dirLen).toInt())
        val directory =
            if (compressed) ZstdCodec.decompress(directoryStored, 0, directoryStored.size) else directoryStored
        val dr = ByteReader(directory)
        require(dr.u8() == FileKind.WORLD.code && dr.u8() == 1 && dr.i32() == flags && dr.i32() == world.blockVersion) {
            "directory prologue mismatch"
        }
        val metaRef = readRef(dr)
        val dictRef = readRef(dr)
        require(dictRef.length == 0) { "append does not support shared dictionaries" }
        val blockRefs = List(dr.count(Limits.MAX_PALETTE, "segment")) { readRef(dr) }
        val biomeRefs = List(dr.count(Limits.MAX_PALETTE, "segment")) { readRef(dr) }

        val blocks = BlockPaletteBuilder(world.blockVersion)
        val biomes = BiomePaletteBuilder()
        val columns = world.columns.sortedWith { a, b -> Morton.compare(Morton.key(a.x, a.z), Morton.key(b.x, b.z)) }
        val collections = RecordEncoder.prepareCollectionsAll(columns)
        val prepared = columns.mapIndexed { i, c ->
            RecordEncoder.prepare(
                c,
                world,
                blocks,
                biomes,
                storeLight = true,
                collections = collections[i]
            )
        }
        // Records reference the palettes the file already carries, so build ids map back to the
        // world's palette indices rather than to a fresh first-seen order.
        val stateIndex = HashMap<BlockState, Int>(world.blockStates.size * 2)
        world.blockStates.forEachIndexed { i, state -> stateIndex.putIfAbsent(state, i) }
        val blockRemap = IntArray(blocks.size) { build -> stateIndex.getValue(blocks.state(build)) }
        val biomeIndex = HashMap<String, Int>(world.biomes.size * 2)
        world.biomes.forEachIndexed { i, name -> biomeIndex.putIfAbsent(name, i) }
        val biomeRemap = IntArray(biomes.size) { build -> biomeIndex.getValue(biomes.name(build)) }
        val storeLight = flags and Flags.STORE_LIGHT != 0

        var offset = old.size.toLong()
        val out = ByteWriter(old.size + (64 shl 10))
        out.raw(old)
        val entries = ArrayList<Triple<Int, Int, Ref>>(prepared.size)
        for (c in prepared) {
            val record = ByteWriter()
            RecordEncoder.encode(record, c, blockRemap, biomeRemap, -1, storeLight) { w, blob -> w.raw(blob) }
            val body = record.toByteArray()
            val stored = if (compressed) ZstdCodec.compress(body, Compression.DEFAULT) else body
            entries.add(Triple(c.x, c.z, Ref(offset, stored.size, XxHash.hash(stored))))
            out.raw(stored)
            offset += stored.size
        }

        val next = ByteWriter()
        next.u8(FileKind.WORLD.code)
        next.u8(1)
        next.i32(flags)
        next.i32(world.blockVersion)
        writeRef(next, metaRef)
        writeRef(next, dictRef)
        next.uvarint(blockRefs.size)
        blockRefs.forEach { writeRef(next, it) }
        next.uvarint(biomeRefs.size)
        biomeRefs.forEach { writeRef(next, it) }
        next.uvarint(entries.size)
        var px = 0
        var pz = 0
        var poff = 0L
        for ((x, z, ref) in entries) {
            next.svarint(x.toLong() - px)
            next.svarint(z.toLong() - pz)
            next.svarint(ref.off - poff)
            next.uvarint(ref.length)
            next.u64(ref.hash)
            px = x
            pz = z
            poff = ref.off
        }
        val nextStored =
            if (compressed) ZstdCodec.compress(next.toByteArray(), Compression.DEFAULT) else next.toByteArray()
        val controls = ByteWriter().apply {
            u64(offset)
            u64(nextStored.size.toLong())
            u64(generation + 1)
            u64(footerAt.toLong())
        }.toByteArray()
        val checkpoint = XxHash.hash(old.copyOfRange(0, Limits.HEADER_SIZE), nextStored, controls, FOOTER_MAGIC)
        out.raw(nextStored)
        out.u64(checkpoint)
        out.raw(controls)
        out.raw(FOOTER_MAGIC)
        Files.write(path, out.toByteArray())
    }

    private fun writeRef(w: ByteWriter, ref: Ref) {
        w.uvarint(ref.off)
        w.uvarint(ref.length.toLong())
        w.u64(ref.hash)
    }

    private fun readRef(r: ByteReader): Ref {
        val off = r.uvarint()
        val length = r.uvarint()
        val hash = r.u64()
        return Ref(off, length.toInt(), hash)
    }
}
