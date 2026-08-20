package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.BlockState
import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.format.PropertyValue
import net.justmcpe.pile.pnx.convert.Biomes
import net.justmcpe.pile.pnx.convert.BlockStates
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.powernukkitx.block.BlockID
import org.powernukkitx.block.BlockUnknown
import java.nio.file.Files
import java.nio.file.Path

class BlockStatesTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registries() = PnxRegistries.ensure()

        val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")
        val golden: Path = Path.of("..", "conformance", "testdata", "upstream", "golden")
    }

    @Test
    fun `vanilla states resolve by hash`() {
        val stone = BlockStates.resolve(BlockState("minecraft:stone", emptyMap(), BlockStates.currentVersion))
        assertEquals(BlockID.STONE, stone.identifier)
        val air = BlockStates.resolve(BlockState.air(BlockStates.currentVersion))
        assertEquals(BlockID.AIR, air.identifier)
    }

    @Test
    fun `a state with properties round trips through PNX`() {
        val water = BlockState(
            "minecraft:water",
            mapOf("liquid_depth" to PropertyValue.IntValue(8)),
            BlockStates.currentVersion
        )
        val pnx = BlockStates.resolve(water)
        assertEquals(BlockID.WATER, pnx.identifier)
        assertEquals(water, BlockStates.of(pnx))
    }

    @Test
    fun `unknown states are kept rather than collapsed to air`() {
        val weird =
            BlockState("example:not_a_block", mapOf("k" to PropertyValue.StringValue("v")), BlockStates.currentVersion)
        val pnx = BlockStates.resolve(weird)
        assertEquals(BlockUnknown.PROPERTIES.defaultState.identifier, pnx.identifier)
        assertNotEquals(BlockUnknown.PROPERTIES.defaultState, pnx)
    }

    @Test
    fun `every state in the upstream fixtures resolves`() {
        val files = Files.list(vectors).use { it.filter { p -> p.fileName.toString().startsWith("world_") }.toList() } +
                Files.list(golden).use {
                    it.filter { p ->
                        p.fileName.toString().startsWith("golden_world") && !p.fileName.toString().contains("unknown")
                    }.toList()
                }
        val unresolved = ArrayList<String>()
        for (f in files) {
            val w = PileReader.readWorld(Files.readAllBytes(f))
            for (s in w.blockStates) {
                val pnx = BlockStates.resolve(s)
                if (pnx.identifier == BlockID.UNKNOWN && s.name.startsWith("minecraft:")) unresolved.add("${f.fileName}: $s")
            }
        }
        assertTrue(unresolved.isEmpty(), unresolved.joinToString("\n"))
    }

    @Test
    fun `every state in a converted lobby resolves`() {
        val lobby = System.getProperty("pile.lobby").orEmpty()
        assumeTrue(lobby.isNotEmpty() && Files.exists(Path.of(lobby)), "PILE_LOBBY not set")
        val w = PileReader.readWorld(Files.readAllBytes(Path.of(lobby)))
        val unresolved = w.blockStates.filter { BlockStates.resolve(it).identifier == BlockID.UNKNOWN }
        assertTrue(unresolved.isEmpty(), "unresolved: $unresolved")
    }

    @Test
    fun `biomes resolve by name and fall back to plains`() {
        assertEquals(1, Biomes.id("minecraft:plains"))
        assertEquals(0, Biomes.id("minecraft:ocean"))
        assertEquals(1, Biomes.id("example:nowhere"))
        assertEquals("minecraft:ocean", Biomes.name(0))
    }
}
