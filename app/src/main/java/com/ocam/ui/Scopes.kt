package com.ocam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ocam.camera.Subject

/** Where the tones sit. Flat and small: a shape to glance at, not a chart to read. */
@Composable
fun Histogram(stats: FrameStats, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(38.dp)
            .background(Color(0x40000000), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val peak = stats.luma.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
            val step = size.width / stats.luma.size
            stats.luma.forEachIndexed { index, count ->
                // Square root, because a linear scale hides everything but the dominant tone.
                val level = kotlin.math.sqrt(count.toFloat() / peak)
                val x = index * step + step / 2f
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(x, size.height),
                    end = Offset(x, size.height * (1f - level)),
                    strokeWidth = step * 0.8f,
                    cap = StrokeCap.Butt,
                )
            }
        }
    }
}

/**
 * What the lens is following: the face, and the point on it focus is aimed at. Drawn thin and
 * white, like everything else here - it is a sight, not a decoration, and it sits over the
 * photograph.
 */
@Composable
fun SubjectOverlay(subject: Subject, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val left = subject.left * size.width
        val top = subject.top * size.height
        val width = subject.width * size.width
        val height = subject.height * size.height
        val corner = minOf(width, height) * 0.22f
        val stroke = 1.5.dp.toPx()
        val ink = Color.White.copy(alpha = 0.8f)

        // Corners rather than a full rectangle: the same information, less of the picture hidden.
        fun corners(x: Float, y: Float, towardsX: Float, towardsY: Float) {
            drawLine(ink, Offset(x, y), Offset(x + corner * towardsX, y), stroke)
            drawLine(ink, Offset(x, y), Offset(x, y + corner * towardsY), stroke)
        }
        corners(left, top, 1f, 1f)
        corners(left + width, top, -1f, 1f)
        corners(left, top + height, 1f, -1f)
        corners(left + width, top + height, -1f, -1f)

        val focusX = subject.focusX * size.width
        val focusY = subject.focusY * size.height
        val radius = (if (subject.onEye) 9.dp else 14.dp).toPx()
        drawCircle(Color.White, radius, Offset(focusX, focusY), style = Stroke(stroke))
    }
}
