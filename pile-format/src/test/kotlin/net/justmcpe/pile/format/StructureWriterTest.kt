package net.justmcpe.pile.format

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StructureWriterTest {
    private val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")

    @Test
    fun `structure vectors re-encode byte-identically`() {
        for (name in listOf("structure_edge_padding.pile", "structure_full.pile")) {
            val file = Files.readAllBytes(vectors.resolve(name))
            val structure = PileReader.readStructure(file)
            assertArrayEquals(file, PileWriter.writeStructure(structure, Compression.NONE), name)
        }
    }

    @Test
    fun `padding outside the box does not reach the file`() {
        val file = Files.readAllBytes(vectors.resolve("structure_edge_padding.pile"))
        val s = PileReader.readStructure(file)
        // Grow a padded copy: put stone into an edge cell's padding and require identical bytes.
        val stone = s.blockStates.indexOfFirst { it.name == "minecraft:stone" }
        assertTrue(stone >= 0)
        val cells = s.cells.copyOf()
        val edge = cells.indices.last { cells[it] != null }
        val layer = cells[edge]!!.layers[0]
        val refs = IntArray(Limits.STORAGE_SIZE) { layer[it] }
        refs[Storage.index(15, 15, 15)] = stone
        cells[edge] = Section(listOf(Storage.of(refs)))
        val padded = Structure(
            s.blockVersion, s.userData, s.blockStates, s.sizeX, s.sizeY, s.sizeZ,
            s.originX, s.originY, s.originZ, cells, s.blockEntities, s.entities,
        )
        assertArrayEquals(
            PileWriter.writeStructure(s, Compression.NONE),
            PileWriter.writeStructure(padded, Compression.NONE)
        )
    }

    @Test
    fun `structure content hash is stable across compression`() {
        val file = Files.readAllBytes(vectors.resolve("structure_full.pile"))
        val s = PileReader.readStructure(file)
        val compressed = PileReader.readStructure(PileWriter.writeStructure(s, Compression.BEST))
        assertEquals(PileWriter.contentHash(s), PileWriter.contentHash(compressed))
    }
}
