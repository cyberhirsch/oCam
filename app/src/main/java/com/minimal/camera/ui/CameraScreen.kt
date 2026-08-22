package com.minimal.camera.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimal.camera.CameraUiState
import com.minimal.camera.CameraViewModel
import com.minimal.camera.Control
import com.minimal.camera.camera.CaptureFormat
import kotlin.math.roundToInt

@Composable
fun CameraScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        TopStrip(state, viewModel)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraPreview(state, viewModel)
            val error = state.error
            val status = state.status
            if (error != null || status != null) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                ) {
                    Banner(
                        text = error ?: status.orEmpty(),
                        color = if (error != null) MaterialTheme.colorScheme.error else Color.White,
                    )
                }
            }
        }
        BottomControls(state, viewModel)
    }
}

@Composable
private fun CameraPreview(state: CameraUiState, viewModel: CameraViewModel) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val aspect = state.previewAspect.coerceIn(0.2f, 5f)
        val width: Dp
        val height: Dp
        if (maxWidth / aspect <= maxHeight) {
            width = maxWidth
            height = maxWidth / aspect
        } else {
            height = maxHeight
            width = maxHeight * aspect
        }

        AndroidView(
            modifier = Modifier
                .size(width, height)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            viewModel.focusAt(
                                offset.x / size.width.toFloat(),
                                offset.y / size.height.toFloat(),
                            )
                        },
                        onDoubleTap = { viewModel.resetFocusPoint() },
                    )
                },
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = viewModel.onSurfaceAvailable(texture)

                        override fun onSurfaceTextureSizeChanged(
                            texture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            viewModel.onSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }
}

@Composable
private fun TopStrip(state: CameraUiState, viewModel: CameraViewModel) {
    val lens = state.selectedLens
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = liveReadout(state),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (lens != null) {
                Text(
                    text = "${lens.facingLabel} ${lens.zoomLabel} · ${lens.detailLabel}",
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                )
            }
        }
        Pill(
            text = if (state.everythingManual) "MANUAL" else if (state.settings.anyManual) "MIXED" else "AUTO",
            selected = state.settings.anyManual,
            onClick = { viewModel.setEverythingManual(!state.everythingManual) },
        )
    }
}

@Composable
private fun BottomControls(state: CameraUiState, viewModel: CameraViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.openControl?.let { control -> ControlPanel(control, state, viewModel) }
        ControlChips(state, viewModel)
        LensRow(state, viewModel)
        ShutterRow(state, viewModel)
    }
}

@Composable
private fun ControlChips(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val caps = state.capabilities
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(
            text = "ISO",
            subtitle = if (settings.manualExposure) settings.iso.toString()
            else state.liveIso?.toString() ?: "auto",
            selected = state.openControl == Control.ISO,
            onClick = { viewModel.openControl(Control.ISO) },
        )
        Pill(
            text = "SHUTTER",
            subtitle = if (settings.manualExposure) formatShutter(settings.exposureTimeNs)
            else state.liveExposureNs?.let(::formatShutter) ?: "auto",
            selected = state.openControl == Control.SHUTTER,
            onClick = { viewModel.openControl(Control.SHUTTER) },
        )
        Pill(
            text = "FOCUS",
            subtitle = when {
                !caps.supportsManualFocus -> "fixed"
                settings.manualFocus -> formatFocus(settings.focusDiopters)
                else -> "auto"
            },
            selected = state.openControl == Control.FOCUS,
            onClick = { viewModel.openControl(Control.FOCUS) },
        )
        Pill(
            text = "WB",
            subtitle = if (settings.manualWhiteBalance) formatKelvin(settings.kelvin) else "auto",
            selected = state.openControl == Control.WHITE_BALANCE,
            onClick = { viewModel.openControl(Control.WHITE_BALANCE) },
        )
        Pill(
            text = "EV",
            subtitle = if (settings.manualExposure) "off"
            else formatExposureCompensation(
                settings.exposureCompensation,
                caps.exposureCompensationStep,
            ),
            selected = state.openControl == Control.EV,
            onClick = { viewModel.openControl(Control.EV) },
        )
    }
}

@Composable
private fun ControlPanel(control: Control, state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val caps = state.capabilities
    Column(modifier = Modifier.fillMaxWidth()) {
        when (control) {
            Control.ISO -> {
                val range = caps.isoRange
                ControlHeader(
                    title = "ISO",
                    value = if (settings.manualExposure) settings.iso.toString()
                    else state.liveIso?.toString() ?: "--",
                    manual = settings.manualExposure,
                    manualAvailable = caps.supportsManualIso,
                    onManualChange = viewModel::setManualExposure,
                )
                if (range != null) {
                    ValueSlider(
                        progress = toLogProgress(
                            settings.iso.toFloat(),
                            range.lower.toFloat(),
                            range.upper.toFloat(),
                        ),
                        enabled = settings.manualExposure,
                        onProgressChange = { progress ->
                            viewModel.setIso(
                                fromLogProgress(
                                    progress,
                                    range.lower.toFloat(),
                                    range.upper.toFloat(),
                                ).roundToInt().coerceIn(range.lower, range.upper)
                            )
                        },
                    )
                    Hint("${range.lower} – ${range.upper}")
                } else {
                    Hint("This lens does not report a sensitivity range")
                }
            }

            Control.SHUTTER -> {
                val range = caps.exposureTimeRange
                ControlHeader(
                    title = "SHUTTER",
                    value = if (settings.manualExposure) formatShutter(settings.exposureTimeNs)
                    else state.liveExposureNs?.let(::formatShutter) ?: "--",
                    manual = settings.manualExposure,
                    manualAvailable = caps.supportsManualShutter,
                    onManualChange = viewModel::setManualExposure,
                )
                if (range != null) {
                    ValueSlider(
                        progress = toLogProgress(
                            settings.exposureTimeNs.toFloat(),
                            range.lower.toFloat(),
                            range.upper.toFloat(),
                        ),
                        enabled = settings.manualExposure,
                        onProgressChange = { progress ->
                            viewModel.setExposureTime(
                                fromLogProgress(
                                    progress,
                                    range.lower.toFloat(),
                                    range.upper.toFloat(),
                                ).toLong().coerceIn(range.lower, range.upper)
                            )
                        },
                    )
                    Hint("${formatShutter(range.lower)} – ${formatShutter(range.upper)}")
                } else {
                    Hint("This lens does not report an exposure time range")
                }
            }

            Control.FOCUS -> {
                ControlHeader(
                    title = "FOCUS",
                    value = if (settings.manualFocus) formatFocus(settings.focusDiopters)
                    else state.liveFocusDiopters?.let(::formatFocus) ?: "--",
                    manual = settings.manualFocus,
                    manualAvailable = caps.supportsManualFocus,
                    onManualChange = viewModel::setManualFocus,
                )
                if (caps.supportsManualFocus) {
                    ValueSlider(
                        progress = (settings.focusDiopters / caps.minFocusDistance)
                            .coerceIn(0f, 1f),
                        enabled = settings.manualFocus,
                        onProgressChange = { progress ->
                            viewModel.setFocusDiopters(progress * caps.minFocusDistance)
                        },
                    )
                    Hint("∞ – ${formatFocus(caps.minFocusDistance)} · tap the preview for autofocus, double tap to reset")
                } else {
                    Hint("This lens is fixed focus")
                }
            }

            Control.WHITE_BALANCE -> {
                ControlHeader(
                    title = "WHITE BALANCE",
                    value = if (settings.manualWhiteBalance) formatKelvin(settings.kelvin) else "auto",
                    manual = settings.manualWhiteBalance,
                    manualAvailable = caps.supportsManualWhiteBalance,
                    onManualChange = viewModel::setManualWhiteBalance,
                )
                ValueSlider(
                    progress = ((settings.kelvin - MIN_KELVIN).toFloat() /
                        (MAX_KELVIN - MIN_KELVIN)).coerceIn(0f, 1f),
                    enabled = settings.manualWhiteBalance,
                    onProgressChange = { progress ->
                        val kelvin = MIN_KELVIN + (progress * (MAX_KELVIN - MIN_KELVIN)).roundToInt()
                        viewModel.setKelvin((kelvin / 100) * 100)
                    },
                )
                Hint("${MIN_KELVIN}K – ${MAX_KELVIN}K · warm light needs a low number")
            }

            Control.EV -> {
                val range = caps.exposureCompensationRange
                ControlHeader(
                    title = "EXPOSURE COMPENSATION",
                    value = formatExposureCompensation(
                        settings.exposureCompensation,
                        caps.exposureCompensationStep,
                    ),
                    manual = settings.manualExposure,
                    manualAvailable = caps.supportsManualSensor,
                    onManualChange = viewModel::setManualExposure,
                )
                val span = (range.upper - range.lower).coerceAtLeast(1)
                ValueSlider(
                    progress = ((settings.exposureCompensation - range.lower).toFloat() / span)
                        .coerceIn(0f, 1f),
                    enabled = !settings.manualExposure && span > 1,
                    onProgressChange = { progress ->
                        viewModel.setExposureCompensation(
                            (range.lower + (progress * span).roundToInt())
                                .coerceIn(range.lower, range.upper)
                        )
                    },
                )
                Hint(
                    if (settings.manualExposure) {
                        "Exposure is manual - compensation only applies to auto exposure"
                    } else {
                        formatExposureCompensation(range.lower, caps.exposureCompensationStep) +
                            " – " +
                            formatExposureCompensation(range.upper, caps.exposureCompensationStep)
                    }
                )
            }
        }
    }
}

@Composable
private fun LensRow(state: CameraUiState, viewModel: CameraViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.lenses.forEach { lens ->
            Pill(
                text = lens.zoomLabel,
                subtitle = "${lens.facingLabel}${if (lens.supportsRaw) " · raw" else ""}",
                selected = lens.id == state.selectedLensId,
                onClick = { viewModel.selectLens(lens.id) },
            )
        }
    }
}

@Composable
private fun ShutterRow(state: CameraUiState, viewModel: CameraViewModel) {
    val caps = state.capabilities
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Pill(
                text = state.settings.format.label,
                subtitle = "format",
                selected = state.settings.format.writesRaw,
                onClick = { viewModel.setFormat(nextFormat(state.settings.format, caps.supportsRaw)) },
            )
        }
        ShutterButton(
            busy = state.busy,
            enabled = state.selectedLensId != null,
            onClick = viewModel::capture,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = listOfNotNull(
                    if (caps.supportsRaw) "RAW" else "no RAW",
                    if (caps.supportsManualSensor) "manual" else "auto only",
                ).joinToString(" · "),
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, color = Color(0x88FFFFFF), fontSize = 11.sp)
}

private const val MIN_KELVIN = 2000
private const val MAX_KELVIN = 10000

private fun nextFormat(current: CaptureFormat, rawAvailable: Boolean): CaptureFormat {
    if (!rawAvailable) return CaptureFormat.JPEG
    return when (current) {
        CaptureFormat.JPEG -> CaptureFormat.RAW
        CaptureFormat.RAW -> CaptureFormat.RAW_JPEG
        CaptureFormat.RAW_JPEG -> CaptureFormat.JPEG
    }
}

private fun liveReadout(state: CameraUiState): String {
    val settings = state.settings
    val iso = state.liveIso ?: settings.iso.takeIf { settings.manualExposure }
    val exposure = state.liveExposureNs ?: settings.exposureTimeNs.takeIf { settings.manualExposure }
    val focus = state.liveFocusDiopters ?: settings.focusDiopters.takeIf { settings.manualFocus }
    val parts = listOfNotNull(
        iso?.let { "ISO $it" },
        exposure?.let(::formatShutter),
        formatAperture(state.liveAperture),
        focus?.let(::formatFocus),
        if (settings.manualWhiteBalance) formatKelvin(settings.kelvin) else "AWB",
    )
    return if (parts.isEmpty()) "starting…" else parts.joinToString(" · ")
}
