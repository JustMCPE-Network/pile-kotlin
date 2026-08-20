package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.NbtCompound
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class PileWriterTest {
    private val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")

    private fun positives(): List<Path> = Files.list(vectors).use { s ->
        s.filter { it.name.startsWith("world_") && it.name.endsWith(".pile") }.sorted().toList()
    }

    @Test
    fun `decode and re-encode is byte-identical for every uncompressed vector`() {
        for (path in positives()) {
            val file = Files.readAllBytes(path)
            val meta = PileReader.readMeta(file)
            val world = PileReader.readWorld(file)
            val out = PileWriter.writeWorld(
                world,
                WriteOptions(Compression.NONE, stats = meta.header.hasStats, storeLight = meta.header.storeLight),
            )
            assertArrayEquals(file, out, path.name)
        }
    }

    @Test
    fun `encoding twice yields identical bytes, compressed included`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_collections.pile")))
        val a = PileWriter.writeWorld(world, WriteOptions(Compression.BEST))
        val b = PileWriter.writeWorld(world, WriteOptions(Compression.BEST))
        assertArrayEquals(a, b)
        assertArrayEquals(world.settings, PileReader.readWorld(a).settings)
    }

    @Test
    fun `compression does not change content identity`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_dedup_morton.pile")))
        val compressed = PileReader.readWorld(PileWriter.writeWorld(world, WriteOptions(Compression.BEST)))
        assertEquals(PileWriter.contentHash(world), PileWriter.contentHash(compressed))
    }

    @Test
    fun `an all-air section becomes absent rather than a file the reader refuses`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val air = world.blockStates.size
        val states = world.blockStates + BlockState.air(world.blockVersion)
        val column = world.columns.single()
        val withAir = Column(
            column.x, column.z, column.minSection,
            arrayOf(Section(listOf(Storage.uniform(air)))),
            column.biomes, null, emptyList(), emptyList(), 0, emptyList(), ByteArray(0),
        )
        val out = PileWriter.writeWorld(
            World(
                world.blockVersion,
                ByteArray(0),
                ByteArray(0),
                states,
                world.biomes,
                listOf(withAir)
            )
        )
        val back = PileReader.readWorld(out)
        assertTrue(back.columns.single().sections.all { it == null })
        assertTrue(back.blockStates.isEmpty())
    }

    @Test
    fun `waterlogged content keeps its internal air layer through a round trip`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_waterlogged.pile")))
        val back = PileReader.readWorld(PileWriter.writeWorld(world))
        val section = back.columns.single().sections.filterNotNull().single()
        assertEquals(2, section.layers.size)
        assertTrue(back.blockStates[section.layers[0][0]].isAir)
    }

    @Test
    fun `duplicate columns and duplicate block entities are refused`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val twice = World(
            world.blockVersion,
            world.settings,
            world.userData,
            world.blockStates,
            world.biomes,
            world.columns + world.columns
        )
        assertThrows(InvalidContentException::class.java) { PileWriter.writeWorld(twice) }

        val c = world.columns.single()
        val be = BlockEntity.of(1, -60, 1, NbtCompound.build { put("id", "Chest") })
        val dup = Column(
            c.x,
            c.z,
            c.minSection,
            c.sections,
            c.biomes,
            null,
            listOf(be, be),
            emptyList(),
            0,
            emptyList(),
            ByteArray(0)
        )
        assertThrows(InvalidContentException::class.java) {
            PileWriter.writeWorld(
                World(
                    world.blockVersion,
                    world.settings,
                    world.userData,
                    world.blockStates,
                    world.biomes,
                    listOf(dup)
                )
            )
        }
    }

    @Test
    fun `palette order is content-derived, not input order`() {
        val version = 18040335
        val states = listOf(
            BlockState("minecraft:stone", emptyMap(), version),
            BlockState("minecraft:dirt", emptyMap(), version),
        )

        fun column(x: Int, ref: Int) = Column(
            x, 0, -4, arrayOf(Section(listOf(Storage.uniform(ref)))),
            arrayOf(Storage.uniform(0)), null, emptyList(), emptyList(), 0, emptyList(), ByteArray(0),
        )

        val world = World(
            version,
            ByteArray(0),
            ByteArray(0),
            states,
            listOf("minecraft:plains"),
            listOf(column(0, 1), column(1, 1), column(2, 0))
        )
        val back = PileReader.readWorld(PileWriter.writeWorld(world))
        assertEquals(listOf("minecraft:dirt", "minecraft:stone"), back.blockStates.map { it.name })
    }

    @Test
    fun `skipBiomes writes the same bytes as upstream's nobiomes golden`() {
        val golden = Path.of("..", "conformance", "testdata", "upstream", "golden", "golden_world_nobiomes.pile")
        val world = PileReader.readWorld(Files.readAllBytes(golden))
        val out = PileWriter.writeWorld(world, WriteOptions(Compression.NONE, skipBiomes = true))
        assertArrayEquals(Files.readAllBytes(golden), out)
    }

    @Test
    fun `fast compression keeps content identity`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_collections.pile")))
        val fast = PileReader.readWorld(
            PileWriter.writeWorld(
                world,
                WriteOptions(Compression.DEFAULT, fastCompression = true)
            )
        )
        assertEquals(PileWriter.contentHash(world), PileWriter.contentHash(fast))
    }

    @Test
    fun `atomic path write lands complete or not at all`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val dir = Files.createTempDirectory("pile-writer")
        try {
            val target = dir.resolve("overworld.pile")
            PileWriter.writeWorld(target, world)
            assertEquals(
                PileWriter.contentHash(world),
                PileWriter.contentHash(PileReader.readWorld(Files.readAllBytes(target)))
            )
            assertTrue(Files.list(dir).use { it.toList() }.size == 1)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
