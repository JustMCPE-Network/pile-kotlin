package net.justmcpe.pile.format.wire

import net.justmcpe.pile.format.*

internal object StructureEncoder {
    fun encode(structure: Structure, compression: Compression): ByteArray {
        if (structure.blockVersion == 0) throw InvalidContentException("blockVersion is zero")
        for (axis in intArrayOf(structure.sizeX, structure.sizeY, structure.sizeZ)) {
            if (axis < 1 || axis > Limits.MAX_STRUCTURE_AXIS) throw InvalidContentException("invalid structure size $axis")
        }
        val nx = (structure.sizeX + 15L) / 16
        val ny = (structure.sizeY + 15L) / 16
        val nz = (structure.sizeZ + 15L) / 16
        val cellCount = nx * ny * nz
        if (cellCount > Limits.MAX_STRUCTURE_CELLS) {
            throw InvalidContentException("structure needs $cellCount cells, limit ${Limits.MAX_STRUCTURE_CELLS}")
        }
        if (structure.cells.size.toLong() != cellCount) {
            throw InvalidContentException("cell count ${structure.cells.size} does not match size (${structure.sizeX},${structure.sizeY},${structure.sizeZ})")
        }
        if (structure.blockEntities.size > Limits.MAX_PER_CHUNK || structure.entities.size > Limits.MAX_PER_CHUNK) {
            throw InvalidContentException("structure exceeds ${Limits.MAX_PER_CHUNK} entries in a collection")
        }
        checkBlobSize(structure.userData, "structure user data")

        val states = structure.blockStates
        // Padding positions reference air through a virtual id one past the palette, so a
        // structure whose palette lacks air still clears its edge cells.
        val airRef = states.indexOfFirst { it.isAir }.let { if (it >= 0) it else states.size }
        val blocks = BlockPaletteBuilder(structure.blockVersion)
        fun toBuild(ref: Int): Int = when {
            ref == airRef && ref == states.size -> blocks.id(BlockState.air(structure.blockVersion))
            ref < 0 || ref >= states.size -> throw InvalidContentException("structure references block state $ref, palette has ${states.size}")
            else -> blocks.id(states[ref])
        }

        val cells = arrayOfNulls<List<UsedStorage>>(structure.cells.size)
        for (i in structure.cells.indices) {
            val cell = structure.cells[i] ?: continue
            val cy = (i % ny).toInt()
            val cz = ((i / ny) % nz).toInt()
            val cx = (i / (ny * nz)).toInt()
            val limX = minOf(16, structure.sizeX - cx * 16)
            val limY = minOf(16, structure.sizeY - cy * 16)
            val limZ = minOf(16, structure.sizeZ - cz * 16)
            val padded = limX < 16 || limY < 16 || limZ < 16
            var layers = cell.layers.map { storage ->
                val cleared = if (!padded) storage else clearPadding(storage, limX, limY, limZ, airRef)
                UsedStorage.of(cleared, ::toBuild)
            }
            while (layers.isNotEmpty() && layers.last().buildIds.all { blocks.state(it).isAir }) {
                layers = layers.subList(0, layers.size - 1)
            }
            if (layers.isEmpty()) continue
            for (layer in layers) layer.countInto { blocks.count(it) }
            cells[i] = layers
        }

        val bes = structure.blockEntities.map { be ->
            if (be.x !in 0 until structure.sizeX || be.y !in 0 until structure.sizeY || be.z !in 0 until structure.sizeZ) {
                throw InvalidContentException("structure block entity at (${be.x},${be.y},${be.z}) is outside the box")
            }
            PreparedEntry(
                be.x,
                be.y,
                be.z,
                RecordEncoder.canonicalNbt(be.nbt, "structure block entity at (${be.x},${be.y},${be.z})")
            )
        }.sortedWith(compareBy<PreparedEntry>(
            { it.y },
            { it.z },
            { it.x }).thenComparator { a, b -> compareBytes(a.nbt, b.nbt) })
        for (i in 1 until bes.size) {
            val a = bes[i - 1]
            val b = bes[i]
            if (a.x == b.x && a.y == b.y && a.z == b.z) {
                throw InvalidContentException("structure has two block entities at (${a.x},${a.y},${a.z})")
            }
        }
        val ents = structure.entities
            .map { RecordEncoder.canonicalNbt(it, "structure entity") }
            .sortedWith { a, b -> compareBytes(a, b) }

        val palette = ByteWriter()
        val remap = blocks.finalize(palette)
        val table = BlobTable()
        val record = ByteWriter()
        record.uvarint(structure.sizeX)
        record.uvarint(structure.sizeY)
        record.uvarint(structure.sizeZ)
        record.svarint(structure.originX.toLong())
        record.svarint(structure.originY.toLong())
        record.svarint(structure.originZ.toLong())
        val presence = ByteArray(((cellCount + 7) / 8).toInt())
        for (i in cells.indices) if (cells[i] != null) presence[i / 8] =
            (presence[i / 8].toInt() or (1 shl (i % 8))).toByte()
        record.raw(presence)
        for (layers in cells) {
            if (layers == null) continue
            record.uvarint(layers.size)
            for (layer in layers) record.uvarint(table.add(layer.canonicalBlob(remap)).toLong())
        }
        record.uvarint(bes.size)
        for (be in bes) {
            record.uvarint(be.x)
            record.uvarint(be.y)
            record.uvarint(be.z)
            record.blob(be.nbt)
        }
        record.uvarint(ents.size)
        for (e in ents) record.blob(e)

        val body = ByteWriter()
        body.blob(ByteArray(0))
        body.blob(structure.userData)
        body.raw(palette.toByteArray())
        body.uvarint(0)
        table.encode(body)
        body.raw(record.array(), 0, record.size)
        if (body.size > Limits.MAX_BODY) throw InvalidContentException("structure body is ${body.size} bytes, limit ${Limits.MAX_BODY}")

        var flags = 0
        val stored: ByteArray
        if (compression == Compression.NONE) {
            flags = Flags.UNCOMPRESSED
            stored = body.toByteArray()
        } else {
            stored = ZstdCodec.compress(body.toByteArray(), compression)
        }
        return Frame.assemble(FileKind.STRUCTURE, flags, structure.blockVersion, stored)
    }

    private fun clearPadding(storage: Storage, limX: Int, limY: Int, limZ: Int, airRef: Int): Storage {
        val refs = IntArray(Limits.STORAGE_SIZE)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in 0 until 16) {
                    val i = Storage.index(x, y, z)
                    refs[i] = if (x < limX && y < limY && z < limZ) storage[i] else airRef
                }
            }
        }
        return Storage.of(refs)
    }
}
