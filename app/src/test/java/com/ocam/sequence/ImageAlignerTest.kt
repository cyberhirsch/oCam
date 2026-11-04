package com.ocam.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ImageAlignerTest {

    private val width = 320
    private val height = 240

    private fun scene(x: Float, y: Float): Float =
        128f + 50f * sin(x * 0.21f + 2f * sin(y * 0.06f)) * sin(y * 0.19f + 2f * sin(x * 0.04f)) +
            40f * sin(x * 0.03f + y * 0.05f)

    /** The picture in which the point [transform] carries a point to shows what that point shows. */
    private fun picture(transform: Similarity): GrayImage {
        val back = transform.inverse()
        val out = GrayImage(width, height)
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        for (row in 0 until height) for (column in 0 until width) {
            val x = column - cx
            val y = row - cy
            out.data[row * width + column] = scene(back.mapX(x, y), back.mapY(x, y))
        }
        return out
    }

    private val fixed = picture(Similarity.IDENTITY)

    private fun assertClose(expected: Similarity, actual: Similarity) {
        assertEquals(expected.a, actual.a, 0.002f)
        assertEquals(expected.b, actual.b, 0.002f)
        assertEquals(expected.tx, actual.tx, 0.15f)
        assertEquals(expected.ty, actual.ty, 0.15f)
    }

    @Test
    fun findsAShift() {
        val truth = Similarity(1f, 0f, 7.5f, -4.25f)
        val found = ImageAligner.align(fixed, picture(truth), Similarity.IDENTITY, allowScale = false)
        assertTrue(found.reliable)
        assertClose(truth, found.transform)
    }

    @Test
    fun findsATurn() {
        val angle = 0.03f
        val truth = Similarity(cos(angle), sin(angle), 3f, 2f)
        val found = ImageAligner.align(fixed, picture(truth), Similarity.IDENTITY, allowScale = false)
        assertTrue(found.reliable)
        assertClose(truth, found.transform)
    }

    @Test
    fun findsAChangeOfSize() {
        val truth = Similarity(1.02f, 0f, 1f, -1.5f)
        val found = ImageAligner.align(fixed, picture(truth), Similarity.IDENTITY, allowScale = true)
        assertTrue(found.reliable)
        assertClose(truth, found.transform)
    }

    @Test
    fun aGoodStartReachesFarAcross() {
        val truth = Similarity(1f, 0f, 96f, 6f)
        val seed = Similarity(1f, 0f, 90f, 0f)
        val found = ImageAligner.align(fixed, picture(truth), seed, allowScale = false)
        assertTrue(found.reliable)
        assertClose(truth, found.transform)
    }

    @Test
    fun nothingToSeeIsNotBelieved() {
        val blank = GrayImage(width, height).also { it.data.fill(100f) }
        val found = ImageAligner.align(blank, blank, Similarity.IDENTITY, allowScale = false)
        assertFalse(found.reliable)
    }

    @Test
    fun compositionAndInverseUndoEachOther() {
        val move = Similarity(1.1f, 0.2f, 5f, -3f)
        val undone = move.inverse().after(move)
        assertEquals(1f, undone.a, 1e-5f)
        assertEquals(0f, undone.b, 1e-5f)
        assertEquals(0f, undone.tx, 1e-4f)
        assertEquals(0f, undone.ty, 1e-4f)
    }

    @Test
    fun warpingLinesAFrameUpWithTheOneItCameFrom() {
        val size = width * height
        val lumaOf = { image: GrayImage ->
            ByteArray(size) { image.data[it].toInt().coerceIn(0, 255).toByte() }
        }
        val truth = Similarity(1f, 0f, 3f, 1f)
        val moved = lumaOf(picture(truth))
        val outY = ByteArray(size)
        val outVu = ByteArray(size / 2)
        warpNv21(moved, ByteArray(size / 2) { 128.toByte() }, width, height, truth, outY, outVu)
        val original = lumaOf(fixed)
        var sum = 0
        var count = 0
        for (row in 8 until height - 8) for (column in 8 until width - 8) {
            val i = row * width + column
            sum += kotlin.math.abs((outY[i].toInt() and 0xFF) - (original[i].toInt() and 0xFF))
            count++
        }
        assertTrue("error ${sum.toFloat() / count}", sum.toFloat() / count < 2f)
    }
}
