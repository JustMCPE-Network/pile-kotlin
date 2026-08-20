package net.justmcpe.pile.pnx

import org.powernukkitx.registry.Registries

/** PNX's registries, initialised once per test JVM without a server. */
object PnxRegistries {
    @Volatile
    private var ready = false

    @Synchronized
    fun ensure() {
        if (ready) return
        Registries.BLOCK.init()
        Registries.BIOME.init()
        Registries.BLOCKENTITY.init()
        Registries.ENTITY.init()
        ready = true
    }
}
