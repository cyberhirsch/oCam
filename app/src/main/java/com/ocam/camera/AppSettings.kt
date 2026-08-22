package com.ocam.camera

import android.content.Context

/**
 * What the app should be willing to write. This is the standing choice - which file types are in
 * play at all - kept apart from the per-shot choice on the camera screen, which only picks between
 * the combinations these allow.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var saveJpeg: Boolean
        get() = prefs.getBoolean(KEY_JPEG, true)
        set(value) = prefs.edit().putBoolean(KEY_JPEG, value).apply()

    var saveHeic: Boolean
        get() = prefs.getBoolean(KEY_HEIC, false)
        set(value) = prefs.edit().putBoolean(KEY_HEIC, value).apply()

    var saveRaw: Boolean
        get() = prefs.getBoolean(KEY_RAW, true)
        set(value) = prefs.edit().putBoolean(KEY_RAW, value).apply()

    private companion object {
        const val KEY_JPEG = "save_jpeg"
        const val KEY_HEIC = "save_heic"
        const val KEY_RAW = "save_raw"
    }
}

/**
 * The combinations the shutter can be set to, given what the user allows and what this lens can
 * actually do. Never empty: with everything switched off there is still a JPEG to fall back on.
 */
fun formatChoices(
    saveJpeg: Boolean,
    saveHeic: Boolean,
    saveRaw: Boolean,
    capabilities: LensCapabilities,
): List<CaptureFormat> {
    val heic = saveHeic && capabilities.supportsHeic
    val raw = saveRaw && capabilities.supportsRaw
    val choices = buildList {
        if (saveJpeg) add(CaptureFormat.JPEG)
        if (heic) add(CaptureFormat.HEIC)
        if (raw) add(CaptureFormat.RAW)
        if (raw && saveJpeg) add(CaptureFormat.RAW_JPEG)
        if (raw && heic) add(CaptureFormat.RAW_HEIC)
    }
    return choices.ifEmpty { listOf(CaptureFormat.JPEG) }
}
