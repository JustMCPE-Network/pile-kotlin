package net.justmcpe.pile.format

import net.justmcpe.pile.format.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PrimitivesTest {
    @Test
    fun `uvarint round trips and is minimal`() {
        val values = listOf(0L, 1, 127, 128, 16383, 16384, 1L shl 32, Long.MAX_VALUE, -1L)
        for (v in values) {
            val w = ByteWriter()
            w.uvarint(v)
            val bytes = w.toByteArray()
            assertEquals(ByteReader.uvarintLength(v), bytes.size)
            assertEquals(v, ByteReader(bytes).uvarint())
        }
    }

    @Test
    fun `overlong uvarint is refused`() {
        assertThrows(CorruptFileException::class.java) { ByteReader(byteArrayOf(0x80.toByte(), 0x00)).uvarint() }
        assertThrows(CorruptFileException::class.java) { ByteReader(byteArrayOf(0x80.toByte())).uvarint() }
        assertThrows(CorruptFileException::class.java) {
            ByteReader(ByteArray(11) { 0xFF.toByte() }).uvarint()
        }
    }

    @Test
    fun `svarint round trips`() {
        for (v in listOf(
            0L,
            -1,
            1,
            -64,
            63,
            64,
            -65,
            Int.MIN_VALUE.toLong(),
            Int.MAX_VALUE.toLong(),
            Long.MIN_VALUE,
            Long.MAX_VALUE
        )) {
            val w = ByteWriter()
            w.svarint(v)
            assertEquals(v, ByteReader(w.toByteArray()).svarint())
        }
        val w = ByteWriter()
        w.svarint(-4)
        assertEquals(0x07, w.toByteArray()[0].toInt())
    }

    @Test
    fun `fixed width fields are little-endian`() {
        val w = ByteWriter()
        w.u16(0x0102)
        w.i32(0x01020304)
        w.u64(0x0102030405060708L)
        val b = w.toByteArray()
        assertEquals(listOf(2, 1, 4, 3, 2, 1, 8, 7, 6, 5, 4, 3, 2, 1), b.map { it.toInt() })
        val r = ByteReader(b)
        assertEquals(0x0102, r.u16())
        assertEquals(0x01020304, r.i32())
        assertEquals(0x0102030405060708L, r.u64())
    }

    @Test
    fun `bitset padding must be zero`() {
        assertThrows(CorruptFileException::class.java) { ByteReader(byteArrayOf(0x02)).bitset(1) }
        assertTrue(ByteReader(byteArrayOf(0x01)).bitset(1).bit(0))
        assertEquals(2, ByteReader(byteArrayOf(0xFF.toByte(), 0x01)).bitset(9).size)
    }

    @Test
    fun `strings must be valid UTF-8 within the limit`() {
        val w = ByteWriter()
        w.uvarint(2)
        w.raw(byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(CorruptFileException::class.java) { ByteReader(w.toByteArray()).string() }
        val ok = ByteWriter()
        ok.string("minecraft:stone")
        assertEquals("minecraft:stone", ByteReader(ok.toByteArray()).string())
    }

    @Test
    fun `morton keys order by interleaved bits over the whole int32 range`() {
        assertEquals(0L, Morton.key(Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals(-1L, Morton.key(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(Morton.compare(Morton.key(-1, 0), Morton.key(0, 0)) < 0)
        assertTrue(Morton.compare(Morton.key(0, 0), Morton.key(1, 0)) < 0)
        assertTrue(Morton.compare(Morton.key(1, 0), Morton.key(0, 1)) < 0)
        assertTrue(Morton.compare(Morton.key(0, 1), Morton.key(1, 1)) < 0)
        assertEquals(3L, Morton.key(Int.MIN_VALUE + 1, Int.MIN_VALUE + 1))
    }

    @Test
    fun `xxhash64 matches the reference vectors`() {
        assertEquals("ef46db3751d8e999", XxHash.hex(XxHash.hash(ByteArray(0))))
        assertEquals("d24ec4f1a98c6e5b", XxHash.hex(XxHash.hash("a".toByteArray())))
        assertEquals("44bc2cf5ad770999", XxHash.hex(XxHash.hash("abc".toByteArray())))
        val long = ByteArray(101) { (it * 7 + 3).toByte() }
        val whole = XxHash.hash(long)
        val s = XxHash.Streaming()
        var p = 0
        for (n in listOf(1, 31, 32, 5, 20, 12)) {
            s.update(long, p, n)
            p += n
        }
        assertEquals(whole, s.digest())
        assertEquals(whole, XxHash.hash(long.copyOfRange(0, 50), long.copyOfRange(50, 101)))
    }

    @Test
    fun `xxhash64 streaming agrees with one-shot on random chunking`() {
        val rnd = Random(7)
        repeat(200) {
            val data = ByteArray(rnd.nextInt(0, 300)).also { rnd.nextBytes(it) }
            val s = XxHash.Streaming()
            var p = 0
            while (p < data.size) {
                val n = minOf(rnd.nextInt(1, 70), data.size - p)
                s.update(data, p, n)
                p += n
            }
            assertEquals(XxHash.hash(data), s.digest())
        }
    }

    @Test
    fun `utf8 order is code point order`() {
        assertTrue(Utf8.compare("", "𐀀") < 0)
        assertTrue("".compareTo("𐀀") > 0)
        assertTrue(Utf8.compare("a", "ab") < 0)
        assertTrue(Utf8.compare("b", "ab") > 0)
        assertEquals(0, Utf8.compare("same", "same"))
    }
}
