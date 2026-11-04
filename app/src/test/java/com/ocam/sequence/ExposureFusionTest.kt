package com.ocam.sequence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExposureFusionTest {

    private val size = 64

    private fun ByteArray.at(index: Int) = this[index].toInt() and 0xFF

    private fun frame(relative: Float = 1f, chroma: Int = 128, luma: (Int, Int) -> Int) = BracketFrame(
        ByteArray(size * size) { luma(it % size, it / size).toByte() },
        ByteArray(size * size / 2) { chroma.toByte() },
        relative,
    )

    private fun fuse(vararg frames: BracketFrame) =
        ByteArray(size * size * 3 / 2).also { ExposureFusion.fuse(frames.toList(), size, size, it) }

    @Test
    fun identicalFramesComeBackAsTheyWere() {
        val texture = frame { x, y -> (x * 3 + y * 5 + (x xor y) * 7) % 256 }
        val out = fuse(texture, texture, texture)
        for (i in 0 until size * size) {
            assertTrue("luma $i", abs(out.at(i) - texture.luma[i].toInt().and(0xFF)) <= 1)
        }
    }

    @Test
    fun chromaComesBackAsItWas() {
        val first = BracketFrame(
            ByteArray(size * size) { 128.toByte() },
            ByteArray(size * size / 2) { (if (it % 2 == 0) 200 else 60).toByte() },
            1f,
        )
        val out = fuse(first, first)
        assertEquals(200, out.at(size * size))
        assertEquals(60, out.at(size * size + 1))
    }

    @Test
    fun eachRegionComesFromTheFrameThatExposedIt() {
        // Left: a bright window, clipped in the long frame. Right: a shadow, black in the short one.
        val short = frame(0.25f) { x, _ -> if (x < size / 2) 128 else 6 }
        val long = frame(4f) { x, _ -> if (x < size / 2) 255 else 128 }
        val out = fuse(short, long)
        assertTrue("window ${out.at(8 * size + 4)}", out.at(8 * size + 4) < 180)
        assertTrue("shadow ${out.at(8 * size + 60)}", out.at(8 * size + 60) > 70)
    }

    @Test
    fun oddSizedLevelsStillCoverEveryPixel() {
        val width = 70
        val height = 46
        val level = BracketFrame(
            ByteArray(width * height) { 90.toByte() },
            ByteArray(width * height / 2) { 128.toByte() },
            1f,
        )
        val out = ByteArray(width * height * 3 / 2)
        ExposureFusion.fuse(listOf(level, level), width, height, out)
        for (i in 0 until width * height) assertTrue(abs(out.at(i) - 90) <= 1)
    }

    @Test
    fun aFrameWithNothingBrightNeedsNoGainMap() {
        // sRGB 50 is two stops under sRGB 100, so both frames measured the same light, and a picture
        // that shows that light has nothing to add.
        val base = frame { _, _ -> 100 }
        val dark = frame(0.25f) { _, _ -> 50 }
        val shown = ByteArray(size * size * 3 / 2) { 100.toByte() }
        assertNull(ExposureFusion.gainMap(listOf(base, dark), shown, size, size))
    }

    @Test
    fun highlightsTheShortFrameCouldReadGainHeadroom() {
        val base = frame { x, _ -> if (x < size / 2) 255 else 100 }
        val short = frame(0.25f) { x, _ -> if (x < size / 2) 200 else 58 }
        val fused = ByteArray(size * size * 3 / 2) { 128.toByte() }
        for (i in 0 until size * size) fused[i] = (if (i % size < size / 2) 200 else 100).toByte()
        val map = ExposureFusion.gainMap(listOf(base, short), fused, size, size)
        assertNotNull(map)
        map!!
        assertEquals(size / 4, map.width)
        // sRGB 200 is 0.578 of white, seen a quarter as long: four times what the picture shows.
        assertEquals(4f, map.ratioMax, 0.3f)
        assertEquals(255, map.pixels[0].toInt() and 0xFF)
        assertTrue(abs(map.pixels[map.width - 1].toInt() and 0xFF) < 40)
    }

    @Test
    fun gainStaysWithinItsLimit() {
        val base = frame { _, _ -> 255 }
        val short = frame(0.01f) { _, _ -> 200 }
        val fused = ByteArray(size * size * 3 / 2) { 40.toByte() }
        val map = ExposureFusion.gainMap(listOf(base, short), fused, size, size, limit = 8f)
        assertEquals(8f, map!!.ratioMax, 0.001f)
    }

    @Test
    fun stopsStartFromTheExposureSet() {
        assertEquals(listOf(0f, -2f, 2f), bracketStops(3, 2f))
        assertEquals(listOf(0f, -1f, 1f, -2f, 2f), bracketStops(5, 2f))
    }

    @Test
    fun theShutterMovesFirst() {
        assertEquals(100 to 40_000_000L, bracketExposure(100, 10_000_000L, 2f, 50, 3200, 1_000L, 1_000_000_000L))
        assertEquals(100 to 2_500_000L, bracketExposure(100, 10_000_000L, -2f, 50, 3200, 1_000L, 1_000_000_000L))
    }

    @Test
    fun sensitivityMakesUpWhatTheShutterCannotReach() {
        assertEquals(200 to 20_000_000L, bracketExposure(100, 10_000_000L, 2f, 50, 3200, 1_000L, 20_000_000L))
        assertEquals(50 to 5_000_000L, bracketExposure(100, 10_000_000L, -2f, 50, 3200, 5_000_000L, 1_000_000_000L))
    }

    @Test
    fun anExposureThatCannotBeReachedStopsAtTheLimit() {
        assertEquals(100 to 20_000_000L, bracketExposure(100, 10_000_000L, 3f, 50, 100, 1_000L, 20_000_000L))
    }

    @Test
    fun frameCountsAreOffered() {
        assertArrayEquals(intArrayOf(3, 5), HDR_FRAMES.toIntArray())
    }
}
