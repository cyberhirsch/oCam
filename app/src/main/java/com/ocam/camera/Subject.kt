package com.ocam.camera

/**
 * A face the camera found, given in the preview's own coordinates - 0..1 from the top left of the
 * picture - so the overlay can draw it without knowing anything about sensor arrays.
 *
 * [focusX] and [focusY] are where focus is being put: an eye where the camera reports eyes, the
 * middle of the face where it only reports the rectangle.
 */
class Subject(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val focusX: Float,
    val focusY: Float,
    /** True when the point came from a reported eye rather than from the middle of the face. */
    val onEye: Boolean,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}
