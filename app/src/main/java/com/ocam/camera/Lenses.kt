package com.ocam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How a camera turned up, which is also roughly how likely it is to open cleanly. */
enum class LensOrigin {
    /** Advertised by getCameraIdList - the ones every app sees. */
    LISTED,

    /** A physical sub-camera of a logical multi-camera. */
    PHYSICAL,

    /** Not advertised anywhere; found by asking for the id directly. */
    HIDDEN,
}

/** How far to probe for ids the system does not advertise. */
private const val PROBE_LIMIT = 100

/**
 * A camera the app can actually open. Phones expose their extra sensors in three different ways
 * and plenty use only the last one, so all three are searched: the public id list, the physical
 * sub-cameras of a logical multi-camera, and ids that answer only when asked for by name.
 */
data class Lens(
    val id: String,
    val facing: Int,
    val focalLengthMm: Float,
    /** Diagonal-equivalent focal length on 35mm film, 0 when the sensor size is unknown. */
    val equivalent35mm: Int,
    /** Focal length relative to the default lens on the same side, e.g. 0.5x, 1x, 3x. */
    val zoom: Float,
    val origin: LensOrigin,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    /** Non-null when this looks like a helper sensor rather than a camera worth opening. */
    val warning: String? = null,
) {
    val facingLabel: String
        get() = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            else -> "Ext"
        }

    val zoomLabel: String
        get() {
            val rounded = (zoom * 10f).roundToInt() / 10f
            return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
        }

    val detailLabel: String
        get() = buildString {
            if (equivalent35mm > 0) append("${equivalent35mm}mm") else append("%.1fmm".format(focalLengthMm))
            append(" · id ").append(id)
            when (origin) {
                LensOrigin.PHYSICAL -> append(" · sub")
                LensOrigin.HIDDEN -> append(" · hidden")
                LensOrigin.LISTED -> Unit
            }
        }
}

/** Everything the UI needs to know about what this lens can be told to do. */
data class LensCapabilities(
    val isoRange: Range<Int>?,
    val exposureTimeRange: Range<Long>?,
    /** Closest focus in diopters (1/m). 0 means the lens is fixed focus. */
    val minFocusDistance: Float,
    val apertures: List<Float>,
    val supportsManualSensor: Boolean,
    val supportsManualWhiteBalance: Boolean,
    /** The CONTROL_AWB_MODE values this camera accepts - its own fixed illuminants. */
    val awbModes: Set<Int>,
    val supportsRaw: Boolean,
    val supportsHeic: Boolean,
    val hasAutoFocus: Boolean,
) {
    /** The named illuminants this camera offers, in the order they are shown. */
    val whiteBalancePresets: List<WhiteBalance>
        get() = WhiteBalance.entries.filter { it != WhiteBalance.CUSTOM && it.awbMode in awbModes }

    val supportsManualIso: Boolean get() = supportsManualSensor && isoRange != null
    val supportsManualShutter: Boolean get() = supportsManualSensor && exposureTimeRange != null
    val supportsManualFocus: Boolean get() = minFocusDistance > 0f
    val supportsManualAperture: Boolean get() = apertures.size > 1

    companion object {
        val EMPTY = LensCapabilities(
            isoRange = null,
            exposureTimeRange = null,
            minFocusDistance = 0f,
            apertures = emptyList(),
            supportsManualSensor = false,
            supportsManualWhiteBalance = false,
            awbModes = emptySet(),
            supportsRaw = false,
            supportsHeic = false,
            hasAutoFocus = false,
        )
    }
}

fun CameraCharacteristics.hasCapability(capability: Int): Boolean =
    get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(capability) == true

fun CameraCharacteristics.capabilities(): LensCapabilities {
    val afModes = get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
    return LensCapabilities(
        isoRange = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
        exposureTimeRange = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
        minFocusDistance = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        apertures = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
        supportsManualSensor = hasCapability(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        ),
        supportsManualWhiteBalance = hasCapability(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        ),
        awbModes = get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toList()?.toSet().orEmpty(),
        supportsRaw = hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            rawSize() != null,
        supportsHeic = supportsHeic(),
        hasAutoFocus = afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF },
    )
}

fun CameraCharacteristics.streamMap(): StreamConfigurationMap? =
    get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

fun CameraCharacteristics.rawSize(): Size? =
    streamMap()?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }

fun CameraCharacteristics.jpegSize(): Size? = stillSize(ImageFormat.JPEG)

fun CameraCharacteristics.stillSize(format: Int): Size? =
    runCatching { streamMap()?.getOutputSizes(format) }.getOrNull()
        ?.maxByOrNull { it.width.toLong() * it.height }

/** Whether the camera itself can encode HEIC; not every device's firmware offers it. */
fun CameraCharacteristics.supportsHeic(): Boolean = stillSize(ImageFormat.HEIC) != null

/**
 * Every openable camera on the device, main lenses first and then the physical sub-cameras that
 * a logical camera hides. Cameras that are not backward compatible (depth/IR helpers) are skipped
 * because they cannot serve a preview.
 */
fun enumerateLenses(manager: CameraManager): List<Lens> {
    val found = LinkedHashMap<String, Pair<CameraCharacteristics, LensOrigin>>()

    fun consider(id: String, origin: LensOrigin) {
        if (found.containsKey(id)) return
        val characteristics =
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        if (characteristics.skipReason(origin) != null) return
        found[id] = characteristics to origin
    }

    // 1. What the system advertises - usually just one camera per side.
    val topLevelIds = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
    topLevelIds.forEach { consider(it, LensOrigin.LISTED) }

    // 2. Physical sub-cameras, on the devices that declare a logical multi-camera.
    for (id in topLevelIds) {
        val logical = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
        runCatching { logical.physicalCameraIds }.getOrDefault(emptySet())
            .forEach { consider(it, LensOrigin.PHYSICAL) }
    }

    // 3. The ones the system hides. Plenty of phones keep their extra sensors out of
    //    getCameraIdList() and out of any logical camera: the id answers only if asked for
    //    directly. Probing is the only way to find them.
    for (id in 0 until PROBE_LIMIT) consider(id.toString(), LensOrigin.HIDDEN)

    val raw = found.map { (id, entry) ->
        val (characteristics, origin) = entry
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: 0f
        Lens(
            id = id,
            facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_EXTERNAL,
            focalLengthMm = focal,
            equivalent35mm = characteristics.equivalent35mm(focal),
            zoom = 1f,
            origin = origin,
            supportsRaw = characteristics.hasCapability(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ) && characteristics.rawSize() != null,
            supportsManualSensor = characteristics.hasCapability(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            warning = characteristics.helperSensorWarning(),
        )
    }

    // The first camera the system lists for a side is its default lens, so everything else is
    // expressed as a zoom factor against that one - which is how phone cameras are labelled.
    return raw
        .map { lens ->
            val reference = raw.firstOrNull { it.facing == lens.facing }
            val referenceFocal = reference?.equivalent35mm?.takeIf { it > 0 }
                ?: reference?.focalLengthMm?.takeIf { it > 0f }?.roundToInt()
            val own = lens.equivalent35mm.takeIf { it > 0 }
                ?: lens.focalLengthMm.takeIf { it > 0f }?.roundToInt()
            if (referenceFocal != null && own != null) {
                lens.copy(zoom = own.toFloat() / referenceFocal.toFloat())
            } else {
                lens
            }
        }
        .sortedWith(compareBy({ facingOrder(it.facing) }, { it.zoom }, { it.id }))
}

/**
 * Why this camera is not offered, or null when it is.
 *
 * How hard the test is depends on how the camera was found. One the system advertises is one the
 * system means for apps, so it is taken at its word unless it says outright that it is something
 * else. One found only by probing ids has made no such claim, and on these phones that is where
 * the depth and assist sensors live - the ones that take the camera service down with them - so
 * it has to look like a photo camera before it is offered: a real focal length, no depth or
 * motion-tracking role, and an image big enough to be a picture.
 */
fun CameraCharacteristics.skipReason(origin: LensOrigin): String? {
    if (!hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
        return "not backward compatible"
    }
    if (streamMap() == null) return "no stream map"
    // Reserved for privileged apps: a normal app is not allowed to open it, and some firmware
    // handles the refusal badly.
    if (hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA)) {
        return "reserved for system apps"
    }
    // An infrared sensor is not a photo camera, whatever else it claims.
    if (get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ==
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR
    ) {
        return "infrared sensor"
    }
    // Nothing that cannot produce a still image is a camera for this app's purposes.
    val jpeg = jpegSize() ?: return "no still image output"

    if (origin != LensOrigin.HIDDEN) return null

    if (hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        return "hidden and reports itself as a depth sensor"
    }
    if (hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING)) {
        return "hidden and reports itself as a motion tracking sensor"
    }
    if (get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.isNotEmpty() != true) {
        return "hidden and reports no focal length"
    }
    // Even a phone's macro camera is a couple of megapixels; an assist sensor is a fraction of one.
    if (jpeg.width.toLong() * jpeg.height < 1_500_000L) {
        return "hidden and its largest image is only ${jpeg.width}x${jpeg.height}"
    }
    return null
}

/**
 * Softer signals: these lenses are still offered, because the guesses are not certain enough to
 * hide a camera someone might want, but they are worth a warning before opening.
 */
private fun CameraCharacteristics.helperSensorWarning(): String? {
    if (hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        return "reports itself as a depth sensor"
    }
    if (hasCapability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING)) {
        return "reports itself as a motion tracking sensor"
    }
    val jpeg = jpegSize() ?: return null
    if (jpeg.width.toLong() * jpeg.height < 1_000_000L) {
        return "largest image is only ${jpeg.width}x${jpeg.height}"
    }
    return null
}

private fun CameraCharacteristics.equivalent35mm(focalLengthMm: Float): Int {
    val sensor = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0
    val diagonal = hypot(sensor.width, sensor.height)
    if (diagonal <= 0f || focalLengthMm <= 0f) return 0
    return (focalLengthMm * 43.267f / diagonal).roundToInt()
}

private fun facingOrder(facing: Int): Int = when (facing) {
    CameraCharacteristics.LENS_FACING_BACK -> 0
    CameraCharacteristics.LENS_FACING_FRONT -> 1
    else -> 2
}
