package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.BlockState
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.PropertyValue
import net.justmcpe.pile.format.corrupt

/** Wire parsing of the two global palettes (format.md §3.1, §3.2). */
internal object Palettes {
    fun readBlockPalette(r: ByteReader, blockVersion: Int): List<BlockState> {
        val n = r.count(Limits.MAX_PALETTE, "block palette")
        if (n > r.remaining / 2 + 1) corrupt("block palette count $n exceeds input")
        val names = ArrayList<String>(minOf(n, Limits.MAX_PREALLOC))
        val props = ArrayList<Map<String, PropertyValue>>(minOf(n, Limits.MAX_PREALLOC))
        repeat(n) {
            names.add(r.string())
            val propN = r.count(Limits.MAX_PROPERTIES, "state property")
            val map = LinkedHashMap<String, PropertyValue>(propN)
            var prev: String? = null
            repeat(propN) {
                val k = r.string()
                if (prev != null) {
                    val c = Utf8.compare(k, prev)
                    if (c == 0) corrupt("duplicate state property \"$k\"")
                    if (c < 0) corrupt("state properties must ascend, \"$k\" follows \"$prev\"")
                }
                prev = k
                map[k] = when (val t = r.u8()) {
                    0 -> PropertyValue.ByteValue(r.u8())
                    1 -> PropertyValue.IntValue(r.i32())
                    2 -> PropertyValue.StringValue(r.string())
                    else -> corrupt("unknown property type $t")
                }
            }
            props.add(map)
        }
        val versions = IntArray(n) { blockVersion }
        val overrideN = r.count(n, "palette version override")
        var prev = 0L
        for (i in 0 until overrideN) {
            val delta = r.uvarint()
            if (i > 0 && delta == 0L) corrupt("palette version overrides must be strictly ascending")
            val idx = prev + delta
            if (java.lang.Long.compareUnsigned(idx, prev) < 0) {
                corrupt("palette version override index chain wraps: delta ${java.lang.Long.toUnsignedString(delta)} after index $prev")
            }
            if (java.lang.Long.compareUnsigned(
                    idx,
                    n.toLong()
                ) >= 0
            ) corrupt("palette version override index $idx out of range")
            val v = r.i32()
            if (v == 0) corrupt("palette version override must not be zero")
            if (v == blockVersion) corrupt("palette version override equals the palette's own version")
            versions[idx.toInt()] = v
            prev = idx
        }
        val states = ArrayList<BlockState>(n)
        val seen = HashSet<BlockState>(n * 2)
        for (i in 0 until n) {
            val s = BlockState(names[i], props[i], versions[i])
            if (!seen.add(s)) corrupt("duplicate block palette entry \"${names[i]}\"")
            states.add(s)
        }
        return states
    }

    fun readBiomePalette(r: ByteReader): List<String> {
        val n = r.count(Limits.MAX_PALETTE, "biome palette")
        if (n > r.remaining / 2 + 1) corrupt("biome palette count $n exceeds input")
        val names = ArrayList<String>(minOf(n, Limits.MAX_PREALLOC))
        val seen = HashSet<String>()
        repeat(n) {
            val name = r.string()
            if (!name.contains(':')) corrupt("biome name \"$name\" is not namespaced")
            if (!seen.add(name)) corrupt("duplicate biome palette entry \"$name\"")
            names.add(name)
        }
        return names
    }
}
