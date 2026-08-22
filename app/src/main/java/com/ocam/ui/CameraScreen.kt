package com.ocam.ui

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import androidx.compose.foundation.gestures.detectDragGestures
import kotlin.math.roundToInt

private const val DAYLIGHT_KELVIN = 5500
private const val TUNGSTEN_KELVIN = 3000

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
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                    Banner(
                        text = error ?: status.orEmpty(),
                        color = if (error != null) MaterialTheme.colorScheme.error else Color.White,
                    )
                }
            }
        }
        Controls(state, viewModel)
    }

    if (state.settingsOpen) {
        SettingsSheet(state = state, viewModel = viewModel, onClose = viewModel::closeSettings)
    }

    state.diagnostics?.let { report ->
        DiagnosticsSheet(
            text = report,
            onCopy = { viewModel.copiedDiagnostics() },
            onClose = viewModel::closeDiagnostics,
        )
    }
}

@Composable
private fun CameraPreview(state: CameraUiState, viewModel: CameraViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                            viewModel.tapPreview(
                                offset.x / size.width.toFloat(),
                                offset.y / size.height.toFloat(),
                            )
                        },
                        onDoubleTap = { viewModel.resetFocusPoint() },
                    )
                }
                .pointerInput(state.whiteBalanceAdjust) {
                    if (!state.whiteBalanceAdjust) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        viewModel.dragWhiteBalance(
                            drag.x / size.width.toFloat(),
                            drag.y / size.height.toFloat(),
                        )
                    }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // Hidden on purpose: a long press is out of the way in normal use, and the
                // report is only ever needed when something has gone wrong.
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { viewModel.openDiagnostics() })
                },
        ) {
            Text(
                text = liveReadout(state),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (lens != null) {
                Text(
                    text = "${lens.facingLabel} ${lens.zoomLabel} · ${lens.detailLabel}" +
                        if (lens.supportsRaw) " · raw" else "",
                    color = Color(0x99FFFFFF),
                    fontSize = 10.sp,
                )
            }
        }
        FlatButton(
            label = "ALL AUTO",
            active = !state.settings.anyManual,
            enabled = state.settings.anyManual,
            onClick = { viewModel.setEverythingManual(false) },
        )
    }
}

/**
 * Every parameter is on screen at once: a slider you can grab straight away, with the button that
 * hands it back to the camera directly underneath it.
 */
@Composable
private fun Controls(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val caps = state.capabilities

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ControlRow(
            label = "ISO",
            value = isoText(state),
            progress = isoProgress(state),
            manual = settings.manualExposure,
            available = caps.supportsManualIso,
            onProgress = { progress ->
                caps.isoRange?.let { range ->
                    viewModel.setIso(
                        fromLogProgress(progress, range.lower.toFloat(), range.upper.toFloat())
                            .roundToInt().coerceIn(range.lower, range.upper)
                    )
                }
            },
            onButton = viewModel::exposureAuto,
        )

        ControlRow(
            label = "SEC",
            value = shutterText(state),
            progress = shutterProgress(state),
            manual = settings.manualExposure,
            available = caps.supportsManualShutter,
            onProgress = { progress ->
                caps.exposureTimeRange?.let { range ->
                    viewModel.setExposureTime(
                        fromLogProgress(progress, range.lower.toFloat(), range.upper.toFloat())
                            .toLong().coerceIn(range.lower, range.upper)
                    )
                }
            },
            onButton = viewModel::exposureAuto,
        )

        ControlRow(
            label = "FOCUS",
            value = focusText(state),
            progress = focusProgress(state),
            manual = settings.manualFocus,
            available = caps.supportsManualFocus,
            showSlider = settings.manualFocus,
            onProgress = { progress -> viewModel.setFocusDiopters(progress * caps.minFocusDistance) },
            onButton = viewModel::focusAuto,
        )

        WhiteBalanceRow(state, viewModel)

        // Compensation only exists while the camera is metering, and it has no manual/auto of its
        // own - the button resets it to zero.
        val evRange = caps.exposureCompensationRange
        val evSpan = (evRange.upper - evRange.lower).coerceAtLeast(1)
        if (!settings.manualExposure && evSpan > 1) ControlRow(
            label = "EV",
            value = if (settings.manualExposure) "--"
            else formatExposureCompensation(
                settings.exposureCompensation,
                caps.exposureCompensationStep,
            ),
            progress = ((settings.exposureCompensation - evRange.lower).toFloat() / evSpan)
                .coerceIn(0f, 1f),
            manual = settings.exposureCompensation != 0 && !settings.manualExposure,
            available = !settings.manualExposure && evSpan > 1,
            onProgress = { progress ->
                viewModel.setExposureCompensation(
                    (evRange.lower + (progress * evSpan).roundToInt())
                        .coerceIn(evRange.lower, evRange.upper)
                )
            },
            buttonLabel = "0",
            buttonActive = settings.exposureCompensation == 0,
            onButton = { viewModel.setExposureCompensation(0) },
        )

        LensRow(state, viewModel)
        ShutterRow(state, viewModel)
    }
}

/**
 * White balance has three lights and a gesture instead of a slider: presets cover almost every
 * frame, and when they do not, dragging on the image itself is faster than aiming at a track.
 */
@Composable
private fun WhiteBalanceRow(state: CameraUiState, viewModel: CameraViewModel) {
    val settings = state.settings
    val available = state.capabilities.supportsManualWhiteBalance
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RowLabel("WB")
        FlatButton(
            label = "AUTO",
            active = !settings.manualWhiteBalance,
            onClick = viewModel::whiteBalanceAuto,
        )
        FlatButton(
            label = "DAY",
            active = settings.manualWhiteBalance && settings.kelvin == DAYLIGHT_KELVIN,
            enabled = available,
            onClick = { viewModel.setWhiteBalancePreset(DAYLIGHT_KELVIN) },
        )
        FlatButton(
            label = "TUNG",
            active = settings.manualWhiteBalance && settings.kelvin == TUNGSTEN_KELVIN,
            enabled = available,
            onClick = { viewModel.setWhiteBalancePreset(TUNGSTEN_KELVIN) },
        )
        FlatButton(
            label = "ADJ",
            active = state.whiteBalanceAdjust,
            enabled = available,
            onClick = viewModel::toggleWhiteBalanceAdjust,
        )
        Spacer(modifier = Modifier.weight(1f))
        RowValue(whiteBalanceText(state), highlighted = settings.manualWhiteBalance)
    }
}

private fun whiteBalanceText(state: CameraUiState): String {
    val settings = state.settings
    if (!settings.manualWhiteBalance) return "auto"
    val tint = settings.tint
    return formatKelvin(settings.kelvin) +
        if (kotlin.math.abs(tint) > 0.02f) " ${"%+.1f".format(tint)}" else ""
}

@Composable
private fun LensRow(state: CameraUiState, viewModel: CameraViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.lenses.forEach { lens ->
            // "!" marks a lens that looks like a helper sensor or has misbehaved here before.
            // It still opens, but only on a second tap.
            val risky = lens.warning != null || lens.id in state.troubled
            Pill(
                // Several cameras on one side can share a focal length, so the id is what
                // actually tells them apart.
                text = lens.zoomLabel,
                subtitle = "${lens.facingLabel} ${lens.id}" + if (risky) " !" else "",
                selected = lens.id == state.selectedLensId,
                onClick = { viewModel.selectLens(lens.id) },
            )
        }
    }
}

@Composable
private fun ShutterRow(state: CameraUiState, viewModel: CameraViewModel) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            FlatButton(
                label = state.settings.format.label,
                active = state.settings.format.writesRaw,
                onClick = viewModel::cycleFormat,
            )
        }
        ShutterButton(
            busy = state.busy,
            enabled = state.selectedLensId != null,
            onClick = viewModel::capture,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatButton(
                label = "FILES",
                active = false,
                onClick = { openPhotoFolder(context) },
            )
            FlatButton(
                label = "SET",
                active = state.settingsOpen,
                onClick = viewModel::openSettings,
            )
        }
    }
}

/**
 * Open the folder the photos went into. The documents provider knows it by path; where no file
 * browser answers that, fall back to whatever handles images.
 */
private fun openPhotoFolder(context: Context) {
    val folder = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_PICTURES}/oCam",
            ),
            DocumentsContract.Document.MIME_TYPE_DIR,
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val gallery = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(folder) }
        .recoverCatching { context.startActivity(gallery) }
}

// The sliders show what the camera is doing even while it is doing it itself, so switching to
// manual never makes the image jump.

private fun isoValue(state: CameraUiState): Int =
    if (state.settings.manualExposure) state.settings.iso
    else state.liveIso ?: state.settings.iso

private fun isoText(state: CameraUiState): String =
    if (state.settings.manualExposure) state.settings.iso.toString()
    else state.liveIso?.toString() ?: "auto"

private fun isoProgress(state: CameraUiState): Float {
    val range = state.capabilities.isoRange ?: return 0f
    return toLogProgress(isoValue(state).toFloat(), range.lower.toFloat(), range.upper.toFloat())
}

private fun shutterValue(state: CameraUiState): Long =
    if (state.settings.manualExposure) state.settings.exposureTimeNs
    else state.liveExposureNs ?: state.settings.exposureTimeNs

private fun shutterText(state: CameraUiState): String =
    if (state.settings.manualExposure) formatShutter(state.settings.exposureTimeNs)
    else state.liveExposureNs?.let(::formatShutter) ?: "auto"

private fun shutterProgress(state: CameraUiState): Float {
    val range = state.capabilities.exposureTimeRange ?: return 0f
    return toLogProgress(
        shutterValue(state).toFloat(),
        range.lower.toFloat(),
        range.upper.toFloat(),
    )
}

private fun focusText(state: CameraUiState): String = when {
    !state.capabilities.supportsManualFocus -> "fixed"
    state.settings.manualFocus -> formatFocus(state.settings.focusDiopters)
    else -> state.liveFocusDiopters?.let(::formatFocus) ?: "auto"
}

private fun focusProgress(state: CameraUiState): Float {
    val closest = state.capabilities.minFocusDistance
    if (closest <= 0f) return 0f
    val diopters = if (state.settings.manualFocus) state.settings.focusDiopters
    else state.liveFocusDiopters ?: state.settings.focusDiopters
    return (diopters / closest).coerceIn(0f, 1f)
}

private fun liveReadout(state: CameraUiState): String {
    val settings = state.settings
    val parts = listOfNotNull(
        state.liveIso?.let { "ISO $it" },
        state.liveExposureNs?.let(::formatShutter),
        formatAperture(state.liveAperture),
        state.liveFocusDiopters?.let(::formatFocus),
        if (settings.manualWhiteBalance) formatKelvin(settings.kelvin) else "AWB",
    )
    return if (parts.isEmpty()) "…" else parts.joinToString(" · ")
}
