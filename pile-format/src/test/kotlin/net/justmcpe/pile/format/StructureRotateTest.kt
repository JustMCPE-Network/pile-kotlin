package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtFloat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StructureRotateTest {
    private val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")

    @Test
    fun `four quarter turns restore the grid exactly and entities within float error`() {
        val s = PileReader.readStructure(Files.readAllBytes(vectors.resolve("structure_full.pile")))
        val blocksOnly = Structure(
            s.blockVersion, s.userData, s.blockStates, s.sizeX, s.sizeY, s.sizeZ,
            0, 0, 0, s.cells, s.blockEntities, emptyList(),
        )
        // Blocks and block entities live on the integer grid, so four turns are byte-identical.
        assertEquals(PileWriter.contentHash(blocksOnly), PileWriter.contentHash(blocksOnly.rotate(1).rotate(3)))
        assertEquals(PileWriter.contentHash(blocksOnly.rotate(-1)), PileWriter.contentHash(blocksOnly.rotate(3)))
        // Entity positions are floats; sizeZ - (sizeZ - x) need not restore the bit pattern, so
        // the round trip is asserted within float tolerance rather than on the bytes.
        val round = s.rotate(1).rotate(1).rotate(1).rotate(1)
        for (i in s.entities.indices) {
            val a = Nbt.decode(s.entities[i]).getList("Pos") ?: continue
            val b = Nbt.decode(round.entities[i]).getList("Pos")!!
            for (axis in 0..2) {
                val va = (a[axis] as NbtFloat).value
                val vb = (b[axis] as NbtFloat).value
                org.junit.jupiter.api.Assertions.assertEquals(va, vb, 1e-3f, "entity $i axis $axis")
            }
        }
    }

    @Test
    fun `one turn swaps the box and moves blocks where a clockwise turn puts them`() {
        val version = 18040335
        val stone = BlockState("minecraft:stone", emptyMap(), version)
        val cells = arrayOfNulls<Section>(1)
        val refs = IntArray(Limits.STORAGE_SIZE)
        refs[Storage.index(2, 0, 1)] = 1
        cells[0] = Section(listOf(Storage.of(refs)))
        val s = Structure(
            version,
            ByteArray(0),
            listOf(BlockState.air(version), stone),
            5,
            1,
            3,
            0,
            0,
            0,
            cells,
            emptyList(),
            emptyList()
        )
        val r = s.rotate(1)
        assertEquals(3, r.sizeX)
        assertEquals(5, r.sizeZ)
        // (x=2, z=1) in a 5x3 box lands at (sizeZ-1-z, x) = (1, 2).
        val section = r.cells[0]!!
        assertEquals("minecraft:stone", r.blockStates[section.layers[0].get(1, 0, 2)].name)
    }

    @Test
    fun `direction properties rotate with the geometry`() {
        val version = 18040335
        val stairs = BlockState(
            "minecraft:oak_stairs",
            mapOf("upside_down_bit" to PropertyValue.ByteValue(0), "weirdo_direction" to PropertyValue.IntValue(0)),
            version,
        )
        val log = BlockState("minecraft:oak_log", mapOf("pillar_axis" to PropertyValue.StringValue("x")), version)
        val chest = BlockState(
            "minecraft:chest",
            mapOf("minecraft:cardinal_direction" to PropertyValue.StringValue("north")),
            version
        )
        val refs = IntArray(Limits.STORAGE_SIZE)
        refs[Storage.index(0, 0, 0)] = 1
        refs[Storage.index(1, 0, 0)] = 2
        refs[Storage.index(2, 0, 0)] = 3
        val s = Structure(
            version, ByteArray(0),
            listOf(BlockState.air(version), stairs, log, chest),
            16, 1, 16, 0, 0, 0,
            arrayOf(Section(listOf(Storage.of(refs)))), emptyList(), emptyList(),
        )
        val r = s.rotate(1)
        val byName = r.blockStates.associateBy { it.name }
        assertEquals(PropertyValue.IntValue(2), byName.getValue("minecraft:oak_stairs").properties["weirdo_direction"])
        assertEquals(PropertyValue.StringValue("z"), byName.getValue("minecraft:oak_log").properties["pillar_axis"])
        assertEquals(
            PropertyValue.StringValue("east"),
            byName.getValue("minecraft:chest").properties["minecraft:cardinal_direction"]
        )
    }
}
