package net.justmcpe.pile.conformance

import net.justmcpe.pile.format.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * The other half of the round trip: files this implementation writes, checked by the reference
 * implementation's CLI. Runs when `pile` (go install github.com/oriumgames/pile/cmd/pile) is on the
 * PATH or named by PILE_CLI; skipped otherwise, so the pure-JVM suite needs no Go toolchain.
 */
class CliRoundTripTest {
    companion object {
        private val cli: String? = sequenceOf(System.getenv("PILE_CLI"), "pile")
            .filterNotNull()
            .firstOrNull { runCatching { run(it, "version").second == 0 }.getOrDefault(false) }

        @JvmStatic
        @BeforeAll
        fun requireCli() {
            assumeTrue(cli != null, "upstream pile CLI not found; set PILE_CLI")
        }

        private fun run(vararg command: String): Pair<String, Int> {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor(120, TimeUnit.SECONDS)) { "timed out: ${command.joinToString(" ")}" }
            return out to process.exitValue()
        }
    }

    private fun verify(file: Path): String {
        val (out, code) = run(cli!!, "verify", file.toString())
        assertEquals(0, code, "pile verify refused ${file.name}: $out")
        return out
    }

    private fun cliHash(file: Path): String {
        val (out, code) = run(cli!!, "hash", file.toString())
        assertEquals(0, code, "pile hash failed on ${file.name}: $out")
        return Regex("file\\s+([0-9a-f]{16})").find(out)?.groupValues?.get(1) ?: error("no hash in: $out")
    }

    @TestFactory
    fun `worlds this implementation writes pass pile verify with the same content hash`() =
        (Fixtures.positiveVectors() + Fixtures.solidGoldens())
            .filter { !it.name.startsWith("structure") && !it.name.startsWith("golden_structure") }
            .map { source ->
                DynamicTest.dynamicTest(source.name) {
                    val world = PileReader.readWorld(Files.readAllBytes(source))
                    val dir = Files.createTempDirectory("pile-cli")
                    try {
                        val file = dir.resolve("overworld.pile")
                        PileWriter.writeWorld(file, world, WriteOptions(Compression.BEST, stats = true))
                        verify(file)
                        assertEquals(XxHash.hex(PileWriter.contentHash(world)), cliHash(file))
                    } finally {
                        dir.toFile().deleteRecursively()
                    }
                }
            }

    // `pile verify` decodes worlds only; `pile hash` decodes and canonically re-encodes a
    // structure, which exercises the same read path and checks content identity with it.
    @TestFactory
    fun `structures this implementation writes hash identically under the reference CLI`() =
        (Fixtures.positiveVectors() + Fixtures.solidGoldens())
            .filter { it.name.startsWith("structure") || it.name.startsWith("golden_structure") }
            .map { source ->
                DynamicTest.dynamicTest(source.name) {
                    val structure = PileReader.readStructure(Files.readAllBytes(source))
                    val dir = Files.createTempDirectory("pile-cli")
                    try {
                        val file = dir.resolve("out.pile")
                        PileWriter.writeStructure(file, structure)
                        assertEquals(XxHash.hex(PileWriter.contentHash(structure)), cliHash(file))
                    } finally {
                        dir.toFile().deleteRecursively()
                    }
                }
            }

    @Test
    fun `an indexed file this implementation writes verifies and hashes the same`() {
        val world = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_collections.pile")))
        val dir = Files.createTempDirectory("pile-cli")
        try {
            val file = dir.resolve("overworld.pile")
            Files.write(file, PileWriter.writeIndexed(world, Compression.DEFAULT))
            verify(file)
            val (out, code) = run(cli!!, "hash", dir.toString())
            assertEquals(0, code, out)
            assertTrue(out.contains(XxHash.hex(PileWriter.contentHash(world))), out)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an IndexedPile handle's stores and checkpoints verify under the reference CLI`() {
        val world = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_collections.pile")))
        val dir = Files.createTempDirectory("pile-cli")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, world.blockVersion).use { pile ->
                pile.setMeta(world.settings, world.userData)
                for (c in world.columns) pile.store(c, world.blockStates, world.biomes)
                pile.checkpoint()
                for (c in world.columns) pile.store(c, world.blockStates, world.biomes)
            }
            verify(file)
            val (out, code) = run(cli!!, "hash", dir.toString())
            assertEquals(0, code, out)
            assertTrue(out.contains(XxHash.hex(PileWriter.contentHash(world))), out)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a compacted file with a trained dictionary verifies under the reference CLI`() {
        val base = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_palette_257.pile")))
        val template = base.columns.single()
        val columns = (0 until 32).map { i ->
            Column(
                i % 16, i / 16, template.minSection, template.sections, template.biomes, null,
                emptyList(), emptyList(), 0, emptyList(), ByteArray(0),
            )
        }
        val world = World(base.blockVersion, base.settings, base.userData, base.blockStates, base.biomes, columns)
        val dir = Files.createTempDirectory("pile-cli")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, world.blockVersion, Compression.DEFAULT).use { pile ->
                pile.setMeta(world.settings, world.userData)
                for (c in world.columns) pile.store(c, world.blockStates, world.biomes)
                pile.compact()
                assertTrue(pile.hasDictionary, "training material was sufficient")
            }
            verify(file)
            val (out, code) = run(cli!!, "hash", dir.toString())
            assertEquals(0, code, out)
            assertTrue(out.contains(XxHash.hex(PileWriter.contentHash(world))), out)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a large real world round trips through both implementations`() {
        val lobby = System.getProperty("pile.lobby").orEmpty()
        assumeTrue(lobby.isNotEmpty() && Files.exists(Path.of(lobby)), "PILE_LOBBY not set")
        val source = Path.of(lobby)
        val world = PileReader.readWorld(Files.readAllBytes(source))
        val dir = Files.createTempDirectory("pile-cli")
        try {
            val file = dir.resolve("overworld.pile")
            PileWriter.writeWorld(file, world, WriteOptions(Compression.BEST, stats = true))
            verify(file)
            assertEquals(cliHash(source), cliHash(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
