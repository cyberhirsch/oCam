package com.ocam.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Finds a QR code in a picture. There is no Android in it, and no network and no Play services. */
object QrReader {

    private val HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** @return what the code in these ARGB pixels says, or null if none could be read. */
    fun read(pixels: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, pixels)
        // A threshold from each point's surroundings copes with uneven light, but a code in shadow
        // in a bright room loses its pale border to the brightness beside it. A threshold from the
        // whole picture does not, so it gets the second try.
        return decode(BinaryBitmap(HybridBinarizer(source)))
            ?: decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
    }

    private fun decode(picture: BinaryBitmap): String? = try {
        QRCodeReader().decode(picture, HINTS).text?.takeIf { it.isNotEmpty() }
    } catch (e: ReaderException) {
        null
    }
}

enum class QrKind(val label: String) {
    LINK("LINK"),
    EMAIL("EMAIL"),
    PHONE("PHONE"),
    MESSAGE("SMS"),
    PLACE("PLACE"),
    WIFI("WIFI"),
    TEXT("TEXT"),
}

/**
 * What a code says, and what may be done with it.
 *
 * @property openable whether another app may be handed it. A code is whatever a stranger stuck on a
 *   wall, so only schemes that show the person something first are offered, never by themselves, and
 *   anything that can name a component of an app, like intent:, stays plain text.
 */
class QrContent(val text: String, val kind: QrKind, val openable: Boolean) {
    companion object {
        fun of(text: String): QrContent {
            val trimmed = text.trim()
            val scheme = trimmed.substringBefore(':', "").lowercase()
            return when (scheme) {
                "http", "https" -> QrContent(trimmed, QrKind.LINK, true)
                "mailto" -> QrContent(trimmed, QrKind.EMAIL, true)
                "tel" -> QrContent(trimmed, QrKind.PHONE, true)
                "sms", "smsto" -> QrContent(trimmed, QrKind.MESSAGE, true)
                "geo" -> QrContent(trimmed, QrKind.PLACE, true)
                "wifi" -> QrContent(trimmed, QrKind.WIFI, false)
                else -> QrContent(trimmed, QrKind.TEXT, false)
            }
        }
    }
}

/**
 * Decides when a code is news. One held in view is read several times a second and must only come up
 * once, and one that was dismissed must not come straight back while it is still in the frame. It
 * has to have been out of sight for [forgetAfterMs] before it counts as seen again.
 */
class QrTracker(private val forgetAfterMs: Long = 2_000L) {
    private var known: String? = null
    private var lastSeenMs = 0L

    /** @return the text to show, if this reading is a code not already up or dismissed. */
    fun offer(text: String?, nowMs: Long): String? {
        val held = known
        if (held != null && nowMs - lastSeenMs >= forgetAfterMs) known = null
        if (text == null) return null
        lastSeenMs = nowMs
        if (text == known) return null
        known = text
        return text
    }

    fun reset() {
        known = null
    }
}
