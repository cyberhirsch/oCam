package com.ocam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * What a press of the shutter writes. The still image is one format or none - JPEG and HEIC are
 * alternatives, not companions - and RAW rides alongside it.
 */
enum class CaptureFormat(
    val label: String,
    /** An [android.graphics.ImageFormat] constant, or null when no rendered image is wanted. */
    val stillFormat: Int?,
    val writesRaw: Boolean,
) {
    JPEG("JPEG", ImageFormat.JPEG, false),
    HEIC("HEIC", ImageFormat.HEIC, false),
    RAW("RAW", null, true),
    RAW_JPEG("RAW+JPEG", ImageFormat.JPEG, true),
    RAW_HEIC("RAW+HEIC", ImageFormat.HEIC, true),
    ;

    val writesStill: Boolean get() = stillFormat != null
    val usesHeic: Boolean get() = stillFormat == ImageFormat.HEIC
    val usesJpeg: Boolean get() = stillFormat == ImageFormat.JPEG
}

/**
 * The light the picture is being balanced for.
 *
 * The three named ones are handed to the camera as its own fixed illuminants rather than as
 * numbers this app invents. Firmware knows what its sensor's channels do under tungsten; a
 * temperature converted to gains here does not, and guessing that produced a green cast.
 * [CUSTOM] is the only one this app computes, and it computes it as a shift away from gains the
 * camera itself reported, so the sensor's own balance is still what it starts from.
 */
enum class WhiteBalance(val label: String, val kelvin: Int, val awbMode: Int) {
    TUNGSTEN("TUN", 2850, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    DAYLIGHT("DAY", 5500, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    SHADE("SHD", 7500, CameraMetadata.CONTROL_AWB_MODE_SHADE),
    CUSTOM("ADJ", 0, CameraMetadata.CONTROL_AWB_MODE_OFF),
}

/**
 * What the camera is being told to do. There is no automatic mode in here: exposure, focus and
 * white balance are all set, and the camera changes none of them by itself.
 */
data class CaptureSettings(
    val iso: Int = 400,
    val exposureTimeNs: Long = 1_000_000_000L / 60,
    /** Focus distance in diopters (1/m). 0 is infinity. */
    val focusDiopters: Float = 0f,
    val whiteBalance: WhiteBalance = WhiteBalance.DAYLIGHT,
    /** The temperature [WhiteBalance.CUSTOM] is aiming at. */
    val kelvin: Int = 5500,
    /** Green (negative) to magenta (positive) shift, -1..1. */
    val tint: Float = 0f,
    val aperture: Float? = null,
    val format: CaptureFormat = CaptureFormat.JPEG,
    /** Physical device rotation in degrees, from the orientation sensor. */
    val deviceRotation: Int = 0,
)

/** Identity matrix for COLOR_CORRECTION_TRANSFORM, as numerator/denominator pairs. */
val IDENTITY_COLOR_TRANSFORM: ColorSpaceTransform = ColorSpaceTransform(
    intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1,
    )
)

/** The colour of a blackbody at [kelvin], as Tanner Helland's approximation of it. */
private fun illuminantRgb(kelvin: Int): Triple<Double, Double, Double> {
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
    return Triple(red.coerceIn(1.0, 255.0), green.coerceIn(1.0, 255.0), blue.coerceIn(1.0, 255.0))
}

/**
 * Gains for [targetKelvin], worked out as a move away from [anchor] - the gains the camera
 * reported while it was balanced for [anchorKelvin].
 *
 * Absolute gains cannot be computed from a colour temperature alone. A raw sensor's green
 * channel collects roughly twice what red and blue do, and by how much is a property of that
 * particular sensor, not of the light; gains derived from the illuminant alone come out near
 * 1:1:1 and leave every picture green. The camera's own reported gains carry that sensor
 * property, so only the difference between two illuminants is computed here.
 */
fun shiftedGains(
    anchor: RggbChannelVector,
    anchorKelvin: Int,
    targetKelvin: Int,
    tint: Float,
): RggbChannelVector {
    val (anchorR, anchorG, anchorB) = illuminantRgb(anchorKelvin)
    val (targetR, targetG, targetB) = illuminantRgb(targetKelvin)

    var red = anchor.red * (anchorR / targetR)
    // Tint trades green against magenta, which on a Bayer sensor is how much of the green
    // channel is let through relative to the other two.
    var green = (anchor.greenEven + anchor.greenOdd) / 2.0 * (anchorG / targetG) *
        (1.0 - tint.coerceIn(-1f, 1f) * 0.4)
    var blue = anchor.blue * (anchorB / targetB)

    // A gain below 1.0 throws signal away rather than balancing anything.
    val smallest = minOf(red, green, blue)
    if (smallest > 0.0) {
        red /= smallest
        green /= smallest
        blue /= smallest
    }
    return RggbChannelVector(red.toFloat(), green.toFloat(), green.toFloat(), blue.toFloat())
}
