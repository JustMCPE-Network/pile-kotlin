package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*
import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtLong

/** Resolves blob references in first-use order (format.md §3.4). */
internal open class BlobSource(
    private val blobs: List<RawBlob>,
    private val used: BooleanArray,
    private val next: IntArray
) {
    open fun next(r: ByteReader): RawBlob {
        val ref = r.uvarint()
        if (ref < 0 || ref >= blobs.size) corrupt("section blob reference $ref out of range")
        val id = ref.toInt()
        if (id > next[0]) corrupt("blob $id referenced before ${next[0]}: ids are assigned in first-use order")
        if (id == next[0]) next[0]++
        used[id] = true
        return blobs[id]
    }
}

/** Indexed records carry section blobs inline instead of referencing a table. */
internal class InlineBlobSource : BlobSource(emptyList(), BooleanArray(0), IntArray(0)) {
    override fun next(r: ByteReader): RawBlob = Blobs.readOne(r)
}

internal class RawRecord(
    val x: Int,
    val z: Int,
    val minSection: Int,
    val sectionN: Int,
    val presence: ByteArray,
    val biomePresence: ByteArray,
    val lightPresence: ByteArray?,
    val layers: List<List<RawBlob>>,
    val biomes: List<RawBlob>,
    val light: List<LightData>,
    val blockEntities: List<RawBlockEntity>,
    val entities: List<ByteArray>,
    val tick: Long,
    val ticks: List<RawTick>,
    val userData: ByteArray,
)

internal class RawBlockEntity(val packedXZ: Int, val y: Long, val nbt: ByteArray)
internal class RawTick(val packedXZ: Int, val y: Long, val ref: Long, val at: Long)

internal object Records {
    /** Runs [apply] over parsed records, in parallel when the batch is large enough to pay for it. */
    fun applyAll(
        records: List<RawRecord>,
        states: List<BlockState>,
        biomeCount: Int,
        defaultRef: Int,
        haveDefault: Boolean
    ): List<Column> {
        if (records.size < 8) return records.map { apply(it, states, biomeCount, defaultRef, haveDefault) }
        val out = arrayOfNulls<Column>(records.size)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        java.util.stream.IntStream.range(0, records.size).parallel().forEach { i ->
            if (failure.get() != null) return@forEach
            try {
                out[i] = apply(records[i], states, biomeCount, defaultRef, haveDefault)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return (out as Array<Column>).asList()
    }

    fun parse(r: ByteReader, src: BlobSource, haveLight: Boolean, x: Int, z: Int, budget: DecodeBudget): RawRecord {
        val minSection = r.svarint()
        val sectionN = r.count(Limits.MAX_SECTIONS, "section")
        if (sectionN == 0) corrupt("chunk ($x,$z) has no sections")
        if (minSection < Limits.MIN_SECTION_INDEX || minSection > Limits.MAX_SECTION_INDEX ||
            minSection + sectionN > Limits.MAX_SECTION_INDEX + 1
        ) {
            corrupt(
                "chunk ($x,$z) spans sections $minSection..${minSection + sectionN - 1}, outside the addressable range " +
                        "${Limits.MIN_SECTION_INDEX}..${Limits.MAX_SECTION_INDEX}",
            )
        }
        val presence = r.bitset(sectionN)
        val layers = ArrayList<List<RawBlob>>()
        for (i in 0 until sectionN) {
            if (!presence.bit(i)) continue
            val layerN = r.count(Limits.MAX_LAYERS, "layer")
            if (layerN == 0) corrupt("section $i is present but declares no layers")
            budget.chargeStorages(layerN)
            layers.add(List(layerN) { src.next(r) })
        }
        val biomePresence = r.bitset(sectionN)
        val biomes = ArrayList<RawBlob>()
        for (i in 0 until sectionN) {
            if (biomePresence.bit(i)) biomes.add(src.next(r))
        }
        var lightPresence: ByteArray? = null
        val light = ArrayList<LightData>()
        if (haveLight) {
            lightPresence = r.bitset(sectionN)
            for (i in 0 until sectionN) {
                if (!lightPresence.bit(i)) continue
                val flags = r.u8()
                if (flags and 0x03.inv() != 0) corrupt("light flags 0x%02x set reserved bits".format(flags))
                if (flags == 0) corrupt("light entry for section $i carries no arrays")
                val block = if (flags and 1 != 0) r.bytes(Limits.LIGHT_ARRAY_SIZE) else null
                val sky = if (flags and 2 != 0) r.bytes(Limits.LIGHT_ARRAY_SIZE) else null
                light.add(LightData(block, sky))
            }
        }
        val beN = r.count(Limits.MAX_PER_CHUNK, "block entity")
        budget.chargeEntries(beN)
        val bes = ArrayList<RawBlockEntity>(minOf(beN, Limits.MAX_PREALLOC))
        repeat(beN) {
            val packed = r.u8()
            val y = r.svarint()
            bes.add(RawBlockEntity(packed, y, r.blob()))
        }
        val entN = r.count(Limits.MAX_PER_CHUNK, "entity")
        budget.chargeEntries(entN)
        val ents = ArrayList<ByteArray>(minOf(entN, Limits.MAX_PREALLOC))
        repeat(entN) { ents.add(r.blob()) }
        val tick = r.svarint()
        val stN = r.count(Limits.MAX_PER_CHUNK, "scheduled tick")
        budget.chargeEntries(stN)
        val ticks = ArrayList<RawTick>(minOf(stN, Limits.MAX_PREALLOC))
        repeat(stN) {
            val packed = r.u8()
            val y = r.svarint()
            val ref = r.uvarint()
            val at = r.svarint()
            ticks.add(RawTick(packed, y, ref, at))
        }
        val userData = r.blob()
        return RawRecord(
            x,
            z,
            minSection.toInt(),
            sectionN,
            presence,
            biomePresence,
            lightPresence,
            layers,
            biomes,
            light,
            bes,
            ents,
            tick,
            ticks,
            userData
        )
    }

    fun apply(
        rec: RawRecord,
        states: List<BlockState>,
        biomeCount: Int,
        defaultRef: Int,
        haveDefault: Boolean
    ): Column {
        val x = rec.x
        val z = rec.z
        val sections = arrayOfNulls<Section>(rec.sectionN)
        var cur = 0
        for (i in 0 until rec.sectionN) {
            if (!rec.presence.bit(i)) continue
            val layers = rec.layers[cur++]
            val last = layers[layers.size - 1]
            if (isUniformAir(last, states)) {
                corrupt("section ${rec.minSection + i} ends in an all-air layer, so it is either absent or shorter")
            }
            sections[i] = Section(layers.map { blob -> blockStorage(blob, states.size) })
        }
        val biomes = arrayOfNulls<Storage>(rec.sectionN)
        cur = 0
        val uniformDefault = Storage.uniform(defaultRef)
        for (i in 0 until rec.sectionN) {
            if (!rec.biomePresence.bit(i)) {
                biomes[i] = uniformDefault
                continue
            }
            val blob = rec.biomes[cur++]
            if (haveDefault && blob.isUniform && blob.refs[0] == defaultRef) {
                corrupt("section ${rec.minSection + i} stores biomes uniformly the file's default, which must be omitted")
            }
            for (ref in blob.refs) if (ref >= biomeCount) corrupt("biome palette reference $ref out of range")
            biomes[i] = blob.storage()
        }
        var light: Array<LightData?>? = null
        if (rec.lightPresence != null) {
            light = arrayOfNulls(rec.sectionN)
            cur = 0
            for (i in 0 until rec.sectionN) if (rec.lightPresence.bit(i)) light[i] = rec.light[cur++]
        }

        val loY = rec.minSection.toLong() * 16
        val hiY = (rec.minSection.toLong() + rec.sectionN) * 16 - 1
        val bes = ArrayList<BlockEntity>(rec.blockEntities.size)
        var prevBE: RawBlockEntity? = null
        for (be in rec.blockEntities) {
            if (be.y < loY || be.y > hiY) corrupt("block entity at Y ${be.y} is outside the chunk's span $loY..$hiY")
            if (prevBE != null && !ascends(prevBE.packedXZ, prevBE.y, be.packedXZ, be.y)) {
                corrupt("block entities are out of order or repeat a position in chunk ($x,$z)")
            }
            prevBE = be
            Nbt.validate(be.nbt)
            bes.add(BlockEntity(x * 16 + (be.packedXZ and 0xF), be.y.toInt(), z * 16 + (be.packedXZ ushr 4), be.nbt))
        }
        val ents = ArrayList<Entity>(rec.entities.size)
        for ((i, blob) in rec.entities.withIndex()) {
            Nbt.validate(blob)
            val id = uniqueId(blob)
            if (id != null) {
                ents.add(Entity(id, blob))
            } else {
                val synthetic = syntheticEntityId(x, z, i)
                ents.add(Entity(synthetic, Nbt.encode(Nbt.decode(blob).with("UniqueID", NbtLong(synthetic)))))
            }
        }
        val ticks = ArrayList<ScheduledUpdate>(rec.ticks.size)
        var prevTick: RawTick? = null
        for (t in rec.ticks) {
            if (t.ref < 0 || t.ref >= states.size) corrupt("scheduled tick block reference ${t.ref} out of range")
            if (t.y < loY || t.y > hiY) corrupt("scheduled update at Y ${t.y} is outside the chunk's span $loY..$hiY")
            if (prevTick != null && !tickAscends(prevTick, t)) {
                corrupt("scheduled updates are out of order or repeat a key in chunk ($x,$z)")
            }
            prevTick = t
            ticks.add(
                ScheduledUpdate(
                    x * 16 + (t.packedXZ and 0xF),
                    t.y.toInt(),
                    z * 16 + (t.packedXZ ushr 4),
                    t.ref.toInt(),
                    t.at
                )
            )
        }
        @Suppress("UNCHECKED_CAST")
        return Column(
            x,
            z,
            rec.minSection,
            sections,
            biomes as Array<Storage>,
            light,
            bes,
            ents,
            rec.tick,
            ticks,
            rec.userData
        )
    }

    private fun blockStorage(blob: RawBlob, stateCount: Int): Storage {
        for (ref in blob.refs) if (ref >= stateCount) corrupt("block palette reference $ref out of range")
        return blob.storage()
    }

    private fun isUniformAir(b: RawBlob, states: List<BlockState>): Boolean {
        if (b.refs.size != 1) return false
        val ref = b.refs[0]
        return ref < states.size && states[ref].isAir
    }

    private fun ascends(aXZ: Int, aY: Long, bXZ: Int, bY: Long): Boolean {
        if (aY != bY) return aY < bY
        val az = aXZ ushr 4
        val bz = bXZ ushr 4
        if (az != bz) return az < bz
        return (aXZ and 0xF) < (bXZ and 0xF)
    }

    private fun tickAscends(a: RawTick, b: RawTick): Boolean {
        if (a.y != b.y) return a.y < b.y
        val az = a.packedXZ ushr 4
        val bz = b.packedXZ ushr 4
        if (az != bz) return az < bz
        val ax = a.packedXZ and 0xF
        val bx = b.packedXZ and 0xF
        if (ax != bx) return ax < bx
        if (a.at != b.at) return a.at < b.at
        return java.lang.Long.compareUnsigned(a.ref, b.ref) < 0
    }

    /** The top-level `UniqueID` long of a validated compound, or null when absent or mistyped. */
    internal fun uniqueId(blob: ByteArray): Long? {
        val r = ByteReader(blob, 3)
        while (true) {
            val t = r.u8()
            if (t == 0) return null
            val n = r.u16()
            val start = r.take(n)
            val isKey = n == 8 && String(blob, start, n, Charsets.UTF_8) == "UniqueID"
            if (isKey) return if (t == 4) r.u64() else null
            skipPayload(r, t)
        }
    }

    private fun skipPayload(r: ByteReader, t: Int) {
        when (t) {
            1 -> r.take(1)
            2 -> r.take(2)
            3, 5 -> r.take(4)
            4, 6 -> r.take(8)
            8 -> r.take(r.u16())
            7 -> r.take(r.i32())
            11 -> r.take(r.i32() * 4)
            12 -> r.take(r.i32() * 8)
            9 -> {
                val et = r.u8()
                val n = r.i32()
                repeat(n) { skipPayload(r, et) }
            }

            10 -> while (true) {
                val ct = r.u8()
                if (ct == 0) break
                r.take(r.u16())
                skipPayload(r, ct)
            }

            else -> corrupt("nbt: unknown tag type $t")
        }
    }

    private fun syntheticEntityId(x: Int, z: Int, index: Int): Long {
        val b = ByteWriter(16)
        b.i32(x)
        b.i32(z)
        b.u64(index.toLong())
        var id = XxHash.hash(b.toByteArray()) and Long.MAX_VALUE
        if (id == 0L) id = 1
        return id
    }
}
