package net.justmcpe.pile.format

import net.justmcpe.pile.format.wire.ByteWriter
import net.justmcpe.pile.format.wire.Utf8
import java.util.*

/** A Bedrock block state property value: exactly the three NBT types Bedrock uses (format.md §3.1). */
public sealed interface PropertyValue {
    public data class ByteValue(val value: Int) : PropertyValue {
        init {
            require(value in 0..255) { "byte property out of range: $value" }
        }
    }

    public data class IntValue(val value: Int) : PropertyValue
    public data class StringValue(val value: String) : PropertyValue

    public companion object {
        public fun of(value: Boolean): PropertyValue = ByteValue(if (value) 1 else 0)
        public fun of(value: Byte): PropertyValue = ByteValue(value.toInt() and 0xFF)
        public fun of(value: Int): PropertyValue = IntValue(value)
        public fun of(value: String): PropertyValue = StringValue(value)
    }
}

/**
 * A block state as a palette stores it: name, properties and the block-state version it is expressed at.
 * [version] is always the effective version (format.md §3.1): the file's own unless the entry carried an
 * override. Two states are equal when all three agree, which is the palette's own identity.
 */
public class BlockState(name: String, properties: Map<String, PropertyValue>, public val version: Int) {
    public val name: String = name
    public val properties: Map<String, PropertyValue> =
        java.util.Collections.unmodifiableMap(TreeMap<String, PropertyValue>(Utf8.order).also { it.putAll(properties) })

    public val isAir: Boolean get() = name == "minecraft:air" && properties.isEmpty()

    public fun withVersion(version: Int): BlockState =
        if (version == this.version) this else BlockState(name, properties, version)

    /** The entry's wire bytes without its override: the length-prefixed name and the property block. This is what the palette order compares. */
    public fun encodedEntry(): ByteArray {
        val w = ByteWriter(32)
        encodeEntry(w)
        return w.toByteArray()
    }

    internal fun encodeEntry(w: ByteWriter) {
        w.string(name)
        w.uvarint(properties.size)
        for ((k, v) in properties) {
            w.string(k)
            when (v) {
                is PropertyValue.ByteValue -> {
                    w.u8(0); w.u8(v.value)
                }

                is PropertyValue.IntValue -> {
                    w.u8(1); w.i32(v.value)
                }

                is PropertyValue.StringValue -> {
                    w.u8(2); w.string(v.value)
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BlockState && other.name == name && other.version == version && other.properties == properties

    override fun hashCode(): Int = (name.hashCode() * 31 + properties.hashCode()) * 31 + version

    override fun toString(): String = buildString {
        append(name)
        if (properties.isNotEmpty()) {
            append('[')
            properties.entries.joinTo(this, ",") { (k, v) ->
                "$k=" + when (v) {
                    is PropertyValue.ByteValue -> v.value.toString()
                    is PropertyValue.IntValue -> v.value.toString()
                    is PropertyValue.StringValue -> v.value
                }
            }
            append(']')
        }
        append('@').append(version)
    }

    public companion object {
        public fun air(version: Int): BlockState = BlockState("minecraft:air", emptyMap(), version)
    }
}
