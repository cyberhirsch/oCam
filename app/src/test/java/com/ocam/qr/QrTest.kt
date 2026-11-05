package com.ocam.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QrTest {

    private val width = 640
    private val height = 480
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A code of [size] pixels in a mid grey room, the way it lands in a preview. */
    private fun picture(
        text: String,
        size: Int = 300,
        ink: Int = black,
        paper: Int = white,
        room: Int = 0xFF777777.toInt(),
    ): IntArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val left = (width - size) / 2
        val top = (height - size) / 2
        return IntArray(width * height) { i ->
            val x = i % width - left
            val y = i / width - top
            when {
                x !in 0 until size || y !in 0 until size -> room
                code.get(x, y) -> ink
                else -> paper
            }
        }
    }

    @Test
    fun readsACode() {
        val text = "https://example.com/a?b=c"
        assertEquals(text, QrReader.read(picture(text), width, height))
    }

    @Test
    fun readsASmallCode() {
        assertEquals("small", QrReader.read(picture("small", 90), width, height))
    }

    @Test
    fun readsACodeOnItsSide() {
        val upright = picture("turned", 300)
        val turned = IntArray(width * height)
        // A half turn stays the same shape, and a code does not care which way up it is.
        for (i in upright.indices) turned[turned.size - 1 - i] = upright[i]
        assertEquals("turned", QrReader.read(turned, width, height))
    }

    @Test
    fun readsThroughADimRoom() {
        // Everything darker together, the room as well as the code.
        val dim = picture(
            "dim", 300, ink = 0xFF080808.toInt(), paper = 0xFF505050.toInt(), room = 0xFF282828.toInt(),
        )
        assertEquals("dim", QrReader.read(dim, width, height))
    }

    @Test
    fun readsACodeInShadowInABrightRoom() {
        val shaded = picture(
            "shaded", 300, ink = 0xFF101010.toInt(), paper = 0xFF505050.toInt(), room = 0xFF777777.toInt(),
        )
        assertEquals("shaded", QrReader.read(shaded, width, height))
    }

    @Test
    fun readsThroughNoise() {
        val random = Random(7)
        val noisy = picture("noisy").map { pixel ->
            val level = ((pixel and 0xFF) + random.nextInt(-40, 41)).coerceIn(0, 255)
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }.toIntArray()
        assertEquals("noisy", QrReader.read(noisy, width, height))
    }

    @Test
    fun aPictureWithNoCodeSaysNothing() {
        val random = Random(3)
        val empty = IntArray(width * height) { random.nextInt() or black }
        assertNull(QrReader.read(empty, width, height))
    }

    @Test
    fun aPlainPictureSaysNothing() {
        assertNull(QrReader.read(IntArray(width * height) { 0xFF777777.toInt() }, width, height))
    }

    @Test
    fun whatMayBeOpenedIsNamed() {
        assertEquals(QrKind.LINK, QrContent.of("https://example.com").kind)
        assertEquals(QrKind.LINK, QrContent.of("HTTP://EXAMPLE.COM").kind)
        assertEquals(QrKind.EMAIL, QrContent.of("mailto:a@example.com").kind)
        assertEquals(QrKind.PHONE, QrContent.of("tel:+49123").kind)
        assertEquals(QrKind.MESSAGE, QrContent.of("smsto:+49123:hello").kind)
        assertEquals(QrKind.PLACE, QrContent.of("geo:47.8,12.1").kind)
        assertTrue(QrContent.of(" https://example.com \n").openable)
        assertEquals("https://example.com", QrContent.of(" https://example.com \n").text)
    }

    @Test
    fun nothingElseMayBeOpened() {
        val refused = listOf(
            "intent://x#Intent;component=com.bank/.Pay;end",
            "javascript:alert(1)",
            "file:///sdcard/secret",
            "content://com.other/secret",
            "market://details?id=x",
            "WIFI:S:home;T:WPA;P:secret;;",
            "just some words",
            "",
        )
        for (text in refused) assertFalse(text, QrContent.of(text).openable)
    }

    @Test
    fun wifiIsNamedButNotJoined() {
        val wifi = QrContent.of("WIFI:S:home;T:WPA;P:secret;;")
        assertEquals(QrKind.WIFI, wifi.kind)
        assertFalse(wifi.openable)
    }

    @Test
    fun aCodeHeldInViewComesUpOnce() {
        val tracker = QrTracker()
        assertEquals("a", tracker.offer("a", 0L))
        assertNull(tracker.offer("a", 300L))
        assertNull(tracker.offer("a", 600L))
    }

    @Test
    fun aCodeStaysDismissedWhileItIsInView() {
        val tracker = QrTracker(forgetAfterMs = 2_000L)
        tracker.offer("a", 0L)
        // Long after it first came up, but it has never left the frame.
        for (now in 300L..9_000L step 300L) assertNull(tracker.offer("a", now))
    }

    @Test
    fun aMomentOutOfFocusDoesNotBringItBack() {
        val tracker = QrTracker(forgetAfterMs = 2_000L)
        tracker.offer("a", 0L)
        assertNull(tracker.offer(null, 500L))
        assertNull(tracker.offer("a", 1_000L))
    }

    @Test
    fun aCodeThatLeftAndCameBackIsNewsAgain() {
        val tracker = QrTracker(forgetAfterMs = 2_000L)
        tracker.offer("a", 0L)
        assertNull(tracker.offer(null, 2_500L))
        assertEquals("a", tracker.offer("a", 3_000L))
    }

    @Test
    fun aDifferentCodeIsNews() {
        val tracker = QrTracker()
        tracker.offer("a", 0L)
        assertEquals("b", tracker.offer("b", 300L))
    }
}
