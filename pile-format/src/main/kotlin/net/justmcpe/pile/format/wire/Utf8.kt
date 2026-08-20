package net.justmcpe.pile.format.wire

internal object Utf8 {
    fun isValid(b: ByteArray, off: Int, len: Int): Boolean {
        var i = off
        val end = off + len
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            when {
                c < 0x80 -> i++
                c < 0xC2 -> return false
                c < 0xE0 -> {
                    if (i + 1 >= end || !cont(b[i + 1])) return false
                    i += 2
                }

                c < 0xF0 -> {
                    if (i + 2 >= end || !cont(b[i + 1]) || !cont(b[i + 2])) return false
                    val c1 = b[i + 1].toInt() and 0xFF
                    if (c == 0xE0 && c1 < 0xA0) return false
                    if (c == 0xED && c1 >= 0xA0) return false
                    i += 3
                }

                c < 0xF5 -> {
                    if (i + 3 >= end || !cont(b[i + 1]) || !cont(b[i + 2]) || !cont(b[i + 3])) return false
                    val c1 = b[i + 1].toInt() and 0xFF
                    if (c == 0xF0 && c1 < 0x90) return false
                    if (c == 0xF4 && c1 >= 0x90) return false
                    i += 4
                }

                else -> return false
            }
        }
        return true
    }

    private fun cont(b: Byte): Boolean = b.toInt() and 0xC0 == 0x80

    val order: Comparator<String> = Comparator { a, b -> compare(a, b) }

    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
