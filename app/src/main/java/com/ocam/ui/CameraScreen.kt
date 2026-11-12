package com.ocam.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocam.CameraUiState
import com.ocam.CameraViewModel
import com.ocam.qr.QrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt


/** How often the preview is read back for the histogram and the clipping warning. */
private const val SAMPLE_INTERVAL_MS = 200L

/** While peaking or false colour is on: often enough to follow a focus pull. */
private const val OVERLAY_INTERVAL_MS = 90L

/** The long side of the copy peaking and false colour are drawn from. */
private const val OVERLAY_SAMPLE_LONG_SIDE = 480

/** How often, and how large, the preview is read back while looking for a QR code. */
private const val QR_INTERVAL_MS = 300L
private const val QR_SAMPLE_LONG_SIDE = 1280

/** How far the magnifier enlarges the frame. */
private const val MAGNIFICATION = 4f

/** How tall the values over the top of the picture are, below the status bar. */
private val BAR_UPRIGHT = 112.dp
private val BAR_ON_ITS_SIDE = 78.dp

@Composable
fun CameraScreen(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frame by remember { mutableStateOf(FrameStats.EMPTY) }

    // Nothing this app draws ever touches the preview pixels, so the only way to know what the
    // frame contains is to read a small copy of it back a few times a second. Peaking and false
    // colour are drawn from that copy, so while either is on it is read larger and more often.
    val peaking = state.aids.peaking
    val falseColor = state.aids.falseColor
    LaunchedEffect(state.selectedLensId, peaking, falseColor) {
        val overlays = peaking || falseColor
        if (!overlays) frame = FrameStats(frame.luma, frame.exposureOffset)
        while (true) {
            delay(if (overlays) OVERLAY_INTERVAL_MS else SAMPLE_INTERVAL_MS)
            val view = textureView?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }
                ?: continue
            val (sampleWidth, sampleHeight) = if (overlays) {
                val scale = OVERLAY_SAMPLE_LONG_SIDE.toFloat() / maxOf(view.width, view.height)
                (view.width * scale).roundToInt().coerceAtLeast(3) to
                    (view.height * scale).roundToInt().coerceAtLeast(3)
            } else {
                120 to 160
            }
            val bitmap = runCatching { view.getBitmap(sampleWidth, sampleHeight) }.getOrNull()
                ?: continue
            val transform = if (overlays) view.getTransform(null) else null
            frame = withContext(Dispatchers.Default) {
                analyseFrame(bitmap, peaking, falseColor, transform)
            }
            bitmap.recycle()
        }
    }

    // Large enough for a code some way off, and only while it is asked for and nothing else needs
    // the frames: reading a preview back at this size is not free.
    val scanning = state.qrScan && !state.reviewOpen && state.sequence == null
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        var bitmap: Bitmap? = null
        var pixels = IntArray(0)
        try {
            while (true) {
                delay(QR_INTERVAL_MS)
                val view = textureView?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }
                    ?: continue
                val scale = min(1f, QR_SAMPLE_LONG_SIDE.toFloat() / maxOf(view.width, view.height))
                val width = (view.width * scale).roundToInt().coerceAtLeast(3)
                val height = (view.height * scale).roundToInt().coerceAtLeast(3)
                val reused = bitmap?.takeIf { it.width == width && it.height == height }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        bitmap?.recycle()
                        bitmap = it
                    }
                if (runCatching { view.getBitmap(reused) }.isFailure) continue
                if (pixels.size != width * height) pixels = IntArray(width * height)
                val found = pixels
                val text = withContext(Dispatchers.Default) {
                    reused.getPixels(found, 0, width, 0, 0, width, height)
                    QrReader.read(found, width, height)
                }
                viewModel.onQrReading(text)
            }
        } finally {
            bitmap?.recycle()
        }
    }

    // One value's presets at a time, closed by tapping the value again or the picture. A run
    // closes it too: nothing should cover the picture being built.
    var open by remember { mutableStateOf<ValueKind?>(null) }
    val running = state.sequence != null
    LaunchedEffect(running) { if (running) open = null }
    val pick: (ValueKind) -> Unit = { open = if (open == it) null else it }
    val review: @Composable () -> Unit = { ReviewButton(state, viewModel) }

    // The window is edge to edge and the picture runs under the values, as on a monitor. Upright
    // the tools and modes are rows under the frame; on its side they are columns beside it, in the
    // same order, so nothing has to be learned twice.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val landscape = maxWidth > maxHeight
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val barHeight = statusBar + if (landscape) BAR_ON_ITS_SIDE else BAR_UPRIGHT
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PreviewArea(state, viewModel, frame, landscape, barHeight, { open = null }) {
                        textureView = it
                    }
                    Column(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding()) {
                        ValueBar(state, viewModel, open, pick, wide = true)
                        open?.let { PresetStrip(state, viewModel, it) }
                    }
                    Scopes(
                        state, frame,
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().displayCutoutPadding().padding(10.dp),
                    )
                }
                ToolStrip(
                    state, viewModel, vertical = true, review = review,
                    modifier = Modifier.fillMaxHeight().background(ToolGround).safeDrawingPadding().width(80.dp),
                )
                ModeBar(
                    state, viewModel, vertical = true,
                    modifier = Modifier.fillMaxHeight().background(ModeGround).safeDrawingPadding().width(92.dp),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewArea(state, viewModel, frame, landscape, barHeight, { open = null }) {
                        textureView = it
                    }
                    Column(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding()) {
                        ValueBar(state, viewModel, open, pick, wide = false)
                        open?.let { PresetStrip(state, viewModel, it) }
                    }
                    Scopes(state, frame, Modifier.align(Alignment.BottomCenter).padding(10.dp))
                }
                ToolStrip(
                    state, viewModel, vertical = false, review = review,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
                ModeBar(
                    state, viewModel, vertical = false, settings = false,
                    modifier = Modifier.fillMaxWidth().background(ModeGround).navigationBarsPadding(),
                )
            }
        }
    }

    if (state.settingsOpen) {
        SettingsSheet(state = state, viewModel = viewModel, onClose = viewModel::closeSettings)
    }

    if (state.reviewOpen) {
        ReviewScreen(
            shots = state.shots,
            onDelete = viewModel::deleteShot,
            onClose = viewModel::closeReview,
        )
    }

    state.diagnostics?.let { report ->
        DiagnosticsSheet(
            text = report,
            onCopy = { viewModel.copiedDiagnostics() },
            onClose = viewModel::closeDiagnostics,
        )
    }
}

/** The histogram in the bottom left corner of the picture, the meter in the bottom right. */
@Composable
private fun Scopes(state: CameraUiState, frame: FrameStats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        ScopePanel(content = { Histogram(stats = frame, modifier = Modifier.fillMaxWidth()) })
        if (state.aids.meter) {
            ScopePanel(content = { MeterScale(frame.exposureOffset, modifier = Modifier.fillMaxWidth()) })
        }
    }
}

/** The frame, and the messages that come up over its lower edge. */
@Composable
private fun PreviewArea(
    state: CameraUiState,
    viewModel: CameraViewModel,
    frame: FrameStats,
    landscape: Boolean,
    barHeight: Dp,
    onTap: () -> Unit,
    onTextureView: (TextureView?) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(state, viewModel, frame, landscape, barHeight, onTap, onTextureView)

        val error = state.error
        val status = state.status
        Column(
            // Clear of the histogram and the meter in the corners below.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UpdateStrip(state, viewModel)
            state.qrCode?.let { code ->
                QrCard(
                    code = code,
                    onMessage = viewModel::onStatus,
                    onDismiss = viewModel::dismissQrCode,
                )
            }
            if (error != null || status != null) {
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
    barHeight: Dp,
    onTap: () -> Unit,
    onTextureView: (TextureView?) -> Unit,
) {
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
        // Labels drawn over the frame keep below the values, wherever the frame's top edge falls.
        val labelTop = (barHeight - (maxHeight - height) / 2).coerceAtLeast(8.dp)

        val center = state.magnifyCenter
        Box(modifier = Modifier.size(width, height).clipToBounds()) {
            // The magnifier scales the preview itself, so what it shows is what the camera sends.
            // Taps are read in the unscaled frame's coordinates either way, so focus still lands
            // where the finger is.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = if (state.magnify) MAGNIFICATION else 1f
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(center.first, center.second)
                    },
            ) {
                PreviewTexture(state, viewModel, onTap, onTextureView)
                AnalysisOverlay(frame, modifier = Modifier.fillMaxSize())
                state.subject?.let { SubjectOverlay(it, modifier = Modifier.fillMaxSize()) }
            }

            // The picture being built replaces the live frame, at the frame's own scale.
            state.sequence?.let {
                SequenceOverlay(it, viewModel::toggleSequenceHold, Modifier.fillMaxSize(), labelTop)
            }

            // Composition aids stay at the frame's own scale: they describe the picture, not
            // the part of it being looked at.
            if (!state.magnify) {
                FramingOverlay(state.aids.grid, state.aids.frame, modifier = Modifier.fillMaxSize())
            }
            if (state.aids.level) LevelOverlay(modifier = Modifier.fillMaxSize())
            if (state.magnify) {
                Text(
                    text = "×${MAGNIFICATION.roundToInt()}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = labelTop)
                        .background(Color(0x73000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            state.countdown?.let { CountdownOverlay(it, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** The view the camera draws into, and the taps and drags that land on it. */
@Composable
private fun PreviewTexture(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onTap: () -> Unit,
    onTextureView: (TextureView?) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTap()
                    viewModel.tapPreview(
                        offset.x / size.width.toFloat(),
                        offset.y / size.height.toFloat(),
                    )
                }
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
}

/**
 * The last shot, which opens review. With nothing to show, the folder is still there: files from
 * before a reinstall are in it, and this app can no longer see them by itself.
 */
@Composable
private fun ReviewButton(state: CameraUiState, viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val newest = state.shots.firstOrNull()
    if (newest != null) {
        ReviewThumbnail(
            shot = newest,
            enabled = state.sequence == null,
            onClick = viewModel::openReview,
            modifier = modifier,
        )
    } else {
        FlatButton(
            label = "FILES",
            active = false,
            onClick = { openPhotoFolder(context) },
            modifier = modifier,
        )
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
internal fun openPhotoFolder(context: Context) {
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
