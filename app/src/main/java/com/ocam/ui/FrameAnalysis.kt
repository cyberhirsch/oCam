package com.ocam.ui

import android.graphics.Bitmap

/** Luminance bins for the histogram. */
const val HISTOGRAM_BINS = 48

/** How coarse the clipping mask is. Fine enough to point at a blown highlight, cheap to compute. */
const val ZEBRA_COLUMNS = 24
const val ZEBRA_ROWS = 32

/**
 * What the frame currently looks like, measured rather than guessed.
 *
 * The preview is handed straight from the camera to the view, so no pixel passes through this
 * app on its way to the screen. Reading a small scaled copy of it back a few times a second is
 * what makes a histogram and a clipping warning possible at all - at the cost of being a sample
 * rather than every pixel of every frame.
 */
class FrameStats(
    val luma: IntArray,
    val clipped: BooleanArray,
    val clippedFraction: Float,
) {
    companion object {
        val EMPTY = FrameStats(IntArray(HISTOGRAM_BINS), BooleanArray(0), 0f)
    }
}

/** Anything above this counts as blown out. */
private const val CLIP_LEVEL = 247

fun analyseFrame(bitmap: Bitmap): FrameStats {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return FrameStats.EMPTY

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val luma = IntArray(HISTOGRAM_BINS)
    val clipped = BooleanArray(ZEBRA_COLUMNS * ZEBRA_ROWS)
    var clippedPixels = 0

    for (y in 0 until height) {
        val cellRow = y * ZEBRA_ROWS / height
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Rec. 709 luma, in integers: what the eye weights, not the average of the channels.
            val value = (r * 2126 + g * 7152 + b * 722) / 10000
            luma[(value * HISTOGRAM_BINS / 256).coerceIn(0, HISTOGRAM_BINS - 1)]++

            if (r >= CLIP_LEVEL || g >= CLIP_LEVEL || b >= CLIP_LEVEL) {
                clippedPixels++
                clipped[cellRow * ZEBRA_COLUMNS + x * ZEBRA_COLUMNS / width] = true
            }
        }
    }

    return FrameStats(
        luma = luma,
        clipped = clipped,
        clippedFraction = clippedPixels.toFloat() / (width * height),
    )
}
