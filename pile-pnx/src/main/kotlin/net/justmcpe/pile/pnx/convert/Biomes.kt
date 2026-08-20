package net.justmcpe.pile.pnx.convert

import org.powernukkitx.level.biome.BiomeID
import org.powernukkitx.registry.Registries
import java.util.concurrent.ConcurrentHashMap

/** Biome names as the format stores them to PNX's numeric ids and back. Unknown names fall back to plains. */
public object Biomes {
    private val ids = ConcurrentHashMap<String, Int>()
    private val names = ConcurrentHashMap<Int, String>()

    public fun id(name: String): Int = ids.computeIfAbsent(name) { n ->
        val bare = n.substringAfter(':')
        val id = Registries.BIOME.getBiomeId(n)
        if (Registries.BIOME.getBiomeName(id) == bare) id else BiomeID.PLAINS
    }

    public fun name(id: Int): String = names.computeIfAbsent(id) { i ->
        val bare = Registries.BIOME.getBiomeName(i) ?: "plains"
        "minecraft:$bare"
    }
}
