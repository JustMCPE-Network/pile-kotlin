package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.corrupt
import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtType

/** The fixed tags of format.md §6.1 (settings) and §4.2 (stats): presence is optional, a wrong tag is invalid. */
internal object Schema {
    private val settings = mapOf(
        "name" to NbtType.STRING,
        "spawnX" to NbtType.INT, "spawnY" to NbtType.INT, "spawnZ" to NbtType.INT,
        "time" to NbtType.LONG, "timeCycle" to NbtType.BYTE,
        "rainTime" to NbtType.LONG, "raining" to NbtType.BYTE,
        "thunderTime" to NbtType.LONG, "thundering" to NbtType.BYTE, "weatherCycle" to NbtType.BYTE,
        "requiredSleepTicks" to NbtType.LONG, "currentTick" to NbtType.LONG,
        "defaultGameMode" to NbtType.INT, "difficulty" to NbtType.INT, "tickRange" to NbtType.INT,
    )

    private val stats = mapOf(
        "chunks" to NbtType.LONG, "filledSections" to NbtType.LONG, "uniqueBlobs" to NbtType.LONG,
        "blockStates" to NbtType.LONG, "biomes" to NbtType.LONG,
    )

    fun checkSettings(blob: ByteArray) = check(blob, settings, "settings")
    fun checkStats(blob: ByteArray) = check(blob, stats, "stats")

    private fun check(blob: ByteArray, schema: Map<String, NbtType>, what: String) {
        if (blob.isEmpty()) return
        val c = Nbt.decode(blob)
        for ((k, want) in schema) {
            val v = c[k] ?: continue
            if (v.type != want) corrupt("$what blob: \"$k\" is ${v.type}, want $want")
        }
    }
}
