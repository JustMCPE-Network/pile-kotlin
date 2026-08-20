package net.justmcpe.pile.format

import net.jpountz.xxhash.StreamingXXHash64
import net.jpountz.xxhash.XXHashFactory

/** xxHash64 with seed 0, the only hash the format uses. Backed by lz4-java's fastest available implementation. */
public object XxHash {
    private val factory: XXHashFactory = XXHashFactory.fastestInstance()

    public fun hash(data: ByteArray, off: Int = 0, len: Int = data.size - off): Long =
        factory.hash64().hash(data, off, len, 0)

    /** Hashes the concatenation of [parts] as one stream. */
    public fun hash(vararg parts: ByteArray): Long {
        val s = Streaming()
        for (p in parts) s.update(p, 0, p.size)
        return s.digest()
    }

    public fun hex(h: Long): String = "%016x".format(h)

    public class Streaming {
        private val hash: StreamingXXHash64 = factory.newStreamingHash64(0)

        public fun update(data: ByteArray, off: Int, len: Int) {
            hash.update(data, off, len)
        }

        public fun digest(): Long = hash.value
    }
}
