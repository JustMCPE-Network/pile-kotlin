package net.justmcpe.pile.pnx

import io.netty.buffer.*
import it.unimi.dsi.fastutil.Pair
import net.justmcpe.pile.format.*
import net.justmcpe.pile.format.nbt.NbtCompound
import net.justmcpe.pile.format.nbt.NbtInt
import net.justmcpe.pile.format.nbt.NbtLong
import net.justmcpe.pile.pnx.convert.*
import org.cloudburstmc.nbt.NbtUtils
import org.powernukkitx.api.UsedByReflection
import org.powernukkitx.blockentity.BlockEntityMobSpawner
import org.powernukkitx.blockentity.BlockEntitySpawnable
import org.powernukkitx.level.DimensionData
import org.powernukkitx.level.GameRules
import org.powernukkitx.level.Level
import org.powernukkitx.level.format.*
import org.powernukkitx.math.Vector3
import org.powernukkitx.nbt.tag.CompoundTag
import org.powernukkitx.utils.BlockUpdateEntry
import org.powernukkitx.utils.ChunkException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A PNX level provider over one `.pile` file per dimension.
 *
 * A solid file is decoded whole when the level opens, which is the format's design point for
 * lobbies and minigame maps; edits accumulate in memory and a save is an atomic canonical rewrite.
 * An indexed file opens as a directory plus palettes, columns decode one frame at a time as PNX
 * loads chunks, and a saved chunk appends one record frame — memory and save cost follow the
 * loaded set, not the world.
 *
 * Register with [register] before the server loads levels, and give the world a `config.json`
 * whose `format` is `pile` and whose generator is `void` unless absent chunks should generate.
 */
public open class PileLevelProvider(
    private val level: Level,
    private val path: String,
    private val openOptions: PileProviderOptions,
) : LevelProvider {
    public constructor(level: Level, path: String) : this(level, path, PileProviderOptions())

    private val log = LoggerFactory.getLogger(PileLevelProvider::class.java)
    private val chunks = ConcurrentHashMap<Long, IChunk>()
    private val lastChunk = ThreadLocal<WeakReference<IChunk>>()
    private val pendingTicks = ConcurrentHashMap<Long, List<ScheduledTick>>()
    private val autosaveStops = ConcurrentLinkedQueue<AutoCloseable>()
    private val autosaveExecutors = ConcurrentLinkedQueue<ScheduledExecutorService>()
    private val state = ProviderState(level)

    private var world: World? = null
    private var indexed: IndexedPile? = null
    private val dirtyColumns = HashMap<Long, Column>()
    private var snapshotter: ColumnSnapshot
    private var converter: ChunkConverter
    private var converterStates = 0

    private val settings: WorldSettings
    private var gameRules: GameRules = GameRules.getDefault()
    private var name: String
    private var seed: Long = 0
    private var time: Long
    private var currentTick: Long
    private var raining: Boolean
    private var rainTime: Int
    private var thundering: Boolean
    private var thunderTime: Int
    private var noSleepNight: Int = 0
    private var spawn: Vector3
    private var dynamicProperties: CompoundTag = CompoundTag()
    private var baseUserData: ByteArray
    private val memoryInstance: Boolean = PileTemplate.isInstancePath(path)

    init {
        val settingsBytes: ByteArray
        val userDataBytes: ByteArray
        if (memoryInstance) {
            val base =
                PileTemplate.claim(path) ?: throw IllegalStateException("no template instance registered under $path")
            world = base
            settingsBytes = base.settings
            userDataBytes = base.userData
            snapshotter = ColumnSnapshot(base, openOptions)
            converter = ChunkConverter(base)
            converterStates = base.blockStates.size
            log.info("opened template instance {} ({} shared columns)", path, base.columns.size)
        } else if (isIndexedFile(dimensionFile(Path.of(path), level.dimensionData))) {
            val file = dimensionFile(Path.of(path), level.dimensionData)
            val handle = IndexedPile.open(file, DecodeOptions(openOptions.maxDecodedBytes), openOptions.readOnly)
            indexed = handle
            settingsBytes = handle.settings
            userDataBytes = handle.userData
            snapshotter = ColumnSnapshot(handle.blockStates, handle.biomes, openOptions)
            converter = ChunkConverter(handle.blockStates, handle.biomes)
            converterStates = handle.blockStates.size
            log.info(
                "opened {} ({} columns indexed, {} block states) from {}",
                file.fileName,
                handle.columnCount,
                handle.blockStates.size,
                file
            )
        } else {
            val file = dimensionFile(Path.of(path), level.dimensionData)
            val loaded = PileReader.readWorld(Files.readAllBytes(file), DecodeOptions(openOptions.maxDecodedBytes))
            world = loaded
            settingsBytes = loaded.settings
            userDataBytes = loaded.userData
            snapshotter = ColumnSnapshot(loaded, openOptions)
            converter = ChunkConverter(loaded)
            converterStates = loaded.blockStates.size
            log.info(
                "loaded {} ({} columns, {} block states) from {}",
                file.fileName,
                loaded.columns.size,
                loaded.blockStates.size,
                file
            )
        }
        val storedMetadata = readPnxMetadata(userDataBytes)
        baseUserData = storedMetadata.userData
        dynamicProperties = storedMetadata.dynamic
        state.restoreNormalUpdates(storedMetadata.normalUpdates)
        state.restoreVillages(storedMetadata.villages)
        settings = WorldSettings.parse(settingsBytes)
        name = settings.name ?: Path.of(path).fileName.toString()
        time = settings.time ?: 0
        currentTick = settings.currentTick ?: 0
        raining = settings.raining ?: false
        rainTime = settings.rainTime?.toInt() ?: 0
        thundering = settings.thundering ?: false
        thunderTime = settings.thunderTime?.toInt() ?: 0
        spawn = Vector3(
            (settings.spawnX ?: 0).toDouble(),
            (settings.spawnY ?: 64).toDouble(),
            (settings.spawnZ ?: 0).toDouble()
        )
        seed = (settings.extra[PNX_SEED] as? NbtLong)?.value ?: 0L
        noSleepNight = (settings.extra[PNX_NO_SLEEP_NIGHT] as? NbtInt)?.value ?: 0
        gameRules = state.readGameRules(settings.extra[PNX_GAME_RULES] as? NbtCompound)
    }

    /** The decoded world behind this provider. For an indexed file this decodes every column. */
    public val pileWorld: World
        get() {
            world?.let { return mergedWorld(it) }
            val handle = indexed!!
            val columns = handle.positions().map { handle.column(it[0], it[1])!! }
            return World(
                handle.blockVersion,
                handle.settings,
                handle.userData,
                handle.blockStates,
                handle.biomes,
                columns
            )
        }

    private class ScheduledTick(val x: Int, val y: Int, val z: Int, val stateName: String, val delay: Long)

    override fun getDimensionData(): DimensionData = level.dimensionData

    override fun getPath(): String = path

    override fun getName(): String = name

    override fun getLevel(): Level = level

    private fun paletteState(ref: Int): BlockState = snapshotter.paletteStates[ref]

    private fun columnFor(x: Int, z: Int): Column? {
        openOptions.filterColumn?.let { if (!it(x, z)) return null }
        dirtyColumns[Level.chunkHash(x, z)]?.let { return it }
        val column = world?.column(x, z) ?: indexed?.column(x, z) ?: return null
        return filterColumn(column)
    }

    private fun filterColumn(column: Column): Column {
        val entityFilter = openOptions.filterEntity
        val blockEntityFilter = openOptions.filterBlockEntity
        if (entityFilter == null && blockEntityFilter == null) return column
        val entities =
            if (entityFilter == null) column.entities else column.entities.filter { entityFilter(it.decoded()) }
        val blockEntities =
            if (blockEntityFilter == null) column.blockEntities else column.blockEntities.filter { blockEntityFilter(it.decoded()) }
        if (entities.size == column.entities.size && blockEntities.size == column.blockEntities.size) return column
        return Column(
            column.x, column.z, column.minSection, column.sections, column.biomes, column.light,
            blockEntities, entities, column.tick, column.scheduledUpdates, column.userData,
        )
    }

    private fun refreshConverter() {
        val handle = indexed ?: return
        if (handle.blockStates.size != converterStates) {
            converter = ChunkConverter(handle.blockStates, handle.biomes)
            converterStates = handle.blockStates.size
        }
    }

    private fun buildChunk(column: Column): IChunk {
        refreshConverter()
        val chunk = converter.convert(this, column)
        if (column.scheduledUpdates.isNotEmpty()) {
            pendingTicks[chunk.index] = column.scheduledUpdates.map {
                ScheduledTick(it.x, it.y, it.z, paletteState(it.state).name, maxOf(1L, it.tick - column.tick))
            }
        }
        return chunk
    }

    private fun restoreTicks(chunk: IChunk) {
        val ticks = pendingTicks.remove(chunk.index) ?: return
        for (t in ticks) {
            val block = level.getBlock(t.x, t.y, t.z, 0) ?: continue
            if (block.id == t.stateName) {
                chunk.blockUpdateScheduler.add(
                    BlockUpdateEntry(
                        Vector3(t.x.toDouble(), t.y.toDouble(), t.z.toDouble()),
                        block,
                        level.currentTick + t.delay,
                        0,
                        false
                    )
                )
            }
        }
    }

    private fun loadChunk(index: Long, chunkX: Int, chunkZ: Int, create: Boolean): IChunk? {
        chunks[index]?.let { return it }
        val column = synchronized(this) { columnFor(chunkX, chunkZ) }
        val chunk = if (column != null) {
            buildChunk(column)
        } else if (create) {
            generateChunk(chunkX, chunkZ) ?: getEmptyChunk(chunkX, chunkZ)
        } else {
            return null
        }
        val existing = chunks.putIfAbsent(index, chunk)
        if (existing != null) return existing
        if (column != null) restoreTicks(chunk)
        return chunk
    }

    private fun generateChunk(chunkX: Int, chunkZ: Int): IChunk? {
        val generator = level.generator ?: return null
        val generated = generator.syncGenerate(getEmptyChunk(chunkX, chunkZ))
        generated.setPosition(chunkX, chunkZ)
        if (!generated.isGenerated) generated.setGenerated()
        if (!generated.isPopulated) generated.setPopulated()
        generated.setChanged()
        return generated
    }

    override fun getLoadedChunk(x: Int, z: Int): IChunk? = getLoadedChunk(Level.chunkHash(x, z))

    override fun getLoadedChunk(hash: Long): IChunk? {
        val cached = lastChunk.get()?.get()
        if (cached != null && cached.index == hash) return cached
        val chunk = chunks[hash]
        lastChunk.set(WeakReference(chunk))
        return chunk
    }

    override fun getChunk(x: Int, z: Int): IChunk? = getChunk(x, z, false)

    override fun getChunk(x: Int, z: Int, create: Boolean): IChunk? {
        val index = Level.chunkHash(x, z)
        getLoadedChunk(index)?.let { return it }
        val chunk = loadChunk(index, x, z, create)
        lastChunk.set(WeakReference(chunk))
        return chunk
    }

    override fun getEmptyChunk(x: Int, z: Int): IChunk = Chunk.builder().levelProvider(this).emptyChunk(x, z)

    override fun loadChunk(x: Int, z: Int): Boolean = loadChunk(x, z, false)

    override fun loadChunk(x: Int, z: Int, create: Boolean): Boolean {
        val index = Level.chunkHash(x, z)
        if (chunks.containsKey(index)) return true
        return loadChunk(index, x, z, create) != null
    }

    override fun unloadChunk(x: Int, z: Int): Boolean = unloadChunk(x, z, true)

    override fun unloadChunk(x: Int, z: Int, safe: Boolean): Boolean {
        val index = Level.chunkHash(x, z)
        val chunk = chunks[index] ?: return false
        // The chunk leaves memory here, so what it holds is captured back into the world model
        // first; the file itself moves on the next save.
        if (!openOptions.readOnly && chunk.hasChanged()) captureChunk(chunk)
        if (!chunk.unload(false, safe)) return false
        lastChunk.remove()
        chunks.remove(index, chunk)
        return true
    }

    override fun unloadChunks() {
        val it = chunks.values.iterator()
        while (it.hasNext()) {
            val chunk = it.next()
            if (!openOptions.readOnly && chunk.hasChanged()) captureChunk(chunk)
            chunk.unload(false, false)
            it.remove()
        }
    }

    override fun isChunkGenerated(x: Int, z: Int): Boolean =
        synchronized(this) { columnFor(x, z) } != null || level.generator != null

    override fun isChunkPopulated(x: Int, z: Int): Boolean {
        val chunk = getChunk(x, z) ?: return false
        return chunk.chunkState.ordinal >= 2
    }

    override fun isChunkLoaded(x: Int, z: Int): Boolean = chunks.containsKey(Level.chunkHash(x, z))

    override fun isChunkLoaded(hash: Long): Boolean = chunks.containsKey(hash)

    override fun setChunk(chunkX: Int, chunkZ: Int, chunk: IChunk) {
        chunk.setPosition(chunkX, chunkZ)
        val index = Level.chunkHash(chunkX, chunkZ)
        val old = chunks[index]
        if (old != null && old != chunk) {
            if (!openOptions.readOnly && old.hasChanged()) captureChunk(old)
            old.unload(false, false)
            chunks.remove(index, old)
        }
        lastChunk.remove()
        chunks[index] = chunk
    }

    override fun getLoadedChunks(): Map<Long, IChunk> = Collections.unmodifiableMap(chunks)

    @Synchronized
    override fun saveChunks() {
        if (openOptions.readOnly) return
        persistWorld()
    }

    @Synchronized
    override fun saveChunks(chunks: Collection<IChunk>) {
        if (openOptions.readOnly) return
        persistWorld()
    }

    // A solid file has no per-chunk write, so the chunk is captured into the world model and the
    // next full save carries it. An indexed file appends exactly one record frame.
    @Synchronized
    override fun saveChunk(x: Int, z: Int) {
        if (openOptions.readOnly) return
        chunks[Level.chunkHash(x, z)]?.let(::captureChunk)
    }

    @Synchronized
    override fun saveChunk(x: Int, z: Int, chunk: IChunk) {
        if (openOptions.readOnly) return
        setChunk(x, z, chunk)
        captureChunk(chunk)
    }

    @Synchronized
    override fun saveLevelData() {
        if (openOptions.readOnly) return
        persistWorld()
    }

    @Synchronized
    override fun close() {
        autosaveStops.forEach { it.close() }
        autosaveStops.clear()
        autosaveExecutors.forEach { it.shutdownNow() }
        autosaveExecutors.clear()
        if (!openOptions.readOnly && !memoryInstance) persistWorld()
        if (memoryInstance) PileTemplate.release(path)
        indexed?.close()
        indexed = null
        chunks.clear()
    }

    /** Writes the current state as a solid world under [dir], template instances included. */
    @Synchronized
    public fun saveAs(dir: Path) {
        for (chunk in chunks.values) if (chunk.hasChanged()) captureChunk(chunk)
        val base = world
        val merged = if (base != null) {
            mergedWorld(base)
        } else {
            val handle = indexed!!
            handle.setMeta(currentSettingsBytes(), currentUserDataBytes())
            mergedWorld(
                World(
                    handle.blockVersion,
                    handle.settings,
                    handle.userData,
                    handle.blockStates,
                    handle.biomes,
                    handle.positions().map { handle.column(it[0], it[1])!! })
            )
        }
        PileWriter.writeWorld(
            dimensionFile(dir, level.dimensionData),
            merged,
            WriteOptions(
                openOptions.compression,
                stats = true,
                storeLight = openOptions.storeLight,
                fastCompression = openOptions.fastSaves
            ),
        )
    }

    /**
     * Starts a coalesced periodic save, matching the upstream provider's AutoSave lifecycle.
     * The returned handle is idempotent and is also stopped automatically by [close].
     */
    public fun autoSave(interval: Duration): AutoCloseable {
        require(!interval.isZero && !interval.isNegative) { "autosave interval must be positive" }
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pile-pnx-autosave").apply { isDaemon = true }
        }
        val period = maxOf(1L, interval.toMillis())
        executor.scheduleAtFixedRate(
            { runCatching { saveChunks() }.onFailure { log.error("autosave failed", it) } },
            period,
            period,
            TimeUnit.MILLISECONDS,
        )
        autosaveExecutors.add(executor)
        val handle = AutoCloseable { executor.shutdownNow() }
        autosaveStops.add(handle)
        return handle
    }

    /** Copies the dimension files into snapshots/<name>, replacing a previous snapshot of that name. */
    @Synchronized
    public fun snapshot(name: String): Path {
        check(!openOptions.readOnly) { "cannot snapshot a read-only provider" }
        val destination = snapshotDirectory(name)
        persistWorld()
        Files.createDirectories(destination.parent)
        deleteTree(destination)
        Files.createDirectories(destination)
        Files.list(Path.of(path)).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".pile") && Files.isRegularFile(it) }.forEach { source ->
                val temporary = Files.createTempFile(destination, source.fileName.toString(), ".tmp")
                try {
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
                    Files.move(temporary, destination.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
        return destination
    }

    /** Lists snapshot names in deterministic order. */
    public fun snapshots(): List<String> {
        val root = Path.of(path).resolve(SNAPSHOTS_DIRECTORY)
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { stream ->
            return stream.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    /**
     * Replaces the world's files with a snapshot and reloads. Loaded chunks are dropped: every
     * change since the snapshot, saved or not, is discarded, which is what a rollback is for.
     */
    @Synchronized
    public fun rollback(name: String) {
        check(!openOptions.readOnly) { "cannot roll back a read-only provider" }
        val source = snapshotDirectory(name)
        check(Files.isDirectory(source)) { "no snapshot named \"$name\"" }
        indexed?.close()
        indexed = null
        world = null
        chunks.clear()
        dirtyColumns.clear()
        pendingTicks.clear()
        lastChunk.remove()
        Files.list(source).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".pile") }.forEach { file ->
                Files.copy(file, Path.of(path).resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val file = dimensionFile(Path.of(path), level.dimensionData)
        if (isIndexedFile(file)) {
            val handle = IndexedPile.open(file, DecodeOptions(openOptions.maxDecodedBytes))
            indexed = handle
            snapshotter = ColumnSnapshot(handle.blockStates, handle.biomes, openOptions)
            converter = ChunkConverter(handle.blockStates, handle.biomes)
            converterStates = handle.blockStates.size
        } else {
            val loaded = PileReader.readWorld(Files.readAllBytes(file), DecodeOptions(openOptions.maxDecodedBytes))
            world = loaded
            snapshotter = ColumnSnapshot(loaded, openOptions)
            converter = ChunkConverter(loaded)
            converterStates = loaded.blockStates.size
        }
    }

    /** Deletes one validated snapshot directory. */
    public fun deleteSnapshot(name: String) {
        check(!openOptions.readOnly) { "cannot delete a snapshot from a read-only provider" }
        deleteTree(snapshotDirectory(name))
    }

    /** The world's application metadata blob, as `pile.UserData` upstream. */
    @Synchronized
    public fun getUserData(): ByteArray = baseUserData.copyOf()

    @Synchronized
    public fun setUserData(data: ByteArray) {
        if (openOptions.readOnly) return
        baseUserData = data.copyOf()
    }

    /** Per-column application metadata, as `pile.ChunkUserData` upstream. */
    @Synchronized
    public fun getChunkUserData(x: Int, z: Int): ByteArray? = columnFor(x, z)?.userData?.copyOf()

    @Synchronized
    public fun setChunkUserData(x: Int, z: Int, data: ByteArray) {
        if (openOptions.readOnly) return
        val column = columnFor(x, z) ?: return
        val updated = Column(
            column.x, column.z, column.minSection, column.sections, column.biomes, column.light,
            column.blockEntities, column.entities, column.tick, column.scheduledUpdates, data.copyOf(),
        )
        val handle = indexed
        if (handle != null) {
            handle.store(updated, snapshotter.paletteStates, snapshotter.paletteBiomes)
        } else {
            dirtyColumns[Level.chunkHash(x, z)] = updated
        }
    }

    /** Folds one loaded chunk back into the world model; an indexed file takes it as a record frame. */
    @Synchronized
    private fun captureChunk(chunk: IChunk) {
        val column = snapshotter.snapshot(chunk, columnFor(chunk.x, chunk.z))
        val handle = indexed
        if (handle != null) {
            handle.store(column, snapshotter.paletteStates, snapshotter.paletteBiomes)
        } else {
            dirtyColumns[chunk.index] = column
        }
        chunk.setChanged(false)
    }

    private fun mergedWorld(base: World): World {
        for (chunk in chunks.values) if (chunk.hasChanged()) captureChunk(chunk)
        val states = snapshotter.paletteStates.toList()
        val biomes = snapshotter.paletteBiomes.toList()
        if (dirtyColumns.isEmpty()) {
            return World(
                base.blockVersion,
                currentSettingsBytes(),
                currentUserDataBytes(),
                states,
                biomes,
                base.columns
            )
        }
        val positions = HashMap<Long, Int>(base.columns.size)
        val columns = base.columns.toMutableList()
        columns.forEachIndexed { index, column -> positions[Level.chunkHash(column.x, column.z)] = index }
        for ((key, column) in dirtyColumns) {
            val at = positions[key]
            if (at == null) {
                positions[key] = columns.size
                columns.add(column)
            } else {
                columns[at] = column
            }
        }
        return World(base.blockVersion, currentSettingsBytes(), currentUserDataBytes(), states, biomes, columns)
    }

    /** Writes every pending change to disk: a canonical rewrite for solid, a checkpoint for indexed. */
    @Synchronized
    private fun persistWorld() {
        if (openOptions.readOnly) return
        if (memoryInstance) {
            // An instance lives and dies in memory; keeping one is an explicit saveAs.
            for (chunk in chunks.values) if (chunk.hasChanged()) captureChunk(chunk)
            return
        }
        val handle = indexed
        if (handle != null) {
            for (chunk in chunks.values) if (chunk.hasChanged()) captureChunk(chunk)
            handle.setMeta(currentSettingsBytes(), currentUserDataBytes())
            handle.checkpoint()
            return
        }
        val base = world ?: return
        val updated = mergedWorld(base)
        val file = dimensionFile(Path.of(path), level.dimensionData)
        if (openOptions.appendMode) {
            PileWriter.writeIndexed(file, updated, openOptions.compression)
            world = null
            dirtyColumns.clear()
            val reopened = IndexedPile.open(file, DecodeOptions(openOptions.maxDecodedBytes))
            indexed = reopened
            snapshotter = ColumnSnapshot(reopened.blockStates, reopened.biomes, openOptions)
            converter = ChunkConverter(reopened.blockStates, reopened.biomes)
            converterStates = reopened.blockStates.size
            return
        }
        PileWriter.writeWorld(
            file,
            updated,
            WriteOptions(
                openOptions.compression,
                stats = true,
                storeLight = openOptions.storeLight,
                fastCompression = openOptions.fastSaves
            )
        )
        world = updated
        dirtyColumns.clear()
    }

    private fun currentSettingsBytes(): ByteArray = WorldSettings(
        name = name,
        spawnX = spawn.getFloorX(),
        spawnY = spawn.getFloorY(),
        spawnZ = spawn.getFloorZ(),
        time = time,
        currentTick = currentTick,
        rainTime = rainTime.toLong(),
        raining = raining,
        thunderTime = thunderTime.toLong(),
        thundering = thundering,
        extra = settings.extra.toMutableMap().apply {
            put(PNX_SEED, NbtLong(seed))
            put(PNX_NO_SLEEP_NIGHT, NbtInt(noSleepNight))
            put(PNX_GAME_RULES, state.writeGameRules(gameRules))
        },
    ).encode()

    private fun currentUserDataBytes(): ByteArray = PnxNbt.write(
        CompoundTag()
            .putString(PNX_METADATA, "1")
            .putByteArray("userData", baseUserData)
            .putCompound("dynamicProperties", dynamicProperties.copy())
            .putList("normalUpdates", state.currentNormalUpdates())
            .putList("villages", state.currentVillages()),
    )

    private class PnxMetadata(
        val userData: ByteArray,
        val dynamic: CompoundTag,
        val normalUpdates: List<CompoundTag>,
        val villages: List<CompoundTag>,
    )

    private fun readPnxMetadata(blob: ByteArray): PnxMetadata {
        if (blob.isEmpty()) return PnxMetadata(ByteArray(0), CompoundTag(), emptyList(), emptyList())
        return runCatching {
            val root = PnxNbt.read(blob)
            if (root.getString(PNX_METADATA) != "1") return@runCatching PnxMetadata(
                blob,
                CompoundTag(),
                emptyList(),
                emptyList()
            )
            PnxMetadata(
                root.getByteArray("userData"),
                root.getCompound("dynamicProperties").copy(),
                root.getList("normalUpdates").getAll().mapNotNull { it as? CompoundTag }.map { it.copy() },
                root.getList("villages").getAll().mapNotNull { it as? CompoundTag }.map { it.copy() },
            )
        }.getOrElse { PnxMetadata(blob, CompoundTag(), emptyList(), emptyList()) }
    }

    private fun snapshotDirectory(name: String): Path {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
            "invalid snapshot name"
        }
        return Path.of(path).resolve(SNAPSHOTS_DIRECTORY).resolve(name).normalize().also {
            require(it.parent == Path.of(path).resolve(SNAPSHOTS_DIRECTORY).normalize()) { "invalid snapshot path" }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** PNX's LevelDB provider exposes dynamic world properties; keep the same extension point. */
    public fun getWorldDynamicProperties(): CompoundTag = dynamicProperties.copy()

    public fun setWorldDynamicProperties(properties: CompoundTag) {
        dynamicProperties = properties.copy()
    }

    override fun updateLevelName(name: String) {
        this.name = name
    }

    override fun isRaining(): Boolean = raining
    override fun setRaining(raining: Boolean) {
        this.raining = raining
    }

    override fun getRainTime(): Int = rainTime
    override fun setRainTime(rainTime: Int) {
        this.rainTime = rainTime
    }

    override fun isThundering(): Boolean = thundering
    override fun setThundering(thundering: Boolean) {
        this.thundering = thundering
    }

    override fun getThunderTime(): Int = thunderTime
    override fun setThunderTime(thunderTime: Int) {
        this.thunderTime = thunderTime
    }

    override fun getNoSleepNight(): Int = noSleepNight
    override fun setNoSleepNight(noSleepNight: Int) {
        this.noSleepNight = noSleepNight
    }

    override fun getCurrentTick(): Long = currentTick
    override fun setCurrentTick(currentTick: Long) {
        this.currentTick = currentTick
    }

    override fun getTime(): Long = time
    override fun setTime(value: Long) {
        time = value
    }

    override fun getSeed(): Long = seed
    override fun setSeed(value: Long) {
        seed = value
    }

    override fun getSpawn(): Vector3 = spawn
    override fun setSpawn(pos: Vector3) {
        spawn = pos
    }

    override fun getGamerules(): GameRules = gameRules
    override fun setGameRules(rules: GameRules) {
        gameRules = rules
    }

    override fun requestChunkData(x: Int, z: Int): Pair<ByteBuf, Int> {
        val chunk = getChunk(x, z, false) ?: throw ChunkException("Invalid Chunk Set")
        val data = AtomicReference<ByteBuf>()
        val count = AtomicReference<Int>()
        chunk.batchProcess { unsafe ->
            val buf = PooledByteBufAllocator.DEFAULT.ioBuffer()
            var success = false
            try {
                val sections = unsafe.sections
                var subChunkCount = unsafe.dimensionData.chunkSectionCount
                while (subChunkCount-- != 0) if (sections[subChunkCount] != null) break
                val total = subChunkCount + 1
                val minSectionY = unsafe.dimensionData.minSectionY
                if (level.isAntiXrayEnabled) {
                    for (i in 0 until total) {
                        if (sections[i] == null) sections[i] = ChunkSection((i + minSectionY).toByte())
                        sections[i].writeObfuscatedToBuf(level, buf)
                    }
                } else {
                    for (i in 0 until total) {
                        val section = sections[i]
                        if (section != null) section.writeToBuf(buf) else buf.writeBytes(emptySectionPayload(i + minSectionY))
                    }
                }
                for (i in 0 until total) {
                    val section = sections[i]
                    if (section != null) section.biomes().writeToNetwork(buf) { it } else buf.writeBytes(
                        emptyBiomePayload()
                    )
                }
                writeBorderBlockData(buf, chunk)
                val tags = ArrayList<CompoundTag>()
                for (blockEntity in unsafe.blockEntities.values) {
                    if (blockEntity is BlockEntitySpawnable) {
                        if (blockEntity is BlockEntityMobSpawner && !blockEntity.hasSpawnEntityType()) continue
                        tags.add(blockEntity.spawnCompound)
                        level.addChunkPacket(blockEntity.chunkX, blockEntity.chunkZ, blockEntity.spawnPacket)
                    }
                }
                try {
                    ByteBufOutputStream(buf).use { stream ->
                        NbtUtils.createNetworkWriter(stream).use { out ->
                            if (tags.isEmpty()) stream.writeByte(0) else for (tag in tags) out.writeTag(tag.toNetwork())
                        }
                    }
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
                data.set(buf)
                count.set(total)
                success = true
            } finally {
                if (!success) buf.release()
            }
        }
        return Pair.of(data.get(), count.get())
    }

    private fun writeBorderBlockData(buf: ByteBuf, chunk: IChunk) {
        if (!chunk.areBorderBlockColumnsInitialized()) chunk.rebuildBorderBlockColumns()
        val countIndex = buf.writerIndex()
        buf.writeByte(0)
        var count = 0
        for ((mask, offset) in listOf(
            chunk.borderColumnsLow to 0,
            chunk.borderColumnsMidLow to 64,
            chunk.borderColumnsMidHigh to 128,
            chunk.borderColumnsHigh to 192
        )) {
            var m = mask
            while (m != 0L && count < 255) {
                val bit = java.lang.Long.numberOfTrailingZeros(m)
                buf.writeByte(offset + bit)
                m = m and (1L shl bit).inv()
                count++
            }
            if (count >= 255) break
        }
        buf.setByte(countIndex, minOf(count, 255))
    }

    public companion object {
        public const val NAME: String = "pile"
        private const val SNAPSHOTS_DIRECTORY: String = "snapshots"
        private const val PNX_SEED: String = "pnx_seed"
        private const val PNX_NO_SLEEP_NIGHT: String = "pnx_no_sleep_night"
        private const val PNX_GAME_RULES: String = "pnx_gamerules"
        private const val PNX_METADATA: String = "__pile_pnx_metadata"
        private val emptySections = AtomicReferenceArray<ByteArray>(1 shl 8)

        @Volatile
        private var emptyBiomes: ByteArray? = null

        /** Registers this provider with PNX under the name `pile`. Call once, before levels load. */
        @JvmStatic
        public fun register() {
            LevelProviderManager.addProvider(NAME, PileLevelProvider::class.java)
        }

        /** The dimension file inside a world directory: `overworld.pile`, `nether.pile`, `end.pile` or `dim<id>.pile`. */
        @JvmStatic
        public fun dimensionFile(dir: Path, dimension: DimensionData): Path = dir.resolve(
            when (val id = dimension.dimensionId) {
                0 -> "overworld.pile"
                1 -> "nether.pile"
                2 -> "end.pile"
                else -> "dim$id.pile"
            },
        )

        private fun isIndexedFile(file: Path): Boolean {
            Files.newInputStream(file).use { input ->
                val header = input.readNBytes(8)
                return header.size == 8 && header[7].toInt() == 1
            }
        }

        @UsedByReflection
        @JvmStatic
        public fun isValid(path: String): Boolean = Files.isRegularFile(Path.of(path, "overworld.pile"))

        @UsedByReflection
        @JvmStatic
        public fun generate(path: String, name: String, generatorConfig: LevelConfig.GeneratorConfig) {
            val dimension = generatorConfig.dimensionData()
            PileWriter.writeWorld(dimensionFile(Path.of(path), dimension), emptyWorld(name, dimension))
        }

        /** The world a generated dimension starts from: no columns, current block version. */
        @JvmStatic
        public fun emptyWorld(name: String, dimension: DimensionData): World {
            return World(
                blockVersion = BlockStates.currentVersion,
                settings = WorldSettings(name = name, spawnY = 64).encode(),
                userData = ByteArray(0),
                blockStates = listOf(BlockState.air(BlockStates.currentVersion)),
                biomes = listOf("minecraft:plains"),
                columns = emptyList(),
            )
        }

        private fun emptySectionPayload(sectionY: Int): ByteArray {
            val slot = sectionY.toByte() - Byte.MIN_VALUE
            var payload = emptySections.get(slot)
            if (payload == null) {
                val scratch = Unpooled.buffer()
                try {
                    ChunkSection(sectionY.toByte()).writeToBuf(scratch)
                    payload = ByteBufUtil.getBytes(scratch)
                } finally {
                    scratch.release()
                }
                emptySections.compareAndSet(slot, null, payload)
                payload = emptySections.get(slot)
            }
            return payload
        }

        private fun emptyBiomePayload(): ByteArray {
            var payload = emptyBiomes
            if (payload == null) {
                val scratch = Unpooled.buffer()
                try {
                    ChunkSection(0).biomes().writeToNetwork(scratch) { it }
                    payload = ByteBufUtil.getBytes(scratch)
                } finally {
                    scratch.release()
                }
                emptyBiomes = payload
            }
            return payload
        }
    }
}
