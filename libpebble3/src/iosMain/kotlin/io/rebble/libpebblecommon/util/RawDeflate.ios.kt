package io.rebble.libpebblecommon.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.uLongfVar

// A zlib stream is a 2-byte header, the raw DEFLATE data, then a 4-byte Adler-32.
private const val ZLIB_HEADER = 2
private const val ZLIB_WRAPPER = ZLIB_HEADER + 4

@OptIn(ExperimentalForeignApi::class)
actual fun rawDeflate(data: UByteArray): UByteArray? = memScoped {
    if (data.isEmpty()) return@memScoped null
    // Sizing the output so it can only just hold the wrapper plus an input-sized payload makes
    // "didn't get smaller" a Z_BUF_ERROR rather than something to measure afterwards.
    val out = UByteArray(data.size + ZLIB_WRAPPER)
    val outLen = alloc<uLongfVar>()
    outLen.value = out.size.convert()
    val res = data.usePinned { src ->
        out.usePinned { dst ->
            compress2(dst.addressOf(0), outLen.ptr, src.addressOf(0), data.size.convert(), 9)
        }
    }
    if (res != Z_OK) return@memScoped null
    val rawLen = outLen.value.toInt() - ZLIB_WRAPPER
    if (rawLen !in 1..<data.size) return@memScoped null
    out.copyOfRange(ZLIB_HEADER, ZLIB_HEADER + rawLen)
}
