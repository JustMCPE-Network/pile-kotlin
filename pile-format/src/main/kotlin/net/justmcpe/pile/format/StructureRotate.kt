package net.justmcpe.pile.format

import net.justmcpe.pile.format.nbt.Nbt
import net.justmcpe.pile.format.nbt.NbtFloat
import net.justmcpe.pile.format.nbt.NbtList
import net.justmcpe.pile.format.nbt.NbtType

/**
 * A copy of the structure rotated clockwise, viewed from above, by `quarters` 90-degree turns
 * around Y. Block, block-entity and entity positions rotate exactly; the paste anchor resets to
 * zero, as the reference implementation's `Structure.Rotate` does.
 *
 * State rotation is best effort and needs no registry: the common Bedrock direction properties
 * (`facing_direction`, `direction`, `weirdo_direction`, `pillar_axis`, `ground_sign_direction`,
 * `minecraft:cardinal_direction` and string facing properties) rotate, which covers stairs, logs,
 * signs, doors, furnaces and chests. Exotic properties keep their value.
 */
public fun Structure.rotate(quarters: Int): Structure {
    var out = this
    repeat(((quarters % 4) + 4) % 4) { out = out.rotateOnce() }
    return out
}

private fun Structure.rotateOnce(): Structure {
    val states = ArrayList(blockStates.map(::rotateState))
    var airRef = states.indexOfFirst { it.isAir }
    if (airRef < 0) {
        states.add(BlockState.air(blockVersion))
        airRef = states.size - 1
    }

    val nx = (sizeZ + 15) / 16
    val ny = (sizeY + 15) / 16
    val nz = (sizeX + 15) / 16
    val sourceNy = (sizeY + 15) / 16
    val sourceNz = (sizeZ + 15) / 16
    val cells = arrayOfNulls<Section>(nx * ny * nz)
    var layerCount = 0
    for (cell in this.cells) if (cell != null) layerCount = maxOf(layerCount, cell.layers.size)

    fun sourceRef(x: Int, y: Int, z: Int, layer: Int): Int {
        val cell = this.cells[((x shr 4) * sourceNz + (z shr 4)) * sourceNy + (y shr 4)] ?: return airRef
        if (layer >= cell.layers.size) return airRef
        return cell.layers[layer].get(x and 15, y and 15, z and 15)
    }

    for (cx in 0 until nx) {
        for (cy in 0 until ny) {
            for (cz in 0 until nz) {
                val layers = ArrayList<Storage>(layerCount)
                var any = false
                for (layer in 0 until layerCount) {
                    val refs = IntArray(Limits.STORAGE_SIZE) { airRef }
                    var layerAny = false
                    for (lx in 0 until 16) {
                        val tx = cx * 16 + lx
                        if (tx >= sizeZ) break
                        for (lz in 0 until 16) {
                            val tz = cz * 16 + lz
                            if (tz >= sizeX) break
                            // Clockwise from above maps source (x, z) to (sizeZ-1-z, x), so the
                            // source of target (tx, tz) is (tz, sizeZ-1-tx).
                            val sx = tz
                            val sz = sizeZ - 1 - tx
                            for (ly in 0 until 16) {
                                val ty = cy * 16 + ly
                                if (ty >= sizeY) break
                                val ref = sourceRef(sx, ty, sz, layer)
                                if (ref != airRef && !states[ref].isAir) {
                                    refs[Storage.index(lx, ly, lz)] = ref
                                    layerAny = true
                                }
                            }
                        }
                    }
                    layers.add(Storage.of(refs))
                    if (layerAny) any = true
                }
                if (any) cells[(cx * nz + cz) * ny + cy] = Section(layers)
            }
        }
    }

    val bes = blockEntities.map { be ->
        BlockEntity(sizeZ - 1 - be.z, be.y, be.x, be.nbt)
    }
    val ents = entities.map(::rotateEntity)
    return Structure(blockVersion, userData.copyOf(), states, sizeZ, sizeY, sizeX, 0, 0, 0, cells, bes, ents)
}

private fun Structure.rotateEntity(nbt: ByteArray): ByteArray {
    val data = Nbt.decodeLenient(nbt)
    var out = data
    val pos = data.getList("Pos")
    if (pos != null && pos.elementType == NbtType.FLOAT && pos.size == 3) {
        val x = (pos[0] as NbtFloat).value
        val y = (pos[1] as NbtFloat).value
        val z = (pos[2] as NbtFloat).value
        out = out.with("Pos", NbtList(NbtType.FLOAT, listOf(NbtFloat(sizeZ - z), NbtFloat(y), NbtFloat(x))))
    }
    (out["Yaw"] as? NbtFloat)?.let {
        // The reference leaves the raw sum in place; normalising to [0, 360) is the same angle
        // and keeps four quarter turns byte-identical.
        var yaw = (it.value + 90f) % 360f
        if (yaw < 0f) yaw += 360f
        out = out.with("Yaw", NbtFloat(yaw))
    }
    return Nbt.encode(out)
}

private val cardinalClockwise = mapOf("north" to "east", "east" to "south", "south" to "west", "west" to "north")

private fun rotateState(state: BlockState): BlockState {
    if (state.properties.isEmpty()) return state
    var changed = false
    val out = LinkedHashMap<String, PropertyValue>(state.properties.size)
    for ((k, v) in state.properties) {
        out[k] = v
        when (k) {
            "minecraft:cardinal_direction", "minecraft:block_face", "facing", "minecraft:facing_direction" -> {
                val sv = (v as? PropertyValue.StringValue)?.value ?: continue
                cardinalClockwise[sv]?.let {
                    out[k] = PropertyValue.StringValue(it)
                    changed = true
                }
            }

            "facing_direction" -> {
                // 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
                val iv = (v as? PropertyValue.IntValue)?.value ?: (v as? PropertyValue.ByteValue)?.value ?: continue
                mapOf(2 to 5, 5 to 3, 3 to 4, 4 to 2)[iv]?.let {
                    out[k] = PropertyValue.IntValue(it)
                    changed = true
                }
            }

            "direction" -> {
                // 0 south, 1 west, 2 north, 3 east: a clockwise ring.
                val iv = (v as? PropertyValue.IntValue)?.value ?: (v as? PropertyValue.ByteValue)?.value ?: continue
                if (iv in 0..3) {
                    out[k] = PropertyValue.IntValue((iv + 1) % 4)
                    changed = true
                }
            }

            "weirdo_direction" -> {
                // Stairs: 0 east, 1 west, 2 south, 3 north.
                val iv = (v as? PropertyValue.IntValue)?.value ?: (v as? PropertyValue.ByteValue)?.value ?: continue
                mapOf(0 to 2, 2 to 1, 1 to 3, 3 to 0)[iv]?.let {
                    out[k] = PropertyValue.IntValue(it)
                    changed = true
                }
            }

            "ground_sign_direction" -> {
                val iv = (v as? PropertyValue.IntValue)?.value ?: (v as? PropertyValue.ByteValue)?.value ?: continue
                if (iv in 0..15) {
                    out[k] = PropertyValue.IntValue((iv + 4) % 16)
                    changed = true
                }
            }

            "pillar_axis" -> {
                when ((v as? PropertyValue.StringValue)?.value) {
                    "x" -> {
                        out[k] = PropertyValue.StringValue("z")
                        changed = true
                    }

                    "z" -> {
                        out[k] = PropertyValue.StringValue("x")
                        changed = true
                    }
                }
            }
        }
    }
    return if (changed) BlockState(state.name, out, state.version) else state
}
