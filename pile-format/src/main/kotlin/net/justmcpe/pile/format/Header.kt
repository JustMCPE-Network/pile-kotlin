package net.justmcpe.pile.format

/** Header feature flags, format.md §2.3. */
public object Flags {
    public const val STORE_LIGHT: Int = 1 shl 0
    public const val STATS: Int = 1 shl 1
    public const val DEFAULT_BIOME: Int = 1 shl 3
    public const val UNCOMPRESSED: Int = 1 shl 4
    public const val DEFAULT_BIOME_SHIFT: Int = 16
    internal const val KNOWN: Int = STORE_LIGHT or STATS or DEFAULT_BIOME or UNCOMPRESSED or 0xFFFF0000.toInt()
}

public enum class FileKind(public val code: Int) { WORLD(0), STRUCTURE(1) }

/** The fixed 16-byte header, format.md §2.1. */
public data class PileHeader(val kind: FileKind, val flags: Int, val blockVersion: Int) {
    public val storeLight: Boolean get() = flags and Flags.STORE_LIGHT != 0
    public val hasStats: Boolean get() = flags and Flags.STATS != 0
    public val uncompressed: Boolean get() = flags and Flags.UNCOMPRESSED != 0
    public val hasDefaultBiome: Boolean get() = flags and Flags.DEFAULT_BIOME != 0
    public val defaultBiomeRef: Int get() = flags ushr Flags.DEFAULT_BIOME_SHIFT
}
