package com.ocam.camera

import android.content.Context

/**
 * Remembers which cameras went wrong on *this* device.
 *
 * No amount of metadata inspection catches every sensor that a manufacturer exposes but does not
 * want opened, and on some firmware the attempt takes down the camera service until the phone is
 * rebooted. So the app writes down which lens it is about to open before it opens it. If that note
 * is still there next time the app starts, that lens is what went down with it - and it is not
 * offered again without an explicit second tap.
 */
class LensMemory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("lens_memory", Context.MODE_PRIVATE)

    /**
     * The lens that was being opened when the app last stopped without finishing. Reading it also
     * clears it, so it is only ever reported once.
     */
    fun takeUnfinishedOpen(): String? {
        val pending = prefs.getString(KEY_PENDING, null) ?: return null
        prefs.edit().remove(KEY_PENDING).commit()
        return pending
    }

    /**
     * Written synchronously on purpose: if the camera service dies it may take the process with
     * it, and an asynchronous write would never reach disk.
     */
    fun beginOpen(lensId: String) {
        prefs.edit().putString(KEY_PENDING, lensId).commit()
    }

    fun openSucceeded(lensId: String) {
        prefs.edit()
            .remove(KEY_PENDING)
            .putStringSet(KEY_TROUBLED, troubled() - lensId)
            .apply()
    }

    fun openFailed(lensId: String) {
        markTroubled(lensId)
    }

    fun markTroubled(lensId: String) {
        prefs.edit()
            .remove(KEY_PENDING)
            .putStringSet(KEY_TROUBLED, troubled() + lensId)
            .apply()
    }

    fun troubled(): Set<String> = prefs.getStringSet(KEY_TROUBLED, emptySet()).orEmpty()

    private companion object {
        const val KEY_PENDING = "pending_open"
        const val KEY_TROUBLED = "troubled"
    }
}
