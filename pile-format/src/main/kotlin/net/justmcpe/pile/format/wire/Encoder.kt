package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*
import net.justmcpe.pile.format.nbt.Nbt

/** A storage reduced to the entries its indices use, as build-order palette ids. */
internal class UsedStorage(val buildIds: IntArray, val slots: IntArray, val storage: Storage) {
    val isUniform: Boolean get() = buildIds.size == 1

    companion object {
        /** [toBuild] maps a storage's global reference to a merged build id, counting nothing. */
        fun of(storage: Storage, toBuild: (Int) -> Int): UsedStorage {
            val indices =
                storage.indices ?: return UsedStorage(intArrayOf(toBuild(storage.palette[0])), intArrayOf(0), storage)
            val used = BooleanArray(storage.palette.size)
            var unseen = storage.palette.size
            for (i in indices.indices) {
                val slot = indices[i].toInt() and 0xFFFF
                if (!used[slot]) {
                    used[slot] = true
                    if (--unseen == 0) break
                }
            }
            // Distinct global references can merge into one build id; fold them so the local
            // palette stays strictly ascending on the wire.
            val slotToBuild = IntArray(storage.palette.size) { -1 }
            val builds = ArrayList<Int>()
            val slots = ArrayList<Int>()
            val seen = HashMap<Int, Int>()
            for (slot in storage.palette.indices) {
                if (!used[slot]) continue
                val build = toBuild(storage.palette[slot])
                val at = seen.getOrPut(build) {
                    builds.add(build)
                    slots.add(slot)
                    builds.size - 1
                }
                slotToBuild[slot] = at
            }
            if (builds.size == 1) return UsedStorage(intArrayOf(builds[0]), intArrayOf(slots[0]), storage)
            return UsedStorage(builds.toIntArray(), slotToBuild, storage)
        }
    }

    /** Distinct build ids this storage references, for palette counting. */
    fun countInto(count: (Int) -> Unit) {
        for (b in buildIds) count(b)
    }

    /** The canonical §3.3 blob bytes under the final palette order. */
    fun canonicalBlob(remap: IntArray): ByteArray {
        if (buildIds.size == 1) {
            val w = ByteWriter(8)
            w.uvarint(1)
            w.uvarint(remap[buildIds[0]].toLong())
            w.u8(0)
            return w.toByteArray()
        }
        val finals = IntArray(buildIds.size) { remap[buildIds[it]] }
        val order = (0 until finals.size).sortedBy { finals[it] }
        val localIndex = IntArray(finals.size)
        order.forEachIndexed { at, old -> localIndex[old] = at }
        val n = finals.size
        val w = ByteWriter(8 + n * 3 + Limits.STORAGE_SIZE * (if (n > 256) 2 else 1))
        w.uvarint(n)
        for (old in order) w.uvarint(finals[old].toLong())
        val indices = storage.indices!!
        if (n <= 256) {
            w.u8(1)
            val at = w.reserve(Limits.STORAGE_SIZE)
            val buf = w.array()
            for (i in 0 until Limits.STORAGE_SIZE) {
                buf[at + i] = localIndex[slots[indices[i].toInt() and 0xFFFF]].toByte()
            }
        } else {
            w.u8(2)
            val at = w.reserve(Limits.STORAGE_SIZE * 2)
            val buf = w.array()
            for (i in 0 until Limits.STORAGE_SIZE) {
                val v = localIndex[slots[indices[i].toInt() and 0xFFFF]]
                buf[at + 2 * i] = v.toByte()
                buf[at + 2 * i + 1] = (v ushr 8).toByte()
            }
        }
        return w.toByteArray()
    }
}

/** Deduplicating §3.4 blob table; ids in first-use order. */
internal class BlobTable {
    private val byHash = HashMap<Long, MutableList<Int>>()
    private val blobs = ArrayList<ByteArray>()

    fun add(blob: ByteArray): Int {
        val h = XxHash.hash(blob)
        byHash[h]?.forEach { id -> if (blobs[id].contentEquals(blob)) return id }
        blobs.add(blob)
        byHash.getOrPut(h) { ArrayList(1) }.add(blobs.size - 1)
        if (blobs.size > Limits.MAX_BLOBS) throw InvalidContentException("blob table exceeds ${Limits.MAX_BLOBS} entries")
        return blobs.size - 1
    }

    val size: Int get() = blobs.size

    fun encode(w: ByteWriter) {
        w.uvarint(blobs.size)
        blobs.forEach(w::raw)
    }
}

/** One column prepared for encoding: canonical collections, kept layers, build-order palette ids. */
internal class PreparedColumn(
    val x: Int,
    val z: Int,
    val minSection: Int,
    val sectionCount: Int,
    val sections: Array<List<UsedStorage>?>,
    val biomes: Array<UsedStorage>,
    val biomeUniform: BooleanArray,
    val light: Array<LightData?>?,
    val blockEntities: List<PreparedEntry>,
    val entities: List<PreparedEntity>,
    val tick: Long,
    val ticks: List<PreparedTick>,
    val userData: ByteArray,
) {
    var blockBlobs: Array<Array<ByteArray>?>? = null
    var biomeBlobs: Array<ByteArray?>? = null

    /** Precomputes this column's canonical blob bytes so the serial table pass only deduplicates. */
    fun precomputeBlobs(blockRemap: IntArray, biomeRemap: IntArray?, defaultRef: Int) {
        val block = arrayOfNulls<Array<ByteArray>>(sectionCount)
        for (i in sections.indices) {
            val layers = sections[i] ?: continue
            block[i] = Array(layers.size) { layers[it].canonicalBlob(blockRemap) }
        }
        blockBlobs = block
        if (biomeRemap != null) {
            val biome = arrayOfNulls<ByteArray>(sectionCount)
            for (i in biomes.indices) {
                val elided = defaultRef >= 0 && biomeUniform[i] && biomeRemap[biomes[i].buildIds[0]] == defaultRef
                if (!elided) biome[i] = biomes[i].canonicalBlob(biomeRemap)
            }
            biomeBlobs = biome
        }
    }
}

internal class PreparedEntry(val x: Int, val y: Int, val z: Int, val nbt: ByteArray)
internal class PreparedEntity(val id: Long, val nbt: ByteArray)
internal class PreparedTick(val packedXZ: Int, val y: Int, val buildId: Int, val at: Long)

/** A column's collections after canonicalisation and sorting: the parallelisable half of prepare. */
internal class PreparedCollections(val blockEntities: List<PreparedEntry>, val entities: List<PreparedEntity>)

/** Shared preparation and encoding of chunk records, used by the solid and indexed writers. */
internal object RecordEncoder {
    /** Canonical NBT and collection order for one column; pure, safe to run across columns in parallel. */
    fun prepareCollections(column: Column): PreparedCollections {
        val bes = ArrayList<PreparedEntry>(column.blockEntities.size)
        for (be in column.blockEntities) {
            if (Math.floorDiv(be.x, 16) != column.x || Math.floorDiv(be.z, 16) != column.z) {
                throw InvalidContentException("column (${column.x},${column.z}) has a block entity at (${be.x},${be.y},${be.z}), outside its footprint")
            }
            if (be.y < column.minY || be.y > column.maxY) {
                throw InvalidContentException("column (${column.x},${column.z}) has a block entity at Y ${be.y}, outside its span ${column.minY}..${column.maxY}")
            }
            bes.add(PreparedEntry(be.x, be.y, be.z, canonicalNbt(be.nbt, "block entity at (${be.x},${be.y},${be.z})")))
        }
        bes.sortWith(compareBy<PreparedEntry>({ it.y }, { it.z }, { it.x }).thenComparator { a, b ->
            compareBytes(
                a.nbt,
                b.nbt
            )
        })
        for (i in 1 until bes.size) {
            val a = bes[i - 1]
            val b = bes[i]
            if (a.x == b.x && a.y == b.y && a.z == b.z) {
                throw InvalidContentException("column (${column.x},${column.z}) has two block entities at (${a.x},${a.y},${a.z})")
            }
        }
        val ents = ArrayList<PreparedEntity>(column.entities.size)
        for (e in column.entities) {
            val nbt = canonicalNbt(e.nbt, "entity ${e.uniqueId}")
            if (Records.uniqueId(nbt) != e.uniqueId) {
                throw InvalidContentException("entity compound does not carry UniqueID ${e.uniqueId} as a long")
            }
            ents.add(PreparedEntity(e.uniqueId, nbt))
        }
        ents.sortWith(compareBy<PreparedEntity> { it.id }.thenComparator { a, b -> compareBytes(a.nbt, b.nbt) })
        return PreparedCollections(bes, ents)
    }

    /** Runs [prepareCollections] across columns, in parallel when the batch pays for it. */
    fun prepareCollectionsAll(columns: List<Column>): List<PreparedCollections> {
        if (columns.size < 8) return columns.map(::prepareCollections)
        val out = arrayOfNulls<PreparedCollections>(columns.size)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        java.util.stream.IntStream.range(0, columns.size).parallel().forEach { i ->
            if (failure.get() != null) return@forEach
            try {
                out[i] = prepareCollections(columns[i])
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return (out as Array<PreparedCollections>).asList()
    }

    fun prepare(
        column: Column,
        world: World,
        blocks: BlockPaletteBuilder,
        biomes: BiomePaletteBuilder?,
        storeLight: Boolean,
        collections: PreparedCollections = prepareCollections(column)
    ): PreparedColumn {
        val states = world.blockStates
        fun toBuild(ref: Int): Int {
            if (ref < 0 || ref >= states.size) throw InvalidContentException("column (${column.x},${column.z}) references block state $ref, palette has ${states.size}")
            return blocks.id(states[ref])
        }

        val sections = arrayOfNulls<List<UsedStorage>>(column.sectionCount)
        for (i in column.sections.indices) {
            val section = column.sections[i] ?: continue
            var layers = section.layers.map { UsedStorage.of(it, ::toBuild) }
            while (layers.isNotEmpty() && layers.last().buildIds.all { blocks.state(it).isAir }) {
                layers = layers.subList(0, layers.size - 1)
            }
            if (layers.isEmpty()) continue
            for (layer in layers) layer.countInto { blocks.count(it) }
            sections[i] = layers
        }

        val biomeStorages: Array<UsedStorage>
        val biomeUniform = BooleanArray(column.sectionCount)
        if (biomes != null) {
            fun biomeBuild(ref: Int): Int {
                if (ref < 0 || ref >= world.biomes.size) throw InvalidContentException("column (${column.x},${column.z}) references biome $ref, palette has ${world.biomes.size}")
                return biomes.id(world.biomes[ref])
            }
            biomeStorages = Array(column.sectionCount) { i -> UsedStorage.of(column.biomes[i], ::biomeBuild) }
            for (i in biomeStorages.indices) {
                biomeUniform[i] = biomeStorages[i].isUniform
                biomeStorages[i].countInto { biomes.count(it, biomeUniform[i]) }
            }
        } else {
            biomeStorages = emptyArray()
        }

        val bes = collections.blockEntities
        val ents = collections.entities

        val ticks = ArrayList<PreparedTick>(column.scheduledUpdates.size)
        for (t in column.scheduledUpdates) {
            if (Math.floorDiv(t.x, 16) != column.x || Math.floorDiv(t.z, 16) != column.z) {
                throw InvalidContentException("column (${column.x},${column.z}) has a scheduled update at (${t.x},${t.y},${t.z}), outside its footprint")
            }
            if (t.y < column.minY || t.y > column.maxY) {
                throw InvalidContentException("column (${column.x},${column.z}) has a scheduled update at Y ${t.y}, outside its span ${column.minY}..${column.maxY}")
            }
            val build = toBuild(t.state)
            blocks.count(build)
            ticks.add(PreparedTick((t.x and 15) or ((t.z and 15) shl 4), t.y, build, t.tick))
        }

        if (bes.size > Limits.MAX_PER_CHUNK || ents.size > Limits.MAX_PER_CHUNK || ticks.size > Limits.MAX_PER_CHUNK) {
            throw InvalidContentException("column (${column.x},${column.z}) exceeds ${Limits.MAX_PER_CHUNK} entries in a collection")
        }
        checkBlobSize(column.userData, "column (${column.x},${column.z}) user data")

        return PreparedColumn(
            column.x, column.z, column.minSection, column.sectionCount,
            sections, biomeStorages, biomeUniform,
            if (storeLight) column.light else null,
            bes, ents, column.tick, ticks, column.userData,
        )
    }

    /** Encodes one record body (no position); [sink] turns blob bytes into a table reference or inlines them. */
    fun encode(
        w: ByteWriter,
        c: PreparedColumn,
        blockRemap: IntArray,
        biomeRemap: IntArray?,
        defaultRef: Int,
        storeLight: Boolean,
        sink: (ByteWriter, ByteArray) -> Unit,
    ) {
        w.svarint(c.minSection.toLong())
        w.uvarint(c.sectionCount)

        val presence = ByteArray((c.sectionCount + 7) / 8)
        for (i in c.sections.indices) if (c.sections[i] != null) presence[i / 8] =
            (presence[i / 8].toInt() or (1 shl (i % 8))).toByte()
        w.raw(presence)
        val precomputedBlocks = c.blockBlobs
        for (i in c.sections.indices) {
            val layers = c.sections[i] ?: continue
            w.uvarint(layers.size)
            val ready = precomputedBlocks?.get(i)
            for (l in layers.indices) sink(w, ready?.get(l) ?: layers[l].canonicalBlob(blockRemap))
        }

        val biomePresence = ByteArray((c.sectionCount + 7) / 8)
        if (biomeRemap != null) {
            for (i in c.biomes.indices) {
                val elided = defaultRef >= 0 && c.biomeUniform[i] && biomeRemap[c.biomes[i].buildIds[0]] == defaultRef
                if (!elided) biomePresence[i / 8] = (biomePresence[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        w.raw(biomePresence)
        if (biomeRemap != null) {
            val ready = c.biomeBlobs
            for (i in c.biomes.indices) {
                if (biomePresence[i / 8].toInt() and (1 shl (i % 8)) != 0) {
                    sink(w, ready?.get(i) ?: c.biomes[i].canonicalBlob(biomeRemap))
                }
            }
        }

        if (storeLight) {
            val light = c.light
            val lightPresence = ByteArray((c.sectionCount + 7) / 8)
            if (light != null) for (i in light.indices) if (light[i] != null) lightPresence[i / 8] =
                (lightPresence[i / 8].toInt() or (1 shl (i % 8))).toByte()
            w.raw(lightPresence)
            if (light != null) for (value in light) {
                if (value == null) continue
                var flags = 0
                if (value.blockLight != null) flags = flags or 1
                if (value.skyLight != null) flags = flags or 2
                w.u8(flags)
                value.blockLight?.let(w::raw)
                value.skyLight?.let(w::raw)
            }
        }

        w.uvarint(c.blockEntities.size)
        for (be in c.blockEntities) {
            w.u8((be.x and 15) or ((be.z and 15) shl 4))
            w.svarint(be.y.toLong())
            w.blob(be.nbt)
        }
        w.uvarint(c.entities.size)
        for (e in c.entities) w.blob(e.nbt)
        w.svarint(c.tick)

        val ticks = c.ticks.sortedWith(
            compareBy<PreparedTick>(
                { it.y },
                { it.packedXZ ushr 4 },
                { it.packedXZ and 15 },
                { it.at },
                { blockRemap[it.buildId] }),
        )
        for (i in 1 until ticks.size) {
            val a = ticks[i - 1]
            val b = ticks[i]
            if (a.y == b.y && a.packedXZ == b.packedXZ && a.at == b.at && blockRemap[a.buildId] == blockRemap[b.buildId]) {
                throw InvalidContentException("column (${c.x},${c.z}) has a duplicate scheduled update")
            }
        }
        w.uvarint(ticks.size)
        for (t in ticks) {
            w.u8(t.packedXZ)
            w.svarint(t.y.toLong())
            w.uvarint(blockRemap[t.buildId].toLong())
            w.svarint(t.at)
        }
        w.blob(c.userData)
    }

    /**
     * Re-encodes an NBT blob canonically. Runtime-supplied compounds arrive with keys in insertion
     * order, so the decode is lenient about ordering; every structural bound of §1 still applies,
     * and the bytes that reach the file are canonical.
     */
    fun canonicalNbt(blob: ByteArray, what: String): ByteArray {
        checkBlobSize(blob, "$what NBT")
        val out = try {
            Nbt.encode(Nbt.decodeLenient(blob))
        } catch (e: CorruptFileException) {
            throw InvalidContentException("$what: ${e.message}")
        }
        checkBlobSize(out, "$what NBT")
        return out
    }
}
