package com.ocam

import android.app.Application
import android.graphics.SurfaceTexture
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ocam.camera.CameraController
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
) {
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

    init {
        viewModelScope.launch {
            // Enumerating lenses touches every camera's characteristics; keep it off the main thread.
            val lenses = withContext(Dispatchers.Default) { controller.lenses }
            _state.update { current ->
                current.copy(
                    lenses = lenses,
                    selectedLensId = current.selectedLensId ?: lenses.firstOrNull()?.id,
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
        _state.update { it.copy(selectedLensId = lensId) }
        openIfReady()
    }

    fun setFormat(format: CaptureFormat) = updateSettings { it.copy(format = format) }

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

    fun whiteBalanceAuto() = updateSettings { it.copy(manualWhiteBalance = false) }

    fun capture() = controller.capture()

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        if (_state.value.settings.manualFocus) return
        controller.focusAt(normalizedX, normalizedY)
    }

    /** Forget the tap-to-focus point and go back to continuous autofocus. */
    fun resetFocusPoint() = controller.clearMetering()

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

    override fun onLensOpened(lens: Lens, capabilities: LensCapabilities, previewSize: Size) {
        val settings = clampToCapabilities(_state.value.settings, capabilities)
        _state.update {
            it.copy(
                capabilities = capabilities,
                settings = settings,
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
        _state.update { it.copy(error = message, busy = false) }
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
        val format = when {
            settings.format.writesRaw && !capabilities.supportsRaw -> CaptureFormat.JPEG
            else -> settings.format
        }
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
