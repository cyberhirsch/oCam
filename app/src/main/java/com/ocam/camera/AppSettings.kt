package com.ocam.camera

import android.content.Context

/**
 * The standing choices: which file types the app may write, and what to make of this phone's
 * cameras. Both are kept apart from the per-shot choices on the camera screen.
 *
 * Which of a phone's cameras are really cameras cannot be settled from metadata alone - every
 * rule that hides a depth sensor on one phone hides a macro lens on another - so the last word
 * is the owner's: any lens can be hidden from the picker and given a name that means something.
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

    /**
     * Whether the camera should straighten its lens on the JPEG or HEIC. On by default: a phone's
     * wide lenses bend straight lines enough to be the first thing you notice.
     */
    var undistort: Boolean
        get() = prefs.getBoolean(KEY_UNDISTORT, true)
        set(value) = prefs.edit().putBoolean(KEY_UNDISTORT, value).apply()

    /**
     * Whether to write position and heading into the photographs. On by default, but it only has
     * anything to write once the location permission has been granted - and it can be switched
     * off here without touching the permission.
     */
    var recordPose: Boolean
        get() = prefs.getBoolean(KEY_POSE, true)
        set(value) = prefs.edit().putBoolean(KEY_POSE, value).apply()

    /** The film simulation in use, by file name, or null while the camera is left alone. */
    var lutName: String?
        get() = prefs.getString(KEY_LUT, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_LUT) else putString(KEY_LUT, value)
        }.apply()

    /** What is drawn over the frame, and how the shutter is released. */
    var aids: ShootingAids
        get() = ShootingAids(
            peaking = prefs.getBoolean(KEY_PEAKING, false),
            falseColor = prefs.getBoolean(KEY_FALSE_COLOR, false),
            level = prefs.getBoolean(KEY_LEVEL, false),
            meter = prefs.getBoolean(KEY_METER, true),
            grid = enumOr(prefs.getString(KEY_GRID, null), GridMode.OFF),
            frame = enumOr(prefs.getString(KEY_FRAME, null), CropFrame.NONE),
            timer = enumOr(prefs.getString(KEY_TIMER, null), SelfTimer.OFF),
            volumeShutter = prefs.getBoolean(KEY_VOLUME_SHUTTER, true),
        )
        set(value) = prefs.edit()
            .putBoolean(KEY_PEAKING, value.peaking)
            .putBoolean(KEY_FALSE_COLOR, value.falseColor)
            .putBoolean(KEY_LEVEL, value.level)
            .putBoolean(KEY_METER, value.meter)
            .putString(KEY_GRID, value.grid.name)
            .putString(KEY_FRAME, value.frame.name)
            .putString(KEY_TIMER, value.timer.name)
            .putBoolean(KEY_VOLUME_SHUTTER, value.volumeShutter)
            .apply()

    /** A stored name from an older build may no longer exist; the default stands in for it. */
    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    /** Lens ids the owner has taken out of the picker. */
    var hiddenLenses: Set<String>
        // The returned set must not be handed back to SharedPreferences after being mutated -
        // it is the live instance - so it is copied on the way out.
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN, value.toSet()).apply()

    /** What the owner calls this lens, or null while it goes by what the phone says it is. */
    fun lensName(id: String): String? =
        prefs.getString(KEY_NAME_PREFIX + id, null)?.takeIf { it.isNotBlank() }

    fun setLensName(id: String, name: String) {
        val trimmed = name.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_NAME_PREFIX + id) else putString(KEY_NAME_PREFIX + id, trimmed)
        }.apply()
    }

    /** Every name the owner has given, by lens id. */
    fun lensNames(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith(KEY_NAME_PREFIX) }
        .mapNotNull { (key, value) ->
            val name = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key.removePrefix(KEY_NAME_PREFIX) to name
        }
        .toMap()

    private companion object {
        const val KEY_JPEG = "save_jpeg"
        const val KEY_HEIC = "save_heic"
        const val KEY_RAW = "save_raw"
        const val KEY_UNDISTORT = "undistort"
        const val KEY_POSE = "record_pose"
        const val KEY_LUT = "lut_name"
        const val KEY_HIDDEN = "hidden_lenses"
        const val KEY_NAME_PREFIX = "lens_name_"
        const val KEY_PEAKING = "aid_peaking"
        const val KEY_FALSE_COLOR = "aid_false_color"
        const val KEY_LEVEL = "aid_level"
        const val KEY_METER = "aid_meter"
        const val KEY_GRID = "aid_grid"
        const val KEY_FRAME = "aid_frame"
        const val KEY_TIMER = "aid_timer"
        const val KEY_VOLUME_SHUTTER = "aid_volume_shutter"
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
