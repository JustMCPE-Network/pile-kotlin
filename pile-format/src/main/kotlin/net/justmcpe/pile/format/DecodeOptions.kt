package net.justmcpe.pile.format

/**
 * Caller policy for a decode. [maxDecodedBytes] caps what one decode materialises under the cost model of
 * format.md §8: columns, section storages and collection entries. Values above [Limits.BUDGET_CEILING] are
 * clamped to it; zero or negative selects [Limits.DEFAULT_BUDGET]. A decode stopped by it fails with
 * [DecodeBudgetException], which is not a claim that the file is invalid.
 */
public data class DecodeOptions(val maxDecodedBytes: Long = Limits.DEFAULT_BUDGET) {
    internal val ceiling: Long
        get() = when {
            maxDecodedBytes <= 0 -> Limits.DEFAULT_BUDGET
            maxDecodedBytes > Limits.BUDGET_CEILING -> Limits.BUDGET_CEILING
            else -> maxDecodedBytes
        }

    public companion object {
        public val DEFAULT: DecodeOptions = DecodeOptions()
    }
}
