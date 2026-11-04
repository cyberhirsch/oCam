package com.ocam.sequence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameMergerTest {

    private fun frame(width: Int, height: Int, luma: Int, v: Int = 128, u: Int = 128) =
        ByteArray(width * height) { luma.toByte() } to
            ByteArray(width * height / 2) { (if (it % 2 == 0) v else u).toByte() }

    private fun FrameMerger.nv21() = ByteArray(width * height * 3 / 2).also { writeNv21(it) }

    private fun ByteArray.at(index: Int) = this[index].toInt() and 0xFF

    @Test
    fun theFirstFrameIsTakenWhole() {
        // A gate on the first frame would leave its shadows black and its chroma green.
        val merger = FrameMerger.create(4, 4, SequenceMode.TRAILS)
        val (y, vu) = frame(4, 4, luma = 2, v = 90, u = 200)
        merger.add(y, vu)
        val out = merger.nv21()
        assertEquals(2, out.at(0))
        assertEquals(90, out.at(16))
        assertEquals(200, out.at(17))
    }

    @Test
    fun lightenKeepsTheBrightestLightSeen() {
        val merger = FrameMerger.create(4, 4, SequenceMode.PAINT)
        val (y, vu) = frame(4, 4, luma = 10)
        merger.add(y, vu)
        val (bright, brightVu) = frame(4, 4, luma = 10)
        bright[5] = 250.toByte()
        merger.add(bright, brightVu)
        val (dark, darkVu) = frame(4, 4, luma = 10)
        merger.add(dark, darkVu)
        val out = merger.nv21()
        assertEquals(250, out.at(5))
        assertEquals(10, out.at(0))
    }

    @Test
    fun noiseBelowTheGateDoesNotClimb() {
        val merger = FrameMerger.create(4, 4, SequenceMode.TRAILS)
        val (y, vu) = frame(4, 4, luma = 20)
        merger.add(y, vu)
        repeat(50) {
            val (noisy, noisyVu) = frame(4, 4, luma = 20 + SequenceMode.TRAILS.gate)
            merger.add(noisy, noisyVu)
        }
        assertEquals(20, merger.nv21().at(0))
    }

    @Test
    fun colourFollowsTheLightThatWon() {
        val merger = FrameMerger.create(4, 4, SequenceMode.PAINT)
        val (y, vu) = frame(4, 4, luma = 10)
        merger.add(y, vu)

        // A red light in the top left block only.
        val (lit, litVu) = frame(4, 4, luma = 10, v = 240, u = 90)
        for (i in intArrayOf(0, 1, 4, 5)) lit[i] = 200.toByte()
        merger.add(lit, litVu)

        // A dimmer frame of another colour must not repaint it.
        val (dim, dimVu) = frame(4, 4, luma = 50, v = 60, u = 200)
        merger.add(dim, dimVu)

        val out = merger.nv21()
        assertEquals(240, out.at(16))
        assertEquals(90, out.at(17))
        // The next block saw only the dim frame rise above what it had.
        assertEquals(60, out.at(18))
        assertEquals(200, out.at(19))
    }

    @Test
    fun averageIsTheMeanOfEveryFrame() {
        val merger = FrameMerger.create(4, 2, SequenceMode.LONG)
        for (level in listOf(0, 100, 200)) {
            val (y, vu) = frame(4, 2, luma = level, v = level, u = 255 - level)
            merger.add(y, vu)
        }
        val out = merger.nv21()
        assertEquals(100, out.at(0))
        assertEquals(100, out.at(8))
        assertEquals(155, out.at(9))
        assertEquals(3, merger.frames)
    }

    @Test
    fun averageOfManyFramesDoesNotDrift() {
        val merger = FrameMerger.create(2, 2, SequenceMode.LONG)
        repeat(10_000) {
            val (y, vu) = frame(2, 2, luma = 255, v = 255, u = 0)
            merger.add(y, vu)
        }
        val out = merger.nv21()
        assertEquals(255, out.at(0))
        assertEquals(255, out.at(4))
        assertEquals(0, out.at(5))
    }

    @Test
    fun scalingAveragesLumaSoThinLinesSurvive() {
        val merger = FrameMerger.create(4, 4, SequenceMode.PAINT)
        val y = ByteArray(16)
        y[0] = 200.toByte()
        y[1] = 100.toByte()
        merger.add(y, ByteArray(8) { 128.toByte() })
        val out = ByteArray(2 * 2 * 3 / 2)
        merger.writeScaledNv21(2, 2, 2, out)
        assertEquals(75, out.at(0))
        assertEquals(0, out.at(1))
    }

    @Test
    fun scalingToAPartialSizeCrops() {
        val merger = FrameMerger.create(8, 8, SequenceMode.LONG)
        val (y, vu) = frame(8, 8, luma = 40)
        merger.add(y, vu)
        val out = ByteArray(2 * 2 * 3 / 2)
        merger.writeScaledNv21(3, 2, 2, out)
        assertEquals(40, out.at(3))
    }

    @Test
    fun everyRowIsMergedHoweverTheBandsFall() {
        val width = 64
        val height = 66
        val merger = FrameMerger.create(width, height, SequenceMode.PAINT)
        val (y, vu) = frame(width, height, luma = 10)
        merger.add(y, vu)
        val lit = ByteArray(width * height) { 200.toByte() }
        merger.add(lit, vu)
        val out = merger.nv21()
        for (i in 0 until width * height) assertEquals(200, out.at(i))
    }

    @Test
    fun thumbnailIsNoLongerThanAsked() {
        val merger = FrameMerger.create(64, 48, SequenceMode.LONG)
        val (y, vu) = frame(64, 48, luma = 128)
        merger.add(y, vu)
        val small = merger.thumbnail(16)
        assertEquals(16, small.width)
        assertEquals(12, small.height)
        assertEquals(yuvToArgb(128, 128, 128), small.argb[0])
    }

    @Test
    fun neutralChromaIsGrey() {
        assertEquals(0xFF808080.toInt(), yuvToArgb(128, 128, 128))
        assertEquals(0xFF000000.toInt(), yuvToArgb(0, 128, 128))
        assertEquals(0xFFFFFFFF.toInt(), yuvToArgb(255, 128, 128))
    }

    @Test
    fun fullVIsRed() {
        val argb = yuvToArgb(128, 128, 255)
        assertEquals(255, argb shr 16 and 0xFF)
        assertEquals(37, argb shr 8 and 0xFF)
        assertEquals(128, argb and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anOddFrameIsRefused() {
        FrameMerger.create(3, 4, SequenceMode.LONG)
    }

    @Test
    fun lumaIsReadPastRowPadding() {
        val plane = ByteBuffer.wrap(byteArrayOf(1, 2, 0, 0, 3, 4, 0, 0))
        val out = ByteArray(4)
        readLuma(plane, rowStride = 4, width = 2, height = 2, out)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out)
    }

    @Test
    fun interleavedChromaIsReadWithoutRunningOffTheLastRow() {
        // VUVU, the common layout: each plane stops one byte short of the other's last sample.
        val bytes = byteArrayOf(10, 20, 11, 21, 0, 0, 12, 22, 13, 23)
        val v = ByteBuffer.wrap(bytes, 0, 9).slice()
        val u = ByteBuffer.wrap(bytes, 1, 9).slice()
        val out = ByteArray(8)
        readChroma(v, u, 2, 6, 4, 4, ByteArray(4), ByteArray(4), out)
        assertArrayEquals(byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23), out)
    }

    @Test
    fun separateChromaPlanesAreInterleaved() {
        val v = ByteBuffer.wrap(byteArrayOf(10, 11, 12, 13))
        val u = ByteBuffer.wrap(byteArrayOf(20, 21, 22, 23))
        val out = ByteArray(8)
        readChroma(v, u, 1, 2, 4, 4, ByteArray(4), ByteArray(4), out)
        assertArrayEquals(byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23), out)
    }
}
