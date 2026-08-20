package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.CorruptFileException
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.corrupt

/** Bounds-checked little-endian cursor over a byte array. Every failure is a [CorruptFileException]. */
internal class ByteReader(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
    val remaining: Int get() = end - pos

    fun take(n: Int): Int {
        if (n < 0 || remaining < n) corrupt("unexpected end of data (want $n bytes, have $remaining)")
        val start = pos
        pos += n
        return start
    }

    fun bytes(n: Int): ByteArray {
        val start = take(n)
        return buf.copyOfRange(start, start + n)
    }

    fun u8(): Int = buf[take(1)].toInt() and 0xFF

    fun u16(): Int {
        val p = take(2)
        return (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

    fun i32(): Int {
        val p = take(4)
        return (buf[p].toInt() and 0xFF) or
                ((buf[p + 1].toInt() and 0xFF) shl 8) or
                ((buf[p + 2].toInt() and 0xFF) shl 16) or
                ((buf[p + 3].toInt() and 0xFF) shl 24)
    }

    fun u64(): Long {
        val p = take(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
        return v
    }

    fun uvarint(): Long {
        var result = 0L
        var shift = 0
        var n = 0
        val start = pos
        while (true) {
            if (pos >= end) corrupt("invalid uvarint at offset $start")
            val b = buf[pos++].toInt() and 0xFF
            n++
            if (n == 10 && b > 1) corrupt("invalid uvarint at offset $start")
            if (n > 10) corrupt("invalid uvarint at offset $start")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (n != uvarintLength(result)) corrupt("non-minimal uvarint at offset $start")
        return result
    }

    fun svarint(): Long {
        val u = uvarint()
        return (u ushr 1) xor -(u and 1)
    }

    fun svarint32(what: String): Int {
        val v = svarint()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) corrupt("$what $v out of int32 range")
        return v.toInt()
    }

    fun count(max: Int, what: String): Int {
        val v = uvarint()
        if (v < 0 || v > max) corrupt("$what count $v exceeds limit $max")
        return v.toInt()
    }

    fun string(): String {
        val n = count(Limits.MAX_STRING, "string length")
        val start = take(n)
        if (!Utf8.isValid(buf, start, n)) corrupt("string is not valid UTF-8")
        return String(buf, start, n, Charsets.UTF_8)
    }

    /** The blob's bounds inside [buf]; callers copy when they retain it. */
    fun blobRange(): IntRange {
        val n = count(Limits.MAX_BLOB, "blob length")
        val start = take(n)
        return start until start + n
    }

    fun blob(): ByteArray {
        val r = blobRange()
        return buf.copyOfRange(r.first, r.last + 1)
    }

    fun bitset(n: Int): ByteArray {
        val bytes = bytes((n + 7) / 8)
        if (n % 8 != 0 && (bytes[bytes.size - 1].toInt() and 0xFF) ushr (n % 8) != 0) {
            corrupt("non-zero padding bits in bitset")
        }
        return bytes
    }

    companion object {
        fun uvarintLength(v: Long): Int {
            var x = v
            var n = 1
            while (x ushr 7 != 0L) {
                x = x ushr 7
                n++
            }
            return n
        }
    }
}

internal fun ByteArray.bit(i: Int): Boolean = (this[i ushr 3].toInt() ushr (i and 7)) and 1 != 0
