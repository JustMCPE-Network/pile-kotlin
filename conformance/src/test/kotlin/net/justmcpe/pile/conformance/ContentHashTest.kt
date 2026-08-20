package net.justmcpe.pile.conformance

import net.justmcpe.pile.format.FileKind
import net.justmcpe.pile.format.PileReader
import net.justmcpe.pile.format.PileWriter
import net.justmcpe.pile.format.XxHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Content identity against the reference implementation: every fixture must decode and re-encode to
 * the content hash the upstream CLI computed for it (testdata/upstream/content_hashes.txt). This is
 * the cross-implementation contract of format.md §9: canonical palette order, blob dedup, collection
 * orders and default-biome election all feed the hash, so a divergence in any of them fails here.
 */
class ContentHashTest {
    @TestFactory
    fun `every fixture matches the upstream content hash`() =
        (Fixtures.positiveVectors() + Fixtures.solidGoldens() + Fixtures.indexedFiles()).mapNotNull { path ->
            val want = Fixtures.contentHashes[path.nameWithoutExtension] ?: return@mapNotNull null
            DynamicTest.dynamicTest(path.name) {
                val bytes = Files.readAllBytes(path)
                val hash = when {
                    path.name.startsWith("structure") || path.name.startsWith("golden_structure") ->
                        PileWriter.contentHash(PileReader.readStructure(bytes))

                    else -> PileWriter.contentHash(PileReader.readWorld(bytes))
                }
                assertEquals(XxHash.hex(want), XxHash.hex(hash))
            }
        }

    @TestFactory
    fun `indexed vectors hash to their solid content`() = Fixtures.indexedFiles()
        .filter { Fixtures.contentHashes.containsKey(it.nameWithoutExtension) }
        .map { path ->
            DynamicTest.dynamicTest(path.name) {
                val world = PileReader.readWorld(Files.readAllBytes(path))
                assertEquals(
                    XxHash.hex(Fixtures.contentHashes.getValue(path.nameWithoutExtension)),
                    XxHash.hex(PileWriter.contentHash(world)),
                )
            }
        }

    @TestFactory
    fun `re-encoded fixtures still decode to the same content`() =
        (Fixtures.positiveVectors() + Fixtures.solidGoldens()).map { path ->
            DynamicTest.dynamicTest(path.name) {
                val bytes = Files.readAllBytes(path)
                if (PileReader.readMeta(bytes).header.kind == FileKind.WORLD) {
                    val world = PileReader.readWorld(bytes)
                    val back = PileReader.readWorld(PileWriter.writeWorld(world))
                    assertEquals(PileWriter.contentHash(world), PileWriter.contentHash(back))
                } else {
                    val structure = PileReader.readStructure(bytes)
                    val back = PileReader.readStructure(PileWriter.writeStructure(structure))
                    assertEquals(PileWriter.contentHash(structure), PileWriter.contentHash(back))
                }
            }
        }
}
