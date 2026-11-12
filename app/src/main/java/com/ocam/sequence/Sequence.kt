package com.ocam.sequence

import android.graphics.Bitmap
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What one press of the shutter does: take a photograph, or keep taking frames and build one
 * picture out of all of them.
 */
enum class SequenceMode(
    val label: String,
    /** How the frames become one picture; null for an ordinary photograph. */
    val blend: Blend?,
    /** How far a pixel must rise before it counts as new light rather than sensor noise. */
    val gate: Int,
    /** Whether the picture being built is worth watching being built. */
    val buildsUp: Boolean,
    /** The least time between frames of the build-up video, in sensor time. */
    val videoStepNs: Long,
    val hint: String,
    /** Whether the frames are a fixed few at different exposures, rather than a run. */
    val bracket: Boolean = false,
    /** Whether the frames are taken one at a time, each at a different focus. */
    val stack: Boolean = false,
    /** Whether the frames are taken as the camera turns, to be laid side by side. */
    val panorama: Boolean = false,
    /** Whether each picture is one frame of a video, taken at an interval. */
    val timelapse: Boolean = false,
) {
    OFF("ONE", null, 0, false, 0L, "One photograph"),
    HDR(
        "HDR", null, 0, false, 0L,
        "HDR: a few frames at different exposures, merged. Hold still, or use a tripod",
        bracket = true,
    ),
    STACK(
        "STACK", null, 0, false, 0L,
        "Focus stack: from the distance focus is set to and further away. Set it on the nearest " +
            "thing. On a tripod",
        stack = true,
    ),
    PANO(
        "PANO", null, 0, false, 0L,
        "Panorama: turn slowly on the spot, sideways. Shutter to start and to stop",
        panorama = true,
    ),
    PAINT(
        "PAINT", Blend.LIGHTEN, 3, true, 66_666_667L,
        "Light painting: the brightest light at each point stays. Shutter to start and to stop",
    ),
    LONG(
        "LONG", Blend.AVERAGE, 0, false, 0L,
        "Long exposure: frames averaged, as if through an ND filter. On a tripod",
    ),
    TRAILS(
        "TRAIL", Blend.LIGHTEN, 6, true, 0L,
        "Star trails: on a tripod, with the shutter at 5 to 30 seconds. Shutter to start and to stop",
    ),
    LAPSE(
        "LAPSE", Blend.AVERAGE, 0, false, 0L,
        "Timelapse: one picture every interval, each the average of the light over its blur, " +
            "written as a 30 fps video. On a tripod. Shutter to start and to stop",
        timelapse = true,
    ),
}

enum class Blend { LIGHTEN, AVERAGE }

/** The standing choices for the modes that build a picture. */
data class SequenceOptions(
    /** How much time a long exposure gathers before it ends by itself. */
    val longSeconds: Int = 8,
    /** Whether light painting and star trails also write the picture being built, as a video. */
    val buildUpVideo: Boolean = true,
    /** How many exposures HDR takes. */
    val hdrFrames: Int = 3,
    /** How many stops either side of the exposure set the bracket reaches. */
    val hdrSpread: Int = 2,
    /** Whether HDR also writes each frame, to merge elsewhere. */
    val hdrKeepFrames: Boolean = false,
    /** Whether HDR writes a gain map for screens that can show more than paper can. */
    val ultraHdr: Boolean = true,
    /** How many frames a focus stack takes. */
    val stackFrames: Int = 9,
    /** How many times further away than the distance set a stack ends, or 0 for infinity. */
    val stackReach: Int = 2,
    /** How long a timelapse waits from one picture to the next. */
    val lapseSeconds: Int = 2,
    /** How much of each interval a timelapse picture gathers light over, in percent; 0 is one frame. */
    val lapseBlur: Int = 50,
)

/** The lengths a long exposure can be set to, in seconds. */
val LONG_EXPOSURE_SECONDS = listOf(2, 4, 8, 15, 30, 60)

val HDR_FRAMES = listOf(3, 5)
val HDR_SPREADS = listOf(1, 2, 3)

val STACK_FRAMES = listOf(5, 9, 15, 25)
val STACK_REACHES = listOf(2, 4, 8, 0)

val LAPSE_SECONDS = listOf(1, 2, 5, 10, 30, 60)
val LAPSE_BLURS = listOf(0, 25, 50, 100)

/**
 * When each frame of a timelapse counts. A picture starts every [intervalNs] and gathers the frames
 * that arrive in the first [blurNs] of it; the frames in between are only shown. Times are the
 * sensor's own, so a slow merge does not move the cadence.
 */
class LapseClock(private val intervalNs: Long, blurNs: Long) {
    // At least one frame, and never more than the interval: a picture cannot outlast its turn.
    private val blurNs = blurNs.coerceIn(1L, intervalNs)
    private var start = -1L
    private var open = false

    /** What to do with a frame. [emit] comes first: the picture gathered so far is finished. */
    class Step(val emit: Boolean, val reset: Boolean, val add: Boolean)

    fun frame(timestamp: Long): Step {
        if (start < 0) {
            start = timestamp
            open = true
            return Step(emit = false, reset = true, add = true)
        }
        var emit = false
        var reset = false
        if (open && timestamp - start >= blurNs) {
            open = false
            emit = true
        }
        if (timestamp - start >= intervalNs) {
            // Keep to the beat even when a frame comes late, rather than drifting later each time.
            start += (timestamp - start) / intervalNs * intervalNs
            open = true
            reset = true
        }
        return Step(emit = emit, reset = reset, add = open)
    }

    /** Start again with the next frame, after a pause. */
    fun restart() {
        start = -1L
        open = false
    }
}

/**
 * How far from the exposure set each frame of a bracket is, in stops, in the order they are taken:
 * the exposure set first, then outwards, so that the frames nearest each other in time are the ones
 * that have to line up best.
 */
fun bracketStops(frames: Int, spread: Float): List<Float> {
    val steps = (frames - 1) / 2
    return buildList {
        add(0f)
        for (step in 1..steps) {
            val stops = spread * step / steps
            add(-stops)
            add(stops)
        }
    }
}

/**
 * The sensitivity and shutter for a frame [stops] from the exposure set. The shutter moves first,
 * because it adds no noise; sensitivity makes up what it cannot reach, and past that the frame is
 * as far as this lens goes, which the capture result reports.
 */
fun bracketExposure(
    iso: Int,
    exposureNs: Long,
    stops: Float,
    isoMin: Int,
    isoMax: Int,
    exposureMinNs: Long,
    exposureMaxNs: Long,
): Pair<Int, Long> {
    val wanted = exposureNs * 2.0.pow(stops.toDouble())
    val shutter = wanted.toLong().coerceIn(exposureMinNs, exposureMaxNs)
    val sensitivity = (iso * wanted / shutter).roundToInt().coerceIn(isoMin, isoMax)
    return sensitivity to shutter
}

/** A run in progress, for the screen. */
class SequenceProgress(
    val mode: SequenceMode,
    val frames: Int,
    val exposedNs: Long,
    val targetNs: Long?,
    val paused: Boolean,
    /** The picture so far, small, in the sensor's own orientation. Null until the first frame. */
    val picture: Bitmap?,
    /** How the sensor sits in the phone, to turn [picture] the way the preview is turned. */
    val sensorOrientation: Int,
    val mirrored: Boolean,
    /** Whether light can stop counting without the run ending, which only a run of light can do. */
    val holds: Boolean = true,
    /** What to say about how far along it is, for a run that is not measured in time. */
    val note: String? = null,
)
