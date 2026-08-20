package net.justmcpe.pile.format.wire

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdException
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import net.justmcpe.pile.format.Compression
import net.justmcpe.pile.format.Limits
import net.justmcpe.pile.format.corrupt
import java.io.ByteArrayInputStream
import java.io.IOException

internal object ZstdCodec {
    fun compress(body: ByteArray, level: Compression, dictionary: ByteArray? = null, fast: Boolean = false): ByteArray {
        require(level != Compression.NONE)
        ZstdCompressCtx().use { ctx ->
            ctx.setLevel(level.zstdLevel)
            ctx.setWorkers(if (fast) Runtime.getRuntime().availableProcessors() else 0)
            dictionary?.let(ctx::loadDict)
            return ctx.compress(body)
        }
    }

    /** Decompresses one body, refusing a window above 8 MiB and output above [Limits.MAX_BODY]. */
    fun decompress(stored: ByteArray, off: Int, len: Int): ByteArray {
        return decompress(stored, off, len, null)
    }

    /** Decompresses an indexed frame using its optional shared dictionary, bounded at [maxOut]. */
    fun decompress(
        stored: ByteArray,
        off: Int,
        len: Int,
        dictionary: ByteArray?,
        maxOut: Int = Limits.MAX_BODY
    ): ByteArray {
        val declared = try {
            Zstd.getFrameContentSize(stored, off, len)
        } catch (e: ZstdException) {
            corrupt("decompress body: ${e.message}")
        }
        if (declared > maxOut) corrupt("body declares $declared bytes, limit $maxOut")
        var out = ByteArray(if (declared > 0) declared.toInt() else minOf(len.toLong() * 4, 1L shl 20).toInt())
        var size = 0
        try {
            ZstdInputStreamNoFinalizer(ByteArrayInputStream(stored, off, len)).use { zin ->
                dictionary?.let { zin.setDict(it) }
                zin.setLongMax(Limits.MAX_ZSTD_WINDOW_LOG)
                while (true) {
                    if (size == out.size) {
                        if (size >= maxOut) corrupt("body exceeds $maxOut bytes")
                        val grown = minOf(out.size.toLong() * 2, maxOut.toLong()).toInt()
                        out = out.copyOf(maxOf(grown, size + 1))
                    }
                    val n = zin.read(out, size, out.size - size)
                    if (n < 0) break
                    size += n
                }
            }
        } catch (e: IOException) {
            corrupt("decompress body: ${e.message}")
        }
        return if (size == out.size) out else out.copyOf(size)
    }
}
