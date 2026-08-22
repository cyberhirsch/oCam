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
import androidx.compose.ui.unit.dp

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
