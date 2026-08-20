package net.justmcpe.pile.format

/** Validity ceilings of format.md §8 and the decode cost model. Raising one is a format revision. */
public object Limits {
    public const val VERSION: Int = 2
    public const val HEADER_SIZE: Int = 16
    public const val FOOTER_SIZE: Int = 44

    public const val MAX_STRING: Int = 65_535
    public const val MAX_NBT_STRING: Int = 32_767
    public const val MAX_BLOB: Int = 16 shl 20
    public const val MAX_NBT_DEPTH: Int = 64
    public const val MAX_NBT_CONTAINERS: Int = 1 shl 20
    public const val MAX_BODY: Int = 512 shl 20
    public const val MAX_ZSTD_WINDOW_LOG: Int = 23
    public const val MAX_CHUNKS: Int = 1 shl 26
    public const val MAX_STORAGES: Int = 1 shl 22
    public const val MAX_PALETTE: Int = 1 shl 20
    public const val MAX_BLOBS: Int = 1 shl 24
    public const val MAX_LOCAL_PALETTE: Int = 1 shl 16
    public const val MAX_PROPERTIES: Int = 64
    public const val MAX_SECTIONS: Int = 4096
    public const val MIN_SECTION_INDEX: Int = -2048
    public const val MAX_SECTION_INDEX: Int = 2047
    public const val MAX_LAYERS: Int = 255
    public const val MAX_PER_CHUNK: Int = 1 shl 20
    public const val MAX_STRUCTURE_AXIS: Int = 1 shl 20
    public const val MAX_STRUCTURE_CELLS: Int = 1 shl 20
    public const val MAX_FRAME: Int = 64 shl 20
    public const val MAX_DICT: Int = 1 shl 20
    public const val MAX_RECOVERY_CHAIN: Int = 256
    public const val LIGHT_ARRAY_SIZE: Int = 2048
    public const val STORAGE_SIZE: Int = 4096

    internal const val MAX_PREALLOC: Int = 4096

    /** Cost model of the decode budget: bytes charged per decoded column, storage and collection entry. */
    public const val COLUMN_COST: Long = 1024
    public const val STORAGE_COST: Long = 128
    public const val ENTRY_COST: Long = 256

    /** The most a decode may cost under the cost model while still being valid; a caller's budget is clamped to it. */
    public const val BUDGET_CEILING: Long =
        MAX_CHUNKS * COLUMN_COST + MAX_STORAGES * STORAGE_COST + MAX_BODY * ENTRY_COST + COLUMN_COST

    /** The budget a caller gets without asking: 5 GiB, well below [BUDGET_CEILING]. */
    public const val DEFAULT_BUDGET: Long = 5L shl 30
}
