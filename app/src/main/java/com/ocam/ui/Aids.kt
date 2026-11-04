package com.ocam.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ocam.camera.CropFrame
import com.ocam.camera.GridMode
import com.ocam.sequence.SequenceProgress
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

/** Within this many degrees of a horizon the level counts as level. */
private const val LEVEL_TOLERANCE = 0.7f

/**
 * Peaking and false colour, drawn over the preview. The sample is in the buffer's coordinates,
 * stretched over the view, and then given the view's own transform - the same two steps that put
 * the preview where it is - so the marks land on the edges they were found on, upright or not.
 */
@Composable
fun AnalysisOverlay(frame: FrameStats, modifier: Modifier = Modifier) {
    val image = frame.overlay ?: return
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier = modifier) {
        if (image.isRecycled) return@Canvas
        val matrix = Matrix().apply {
            setScale(size.width / image.width, size.height / image.height)
            frame.overlayTransform?.let { postConcat(it) }
        }
        drawContext.canvas.nativeCanvas.drawBitmap(image, matrix, paint)
    }
}

/**
 * Composition lines, and the mask of a proportion to compose for. The mask darkens what a crop to
 * that proportion would cut; the grid then divides what is left, because that is the picture
 * being composed.
 */
@Composable
fun FramingOverlay(grid: GridMode, frame: CropFrame, modifier: Modifier = Modifier) {
    if (grid == GridMode.OFF && frame.ratio == null) return
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        val ink = Color.White.copy(alpha = 0.45f)

        var left = 0f
        var top = 0f
        var width = size.width
        var height = size.height
        frame.ratio?.let { long ->
            // The proportion turns with the frame: 16:9 on a frame held upright is 9:16.
            val target = if (size.width >= size.height) long else 1f / long
            if (size.width / size.height > target) {
                width = size.height * target
                left = (size.width - width) / 2f
            } else {
                height = size.width / target
                top = (size.height - height) / 2f
            }
            val mask = Color.Black.copy(alpha = 0.55f)
            drawRect(mask, Offset.Zero, Size(size.width, top))
            drawRect(mask, Offset(0f, top + height), Size(size.width, size.height - top - height))
            drawRect(mask, Offset(0f, top), Size(left, height))
            drawRect(mask, Offset(left + width, top), Size(size.width - left - width, height))
            drawRect(Color.White.copy(alpha = 0.7f), Offset(left, top), Size(width, height), style = Stroke(stroke))
        }

        when (grid) {
            GridMode.OFF -> Unit
            GridMode.THIRDS -> for (step in 1..2) {
                val x = left + width * step / 3f
                val y = top + height * step / 3f
                drawLine(ink, Offset(x, top), Offset(x, top + height), stroke)
                drawLine(ink, Offset(left, y), Offset(left + width, y), stroke)
            }
            GridMode.CROSS -> {
                val cx = left + width / 2f
                val cy = top + height / 2f
                val arm = minOf(width, height) * 0.06f
                drawLine(ink, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
                drawLine(ink, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)
            }
        }
    }
}

/**
 * A horizon that stays level with the world. Gravity is read in the device's axes, turned into
 * the screen's for however the display is rotated, and the line is drawn across it at that
 * angle. It snaps to whichever edge of the frame is nearest horizontal, so it works held either
 * way up.
 */
@Composable
fun LevelOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var angle by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothX = 0f
        var smoothY = 9.81f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val dx = event.values[0]
                val dy = event.values[1]
                // Device axes into screen axes, for the rotation the display is in.
                val (sx, sy) = when (view.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> -dy to dx
                    Surface.ROTATION_180 -> -dx to -dy
                    Surface.ROTATION_270 -> dy to -dx
                    else -> dx to dy
                }
                // A little smoothing: a hand shakes, the horizon does not.
                smoothX += (sx - smoothX) * 0.2f
                smoothY += (sy - smoothY) * 0.2f
                angle = Math.toDegrees(atan2(smoothX, smoothY).toDouble()).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensors?.unregisterListener(listener) }
    }

    // How far off the nearest horizon, whichever edge of the frame that is.
    val tilt = ((angle % 90f) + 135f) % 90f - 45f
    val level = abs(tilt) < LEVEL_TOLERANCE

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val reach = minOf(size.width, size.height) * 0.32f
            val gap = 14.dp.toPx()
            val ink = if (level) Color.White else Color.White.copy(alpha = 0.6f)
            val stroke = (if (level) 2.dp else 1.dp).toPx()

            // Fixed ticks along the frame edge nearest the horizon, so the gap to level is visible.
            val tick = 10.dp.toPx()
            val faint = Color.White.copy(alpha = 0.35f)
            rotate(degrees = angle - tilt, pivot = center) {
                drawLine(faint, Offset(center.x - reach - tick, center.y), Offset(center.x - reach, center.y), stroke)
                drawLine(faint, Offset(center.x + reach, center.y), Offset(center.x + reach + tick, center.y), stroke)
            }

            rotate(degrees = angle, pivot = center) {
                drawLine(ink, Offset(center.x - reach, center.y), Offset(center.x - gap, center.y), stroke)
                drawLine(ink, Offset(center.x + gap, center.y), Offset(center.x + reach, center.y), stroke)
            }
        }
        if (!level) {
            Text(
                text = String.format(Locale.US, "%.1f°", tilt),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 26.dp),
            )
        }
    }
}

/**
 * Where the frame sits against a mid-grey average, three stops either way. A reading, not a
 * correction: nothing moves the exposure but the sliders.
 */
@Composable
fun MeterScale(offset: Float?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = offset?.let { String.format(Locale.US, "%+.1f EV", it) } ?: "-- EV",
            color = Color(0xA6FFFFFF),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.End),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color(0x40000000), RoundedCornerShape(2.dp)),
        ) {
            val stroke = 1.dp.toPx()
            for (stop in -3..3) {
                val x = size.width * (stop + 3) / 6f
                val tall = if (stop == 0) size.height else size.height * 0.45f
                drawLine(Color.White.copy(alpha = 0.45f), Offset(x, size.height - tall), Offset(x, size.height), stroke)
            }
            offset?.let {
                val x = size.width * (it.coerceIn(-3f, 3f) + 3f) / 6f
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
    }
}

/** The self-timer's seconds, large and in the middle, where the eye already is. */
@Composable
fun CountdownOverlay(seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            color = Color.White,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 26.dp),
        )
    }
}

/**
 * The picture being built, over the live frame, and how far along it is. While held the live frame
 * shows through it, so whoever is walking through can see where they are against the light so far.
 */
@Composable
fun SequenceOverlay(progress: SequenceProgress, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Box(modifier = modifier) {
        progress.picture?.let { image ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val degrees = when (view.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                // The frames are the sensor's own, so they are turned the way the camera's
                // preview is: the platform's rule for it, mirror first where the lens faces you.
                val turn = if (progress.mirrored) {
                    (360 - (progress.sensorOrientation + degrees) % 360) % 360
                } else {
                    (progress.sensorOrientation - degrees + 360) % 360
                }
                val across = if (turn % 180 == 0) image.width else image.height
                val down = if (turn % 180 == 0) image.height else image.width
                val matrix = Matrix().apply {
                    postTranslate(-image.width / 2f, -image.height / 2f)
                    if (progress.mirrored) postScale(-1f, 1f)
                    postRotate(turn.toFloat())
                    postScale(size.width / across, size.height / down)
                    postTranslate(size.width / 2f, size.height / 2f)
                }
                paint.alpha = if (progress.paused) 140 else 255
                drawContext.canvas.nativeCanvas.drawBitmap(image, matrix, paint)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sequenceText(progress),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (progress.holds) {
                FlatButton(
                    label = if (progress.paused) "GO ON" else "HOLD",
                    active = progress.paused,
                    onClick = onHold,
                )
            }
        }
    }
}

private fun sequenceText(progress: SequenceProgress): String {
    progress.note?.let { return "${progress.mode.label} · $it" }
    val seconds = progress.exposedNs / 1_000_000_000.0
    val time = progress.targetNs?.let {
        String.format(Locale.US, "%.1f / %d s", seconds, it / 1_000_000_000L)
    } ?: String.format(Locale.US, "%.1f s", seconds)
    return "${progress.mode.label} · ${progress.frames} · $time"
}
