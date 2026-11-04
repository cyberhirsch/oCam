package com.ocam.sequence

import android.graphics.Bitmap

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
) {
    OFF("ONE", null, 0, false, 0L, "One photograph"),
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
}

enum class Blend { LIGHTEN, AVERAGE }

/** The standing choices for the modes that build a picture. */
data class SequenceOptions(
    /** How much time a long exposure gathers before it ends by itself. */
    val longSeconds: Int = 8,
    /** Whether light painting and star trails also write the picture being built, as a video. */
    val buildUpVideo: Boolean = true,
)

/** The lengths a long exposure can be set to, in seconds. */
val LONG_EXPOSURE_SECONDS = listOf(2, 4, 8, 15, 30, 60)

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
)
