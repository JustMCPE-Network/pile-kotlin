package net.justmcpe.pile.format.nbt

import net.justmcpe.pile.format.wire.Utf8
import java.util.*

/** A little-endian Bedrock NBT value. Array tags and lists are distinct types, as the format requires. */
public sealed interface NbtTag {
    public val type: NbtType
}

public enum class NbtType(public val id: Int) {
    END(0), BYTE(1), SHORT(2), INT(3), LONG(4), FLOAT(5), DOUBLE(6),
    BYTE_ARRAY(7), STRING(8), LIST(9), COMPOUND(10), INT_ARRAY(11), LONG_ARRAY(12);

    public companion object {
        private val byId = entries.associateBy { it.id }
        public fun of(id: Int): NbtType? = byId[id]
    }
}

public data class NbtByte(val value: Byte) : NbtTag {
    override val type: NbtType get() = NbtType.BYTE
}

public data class NbtShort(val value: Short) : NbtTag {
    override val type: NbtType get() = NbtType.SHORT
}

public data class NbtInt(val value: Int) : NbtTag {
    override val type: NbtType get() = NbtType.INT
}

public data class NbtLong(val value: Long) : NbtTag {
    override val type: NbtType get() = NbtType.LONG
}

public data class NbtFloat(val value: Float) : NbtTag {
    override val type: NbtType get() = NbtType.FLOAT
}

public data class NbtDouble(val value: Double) : NbtTag {
    override val type: NbtType get() = NbtType.DOUBLE
}

public data class NbtString(val value: String) : NbtTag {
    override val type: NbtType get() = NbtType.STRING
}

public class NbtByteArray(public val value: ByteArray) : NbtTag {
    override val type: NbtType get() = NbtType.BYTE_ARRAY
    override fun equals(other: Any?): Boolean = other is NbtByteArray && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "NbtByteArray(${value.size} bytes)"
}

public class NbtIntArray(public val value: IntArray) : NbtTag {
    override val type: NbtType get() = NbtType.INT_ARRAY
    override fun equals(other: Any?): Boolean = other is NbtIntArray && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "NbtIntArray(${value.toList()})"
}

public class NbtLongArray(public val value: LongArray) : NbtTag {
    override val type: NbtType get() = NbtType.LONG_ARRAY
    override fun equals(other: Any?): Boolean = other is NbtLongArray && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "NbtLongArray(${value.toList()})"
}

/**
 * A list whose elements share one tag type. An empty list has no element type on the wire (it is written
 * as TAG_End), so [elementType] of an empty list is [NbtType.END].
 */
public class NbtList(public val elementType: NbtType, elements: List<NbtTag>) : NbtTag,
    List<NbtTag> by java.util.Collections.unmodifiableList(ArrayList(elements)) {
    init {
        require(elementType != NbtType.END || elements.isEmpty()) { "a non-empty list needs an element type" }
        require(elements.all { it.type == elementType }) { "mixed element types in nbt list" }
    }

    override val type: NbtType get() = NbtType.LIST
    override fun equals(other: Any?): Boolean =
        other is NbtList && other.elementType == elementType && other.size == size && indices.all { other[it] == this[it] }

    override fun hashCode(): Int = fold(elementType.hashCode()) { h, e -> h * 31 + e.hashCode() }
    override fun toString(): String = "NbtList($elementType, ${joinToString(", ", "[", "]")})"

    public companion object {
        public fun of(elements: List<NbtTag>): NbtList =
            NbtList(if (elements.isEmpty()) NbtType.END else elements[0].type, elements)
    }
}

/** A compound whose keys iterate in bytewise UTF-8 order, which is the canonical order on the wire. */
public class NbtCompound private constructor(private val map: TreeMap<String, NbtTag>) : NbtTag,
    Map<String, NbtTag> by map {
    public constructor() : this(TreeMap(Utf8.order))
    public constructor(entries: Map<String, NbtTag>) : this(TreeMap<String, NbtTag>(Utf8.order).also { it.putAll(entries) })

    override val type: NbtType get() = NbtType.COMPOUND

    public fun with(key: String, value: NbtTag): NbtCompound = NbtCompound(map).also { it.map[key] = value }
    public fun without(vararg keys: String): NbtCompound =
        NbtCompound(map).also { c -> keys.forEach { c.map.remove(it) } }

    public fun toMutableMap(): MutableMap<String, NbtTag> = TreeMap<String, NbtTag>(Utf8.order).also { it.putAll(map) }

    public fun getByte(key: String): Byte? = (map[key] as? NbtByte)?.value
    public fun getShort(key: String): Short? = (map[key] as? NbtShort)?.value
    public fun getInt(key: String): Int? = (map[key] as? NbtInt)?.value
    public fun getLong(key: String): Long? = (map[key] as? NbtLong)?.value
    public fun getFloat(key: String): Float? = (map[key] as? NbtFloat)?.value
    public fun getDouble(key: String): Double? = (map[key] as? NbtDouble)?.value
    public fun getString(key: String): String? = (map[key] as? NbtString)?.value
    public fun getList(key: String): NbtList? = map[key] as? NbtList
    public fun getCompound(key: String): NbtCompound? = map[key] as? NbtCompound

    override fun equals(other: Any?): Boolean = other is NbtCompound && other.map == map
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = "NbtCompound($map)"

    public class Builder {
        private val map = TreeMap<String, NbtTag>(Utf8.order)
        public fun put(key: String, value: NbtTag): Builder = apply { map[key] = value }
        public fun put(key: String, value: Byte): Builder = put(key, NbtByte(value))
        public fun put(key: String, value: Boolean): Builder = put(key, NbtByte(if (value) 1 else 0))
        public fun put(key: String, value: Short): Builder = put(key, NbtShort(value))
        public fun put(key: String, value: Int): Builder = put(key, NbtInt(value))
        public fun put(key: String, value: Long): Builder = put(key, NbtLong(value))
        public fun put(key: String, value: Float): Builder = put(key, NbtFloat(value))
        public fun put(key: String, value: Double): Builder = put(key, NbtDouble(value))
        public fun put(key: String, value: String): Builder = put(key, NbtString(value))
        public fun build(): NbtCompound = NbtCompound(map)
    }

    public companion object {
        public fun build(block: Builder.() -> Unit): NbtCompound = Builder().apply(block).build()
    }
}
