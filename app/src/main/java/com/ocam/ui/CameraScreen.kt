package com.ocam.ui

import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val DAYLIGHT_KELVIN = 5500
private const val TUNGSTEN_KELVIN = 3000

/** How often the preview is read back for the histogram and the clipping warning. */
private const val SAMPLE_INTERVAL_MS = 200L

@Composable
fun CameraScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frame by remember { mutableStateOf(FrameStats.EMPTY) }

    // Nothing this app draws ever touches the preview pixels, so the only way to know what the
    // frame contains is to read a small copy of it back a few times a second.
    LaunchedEffect(state.selectedLensId) {
        while (true) {
            delay(SAMPLE_INTERVAL_MS)
            val view = textureView?.takeIf { it.isAvailable } ?: continue
            val bitmap = runCatching { view.getBitmap(120, 160) }.getOrNull() ?: continue
            frame = withContext(Dispatchers.Default) { analyseFrame(bitmap) }
            bitmap.recycle()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // Upright the controls sit under the frame; on its side they sit beside it. Same parts,
        // same order, so nothing has to be learned twice.
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopStrip(state, viewModel)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        PreviewArea(state, viewModel, frame, landscape) { textureView = it }
                    }
                    LandscapeControls(state, viewModel, frame)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopStrip(state, viewModel)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewArea(state, viewModel, frame, landscape) { textureView = it }
                }
                Controls(state, viewModel, frame)
            }
        }
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

/**
 * On its side the sliders stand up and the shutter keeps the middle of the outer edge - the same
 * place under the same thumb as the bottom centre when the phone is upright.
 */
@Composable
private fun LandscapeControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    frame: FrameStats,
) {
    val context = LocalContext.current
    val settings = state.settings
    val caps = state.capabilities
    val sliderHeight = 150.dp

    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.width(210.dp).fillMaxHeight().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                VerticalControl(
                    label = "ISO",
                    value = isoText(state),
                    progress = isoProgress(state),
                    manual = settings.manualExposure,
                    available = caps.supportsManualIso,
                    sliderHeight = sliderHeight,
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
                VerticalControl(
                    label = "SEC",
                    value = shutterText(state),
                    progress = shutterProgress(state),
                    manual = settings.manualExposure,
                    available = caps.supportsManualShutter,
                    sliderHeight = sliderHeight,
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
                val evRange = caps.exposureCompensationRange
                val evSpan = (evRange.upper - evRange.lower).coerceAtLeast(1)
                if (!settings.manualExposure && evSpan > 1) {
                    VerticalControl(
                        label = "EV",
                        value = formatExposureCompensation(
                            settings.exposureCompensation,
                            caps.exposureCompensationStep,
                        ),
                        progress = ((settings.exposureCompensation - evRange.lower).toFloat() / evSpan)
                            .coerceIn(0f, 1f),
                        manual = settings.exposureCompensation != 0,
                        available = true,
                        sliderHeight = sliderHeight,
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
                }
            }

            Row(
                modifier = Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.lenses.forEach { lens ->
                    val risky = lens.warning != null || lens.id in state.troubled
                    Pill(
                        text = lens.zoomLabel,
                        subtitle = "${lens.facingLabel} ${lens.id}" + if (risky) " !" else "",
                        selected = lens.id == state.selectedLensId,
                        onClick = { viewModel.selectLens(lens.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlatButton(
                    label = state.settings.format.label,
                    active = state.settings.format.writesRaw,
                    onClick = viewModel::cycleFormat,
                )
                FlatButton(label = "SET", active = state.settingsOpen, onClick = viewModel::openSettings)
                FlatButton(label = "FILES", active = false, onClick = { openPhotoFolder(context) })
            }
        }

        Box(modifier = Modifier.width(110.dp).fillMaxHeight()) {
            FlatButton(
                label = "ZEBRA",
                active = state.zebra,
                onClick = viewModel::toggleZebra,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )
            ShutterButton(
                busy = state.busy,
                enabled = state.selectedLensId != null,
                onClick = viewModel::capture,
                modifier = Modifier.align(Alignment.Center),
            )
            Histogram(
                stats = frame,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            )
        }
    }
}

/** The frame plus everything that lives along its edges. */
@Composable
private fun PreviewArea(
    state: CameraUiState,
    viewModel: CameraViewModel,
    frame: FrameStats,
    landscape: Boolean,
    onTextureView: (TextureView?) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(state, viewModel, frame, landscape, onTextureView)

        WhiteBalanceColumn(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
        )
        FocusColumn(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
        )

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
}

@Composable
private fun CameraPreview(
    state: CameraUiState,
    viewModel: CameraViewModel,
    frame: FrameStats,
    landscape: Boolean,
    onTextureView: (TextureView?) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Fit, never fill: the box takes the frame's own proportions, so the whole picture is
        // on screen and the black bars fall outside it.
        val buffer = state.previewWidth.toFloat() / state.previewHeight.toFloat()
        val aspect = (if (landscape) buffer else 1f / buffer).coerceIn(0.2f, 5f)
        val width: Dp
        val height: Dp
        if (maxWidth / aspect <= maxHeight) {
            width = maxWidth
            height = maxWidth / aspect
        } else {
            height = maxHeight
            width = maxHeight * aspect
        }

        Box(modifier = Modifier.size(width, height)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
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
                update = { view ->
                    view.post {
                        applyPreviewTransform(
                            view = view,
                            bufferWidth = state.previewWidth,
                            bufferHeight = state.previewHeight,
                            rotation = ContextCompat.getDisplayOrDefault(context).rotation,
                        )
                    }
                },
                factory = { context ->
                    TextureView(context).apply {
                        onTextureView(this)
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

                            override fun onSurfaceTextureDestroyed(
                                texture: SurfaceTexture,
                            ): Boolean {
                                viewModel.onSurfaceDestroyed()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
            )
            if (state.zebra) {
                ZebraOverlay(stats = frame, modifier = Modifier.fillMaxSize())
            }
        }
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

/** Focus lives on the right edge of the frame, running from infinity down to as close as it goes. */
@Composable
private fun FocusColumn(
    state: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val caps = state.capabilities
    Column(
        modifier = modifier
            .background(Color(0x40000000), RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = focusText(state), color = Color(0xCCFFFFFF), fontSize = 10.sp)
        if (state.settings.manualFocus && caps.supportsManualFocus) {
            VerticalThinSlider(
                progress = focusProgress(state),
                manual = true,
                enabled = true,
                onProgress = { progress ->
                    viewModel.setFocusDiopters(progress * caps.minFocusDistance)
                },
                modifier = Modifier.height(150.dp),
            )
        }
        SquareButton(
            label = "A",
            active = !state.settings.manualFocus,
            enabled = caps.supportsManualFocus,
            onClick = {
                if (state.settings.manualFocus) viewModel.focusAuto()
                else viewModel.setManualFocus(true)
            },
        )
    }
}

/** White balance lives on the left edge: three lights, and the frame itself for anything between. */
@Composable
private fun WhiteBalanceColumn(
    state: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val available = state.capabilities.supportsManualWhiteBalance
    Column(
        modifier = modifier
            .background(Color(0x40000000), RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = whiteBalanceText(state), color = Color(0xCCFFFFFF), fontSize = 10.sp)
        SquareButton(
            label = "A",
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
            label = "TUN",
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
    }
}

private fun whiteBalanceText(state: CameraUiState): String {
    val settings = state.settings
    if (!settings.manualWhiteBalance) return "auto"
    return formatKelvin(settings.kelvin) +
        if (abs(settings.tint) > 0.02f) " ${"%+.1f".format(settings.tint)}" else ""
}

/** Exposure stays under the frame; focus and white balance are along its sides. */
@Composable
private fun Controls(state: CameraUiState, viewModel: CameraViewModel, frame: FrameStats) {
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

        // Compensation biases a meter that is not running while exposure is manual, so it only
        // exists while the camera is metering.
        val evRange = caps.exposureCompensationRange
        val evSpan = (evRange.upper - evRange.lower).coerceAtLeast(1)
        if (!settings.manualExposure && evSpan > 1) {
            ControlRow(
                label = "EV",
                value = formatExposureCompensation(
                    settings.exposureCompensation,
                    caps.exposureCompensationStep,
                ),
                progress = ((settings.exposureCompensation - evRange.lower).toFloat() / evSpan)
                    .coerceIn(0f, 1f),
                manual = settings.exposureCompensation != 0,
                available = true,
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
        }

        LensRow(state, viewModel)
        ShutterRow(state, viewModel, frame)
    }
}

@Composable
private fun LensRow(state: CameraUiState, viewModel: CameraViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.lenses.forEach { lens ->
                // "!" marks a lens that looks like a helper sensor or has misbehaved here before.
                // It still opens, but only on a second tap.
                val risky = lens.warning != null || lens.id in state.troubled
                Pill(
                    text = lens.zoomLabel,
                    subtitle = "${lens.facingLabel} ${lens.id}" + if (risky) " !" else "",
                    selected = lens.id == state.selectedLensId,
                    onClick = { viewModel.selectLens(lens.id) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FlatButton(
                label = state.settings.format.label,
                active = state.settings.format.writesRaw,
                onClick = viewModel::cycleFormat,
            )
            FlatButton(label = "SET", active = state.settingsOpen, onClick = viewModel::openSettings)
        }
    }
}

@Composable
private fun ShutterRow(state: CameraUiState, viewModel: CameraViewModel, frame: FrameStats) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlatButton(
                label = "ZEBRA",
                active = state.zebra,
                onClick = viewModel::toggleZebra,
            )
            FlatButton(label = "FILES", active = false, onClick = { openPhotoFolder(context) })
        }
        ShutterButton(
            busy = state.busy,
            enabled = state.selectedLensId != null,
            onClick = viewModel::capture,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Histogram(stats = frame)
        }
    }
}

/**
 * The camera hands over each frame already turned for the device's natural orientation, so only
 * the difference from that needs correcting - and the correction scales to fit rather than fill,
 * because a cropped preview lies about what the picture will contain.
 */
private fun applyPreviewTransform(
    view: TextureView,
    bufferWidth: Int,
    bufferHeight: Int,
    rotation: Int,
) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0 || bufferHeight <= 0) return

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    when (rotation) {
        Surface.ROTATION_90, Surface.ROTATION_270 -> {
            // Undo the default stretch first: put the frame back at its own size, centred.
            val content = RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())
            content.offset(centerX - content.centerX(), centerY - content.centerY())
            matrix.setRectToRect(
                RectF(0f, 0f, viewWidth, viewHeight),
                content,
                Matrix.ScaleToFit.FILL,
            )
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            val scale = min(viewWidth / bufferWidth, viewHeight / bufferHeight)
            matrix.postScale(scale, scale, centerX, centerY)
        }
        Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
    }
    view.setTransform(matrix)
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
