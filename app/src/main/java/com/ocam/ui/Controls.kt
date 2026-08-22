package com.ocam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A thin always-visible slider. Touching it anywhere jumps there, so a value is never more than
 * one gesture away - and any touch means "I am setting this myself", which is what switches the
 * control out of auto.
 */
@Composable
fun ThinSlider(
    progress: Float,
    manual: Boolean,
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(26.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onProgress((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onProgress((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val track = 2.dp.toPx()
            val x = clamped * size.width
            val radius = if (manual) 7.dp.toPx() else 5.dp.toPx()

            val rail = Color.White.copy(alpha = if (enabled) 0.20f else 0.08f)
            val ink = when {
                !enabled -> Color.White.copy(alpha = 0.15f)
                manual -> Color.White
                else -> Color.White.copy(alpha = 0.45f)
            }

            drawLine(rail, Offset(0f, centerY), Offset(size.width, centerY), track, StrokeCap.Round)
            drawLine(ink, Offset(0f, centerY), Offset(x, centerY), track, StrokeCap.Round)

            if (manual) {
                drawCircle(ink, radius, Offset(x, centerY))
            } else {
                // Hollow thumb: the camera is choosing this value, not you.
                drawCircle(Color.Black, radius, Offset(x, centerY))
                drawCircle(ink, radius, Offset(x, centerY), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Small flat rectangle. Filled means "this is the state you are in". */
@Composable
fun FlatButton(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        !enabled -> Color(0x0DFFFFFF)
        active -> Color.White
        else -> Color(0x1FFFFFFF)
    }
    val foreground = when {
        !enabled -> Color(0x40FFFFFF)
        active -> Color.Black
        else -> Color(0xCCFFFFFF)
    }
    Text(
        text = label,
        color = foreground,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/**
 * One camera parameter on one line: name, the button that hands it back to the camera, the
 * slider, and the value it currently has. The choice is only ever "press the button" or "move
 * the slider" - never a menu.
 */
@Composable
fun ControlRow(
    label: String,
    value: String,
    progress: Float,
    manual: Boolean,
    available: Boolean,
    onProgress: (Float) -> Unit,
    buttonLabel: String = "AUTO",
    buttonActive: Boolean = !manual,
    showSlider: Boolean = true,
    onButton: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label)
        FlatButton(
            label = buttonLabel,
            active = buttonActive,
            enabled = available,
            onClick = onButton,
        )
        if (showSlider) {
            ThinSlider(
                progress = progress,
                manual = manual,
                enabled = available,
                onProgress = onProgress,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        RowValue(value, highlighted = manual && available)
    }
}

@Composable
fun RowLabel(label: String) {
    Text(
        text = label,
        color = Color(0x73FFFFFF),
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.width(38.dp),
    )
}

@Composable
fun RowValue(value: String, highlighted: Boolean) {
    Text(
        text = value,
        color = if (highlighted) Color.White else Color(0xA6FFFFFF),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.End,
        modifier = Modifier.width(62.dp),
    )
}

/** Small rounded button used for lenses and the capture format. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> Color(0x14FFFFFF)
        selected -> Color.White
        else -> Color(0x33FFFFFF)
    }
    val foreground = when {
        !enabled -> Color(0x55FFFFFF)
        selected -> Color.Black
        else -> Color.White
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (subtitle != null) {
            Text(text = subtitle, color = foreground.copy(alpha = 0.7f), fontSize = 9.sp)
        }
    }
}

/** A ring, not a disc: the frame stays visible through the middle of the control. */
@Composable
fun ShutterButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .semantics { contentDescription = "Shutter" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.5.dp.toPx()
            val colour = when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                busy -> Color.White.copy(alpha = 0.45f)
                else -> Color.White
            }
            drawCircle(
                color = colour,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(stroke),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
fun Banner(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
