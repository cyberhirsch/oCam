package com.ocam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.ocam.appVersion

/**
 * A plain-text report of what this device's cameras actually say about themselves, including the
 * ones the app decided not to offer and why. Written to be pasted into a bug report: without a
 * device in hand this is the only way to tell a lens that is missing from a lens that refused.
 */
object Diagnostics {

    fun report(context: Context, manager: CameraManager, openState: String): String = buildString {
        appendLine("oCam ${appVersion(context)}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine(openState)
        appendLine()
        appendLine("CAMERAS")

        val listed = runCatching { manager.cameraIdList }.getOrDefault(emptyArray()).toSet()
        val physical = listed.flatMap { id ->
            runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                .getOrDefault(emptySet())
        }.toSet()

        appendLine("  getCameraIdList: ${listed.sorted().joinToString(", ").ifEmpty { "(empty)" }}")
        appendLine("  physical ids:    ${physical.sorted().joinToString(", ").ifEmpty { "(none)" }}")
        appendLine()

        val probed = (0 until 100).map { it.toString() }
        for (id in (listed + physical + probed).distinct()) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            val origin = when {
                id in listed -> LensOrigin.LISTED
                id in physical -> LensOrigin.PHYSICAL
                else -> LensOrigin.HIDDEN
            }
            appendLine(
                "  id $id  [${origin.name.lowercase()}]  ${describe(characteristics, origin)}"
            )
        }
    }

    private fun describe(
        characteristics: CameraCharacteristics,
        origin: LensOrigin,
    ): String {
        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.joinToString("/") { "%.1f".format(it) } ?: "?"
        val level = when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()?.mapNotNull(::capabilityName)?.joinToString(",") ?: "none"

        val jpeg = characteristics.jpegSize()?.let { "${it.width}x${it.height}" } ?: "-"
        val raw = characteristics.rawSize()?.let { "${it.width}x${it.height}" } ?: "-"
        val previews = characteristics.streamMap()
            ?.getOutputSizes(ImageFormat.JPEG)?.size ?: 0

        // The same rule the lens list uses, so the report can never disagree with the app.
        val verdict = characteristics.skipReason(origin)?.let { "SKIPPED: $it" } ?: "offered"

        return "$facing ${focal}mm $level jpeg=$jpeg raw=$raw sizes=$previews\n" +
            "        caps: $caps\n" +
            "        -> $verdict"
    }

    private fun capabilityName(capability: Int): String? = when (capability) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE"
        else -> null
    }
}
