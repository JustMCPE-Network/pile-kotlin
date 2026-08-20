package net.justmcpe.pile.pnx.convert

import it.unimi.dsi.fastutil.objects.ReferenceArrayList
import net.justmcpe.pile.format.Storage
import org.powernukkitx.level.format.bitarray.BitArrayVersion
import org.powernukkitx.level.format.palette.BlockPalette
import org.powernukkitx.level.format.palette.Palette
import org.powernukkitx.block.BlockState as PnxBlockState

/** Palettes filled from a decoded storage without going through per-block lookups. */
internal object PnxPalettes {
    /** V0 is a singleton PNX cannot serialise to the network, so one bit is the floor. */
    fun versionFor(entries: Int): BitArrayVersion {
        var bits = 1
        while ((1 shl bits) < entries) bits++
        return BitArrayVersion.forBitsCeil(bits) ?: BitArrayVersion.V16
    }

    fun blocks(storage: Storage, resolve: (Int) -> PnxBlockState): BlockPalette = FilledBlockPalette(storage, resolve)

    fun biomes(storage: Storage, resolve: (Int) -> Int): Palette<Int> = FilledBiomePalette(storage, resolve)

    private class FilledBlockPalette(storage: Storage, resolve: (Int) -> PnxBlockState) :
        BlockPalette(resolve(storage.palette[0]), ReferenceArrayList(16), versionFor(storage.palette.size)) {
        init {
            for (i in 1 until storage.palette.size) addToPalette(resolve(storage.palette[i]))
            val idx = storage.indices
            if (idx != null) for (i in idx.indices) bitArray.set(i, idx[i].toInt() and 0xFFFF)
        }
    }

    private class FilledBiomePalette(storage: Storage, resolve: (Int) -> Int) :
        Palette<Int>(resolve(storage.palette[0]), ArrayList<Int>(16), versionFor(storage.palette.size)) {
        init {
            for (i in 1 until storage.palette.size) addToPalette(resolve(storage.palette[i]))
            val idx = storage.indices
            if (idx != null) for (i in idx.indices) bitArray.set(i, idx[i].toInt() and 0xFFFF)
        }
    }
}
