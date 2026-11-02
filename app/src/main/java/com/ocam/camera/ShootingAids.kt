package com.ocam.camera

/**
 * What is drawn over the frame to help take the picture, and how the shutter is released. None of
 * it changes what the camera does: these are sights and a trigger, not settings.
 */
data class ShootingAids(
    /** Edges that are in focus, marked on the preview. */
    val peaking: Boolean = false,
    /** The preview replaced by bands of brightness, so exposure can be read off the subject. */
    val falseColor: Boolean = false,
    /** A horizon line that stays level with the world, not with the phone. */
    val level: Boolean = false,
    /** How far the frame sits from a mid-grey average, in stops. */
    val meter: Boolean = true,
    val grid: GridMode = GridMode.OFF,
    val frame: CropFrame = CropFrame.NONE,
    val timer: SelfTimer = SelfTimer.OFF,
    /** Whether the volume keys release the shutter, which is the steadiest button on the phone. */
    val volumeShutter: Boolean = true,
)

enum class GridMode(val label: String) {
    OFF("OFF"),
    THIRDS("3×3"),
    CROSS("+"),
}

/**
 * A proportion to compose for, drawn as a mask over the frame. The file is not cropped: the lines
 * only say where a crop made later will fall. [ratio] is long side over short side, and turns with
 * the frame.
 */
enum class CropFrame(val label: String, val ratio: Float?) {
    NONE("OFF", null),
    SQUARE("1:1", 1f),
    FOUR_FIVE("4:5", 5f / 4f),
    WIDE("16:9", 16f / 9f),
    SCOPE("2.39", 2.39f),
    XPAN("65:24", 65f / 24f),
}

enum class SelfTimer(val label: String, val seconds: Int) {
    OFF("OFF", 0),
    TWO("2s", 2),
    TEN("10s", 10),
}
