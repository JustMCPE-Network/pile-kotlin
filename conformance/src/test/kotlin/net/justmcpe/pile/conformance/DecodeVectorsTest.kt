package net.justmcpe.pile.conformance

import net.justmcpe.pile.format.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class DecodeVectorsTest {
    @TestFactory
    fun `positive vectors and goldens decode`() = (Fixtures.positiveVectors() + Fixtures.solidGoldens()).map { path ->
        DynamicTest.dynamicTest(path.name) {
            val bytes = Files.readAllBytes(path)
            if (PileReader.readMeta(bytes).header.kind == FileKind.WORLD) {
                PileReader.readWorld(bytes)
            } else {
                PileReader.readStructure(bytes)
            }
        }
    }

    /** Every negative vector, with the rule vectors.md says it breaks, as the reader reports it. */
    private val refusals: Map<String, Pair<Class<out PileException>, String>> = mapOf(
        "neg_header_magic" to (CorruptFileException::class.java to "bad header magic"),
        "neg_header_version" to (UnsupportedVersionException::class.java to "version 3"),
        "neg_header_kind" to (CorruptFileException::class.java to "file kind 2 is not defined"),
        "neg_header_mode" to (UnsupportedModeException::class.java to "mode 2"),
        "neg_header_structure_indexed" to (UnsupportedModeException::class.java to "mode 1"),
        "neg_header_block_version_zero" to (CorruptFileException::class.java to "blockVersion is zero"),
        "neg_flag_reserved_bit2" to (UnknownFlagsException::class.java to "0x0000001c"),
        "neg_flag_reserved_bit8" to (UnknownFlagsException::class.java to "0x00000118"),
        "neg_flag_dimension_reserved" to (UnknownFlagsException::class.java to "0x000000f8"),
        "neg_flag_default_biome_ref_without_flag" to (CorruptFileException::class.java to "default biome reference set without its flag"),
        "neg_footer_magic" to (CorruptFileException::class.java to "bad footer magic"),
        "neg_footer_generation_nonzero" to (CorruptFileException::class.java to "generation must be zero"),
        "neg_checkpoint_hash" to (ChecksumMismatchException::class.java to "checksum mismatch"),
        "neg_uvarint_overlong" to (CorruptFileException::class.java to "non-minimal uvarint"),
        "neg_bitset_padding_bits" to (CorruptFileException::class.java to "padding bits in bitset"),
        "neg_string_not_utf8" to (CorruptFileException::class.java to "not valid UTF-8"),
        "neg_palette_duplicate_entry" to (CorruptFileException::class.java to "duplicate block palette entry"),
        "neg_palette_property_order" to (CorruptFileException::class.java to "state properties must ascend"),
        "neg_override_zero_delta" to (CorruptFileException::class.java to "overrides must be strictly ascending"),
        "neg_override_index_chain_wraps" to (CorruptFileException::class.java to "override index chain wraps"),
        "neg_override_zero_version" to (CorruptFileException::class.java to "override must not be zero"),
        "neg_override_same_version" to (CorruptFileException::class.java to "equals the palette's own version"),
        "neg_biome_bare_name" to (CorruptFileException::class.java to "is not namespaced"),
        "neg_biome_duplicate_name" to (CorruptFileException::class.java to "duplicate biome palette entry"),
        "neg_blob_uniform_width_nonzero" to (CorruptFileException::class.java to "single-entry palette must use the uniform width"),
        "neg_blob_width_not_minimal" to (CorruptFileException::class.java to "non-minimal index width"),
        "neg_blob_refs_not_ascending" to (CorruptFileException::class.java to "references are not strictly ascending"),
        "neg_blob_refs_duplicate" to (CorruptFileException::class.java to "references are not strictly ascending"),
        "neg_blob_unused_palette_entry" to (CorruptFileException::class.java to "is never used by the indices"),
        "neg_blob_index_out_of_range" to (CorruptFileException::class.java to "out of palette range"),
        "neg_blob_table_duplicate" to (CorruptFileException::class.java to "repeats blob"),
        "neg_blob_table_unreferenced" to (CorruptFileException::class.java to "is never referenced"),
        "neg_blob_id_first_use_order" to (CorruptFileException::class.java to "first-use order"),
        "neg_record_keys_not_ascending" to (CorruptFileException::class.java to "out of order or duplicated"),
        "neg_record_duplicate_position" to (CorruptFileException::class.java to "out of order or duplicated"),
        "neg_record_trailing_bytes" to (CorruptFileException::class.java to "trailing bytes after last chunk"),
        "neg_section_present_with_no_layers" to (CorruptFileException::class.java to "present but declares no layers"),
        "neg_layer_count_over_max" to (CorruptFileException::class.java to "layer count 256 exceeds limit 255"),
        "neg_section_all_air" to (CorruptFileException::class.java to "ends in an all-air layer"),
        "neg_section_trailing_air_layer" to (CorruptFileException::class.java to "ends in an all-air layer"),
        "neg_light_flags_zero" to (CorruptFileException::class.java to "carries no arrays"),
        "neg_light_flags_reserved_bits" to (CorruptFileException::class.java to "set reserved bits"),
        "neg_block_entity_outside_span" to (CorruptFileException::class.java to "outside the chunk's span"),
        "neg_block_entity_duplicate_position" to (CorruptFileException::class.java to "block entities are out of order or repeat"),
        "neg_block_entity_out_of_order" to (CorruptFileException::class.java to "block entities are out of order or repeat"),
        "neg_scheduled_update_out_of_order" to (CorruptFileException::class.java to "scheduled updates are out of order or repeat"),
        "neg_nbt_keys_not_ascending" to (CorruptFileException::class.java to "compound keys must ascend"),
        "neg_nbt_duplicate_keys" to (CorruptFileException::class.java to "duplicate compound key"),
        "neg_nbt_named_root" to (CorruptFileException::class.java to "root compound must be unnamed"),
        "neg_settings_wrong_tag" to (CorruptFileException::class.java to "\"time\" is INT, want LONG"),
        "neg_stats_wrong_tag" to (CorruptFileException::class.java to "\"chunks\" is INT, want LONG"),
        "neg_structure_flag_set" to (CorruptFileException::class.java to "not valid for a structure"),
        "neg_structure_biome_palette_nonempty" to (CorruptFileException::class.java to "structure biome palette must be empty"),
        "neg_structure_block_entity_outside_box" to (CorruptFileException::class.java to "outside structure"),
        "neg_indexed_prologue_stats_flag" to (CorruptFileException::class.java to "prologue disagrees"),
        "neg_indexed_prologue_block_version_zero" to (CorruptFileException::class.java to "prologue disagrees"),
        "neg_indexed_empty_palette_segment" to (CorruptFileException::class.java to "empty block palette segment"),
    )

    @TestFactory
    fun `negative vectors are refused for the stated rule`() = Fixtures.negativeVectors().map { path ->
        DynamicTest.dynamicTest(path.name) {
            val (type, fragment) = refusals[path.nameWithoutExtension]
                ?: error("no expectation recorded for ${path.name}")
            val bytes = Files.readAllBytes(path)
            val e = assertThrows(PileException::class.java) {
                when {
                    bytes.size > 7 && bytes[7].toInt() == 1 -> PileReader.readWorld(bytes)
                    bytes.size > 6 && bytes[6].toInt() == 1 -> PileReader.readStructure(bytes)
                    else -> {
                        PileReader.readMeta(bytes)
                        PileReader.readWorld(bytes)
                    }
                }
            }
            assertEquals(type, e.javaClass, "exception type for ${path.name}: ${e.message}")
            assertTrue(e.message!!.contains(fragment), "${path.name}: expected \"$fragment\" in \"${e.message}\"")
        }
    }

    @Test
    fun `every negative vector has a recorded expectation and vice versa`() {
        val names = Fixtures.negativeVectors().map { it.nameWithoutExtension }.toSet()
        assertEquals(names, refusals.keys)
    }

    @TestFactory
    fun `indexed files decode as worlds but carry no meta block`() = Fixtures.indexedFiles().map { path ->
        DynamicTest.dynamicTest(path.name) {
            val bytes = Files.readAllBytes(path)
            assertThrows(UnsupportedModeException::class.java) { PileReader.readMeta(bytes) }
            PileReader.readWorld(bytes)
        }
    }

    @Test
    fun `minimal world decodes to one stone section`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_minimal.pile")))
        assertEquals(18040335, w.blockVersion)
        assertEquals(listOf("minecraft:stone"), w.blockStates.map { it.name })
        assertEquals(listOf("minecraft:ocean"), w.biomes)
        assertEquals(1, w.columns.size)
        val c = w.columns[0]
        assertEquals(0, c.x)
        assertEquals(0, c.z)
        assertEquals(-4, c.minSection)
        assertEquals(1, c.sectionCount)
        val s = c.sections[0]!!
        assertEquals(1, s.layers.size)
        assertTrue(s.layers[0].isUniform)
        assertEquals(0, s.layers[0][0])
        assertTrue(c.biomes[0].isUniform)
        assertEquals(0, c.biomes[0][0])
    }

    @Test
    fun `empty chunk carries its full span with nothing present`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_empty_chunk.pile")))
        assertTrue(w.blockStates.isEmpty())
        val c = w.columns.single()
        assertEquals(-4, c.minSection)
        assertEquals(24, c.sectionCount)
        assertTrue(c.sections.all { it == null })
        assertTrue(c.biomes.all { it.isUniform && it[0] == 0 })
    }

    @Test
    fun `waterlogged section keeps its uniform air layer 0`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_waterlogged.pile")))
        val section = w.columns.single().sections.filterNotNull().single()
        assertEquals(2, section.layers.size)
        assertTrue(section.layers[0].isUniform)
        assertTrue(w.blockStates[section.layers[0][0]].isAir)
        assertEquals("minecraft:water", w.blockStates[section.layers[1][0]].name)
    }

    @Test
    fun `layers vector stores an internal all-air layer`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_layers.pile")))
        val section = w.columns.single().sections.filterNotNull().single()
        assertEquals(3, section.layers.size)
        assertEquals("minecraft:stone", w.blockStates[section.layers[0].get(1, 0, 1)].name)
        assertTrue(section.layers[1].isUniform && w.blockStates[section.layers[1][0]].isAir)
        assertEquals("minecraft:water", w.blockStates[section.layers[2].get(2, 1, 2)].name)
    }

    @Test
    fun `palette width vectors decode both index widths`() {
        val w256 = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_palette_256.pile")))
        val w257 = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_palette_257.pile")))
        val s256 = w256.columns.single().sections.filterNotNull().single().layers[0]
        val s257 = w257.columns.single().sections.filterNotNull().single().layers[0]
        assertEquals(256, s256.palette.size)
        assertEquals(257, s257.palette.size)
        assertEquals(256, (0 until 4096).map { s256[it] }.toSet().size)
        assertEquals(257, (0 until 4096).map { s257[it] }.toSet().size)
    }

    @Test
    fun `dedup vector shares one storage between identical columns`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_dedup_morton.pile")))
        assertEquals(4, w.columns.size)
        val storages = w.columns.map { c -> c.sections.filterNotNull().single().layers[0] }
        assertTrue(storages.all { it === storages[0] })
        val keys = w.columns.map { it.x to it.z }
        assertEquals(keys.sortedWith(compareBy({ mortonKey(it.first, it.second) })), keys)
    }

    @Test
    fun `collections vector carries every per-column collection`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_collections.pile")))
        val c = w.columns.single()
        assertTrue(c.blockEntities.isNotEmpty())
        assertTrue(c.entities.isNotEmpty())
        assertTrue(c.scheduledUpdates.isNotEmpty())
        assertTrue(c.userData.isNotEmpty())
        assertTrue(w.userData.isNotEmpty())
        assertTrue(w.settings.isNotEmpty())
        assertTrue(c.blockEntities.all {
            it.decoded().getInt("x") == it.x && it.decoded().getInt("y") == it.y && it.decoded().getInt("z") == it.z
        })
        assertTrue(c.entities.all { it.decoded().getLong("UniqueID") == it.uniqueId })
    }

    @Test
    fun `light vector carries light on sections without blocks`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_light.pile")))
        val c = w.columns.single()
        val light = c.light!!
        assertTrue((0 until c.sectionCount).any { c.sections[it] == null && light[it] != null })
    }

    @Test
    fun `preserved vector keeps two override versions`() {
        val w = PileReader.readWorld(Files.readAllBytes(Fixtures.vectors.resolve("world_preserved.pile")))
        val versions = w.blockStates.map { it.version }.filter { it != w.blockVersion }.toSet()
        assertEquals(2, versions.size)
        assertTrue(w.blockStates.any { it.properties.size == 2 })
    }

    @Test
    fun `default biome vector picks the lowest reference on a tie`() {
        val bytes = Files.readAllBytes(Fixtures.vectors.resolve("world_default_biome.pile"))
        val meta = PileReader.readMeta(bytes)
        assertTrue(meta.header.hasDefaultBiome)
        assertEquals(0, meta.header.defaultBiomeRef)
    }

    private fun mortonKey(x: Int, z: Int): java.math.BigInteger {
        fun spread(v: Int): java.math.BigInteger {
            var out = java.math.BigInteger.ZERO
            for (i in 0 until 32) if ((v ushr i) and 1 == 1) out = out.setBit(2 * i)
            return out
        }
        return spread(x xor Int.MIN_VALUE).or(spread(z xor Int.MIN_VALUE).shiftLeft(1))
    }
}
