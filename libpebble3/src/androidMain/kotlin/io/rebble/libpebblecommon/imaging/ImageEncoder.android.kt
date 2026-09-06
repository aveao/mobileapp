package io.rebble.libpebblecommon.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import co.touchlab.kermit.Logger

private val logger = Logger.withTag("ImageEncoder")

/** Centre-crops and scales this bitmap to [width] x [height], then encodes it for the watch. */
fun Bitmap.encodeForWatch(width: Int, height: Int): EncodedImage {
    val startWall = SystemClock.elapsedRealtime()
    val startCpu = SystemClock.currentThreadTimeMillis()
    val scaled = centerCropScale(this, width, height)
    val argb = IntArray(width * height)
    scaled.getPixels(argb, 0, width, 0, 0, width, height)
    if (scaled !== this) scaled.recycle()
    val scaledAt = SystemClock.elapsedRealtime()
    val encoded = ImageEncoder.encode(argb, width, height)
    val wall = SystemClock.elapsedRealtime() - startWall
    val cpu = SystemClock.currentThreadTimeMillis() - startCpu
    // Wall and CPU diverging means this thread spent the difference descheduled rather than
    // working, which is what being throttled in the background looks like from here.
    logger.d {
        "encoded ${this.width}x${this.height} -> ${width}x$height: " +
            "${wall}ms wall (${scaledAt - startWall}ms scale, ${wall - (scaledAt - startWall)}ms " +
            "quantise), ${cpu}ms cpu, ${encoded.pixels.size}B"
    }
    return encoded
}

private fun centerCropScale(source: Bitmap, width: Int, height: Int): Bitmap {
    if (source.width == width && source.height == height) return source
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val srcAspect = source.width.toFloat() / source.height
    val dstAspect = width.toFloat() / height
    val src = if (srcAspect > dstAspect) {
        val cropW = (source.height * dstAspect).toInt()
        val x = (source.width - cropW) / 2
        Rect(x, 0, x + cropW, source.height)
    } else {
        val cropH = (source.width / dstAspect).toInt()
        val y = (source.height - cropH) / 2
        Rect(0, y, source.width, y + cropH)
    }
    // Bilinear filter the downscale so the ditherer sees a smooth image, not an aliased one.
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(source, src, Rect(0, 0, width, height), paint)
    return out
}
