package net.justmcpe.pile.format.wire

/** Open-addressing int-to-int map for hot remap paths; no boxing, linear probing, -1 keys unused. */
internal class IntIntMap(expected: Int) {
    private var mask: Int
    private var keys: IntArray
    private var values: IntArray
    private var count = 0

    init {
        var cap = Integer.highestOneBit(maxOf(8, expected * 2 - 1)) * 2
        if (cap < 8) cap = 8
        mask = cap - 1
        keys = IntArray(cap) { -1 }
        values = IntArray(cap)
    }

    fun getOrDefault(key: Int, default: Int): Int {
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == -1) return default
            i = (i + 1) and mask
        }
    }

    fun put(key: Int, value: Int) {
        if (count * 4 >= mask * 3) grow()
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) {
                values[i] = value
                return
            }
            if (k == -1) {
                keys[i] = key
                values[i] = value
                count++
                return
            }
            i = (i + 1) and mask
        }
    }

    inline fun getOrPut(key: Int, compute: () -> Int): Int {
        val existing = getOrDefault(key, Int.MIN_VALUE)
        if (existing != Int.MIN_VALUE) return existing
        val value = compute()
        put(key, value)
        return value
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val cap = (mask + 1) * 2
        mask = cap - 1
        keys = IntArray(cap) { -1 }
        values = IntArray(cap)
        count = 0
        for (i in oldKeys.indices) if (oldKeys[i] != -1) put(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Int): Int {
        var h = key * -0x61c88647
        h = h xor (h ushr 16)
        return h
    }
}
