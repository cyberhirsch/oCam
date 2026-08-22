package com.ocam.camera

import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

enum class CaptureFormat(val label: String, val writesJpeg: Boolean, val writesRaw: Boolean) {
    JPEG("JPEG", true, false),
    RAW("RAW", false, true),
    RAW_JPEG("RAW+JPEG", true, true),
}

/**
 * What the camera is being told to do. Exposure is one unit on purpose: the hardware AE is
 * all-or-nothing, so ISO and shutter go manual together and exposure compensation only exists
 * while AE is on.
 */
data class CaptureSettings(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val exposureTimeNs: Long = 1_000_000_000L / 60,
    val exposureCompensation: Int = 0,
    val manualFocus: Boolean = false,
    /** Focus distance in diopters (1/m). 0 is infinity. */
    val focusDiopters: Float = 0f,
    val manualWhiteBalance: Boolean = false,
    val kelvin: Int = 5200,
    /** Green (negative) to magenta (positive) shift, -1..1. */
    val tint: Float = 0f,
    val aperture: Float? = null,
    val format: CaptureFormat = CaptureFormat.JPEG,
    /** Physical device rotation in degrees, from the orientation sensor. */
    val deviceRotation: Int = 0,
) {
    val allManual: Boolean get() = manualExposure && manualFocus && manualWhiteBalance
    val anyManual: Boolean get() = manualExposure || manualFocus || manualWhiteBalance
}

/** Identity matrix for COLOR_CORRECTION_TRANSFORM, as numerator/denominator pairs. */
val IDENTITY_COLOR_TRANSFORM: ColorSpaceTransform = ColorSpaceTransform(
    intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1,
    )
)

/**
 * Channel gains that render a scene lit at [kelvin] as neutral. The illuminant colour comes from
 * Tanner Helland's blackbody approximation; the gains are its reciprocal, normalised so the
 * smallest gain is 1.0 (gains below 1 would just throw away signal).
 */
fun kelvinToGains(kelvin: Int, tint: Float = 0f): RggbChannelVector {
    val t = kelvin.coerceIn(1500, 15000) / 100.0

    val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val green = if (t <= 66) {
        99.4708025861 * ln(t) - 161.1195681661
    } else {
        288.1221695283 * (t - 60).pow(-0.0755148492)
    }
    val blue = when {
        t >= 66 -> 255.0
        t <= 19 -> 1.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }

    val r = red.coerceIn(1.0, 255.0)
    val g = green.coerceIn(1.0, 255.0)
    val b = blue.coerceIn(1.0, 255.0)

    val gainR = 255.0 / r
    // Tint trades green against magenta, which on a Bayer sensor is simply how much of the
    // green channel is let through relative to the other two.
    val gainG = 255.0 / g * (1.0 - tint.coerceIn(-1f, 1f) * 0.4)
    val gainB = 255.0 / b
    val smallest = minOf(gainR, gainG, gainB)

    return RggbChannelVector(
        (gainR / smallest).toFloat(),
        (gainG / smallest).toFloat(),
        (gainG / smallest).toFloat(),
        (gainB / smallest).toFloat(),
    )
}
