package com.ocam.ui

import android.graphics.Bitmap

/** Luminance bins for the histogram. */
const val HISTOGRAM_BINS = 48

/**
 * What the frame currently looks like, measured rather than guessed.
 *
 * The preview is handed straight from the camera to the view, so no pixel passes through this
 * app on its way to the screen. Reading a small scaled copy of it back a few times a second is
 * what makes a histogram possible at all - at the cost of being a sample rather than every pixel
 * of every frame.
 */
class FrameStats(val luma: IntArray) {
    companion object {
        val EMPTY = FrameStats(IntArray(HISTOGRAM_BINS))
    }
}

fun analyseFrame(bitmap: Bitmap): FrameStats {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return FrameStats.EMPTY

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val luma = IntArray(HISTOGRAM_BINS)
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // Rec. 709 luma, in integers: what the eye weights, not the average of the channels.
        val value = (r * 2126 + g * 7152 + b * 722) / 10000
        luma[(value * HISTOGRAM_BINS / 256).coerceIn(0, HISTOGRAM_BINS - 1)]++
    }
    return FrameStats(luma)
}
