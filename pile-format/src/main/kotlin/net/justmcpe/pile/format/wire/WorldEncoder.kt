package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*
import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtCompound

internal object WorldEncoder {
    fun encode(world: World, options: WriteOptions): ByteArray {
        if (world.blockVersion == 0) throw InvalidContentException("blockVersion is zero")
        if (world.columns.size > Limits.MAX_CHUNKS) {
            throw InvalidContentException("${world.columns.size} chunks exceeds limit ${Limits.MAX_CHUNKS}")
        }
        checkMeta(world)

        val columns = world.columns.sortedWith { a, b -> Morton.compare(Morton.key(a.x, a.z), Morton.key(b.x, b.z)) }
        for (i in 1 until columns.size) {
            if (columns[i - 1].x == columns[i].x && columns[i - 1].z == columns[i].z) {
                throw InvalidContentException("duplicate chunk (${columns[i].x},${columns[i].z})")
            }
        }

        val blocks = BlockPaletteBuilder(world.blockVersion)
        val biomes = if (options.skipBiomes) null else BiomePaletteBuilder()
        val collections = RecordEncoder.prepareCollectionsAll(columns)
        val prepared = columns.mapIndexed { i, c ->
            RecordEncoder.prepare(
                c,
                world,
                blocks,
                biomes,
                options.storeLight,
                collections[i]
            )
        }
        var storages = 0L
        for (c in prepared) for (layers in c.sections) storages += layers?.size ?: 0
        if (storages > Limits.MAX_STORAGES) {
            throw InvalidContentException("world holds $storages section storages, limit ${Limits.MAX_STORAGES}")
        }
        val storeLight = options.storeLight && prepared.any { c -> c.light?.any { it != null } == true }

        val blockPalette = ByteWriter()
        val blockRemap = blocks.finalize(blockPalette)
        val biomePalette = ByteWriter()
        val biomeRemap = biomes?.finalize(biomePalette)
        if (biomes == null) biomePalette.uvarint(0)
        val defaultRef = biomes?.electDefault(biomeRemap!!) ?: -1

        if (prepared.size >= 8) {
            java.util.stream.IntStream.range(0, prepared.size).parallel().forEach { i ->
                prepared[i].precomputeBlobs(blockRemap, biomeRemap, defaultRef)
            }
        }

        val table = BlobTable()
        val records = ByteWriter(64 shl 10)
        records.uvarint(prepared.size)
        var prevX = 0
        var prevZ = 0
        for (c in prepared) {
            records.svarint(c.x.toLong() - prevX)
            records.svarint(c.z.toLong() - prevZ)
            RecordEncoder.encode(records, c, blockRemap, biomeRemap, defaultRef, storeLight) { w, blob ->
                w.uvarint(table.add(blob).toLong())
            }
            prevX = c.x
            prevZ = c.z
        }

        val body = ByteWriter(128 shl 10)
        body.blob(world.settings)
        body.blob(world.userData)
        if (options.stats) {
            var filled = 0L
            for (c in prepared) for (layers in c.sections) if (layers != null) filled++
            body.blob(
                Nbt.encode(
                    NbtCompound.build {
                        put("chunks", prepared.size.toLong())
                        put("filledSections", filled)
                        put("uniqueBlobs", table.size.toLong())
                        put("blockStates", blockRemap.size.toLong())
                        put("biomes", (biomeRemap?.size ?: 0).toLong())
                    },
                ),
            )
        }
        body.raw(blockPalette.toByteArray())
        body.raw(biomePalette.toByteArray())
        table.encode(body)
        body.raw(records.array(), 0, records.size)
        if (body.size > Limits.MAX_BODY) throw InvalidContentException("body is ${body.size} bytes, limit ${Limits.MAX_BODY}")

        var flags = 0
        if (storeLight) flags = flags or Flags.STORE_LIGHT
        if (options.stats) flags = flags or Flags.STATS
        if (defaultRef >= 0) flags = flags or Flags.DEFAULT_BIOME or (defaultRef shl Flags.DEFAULT_BIOME_SHIFT)
        val stored: ByteArray
        if (options.compression == Compression.NONE) {
            flags = flags or Flags.UNCOMPRESSED
            stored = body.toByteArray()
        } else {
            stored = ZstdCodec.compress(body.toByteArray(), options.compression, fast = options.fastCompression)
        }
        return Frame.assemble(FileKind.WORLD, flags, world.blockVersion, stored)
    }

    fun checkMeta(world: World) {
        checkBlobSize(world.settings, "world settings blob")
        checkBlobSize(world.userData, "world user data")
        if (world.settings.isNotEmpty()) {
            try {
                Nbt.validate(world.settings)
                Schema.checkSettings(world.settings)
            } catch (e: CorruptFileException) {
                throw InvalidContentException("world settings blob: ${e.message}")
            }
        }
    }
}
