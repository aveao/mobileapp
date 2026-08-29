package io.rebble.libpebblecommon.util

/**
 * Compress [data] as a raw DEFLATE stream — no zlib header or trailer, which is what the watch's
 * tinflate expects.
 *
 * Returns null when the compressed form would not be smaller than [data], so callers fall back to
 * sending it uncompressed; the watch rejects a stream that isn't smaller.
 */
expect fun rawDeflate(data: UByteArray): UByteArray?
