package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.DecodeBudgetException
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.corrupt

/** Bounds storages decoded per file (validity) and bytes decoded per call (the caller's budget), charged at the same places. */
internal class DecodeBudget(private val byteLimit: Long) {
    private var storages = 0
    private var bytes = 0L

    fun chargeStorages(n: Int) {
        storages += n
        if (storages > Limits.MAX_STORAGES) corrupt("file decodes into more than ${Limits.MAX_STORAGES} section storages")
        chargeBytes(n * Limits.STORAGE_COST)
    }

    fun chargeColumns(n: Int) = chargeBytes(n * Limits.COLUMN_COST)

    fun chargeEntries(n: Int) = chargeBytes(n * Limits.ENTRY_COST)

    private fun chargeBytes(n: Long) {
        bytes += n
        if (bytes > byteLimit) throw DecodeBudgetException(bytes, byteLimit)
    }
}
