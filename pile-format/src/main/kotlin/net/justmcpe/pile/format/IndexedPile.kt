package net.justmcpe.pile.format

import net.justmcpe.pile.format.IndexedPile.Companion.DICT_MIN_BYTES
import net.justmcpe.pile.format.IndexedPile.Companion.DICT_MIN_SAMPLES
import net.justmcpe.pile.format.wire.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * An open indexed file (format.md §5): the directory and palettes stay resident, columns are
 * decoded one frame at a time, stores append record frames and a checkpoint makes them durable.
 * This is the counterpart of the reference implementation's `IndexedWorld` — the memory a handle
 * costs follows the directory size, never the world.
 *
 * All methods are thread-safe. Reads use positional I/O; writers append and are serialised.
 */
public class IndexedPile private constructor(
    private val path: Path,
    private var channel: FileChannel,
    private val readOnly: Boolean,
    private val options: DecodeOptions,
    level: Compression,
) : AutoCloseable {
    /** The zstd effort every frame this handle writes uses, as upstream carries its open options. */
    private val level: Compression = if (level == Compression.NONE) Compression.DEFAULT else level

    private class Ref(val off: Long, val length: Int, val hash: Long)

    private val lock = Any()
    private var closed = false

    private lateinit var header: ByteArray
    public var flags: Int = 0
        private set
    public var blockVersion: Int = 0
        private set
    private var generation = 0L
    private var footerAt = 0L
    private var dirLen = 0L
    private var fileEnd = 0L
    private var garbageBytes = 0L

    private var metaRef: Ref? = null
    private var dictRef: Ref? = null
    private var dictionary: ByteArray? = null
    private var blockSegments = ArrayList<Ref>()
    private var biomeSegments = ArrayList<Ref>()
    private var settingsBytes = ByteArray(0)
    private var userDataBytes = ByteArray(0)
    private var metaDirty = false

    private val states = ArrayList<BlockState>()
    private val stateIndex = HashMap<BlockState, Int>()
    private var persistedStates = 0
    private val biomeNames = ArrayList<String>()
    private val biomeIndex = HashMap<String, Int>()
    private var persistedBiomes = 0
    private var fallbackBiome = 0

    private val directory = LinkedHashMap<Long, Ref>()
    private var dirDirty = false
    private var directoryBudget = 0L

    public val storeLight: Boolean get() = flags and Flags.STORE_LIGHT != 0
    public val hasDictionary: Boolean get() = synchronized(lock) { dictionary != null }
    private val compressed: Boolean get() = flags and Flags.UNCOMPRESSED == 0

    public val blockStates: List<BlockState> get() = synchronized(lock) { states.toList() }
    public val biomes: List<String> get() = synchronized(lock) { biomeNames.toList() }
    public val settings: ByteArray get() = synchronized(lock) { settingsBytes.copyOf() }
    public val userData: ByteArray get() = synchronized(lock) { userDataBytes.copyOf() }

    public fun positions(): List<IntArray> = synchronized(lock) {
        directory.keys.map { intArrayOf((it shr 32).toInt(), it.toInt()) }
    }

    public val columnCount: Int get() = synchronized(lock) { directory.size }

    public fun contains(x: Int, z: Int): Boolean = synchronized(lock) { directory.containsKey(key(x, z)) }

    /** The ratio of superseded bytes to file size; [compact] rewrites them away. */
    public fun garbageRatio(): Double = synchronized(lock) {
        if (fileEnd == 0L) 0.0 else garbageBytes.toDouble() / fileEnd
    }

    /** Decodes one column, or null when the directory does not name it. */
    public fun column(x: Int, z: Int): Column? {
        val ref: Ref
        val myStates: List<BlockState>
        val biomeCount: Int
        val fallback: Int
        synchronized(lock) {
            checkOpen()
            ref = directory[key(x, z)] ?: return null
            myStates = ArrayList(states)
            biomeCount = biomeNames.size
            fallback = fallbackBiome
        }
        val stored = readAt(ref.off, ref.length)
        if (XxHash.hash(stored) != ref.hash) throw ChecksumMismatchException()
        val body =
            if (compressed) ZstdCodec.decompress(stored, 0, stored.size, dictionary, Limits.MAX_FRAME) else stored
        val budget = DecodeBudget(maxOf(options.ceiling - directoryBudget, Limits.COLUMN_COST))
        budget.chargeColumns(1)
        val r = ByteReader(body)
        val raw = Records.parse(r, InlineBlobSource(), storeLight, x, z, budget)
        if (r.remaining != 0) corrupt("${r.remaining} trailing bytes in record frame ($x,$z)")
        return Records.apply(raw, myStates, biomeCount, fallback, false)
    }

    /**
     * Appends [column] as a new record frame; the previous frame for that position becomes garbage.
     * References index [columnStates] and [columnBiomes]; states the file's palettes lack are queued
     * and written as delta segments by the next [checkpoint].
     */
    public fun store(column: Column, columnStates: List<BlockState>, columnBiomes: List<String>): Unit =
        synchronized(lock) {
            checkOpen()
            checkWritable()
            val blocks = BlockPaletteBuilder(blockVersion)
            val biomesBuilder = BiomePaletteBuilder()
            val view = World(blockVersion, ByteArray(0), ByteArray(0), columnStates, columnBiomes, emptyList())
            val prepared = RecordEncoder.prepare(column, view, blocks, biomesBuilder, storeLight)
            val blockRemap = IntArray(blocks.size) { build ->
                val state = blocks.state(build)
                stateIndex.getOrPut(state) {
                    states.add(state)
                    states.size - 1
                }
            }
            val biomeRemap = IntArray(biomesBuilder.size) { build ->
                val name = biomesBuilder.name(build)
                biomeIndex.getOrPut(name) {
                    biomeNames.add(name)
                    biomeNames.size - 1
                }
            }
            val record = ByteWriter()
            RecordEncoder.encode(record, prepared, blockRemap, biomeRemap, -1, storeLight) { w, blob -> w.raw(blob) }
            val body = record.toByteArray()
            if (body.size > Limits.MAX_FRAME) throw InvalidContentException("record frame is ${body.size} bytes, limit ${Limits.MAX_FRAME}")
            val stored = if (compressed) ZstdCodec.compress(body, level, dictionary) else body
            val ref = Ref(fileEnd, stored.size, XxHash.hash(stored))
            writeAt(ref.off, stored)
            fileEnd += stored.size
            directory.put(key(column.x, column.z), ref)?.let { garbageBytes += it.length }
            dirDirty = true
        }

    /** Drops a column from the next checkpoint's directory; its frame becomes garbage. */
    public fun delete(x: Int, z: Int): Unit = synchronized(lock) {
        checkOpen()
        checkWritable()
        directory.remove(key(x, z))?.let {
            garbageBytes += it.length
            dirDirty = true
        }
    }

    public fun setMeta(settings: ByteArray, userData: ByteArray): Unit = synchronized(lock) {
        checkOpen()
        checkWritable()
        if (settings.contentEquals(settingsBytes) && userData.contentEquals(userDataBytes)) return
        settingsBytes = settings.copyOf()
        userDataBytes = userData.copyOf()
        metaDirty = true
    }

    /**
     * Appends pending palette segments, a meta frame when metadata changed and a directory frame,
     * fsyncs, then writes the footer and fsyncs again: a footer never names frames that are not
     * durable, and a torn write loses at most the work since the previous checkpoint.
     */
    public fun checkpoint(): Unit = synchronized(lock) {
        checkOpen()
        checkWritable()
        checkpointLocked()
    }

    private fun checkpointLocked() {
        if (!dirDirty && !metaDirty && persistedStates == states.size && persistedBiomes == biomeNames.size) return

        if (states.size - persistedStates > 0) {
            val builder = BlockPaletteBuilder(blockVersion)
            for (i in persistedStates until states.size) builder.id(states[i]).also { builder.count(it) }
            val segment = ByteWriter()
            segment.i32(blockVersion)
            builder.finalizeFirstSeen(segment)
            blockSegments.add(appendFrame(segment.toByteArray()))
            persistedStates = states.size
        }
        if (biomeNames.size - persistedBiomes > 0) {
            val builder = BiomePaletteBuilder()
            for (i in persistedBiomes until biomeNames.size) builder.count(builder.id(biomeNames[i]), false)
            val segment = ByteWriter()
            builder.finalizeFirstSeen(segment)
            biomeSegments.add(appendFrame(segment.toByteArray()))
            persistedBiomes = biomeNames.size
        }
        if (metaDirty) {
            val meta = ByteWriter()
            meta.blob(settingsBytes)
            meta.blob(userDataBytes)
            metaRef?.let { garbageBytes += it.length }
            metaRef = appendFrame(meta.toByteArray())
            metaDirty = false
        }

        val dir = ByteWriter()
        dir.u8(FileKind.WORLD.code)
        dir.u8(1)
        dir.i32(flags)
        dir.i32(blockVersion)
        writeRef(dir, metaRef)
        writeRef(dir, dictRef)
        dir.uvarint(blockSegments.size)
        blockSegments.forEach { writeRef(dir, it) }
        dir.uvarint(biomeSegments.size)
        biomeSegments.forEach { writeRef(dir, it) }
        val ordered = directory.entries.sortedWith { a, b ->
            Morton.compare(
                Morton.key((a.key shr 32).toInt(), a.key.toInt()),
                Morton.key((b.key shr 32).toInt(), b.key.toInt())
            )
        }
        dir.uvarint(ordered.size)
        var px = 0
        var pz = 0
        var poff = 0L
        for ((k, ref) in ordered) {
            val x = (k shr 32).toInt()
            val z = k.toInt()
            dir.svarint(x.toLong() - px)
            dir.svarint(z.toLong() - pz)
            dir.svarint(ref.off - poff)
            dir.uvarint(ref.length.toLong())
            dir.u64(ref.hash)
            px = x
            pz = z
            poff = ref.off
        }
        val dirBody = dir.toByteArray()
        val dirStored = if (compressed) ZstdCodec.compress(dirBody, level) else dirBody
        val dirOff = fileEnd
        writeAt(dirOff, dirStored)
        channel.force(true)

        val controls = ByteWriter().apply {
            u64(dirOff)
            u64(dirStored.size.toLong())
            u64(generation + 1)
            u64(footerAt)
        }.toByteArray()
        val footer = ByteWriter().apply {
            u64(XxHash.hash(header, dirStored, controls, FOOTER_MAGIC))
            raw(controls)
            raw(FOOTER_MAGIC)
        }.toByteArray()
        val newFooterAt = dirOff + dirStored.size
        writeAt(newFooterAt, footer)
        channel.force(true)

        garbageBytes += dirLen + Limits.FOOTER_SIZE
        dirLen = dirStored.size.toLong()
        generation++
        footerAt = newFooterAt
        fileEnd = newFooterAt + Limits.FOOTER_SIZE
        dirDirty = false
    }

    /**
     * Rewrites the live records into a fresh garbage-free file, one column at a time in Morton
     * order, atomically renames it over this one and reopens. On a compressed file with enough
     * material a shared dictionary is trained first and every record, palette and meta frame of
     * the new file is compressed with it; a refused training simply compacts without one.
     */
    public fun compact(): Unit = synchronized(lock) {
        checkOpen()
        checkWritable()
        checkpointLocked()
        val keys = directory.keys.sortedWith { a, b ->
            Morton.compare(Morton.key((a shr 32).toInt(), a.toInt()), Morton.key((b shr 32).toInt(), b.toInt()))
        }
        val trained = if (compressed) trainDictionaryLocked(keys) else null
        val statesView = ArrayList(states)
        val biomesView = ArrayList(biomeNames)
        val temp = Files.createTempFile(path.toAbsolutePath().parent, path.fileName.toString(), ".compact")
        try {
            Files.deleteIfExists(temp)
            val fresh = create(temp, blockVersion, if (compressed) level else Compression.NONE, storeLight)
            try {
                trained?.let(fresh::installDictionary)
                for (k in keys) {
                    val column = columnLocked((k shr 32).toInt(), k.toInt())
                        ?: corrupt("directory entry vanished during compaction")
                    fresh.store(column, statesView, biomesView)
                }
                fresh.setMeta(settingsBytes, userDataBytes)
                fresh.checkpoint()
            } finally {
                fresh.close()
            }
            val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
            permissions?.let { Files.setPosixFilePermissions(temp, it) }
            channel.close()
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
        loadLocked()
    }

    /**
     * Samples the live records evenly across the world — Morton order clusters neighbours, so a
     * prefix would train on one corner — and trains when at least [DICT_MIN_SAMPLES] records give
     * [DICT_MIN_BYTES] of material, exactly the reference implementation's thresholds.
     */
    private fun trainDictionaryLocked(keys: List<Long>): ByteArray? {
        if (keys.size < DICT_MIN_SAMPLES) return null
        val stride = if (keys.size <= DICT_MAX_SAMPLES) 1 else (keys.size + DICT_MAX_SAMPLES - 1) / DICT_MAX_SAMPLES
        val samples = ArrayList<ByteArray>(minOf(keys.size, DICT_MAX_SAMPLES))
        var total = 0
        var i = 0
        while (i < keys.size && total < DICT_MAX_SAMPLE_BYTES && samples.size < DICT_MAX_SAMPLES) {
            val ref = directory.getValue(keys[i])
            val stored = readAt(ref.off, ref.length)
            if (XxHash.hash(stored) == ref.hash) {
                var body = ZstdCodec.decompress(stored, 0, stored.size, dictionary, Limits.MAX_FRAME)
                if (body.size > DICT_SAMPLE_BYTES) body = body.copyOf(DICT_SAMPLE_BYTES)
                samples.add(body)
                total += body.size
            }
            i += stride
        }
        if (samples.size < DICT_MIN_SAMPLES || total < DICT_MIN_BYTES) return null
        val trained = ZstdCodec.train(samples, DICT_TARGET_BYTES) ?: return null
        // A dictionary earns its place by shrinking the records it was trained on; content that
        // already compresses well can come out marginally larger with one, and a compaction must
        // never grow the file it was asked to shrink.
        var withDict = trained.size.toLong()
        var without = 0L
        for (sample in samples) {
            withDict += ZstdCodec.compress(sample, level, trained).size
            without += ZstdCodec.compress(sample, level).size
        }
        return if (withDict < without) trained else null
    }

    /**
     * Installs a trained dictionary into a fresh file: the dictionary frame itself is stored
     * without it (readers meet it before they can load it, format.md §5.1), every later frame is
     * compressed with it.
     */
    internal fun installDictionary(trained: ByteArray): Unit = synchronized(lock) {
        checkOpen()
        checkWritable()
        check(directory.isEmpty() && dictRef == null) { "a dictionary installs into a fresh file only" }
        if (trained.size > Limits.MAX_DICT) return
        dictRef = appendFrame(trained)
        dictionary = trained
        dirDirty = true
    }

    private fun columnLocked(x: Int, z: Int): Column? {
        val ref = directory[key(x, z)] ?: return null
        val stored = readAt(ref.off, ref.length)
        if (XxHash.hash(stored) != ref.hash) throw ChecksumMismatchException()
        val body =
            if (compressed) ZstdCodec.decompress(stored, 0, stored.size, dictionary, Limits.MAX_FRAME) else stored
        val budget = DecodeBudget(maxOf(options.ceiling - directoryBudget, Limits.COLUMN_COST))
        budget.chargeColumns(1)
        val r = ByteReader(body)
        val raw = Records.parse(r, InlineBlobSource(), storeLight, x, z, budget)
        if (r.remaining != 0) corrupt("${r.remaining} trailing bytes in record frame ($x,$z)")
        return Records.apply(raw, states, biomeNames.size, fallbackBiome, false)
    }

    /** Checkpoints when writable, compacts first when garbage passed half the file, then closes. */
    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        if (!readOnly) {
            if (garbageRatioLocked() > 0.5) compactLocked() else checkpointLocked()
        }
        closed = true
        channel.close()
    }

    private fun garbageRatioLocked(): Double = if (fileEnd == 0L) 0.0 else garbageBytes.toDouble() / fileEnd

    private fun compactLocked() {
        val wasClosed = closed
        closed = false
        try {
            compact()
        } finally {
            closed = wasClosed
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("indexed pile is closed")
    }

    private fun checkWritable() {
        if (readOnly) throw IllegalStateException("indexed pile is read-only")
    }

    private fun appendFrame(body: ByteArray): Ref {
        if (body.size > Limits.MAX_FRAME) throw InvalidContentException("indexed frame is ${body.size} bytes, limit ${Limits.MAX_FRAME}")
        val stored = if (compressed) ZstdCodec.compress(body, level, dictionary) else body
        val ref = Ref(fileEnd, stored.size, XxHash.hash(stored))
        writeAt(ref.off, stored)
        fileEnd += stored.size
        return ref
    }

    private fun writeRef(w: ByteWriter, ref: Ref?) {
        if (ref == null) {
            w.uvarint(0L)
            w.uvarint(0L)
            w.u64(0)
        } else {
            w.uvarint(ref.off)
            w.uvarint(ref.length.toLong())
            w.u64(ref.hash)
        }
    }

    private fun readAt(off: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var pos = off
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) corrupt("unexpected end of file at $pos")
            pos += n
        }
        return buf.array()
    }

    private fun writeAt(off: Long, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        var pos = off
        while (buf.hasRemaining()) pos += channel.write(buf, pos)
    }

    private fun loadLocked() {
        states.clear()
        stateIndex.clear()
        biomeNames.clear()
        biomeIndex.clear()
        directory.clear()
        blockSegments = ArrayList()
        biomeSegments = ArrayList()
        garbageBytes = 0
        metaDirty = false
        dirDirty = false

        val size = channel.size()
        if (size < Limits.HEADER_SIZE + Limits.FOOTER_SIZE) corrupt("file too short ($size bytes)")
        header = readAt(0, Limits.HEADER_SIZE)
        val hr = ByteReader(header)
        if (!header.copyOfRange(0, 4).contentEquals(HEADER_MAGIC)) corrupt("bad header magic")
        hr.take(4)
        val version = hr.u16()
        if (version != Limits.VERSION) throw UnsupportedVersionException(version)
        val kind = hr.u8()
        val mode = hr.u8()
        if (kind != FileKind.WORLD.code) corrupt("file kind $kind is not an indexed world")
        if (mode != 1) throw UnsupportedModeException(mode)
        flags = hr.i32()
        blockVersion = hr.i32()
        if (blockVersion == 0) corrupt("blockVersion is zero")
        if (flags and Flags.KNOWN.inv() != 0) throw UnknownFlagsException(flags)
        if (flags and (Flags.STATS or Flags.DEFAULT_BIOME) != 0 || flags ushr Flags.DEFAULT_BIOME_SHIFT != 0) {
            corrupt("flags 0x%08x are not valid for an indexed file".format(flags))
        }

        val adopted = adoptCheckpoint(size)
        footerAt = adopted.first
        val dirStored = adopted.second
        dirLen = dirStored.size.toLong()
        fileEnd = size
        garbageBytes = size - footerAt - Limits.FOOTER_SIZE

        val dirBody =
            if (compressed) ZstdCodec.decompress(dirStored, 0, dirStored.size, null, Limits.MAX_BODY) else dirStored
        val dr = ByteReader(dirBody)
        val dirKind = dr.u8()
        val dirMode = dr.u8()
        val dirFlags = dr.i32()
        val dirVersion = dr.i32()
        if (dirKind != FileKind.WORLD.code || dirMode != 1) corrupt("directory is not an indexed world")
        if (dirFlags != flags || dirVersion != blockVersion) corrupt("directory prologue disagrees with header")
        metaRef = readRefChecked(dr, "metadata frame")
        dictRef = readRefChecked(dr, "dictionary frame")
        dictionary = dictRef?.let { ref ->
            if (ref.length > Limits.MAX_DICT) corrupt("dictionary frame of ${ref.length} bytes, limit ${Limits.MAX_DICT}")
            val stored = readAt(ref.off, ref.length)
            if (XxHash.hash(stored) != ref.hash) throw ChecksumMismatchException()
            if (compressed) ZstdCodec.decompress(stored, 0, stored.size, null, Limits.MAX_DICT) else stored
        }
        metaRef?.let { ref ->
            val stored = readAt(ref.off, ref.length)
            if (XxHash.hash(stored) != ref.hash) throw ChecksumMismatchException()
            val body =
                if (compressed) ZstdCodec.decompress(stored, 0, stored.size, dictionary, Limits.MAX_FRAME) else stored
            val mr = ByteReader(body)
            val meta = PileReader.readMetaBlobs(mr, PileHeader(FileKind.WORLD, flags, blockVersion))
            if (mr.remaining != 0) corrupt("trailing bytes in metadata frame")
            settingsBytes = meta.settings
            userDataBytes = meta.userData
        } ?: run {
            settingsBytes = ByteArray(0)
            userDataBytes = ByteArray(0)
        }

        val blockSegN = dr.count(Limits.MAX_PALETTE, "block palette segment")
        repeat(blockSegN) {
            val ref = readRefChecked(dr, "block palette segment") ?: corrupt("absent block palette segment reference")
            blockSegments.add(ref)
            val body = frameBody(ref)
            val sr = ByteReader(body)
            val segmentVersion = sr.i32()
            val segment = Palettes.readBlockPalette(sr, segmentVersion)
            if (segment.isEmpty()) corrupt("empty block palette segment")
            if (sr.remaining != 0) corrupt("trailing bytes in block palette segment")
            for (s in segment) {
                if (stateIndex.putIfAbsent(
                        s,
                        states.size
                    ) != null
                ) corrupt("duplicate block palette entry across segments")
                states.add(s)
            }
            if (states.size > Limits.MAX_PALETTE) corrupt("cumulative block palette exceeds ${Limits.MAX_PALETTE}")
        }
        persistedStates = states.size
        val biomeSegN = dr.count(Limits.MAX_PALETTE, "biome palette segment")
        repeat(biomeSegN) {
            val ref = readRefChecked(dr, "biome palette segment") ?: corrupt("absent biome palette segment reference")
            biomeSegments.add(ref)
            val body = frameBody(ref)
            val sr = ByteReader(body)
            val segment = Palettes.readBiomePalette(sr)
            if (segment.isEmpty()) corrupt("empty biome palette segment")
            if (sr.remaining != 0) corrupt("trailing bytes in biome palette segment")
            for (name in segment) {
                if (biomeIndex.putIfAbsent(
                        name,
                        biomeNames.size
                    ) != null
                ) corrupt("duplicate biome palette entry across segments")
                biomeNames.add(name)
            }
            if (biomeNames.size > Limits.MAX_PALETTE) corrupt("cumulative biome palette exceeds ${Limits.MAX_PALETTE}")
        }
        persistedBiomes = biomeNames.size
        // Absent biome sections decode as plains. When the file's palettes lack it, plains joins
        // the cumulative palette as a pending entry, so record references stay aligned with what
        // later checkpoints persist.
        fallbackBiome = biomeIndex[PileReader.PLAINS] ?: run {
            biomeNames.add(PileReader.PLAINS)
            biomeIndex[PileReader.PLAINS] = biomeNames.size - 1
            biomeNames.size - 1
        }

        val chunkN = dr.count(Limits.MAX_CHUNKS, "directory chunk")
        var x = 0L
        var z = 0L
        var off = 0L
        var prevKey = 0L
        repeat(chunkN) {
            x += dr.svarint()
            z += dr.svarint()
            off += dr.svarint()
            if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                corrupt("directory position ($x,$z) out of int32 range")
            }
            val length = dr.uvarint()
            val hash = dr.u64()
            if (length <= 0L || length > Limits.MAX_FRAME || off < Limits.HEADER_SIZE || off + length > footerAt) {
                corrupt("directory frame reference out of bounds")
            }
            val mortonKey = Morton.key(x.toInt(), z.toInt())
            if (directory.isNotEmpty() && Morton.compare(
                    mortonKey,
                    prevKey
                ) <= 0
            ) corrupt("directory entries are out of order or duplicated")
            prevKey = mortonKey
            directory[key(x.toInt(), z.toInt())] = Ref(off, length.toInt(), hash)
        }
        if (dr.remaining != 0) corrupt("${dr.remaining} trailing bytes in directory")
        directoryBudget = directory.size.toLong() * Limits.COLUMN_COST
        val trailer = ByteReader(readAt(footerAt, Limits.FOOTER_SIZE))
        trailer.take(24)
        generation = trailer.u64()
    }

    /** The newest footer whose directory validates; a torn tail falls back through older footers. */
    private fun adoptCheckpoint(size: Long): Pair<Long, ByteArray> {
        val direct = tryFooter(size - Limits.FOOTER_SIZE)
        if (direct != null) return direct
        if (size > Int.MAX_VALUE) corrupt("torn-tail recovery over a ${size}-byte file is not supported")
        var candidates = 0
        var at = size - Limits.FOOTER_SIZE - 1
        val tail = readAt(0, size.toInt())
        while (at >= Limits.HEADER_SIZE) {
            if (tail[(at + 40).toInt()] == FOOTER_MAGIC[0] &&
                tail.copyOfRange((at + 40).toInt(), (at + 44).toInt()).contentEquals(FOOTER_MAGIC)
            ) {
                if (++candidates > Limits.MAX_RECOVERY_CHAIN) corrupt("recovery tried more than ${Limits.MAX_RECOVERY_CHAIN} checkpoints")
                tryFooter(at)?.let { return it }
            }
            at--
        }
        corrupt("no valid indexed footer found")
    }

    private fun tryFooter(at: Long): Pair<Long, ByteArray>? {
        if (at < Limits.HEADER_SIZE || at + Limits.FOOTER_SIZE > channel.size()) return null
        val footer = readAt(at, Limits.FOOTER_SIZE)
        if (!footer.copyOfRange(40, 44).contentEquals(FOOTER_MAGIC)) return null
        val fr = ByteReader(footer)
        val hash = fr.u64()
        val dirOff = fr.u64()
        val dirLen = fr.u64()
        fr.u64()
        val prev = fr.u64()
        if (dirOff < Limits.HEADER_SIZE || dirLen <= 0 || dirLen > Limits.MAX_BODY || dirOff + dirLen > at) return null
        if (prev != 0L && (prev < Limits.HEADER_SIZE || prev >= at)) return null
        val stored = readAt(dirOff, dirLen.toInt())
        if (XxHash.hash(header, stored, footer.copyOfRange(8, 40), FOOTER_MAGIC) != hash) return null
        return at to stored
    }

    private fun readRefChecked(r: ByteReader, what: String): Ref? {
        val off = r.uvarint()
        val length = r.uvarint()
        val hash = r.u64()
        if (length == 0L) {
            if (off != 0L || hash != 0L) corrupt("absent $what reference is not zero")
            return null
        }
        if (off < Limits.HEADER_SIZE || length > Limits.MAX_FRAME || off + length > footerAt) corrupt("$what reference out of bounds")
        return Ref(off, length.toInt(), hash)
    }

    private fun frameBody(ref: Ref): ByteArray {
        val stored = readAt(ref.off, ref.length)
        if (XxHash.hash(stored) != ref.hash) throw ChecksumMismatchException()
        return if (compressed) ZstdCodec.decompress(stored, 0, stored.size, dictionary, Limits.MAX_FRAME) else stored
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFF_FFFFL)

    public companion object {
        private const val DICT_MIN_SAMPLES = 16
        private const val DICT_MIN_BYTES = 64 shl 10
        private const val DICT_MAX_SAMPLES = 256
        private const val DICT_MAX_SAMPLE_BYTES = 8 shl 20
        private const val DICT_SAMPLE_BYTES = DICT_MAX_SAMPLE_BYTES / DICT_MAX_SAMPLES
        private const val DICT_TARGET_BYTES = 16 shl 10
        private val HEADER_MAGIC =
            byteArrayOf('P'.code.toByte(), 'I'.code.toByte(), 'L'.code.toByte(), 'E'.code.toByte())
        private val FOOTER_MAGIC =
            byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())

        public fun open(
            path: Path,
            options: DecodeOptions = DecodeOptions.DEFAULT,
            readOnly: Boolean = false,
            compression: Compression = Compression.DEFAULT,
        ): IndexedPile {
            val channel = if (readOnly) {
                FileChannel.open(path, StandardOpenOption.READ)
            } else {
                FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
            }
            val pile = IndexedPile(path, channel, readOnly, options, compression)
            try {
                synchronized(pile.lock) { pile.loadLocked() }
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
            return pile
        }

        /** Creates a fresh indexed file holding one empty checkpoint. */
        public fun create(
            path: Path,
            blockVersion: Int,
            compression: Compression = Compression.DEFAULT,
            storeLight: Boolean = false,
        ): IndexedPile {
            require(blockVersion != 0) { "blockVersion must not be zero" }
            val empty = World(blockVersion, ByteArray(0), ByteArray(0), emptyList(), emptyList(), emptyList())
            Files.createDirectories(path.toAbsolutePath().parent)
            Files.write(path, IndexedEncoder.encode(empty, compression, storeLight))
            return open(path, compression = compression)
        }
    }
}
