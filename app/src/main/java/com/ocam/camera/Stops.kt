package com.ocam.camera

import kotlin.math.abs
import kotlin.math.ln

/**
 * The values a camera steps through, a third of a stop apart, as printed on every dial. A key or a
 * strip of presets moves between these rather than to arbitrary numbers.
 */
private val ISO_STOPS = listOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
    2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200, 102400,
)

/** Shutter speeds in seconds, written as their denominators below one second. */
private val SHUTTER_STOPS_SECONDS: List<Double> = listOf(
    8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200,
    160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
).map { 1.0 / it } + listOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)

/** The kelvins white balance steps through, finer where the eye is most sensitive to them. */
private val KELVIN_STOPS = listOf(
    2500, 2800, 3200, 3600, 4000, 4300, 4700, 5000, 5300, 5600, 6000, 6500, 7000, 7500, 8500, 10000,
)

/** Every ISO a third of a stop apart that this lens can do, with its own ends when they fall between. */
fun isoStops(lower: Int, upper: Int): List<Int> =
    (listOf(lower) + ISO_STOPS.filter { it in (lower + 1) until upper } + listOf(upper)).distinct()

fun shutterStopsNs(lowerNs: Long, upperNs: Long): List<Long> {
    val inside = SHUTTER_STOPS_SECONDS.map { (it * 1_000_000_000.0).toLong() }
        .filter { it in (lowerNs + 1) until upperNs }
    return (listOf(lowerNs) + inside + listOf(upperNs)).distinct()
}

fun kelvinStops(lower: Int, upper: Int): List<Int> = KELVIN_STOPS.filter { it in lower..upper }

/** The stop nearest [value], judged in stops (by ratio), not by difference. */
fun <T : Number> nearestStop(stops: List<T>, value: Double): Int {
    if (stops.isEmpty() || value <= 0.0) return 0
    return stops.indices.minByOrNull { abs(ln(stops[it].toDouble() / value)) } ?: 0
}

/** What the volume keys do. */
enum class VolumeKeys(val label: String) {
    /** Up records - starts and stops a run - and down takes a photo. */
    SPLIT("REC / PHOTO"),
    /** Either key is the shutter of whatever mode is chosen. */
    SHUTTER("SHUTTER"),
    OFF("OFF"),
}
