package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*
import java.util.*

/** A section blob as parsed from the body, with its index bytes still aliasing the body (format.md §3.3). */
internal class RawBlob(val refs: IntArray, val width: Int, val buf: ByteArray, val idxOffset: Int) {
    private var storage: Storage? = null

    val isUniform: Boolean get() = width == 0

    /** Materialises the blob once; identical sections share the result. */
    fun storage(): Storage {
        storage?.let { return it }
        val s = if (width == 0) {
            Storage(refs, null)
        } else {
            val idx = ShortArray(Limits.STORAGE_SIZE)
            val n = refs.size
            if (width == 1) {
                for (i in 0 until Limits.STORAGE_SIZE) {
                    val li = buf[idxOffset + i].toInt() and 0xFF
                    if (li >= n) corrupt("section index $li out of palette range $n")
                    idx[i] = li.toShort()
                }
            } else {
                for (i in 0 until Limits.STORAGE_SIZE) {
                    val li =
                        (buf[idxOffset + 2 * i].toInt() and 0xFF) or ((buf[idxOffset + 2 * i + 1].toInt() and 0xFF) shl 8)
                    if (li >= n) corrupt("section index $li out of palette range $n")
                    idx[i] = li.toShort()
                }
            }
            Storage(refs, idx)
        }
        storage = s
        return s
    }
}

internal object Blobs {
    fun readOne(r: ByteReader): RawBlob {
        val pn = r.count(Limits.MAX_LOCAL_PALETTE, "section palette")
        if (pn == 0) corrupt("empty section palette in blob")
        if (pn > r.remaining) corrupt("section palette count $pn exceeds input")
        val refs = IntArray(pn)
        for (j in 0 until pn) {
            val v = r.uvarint()
            if (v < 0 || v > Limits.MAX_PALETTE) corrupt("palette reference $v out of range")
            if (j > 0 && v <= refs[j - 1]) corrupt("section palette references are not strictly ascending")
            refs[j] = v.toInt()
        }
        val width = r.u8()
        var idxOffset = -1
        when (width) {
            0 -> if (pn != 1) corrupt("uniform blob with $pn palette entries")
            1 -> {
                if (pn == 1) corrupt("single-entry palette must use the uniform width")
                if (pn > 256) corrupt("u8 indices with $pn palette entries")
                idxOffset = r.take(Limits.STORAGE_SIZE)
            }

            2 -> {
                if (pn <= 256) corrupt("non-minimal index width for $pn palette entries")
                idxOffset = r.take(Limits.STORAGE_SIZE * 2)
            }

            else -> corrupt("unknown index width $width")
        }
        if (idxOffset >= 0) {
            val used = BooleanArray(pn)
            var unseen = pn
            val step = width
            var i = 0
            while (i < Limits.STORAGE_SIZE * step && unseen > 0) {
                var li = r.buf[idxOffset + i].toInt() and 0xFF
                if (step == 2) li = li or ((r.buf[idxOffset + i + 1].toInt() and 0xFF) shl 8)
                if (li < pn && !used[li]) {
                    used[li] = true
                    unseen--
                }
                i += step
            }
            if (unseen > 0) {
                val first = used.indexOfFirst { !it }
                corrupt("section palette entry $first is never used by the indices")
            }
        }
        return RawBlob(refs, width, r.buf, idxOffset)
    }

    fun readTable(r: ByteReader): List<RawBlob> {
        val n = r.count(Limits.MAX_BLOBS, "blob table")
        if (n > r.remaining / 3 + 1) corrupt("blob table count $n exceeds input")
        val hint = minOf(n, Limits.MAX_PREALLOC)
        val blobs = ArrayList<RawBlob>(hint)
        val seen = HashMap<Long, MutableList<Int>>(hint)
        val spans = ArrayList<IntArray>(hint)
        for (i in 0 until n) {
            val start = r.pos
            val b = try {
                readOne(r)
            } catch (e: CorruptFileException) {
                throw CorruptFileException("blob $i: ${e.message}", e)
            }
            val end = r.pos
            val h = XxHash.hash(r.buf, start, end - start)
            seen[h]?.forEach { prev ->
                val s = spans[prev]
                if (regionEquals(r.buf, s[0], s[1], start, end)) corrupt("blob $i repeats blob $prev")
            }
            seen.getOrPut(h) { ArrayList(1) }.add(i)
            spans.add(intArrayOf(start, end))
            blobs.add(b)
        }
        return blobs
    }

    private fun regionEquals(b: ByteArray, s1: Int, e1: Int, s2: Int, e2: Int): Boolean =
        e1 - s1 == e2 - s2 && Arrays.equals(b, s1, e1, b, s2, e2)
}
