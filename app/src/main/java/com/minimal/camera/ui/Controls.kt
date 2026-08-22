package com.minimal.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small rounded button used for lenses, formats and control names. */
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
            .background(background, RoundedCornerShape(50))
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

/** Header used by every control panel: the name, an auto/manual switch and the current value. */
@Composable
fun ControlHeader(
    title: String,
    value: String,
    manual: Boolean,
    manualAvailable: Boolean,
    onManualChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Pill(text = "AUTO", selected = !manual, onClick = { onManualChange(false) })
        Pill(
            text = "MANUAL",
            selected = manual,
            enabled = manualAvailable,
            onClick = { onManualChange(true) },
        )
        Text(
            text = value,
            color = if (manual) Color.White else Color(0xCCFFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun ValueSlider(
    progress: Float,
    enabled: Boolean,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = progress,
        onValueChange = onProgressChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color(0x44FFFFFF),
            disabledThumbColor = Color(0x55FFFFFF),
            disabledActiveTrackColor = Color(0x33FFFFFF),
            disabledInactiveTrackColor = Color(0x22FFFFFF),
        ),
    )
}

@Composable
fun ShutterButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
                .padding(6.dp)
                .background(
                    color = if (busy) Color(0x66FFFFFF) else Color.White.copy(
                        alpha = if (enabled) 1f else 0.4f
                    ),
                    shape = CircleShape,
                )
                .clickable(enabled = enabled && !busy, onClick = onClick),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = Color.Black,
                strokeWidth = 3.dp,
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
