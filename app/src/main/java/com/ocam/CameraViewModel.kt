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
import com.ocam.camera.WhiteBalance
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
    /** The preview buffer, in the sensor's own landscape numbers. */
    val previewWidth: Int = 1440,
    val previewHeight: Int = 1080,
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
    /** Lenses the owner has taken out of the picker, and the names they gave them. */
    val hiddenLenses: Set<String> = emptySet(),
    val lensNames: Map<String, String> = emptyMap(),
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

    /**
     * What the picker offers. Hiding is the owner's own judgement about which of this phone's
     * cameras are cameras, so it wins over anything the app worked out - except that hiding the
     * lot would leave nothing to open, and then the list stands as it is.
     */
    val visibleLenses: List<Lens>
        get() = lenses.filter { it.id !in hiddenLenses }.ifEmpty { lenses }

    /** What to call a lens: the owner's name for it, or what the phone says it is. */
    fun lensLabel(lens: Lens): String = lensNames[lens.id] ?: lens.zoomLabel
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
                hiddenLenses = appSettings.hiddenLenses,
                lensNames = appSettings.lensNames(),
            )
        }

        viewModelScope.launch {
            // Enumerating lenses touches every camera's characteristics; keep it off the main thread.
            val lenses = withContext(Dispatchers.Default) { controller.lenses }
            _state.update { current ->
                current.copy(
                    lenses = lenses,
                    troubled = memory.troubled(),
                    // Never start on a lens known to misbehave, or on one that has been hidden.
                    selectedLensId = current.selectedLensId
                        ?: lenses.firstOrNull {
                            it.warning == null &&
                                it.id !in memory.troubled() &&
                                it.id !in current.hiddenLenses
                        }?.id
                        ?: lenses.firstOrNull { it.id !in current.hiddenLenses }?.id
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

    /**
     * Take a lens out of the picker, or put it back. This is how a phone's depth and assist
     * sensors get out of the way for good: no rule the app applies can be right on every phone,
     * but the person holding this one knows which buttons show a picture.
     */
    fun setLensHidden(id: String, hidden: Boolean) {
        val current = _state.value
        if (hidden && current.lenses.count { it.id !in current.hiddenLenses } <= 1) {
            showError("That is the last lens left; hide another one first")
            return
        }
        val updated = if (hidden) current.hiddenLenses + id else current.hiddenLenses - id
        appSettings.hiddenLenses = updated
        _state.update { it.copy(hiddenLenses = updated) }

        // Hiding the lens that is open would leave the picker with nothing marked; move first.
        if (hidden && current.selectedLensId == id) {
            _state.value.visibleLenses.firstOrNull()?.let { next ->
                armedLensId = next.id
                selectLens(next.id)
            }
        }
    }

    /** Name a lens something that means something here. Blank restores what the phone calls it. */
    fun setLensName(id: String, name: String) {
        appSettings.setLensName(id, name)
        _state.update { it.copy(lensNames = appSettings.lensNames()) }
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

    fun setIso(iso: Int) = updateSettings { it.copy(iso = iso) }

    fun setExposureTime(nanos: Long) = updateSettings { it.copy(exposureTimeNs = nanos) }

    fun setFocusDiopters(diopters: Float) = updateSettings { it.copy(focusDiopters = diopters) }

    /**
     * The one reading taken from the camera's own meter, as the lens opens. It lands in the same
     * two values the sliders write, so from here on there is nothing to tell them apart - and
     * nothing that will move them again except the user.
     */
    override fun onMeteredExposure(iso: Int, exposureTimeNs: Long) {
        updateSettings { it.copy(iso = iso, exposureTimeNs = exposureTimeNs) }
    }

    /** One of the camera's own fixed lights. Picking one drops any fine tuning done by hand. */
    fun setWhiteBalance(preset: WhiteBalance) {
        _state.update { it.copy(whiteBalanceAdjust = false) }
        updateSettings { it.copy(whiteBalance = preset, tint = 0f) }
    }

    /**
     * Hand white balance to the frame itself: while this is on, dragging across the preview sets
     * temperature sideways and tint up and down, which is quicker than any slider and lets you
     * watch the image instead of a number.
     */
    fun toggleWhiteBalanceAdjust() {
        if (!_state.value.capabilities.supportsManualWhiteBalance) {
            showError("This lens only offers its own fixed lights")
            return
        }
        val turningOn = !_state.value.whiteBalanceAdjust
        _state.update { it.copy(whiteBalanceAdjust = turningOn) }
        if (turningOn) {
            // Start from where the current light already is, so nothing jumps on the first drag.
            updateSettings {
                it.copy(
                    kelvin = if (it.whiteBalance == WhiteBalance.CUSTOM) it.kelvin
                    else it.whiteBalance.kelvin,
                    whiteBalance = WhiteBalance.CUSTOM,
                )
            }
            onStatus("Drag the frame: sideways for warmth, up and down for tint")
        }
    }

    /** Drag deltas as a fraction of the preview size. */
    fun dragWhiteBalance(horizontal: Float, vertical: Float) {
        updateSettings { current ->
            current.copy(
                whiteBalance = WhiteBalance.CUSTOM,
                kelvin = (current.kelvin + horizontal * KELVIN_SPAN).roundToInt()
                    .coerceIn(MIN_KELVIN, MAX_KELVIN),
                tint = (current.tint + vertical * 2f).coerceIn(-1f, 1f),
            )
        }
    }

    /**
     * A tap on the frame means "look there once". The lens searches, and the distance it lands
     * on becomes the distance it holds - which is the only way a fixed focus distance can be
     * found quickly.
     */
    fun tapPreview(normalizedX: Float, normalizedY: Float) {
        if (_state.value.whiteBalanceAdjust) return
        controller.rackFocusAt(normalizedX, normalizedY)
    }

    override fun onFocusRacked(diopters: Float) {
        updateSettings { it.copy(focusDiopters = diopters) }
    }

    fun capture() = controller.capture()

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
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
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
        // A key the camera omits for one frame must not blank the readout: keep the last value
        // it actually reported.
        _state.update {
            it.copy(
                liveIso = iso ?: it.liveIso,
                liveExposureNs = exposureTimeNs ?: it.liveExposureNs,
                liveFocusDiopters = focusDiopters ?: it.liveFocusDiopters,
                liveAperture = aperture ?: it.liveAperture,
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
        // A light the last lens offered may not be one this lens has.
        val presets = capabilities.whiteBalancePresets
        val whiteBalance = when {
            settings.whiteBalance == WhiteBalance.CUSTOM &&
                capabilities.supportsManualWhiteBalance -> settings.whiteBalance
            settings.whiteBalance in presets -> settings.whiteBalance
            else -> presets.firstOrNull() ?: WhiteBalance.DAYLIGHT
        }
        return settings.copy(
            iso = capabilities.isoRange?.clamp(settings.iso) ?: settings.iso,
            exposureTimeNs = capabilities.exposureTimeRange?.clamp(settings.exposureTimeNs)
                ?: settings.exposureTimeNs,
            focusDiopters = settings.focusDiopters.coerceIn(0f, capabilities.minFocusDistance),
            whiteBalance = whiteBalance,
            format = format,
        )
    }
}
