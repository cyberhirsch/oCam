package com.ocam.ui

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

fun formatShutter(nanos: Long): String {
    val seconds = nanos / 1_000_000_000.0
    if (seconds <= 0.0) return "--"
    return if (seconds >= 0.4) {
        String.format(Locale.US, "%.1fs", seconds)
    } else {
        "1/${(1.0 / seconds).roundToInt()}"
    }
}

fun formatFocus(diopters: Float): String {
    if (diopters <= 0.05f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) {
        String.format(Locale.US, "%.1fm", meters)
    } else {
        String.format(Locale.US, "%.0fcm", meters * 100f)
    }
}

fun formatAperture(aperture: Float?): String? =
    aperture?.let { String.format(Locale.US, "f/%.1f", it) }

fun formatKelvin(kelvin: Int): String = "${kelvin}K"
