package net.justmcpe.pile.format

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class IndexedTest {
    private val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")
    private val golden: Path = Path.of("..", "conformance", "testdata", "upstream", "golden")

    @Test
    fun `indexed_full decodes to what vectors_md claims`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("indexed_full.pile")))
        assertEquals(22, world.columns.size)
        val positions = world.columns.map { it.x to it.z }.toSet()
        val expected = buildSet {
            for (x in 0..4) for (z in 0..3) add(x to z)
            add(0 to 4)
            add(1 to 4)
        }
        assertEquals(expected, positions)
        val origin = world.column(0, 0)!!
        val section = origin.sections[origin.sections.indexOfFirst { it != null }]!!
        assertEquals("minecraft:bedrock", world.blockStates[section.layers[0].get(0, 0, 0)].name)
        val settings = WorldSettings.parse(world.settings)
        assertEquals("indexed vector", settings.name)
        assertEquals(6000L, settings.time)
        assertEquals(1, settings.difficulty)
        assertEquals(23, world.userData.size)
        assertEquals("138060de51114925", XxHash.hex(PileWriter.contentHash(world)))
    }

    @Test
    fun `indexed_torn recovers the checkpoint before the torn footer`() {
        val world = PileReader.readWorld(Files.readAllBytes(vectors.resolve("indexed_torn.pile")))
        assertEquals("91e8c6cec870044e", XxHash.hex(PileWriter.contentHash(world)))
    }

    @Test
    fun `golden indexed files decode to their manifest content hashes`() {
        for ((name, want) in listOf(
            "golden_indexed" to "bcf8616f7000c636",
            "golden_indexed_zstd" to "bcf8616f7000c636"
        )) {
            val world = PileReader.readWorld(Files.readAllBytes(golden.resolve("$name.pile")))
            assertEquals(want, XxHash.hex(PileWriter.contentHash(world)), name)
        }
    }

    @Test
    fun `writeIndexed round trips through the reader`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_collections.pile")))
        for (compression in listOf(Compression.NONE, Compression.DEFAULT)) {
            val bytes = PileWriter.writeIndexed(solid, compression)
            assertEquals(1, bytes[7].toInt())
            val back = PileReader.readWorld(bytes)
            assertEquals(PileWriter.contentHash(solid), PileWriter.contentHash(back))
        }
    }

    @Test
    fun `appendIndexed adds a checkpoint the reader adopts`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val dir = Files.createTempDirectory("pile-indexed")
        try {
            val path = dir.resolve("overworld.pile")
            Files.write(path, PileWriter.writeIndexed(solid))
            val grown = PileReader.readWorld(Files.readAllBytes(path))
            PileWriter.appendIndexed(path, grown)
            val back = PileReader.readWorld(Files.readAllBytes(path))
            assertEquals(PileWriter.contentHash(solid), PileWriter.contentHash(back))
            assertTrue(Files.size(path) > PileWriter.writeIndexed(solid).size.toLong())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `appendIndexed refuses a grown palette`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val dir = Files.createTempDirectory("pile-indexed")
        try {
            val path = dir.resolve("overworld.pile")
            Files.write(path, PileWriter.writeIndexed(solid))
            val grown = World(
                solid.blockVersion, solid.settings, solid.userData,
                solid.blockStates + BlockState("minecraft:dirt", emptyMap(), solid.blockVersion),
                solid.biomes, solid.columns,
            )
            assertThrows(IllegalArgumentException::class.java) { PileWriter.appendIndexed(path, grown) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `handle decodes indexed_full column by column, dictionary included`() {
        val dir = Files.createTempDirectory("pile-handle")
        try {
            val file = dir.resolve("overworld.pile")
            Files.copy(vectors.resolve("indexed_full.pile"), file)
            IndexedPile.open(file, readOnly = true).use { pile ->
                assertEquals(22, pile.columnCount)
                val eager = PileReader.readWorld(Files.readAllBytes(file))
                val columns = pile.positions().map { (pile.column(it[0], it[1]))!! }
                val world =
                    World(pile.blockVersion, pile.settings, pile.userData, pile.blockStates, pile.biomes, columns)
                assertEquals(PileWriter.contentHash(eager), PileWriter.contentHash(world))
                val origin = pile.column(0, 0)!!
                val section = origin.sections.first { it != null }!!
                assertEquals("minecraft:bedrock", pile.blockStates[section.layers[0].get(0, 0, 0)].name)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `handle store and checkpoint survive reopen and eager read`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_collections.pile")))
        val dir = Files.createTempDirectory("pile-handle")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, solid.blockVersion).use { pile ->
                pile.setMeta(solid.settings, solid.userData)
                for (c in solid.columns) pile.store(c, solid.blockStates, solid.biomes)
                assertEquals(solid.columns.size, pile.columnCount)
                val back = pile.column(solid.columns[0].x, solid.columns[0].z)!!
                assertEquals(solid.columns[0].blockEntities.size, back.blockEntities.size)
            }
            val eager = PileReader.readWorld(Files.readAllBytes(file))
            assertEquals(PileWriter.contentHash(solid), PileWriter.contentHash(eager))
            IndexedPile.open(file).use { pile ->
                assertEquals(solid.columns.size, pile.columnCount)
                assertTrue(pile.settings.contentEquals(solid.settings))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `palette growth lands in a second segment after checkpoint`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val dir = Files.createTempDirectory("pile-handle")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, solid.blockVersion).use { pile ->
                for (c in solid.columns) pile.store(c, solid.blockStates, solid.biomes)
                pile.checkpoint()
                val dirt = BlockState("minecraft:dirt", emptyMap(), solid.blockVersion)
                val grownStates = solid.blockStates + dirt
                val column = solid.columns.single()
                val changed = Column(
                    column.x, column.z, column.minSection,
                    arrayOf(Section(listOf(Storage.uniform(grownStates.size - 1)))),
                    column.biomes, null, emptyList(), emptyList(), 0, emptyList(), ByteArray(0),
                )
                pile.store(changed, grownStates, solid.biomes)
            }
            IndexedPile.open(file, readOnly = true).use { pile ->
                assertEquals("minecraft:dirt", pile.blockStates.last().name)
                val section = pile.column(0, 0)!!.sections.first { it != null }!!
                assertEquals("minecraft:dirt", pile.blockStates[section.layers[0][0]].name)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `store makes garbage and compact removes it`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_dedup_morton.pile")))
        val dir = Files.createTempDirectory("pile-handle")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, solid.blockVersion).use { pile ->
                repeat(4) { for (c in solid.columns) pile.store(c, solid.blockStates, solid.biomes) }
                pile.checkpoint()
                assertTrue(pile.garbageRatio() > 0.3)
                val before = Files.size(file)
                pile.compact()
                assertTrue(Files.size(file) < before)
                assertEquals(solid.columns.size, pile.columnCount)
            }
            val eager = PileReader.readWorld(Files.readAllBytes(file))
            assertEquals(PileWriter.contentHash(solid), PileWriter.contentHash(eager))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a torn tail falls back to the previous checkpoint`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val dir = Files.createTempDirectory("pile-handle")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, solid.blockVersion).use { pile ->
                for (c in solid.columns) pile.store(c, solid.blockStates, solid.biomes)
            }
            val intact = Files.readAllBytes(file)
            Files.write(file, intact + ByteArray(37) { 0x51 })
            IndexedPile.open(file, readOnly = true).use { pile ->
                assertEquals(solid.columns.size, pile.columnCount)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun wideWorld(columns: Int): World {
        val base = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_palette_257.pile")))
        val template = base.columns.single()
        val spread = (0 until columns).map { i ->
            Column(
                i % 16, i / 16, template.minSection, template.sections, template.biomes, null,
                emptyList(), emptyList(), 0, emptyList(), ByteArray(0),
            )
        }
        return World(base.blockVersion, base.settings, base.userData, base.blockStates, base.biomes, spread)
    }

    @Test
    fun `compaction trains a shared dictionary on a large compressed file`() {
        val world = wideWorld(32)
        val dir = Files.createTempDirectory("pile-dict")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, world.blockVersion, Compression.DEFAULT).use { pile ->
                pile.setMeta(world.settings, world.userData)
                for (c in world.columns) pile.store(c, world.blockStates, world.biomes)
                repeat(2) { for (c in world.columns) pile.store(c, world.blockStates, world.biomes) }
                pile.checkpoint()
                assertTrue(!pile.hasDictionary)
                pile.compact()
                assertTrue(pile.hasDictionary, "32 records of 13KB each are past the training floor")
                assertEquals(world.columns.size, pile.columnCount)
                val section = pile.column(0, 0)!!.sections.first { it != null }!!
                assertEquals(257, section.layers[0].palette.size)
            }
            IndexedPile.open(file, readOnly = true).use { pile ->
                assertTrue(pile.hasDictionary)
                assertEquals(world.columns.size, pile.columnCount)
            }
            val eager = PileReader.readWorld(Files.readAllBytes(file))
            assertEquals(PileWriter.contentHash(world), PileWriter.contentHash(eager))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a small world compacts without a dictionary`() {
        val solid = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_dedup_morton.pile")))
        val dir = Files.createTempDirectory("pile-dict")
        try {
            val file = dir.resolve("overworld.pile")
            IndexedPile.create(file, solid.blockVersion, Compression.DEFAULT).use { pile ->
                for (c in solid.columns) pile.store(c, solid.blockStates, solid.biomes)
                pile.compact()
                assertTrue(!pile.hasDictionary)
            }
            assertEquals(
                PileWriter.contentHash(solid),
                PileWriter.contentHash(PileReader.readWorld(Files.readAllBytes(file)))
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
