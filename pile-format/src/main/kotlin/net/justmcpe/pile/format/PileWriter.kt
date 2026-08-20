package net.justmcpe.pile.format

import net.justmcpe.pile.format.wire.IndexedEncoder
import net.justmcpe.pile.format.wire.StructureEncoder
import net.justmcpe.pile.format.wire.WorldEncoder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Encodes worlds and structures in solid mode. Output is canonical and deterministic: identical
 * content, block version and options produce identical bytes, and the palettes, records, section
 * blobs and per-column collections come out in the orders format.md §3–§4 fix. Content the reader
 * would refuse is refused here first, so a written file always reads back.
 */
public object PileWriter {
    public fun writeWorld(world: World, options: WriteOptions = WriteOptions()): ByteArray =
        WorldEncoder.encode(world, options)

    /** Writes atomically: temporary file, fsync, rename over [path]. */
    public fun writeWorld(path: Path, world: World, options: WriteOptions = WriteOptions()) {
        writeAtomic(path, writeWorld(world, options))
    }

    public fun writeStructure(structure: Structure, compression: Compression = Compression.BEST): ByteArray =
        StructureEncoder.encode(structure, compression)

    public fun writeStructure(path: Path, structure: Structure, compression: Compression = Compression.BEST) {
        writeAtomic(path, writeStructure(structure, compression))
    }

    /**
     * Encodes an indexed file holding one checkpoint (format.md §5). Indexed bytes are
     * history-dependent and are not part of the format's content identity.
     */
    public fun writeIndexed(world: World, compression: Compression = Compression.NONE): ByteArray =
        IndexedEncoder.encode(world, compression)

    public fun writeIndexed(path: Path, world: World, compression: Compression = Compression.NONE) {
        writeAtomic(path, writeIndexed(world, compression))
    }

    /**
     * Appends [world] to the indexed file at [path] as a new checkpoint. Requires the palettes and
     * metadata to be unchanged; a save that grew either rewrites with [writeIndexed] instead.
     */
    public fun appendIndexed(path: Path, world: World): Unit = IndexedEncoder.append(path, world)

    /**
     * The world's content identity: xxHash64 (seed 0) of the canonical uncompressed body, without
     * stats or light. Stable across compressors, file modes and implementations that follow the
     * canonical rules; equal to the reference implementation's `format.ContentHash`.
     */
    public fun contentHash(world: World): Long {
        val file = writeWorld(world, WriteOptions(Compression.NONE, stats = false, storeLight = false))
        return XxHash.hash(file, Limits.HEADER_SIZE, file.size - Limits.HEADER_SIZE - Limits.FOOTER_SIZE)
    }

    public fun contentHash(structure: Structure): Long {
        val file = writeStructure(structure, Compression.NONE)
        return XxHash.hash(file, Limits.HEADER_SIZE, file.size - Limits.HEADER_SIZE - Limits.FOOTER_SIZE)
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, path.fileName.toString(), ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                ch.write(java.nio.ByteBuffer.wrap(bytes))
                ch.force(true)
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
