package com.ocam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.ocam.io.PhotoStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "CameraController"

/** How long to wait for AF/AE to settle before taking the shot anyway. */
private const val CONVERGENCE_TIMEOUT_MS = 2_500L

/** Safety net so a dropped frame cannot leave the shutter button stuck. */
private const val SAVE_TIMEOUT_MS = 10_000L

/** Switching between physical lenses of one logical camera can briefly report CAMERA_IN_USE. */
private const val MAX_OPEN_ATTEMPTS = 4
private const val OPEN_RETRY_DELAY_MS = 300L
private val OPEN_RETRY_TOKEN = Any()

/** How long to let autofocus hunt when racking focus by tap. */
private const val RACK_TIMEOUT_MS = 2_500L

/** How many frames to give the meter to settle before taking its reading anyway. */
private const val METERING_FRAME_LIMIT = 60

/**
 * Owns the Camera2 device, session and capture pipeline. Every camera call happens on a single
 * background thread; image encoding happens on a second one. Nothing here knows about Compose.
 */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onLensOpened(
            lens: Lens,
            capabilities: LensCapabilities,
            previewSize: Size,
            streams: String,
        )
        fun onLiveValues(iso: Int?, exposureTimeNs: Long?, focusDiopters: Float?, aperture: Float?)
        /**
         * The exposure the camera's own meter arrived at on the first frames. It is read once,
         * to start the manual sliders from the light in the room instead of from a guess; from
         * then on nothing moves them but the user.
         */
        fun onMeteredExposure(iso: Int, exposureTimeNs: Long)
        /** Autofocus found a subject while in manual focus; this is the distance it settled on. */
        fun onFocusRacked(diopters: Float)
        fun onCaptureBusy(busy: Boolean)
        fun onStatus(message: String)
        fun onError(message: String)
    }

    private enum class State { PREVIEW, WAIT_FOCUS, WAIT_PRECAPTURE, WAIT_EXPOSURE, CAPTURING }

    /** One attempt at a set of output streams: null means "do not ask for this one". */
    private data class StreamPlan(val still: Size?, val raw: Size?)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val memory = LensMemory(appContext)

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }
    private val ioThread = HandlerThread("camera-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    /** Every lens the device exposes, main lenses first. */
    val lenses: List<Lens> by lazy { enumerateLenses(manager) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var stillReader: ImageReader? = null
    private var configuredStillFormat: Int? = null
    private var rawReader: ImageReader? = null
    private var openLens: Lens? = null
    private var streamPlans: List<StreamPlan> = emptyList()
    private var planIndex = 0
    private var currentLensId: String? = null
    private var currentTexture: SurfaceTexture? = null
    private var openAttempts = 0
    private var previewSize = Size(1440, 1080)
    private var autoAfMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var shadingMapAvailable = false
    private var rackingFocus = false
    /** Whether the search now running was asked for by a tap, and so worth reporting on. */
    private var rackAnnounces = false
    private var meteringRegion: MeteringRectangle? = null
    /** True until the meter has been read once; see [Listener.onMeteredExposure]. */
    private var metering = true
    private var meteringFrames = 0
    /** The last channel gains the camera reported, and the illuminant it reported them for. */
    private var reportedGains: RggbChannelVector? = null
    private var reportedGainsKelvin = WhiteBalance.DAYLIGHT.kelvin
    private var state = State.PREVIEW
    private var lastPublishMs = 0L
    private var pendingOrientation = 0

    @Volatile private var characteristics: CameraCharacteristics? = null
    @Volatile private var capabilities = LensCapabilities.EMPTY
    @Volatile private var sensorOrientation = 0
    @Volatile private var facing = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var settings = CaptureSettings()
    @Volatile private var baseName = ""
    @Volatile private var capturing = false

    private val pendingSaves = AtomicInteger(0)
    private val rawPending = RawPending()

    // region public API

    fun open(lensId: String, texture: SurfaceTexture) {
        cameraHandler.post {
            cameraHandler.removeCallbacksAndMessages(OPEN_RETRY_TOKEN)
            openAttempts = 0
            currentLensId = lensId
            currentTexture = texture
            openInternal(lensId, texture)
        }
    }

    fun close() {
        cameraHandler.post {
            cameraHandler.removeCallbacksAndMessages(OPEN_RETRY_TOKEN)
            closeInternal()
        }
    }

    fun updateSettings(newSettings: CaptureSettings) {
        cameraHandler.post {
            settings = newSettings

            // JPEG and HEIC come out of different readers, so changing between them means
            // building the session again rather than just changing a request key.
            val wanted = newSettings.format.stillFormat
            if (!capturing && wanted != null && configuredStillFormat != null &&
                wanted != configuredStillFormat
            ) {
                reconfigureForFormat()
                return@post
            }
            if (!capturing) applyToPreview()
        }
    }

    fun capture() {
        cameraHandler.post { startCapture() }
    }

    /**
     * Pull focus to a spot: borrow autofocus for one shot, then keep the distance it found.
     */
    fun rackFocusAt(normalizedX: Float, normalizedY: Float) {
        cameraHandler.post {
            if (!capabilities.hasAutoFocus) {
                listener.onStatus("This lens cannot search for focus")
                return@post
            }
            setMeteringPoint(normalizedX, normalizedY)
            startRack(byTap = true)
        }
    }

    /**
     * Let the lens search once and keep the distance it lands on. This is the only thing that
     * moves focus other than the slider: manual focus cannot search by itself, but it can be
     * told where to look - by a tap, or by the one look taken when a lens opens.
     */
    private fun startRack(byTap: Boolean) {
        rackingFocus = true
        rackAnnounces = byTap
        applyToPreview()

        val builder = previewBuilder ?: return
        val active = session ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        runCatching { active.capture(builder.build(), previewCallback, cameraHandler) }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        cameraHandler.removeCallbacks(rackTimeout)
        cameraHandler.postDelayed(rackTimeout, RACK_TIMEOUT_MS)
    }

    private val rackTimeout = Runnable {
        if (rackingFocus) {
            rackingFocus = false
            applyToPreview()
            if (rackAnnounces) listener.onStatus("Focus did not find anything there")
        }
    }

    fun release() {
        cameraHandler.post {
            closeInternal()
            cameraThread.quitSafely()
        }
        ioHandler.post { ioThread.quitSafely() }
    }

    // endregion

    // region open / close

    @SuppressLint("MissingPermission")
    private fun openInternal(lensId: String, texture: SurfaceTexture) {
        closeInternal()
        val lens = lenses.firstOrNull { it.id == lensId }
        if (lens == null) {
            listener.onError("Lens $lensId is gone")
            return
        }
        try {
            val chars = manager.getCameraCharacteristics(lensId)
            val map = chars.streamMap() ?: throw IllegalStateException("no stream map")
            val stillFormat = settings.format.stillFormat ?: ImageFormat.JPEG
            val stillSize = chars.stillSize(stillFormat)
                ?: chars.jpegSize()
                ?: throw IllegalStateException("no still output")
            val rawSize = chars.rawSize()

            characteristics = chars
            capabilities = chars.capabilities()
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            shadingMapAvailable = chars
                .get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true
            autoAfMode = pickAutoAfMode(chars)
            meteringRegion = null
            openLens = lens
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java), stillSize)
            streamPlans = buildStreamPlans(chars, stillSize, rawSize)
            planIndex = 0

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(texture)

            // Written before the call that might not come back, so the next start knows.
            memory.beginOpen(lensId)
            manager.openCamera(lensId, cameraExecutor, deviceCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open lens $lensId", e)
            retryOrFail(e.javaClass.simpleName)
        }
    }

    /** The camera can be busy for a moment after another lens on the same sensor is released. */
    private fun retryOrFail(reason: String) {
        val lensId = currentLensId
        val texture = currentTexture
        if (lensId != null && texture != null && openAttempts < MAX_OPEN_ATTEMPTS) {
            openAttempts++
            cameraHandler.postDelayed(
                { openInternal(lensId, texture) },
                OPEN_RETRY_TOKEN,
                OPEN_RETRY_DELAY_MS,
            )
        } else {
            lensId?.let { memory.openFailed(it) }
            listener.onError("Cannot open lens ${lensId ?: "?"} ($reason)")
        }
    }

    private fun closeInternal() {
        cameraHandler.removeCallbacks(convergenceTimeout)
        cameraHandler.removeCallbacks(saveTimeout)
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        closeReaders()
        runCatching { previewSurface?.release() }
        previewSurface = null
        previewBuilder = null
        rawPending.clear()
        pendingSaves.set(0)
        capturing = false
        state = State.PREVIEW
    }

    private fun closeReaders() {
        runCatching { stillReader?.close() }
        stillReader = null
        runCatching { rawReader?.close() }
        rawReader = null
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            openAttempts = 0
            configureCurrentPlan()
        }

        override fun onDisconnected(camera: CameraDevice) {
            closeInternal()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            closeInternal()
            val busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
            if (busy) retryOrFail("in use") else listener.onError("Camera error $error")
        }
    }

    /**
     * What to ask the camera for, richest first. Only the main cameras are guaranteed to accept
     * preview + full size JPEG + full size RAW at once; the extra sensors frequently refuse, so
     * every simpler combination is tried before giving up on the lens.
     */
    private fun buildStreamPlans(
        characteristics: CameraCharacteristics,
        stillMax: Size?,
        rawMax: Size?,
    ): List<StreamPlan> {
        val raw = rawMax?.takeIf {
            characteristics.hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val stillFormat = settings.format.stillFormat ?: ImageFormat.JPEG
        val stillSmall = characteristics.streamMap()
            ?.getOutputSizes(stillFormat)
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height }

        return buildList {
            if (stillMax != null && raw != null) add(StreamPlan(stillMax, raw))
            if (raw != null) add(StreamPlan(null, raw))
            if (stillMax != null) add(StreamPlan(stillMax, null))
            if (stillSmall != null && stillSmall != stillMax) add(StreamPlan(stillSmall, null))
            // Last resort: a preview and nothing else, so the lens at least shows a picture.
            add(StreamPlan(null, null))
        }
    }

    private fun configureCurrentPlan() {
        val camera = device ?: return
        val preview = previewSurface ?: return
        val plan = streamPlans.getOrNull(planIndex)
        if (plan == null) {
            listener.onError("Lens ${openLens?.id} rejected every stream combination")
            return
        }

        closeReaders()
        val stillFormat = settings.format.stillFormat ?: ImageFormat.JPEG
        configuredStillFormat = stillFormat
        plan.still?.let { size ->
            stillReader = ImageReader.newInstance(size.width, size.height, stillFormat, 2)
                .apply { setOnImageAvailableListener(stillAvailable, ioHandler) }
        }
        plan.raw?.let { size ->
            rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
                .apply { setOnImageAvailableListener(rawAvailable, ioHandler) }
        }

        try {
            val outputs = mutableListOf(OutputConfiguration(preview))
            stillReader?.let { outputs += OutputConfiguration(it.surface) }
            rawReader?.let { outputs += OutputConfiguration(it.surface) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    cameraExecutor,
                    sessionCallback,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Stream plan $planIndex could not be submitted", e)
            tryNextPlan()
        }
    }

    /** Rebuild the session because the still format changed under it. */
    private fun reconfigureForFormat() {
        val chars = characteristics ?: return
        runCatching { session?.close() }
        session = null
        previewBuilder = null
        val stillFormat = settings.format.stillFormat ?: ImageFormat.JPEG
        val stillSize = chars.stillSize(stillFormat) ?: chars.jpegSize()
        streamPlans = buildStreamPlans(chars, stillSize, chars.rawSize())
        planIndex = 0
        configureCurrentPlan()
    }

    private fun tryNextPlan() {
        planIndex++
        if (planIndex < streamPlans.size) {
            configureCurrentPlan()
        } else {
            listener.onError("Lens ${openLens?.id} rejected every stream combination")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            session = configured
            // Report what the lens actually granted, not what its metadata promised: a lens can
            // advertise RAW and still refuse to deliver it alongside a preview.
            openLens?.let { lens -> memory.openSucceeded(lens.id) }
            openLens?.let { lens ->
                listener.onLensOpened(
                    lens,
                    capabilities.copy(supportsRaw = rawReader != null),
                    previewSize,
                    describeStreams(),
                )
            }
            startPreview()
        }

        override fun onConfigureFailed(configured: CameraCaptureSession) {
            Log.w(TAG, "Stream plan $planIndex rejected by lens ${openLens?.id}")
            runCatching { configured.close() }
            tryNextPlan()
        }
    }

    /** What the lens actually granted, for the diagnostics report. */
    private fun describeStreams(): String = buildString {
        append("preview ${previewSize.width}x${previewSize.height}")
        stillReader?.let {
            append(", ${if (settings.format.usesHeic) "heic" else "jpeg"} ${it.width}x${it.height}")
        }
        rawReader?.let { append(", raw ${it.width}x${it.height}") }
        if (stillReader == null && rawReader == null) append(", no capture stream")
        append(" (plan ${planIndex + 1} of ${streamPlans.size})")
    }

    private fun startPreview() {
        val camera = device ?: return
        val active = session ?: return
        val preview = previewSurface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            previewBuilder = builder
            applySettings(builder)
            state = State.PREVIEW
            active.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)

            // One look as the lens opens, so it holds the distance to what is in front of it
            // rather than to infinity. Nothing after this moves focus on its own.
            if (capabilities.supportsManualFocus && capabilities.hasAutoFocus) {
                startRack(byTap = false)
            }
        } catch (e: Exception) {
            listener.onError("Preview failed: ${e.javaClass.simpleName}")
        }
    }

    private fun applyToPreview() {
        val builder = previewBuilder ?: return
        val active = session ?: return
        applySettings(builder)
        runCatching { active.setRepeatingRequest(builder.build(), previewCallback, cameraHandler) }
            .onFailure { listener.onError("Cannot apply settings: ${it.javaClass.simpleName}") }
    }

    // endregion

    // region request building

    private fun applySettings(builder: CaptureRequest.Builder) {
        val current = settings
        val caps = capabilities
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Exposure is set, not chosen. The exception is the opening frames, where the meter runs
        // once so the sliders start from the light in the room, and a lens that cannot be told
        // its sensitivity at all, where there is nothing else to do.
        if (caps.supportsManualSensor && !metering) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            caps.isoRange?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it.clamp(current.iso)) }
            caps.exposureTimeRange?.let { range ->
                val exposure = range.clamp(current.exposureTimeNs)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, exposure)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        // Focus likewise: the only time the lens searches is when it has been told to, by a tap
        // or by the one look it takes when a lens opens, and the distance it lands on is kept.
        if (rackingFocus && caps.hasAutoFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            meteringRegion?.takeIf { maxAfRegions > 0 }?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            }
        } else if (caps.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                current.focusDiopters.coerceIn(0f, caps.minFocusDistance),
            )
        } else if (caps.hasAutoFocus) {
            // A lens that cannot be given a distance can only be asked to find one.
            builder.set(CaptureRequest.CONTROL_AF_MODE, autoAfMode)
        }

        val gains = reportedGains
        if (current.whiteBalance == WhiteBalance.CUSTOM &&
            caps.supportsManualWhiteBalance && gains != null
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                shiftedGains(gains, reportedGainsKelvin, current.kelvin, current.tint),
            )
        } else {
            // The camera's own fixed illuminant, which is calibrated for this sensor. Naming a
            // temperature and converting it to gains here is what made tungsten and daylight green.
            val preset = current.whiteBalance.takeIf { it != WhiteBalance.CUSTOM }
                ?: caps.whiteBalancePresets.firstOrNull()
                ?: WhiteBalance.DAYLIGHT
            builder.set(CaptureRequest.CONTROL_AWB_MODE, preset.awbMode)
        }

        if (caps.supportsManualAperture) {
            current.aperture?.let { builder.set(CaptureRequest.LENS_APERTURE, it) }
        }

        // DngCreator turns the lens shading map in the capture result into the DNG's shading
        // opcode. Without it a converter renders the corners uncorrected, so ask for the map
        // whenever a RAW file is going to be written.
        if (current.format.writesRaw && shadingMapAvailable) {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        // Partial results carry only whatever the camera has decided so far; every other key
        // reads back null. They are fine for watching the focus and exposure state machines,
        // but publishing values from them makes the readout blink on and off.
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult,
        ) = advance(partialResult)

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            observe(result)
            publishLiveValues(result)
            advance(result)
        }
    }

    private fun advance(result: CaptureResult) {

        if (rackingFocus) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                rackingFocus = false
                cameraHandler.removeCallbacks(rackTimeout)
                val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (distance != null) listener.onFocusRacked(distance) else applyToPreview()
            }
        }
        when (state) {
            State.PREVIEW, State.CAPTURING -> Unit

            State.WAIT_FOCUS -> {
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val settled = af == null ||
                    af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_INACTIVE
                if (settled) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val exposureIsSet = capabilities.supportsManualSensor && !metering
                    if (exposureIsSet || ae == null ||
                        ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                    ) {
                        captureStill()
                    } else {
                        runPrecapture()
                    }
                }
            }

            State.WAIT_PRECAPTURE -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null ||
                    ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE ||
                    ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                    ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                ) {
                    state = State.WAIT_EXPOSURE
                }
            }

            State.WAIT_EXPOSURE -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null || ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) captureStill()
            }
        }
    }

    /**
     * What the camera says about itself, taken once each.
     *
     * The exposure is read on the opening frames so the manual sliders start from the light in
     * the room; after that the meter is off and nothing moves them but the user. The channel
     * gains are read whenever a named illuminant is selected, because those gains are the
     * sensor's own balance for that light - and a custom temperature is a shift away from them
     * rather than a number invented here.
     */
    private fun observe(result: CaptureResult) {
        val preset = settings.whiteBalance
        if (preset != WhiteBalance.CUSTOM) {
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
                reportedGains = gains
                reportedGainsKelvin = preset.kelvin
            }
        }

        if (!metering) return
        // Wait for the meter to settle before taking its word for it: the opening frames are
        // whatever the camera started at, not what it decided. If it never converges, take what
        // it has rather than metering forever.
        meteringFrames++
        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
        val settled = ae == null ||
            ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
            ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
            meteringFrames > METERING_FRAME_LIMIT
        if (!settled) return

        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        metering = false
        listener.onMeteredExposure(iso, exposure)
    }

    private fun publishLiveValues(result: CaptureResult) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishMs < 200L) return
        lastPublishMs = now
        listener.onLiveValues(
            result.get(CaptureResult.SENSOR_SENSITIVITY),
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            result.get(CaptureResult.LENS_APERTURE),
        )
    }

    // endregion

    // region capture

    private fun startCapture() {
        if (capturing) return
        val active = session ?: return
        val current = settings
        val willWriteJpeg = current.format.writesStill && stillReader != null
        val willWriteRaw = current.format.writesRaw && rawReader != null
        if (!willWriteJpeg && !willWriteRaw) {
            listener.onError(
                if (current.format.writesRaw) "This lens gave no RAW stream"
                else "This lens gave no capture stream"
            )
            return
        }
        capturing = true
        listener.onCaptureBusy(true)
        baseName = PhotoStore.newBaseName(System.currentTimeMillis())
        pendingOrientation = outputOrientation()
        pendingSaves.set((if (willWriteJpeg) 1 else 0) + (if (willWriteRaw) 1 else 0))
        cameraHandler.postDelayed(convergenceTimeout, CONVERGENCE_TIMEOUT_MS)
        cameraHandler.postDelayed(saveTimeout, SAVE_TIMEOUT_MS)

        // Nothing to converge: the exposure and the focus distance are already what they will
        // be. Only a lens that cannot be told either has to be waited for.
        when {
            !capabilities.supportsManualFocus && capabilities.hasAutoFocus -> lockFocus(active)
            !capabilities.supportsManualSensor || metering -> runPrecapture()
            else -> captureStill()
        }
    }

    private fun lockFocus(active: CameraCaptureSession) {
        val builder = previewBuilder ?: return captureStill()
        state = State.WAIT_FOCUS
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        runCatching { active.capture(builder.build(), previewCallback, cameraHandler) }
            .onFailure { captureStill() }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
    }

    private fun runPrecapture() {
        val builder = previewBuilder ?: return captureStill()
        val active = session ?: return captureStill()
        state = State.WAIT_PRECAPTURE
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START,
        )
        runCatching { active.capture(builder.build(), previewCallback, cameraHandler) }
            .onFailure { captureStill() }
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
        )
    }

    private val convergenceTimeout = Runnable {
        if (capturing && state != State.CAPTURING) captureStill()
    }

    private val saveTimeout = Runnable {
        if (capturing) {
            listener.onError("Capture timed out")
            finishCapture()
        }
    }

    private fun captureStill() {
        cameraHandler.removeCallbacks(convergenceTimeout)
        state = State.CAPTURING
        val camera = device
        val active = session
        if (camera == null || active == null) {
            finishCapture()
            return
        }
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applySettings(builder)
            if (settings.format.writesStill) stillReader?.let { builder.addTarget(it.surface) }
            if (settings.format.writesRaw) rawReader?.let { builder.addTarget(it.surface) }
            builder.set(CaptureRequest.JPEG_ORIENTATION, pendingOrientation)
            builder.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            active.stopRepeating()
            active.capture(builder.build(), stillCallback, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Still capture failed", e)
            listener.onError("Capture failed: ${e.javaClass.simpleName}")
            finishCapture()
            resumePreview()
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (settings.format.writesRaw) rawPending.offerResult(result)
            releaseFocusLock()
            resumePreview()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            listener.onError("Capture failed (reason ${failure.reason})")
            finishCapture()
            releaseFocusLock()
            resumePreview()
        }
    }

    private fun releaseFocusLock() {
        val builder = previewBuilder ?: return
        val active = session ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        runCatching { active.capture(builder.build(), previewCallback, cameraHandler) }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
    }

    private fun resumePreview() {
        val builder = previewBuilder ?: return
        val active = session ?: return
        state = State.PREVIEW
        applySettings(builder)
        runCatching { active.setRepeatingRequest(builder.build(), previewCallback, cameraHandler) }
    }

    private fun finishCapture() {
        cameraHandler.removeCallbacks(convergenceTimeout)
        cameraHandler.removeCallbacks(saveTimeout)
        pendingSaves.set(0)
        rawPending.clear()
        capturing = false
        state = State.PREVIEW
        listener.onCaptureBusy(false)
    }

    private fun onFileSaved(name: String) {
        listener.onStatus("Saved $name")
        if (pendingSaves.decrementAndGet() <= 0) {
            cameraHandler.post { finishCapture() }
        }
    }

    private fun onSaveFailed(what: String, error: Throwable) {
        Log.e(TAG, "Cannot save $what", error)
        listener.onError("Cannot save $what: ${error.message ?: error.javaClass.simpleName}")
        cameraHandler.post { finishCapture() }
    }

    private val stillAvailable = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        val name = baseName
        val heic = settings.format.usesHeic
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            onFileSaved(PhotoStore.saveStill(appContext, bytes, name, heic))
        } catch (t: Throwable) {
            runCatching { image.close() }
            onSaveFailed(if (heic) "HEIC" else "JPEG", t)
        }
    }

    private val rawAvailable = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        rawPending.offerImage(image)
    }

    /** Pairs a RAW frame with the capture result that describes it; DngCreator needs both. */
    private inner class RawPending {
        private var image: Image? = null
        private var result: TotalCaptureResult? = null

        @Synchronized fun offerImage(newImage: Image) {
            image?.let { runCatching { it.close() } }
            image = newImage
            emitIfReady()
        }

        @Synchronized fun offerResult(newResult: TotalCaptureResult) {
            result = newResult
            emitIfReady()
        }

        @Synchronized fun clear() {
            image?.let { runCatching { it.close() } }
            image = null
            result = null
        }

        private fun emitIfReady() {
            val readyImage = image ?: return
            val readyResult = result ?: return
            image = null
            result = null
            val chars = characteristics ?: run {
                runCatching { readyImage.close() }
                return
            }
            val name = baseName
            val orientation = pendingOrientation
            ioHandler.post {
                try {
                    val saved = PhotoStore.saveDng(
                        appContext, chars, readyResult, readyImage, orientation, name
                    )
                    onFileSaved(saved)
                } catch (t: Throwable) {
                    onSaveFailed("DNG", t)
                } finally {
                    runCatching { readyImage.close() }
                }
            }
        }
    }

    // endregion

    // region geometry

    private fun setMeteringPoint(normalizedX: Float, normalizedY: Float) {
        if (maxAfRegions <= 0 && maxAeRegions <= 0) return
        val chars = characteristics ?: return
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val (sensorX, sensorY) = viewToSensor(normalizedX, normalizedY)
        val halfWidth = (array.width() * 0.075f).roundToInt().coerceAtLeast(1)
        val halfHeight = (array.height() * 0.075f).roundToInt().coerceAtLeast(1)
        val centerX = (array.left + sensorX * array.width()).roundToInt()
        val centerY = (array.top + sensorY * array.height()).roundToInt()
        val rect = Rect(
            (centerX - halfWidth).coerceIn(array.left, array.right - 1),
            (centerY - halfHeight).coerceIn(array.top, array.bottom - 1),
            (centerX + halfWidth).coerceIn(array.left + 1, array.right),
            (centerY + halfHeight).coerceIn(array.top + 1, array.bottom),
        )
        meteringRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        applyToPreview()

    }

    /** The preview is the sensor image rotated by [sensorOrientation]; undo that rotation. */
    private fun viewToSensor(x: Float, y: Float): Pair<Float, Float> = when (sensorOrientation) {
        90 -> y to (1f - x)
        180 -> (1f - x) to (1f - y)
        270 -> (1f - y) to x
        else -> x to y
    }

    /** Rotation to store in the file, from the sensor mounting plus how the phone is held. */
    private fun outputOrientation(): Int {
        val rotation = ((settings.deviceRotation + 45) / 90 * 90) % 360
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - rotation + 360) % 360
        } else {
            (sensorOrientation + rotation) % 360
        }
    }

    // endregion

    private fun pickAutoAfMode(chars: CameraCharacteristics): Int {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        return when {
            modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> CameraMetadata.CONTROL_AF_MODE_OFF
        }
    }

    /**
     * Largest preview no bigger than 1080p that matches the still aspect ratio - the stream
     * combination "preview + JPEG max + RAW max" is only guaranteed while the preview stays
     * within that bound.
     */
    private fun choosePreviewSize(candidates: Array<Size>?, captureSize: Size): Size {
        val options = candidates?.filter { it.width <= 1920 && it.height <= 1080 }.orEmpty()
        if (options.isEmpty()) return previewSize
        val targetRatio = captureSize.width.toFloat() / captureSize.height
        val matching = options.filter {
            abs(it.width.toFloat() / it.height - targetRatio) < 0.02f
        }
        val pool = matching.ifEmpty { options }
        return pool.maxByOrNull { it.width.toLong() * it.height } ?: previewSize
    }
}
