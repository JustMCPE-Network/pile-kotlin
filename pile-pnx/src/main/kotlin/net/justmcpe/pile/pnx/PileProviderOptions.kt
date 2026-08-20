package net.justmcpe.pile.pnx

import net.justmcpe.pile.format.Compression
import net.justmcpe.pile.format.nbt.NbtCompound

/**
 * Policies for direct provider opens; reflective PNX opens use defaults. The filters run when a
 * column is loaded: a column, entity or block entity they reject never reaches the level, and a
 * later save writes the world without it.
 */
public data class PileProviderOptions(
    public val readOnly: Boolean = false,
    public val appendMode: Boolean = false,
    public val compression: Compression = Compression.BEST,
    public val maxDecodedBytes: Long = 0,
    public val storeLight: Boolean = true,
    public val fastSaves: Boolean = false,
    public val skipEntities: Boolean = false,
    public val skipBlockEntities: Boolean = false,
    public val skipScheduledTicks: Boolean = false,
    public val skipBiomes: Boolean = false,
    public val skipUserData: Boolean = false,
    public val filterColumn: ((x: Int, z: Int) -> Boolean)? = null,
    public val filterEntity: ((NbtCompound) -> Boolean)? = null,
    public val filterBlockEntity: ((NbtCompound) -> Boolean)? = null,
) {
    init {
        require(maxDecodedBytes >= 0) { "maxDecodedBytes must be non-negative" }
    }
}
