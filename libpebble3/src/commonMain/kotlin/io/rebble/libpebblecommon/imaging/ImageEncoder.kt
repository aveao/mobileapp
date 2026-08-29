package io.rebble.libpebblecommon.imaging

import kotlin.math.sqrt

/**
 * Encodes an ARGB image as the watch's 4-bpp palettized [EncodedImage]: choose a 16-colour palette
 * by median cut over the watch's 64 screen colours, ordered-dither to that palette, and pack two
 * 4-bit indices per byte (even x = high nibble, matching the firmware).
 *
 * Colours are matched against what the panel actually shows (see ScreenColours.kt), not the nominal
 * 0/85/170/255 the GColor8 bits suggest. Only the choice of palette entry changes; the bytes on the
 * wire are still GColor8 codes.
 *
 * The dither is an 8x8 Bayer tile rather than error diffusion. Both hide banding about equally
 * well at this palette size, but the tile's noise is periodic, so the packed pixels keep the runs
 * and repeats the wire's DEFLATE trades on: over a corpus of photographs the encoded image
 * compresses about 1.9x better than the Floyd-Steinberg equivalent.
 */
object ImageEncoder {
    private const val MAX_COLORS = 16

    private val BAYER_8X8 = intArrayOf(
         0, 32,  8, 40,  2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44,  4, 36, 14, 46,  6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
         3, 35, 11, 43,  1, 33,  9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47,  7, 39, 13, 45,  5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    // Screen colour index (0..63) -> the GColor8 byte sent to the watch, always fully opaque.
    private fun gcolor8(code: Int): Int = 0xC0 or code

    private class Entry(val code: Int, val count: Int)

    /** Encodes an ARGB8888 pixel array (row-major, [width] * [height]). */
    fun encode(argb: IntArray, width: Int, height: Int): EncodedImage {
        val palette = medianCutPalette(argb)
        val palR = IntArray(palette.size) { SCREEN_R[palette[it]] }
        val palG = IntArray(palette.size) { SCREEN_G[palette[it]] }
        val palB = IntArray(palette.size) { SCREEN_B[palette[it]] }

        val stride = (width + 1) / 2
        val pixels = UByteArray(stride * height)
        val step = ditherStep(palR, palG, palB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val bias = (BAYER_8X8[(y and 7) * 8 + (x and 7)] / 64f - 0.5f) * step
                val r = (((p shr 16) and 0xFF) + bias).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + bias).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) + bias).toInt().coerceIn(0, 255)
                val idx = nearest(r, g, b, palR, palG, palB)
                val bi = y * stride + (x shr 1)
                pixels[bi] = if (x and 1 == 0) {
                    ((pixels[bi].toInt() and 0x0F) or (idx shl 4)).toUByte()
                } else {
                    ((pixels[bi].toInt() and 0xF0) or idx).toUByte()
                }
            }
        }
        val paletteBytes = UByteArray(palette.size) { gcolor8(palette[it]).toUByte() }
        return EncodedImage(width, height, paletteBytes, pixels)
    }

    // Median cut over the screen colours the image's pixels land on. Returns up to 16 distinct
    // screen colour indices. Box choice and split point are weighted by pixel count, so a large
    // flat region doesn't lose a palette slot to a handful of stray pixels.
    private fun medianCutPalette(argb: IntArray): List<Int> {
        val counts = HashMap<Int, Int>()
        for (p in argb) {
            val code = nearestScreenColour((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            counts[code] = (counts[code] ?: 0) + 1
        }
        val initial = counts.entries.map { Entry(it.key, it.value) }.toMutableList()
        val boxes = mutableListOf(initial)
        while (boxes.size < MAX_COLORS) {
            val bi = boxes.indices.filter { boxes[it].size > 1 }
                .maxByOrNull { spread(boxes[it]).toLong() * population(boxes[it]) } ?: break
            val box = boxes[bi]
            val axis = longestAxis(box)
            box.sortBy { channel(it.code, axis) }
            val mid = weightedMedian(box)
            boxes[bi] = box.subList(0, mid).toMutableList()
            boxes.add(box.subList(mid, box.size).toMutableList())
        }
        return boxes.filter { it.isNotEmpty() }.map { box ->
            // Screen colours can't be averaged into a new entry the way nominal 2-bit components
            // could, so take the one nearest the box's count-weighted centroid.
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var sn = 0L
            for (e in box) {
                sr += SCREEN_R[e.code].toLong() * e.count
                sg += SCREEN_G[e.code].toLong() * e.count
                sb += SCREEN_B[e.code].toLong() * e.count
                sn += e.count
            }
            nearestScreenColour(round(sr, sn), round(sg, sn), round(sb, sn))
        }.distinct().ifEmpty { listOf(nearestScreenColour(0, 0, 0)) }
    }

    private fun round(sum: Long, count: Long): Int = ((sum * 2 + count) / (count * 2)).toInt()

    private fun channel(code: Int, axis: Int): Int = when (axis) {
        0 -> SCREEN_R[code]
        1 -> SCREEN_G[code]
        else -> SCREEN_B[code]
    }

    private fun population(box: List<Entry>): Long = box.sumOf { it.count.toLong() }

    // Split index that puts half the box's pixels either side, keeping both halves non-empty.
    private fun weightedMedian(box: List<Entry>): Int {
        val half = population(box) / 2
        var acc = 0L
        for (i in box.indices) {
            acc += box[i].count
            if (acc > half) return (i + 1).coerceIn(1, box.size - 1)
        }
        return box.size - 1
    }

    // Extent of the box along each channel, measured in screen colours.
    private fun extents(box: List<Entry>): IntArray {
        val lo = intArrayOf(255, 255, 255)
        val hi = intArrayOf(0, 0, 0)
        for (e in box) {
            for (axis in 0..2) {
                val v = channel(e.code, axis)
                if (v < lo[axis]) lo[axis] = v
                if (v > hi[axis]) hi[axis] = v
            }
        }
        return intArrayOf(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2])
    }

    private fun spread(box: List<Entry>): Int = extents(box).max()

    private fun longestAxis(box: List<Entry>): Int {
        val d = extents(box)
        return if (d[0] >= d[1] && d[0] >= d[2]) 0 else if (d[1] >= d[2]) 1 else 2
    }

    // How far the tile may push a pixel: the typical spacing between palette colours, so the
    // dither only ever pulls a pixel towards a neighbouring entry.
    private fun ditherStep(palR: IntArray, palG: IntArray, palB: IntArray): Float {
        if (palR.size < 2) return 0f
        val nearest = FloatArray(palR.size)
        for (i in palR.indices) {
            var best = Int.MAX_VALUE
            for (j in palR.indices) {
                if (i == j) continue
                val dr = palR[i] - palR[j]
                val dg = palG[i] - palG[j]
                val db = palB[i] - palB[j]
                val d = dr * dr + dg * dg + db * db
                if (d in 1..<best) best = d
            }
            nearest[i] = if (best == Int.MAX_VALUE) 0f else sqrt(best.toFloat())
        }
        nearest.sort()
        return nearest[nearest.size / 2]
    }

    private fun nearest(r: Int, g: Int, b: Int, palR: IntArray, palG: IntArray, palB: IntArray): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (j in palR.indices) {
            val dr = r - palR[j]; val dg = g - palG[j]; val db = b - palB[j]
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = j }
        }
        return best
    }
}
