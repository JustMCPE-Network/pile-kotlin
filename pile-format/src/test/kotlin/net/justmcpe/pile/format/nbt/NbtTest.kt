package net.justmcpe.pile.format.nbt

import net.justmcpe.pile.format.CorruptFileException
import net.justmcpe.pile.format.InvalidContentException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NbtTest {
    private val sample = NbtCompound.build {
        put("name", "lobby")
        put("time", 6000L)
        put("raining", false)
        put("spawnX", 12)
        put("pos", NbtList(NbtType.FLOAT, listOf(NbtFloat(1f), NbtFloat(2f), NbtFloat(3f))))
        put("uuid", NbtIntArray(intArrayOf(1, 2, 3, 4)))
        put("bytes", NbtByteArray(byteArrayOf(9, 8)))
        put("empty", NbtList(NbtType.END, emptyList()))
        put("nested", NbtCompound.build { put("z", 1.toShort()); put("a", 2.5) })
        put("longs", NbtLongArray(longArrayOf(-1L)))
    }

    @Test
    fun `encode then decode is identity and canonical`() {
        val bytes = Nbt.encode(sample)
        Nbt.validate(bytes)
        val back = Nbt.decode(bytes)
        assertEquals(sample, back)
        assertArrayEquals(bytes, Nbt.encode(back))
        assertEquals(
            listOf("bytes", "empty", "longs", "name", "nested", "pos", "raining", "spawnX", "time", "uuid"),
            back.keys.toList()
        )
    }

    @Test
    fun `wire layout is little-endian with an unnamed root`() {
        val bytes = Nbt.encode(NbtCompound.build { put("k", 0x0102.toShort()) })
        assertEquals(listOf(10, 0, 0, 2, 1, 0, 'k'.code, 2, 1, 0), bytes.map { it.toInt() })
    }

    @Test
    fun `empty lists are written as TAG_End and any other type is refused`() {
        val bytes = Nbt.encode(NbtCompound.build { put("l", NbtList(NbtType.INT, emptyList())) })
        assertEquals(listOf(10, 0, 0, 9, 1, 0, 'l'.code, 0, 0, 0, 0, 0, 0), bytes.map { it.toInt() })
        val bad = bytes.copyOf()
        bad[7] = 3
        assertThrows(CorruptFileException::class.java) { Nbt.validate(bad) }
    }

    @Test
    fun `duplicate and unsorted keys are refused`() {
        fun raw(vararg keys: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf(10, 0, 0))
            for (k in keys) {
                out.write(byteArrayOf(1, k.length.toByte(), 0))
                out.write(k.toByteArray())
                out.write(1)
            }
            out.write(0)
            return out.toByteArray()
        }
        Nbt.validate(raw("a", "b"))
        assertThrows(CorruptFileException::class.java) { Nbt.validate(raw("a", "a")) }
        assertThrows(CorruptFileException::class.java) { Nbt.validate(raw("b", "a")) }
    }

    @Test
    fun `named root, trailing bytes and truncation are refused`() {
        val ok = Nbt.encode(NbtCompound())
        assertThrows(CorruptFileException::class.java) { Nbt.validate(ok + byteArrayOf(0)) }
        assertThrows(CorruptFileException::class.java) { Nbt.validate(byteArrayOf(10, 1, 0, 'x'.code.toByte(), 0)) }
        val full = Nbt.encode(sample)
        for (n in 0 until full.size) assertThrows(
            CorruptFileException::class.java,
            { Nbt.validate(full.copyOf(n)) },
            "truncated at $n"
        )
    }

    @Test
    fun `declared lengths are bounded by the remaining input`() {
        val bytes =
            byteArrayOf(10, 0, 0, 7, 1, 0, 'a'.code.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0)
        assertThrows(CorruptFileException::class.java) { Nbt.validate(bytes) }
        val list = byteArrayOf(10, 0, 0, 9, 1, 0, 'a'.code.toByte(), 10, 0x00, 0x00, 0x10, 0x00, 0)
        assertThrows(CorruptFileException::class.java) { Nbt.validate(list) }
    }

    @Test
    fun `nesting deeper than 64 is refused on both paths`() {
        var c = NbtCompound()
        repeat(64) { c = NbtCompound.build { put("c", c) } }
        val bytes = Nbt.encode(c)
        Nbt.validate(bytes)
        val deeper = NbtCompound.build { put("c", c) }
        assertThrows(InvalidContentException::class.java) { Nbt.encode(deeper) }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(10, 0, 0))
        repeat(65) { out.write(byteArrayOf(10, 1, 0, 'c'.code.toByte())) }
        repeat(66) { out.write(0) }
        val wire = out.toByteArray()
        assertThrows(CorruptFileException::class.java) { Nbt.validate(wire) }
    }

    @Test
    fun `lenient decode accepts runtime key order and canonicalises it`() {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(10, 0, 0))
        for (k in listOf("Pos", "Motion")) {
            out.write(byteArrayOf(1, k.length.toByte(), 0))
            out.write(k.toByteArray())
            out.write(7)
        }
        out.write(byteArrayOf(9, 5, 0) + "empty".toByteArray() + byteArrayOf(3, 0, 0, 0, 0))
        out.write(0)
        val raw = out.toByteArray()
        assertThrows(CorruptFileException::class.java) { Nbt.decode(raw) }
        val canonical = Nbt.encode(Nbt.decodeLenient(raw))
        Nbt.validate(canonical)
        assertEquals(listOf("Motion", "Pos", "empty"), Nbt.decode(canonical).keys.toList())
        val dup = byteArrayOf(10, 0, 0, 1, 1, 0, 'a'.code.toByte(), 1, 1, 1, 0, 'a'.code.toByte(), 2, 0)
        assertThrows(CorruptFileException::class.java) { Nbt.decodeLenient(dup) }
    }

    @Test
    fun `strings past 32767 bytes are refused`() {
        assertThrows(InvalidContentException::class.java) {
            Nbt.encode(NbtCompound.build {
                put(
                    "s",
                    "x".repeat(32768)
                )
            })
        }
        Nbt.encode(NbtCompound.build { put("s", "x".repeat(32767)) })
    }

    @Test
    fun `container budget is charged for nested compounds and list elements`() {
        val elements = List(1000) { NbtCompound() }
        val bytes = Nbt.encode(NbtCompound.build { put("l", NbtList(NbtType.COMPOUND, elements)) })
        Nbt.validate(bytes)
        val many = NbtCompound.build { put("l", NbtList(NbtType.COMPOUND, List((1 shl 20) + 1) { NbtCompound() })) }
        assertThrows(InvalidContentException::class.java) { Nbt.encode(many) }
    }
}
