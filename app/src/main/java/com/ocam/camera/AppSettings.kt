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
        const val KEY_HIDDEN = "hidden_lenses"
        const val KEY_NAME_PREFIX = "lens_name_"
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
