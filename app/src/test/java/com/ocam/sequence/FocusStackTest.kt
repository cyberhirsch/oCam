package com.ocam.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class FocusStackTest {

    private val width = 256
    private val height = 192
    private val cx = (width - 1) / 2f
    private val cy = (height - 1) / 2f

    /** Fine and coarse detail everywhere, defined at every point so it can be moved exactly. */
    private fun scene(x: Float, y: Float): Float =
        128f + 45f * sin(x * 0.53f + 3f * sin(y * 0.11f)) * sin(y * 0.47f + 2f * sin(x * 0.07f)) +
            30f * sin(x * 0.09f + y * 0.05f)

    private fun render(transform: Similarity = Similarity.IDENTITY): ByteArray =
        ByteArray(width * height) { i ->
            val x = i % width - cx
            val y = i / width - cy
            // What the frame shows at a point is what the first frame shows at the point mapped back.
            val back = transform.inverse()
            scene(back.mapX(x, y), back.mapY(x, y)).toInt().coerceIn(0, 255).toByte()
        }

    private fun blurred(luma: ByteArray, radius: Int, where: (Int, Int) -> Boolean): ByteArray {
        val out = luma.copyOf()
        for (row in radius until height - radius) for (column in radius until width - radius) {
            if (!where(column, row)) continue
            var sum = 0
            for (down in -radius..radius) for (across in -radius..radius) {
                sum += luma[(row + down) * width + column + across].toInt() and 0xFF
            }
            out[row * width + column] = (sum / ((2 * radius + 1) * (2 * radius + 1))).toByte()
        }
        return out
    }

    private fun grey() = ByteArray(width * height / 2) { 128.toByte() }

    private fun error(picture: ByteArray, target: ByteArray): Float {
        var sum = 0
        var count = 0
        for (row in 12 until height - 12) for (column in 12 until width - 12) {
            val i = row * width + column
            sum += abs((picture[i].toInt() and 0xFF) - (target[i].toInt() and 0xFF))
            count++
        }
        return sum.toFloat() / count
    }

    private fun stack(vararg frames: ByteArray, chroma: List<ByteArray>? = null): Pair<FocusStack, ByteArray> {
        val stack = FocusStack(width, height)
        frames.forEachIndexed { index, luma -> stack.add(luma, chroma?.get(index) ?: grey()) }
        val out = ByteArray(width * height * 3 / 2)
        stack.writeNv21(out)
        return stack to out
    }

    @Test
    fun identicalFramesComeBackAsTheyWere() {
        val sharp = render()
        val (_, out) = stack(sharp, sharp, sharp)
        assertTrue("error ${error(out, sharp)}", error(out, sharp) < 1f)
    }

    @Test
    fun eachRegionComesFromTheFrameFocusedThere() {
        val sharp = render()
        val nearSharp = blurred(sharp, 3) { column, _ -> column >= width / 2 }
        val farSharp = blurred(sharp, 3) { column, _ -> column < width / 2 }
        val (_, out) = stack(nearSharp, farSharp)
        val merged = error(out, sharp)
        val alone = error(nearSharp, sharp)
        assertTrue("merged $merged against $alone", merged < alone / 3f)
    }

    @Test
    fun colourGoesWithTheSharpFrame() {
        val sharp = render()
        val nearSharp = blurred(sharp, 3) { column, _ -> column >= width / 2 }
        val farSharp = blurred(sharp, 3) { column, _ -> column < width / 2 }
        val warm = ByteArray(width * height / 2) { 200.toByte() }
        val cool = ByteArray(width * height / 2) { 60.toByte() }
        val (_, out) = stack(nearSharp, farSharp, chroma = listOf(warm, cool))
        val middle = width * height + (height / 4) * width
        assertEquals(200, out[middle + width / 4 / 2 * 2].toInt() and 0xFF)
        assertEquals(60, out[middle + width * 3 / 4 / 2 * 2].toInt() and 0xFF)
    }

    @Test
    fun aLensThatBreathesIsLinedUp() {
        val sharp = render()
        val breathing = render(Similarity(1.015f, 0f, 1.2f, -0.8f))
        val nearSharp = blurred(sharp, 3) { column, _ -> column >= width / 2 }
        val farSharp = blurred(breathing, 3) { column, _ -> column < width / 2 }
        val (stack, out) = stack(nearSharp, farSharp)
        assertEquals(0, stack.unaligned)
        val merged = error(out, sharp)
        assertTrue("merged $merged", merged < error(nearSharp, sharp) / 2f)
    }

    @Test
    fun aFlatFrameIsLeftWhereItIs() {
        val flat = ByteArray(width * height) { 90.toByte() }
        val (stack, out) = stack(flat, flat)
        assertEquals(1, stack.unaligned)
        for (i in 0 until width * height) assertTrue(abs((out[i].toInt() and 0xFF) - 90) <= 1)
    }

    @Test
    fun distancesAreEvenInDioptersFromNearToFar() {
        val distances = stackDistances(4f, 5, 2)
        assertEquals(listOf(4f, 3.5f, 3f, 2.5f, 2f), distances)
    }

    @Test
    fun aReachOfZeroGoesToInfinity() {
        val distances = stackDistances(2f, 3, 0)
        assertEquals(listOf(2f, 1f, 0f), distances)
    }

    @Test
    fun focusAlreadyFarAwayLeavesNothingToStack() {
        assertTrue(stackDistances(0f, 9, 2).isEmpty())
    }
}
