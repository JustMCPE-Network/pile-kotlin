package net.justmcpe.pile.pnx.convert

import net.justmcpe.pile.format.nbt.NbtCompound
import net.justmcpe.pile.format.nbt.NbtString
import org.powernukkitx.level.GameRule
import org.powernukkitx.level.GameRules
import org.powernukkitx.level.Level
import org.powernukkitx.level.Level.QueuedUpdate
import org.powernukkitx.level.village.*
import org.powernukkitx.math.BlockFace
import org.powernukkitx.nbt.tag.CompoundTag
import org.powernukkitx.nbt.tag.ListTag
import java.util.*

/**
 * Level state PNX's own provider keeps outside chunk data: pending neighbour updates, villages and
 * game rules. Serialised into the file's metadata so a pile world matches LevelDB behaviour.
 */
internal class ProviderState(private val level: Level) {
    fun currentNormalUpdates(): ListTag<CompoundTag> {
        val result = ListTag<CompoundTag>()
        val queue = runCatching { level.normalUpdateQueue }.getOrNull() ?: return result
        for (queued in queue) {
            val block = queued.block
            val tag = CompoundTag()
                .putInt("x", block.floorX)
                .putInt("y", block.floorY)
                .putInt("z", block.floorZ)
                .putInt("layer", block.layer)
            queued.neighbor?.let { tag.putInt("neighbor", it.index) }
            result.add(tag)
        }
        return result
    }

    fun restoreNormalUpdates(updates: List<CompoundTag>) {
        if (updates.isEmpty()) return
        val queue = runCatching { level.normalUpdateQueue }.getOrNull() ?: return
        for (tag in updates) {
            val block = level.getBlock(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"), tag.getInt("layer"))
            val neighbor = tag.getInt("neighbor", -1).takeIf { it >= 0 }?.let(BlockFace::fromIndex)
            queue.add(QueuedUpdate(block, neighbor))
        }
    }

    fun currentVillages(): ListTag<CompoundTag> {
        val result = ListTag<CompoundTag>()
        val manager = runCatching { level.villageManager }.getOrNull() ?: return result
        for (village in manager.villages) {
            val tag = CompoundTag()
                .putString("uuid", village.uuid().toString())
                .putCompound("DWELLERS", village.dwellers().toCompound())
                .putCompound("INFO", village.info().toCompound())
                .putCompound("PLAYERS", village.players().toCompound())
                .putCompound("POI", village.pois().toCompound())
            village.raid()?.let { tag.putCompound("RAID", it.toCompound()) }
            result.add(tag)
        }
        return result
    }

    fun restoreVillages(villages: List<CompoundTag>) {
        if (villages.isEmpty()) return
        val manager = runCatching { level.villageManager }.getOrNull() ?: return
        val restored = villages.mapNotNull { tag ->
            runCatching {
                Village(
                    UUID.fromString(tag.getString("uuid")),
                    VillageDwellers.fromCompound(tag.getCompound("DWELLERS")),
                    VillageInfo.fromCompound(tag.getCompound("INFO")),
                    VillagePlayers.fromCompound(tag.getCompound("PLAYERS")),
                    VillagePois.fromCompound(tag.getCompound("POI")),
                    if (tag.contains("RAID")) VillageRaid.fromCompound(tag.getCompound("RAID")) else null,
                )
            }.getOrNull()
        }
        manager.load(restored)
    }

    fun readGameRules(data: NbtCompound?): GameRules {
        val result = GameRules.getDefault()
        if (data == null) return result
        for ((name, value) in data) {
            val rule = GameRule.parseString(name).orElse(null) ?: continue
            val text = (value as? NbtString)?.value ?: continue
            runCatching {
                if (result.getGameRuleType(rule).name == "BOOLEAN" && (text == "0" || text == "1")) {
                    result.setGameRule(rule, text == "1")
                } else {
                    result.setGameRule(rule, text)
                }
            }
        }
        result.refresh()
        return result
    }

    fun writeGameRules(rules: GameRules): NbtCompound = NbtCompound.build {
        for ((key, value) in rules.gameRules) put(key.getName(), value.tag.toString())
    }
}
