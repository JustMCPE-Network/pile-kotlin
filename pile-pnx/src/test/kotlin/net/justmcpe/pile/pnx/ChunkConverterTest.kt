package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.pnx.convert.ChunkConverter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.powernukkitx.block.BlockID
import org.powernukkitx.level.format.LevelProvider
import java.nio.file.Files
import java.nio.file.Path

class ChunkConverterTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registries() = PnxRegistries.ensure()

        val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")
    }

    private fun provider(): LevelProvider = FakeProvider()

    @Test
    fun `minimal world becomes a chunk with a stone section at the bottom`() {
        val w = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_minimal.pile")))
        val chunk = ChunkConverter(w).convert(provider(), w.columns.single())
        assertEquals(BlockID.STONE, chunk.getBlockState(0, -64, 0).identifier)
        assertEquals(BlockID.STONE, chunk.getBlockState(15, -49, 15).identifier)
        assertEquals(BlockID.AIR, chunk.getBlockState(0, -48, 0).identifier)
        assertEquals(0, chunk.getBiomeId(3, -60, 3))
        assertTrue(chunk.isFinished)
        assertTrue(chunk.isLightPopulated)
        assertEquals(-49, chunk.getHeightMap(0, 0))
    }

    @Test
    fun `waterlogged section keeps water in layer 1 over air in layer 0`() {
        val w = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_waterlogged.pile")))
        val chunk = ChunkConverter(w).convert(provider(), w.columns.single())
        val section = chunk.sections.first { it != null }!!
        var water = 0
        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
            if (section.getBlockState(x, y, z, 1).identifier == BlockID.WATER) water++
        }
        assertTrue(water > 0)
        assertEquals(BlockID.AIR, section.getBlockState(0, 0, 0, 0).identifier)
    }

    @Test
    fun `collections vector carries block entities and entities into the chunk`() {
        val w = PileReader.readWorld(Files.readAllBytes(vectors.resolve("world_collections.pile")))
        val column = w.columns.single()
        val chunk = ChunkConverter(w).convert(provider(), column)
        assertNotNull(chunk)
        assertEquals(column.blockEntities.size, (chunk as org.powernukkitx.level.format.Chunk).let { c ->
            val f = org.powernukkitx.level.format.Chunk::class.java.getDeclaredField("blockEntityNBT")
            f.isAccessible = true
            (f.get(c) as List<*>).size
        })
    }

    @Test
    fun `a converted lobby builds every column`() {
        val lobby = System.getProperty("pile.lobby").orEmpty()
        assumeTrue(lobby.isNotEmpty() && Files.exists(Path.of(lobby)), "PILE_LOBBY not set")
        val w = PileReader.readWorld(Files.readAllBytes(Path.of(lobby)))
        val converter = ChunkConverter(w)
        val provider = provider()
        val t0 = System.nanoTime()
        var nonAir = 0
        for (c in w.columns) {
            val chunk = converter.convert(provider, c)
            if (chunk.getBlockState(8, 64, 8).identifier != BlockID.AIR) nonAir++
        }
        println("converted ${w.columns.size} columns in ${(System.nanoTime() - t0) / 1_000_000} ms, $nonAir with a block at (8,64,8)")
        assertTrue(nonAir > 0)
    }
}
