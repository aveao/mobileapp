package io.rebble.libpebblecommon.util

import java.util.zip.Deflater

actual fun rawDeflate(data: UByteArray): UByteArray? {
    if (data.isEmpty()) return null
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
        deflater.setInput(data.toByteArray())
        deflater.finish()
        // Sizing the output at the input length makes "didn't get smaller" a buffer-full condition.
        val out = ByteArray(data.size)
        var n = 0
        while (!deflater.finished()) {
            if (n == out.size) return null
            val written = deflater.deflate(out, n, out.size - n)
            if (written == 0) return null
            n += written
        }
        return if (n < data.size) out.copyOf(n).toUByteArray() else null
    } finally {
        deflater.end()
    }
}
