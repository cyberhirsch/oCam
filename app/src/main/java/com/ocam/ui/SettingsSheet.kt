package com.ocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import com.ocam.appVersion

/**
 * The standing choice of which file types to write. The per-shot choice stays on the camera
 * screen: this only decides what that button is allowed to offer.
 */
@Composable
fun SettingsSheet(state: CameraUiState, viewModel: CameraViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C), RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Formats to save",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            FormatToggle(
                name = "JPEG",
                note = "Works everywhere",
                enabled = state.saveJpeg,
                available = true,
                onChange = viewModel::setSaveJpeg,
            )
            FormatToggle(
                name = "HEIC",
                note = if (state.capabilities.supportsHeic) {
                    "Half the size of JPEG, 10 bit"
                } else {
                    "This lens cannot encode it"
                },
                enabled = state.saveHeic,
                available = state.capabilities.supportsHeic,
                onChange = viewModel::setSaveHeic,
            )
            FormatToggle(
                name = "DNG",
                note = if (state.capabilities.supportsRaw) {
                    "Raw sensor data, large"
                } else {
                    "This lens has no raw output"
                },
                enabled = state.saveRaw,
                available = state.capabilities.supportsRaw,
                onChange = viewModel::setSaveRaw,
            )

            Text(
                text = "The shutter button on the camera screen steps through the combinations " +
                    "these allow: " +
                    state.formatChoices.joinToString(", ") { it.label },
                color = Color(0x88FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Which build this is, on the phone itself: the version and the commit it was
                // built from, the same string the downloaded file is named after.
                Text(
                    text = "oCam ${appVersion(context)}",
                    color = Color(0x88FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                FlatButton(label = "CLOSE", active = false, onClick = onClose)
            }
        }
    }
}

@Composable
private fun FormatToggle(
    name: String,
    note: String,
    enabled: Boolean,
    available: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatButton(
            label = if (enabled && available) "ON" else "OFF",
            active = enabled && available,
            enabled = available,
            onClick = { onChange(!enabled) },
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = name,
                color = if (available) Color.White else Color(0x66FFFFFF),
                fontSize = 13.sp,
            )
            Text(text = note, color = Color(0x77FFFFFF), fontSize = 10.sp)
        }
    }
}
