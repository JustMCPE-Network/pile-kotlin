package net.justmcpe.pile.pnx.convert

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import org.powernukkitx.nbt.tag.CompoundTag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Little-endian NBT bytes, as the format stores them, to and from PNX compounds. */
internal object PnxNbt {
    fun read(blob: ByteArray): CompoundTag {
        NbtUtils.createReaderLE(ByteArrayInputStream(blob)).use { input ->
            return CompoundTag.fromNetwork(input.readTag() as NbtMap)
        }
    }

    fun readMap(blob: ByteArray): NbtMap {
        NbtUtils.createReaderLE(ByteArrayInputStream(blob)).use { input -> return input.readTag() as NbtMap }
    }

    fun write(tag: CompoundTag): ByteArray = write(tag.toNetwork())

    fun write(map: NbtMap): ByteArray {
        val out = ByteArrayOutputStream()
        NbtUtils.createWriterLE(out).use { it.writeTag(map) }
        return out.toByteArray()
    }
}
