package net.justmcpe.pile.format.wire

/** Append-only little-endian byte buffer. */
internal class ByteWriter(initial: Int = 256) {
    private var buf = ByteArray(initial)
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) {
            var cap = buf.size * 2
            while (cap < size + n) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun raw(p: ByteArray, off: Int = 0, len: Int = p.size - off) {
        ensure(len)
        System.arraycopy(p, off, buf, size, len)
        size += len
    }

    fun u16(v: Int) {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
    }

    fun i32(v: Int) {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 24).toByte()
    }

    fun u64(v: Long) {
        ensure(8)
        var x = v
        repeat(8) {
            buf[size++] = x.toByte()
            x = x ushr 8
        }
    }

    fun uvarint(v: Long) {
        var x = v
        while (x ushr 7 != 0L) {
            u8(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        u8(x.toInt())
    }

    fun uvarint(v: Int) = uvarint(v.toLong())

    fun svarint(v: Long) = uvarint((v shl 1) xor (v shr 63))

    fun string(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        uvarint(b.size)
        raw(b)
    }

    fun blob(p: ByteArray) {
        uvarint(p.size)
        raw(p)
    }

    fun reserve(n: Int): Int {
        ensure(n)
        val start = size
        size += n
        return start
    }

    fun array(): ByteArray = buf
    fun toByteArray(): ByteArray = buf.copyOf(size)
}
