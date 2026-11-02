package com.ocam.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Luminance bins for the histogram. */
const val HISTOGRAM_BINS = 48

/** Mid grey, as a linear reflectance: what a meter calls "correctly exposed". */
private const val MID_GREY = 0.18

/** Edge strength below this is noise, however sharp the rest of the frame is. */
private const val PEAKING_FLOOR = 60

/** Peaking marks this share of the frame at most: the edges that are sharpest, not all edges. */
private const val PEAKING_SHARE = 0.04

private val PEAKING_INK = 0xFFFF3B30.toInt()

/**
 * What the frame currently looks like, measured rather than guessed.
 *
 * The preview is handed straight from the camera to the view, so no pixel passes through this
 * app on its way to the screen. Reading a small scaled copy of it back a few times a second is
 * what makes a histogram possible at all - at the cost of being a sample rather than every pixel
 * of every frame.
 *
 * [overlay] is drawn over the preview when peaking or false colour is on. It is in the buffer's
 * own coordinates, as the sample was read; [overlayTransform] is the view's transform at that
 * moment, which carries it onto the screen the same way the preview is carried.
 */
class FrameStats(
    val luma: IntArray,
    /** Stops away from a mid-grey average, or null before anything was measured. */
    val exposureOffset: Float? = null,
    val overlay: Bitmap? = null,
    val overlayTransform: Matrix? = null,
) {
    companion object {
        val EMPTY = FrameStats(IntArray(HISTOGRAM_BINS))
    }
}

/** sRGB decoded to linear light, for every 8 bit value. */
private val LINEAR = DoubleArray(256) { value ->
    val c = value / 255.0
    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

fun analyseFrame(
    bitmap: Bitmap,
    peaking: Boolean = false,
    falseColor: Boolean = false,
    transform: Matrix? = null,
): FrameStats {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return FrameStats.EMPTY

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val histogram = IntArray(HISTOGRAM_BINS)
    val luma = IntArray(pixels.size)
    var linearSum = 0.0
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // Rec. 709 luma, in integers: what the eye weights, not the average of the channels.
        val value = (r * 2126 + g * 7152 + b * 722) / 10000
        luma[i] = value
        histogram[(value * HISTOGRAM_BINS / 256).coerceIn(0, HISTOGRAM_BINS - 1)]++
        linearSum += LINEAR[value]
    }

    // An average meter: the mean light in the frame against mid grey. It reads the preview, so a
    // film simulation's curve is in it too - it says where the picture on screen sits.
    val mean = linearSum / pixels.size
    val offset = if (mean > 0.0) (ln(mean / MID_GREY) / ln(2.0)).toFloat().coerceIn(-6f, 6f) else -6f

    if (!peaking && !falseColor) return FrameStats(histogram, offset)

    val overlay = IntArray(pixels.size)
    if (falseColor) {
        for (i in luma.indices) overlay[i] = falseColorOf(luma[i])
    }
    if (peaking) markEdges(luma, width, height, overlay)

    val image = Bitmap.createBitmap(overlay, width, height, Bitmap.Config.ARGB_8888)
    return FrameStats(histogram, offset, image, transform)
}

/**
 * Bands of brightness, in percent of the output range. Everything between the bands is shown in
 * grey, so a colour on the frame always means "this is at that level" and never "this is red".
 */
private fun falseColorOf(value: Int): Int {
    val percent = value * 100 / 255
    return when {
        percent <= 2 -> 0xFF7A2EBE.toInt()      // crushed to black
        percent <= 10 -> 0xFF2F6FE0.toInt()     // deep shadow, the last detail
        percent in 38..45 -> 0xFF39B54A.toInt() // mid grey
        percent in 52..58 -> 0xFFF28CC0.toInt() // a stop over grey: where skin usually sits
        percent in 93..97 -> 0xFFF5D90A.toInt() // the last highlight detail
        percent >= 98 -> 0xFFE8261B.toInt()     // clipped
        else -> {
            val grey = value * 3 / 4
            (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }
}

/**
 * Sobel edge strength on luma, marking the strongest edges. The threshold follows the frame: a
 * soft frame marks little, a crisp one marks only its crispest edges, so the marks are always
 * where focus is rather than wherever there is texture.
 */
private fun markEdges(luma: IntArray, width: Int, height: Int, overlay: IntArray) {
    if (width < 3 || height < 3) return
    val strength = IntArray(luma.size)
    val counts = IntArray(1021)
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val i = row + x
            val gx = (luma[i - width + 1] + 2 * luma[i + 1] + luma[i + width + 1]) -
                (luma[i - width - 1] + 2 * luma[i - 1] + luma[i + width - 1])
            val gy = (luma[i + width - 1] + 2 * luma[i + width] + luma[i + width + 1]) -
                (luma[i - width - 1] + 2 * luma[i - width] + luma[i - width + 1])
            val magnitude = ((abs(gx) + abs(gy)) / 2).coerceAtMost(1020)
            strength[i] = magnitude
            counts[magnitude]++
        }
    }

    // The strength above which only the sharpest share of the frame lies.
    val allowed = (luma.size * PEAKING_SHARE).toInt()
    var above = 0
    var threshold = 1020
    while (threshold > PEAKING_FLOOR && above + counts[threshold] <= allowed) {
        above += counts[threshold]
        threshold--
    }
    threshold = threshold.coerceAtLeast(PEAKING_FLOOR)

    for (i in strength.indices) {
        if (strength[i] > threshold) overlay[i] = PEAKING_INK
    }
}
