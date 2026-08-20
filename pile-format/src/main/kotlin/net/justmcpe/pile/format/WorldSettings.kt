package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.*
import net.justmcpe.pile.format.wire.Schema

/**
 * The settings compound of format.md §6.1 as typed fields. Every field is optional on the wire; [extra]
 * carries keys this class does not name so a rewrite preserves them.
 */
public data class WorldSettings(
    val name: String? = null,
    val spawnX: Int? = null,
    val spawnY: Int? = null,
    val spawnZ: Int? = null,
    val time: Long? = null,
    val timeCycle: Boolean? = null,
    val rainTime: Long? = null,
    val raining: Boolean? = null,
    val thunderTime: Long? = null,
    val thundering: Boolean? = null,
    val weatherCycle: Boolean? = null,
    val requiredSleepTicks: Long? = null,
    val currentTick: Long? = null,
    val defaultGameMode: Int? = null,
    val difficulty: Int? = null,
    val tickRange: Int? = null,
    val extra: Map<String, NbtTag> = emptyMap(),
) {
    /** Canonical NBT bytes; empty when no field is set and [extra] is empty. */
    public fun encode(): ByteArray {
        val b = NbtCompound.Builder()
        for ((k, v) in extra) if (k !in KNOWN) b.put(k, v)
        name?.let { b.put("name", it) }
        spawnX?.let { b.put("spawnX", it) }
        spawnY?.let { b.put("spawnY", it) }
        spawnZ?.let { b.put("spawnZ", it) }
        time?.let { b.put("time", it) }
        timeCycle?.let { b.put("timeCycle", it) }
        rainTime?.let { b.put("rainTime", it) }
        raining?.let { b.put("raining", it) }
        thunderTime?.let { b.put("thunderTime", it) }
        thundering?.let { b.put("thundering", it) }
        weatherCycle?.let { b.put("weatherCycle", it) }
        requiredSleepTicks?.let { b.put("requiredSleepTicks", it) }
        currentTick?.let { b.put("currentTick", it) }
        defaultGameMode?.let { b.put("defaultGameMode", it) }
        difficulty?.let { b.put("difficulty", it) }
        tickRange?.let { b.put("tickRange", it) }
        val c = b.build()
        return if (c.isEmpty()) ByteArray(0) else Nbt.encode(c)
    }

    public companion object {
        private val KNOWN = setOf(
            "name",
            "spawnX",
            "spawnY",
            "spawnZ",
            "time",
            "timeCycle",
            "rainTime",
            "raining",
            "thunderTime",
            "thundering",
            "weatherCycle",
            "requiredSleepTicks",
            "currentTick",
            "defaultGameMode",
            "difficulty",
            "tickRange",
        )

        /** Parses a settings blob; an empty blob yields defaults. A listed key with the wrong tag is rejected as §6.1 requires. */
        public fun parse(blob: ByteArray): WorldSettings {
            if (blob.isEmpty()) return WorldSettings()
            Schema.checkSettings(blob)
            val c = Nbt.decode(blob)
            fun bool(k: String) = (c[k] as? NbtByte)?.value?.let { it.toInt() != 0 }
            return WorldSettings(
                name = (c["name"] as? NbtString)?.value,
                spawnX = (c["spawnX"] as? NbtInt)?.value,
                spawnY = (c["spawnY"] as? NbtInt)?.value,
                spawnZ = (c["spawnZ"] as? NbtInt)?.value,
                time = (c["time"] as? NbtLong)?.value,
                timeCycle = bool("timeCycle"),
                rainTime = (c["rainTime"] as? NbtLong)?.value,
                raining = bool("raining"),
                thunderTime = (c["thunderTime"] as? NbtLong)?.value,
                thundering = bool("thundering"),
                weatherCycle = bool("weatherCycle"),
                requiredSleepTicks = (c["requiredSleepTicks"] as? NbtLong)?.value,
                currentTick = (c["currentTick"] as? NbtLong)?.value,
                defaultGameMode = (c["defaultGameMode"] as? NbtInt)?.value,
                difficulty = (c["difficulty"] as? NbtInt)?.value,
                tickRange = (c["tickRange"] as? NbtInt)?.value,
                extra = c.filterKeys { it !in KNOWN },
            )
        }
    }
}
