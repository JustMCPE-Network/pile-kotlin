package net.justmcpe.pile.conformance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** The vendored upstream fixtures under conformance/testdata/upstream, pinned by commit.txt. */
object Fixtures {
    val root: Path = Path.of("testdata", "upstream")
    val vectors: Path = root.resolve("vectors")
    val golden: Path = root.resolve("golden")

    fun positiveVectors(): List<Path> =
        list(vectors).filter { !it.name.startsWith("neg_") && !it.name.startsWith("indexed_") }

    fun negativeVectors(): List<Path> = list(vectors).filter { it.name.startsWith("neg_") }
    fun solidGoldens(): List<Path> = list(golden).filter { !it.name.startsWith("golden_indexed") }
    fun indexedFiles(): List<Path> =
        list(vectors).filter { it.name.startsWith("indexed_") } + list(golden).filter { it.name.startsWith("golden_indexed") }

    /** name -> format.ContentHash, as computed by the upstream CLI. */
    val contentHashes: Map<String, Long> by lazy {
        Files.readAllLines(root.resolve("content_hashes.txt"))
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (name, hash) = line.trim().split(Regex("\\s+"))
                name to java.lang.Long.parseUnsignedLong(hash, 16)
            }
    }

    fun contentHash(file: Path): Long = contentHashes.getValue(file.nameWithoutExtension)

    private fun list(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.filter { it.name.endsWith(".pile") }.sorted().toList() }
}
