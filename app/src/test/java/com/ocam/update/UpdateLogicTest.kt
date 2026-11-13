package com.ocam.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `reads the build from a versioned asset`() {
        assertEquals(
            ReleaseBuild(37, "1.0.37+5b309bd"),
            parseVersionedAsset("oCam-1.0.37-5b309bd.apk"),
        )
    }

    @Test
    fun `reads a build with no commit`() {
        assertEquals(ReleaseBuild(12, "1.0.12"), parseVersionedAsset("oCam-1.0.12.apk"))
    }

    @Test
    fun `the fixed name and strangers carry no build`() {
        assertNull(parseVersionedAsset(FIXED_APK_NAME))
        assertNull(parseVersionedAsset("app-debug.apk"))
        assertNull(parseVersionedAsset("oCam.apk"))
        assertNull(parseVersionedAsset("oCam-1.0.apk"))
        assertNull(parseVersionedAsset("oCam-1.0.37-5b309bd.apk.sig"))
        assertNull(parseVersionedAsset("oCam-1.0.99999999999999999999.apk"))
    }

    @Test
    fun `the newest build wins while two stand side by side`() {
        val names = listOf(
            "oCam-1.0.9-aaaaaaa.apk",
            FIXED_APK_NAME,
            "oCam-1.0.10-bbbbbbb.apk",
        )
        // 10 is newer than 9, which comparing the names as text would get wrong.
        assertEquals(10L, newestBuild(names)?.versionCode)
    }

    @Test
    fun `a release with no versioned asset has no newest build`() {
        assertNull(newestBuild(listOf(FIXED_APK_NAME)))
        assertNull(newestBuild(emptyList()))
    }

    @Test
    fun `only a higher version code is newer`() {
        assertTrue(isNewer(38, 37))
        assertFalse(isNewer(37, 37))
        assertFalse(isNewer(36, 37))
    }

    @Test
    fun `a check is due once a day`() {
        assertTrue(checkDue(now = 1_000, lastCheck = null))
        assertFalse(checkDue(now = day + 999, lastCheck = 1_000))
        assertTrue(checkDue(now = day + 1_000, lastCheck = 1_000))
    }

    @Test
    fun `a clock that went backwards does not silence the check`() {
        assertTrue(checkDue(now = 1_000, lastCheck = 5 * day))
    }

    @Test
    fun `a local build is not checked on launch`() {
        assertFalse(automaticCheckWanted(1))
        assertTrue(automaticCheckWanted(2))
    }

    @Test
    fun `any work in progress blocks the install`() {
        assertTrue(safeToInstall(busy = false, runInProgress = false, timerRunning = false))
        assertFalse(safeToInstall(busy = true, runInProgress = false, timerRunning = false))
        assertFalse(safeToInstall(busy = false, runInProgress = true, timerRunning = false))
        assertFalse(safeToInstall(busy = false, runInProgress = false, timerRunning = true))
    }

    @Test
    fun `a fingerprint is the lower case sha-256 of the certificate`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            certificateFingerprint("abc".toByteArray()),
        )
    }

    private val own = setOf("aa")
    private fun judge(facts: ApkFacts?) = judgeDownload(facts, "com.ocam", 37, own)

    @Test
    fun `a newer build under the same key passes`() {
        assertEquals(Verdict.OK, judge(ApkFacts("com.ocam", 38, own)))
    }

    @Test
    fun `an unreadable file is refused`() {
        assertEquals(Verdict.UNREADABLE, judge(null))
    }

    @Test
    fun `another package is refused`() {
        assertEquals(Verdict.WRONG_PACKAGE, judge(ApkFacts("com.other", 38, own)))
    }

    @Test
    fun `another key is refused`() {
        assertEquals(Verdict.DIFFERENT_KEY, judge(ApkFacts("com.ocam", 38, setOf("bb"))))
        assertEquals(Verdict.DIFFERENT_KEY, judge(ApkFacts("com.ocam", 38, own + "bb")))
    }

    @Test
    fun `no certificates at all never match`() {
        assertEquals(
            Verdict.DIFFERENT_KEY,
            judgeDownload(ApkFacts("com.ocam", 38, emptySet()), "com.ocam", 37, emptySet()),
        )
    }

    @Test
    fun `the same or an older build is refused`() {
        assertEquals(Verdict.NOT_NEWER, judge(ApkFacts("com.ocam", 37, own)))
        assertEquals(Verdict.NOT_NEWER, judge(ApkFacts("com.ocam", 36, own)))
    }
}
