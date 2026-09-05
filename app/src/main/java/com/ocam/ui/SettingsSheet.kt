package com.ocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import com.ocam.appVersion
import com.ocam.camera.Lens

/**
 * The standing choices: which file types to write, what to do about the lens, and what to make of
 * this phone's cameras. The per-shot choice stays on the camera screen; this only decides what it
 * may offer.
 */
@Composable
fun SettingsSheet(state: CameraUiState, viewModel: CameraViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C), RoundedCornerShape(10.dp))
                // Long on a phone with eight cameras, and short on one with two.
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Formats to save",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            ToggleRow(
                name = "JPEG",
                note = "Works everywhere",
                enabled = state.saveJpeg,
                available = true,
                onChange = viewModel::setSaveJpeg,
            )
            ToggleRow(
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
            ToggleRow(
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

            Text(
                text = "Geometry",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
            )
            ToggleRow(
                name = "UNDISTORT",
                note = if (state.capabilities.supportsUndistort) {
                    "Straightens the lens on JPEG and HEIC. DNG is never touched."
                } else {
                    "This lens has no correction block"
                },
                enabled = state.settings.undistort,
                available = state.capabilities.supportsUndistort,
                onChange = viewModel::setUndistort,
            )

            Text(
                text = "Lenses",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
            )
            Text(
                text = "Every camera this phone answers for, including the ones that are not " +
                    "photo cameras. Switch off what does not belong in the picker and name the " +
                    "rest whatever you call them.",
                color = Color(0x88FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val lastOneLeft = state.lenses.count { it.id !in state.hiddenLenses } <= 1
            state.lenses.forEach { lens ->
                val hidden = lens.id in state.hiddenLenses
                LensRow(
                    lens = lens,
                    name = state.lensNames[lens.id].orEmpty(),
                    hidden = hidden,
                    // Something has to stay in the picker, so the last one left cannot go.
                    canHide = hidden || !lastOneLeft,
                    troubled = lens.id in state.troubled,
                    onHidden = { wanted -> viewModel.setLensHidden(lens.id, wanted) },
                    onName = { name -> viewModel.setLensName(lens.id, name) },
                )
            }

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
private fun ToggleRow(
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

/**
 * One camera, as the owner may redefine it: a switch that decides whether the picker offers it
 * at all, and a name to replace what the phone calls it. Everything the app knows about it stays
 * on the line underneath, because that is what tells a wide-angle lens from a depth sensor.
 */
@Composable
private fun LensRow(
    lens: Lens,
    name: String,
    hidden: Boolean,
    canHide: Boolean,
    troubled: Boolean,
    onHidden: (Boolean) -> Unit,
    onName: (String) -> Unit,
) {
    // Held locally while typing. Keyed on the lens rather than on the stored name, because
    // re-keying on what was just typed would move the cursor back to the end on every letter.
    var draft by remember(lens.id) { mutableStateOf(name) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlatButton(
            label = if (hidden) "OFF" else "ON",
            active = !hidden,
            enabled = canHide,
            onClick = { onHidden(!hidden) },
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { typed ->
                    draft = typed.take(16)
                    onName(draft)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (hidden) Color(0x66FFFFFF) else Color.White,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { field ->
                    Box(
                        modifier = Modifier
                            // Wide enough to aim at when it is still empty.
                            .widthIn(min = 130.dp)
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(2.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = lens.zoomLabel,
                                color = Color(0x55FFFFFF),
                                fontSize = 13.sp,
                            )
                        }
                        field()
                    }
                },
            )
            Text(
                text = buildString {
                    append(lens.facingLabel).append(" · id ").append(lens.id)
                    append(" · ").append(lens.detailLabel.substringBefore(" · "))
                    if (lens.supportsRaw) append(" · raw")
                    if (troubled) append(" · went wrong here")
                    lens.warning?.let { append(" · ").append(it) }
                },
                color = Color(0x77FFFFFF),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
