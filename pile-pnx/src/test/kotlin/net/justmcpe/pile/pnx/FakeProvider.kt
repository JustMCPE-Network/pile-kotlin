package net.justmcpe.pile.pnx

import io.netty.buffer.ByteBuf
import it.unimi.dsi.fastutil.Pair
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.powernukkitx.level.DimensionData
import org.powernukkitx.level.DimensionEnum
import org.powernukkitx.level.GameRules
import org.powernukkitx.level.Level
import org.powernukkitx.level.format.IChunk
import org.powernukkitx.level.format.LevelProvider
import org.powernukkitx.math.Vector3

/** The least provider a chunk can be built against: a dimension and a level whose tick is zero. */
class FakeProvider(private val dimension: DimensionData = DimensionEnum.OVERWORLD.dimensionData) : LevelProvider {
    private val level: Level = mock(Level::class.java, withSettings().stubOnly())

    override fun getDimensionData(): DimensionData = dimension
    override fun getLevel(): Level = level
    override fun getPath(): String = "fake"
    override fun getName(): String = "fake"
    override fun getSpawn(): Vector3 = Vector3(0.0, 64.0, 0.0)
    override fun getGamerules(): GameRules = GameRules.getDefault()
    override fun getSeed(): Long = 0
    override fun getTime(): Long = 0
    override fun getCurrentTick(): Long = 0
    override fun isRaining(): Boolean = false
    override fun getRainTime(): Int = 0
    override fun isThundering(): Boolean = false
    override fun getThunderTime(): Int = 0
    override fun getNoSleepNight(): Int = 0
    override fun getLoadedChunks(): Map<Long, IChunk> = emptyMap()
    override fun isChunkGenerated(x: Int, z: Int): Boolean = true
    override fun isChunkPopulated(x: Int, z: Int): Boolean = true
    override fun isChunkLoaded(x: Int, z: Int): Boolean = false
    override fun isChunkLoaded(hash: Long): Boolean = false

    override fun requestChunkData(x: Int, z: Int): Pair<ByteBuf, Int> = throw UnsupportedOperationException()
    override fun getLoadedChunk(x: Int, z: Int): IChunk? = null
    override fun getLoadedChunk(hash: Long): IChunk? = null
    override fun getChunk(x: Int, z: Int): IChunk? = null
    override fun getChunk(x: Int, z: Int, create: Boolean): IChunk? = null
    override fun getEmptyChunk(x: Int, z: Int): IChunk = throw UnsupportedOperationException()
    override fun saveChunks() {}
    override fun saveChunks(chunks: Collection<IChunk>) {}
    override fun saveChunk(x: Int, z: Int) {}
    override fun saveChunk(x: Int, z: Int, chunk: IChunk) {}
    override fun unloadChunks() {}
    override fun loadChunk(x: Int, z: Int): Boolean = false
    override fun loadChunk(x: Int, z: Int, create: Boolean): Boolean = false
    override fun unloadChunk(x: Int, z: Int): Boolean = false
    override fun unloadChunk(x: Int, z: Int, safe: Boolean): Boolean = false
    override fun setChunk(chunkX: Int, chunkZ: Int, chunk: IChunk) {}
    override fun setRaining(raining: Boolean) {}
    override fun setRainTime(rainTime: Int) {}
    override fun setThundering(thundering: Boolean) {}
    override fun setThunderTime(thunderTime: Int) {}
    override fun setNoSleepNight(noSleepNight: Int) {}
    override fun setCurrentTick(currentTick: Long) {}
    override fun setTime(value: Long) {}
    override fun setSeed(value: Long) {}
    override fun setSpawn(pos: Vector3) {}
    override fun close() {}
    override fun saveLevelData() {}
    override fun updateLevelName(name: String) {}
    override fun setGameRules(rules: GameRules) {}
}
