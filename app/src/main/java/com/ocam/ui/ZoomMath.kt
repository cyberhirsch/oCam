package com.ocam.ui

/** How far a picture enlarged by [scale] may be moved along a [side] and still fill it. */
fun clampPan(pan: Float, scale: Float, side: Float): Float {
    val most = ((scale - 1f) * side / 2f).coerceAtLeast(0f)
    return pan.coerceIn(-most, most)
}

/** The move that keeps the point at [point] under the finger as a picture enlarges to [scale]. */
fun panToKeep(point: Float, scale: Float, side: Float): Float =
    clampPan((side / 2f - point) * (scale - 1f), scale, side)
