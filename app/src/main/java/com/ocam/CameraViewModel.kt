package com.ocam

import android.app.Application
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ocam.camera.AppSettings
import com.ocam.camera.CameraController
import com.ocam.camera.formatChoices
import com.ocam.camera.Diagnostics
import com.ocam.camera.LensMemory
import com.ocam.camera.CaptureFormat
import com.ocam.camera.CaptureSettings
import com.ocam.camera.Lens
import com.ocam.camera.LensCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

const val MIN_KELVIN = 2000
const val MAX_KELVIN = 10000
private const val KELVIN_SPAN = 6000f

data class CameraUiState(
    val lenses: List<Lens> = emptyList(),
    val selectedLensId: String? = null,
    val capabilities: LensCapabilities = LensCapabilities.EMPTY,
    val settings: CaptureSettings = CaptureSettings(),
    /** Preview width / height as shown on screen. */
    val previewAspect: Float = 3f / 4f,
    val liveIso: Int? = null,
    val liveExposureNs: Long? = null,
    val liveFocusDiopters: Float? = null,
    val liveAperture: Float? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    /** What the open lens actually granted, for the diagnostics report. */
    val streamSummary: String = "",
    /** Non-null while the diagnostics sheet is open. */
    val diagnostics: String? = null,
    /** Lenses that failed or took the camera down on this device. */
    val troubled: Set<String> = emptySet(),
    /** While true, dragging on the frame sets white balance instead of focusing. */
    val whiteBalanceAdjust: Boolean = false,
    /** The file types the user allows at all, set in settings. */
    val saveJpeg: Boolean = true,
    val saveHeic: Boolean = false,
    val saveRaw: Boolean = true,
    val settingsOpen: Boolean = false,
) {
    /** What the shutter can be set to here: what is allowed, filtered by what this lens can do. */
    val formatChoices: List<CaptureFormat>
        get() = formatChoices(saveJpeg, saveHeic, saveRaw, capabilities)

    val selectedLens: Lens? get() = lenses.firstOrNull { it.id == selectedLensId }

    /** True when everything this lens *can* be told manually is set manually. */
    val everythingManual: Boolean
        get() = settings.anyManual &&
            (!capabilities.supportsManualSensor || settings.manualExposure) &&
            (!capabilities.supportsManualFocus || settings.manualFocus) &&
            (!capabilities.supportsManualWhiteBalance || settings.manualWhiteBalance)
}

class CameraViewModel(application: Application) : AndroidViewModel(application),
    CameraController.Listener {

    private val controller = CameraController(application, this)

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var surface: SurfaceTexture? = null
    private var resumed = false
    private var openedLensId: String? = null
    private var statusJob: Job? = null
    private val memory = LensMemory(application)
    private val appSettings = AppSettings(application)
    private var armedLensId: String? = null

    init {
        // Before touching a camera: if a lens was mid-open when the app last went away, that is
        // the one that took it down. Some firmware needs a reboot after this, so it must not be
        // opened again by accident.
        memory.takeUnfinishedOpen()?.let { crashed ->
            memory.markTroubled(crashed)
            showError("Lens $crashed did not come back last time - handle with care")
        }

        _state.update {
            it.copy(
                saveJpeg = appSettings.saveJpeg,
                saveHeic = appSettings.saveHeic,
                saveRaw = appSettings.saveRaw,
            )
        }

        viewModelScope.launch {
            // Enumerating lenses touches every camera's characteristics; keep it off the main thread.
            val lenses = withContext(Dispatchers.Default) { controller.lenses }
            _state.update { current ->
                current.copy(
                    lenses = lenses,
                    troubled = memory.troubled(),
                    // Never start on a lens known to misbehave.
                    selectedLensId = current.selectedLensId
                        ?: lenses.firstOrNull { it.warning == null && it.id !in memory.troubled() }
                            ?.id
                        ?: lenses.firstOrNull()?.id,
                )
            }
            openIfReady()
        }
    }

    // region lifecycle plumbing

    fun onSurfaceAvailable(texture: SurfaceTexture) {
        surface = texture
        openIfReady()
    }

    fun onSurfaceDestroyed() {
        surface = null
        openedLensId = null
        controller.close()
    }

    fun onResume() {
        resumed = true
        openIfReady()
    }

    fun onPause() {
        resumed = false
        openedLensId = null
        controller.close()
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }

    /** Opening is driven by three independent events, so make it idempotent. */
    private fun openIfReady() {
        val texture = surface ?: return
        val lensId = _state.value.selectedLensId ?: return
        if (!resumed || openedLensId == lensId) return
        openedLensId = lensId
        controller.open(lensId, texture)
    }

    // endregion

    // region user actions

    fun selectLens(lensId: String) {
        if (_state.value.selectedLensId == lensId) return
        val lens = _state.value.lenses.firstOrNull { it.id == lensId } ?: return

        // A lens that looks like a helper sensor, or that already went wrong here, takes two
        // taps. The first one explains what is about to happen.
        val risk = riskReason(lens)
        if (risk != null && armedLensId != lensId) {
            armedLensId = lensId
            showError("$risk - tap again to open it anyway")
            return
        }

        armedLensId = null
        _state.update { it.copy(selectedLensId = lensId) }
        openIfReady()
    }

    private fun riskReason(lens: Lens): String? = when {
        lens.id in memory.troubled() -> "Lens ${lens.id} went wrong on this phone before"
        lens.warning != null -> "Lens ${lens.id} ${lens.warning}"
        else -> null
    }

    /** Step to the next combination this lens and these settings allow. */
    fun cycleFormat() {
        val choices = _state.value.formatChoices
        val next = choices[(choices.indexOf(_state.value.settings.format) + 1) % choices.size]
        updateSettings { it.copy(format = next) }
    }

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun setSaveJpeg(enabled: Boolean) {
        appSettings.saveJpeg = enabled
        _state.update { it.copy(saveJpeg = enabled) }
        ensureFormatAllowed()
    }

    fun setSaveHeic(enabled: Boolean) {
        appSettings.saveHeic = enabled
        _state.update { it.copy(saveHeic = enabled) }
        ensureFormatAllowed()
    }

    fun setSaveRaw(enabled: Boolean) {
        appSettings.saveRaw = enabled
        _state.update { it.copy(saveRaw = enabled) }
        ensureFormatAllowed()
    }

    /** Switching a file type off must not leave the shutter set to something it may not write. */
    private fun ensureFormatAllowed() {
        val choices = _state.value.formatChoices
        if (_state.value.settings.format !in choices) {
            updateSettings { it.copy(format = choices.first()) }
        }
    }

    fun setDeviceRotation(degrees: Int) {
        if (_state.value.settings.deviceRotation == degrees) return
        updateSettings { it.copy(deviceRotation = degrees) }
    }

    /** Flip exposure, focus and white balance together. */
    fun setEverythingManual(manual: Boolean) {
        val caps = _state.value.capabilities
        updateSettings { current ->
            current.copy(
                manualExposure = manual && caps.supportsManualSensor,
                iso = if (manual) seedIso(current) else current.iso,
                exposureTimeNs = if (manual) seedExposure(current) else current.exposureTimeNs,
                manualFocus = manual && caps.supportsManualFocus,
                focusDiopters = if (manual) seedFocus(current) else current.focusDiopters,
                manualWhiteBalance = manual && caps.supportsManualWhiteBalance,
            )
        }
        if (!manual) controller.clearMetering()
    }

    fun setManualExposure(manual: Boolean) {
        if (manual && !_state.value.capabilities.supportsManualSensor) {
            showError("This lens has no manual sensor control")
            return
        }
        updateSettings { current ->
            current.copy(
                manualExposure = manual,
                iso = if (manual) seedIso(current) else current.iso,
                exposureTimeNs = if (manual) seedExposure(current) else current.exposureTimeNs,
            )
        }
    }

    // Touching a slider IS the decision to go manual - there is no separate switch to find.
    fun setIso(iso: Int) = updateSettings { current ->
        current.copy(
            manualExposure = true,
            iso = iso,
            exposureTimeNs = if (current.manualExposure) current.exposureTimeNs
            else seedExposure(current),
        )
    }

    fun setExposureTime(nanos: Long) = updateSettings { current ->
        current.copy(
            manualExposure = true,
            exposureTimeNs = nanos,
            iso = if (current.manualExposure) current.iso else seedIso(current),
        )
    }

    /** Hand exposure back to the camera. ISO and shutter go together: the hardware AE is one unit. */
    fun exposureAuto() = updateSettings { it.copy(manualExposure = false) }

    fun setExposureCompensation(steps: Int) = updateSettings { it.copy(exposureCompensation = steps) }

    fun setManualFocus(manual: Boolean) {
        if (manual && !_state.value.capabilities.supportsManualFocus) {
            showError("This lens is fixed focus")
            return
        }
        updateSettings { current ->
            current.copy(
                manualFocus = manual,
                focusDiopters = if (manual) seedFocus(current) else current.focusDiopters,
            )
        }
        if (!manual) controller.clearMetering()
    }

    fun setFocusDiopters(diopters: Float) =
        updateSettings { it.copy(manualFocus = true, focusDiopters = diopters) }

    fun focusAuto() {
        updateSettings { it.copy(manualFocus = false) }
        controller.clearMetering()
    }

    fun setManualWhiteBalance(manual: Boolean) {
        if (manual && !_state.value.capabilities.supportsManualWhiteBalance) {
            showError("This lens has no manual white balance")
            return
        }
        updateSettings { it.copy(manualWhiteBalance = manual) }
    }

    fun setKelvin(kelvin: Int) =
        updateSettings { it.copy(manualWhiteBalance = true, kelvin = kelvin) }

    fun whiteBalanceAuto() {
        _state.update { it.copy(whiteBalanceAdjust = false) }
        updateSettings { it.copy(manualWhiteBalance = false, tint = 0f) }
    }

    /** One of the three fixed lights. Picking one leaves any fine tuning in place. */
    fun setWhiteBalancePreset(kelvin: Int) {
        _state.update { it.copy(whiteBalanceAdjust = false) }
        updateSettings { it.copy(manualWhiteBalance = true, kelvin = kelvin) }
    }

    /**
     * Hand white balance to the frame itself: while this is on, dragging across the preview sets
     * temperature sideways and tint up and down, which is quicker than any slider and lets you
     * watch the image instead of a number.
     */
    fun toggleWhiteBalanceAdjust() {
        val turningOn = !_state.value.whiteBalanceAdjust
        _state.update { it.copy(whiteBalanceAdjust = turningOn) }
        if (turningOn) {
            updateSettings { it.copy(manualWhiteBalance = true) }
            onStatus("Drag the frame: sideways for warmth, up and down for tint")
        }
    }

    /** Drag deltas as a fraction of the preview size. */
    fun dragWhiteBalance(horizontal: Float, vertical: Float) {
        updateSettings { current ->
            current.copy(
                manualWhiteBalance = true,
                kelvin = (current.kelvin + horizontal * KELVIN_SPAN).roundToInt()
                    .coerceIn(MIN_KELVIN, MAX_KELVIN),
                tint = (current.tint + vertical * 2f).coerceIn(-1f, 1f),
            )
        }
    }

    /**
     * A tap on the frame means "focus here" either way: autofocus gets a metering point, manual
     * focus borrows autofocus once and keeps the distance it lands on.
     */
    fun tapPreview(normalizedX: Float, normalizedY: Float) {
        if (_state.value.whiteBalanceAdjust) return
        if (_state.value.settings.manualFocus) {
            controller.rackFocusAt(normalizedX, normalizedY)
        } else {
            controller.focusAt(normalizedX, normalizedY)
        }
    }

    override fun onFocusRacked(diopters: Float) {
        updateSettings { it.copy(manualFocus = true, focusDiopters = diopters) }
        onStatus("Focus set to ${"%.2f".format(diopters)} dpt")
    }

    fun capture() = controller.capture()

    /** Forget the tap-to-focus point and go back to continuous autofocus. */
    fun resetFocusPoint() = controller.clearMetering()

    /**
     * Collect what this device reports about its cameras. Probing every id takes a moment, so it
     * happens off the main thread and the sheet fills in when it is ready.
     */
    fun openDiagnostics() {
        _state.update { it.copy(diagnostics = "Collecting…") }
        viewModelScope.launch {
            val text = withContext(Dispatchers.Default) { buildDiagnostics() }
            _state.update { if (it.diagnostics != null) it.copy(diagnostics = text) else it }
        }
    }

    fun closeDiagnostics() {
        _state.update { it.copy(diagnostics = null) }
    }

    /** Copying closes the sheet, so there is visible proof the button did something. */
    fun copiedDiagnostics() {
        closeDiagnostics()
        onStatus("Report copied to clipboard")
    }

    private fun buildDiagnostics(): String {
        val current = _state.value
        val application = getApplication<Application>()
        val manager = application.getSystemService(CameraManager::class.java)
        val openState = buildString {
            val lens = current.selectedLens
            if (lens == null) {
                appendLine("OPEN: none")
            } else {
                appendLine("OPEN: id ${lens.id} (${lens.facingLabel} ${lens.zoomLabel}, ${lens.origin})")
                appendLine("  ${current.streamSummary}")
                appendLine(
                    "  raw=${current.capabilities.supportsRaw}" +
                        " sensor=${current.capabilities.supportsManualSensor}" +
                        " focus=${current.capabilities.supportsManualFocus}" +
                        " wb=${current.capabilities.supportsManualWhiteBalance}"
                )
            }
            appendLine("OFFERED: ${current.lenses.size} lenses - " +
                current.lenses.joinToString(", ") { "${it.id}:${it.facingLabel}" })
            val flagged = current.lenses.filter { it.warning != null }
            if (flagged.isNotEmpty()) {
                appendLine("FLAGGED:")
                flagged.forEach { appendLine("  id ${it.id}: ${it.warning}") }
            }
            if (current.troubled.isNotEmpty()) {
                appendLine("WENT WRONG HERE: ${current.troubled.sorted().joinToString(", ")}")
            }
            current.error?.let { appendLine("LAST ERROR: $it") }
        }
        return Diagnostics.report(application, manager, openState.trimEnd())
    }

    private fun updateSettings(transform: (CaptureSettings) -> CaptureSettings) {
        val updated = transform(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        controller.updateSettings(updated)
    }

    private fun seedIso(settings: CaptureSettings): Int =
        _state.value.liveIso ?: settings.iso

    private fun seedExposure(settings: CaptureSettings): Long =
        _state.value.liveExposureNs ?: settings.exposureTimeNs

    private fun seedFocus(settings: CaptureSettings): Float =
        _state.value.liveFocusDiopters ?: settings.focusDiopters

    // endregion

    // region controller callbacks

    override fun onLensOpened(
        lens: Lens,
        capabilities: LensCapabilities,
        previewSize: Size,
        streams: String,
    ) {
        val settings = clampToCapabilities(_state.value.settings, capabilities)
        _state.update {
            it.copy(
                capabilities = capabilities,
                settings = settings,
                streamSummary = streams,
                // The preview arrives rotated for the display, so the portrait aspect is inverted.
                previewAspect = previewSize.height.toFloat() / previewSize.width.toFloat(),
                liveIso = null,
                liveExposureNs = null,
                liveFocusDiopters = null,
                liveAperture = null,
                error = null,
            )
        }
        controller.updateSettings(settings)
    }

    override fun onLiveValues(
        iso: Int?,
        exposureTimeNs: Long?,
        focusDiopters: Float?,
        aperture: Float?,
    ) {
        _state.update {
            it.copy(
                liveIso = iso,
                liveExposureNs = exposureTimeNs,
                liveFocusDiopters = focusDiopters,
                liveAperture = aperture,
            )
        }
    }

    override fun onCaptureBusy(busy: Boolean) {
        _state.update { it.copy(busy = busy) }
    }

    override fun onStatus(message: String) {
        _state.update { it.copy(status = message, error = null) }
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            delay(2_500)
            _state.update { if (it.status == message) it.copy(status = null) else it }
        }
    }

    override fun onError(message: String) = showError(message)

    private fun showError(message: String) {
        // A camera the system hides can refuse to open. Forget what we thought was open so
        // picking the same lens again actually retries instead of doing nothing.
        openedLensId = null
        _state.update { it.copy(error = message, busy = false, troubled = memory.troubled()) }
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            delay(4_000)
            _state.update { if (it.error == message) it.copy(error = null) else it }
        }
    }

    // endregion

    /** A lens the app switches to may not reach as far, as fast or as high as the last one. */
    private fun clampToCapabilities(
        settings: CaptureSettings,
        capabilities: LensCapabilities,
    ): CaptureSettings {
        val allowed = formatChoices(
            _state.value.saveJpeg,
            _state.value.saveHeic,
            _state.value.saveRaw,
            capabilities,
        )
        val format = if (settings.format in allowed) settings.format else allowed.first()
        return settings.copy(
            manualExposure = settings.manualExposure && capabilities.supportsManualSensor,
            iso = capabilities.isoRange?.clamp(settings.iso) ?: settings.iso,
            exposureTimeNs = capabilities.exposureTimeRange?.clamp(settings.exposureTimeNs)
                ?: settings.exposureTimeNs,
            exposureCompensation = capabilities.exposureCompensationRange
                .clamp(settings.exposureCompensation),
            manualFocus = settings.manualFocus && capabilities.supportsManualFocus,
            focusDiopters = settings.focusDiopters.coerceIn(0f, capabilities.minFocusDistance),
            manualWhiteBalance = settings.manualWhiteBalance &&
                capabilities.supportsManualWhiteBalance,
            format = format,
        )
    }
}
