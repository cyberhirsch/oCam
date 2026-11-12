package com.ocam.sequence

import com.ocam.camera.isoStops
import com.ocam.camera.nearestStop
import com.ocam.camera.shutterStopsNs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapseTest {

    private val second = 1_000_000_000L
    private val frame = second / 10

    /** Runs a clock over evenly spaced frames and says which were kept and when pictures ended. */
    private fun run(clock: LapseClock, frames: Int): Pair<List<Int>, List<Int>> {
        val kept = mutableListOf<Int>()
        val emitted = mutableListOf<Int>()
        for (index in 0 until frames) {
            val step = clock.frame(1_000L + index * frame)
            if (step.emit) emitted += index
            if (step.add) kept += index
        }
        return kept to emitted
    }

    @Test
    fun halfOfEachIntervalGathersLight() {
        // Two seconds a picture, one of them gathered, frames every tenth of a second.
        val (kept, emitted) = run(LapseClock(2 * second, second), 50)
        assertEquals((0..9) + (20..29) + (40..49), kept)
        assertEquals(listOf(10, 30), emitted)
    }

    @Test
    fun noBlurKeepsOneFramePerInterval() {
        val (kept, emitted) = run(LapseClock(2 * second, 0L), 45)
        assertEquals(listOf(0, 20, 40), kept)
        assertEquals(listOf(1, 21, 41), emitted)
    }

    @Test
    fun fullBlurKeepsEveryFrameAndEndsEachPictureAsTheNextStarts() {
        val (kept, emitted) = run(LapseClock(second, second), 30)
        assertEquals((0 until 30).toList(), kept)
        assertEquals(listOf(10, 20), emitted)
    }

    @Test
    fun aLateFrameDoesNotMoveTheBeat() {
        val clock = LapseClock(2 * second, second)
        clock.frame(0L)
        clock.frame(second)
        // The next interval was due at 2 s; a frame at 2.5 s opens it, and the one after that ends
        // the picture at 3 s, on the beat, not at 3.5 s.
        val late = clock.frame(2 * second + second / 2)
        assertTrue(late.reset && late.add)
        assertFalse(clock.frame(2 * second + 9 * second / 10).emit)
        assertTrue(clock.frame(3 * second).emit)
    }

    @Test
    fun isoStopsKeepTheLensOwnEnds() {
        val stops = isoStops(55, 3000)
        assertEquals(55, stops.first())
        assertEquals(3000, stops.last())
        assertTrue(64 in stops && 2500 in stops && 3200 !in stops)
    }

    @Test
    fun theNearestStopIsJudgedInStops() {
        val stops = isoStops(50, 6400)
        assertEquals(stops.indexOf(125), nearestStop(stops, 125.0))
        assertEquals(stops.indexOf(6400), nearestStop(stops, 9000.0))
        // 110 is nearer 100 than 125 in stops.
        assertEquals(stops.indexOf(100), nearestStop(stops, 110.0))
    }

    @Test
    fun shutterStopsRunFromFastToSlow() {
        val stops = shutterStopsNs(second / 8000, second)
        assertEquals(second / 8000, stops.first())
        assertEquals(second, stops.last())
        assertTrue(stops.zipWithNext().all { (a, b) -> a < b })
        assertEquals(second / 60, stops[nearestStop(stops, second / 58.0)])
    }
}
