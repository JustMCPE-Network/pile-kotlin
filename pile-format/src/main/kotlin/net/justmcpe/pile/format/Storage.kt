package net.justmcpe.pile.format

import net.justmcpe.pile.format.wire.IntIntMap

/**
 * One 16³ paletted storage: a block layer or a section's biomes. [palette] holds references into the
 * world's block state or biome list; [indices], when present, holds 4096 local palette indices in the
 * order of format.md §1. A null [indices] means every position holds `palette[0]`.
 */
public class Storage(public val palette: IntArray, public val indices: ShortArray?) {

    init {
        require(palette.isNotEmpty()) { "a storage needs at least one palette entry" }
        require(indices == null || indices.size == Limits.STORAGE_SIZE) { "a storage holds exactly 4096 indices" }
        require(indices != null || palette.size == 1) { "a uniform storage has one palette entry" }
    }

    public val isUniform: Boolean get() = indices == null

    /** The global reference at local index [i] (format.md §1). */
    public operator fun get(i: Int): Int = if (indices == null) palette[0] else palette[indices[i].toInt() and 0xFFFF]

    public fun get(x: Int, y: Int, z: Int): Int = get((x shl 8) or (z shl 4) or y)

    public companion object {
        public fun uniform(ref: Int): Storage = Storage(intArrayOf(ref), null)

        /** Builds a storage from 4096 global references, compacting the palette to the references used. */
        public fun of(refs: IntArray): Storage {
            require(refs.size == Limits.STORAGE_SIZE)
            val seen = IntIntMap(16)
            var palette = IntArray(8)
            var paletteSize = 0
            val idx = ShortArray(Limits.STORAGE_SIZE)
            for (i in refs.indices) {
                val ref = refs[i]
                var local = seen.getOrDefault(ref, -1)
                if (local < 0) {
                    if (paletteSize == palette.size) palette = palette.copyOf(palette.size * 2)
                    palette[paletteSize] = ref
                    local = paletteSize++
                    seen.put(ref, local)
                }
                idx[i] = local.toShort()
            }
            return if (paletteSize == 1) uniform(palette[0]) else Storage(palette.copyOf(paletteSize), idx)
        }

        public fun index(x: Int, y: Int, z: Int): Int = (x shl 8) or (z shl 4) or y
    }
}
