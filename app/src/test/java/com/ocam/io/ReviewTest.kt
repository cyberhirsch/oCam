package com.ocam.io

import com.ocam.ui.clampPan
import com.ocam.ui.panToKeep
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewTest {

    private fun row(id: Long, name: String, mime: String) = PhotoRow(id, name, mime)

    @Test
    fun rawAndJpegOfOneShotAreOneShot() {
        val shots = groupShots(
            listOf(
                row(2, "IMG_1.dng", "image/x-adobe-dng"),
                row(1, "IMG_1.jpg", "image/jpeg"),
            )
        )
        assertEquals(1, shots.size)
        assertEquals(2, shots[0].rows.size)
        assertEquals("image/jpeg", shots[0].primary.mime)
        assertEquals("JPEG+RAW", shots[0].formats)
    }

    @Test
    fun rawAloneIsWhatIsShown() {
        val shot = groupShots(listOf(row(1, "IMG_1.dng", "image/x-adobe-dng"))).single()
        assertEquals("image/x-adobe-dng", shot.primary.mime)
        assertEquals("RAW", shot.formats)
    }

    @Test
    fun heicNamesItself() {
        assertEquals("HEIC", groupShots(listOf(row(1, "IMG_1.heic", "image/heic"))).single().formats)
    }

    @Test
    fun theNewestStaysFirst() {
        val shots = groupShots(
            listOf(
                row(3, "IMG_3.jpg", "image/jpeg"),
                row(2, "IMG_2.jpg", "image/jpeg"),
                row(1, "IMG_1.jpg", "image/jpeg"),
            )
        )
        assertEquals(listOf("IMG_3", "IMG_2", "IMG_1"), shots.map { it.baseName })
    }

    @Test
    fun theFramesKeptFromABracketAreTheirOwnShots() {
        val shots = groupShots(
            listOf(
                row(3, "IMG_1_EV+2.0.jpg", "image/jpeg"),
                row(2, "IMG_1_EV-2.0.jpg", "image/jpeg"),
                row(1, "IMG_1.jpg", "image/jpeg"),
            )
        )
        assertEquals(3, shots.size)
        assertEquals("IMG_1_EV+2.0", shots[0].baseName)
    }

    @Test
    fun noFilesAreNoShots() {
        assertEquals(0, groupShots(emptyList()).size)
    }

    @Test
    fun aPictureAtItsNormalSizeCannotBeMoved() {
        assertEquals(0f, clampPan(50f, 1f, 1000f), 0f)
    }

    @Test
    fun anEnlargedPictureCannotBeMovedPastItsEdge() {
        assertEquals(1000f, clampPan(5000f, 3f, 1000f), 0f)
        assertEquals(-1000f, clampPan(-5000f, 3f, 1000f), 0f)
        assertEquals(200f, clampPan(200f, 3f, 1000f), 0f)
    }

    @Test
    fun theMiddleStaysInTheMiddle() {
        assertEquals(0f, panToKeep(500f, 3f, 1000f), 0f)
    }

    @Test
    fun aPointStaysUnderTheFinger() {
        val side = 1000f
        val scale = 3f
        val point = 400f
        val pan = panToKeep(point, scale, side)
        // Where a graphics layer draws a point: out from the middle by the scale, then moved.
        assertEquals(point, side / 2f + (point - side / 2f) * scale + pan, 0.001f)
    }

    @Test
    fun aPointAtTheEdgeStaysInsideThePicture() {
        assertEquals(1000f, panToKeep(0f, 3f, 1000f), 0f)
        assertEquals(-1000f, panToKeep(1000f, 3f, 1000f), 0f)
    }
}
