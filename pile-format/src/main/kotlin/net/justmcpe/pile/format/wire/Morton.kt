package net.justmcpe.pile.format.wire

internal object Morton {
    fun key(x: Int, z: Int): Long = spread(x xor Int.MIN_VALUE) or (spread(z xor Int.MIN_VALUE) shl 1)

    private fun spread(v: Int): Long {
        var x = v.toLong() and 0xFFFF_FFFFL
        x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
        x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
        x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x shl 2)) and 0x3333333333333333L
        x = (x or (x shl 1)) and 0x5555555555555555L
        return x
    }

    fun compare(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)
}
