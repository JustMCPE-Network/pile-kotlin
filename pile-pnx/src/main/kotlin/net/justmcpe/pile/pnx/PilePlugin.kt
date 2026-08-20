package net.justmcpe.pile.pnx

import org.powernukkitx.plugin.PluginBase

/** Registers the pile level provider when the adapter is installed as a plugin rather than embedded. */
public class PilePlugin : PluginBase() {
    override fun onLoad() {
        PileLevelProvider.register()
    }
}
