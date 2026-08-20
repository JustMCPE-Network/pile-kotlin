package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.Compression
import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.format.PileWriter
import net.justmcpe.pile.format.WorldSettings
import net.justmcpe.pile.format.nbt.NbtCompound
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.powernukkitx.block.BlockDirt
import org.powernukkitx.block.BlockStone
import org.powernukkitx.level.DimensionEnum
import org.powernukkitx.level.GameRule
import org.powernukkitx.level.Level
import org.powernukkitx.level.format.IChunk
import org.powernukkitx.level.format.LevelConfig
import org.powernukkitx.level.generator.Generator
import org.powernukkitx.math.Vector3
import org.powernukkitx.nbt.tag.CompoundTag
import java.nio.file.Files
import java.nio.file.Path

class PileLevelProviderTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun registries() = PnxRegistries.ensure()
    }

    @Test
    fun `provider opens a pile world and restores chunks after unload`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))

        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)

        val provider = PileLevelProvider(level, temp.toString())
        assertEquals(1, provider.pileWorld.columns.size)
        assertNull(provider.getLoadedChunk(0, 0))

        val first = provider.getChunk(0, 0, false)
        assertNotNull(first)
        assertEquals("minecraft:stone", first!!.getBlockState(0, -64, 0).identifier)
        assertEquals(1, provider.getLoadedChunks().size)

        assertEquals(true, provider.unloadChunk(0, 0, true))
        assertNull(provider.getLoadedChunk(0, 0))

        val second = provider.getChunk(0, 0, false)
        assertNotNull(second)
        assertEquals("minecraft:stone", second!!.getBlockState(0, -64, 0).identifier)
        provider.close()
    }

    @Test
    fun `dimension files follow PNX dimension ids`(@TempDir temp: Path) {
        assertEquals(
            temp.resolve("overworld.pile"),
            PileLevelProvider.dimensionFile(temp, DimensionEnum.OVERWORLD.dimensionData)
        )
        assertEquals(
            temp.resolve("nether.pile"),
            PileLevelProvider.dimensionFile(temp, DimensionEnum.NETHER.dimensionData)
        )
        assertEquals(
            temp.resolve("end.pile"),
            PileLevelProvider.dimensionFile(temp, DimensionEnum.THE_END.dimensionData)
        )
    }

    @Test
    fun `generate creates a valid empty pile seed file`(@TempDir temp: Path) {
        val config = LevelConfig.GeneratorConfig().dimensionData(DimensionEnum.OVERWORLD.dimensionData)
        PileLevelProvider.generate(temp.toString(), "generated", config)

        val file = temp.resolve("overworld.pile")
        assertEquals(true, Files.isRegularFile(file))
        val world = PileReader.readWorld(Files.readAllBytes(file))
        // The canonical writer emits only states the content references; an empty world has none.
        assertEquals(0, world.blockStates.size)
        assertEquals(0, world.columns.size)
    }

    @Test
    fun `builder creates and saves a generated pile world`(@TempDir temp: Path) {
        val builder = PileBuilder(DimensionEnum.OVERWORLD.dimensionData, "builder-test")
        builder.setBlock(0, -64, 0, BlockStone.PROPERTIES.defaultState)
        builder.setBiome(0, -64, 0, "minecraft:plains")

        val world = builder.build()
        assertEquals("minecraft:stone", world.blockStates[world.column(0, 0)!!.sections[0]!!.layers[0][0]].name)

        val file = builder.save(temp.resolve("overworld.pile"), Compression.NONE)
        assertEquals(true, Files.isRegularFile(file))
        assertEquals(1, PileReader.readWorld(Files.readAllBytes(file)).columns.size)
    }

    @Test
    fun `missing chunks are generated and persisted as pile columns`(@TempDir temp: Path) {
        PileLevelProvider.generate(
            temp.toString(),
            "generated",
            LevelConfig.GeneratorConfig().dimensionData(DimensionEnum.OVERWORLD.dimensionData),
        )
        val generator = mock(Generator::class.java)
        `when`(generator.syncGenerate(any())).thenAnswer { invocation ->
            val chunk = invocation.arguments[0] as IChunk
            chunk.setBlockState(0, chunk.dimensionData.minSectionY * 16, 0, BlockStone.PROPERTIES.defaultState)
            chunk
        }
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        `when`(level.generator).thenReturn(generator)

        val provider = PileLevelProvider(level, temp.toString())
        val generated = provider.getChunk(2, -3, true)!!
        assertEquals(
            "minecraft:stone",
            generated.getBlockState(0, generated.dimensionData.minSectionY * 16, 0).identifier
        )
        assertEquals(true, provider.unloadChunk(2, -3))
        provider.close()

        val reopenedLevel = mock(Level::class.java)
        `when`(reopenedLevel.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val reopened = PileLevelProvider(reopenedLevel, temp.toString())
        assertEquals(
            "minecraft:stone",
            reopened.getChunk(2, -3, false)!!
                .getBlockState(0, reopenedLevel.dimensionData.minSectionY * 16, 0).identifier
        )
        reopened.close()
    }

    @Test
    fun `pnx level metadata survives reopen`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)

        val provider = PileLevelProvider(level, temp.toString())
        provider.seed = 987654321L
        provider.noSleepNight = 7
        provider.getGamerules().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        provider.setWorldDynamicProperties(CompoundTag().putString("example", "persisted"))
        provider.saveLevelData()
        provider.close()

        val savedSettings = WorldSettings.parse(
            PileReader.readWorld(Files.readAllBytes(temp.resolve("overworld.pile"))).settings,
        )
        assertNotNull(savedSettings.extra["pnx_gamerules"])
        assertEquals(
            true,
            (savedSettings.extra["pnx_gamerules"] as NbtCompound).keys.contains(GameRule.DO_DAYLIGHT_CYCLE.getName())
        )
        val reopened = PileLevelProvider(level, temp.toString())
        assertEquals(987654321L, reopened.seed)
        assertEquals(7, reopened.noSleepNight)
        assertEquals(false, reopened.getGamerules().getBoolean(GameRule.DO_DAYLIGHT_CYCLE))
        assertEquals("persisted", reopened.getWorldDynamicProperties().getString("example"))
        reopened.close()
    }

    @Test
    fun `saving a changed loaded chunk persists the block state`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)

        val provider = PileLevelProvider(level, temp.toString())
        val chunk = provider.getChunk(0, 0, false)!!
        chunk.setBlockState(0, -64, 0, BlockDirt.PROPERTIES.defaultState)
        provider.updateLevelName("saved-settings")
        provider.time = 1234
        provider.spawn = Vector3(4.0, 70.0, -2.0)
        provider.saveChunks()
        provider.close()

        val reopenedLevel = mock(Level::class.java)
        `when`(reopenedLevel.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val reopened = PileLevelProvider(reopenedLevel, temp.toString())
        assertEquals("minecraft:dirt", reopened.getChunk(0, 0, false)!!.getBlockState(0, -64, 0).identifier)
        assertEquals("saved-settings", reopened.name)
        assertEquals(1234, reopened.time)
        assertEquals(4.0, reopened.spawn.x)
        reopened.close()
    }

    @Test
    fun `indexed worlds remain indexed after provider save`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        val world = PileReader.readWorld(Files.readAllBytes(source))
        Files.write(temp.resolve("overworld.pile"), PileWriter.writeIndexed(world, Compression.NONE))
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val provider = PileLevelProvider(level, temp.toString())
        provider.getChunk(0, 0, false)
        provider.saveChunks()
        provider.close()
        assertEquals(1, Files.readAllBytes(temp.resolve("overworld.pile"))[7].toInt())
    }

    @Test
    fun `snapshots are validated and include the current dimension`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val provider = PileLevelProvider(level, temp.toString())

        val snapshot = provider.snapshot("before_changes")
        assertEquals(true, Files.isRegularFile(snapshot.resolve("overworld.pile")))
        assertEquals(listOf("before_changes"), provider.snapshots())
        provider.deleteSnapshot("before_changes")
        assertEquals(emptyList<String>(), provider.snapshots())
        provider.close()
    }

    @Test
    fun `read only options never rewrite the provider file`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val file = temp.resolve("overworld.pile")
        val before = Files.readAllBytes(file)
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val provider = PileLevelProvider(level, temp.toString(), PileProviderOptions(readOnly = true))
        provider.getChunk(0, 0, false)!!.setBlockState(0, -64, 0, BlockDirt.PROPERTIES.defaultState)
        provider.saveChunks()
        provider.close()
        assertEquals(before.toList(), Files.readAllBytes(file).toList())
    }

    @Test
    fun `pile structures convert to native PNX structures`() {
        val path = Path.of("..", "conformance", "testdata", "upstream", "vectors", "structure_full.pile")
        val structure = PileReader.readStructure(Files.readAllBytes(path))
        val native = PnxStructures.fromPileNative(structure)
        assertEquals(structure.sizeX, native.sizeX)
        assertEquals(structure.sizeY, native.sizeY)
        assertEquals(structure.sizeZ, native.sizeZ)
    }

    @Test
    fun `template instances are copy-on-write and evaporate on close`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_minimal.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val template = PileTemplate.open(temp, DimensionEnum.OVERWORLD.dimensionData)

        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val a = PileLevelProvider(level, template.instancePath())
        val b = PileLevelProvider(level, template.instancePath())
        a.getChunk(0, 0, false)!!.setBlockState(0, -64, 0, BlockDirt.PROPERTIES.defaultState)
        a.saveChunk(0, 0)
        assertEquals("minecraft:dirt", a.getChunk(0, 0, false)!!.getBlockState(0, -64, 0).identifier)
        assertEquals("minecraft:stone", b.getChunk(0, 0, false)!!.getBlockState(0, -64, 0).identifier)

        val kept = temp.resolve("kept")
        a.saveAs(kept)
        a.close()
        b.close()
        val saved = PileReader.readWorld(Files.readAllBytes(kept.resolve("overworld.pile")))
        val section = saved.columns.single().sections.first { it != null }!!
        assertEquals("minecraft:dirt", saved.blockStates[section.layers[0].get(0, 0, 0)].name)
        assertEquals(
            "minecraft:stone",
            PileReader.readWorld(Files.readAllBytes(temp.resolve("overworld.pile"))).blockStates.single().name,
        )
    }

    @Test
    fun `column and entity filters keep filtered content out of loads and saves`(@TempDir temp: Path) {
        val source = Path.of("..", "conformance", "testdata", "upstream", "vectors", "world_collections.pile")
        Files.copy(source, temp.resolve("overworld.pile"))
        val level = mock(Level::class.java)
        `when`(level.dimensionData).thenReturn(DimensionEnum.OVERWORLD.dimensionData)
        val filtered = PileLevelProvider(
            level, temp.toString(),
            PileProviderOptions(filterEntity = { false }, filterBlockEntity = { it.getString("id") != "Chest" }),
        )
        val chunk = filtered.getChunk(0, 0, false)!!
        assertEquals(0, (chunk as org.powernukkitx.level.format.Chunk).let { c ->
            val f = org.powernukkitx.level.format.Chunk::class.java.getDeclaredField("entityNBT")
            f.isAccessible = true
            (f.get(c) as List<*>).size
        })
        filtered.close()

        val none = PileLevelProvider(
            level,
            temp.toString(),
            PileProviderOptions(filterColumn = { _, _ -> false }, readOnly = true)
        )
        assertEquals(null, none.getChunk(0, 0, false))
        none.close()
    }
}
