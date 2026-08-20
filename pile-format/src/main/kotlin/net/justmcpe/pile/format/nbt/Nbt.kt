package net.justmcpe.pile.format.nbt

import net.justmcpe.pile.format.CorruptFileException
import net.justmcpe.pile.format.InvalidContentException
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.corrupt
import net.justmcpe.pile.format.wire.ByteReader
import net.justmcpe.pile.format.wire.ByteWriter
import net.justmcpe.pile.format.wire.Utf8

/**
 * Little-endian Bedrock NBT as the format stores it: one unnamed root compound, keys strictly ascending
 * bytewise, empty lists typed TAG_End, bounded strings, depth and container count (format.md §1).
 */
public object Nbt {
    /** Structural validation without building a tree: every rule of format.md §1, in one bounded pass. */
    public fun validate(blob: ByteArray, off: Int = 0, len: Int = blob.size - off) {
        Walker(blob, off, off + len).validateRoot()
    }

    /** Decodes a blob into a tree. Input is validated first, so a hostile blob fails before it allocates. */
    public fun decode(blob: ByteArray, off: Int = 0, len: Int = blob.size - off): NbtCompound =
        decode(blob, off, len, strict = true)

    /**
     * Decodes a blob that was not written canonically: unsorted keys and typed empty lists are
     * accepted and normalised away by [encode], while every structural bound of §1 — lengths,
     * depth, the container budget, string ceilings, duplicate keys — still applies. This is the
     * entry point for NBT handed over by a runtime rather than read from a pile file.
     */
    public fun decodeLenient(blob: ByteArray, off: Int = 0, len: Int = blob.size - off): NbtCompound =
        decode(blob, off, len, strict = false)

    private fun decode(blob: ByteArray, off: Int, len: Int, strict: Boolean): NbtCompound {
        val w = Walker(blob, off, off + len, strict)
        w.validateRoot()
        val r = Walker(blob, off, off + len, strict)
        r.u8()
        r.name()
        return r.compound(0)
    }

    /** Canonical encoding: sorted keys, TAG_End element type for empty lists, strings at most 32 767 bytes. */
    public fun encode(root: NbtCompound): ByteArray {
        val w = ByteWriter(128)
        w.u8(NbtType.COMPOUND.id)
        w.u16(0)
        val budget = IntArray(1)
        writeCompound(w, root, 0, budget)
        return w.toByteArray()
    }

    private fun writeCompound(w: ByteWriter, c: NbtCompound, depth: Int, budget: IntArray) {
        if (depth > Limits.MAX_NBT_DEPTH) throw InvalidContentException("nbt nested deeper than ${Limits.MAX_NBT_DEPTH}")
        for ((k, v) in c) {
            if (v.type == NbtType.COMPOUND || v.type == NbtType.LIST) charge(budget, 1)
            w.u8(v.type.id)
            writeString(w, k)
            writePayload(w, v, depth + 1, budget)
        }
        w.u8(NbtType.END.id)
    }

    private fun writePayload(w: ByteWriter, v: NbtTag, depth: Int, budget: IntArray) {
        if (depth > Limits.MAX_NBT_DEPTH) throw InvalidContentException("nbt nested deeper than ${Limits.MAX_NBT_DEPTH}")
        when (v) {
            is NbtByte -> w.u8(v.value.toInt())
            is NbtShort -> w.u16(v.value.toInt())
            is NbtInt -> w.i32(v.value)
            is NbtLong -> w.u64(v.value)
            is NbtFloat -> w.i32(v.value.toRawBits())
            is NbtDouble -> w.u64(v.value.toRawBits())
            is NbtString -> writeString(w, v.value)
            is NbtByteArray -> {
                w.i32(v.value.size)
                w.raw(v.value)
            }

            is NbtIntArray -> {
                w.i32(v.value.size)
                for (e in v.value) w.i32(e)
            }

            is NbtLongArray -> {
                w.i32(v.value.size)
                for (e in v.value) w.u64(e)
            }

            is NbtList -> {
                val et = if (v.isEmpty()) NbtType.END else v.elementType
                w.u8(et.id)
                w.i32(v.size)
                if (et == NbtType.COMPOUND || et == NbtType.LIST) charge(budget, v.size)
                for (e in v) writePayload(w, e, depth + 1, budget)
            }

            is NbtCompound -> writeCompound(w, v, depth, budget)
        }
    }

    private fun charge(budget: IntArray, n: Int) {
        budget[0] += n
        if (budget[0] > Limits.MAX_NBT_CONTAINERS) {
            throw InvalidContentException("nbt decodes into more than ${Limits.MAX_NBT_CONTAINERS} containers")
        }
    }

    private fun writeString(w: ByteWriter, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size > Limits.MAX_NBT_STRING) {
            throw InvalidContentException("nbt string is ${b.size} bytes, limit ${Limits.MAX_NBT_STRING}")
        }
        w.u16(b.size)
        w.raw(b)
    }

    private class Walker(buf: ByteArray, start: Int, end: Int, private val strict: Boolean = true) {
        private val r = ByteReader(buf, start, end)
        private var containers = 0

        fun u8(): Int = if (r.remaining < 1) corrupt("nbt: truncated tag") else r.u8()

        private fun u16(): Int = if (r.remaining < 2) corrupt("nbt: truncated length") else r.u16()

        private fun i32(): Int = if (r.remaining < 4) corrupt("nbt: truncated length") else r.i32()

        fun name(): String {
            val n = u16()
            if (n > Limits.MAX_NBT_STRING) corrupt("nbt: string of $n bytes, past the ${Limits.MAX_NBT_STRING} a Bedrock NBT reader can address")
            if (r.remaining < n) corrupt("nbt: truncated name (want $n bytes, have ${r.remaining})")
            val start = r.take(n)
            return String(r.buf, start, n, Charsets.UTF_8)
        }

        fun validateRoot() {
            val t = u8()
            if (t != NbtType.COMPOUND.id) corrupt("nbt: root tag type $t is not a compound")
            val root = name()
            if (root.isNotEmpty()) corrupt("nbt: root compound must be unnamed, got \"$root\"")
            payload(NbtType.COMPOUND, 0)
            if (r.remaining != 0) corrupt("nbt: ${r.remaining} trailing bytes")
        }

        private fun count(n: Int) {
            containers += n
            if (containers > Limits.MAX_NBT_CONTAINERS) corrupt("nbt: more than ${Limits.MAX_NBT_CONTAINERS} values")
        }

        private fun minPayload(t: NbtType): Int = when (t) {
            NbtType.BYTE -> 1
            NbtType.SHORT -> 2
            NbtType.INT, NbtType.FLOAT -> 4
            NbtType.LONG, NbtType.DOUBLE -> 8
            NbtType.STRING -> 2
            NbtType.BYTE_ARRAY, NbtType.INT_ARRAY, NbtType.LONG_ARRAY -> 4
            NbtType.LIST -> 5
            NbtType.COMPOUND -> 1
            NbtType.END -> corrupt("nbt: unknown tag type 0")
        }

        private fun payload(t: NbtType, depth: Int) {
            if (depth > Limits.MAX_NBT_DEPTH) corrupt("nbt: nesting deeper than ${Limits.MAX_NBT_DEPTH}")
            when (t) {
                NbtType.BYTE -> skip(1)
                NbtType.SHORT -> skip(2)
                NbtType.INT, NbtType.FLOAT -> skip(4)
                NbtType.LONG, NbtType.DOUBLE -> skip(8)
                NbtType.STRING -> {
                    val n = u16()
                    if (n > Limits.MAX_NBT_STRING) corrupt("nbt: string of $n bytes, past the ${Limits.MAX_NBT_STRING} a Bedrock NBT reader can address")
                    skip(n)
                }

                NbtType.BYTE_ARRAY, NbtType.INT_ARRAY, NbtType.LONG_ARRAY -> {
                    val n = i32()
                    if (n < 0) corrupt("nbt: negative array length $n")
                    val elem = when (t) {
                        NbtType.INT_ARRAY -> 4; NbtType.LONG_ARRAY -> 8; else -> 1
                    }
                    if (n.toLong() * elem > r.remaining) corrupt("nbt: array of $n elements exceeds remaining input")
                    skip(n * elem)
                }

                NbtType.LIST -> {
                    val etId = u8()
                    val n = i32()
                    if (n < 0) corrupt("nbt: negative list length $n")
                    if (n == 0) {
                        if (strict && etId != NbtType.END.id) corrupt("nbt: empty list declares element type $etId, want TAG_End")
                        if (etId != NbtType.END.id && NbtType.of(etId) == null) corrupt("nbt: unknown list element type $etId")
                        return
                    }
                    if (etId == NbtType.END.id) corrupt("nbt: non-empty list declares TAG_End element type")
                    val et = NbtType.of(etId) ?: corrupt("nbt: unknown list element type $etId")
                    if (n.toLong() * minPayload(et) > r.remaining) corrupt("nbt: list of $n elements (type $etId) exceeds remaining input")
                    if (et == NbtType.COMPOUND || et == NbtType.LIST) count(n)
                    repeat(n) { payload(et, depth + 1) }
                }

                NbtType.COMPOUND -> {
                    var prev: String? = null
                    val looseKeys = if (strict) null else HashSet<String>()
                    while (true) {
                        val ctId = u8()
                        if (ctId == NbtType.END.id) return
                        val ct = NbtType.of(ctId) ?: corrupt("nbt: unknown tag type $ctId")
                        if (ct == NbtType.COMPOUND || ct == NbtType.LIST) count(1)
                        val key = name()
                        if (strict) {
                            if (prev != null) {
                                val c = Utf8.compare(key, prev)
                                if (c == 0) corrupt("nbt: duplicate compound key \"$key\"")
                                if (c < 0) corrupt("nbt: compound keys must ascend, \"$key\" follows \"$prev\"")
                            }
                            prev = key
                        } else {
                            if (!looseKeys!!.add(key)) corrupt("nbt: duplicate compound key \"$key\"")
                        }
                        payload(ct, depth + 1)
                    }
                }

                NbtType.END -> corrupt("nbt: unknown tag type 0")
            }
        }

        private fun skip(n: Int) {
            if (n < 0 || r.remaining < n) corrupt("nbt: truncated (want $n bytes, have ${r.remaining})")
            r.take(n)
        }

        fun compound(depth: Int): NbtCompound {
            val b = NbtCompound.Builder()
            while (true) {
                val ctId = u8()
                if (ctId == NbtType.END.id) return b.build()
                val ct = NbtType.of(ctId) ?: corrupt("nbt: unknown tag type $ctId")
                val key = name()
                b.put(key, value(ct, depth + 1))
            }
        }

        private fun value(t: NbtType, depth: Int): NbtTag = when (t) {
            NbtType.BYTE -> NbtByte(u8().toByte())
            NbtType.SHORT -> NbtShort(u16().toShort())
            NbtType.INT -> NbtInt(i32())
            NbtType.LONG -> NbtLong(r.u64())
            NbtType.FLOAT -> NbtFloat(Float.fromBits(i32()))
            NbtType.DOUBLE -> NbtDouble(Double.fromBits(r.u64()))
            NbtType.STRING -> NbtString(name())
            NbtType.BYTE_ARRAY -> NbtByteArray(r.bytes(i32()))
            NbtType.INT_ARRAY -> NbtIntArray(IntArray(i32()) { i32() })
            NbtType.LONG_ARRAY -> NbtLongArray(LongArray(i32()) { r.u64() })
            NbtType.LIST -> {
                val et = NbtType.of(u8()) ?: corrupt("nbt: unknown list element type")
                val n = i32()
                NbtList(if (n == 0) NbtType.END else et, List(n) { value(et, depth + 1) })
            }

            NbtType.COMPOUND -> compound(depth)
            NbtType.END -> corrupt("nbt: unknown tag type 0")
        }
    }

    /** True when [blob] is a valid canonical compound; the exception is the reason when it is not. */
    public fun check(blob: ByteArray): CorruptFileException? = try {
        validate(blob)
        null
    } catch (e: CorruptFileException) {
        e
    }
}
