package net.justmcpe.pile.format

import net.justmcpe.pile.format.wire.ByteReader
import net.justmcpe.pile.format.wire.ByteWriter
import net.justmcpe.pile.format.wire.Frame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.random.Random

/** Mutated upstream vectors must fail with a typed exception: never an index, cast or allocation error. */
class HostileInputTest {
    private val vectors: Path = Path.of("..", "conformance", "testdata", "upstream", "vectors")

    private fun positives(): List<Path> = Files.list(vectors).use { s ->
        s.filter { it.name.startsWith("world_") && it.name.endsWith(".pile") }.sorted().toList()
    }

    private fun decode(bytes: ByteArray) {
        PileReader.readMeta(bytes)
        PileReader.readWorld(bytes)
    }

    /** Re-seals a mutated body so the mutation reaches the parser instead of failing the checksum. */
    private fun reseal(file: ByteArray, body: ByteArray): ByteArray {
        val header = PileHeader(FileKind.WORLD, ByteReader(file, 8).i32(), ByteReader(file, 12).i32())
        return Frame.assemble(header.kind, header.flags, header.blockVersion, body)
    }

    @TestFactory
    fun `every truncation is refused cleanly`() = positives().map { path ->
        DynamicTest.dynamicTest(path.name) {
            val file = Files.readAllBytes(path)
            for (n in 0 until file.size) {
                assertThrows(PileException::class.java, { decode(file.copyOf(n)) }, "truncated at $n")
            }
        }
    }

    @TestFactory
    fun `every single-byte body mutation is refused cleanly or decodes`() = positives().map { path ->
        DynamicTest.dynamicTest(path.name) {
            val file = Files.readAllBytes(path)
            val body = file.copyOfRange(Limits.HEADER_SIZE, file.size - Limits.FOOTER_SIZE)
            val rnd = Random(path.name.hashCode())
            val positions = if (body.size <= 600) body.indices.toList() else List(600) { rnd.nextInt(body.size) }
            for (i in positions) {
                for (delta in intArrayOf(1, 0x80, 0xFF)) {
                    val mutated = body.copyOf()
                    mutated[i] = (mutated[i].toInt() xor delta).toByte()
                    val resealed = reseal(file, mutated)
                    try {
                        decode(resealed)
                    } catch (e: PileException) {
                        assertTrue(e !is DecodeBudgetException, "budget is not a validity verdict at byte $i")
                    }
                }
            }
        }
    }

    @TestFactory
    fun `random garbage bodies are refused cleanly`() = positives().take(3).map { path ->
        DynamicTest.dynamicTest(path.name) {
            val file = Files.readAllBytes(path)
            val rnd = Random(11)
            repeat(300) {
                val body = ByteArray(rnd.nextInt(0, 200)).also { rnd.nextBytes(it) }
                assertThrows(PileException::class.java) { decode(reseal(file, body)) }
            }
        }
    }

    @Test
    fun `indexed vectors survive truncation and bit flips without a crash`() {
        for (name in listOf("indexed_torn.pile", "indexed_full.pile")) {
            val file = Files.readAllBytes(vectors.resolve(name))
            for (n in intArrayOf(0, 1, 15, 16, 60, file.size / 2, file.size - 1)) {
                try {
                    PileReader.readWorld(file.copyOf(n))
                } catch (_: PileException) {
                }
            }
            val rnd = Random(name.hashCode())
            repeat(300) {
                val mutated = file.copyOf()
                val i = rnd.nextInt(mutated.size)
                mutated[i] = (mutated[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                try {
                    PileReader.readWorld(mutated)
                } catch (_: PileException) {
                }
            }
        }
    }

    @Test
    fun `a tight budget refuses a conforming file with a distinct exception`() {
        val file = Files.readAllBytes(vectors.resolve("world_dedup_morton.pile"))
        PileReader.readWorld(file, DecodeOptions(maxDecodedBytes = 64 shl 10))
        assertThrows(DecodeBudgetException::class.java) {
            PileReader.readWorld(
                file,
                DecodeOptions(maxDecodedBytes = 3 * Limits.COLUMN_COST)
            )
        }
    }

    @Test
    fun `a small file declaring many columns stops at the budget before materialising them`() {
        val file = Files.readAllBytes(vectors.resolve("world_empty_chunk.pile"))
        val body = file.copyOfRange(Limits.HEADER_SIZE, file.size - Limits.FOOTER_SIZE)
        val w = ByteWriter()
        w.raw(body, 0, body.size - 1 - 15)
        val many = 2000
        w.uvarint(many)
        val one = body.copyOfRange(body.size - 15, body.size)
        w.raw(one)
        repeat(many - 1) {
            w.u8(0x02)
            w.raw(one, 1, one.size - 1)
        }
        val inflated = reseal(file, w.toByteArray())
        PileReader.readWorld(inflated, DecodeOptions(maxDecodedBytes = 4 shl 20))
        val t0 = System.nanoTime()
        assertThrows(DecodeBudgetException::class.java) {
            PileReader.readWorld(
                inflated,
                DecodeOptions(maxDecodedBytes = 1 shl 20)
            )
        }
        assertTrue((System.nanoTime() - t0) < 5_000_000_000L)
    }
}
