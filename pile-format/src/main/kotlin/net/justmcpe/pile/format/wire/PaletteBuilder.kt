package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.BlockState
import net.justmcpe.pile.format.InvalidContentException
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.PropertyValue

/**
 * Accumulates the block states a file references and finalizes them into the canonical order of
 * format.md §3.1: descending reference count, then the entry's encoded bytes, then version. The
 * reference count is the number of local palettes a state appears in plus one per scheduled update,
 * counted after trailing all-air layers are dropped.
 */
internal class BlockPaletteBuilder(private val fileVersion: Int) {
    private class Entry(val state: BlockState, val bytes: ByteArray) {
        var count = 0L
    }

    private val entries = ArrayList<Entry>()
    private val ids = HashMap<BlockState, Int>()

    /** The merged id for [state]; stable across calls, remapped by [finalize]. */
    fun id(state: BlockState): Int = ids.getOrPut(state) {
        entries.add(Entry(state, state.encodedEntry()))
        entries.size - 1
    }

    fun count(id: Int, n: Long = 1) {
        entries[id].count += n
    }

    fun state(id: Int): BlockState = entries[id].state

    /**
     * Sorts, encodes the palette with its override table, and returns build-id to final-id.
     * Entries nothing referenced — states seen only in dropped trailing air layers — never reach
     * the file: an entry no blob or update names is content nothing reads.
     */
    fun finalize(out: ByteWriter): IntArray {
        for (e in entries) checkState(e.state)
        val order = (0 until entries.size).filter { entries[it].count > 0 }
            .sortedWith { a, b -> compare(entries[a], entries[b]) }
        if (order.size > Limits.MAX_PALETTE) {
            throw InvalidContentException("${order.size} block states exceeds limit ${Limits.MAX_PALETTE}")
        }
        val remap = IntArray(entries.size) { -1 }
        order.forEachIndexed { final, build -> remap[build] = final }
        out.uvarint(order.size)
        val overrides = ArrayList<Int>()
        for ((final, build) in order.withIndex()) {
            out.raw(entries[build].bytes)
            if (entries[build].state.version != fileVersion) overrides.add(final)
        }
        out.uvarint(overrides.size)
        var prev = 0
        for (final in overrides) {
            out.uvarint(final - prev)
            out.i32(entries[order[final]].state.version)
            prev = final
        }
        return remap
    }

    /** Encodes the palette in first-seen order (indexed mode, §5.3) and returns the remap. */
    fun finalizeFirstSeen(out: ByteWriter): IntArray {
        for (e in entries) checkState(e.state)
        val kept = (0 until entries.size).filter { entries[it].count > 0 }
        if (kept.size > Limits.MAX_PALETTE) {
            throw InvalidContentException("${kept.size} block states exceeds limit ${Limits.MAX_PALETTE}")
        }
        val remap = IntArray(entries.size) { -1 }
        kept.forEachIndexed { final, build -> remap[build] = final }
        out.uvarint(kept.size)
        val overrides = ArrayList<Int>()
        for ((final, build) in kept.withIndex()) {
            out.raw(entries[build].bytes)
            if (entries[build].state.version != fileVersion) overrides.add(final)
        }
        out.uvarint(overrides.size)
        var prev = 0
        for (final in overrides) {
            out.uvarint(final - prev)
            out.i32(entries[kept[final]].state.version)
            prev = final
        }
        return remap
    }

    val size: Int get() = entries.size

    private fun compare(a: Entry, b: Entry): Int {
        if (a.count != b.count) return b.count.compareTo(a.count)
        val v = compareBytes(a.bytes, b.bytes)
        if (v != 0) return v
        return a.state.version.compareTo(b.state.version)
    }

    private fun checkState(s: BlockState) {
        checkString(s.name, "block state name")
        if (s.properties.size > Limits.MAX_PROPERTIES) {
            throw InvalidContentException("block state \"${s.name}\" has ${s.properties.size} properties, limit ${Limits.MAX_PROPERTIES}")
        }
        for ((k, v) in s.properties) {
            checkString(k, "block state property name")
            if (v is PropertyValue.StringValue) checkString(v.value, "block state property value")
        }
    }
}

/** The biome palette builder of format.md §3.2: descending reference count, ties by name. */
internal class BiomePaletteBuilder {
    private class Entry(val name: String) {
        var count = 0L
        var uniform = 0L
    }

    private val entries = ArrayList<Entry>()
    private val ids = HashMap<String, Int>()

    fun id(name: String): Int = ids.getOrPut(name) {
        entries.add(Entry(name))
        entries.size - 1
    }

    fun count(id: Int, uniform: Boolean) {
        entries[id].count++
        if (uniform) entries[id].uniform++
    }

    fun name(id: Int): String = entries[id].name

    fun finalize(out: ByteWriter): IntArray {
        if (entries.size > Limits.MAX_PALETTE) {
            throw InvalidContentException("${entries.size} biomes exceeds limit ${Limits.MAX_PALETTE}")
        }
        val order = (0 until entries.size).sortedWith { a, b ->
            if (entries[a].count != entries[b].count) entries[b].count.compareTo(entries[a].count)
            else Utf8.compare(entries[a].name, entries[b].name)
        }
        val remap = IntArray(entries.size)
        order.forEachIndexed { final, build -> remap[build] = final }
        out.uvarint(entries.size)
        for (build in order) {
            val name = entries[build].name
            checkString(name, "biome name")
            if (!name.contains(':')) throw InvalidContentException("biome name \"$name\" is not namespaced")
            out.string(name)
        }
        return remap
    }

    /** Encodes the palette in first-seen order and returns an identity remap. */
    fun finalizeFirstSeen(out: ByteWriter): IntArray {
        out.uvarint(entries.size)
        for (e in entries) {
            checkString(e.name, "biome name")
            if (!e.name.contains(':')) throw InvalidContentException("biome name \"${e.name}\" is not namespaced")
            out.string(e.name)
        }
        return IntArray(entries.size) { it }
    }

    val size: Int get() = entries.size

    /**
     * The §4.7 default election over final references: the biome with the most uniform sections,
     * ties broken by the lowest reference; none when nothing is uniform or the winner exceeds 16 bits.
     */
    fun electDefault(remap: IntArray): Int {
        var bestRef = -1
        var bestCount = 0L
        for ((build, e) in entries.withIndex()) {
            if (e.uniform == 0L) continue
            val ref = remap[build]
            if (e.uniform > bestCount || (e.uniform == bestCount && ref < bestRef)) {
                bestCount = e.uniform
                bestRef = ref
            }
        }
        return if (bestRef in 0..0xFFFF) bestRef else -1
    }
}

internal fun compareBytes(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val v = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (v != 0) return v
    }
    return a.size.compareTo(b.size)
}

internal fun checkString(s: String, what: String) {
    val n = s.toByteArray(Charsets.UTF_8).size
    if (n > Limits.MAX_STRING) throw InvalidContentException("$what is $n bytes, limit ${Limits.MAX_STRING}")
}

internal fun checkBlobSize(b: ByteArray, what: String) {
    if (b.size > Limits.MAX_BLOB) throw InvalidContentException("$what is ${b.size} bytes, limit ${Limits.MAX_BLOB}")
}
