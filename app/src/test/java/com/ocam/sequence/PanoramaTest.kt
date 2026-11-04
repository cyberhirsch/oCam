package com.ocam.sequence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

class PanoramaTest {

    private val width = 320
    private val height = 240
    private val focal = 300f

    /** What a camera on a turntable sees, by angle around it and height up the cylinder around it. */
    private fun scene(angle: Float, up: Float): Float =
        128f + 45f * sin(angle * 37f + 2f * sin(up * 11f)) * sin(up * 41f + 2f * sin(angle * 7f)) +
            35f * sin(angle * 5f + up * 3f)

    /** The flat frame a camera pointing at [yawDegrees] takes of it. */
    private fun frame(yawDegrees: Float, y: ByteArray, vu: ByteArray, tiltPixels: Float = 0f) {
        val yaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        for (row in 0 until height) for (column in 0 until width) {
            val x = column - cx
            val down = row - cy + tiltPixels
            val value = scene(yaw + atan(x / focal), down / sqrt(x * x + focal * focal))
            y[row * width + column] = value.toInt().coerceIn(0, 255).toByte()
        }
        vu.fill(128.toByte())
    }

    private fun sweep(vararg yaws: Float) = PanoramaFrames { index, y, vu -> frame(yaws[index], y, vu) }

    /** How far what was stitched is from the scene, by working out what should be at each point. */
    private fun error(result: StitchedPanorama, firstYaw: Float): Float {
        val yaw = Math.toRadians(firstYaw.toDouble()).toFloat()
        var sum = 0f
        var count = 0
        for (row in result.top until result.top + result.height) {
            for (column in result.left until result.left + result.width) {
                val angle = yaw + (column - result.originX) / (focal * result.scale)
                val up = (row - result.originY) / (focal * result.scale)
                val value = result.pixels[row * result.canvasWidth + column].toInt() and 0xFF
                sum += abs(value - scene(angle, up))
                count++
            }
        }
        return sum / count
    }

    @Test
    fun framesFromATurningCameraBecomeTheScene() {
        val yaws = floatArrayOf(0f, 18f, 36f, 54f)
        val shots = yaws.map { PanoramaShot(it, 0f) }
        val result = PanoramaStitcher.stitch(shots, width, height, focal, Long.MAX_VALUE, sweep(*yaws))
        assertEquals(0, result.joinsBySensor)
        assertEquals(1f, result.scale, 0f)
        // 54 degrees of turning, 283 pixels, on top of a frame 294 wide on the cylinder.
        assertTrue("wide ${result.width}", result.width > 550)
        assertTrue("tall ${result.height}", result.height > height * 3 / 4)
        val error = error(result, 0f)
        assertTrue("error $error", error < 4f)
    }

    @Test
    fun turningLeftWorksAsWellAsTurningRight() {
        val yaws = floatArrayOf(54f, 36f, 18f, 0f)
        val shots = yaws.map { PanoramaShot(it, 0f) }
        val result = PanoramaStitcher.stitch(shots, width, height, focal, Long.MAX_VALUE, sweep(*yaws))
        assertEquals(0, result.joinsBySensor)
        val error = error(result, 54f)
        assertTrue("error $error", error < 4f)
    }

    @Test
    fun picturesCorrectASensorThatIsOff() {
        val yaws = floatArrayOf(0f, 18f, 36f, 54f)
        val wrong = floatArrayOf(0f, 20f, 35f, 56f)
        val shots = wrong.map { PanoramaShot(it, 0.5f) }
        val result = PanoramaStitcher.stitch(shots, width, height, focal, Long.MAX_VALUE, sweep(*yaws))
        assertEquals(0, result.joinsBySensor)
        val error = error(result, 0f)
        assertTrue("error $error", error < 4f)
    }

    @Test
    fun aBlankSweepFallsBackOnTheSensor() {
        val shots = listOf(PanoramaShot(0f, 0f), PanoramaShot(18f, 0f), PanoramaShot(36f, 0f))
        val blank = PanoramaFrames { _, y, vu ->
            y.fill(70.toByte())
            vu.fill(128.toByte())
        }
        val result = PanoramaStitcher.stitch(shots, width, height, focal, Long.MAX_VALUE, blank)
        assertEquals(2, result.joinsBySensor)
        // Three frames 18 degrees apart: 36 degrees of turning beyond the frame's own width.
        val turned = focal * Math.toRadians(36.0).toFloat()
        assertEquals(turned + 2 * focal * atan(width / 2f / focal), result.canvasWidth.toFloat(), 8f)
        for (row in result.top until result.top + result.height) {
            val value = result.pixels[row * result.canvasWidth + result.left + result.width / 2].toInt() and 0xFF
            assertEquals(70, value)
        }
    }

    @Test
    fun theCanvasStaysWithinItsBudget() {
        val yaws = floatArrayOf(0f, 18f, 36f)
        val shots = yaws.map { PanoramaShot(it, 0f) }
        val budget = 40_000L
        val result = PanoramaStitcher.stitch(shots, width, height, focal, budget, sweep(*yaws))
        assertTrue("scale ${result.scale}", result.scale < 1f)
        assertTrue(result.canvasWidth.toLong() * result.canvasHeight <= budget * 11 / 10)
    }

    @Test
    fun oneFrameIsStillAPicture() {
        val result = PanoramaStitcher.stitch(
            listOf(PanoramaShot(10f, 0f)), width, height, focal, Long.MAX_VALUE, sweep(10f),
        )
        assertTrue(result.width > 0 && result.height > 0)
        val error = error(result, 10f)
        assertTrue("error $error", error < 3f)
    }

    @Test
    fun theSeamAvoidsWhatDiffers() {
        val columns = 5
        val rows = 4
        // Everything costs a lot except column three.
        val cost = IntArray(columns * rows) { if (it % columns == 3) 1 else 50 }
        assertArrayEquals(intArrayOf(3, 3, 3, 3), cheapestSeam(cost, columns, rows))
    }

    @Test
    fun theSeamMovesOneColumnAtATime() {
        val cost = intArrayOf(
            1, 9, 9, 9,
            9, 9, 9, 9,
            9, 9, 9, 1,
        )
        val seam = cheapestSeam(cost, 4, 3)
        for (row in 1 until 3) assertTrue(abs(seam[row] - seam[row - 1]) <= 1)
    }

    @Test
    fun theSeamCarriesOnThroughARowWithNoOverlap() {
        val cost = intArrayOf(
            -1, 1, 9,
            -1, -1, -1,
            -1, 1, 9,
        )
        assertArrayEquals(intArrayOf(1, 1, 1), cheapestSeam(cost, 3, 3))
    }

    @Test
    fun theLargestCoveredRectangleIsFound() {
        val picture = listOf(
            "..##..",
            ".#####",
            ".#####",
            "..###.",
        )
        val covered = BooleanArray(24) { picture[it / 6][it % 6] == '#' }
        val found = largestCovered(covered, 6, 4)
        assertEquals(1, found.left)
        assertEquals(1, found.top)
        assertEquals(5, found.width)
        assertEquals(2, found.height)
    }

    @Test
    fun aFullyCoveredPictureIsAllRectangle() {
        val found = largestCovered(BooleanArray(12) { true }, 4, 3)
        assertEquals(12, found.width * found.height)
    }

    @Test
    fun theTriggerKeepsAFrameEachStep() {
        val trigger = PanoramaTrigger(20f)
        assertTrue(trigger.offer(350f))
        assertFalse(trigger.offer(355f))
        assertTrue(trigger.offer(11f))
        assertEquals(21f, trigger.swept, 0.01f)
        assertFalse(trigger.offer(30f))
        assertTrue(trigger.offer(31f))
        assertEquals(41f, trigger.swept, 0.01f)
    }

    @Test
    fun swingingBackDoesNotCount() {
        val trigger = PanoramaTrigger(20f)
        assertTrue(trigger.offer(100f))
        assertTrue(trigger.offer(120f))
        assertFalse(trigger.offer(90f))
        assertEquals(20f, trigger.swept, 0.01f)
    }

    @Test
    fun theTriggerNoticesASweepTooFast() {
        val trigger = PanoramaTrigger(20f)
        trigger.offer(0f)
        trigger.offer(21f)
        assertFalse(trigger.tooFast)
        trigger.offer(60f)
        assertTrue(trigger.tooFast)
    }

    @Test
    fun aFullTurnEnds() {
        val trigger = PanoramaTrigger(20f)
        var yaw = 0f
        trigger.offer(yaw)
        while (!trigger.complete) {
            yaw += 25f
            assertTrue(trigger.offer(yaw % 360f))
        }
        assertTrue(trigger.swept >= 340f)
    }

    @Test
    fun theStepIsAShareOfTheFieldOfView() {
        // A frame as wide as its focal length takes in about 53 degrees.
        assertEquals(53.13f * 0.4f, panoramaStepDegrees(100f, 100), 0.05f)
    }

    @Test
    fun theCompassWrapsTheShortWay() {
        assertEquals(20f, wrappedDegrees(10f - 350f), 0.001f)
        assertEquals(-20f, wrappedDegrees(350f - 10f), 0.001f)
    }

    @Test
    fun aQuarterTurnMovesEveryCorner() {
        val w = 4
        val h = 2
        val y = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val vu = byteArrayOf(10, 11, 20, 21)
        val outY = ByteArray(8)
        val outVu = ByteArray(4)
        rotateNv21(y, vu, w, h, 90, outY, outVu)
        // Clockwise: the bottom left corner ends up top left.
        assertArrayEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3), outY)
        assertArrayEquals(byteArrayOf(10, 11, 20, 21), outVu)
    }

    @Test
    fun fourQuarterTurnsAreNoTurnAtAll() {
        val w = 4
        val h = 2
        val y = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val vu = byteArrayOf(10, 11, 20, 21)
        val onceY = ByteArray(8)
        val onceVu = ByteArray(4)
        rotateNv21(y, vu, w, h, 270, onceY, onceVu)
        val backY = ByteArray(8)
        val backVu = ByteArray(4)
        rotateNv21(onceY, onceVu, h, w, 90, backY, backVu)
        assertArrayEquals(y, backY)
        assertArrayEquals(vu, backVu)
    }

    @Test
    fun aHalfTurnReversesEverything() {
        val y = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val vu = byteArrayOf(10, 11, 20, 21)
        val outY = ByteArray(8)
        val outVu = ByteArray(4)
        rotateNv21(y, vu, 4, 2, 180, outY, outVu)
        assertArrayEquals(byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0), outY)
        assertArrayEquals(byteArrayOf(20, 21, 10, 11), outVu)
    }

    @Test
    fun theBudgetLeavesRoomForTheFrames() {
        val frame = 20_000_000L
        val budget = panoramaPixelBudget(512_000_000L, frame)
        assertTrue("budget $budget", budget * 7 / 4 + frame * 2 < 512_000_000L)
        assertTrue("budget $budget", budget > 100_000_000L)
    }
}
