package net.justmcpe.pile.format


/** Zstandard level used for a body. The numbers are the reference zstd library's, not klauspost's (format.md §2.5). */
public enum class Compression(internal val zstdLevel: Int) {
    NONE(0),
    FAST(1),
    DEFAULT(3),

    /** Level 19: the highest level whose window stays within the 8 MiB a reader must accept. */
    BEST(19),
}

/**
 * Writer policy. [stats] embeds the §4.2 summary compound; [storeLight] keeps baked light when the
 * columns carry any; [skipBiomes] stores no biome data at all, so readers yield plains everywhere.
 * None of the three changes content identity beyond what it removes.
 *
 * [fastCompression] compresses with one thread per core. Saves get faster and the compressed bytes
 * stop being deterministic across runs; the uncompressed body, and with it [PileWriter.contentHash],
 * stays canonical.
 */
public data class WriteOptions(
    val compression: Compression = Compression.BEST,
    val stats: Boolean = false,
    val storeLight: Boolean = true,
    val skipBiomes: Boolean = false,
    val fastCompression: Boolean = false,
)
