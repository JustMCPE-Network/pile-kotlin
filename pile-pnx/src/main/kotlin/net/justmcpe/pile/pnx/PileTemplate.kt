package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.DecodeOptions
import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.format.World
import org.powernukkitx.level.DimensionData
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The minigame primitive: one decoded base world, any number of throwaway copy-on-write instances.
 *
 * An instance is an in-memory [PileLevelProvider]: chunks build from the shared base columns,
 * edits stay in the instance, closing the level evaporates them and the base stays pristine. Hand
 * [instancePath] to PNX as the level path together with [PileLevelProvider] as the provider class;
 * [PileLevelProvider.saveAs] keeps an instance worth keeping.
 */
public class PileTemplate private constructor(private val base: World) {
    /** Registers a fresh instance and returns the synthetic path PNX creates the level with. */
    public fun instancePath(): String {
        val key = "$PREFIX${counter.incrementAndGet()}"
        instances[key] = base
        return key
    }

    public companion object {
        private const val PREFIX = "pile-instance:"
        private val counter = AtomicLong()
        private val instances = ConcurrentHashMap<String, World>()

        /** Decodes the base world once; every instance shares it. */
        public fun open(
            dir: Path,
            dimension: DimensionData,
            options: DecodeOptions = DecodeOptions.DEFAULT
        ): PileTemplate {
            val file = PileLevelProvider.dimensionFile(dir, dimension)
            return PileTemplate(PileReader.readWorld(Files.readAllBytes(file), options))
        }

        /** A template with no base: generated arenas, as the upstream `NewMemory`. */
        public fun memory(name: String, dimension: DimensionData): PileTemplate =
            PileTemplate(PileLevelProvider.emptyWorld(name, dimension))

        internal fun claim(path: String): World? = instances[path]

        internal fun release(path: String) {
            instances.remove(path)
        }

        internal fun isInstancePath(path: String): Boolean = path.startsWith(PREFIX)
    }
}
