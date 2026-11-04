package com.ocam.sequence

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.ocam.camera.Pose
import com.ocam.io.PhotoStore
import com.ocam.lut.LutPipeline
import com.ocam.lut.StillGrader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private const val TAG = "PanoramaRun"

/** Fixed when the shutter is pressed, like everything else a shot writes. */
class PanoramaPlan(
    val baseName: String,
    /** How far to turn each frame, clockwise, to have it upright; the sweep runs sideways of that. */
    val turnDegrees: Int,
    val pose: Pose,
    val iso: Int,
    val exposureNs: Long,
    /** The lens's focal length, in pixels of the frames. */
    val focal: Float,
    val sensorOrientation: Int,
)

/**
 * One press of the shutter in PANO. Frames go to the cache as the camera turns and are only stitched
 * when it stops: a stitch takes far longer than a frame, a phone's heap has no room for a whole turn,
 * but its storage does, so nobody has to sweep at the pace of the merge and nothing here can run out
 * of memory until the end, where it can still try again smaller. Every call happens on the thread
 * the frames arrive on.
 */
class PanoramaRun(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val plan: PanoramaPlan,
    private val film: () -> LutPipeline?,
    /** Which way the lens is pointing now: yaw and pitch, in degrees. */
    private val heading: () -> Pair<Float, Float>?,
    private val onProgress: (SequenceProgress) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val parent = File(context.cacheDir, "panorama")
    private val folder = File(parent, plan.baseName)
    private val luma = ByteArray(width * height)
    private val chroma = ByteArray(width * height / 2)
    private val scratchV = ByteArray(width)
    private val scratchU = ByteArray(width)
    private val upright = plan.turnDegrees % 180 == 0
    private val uprightWidth = if (upright) width else height
    private val uprightHeight = if (upright) height else width
    private val trigger = PanoramaTrigger(panoramaStepDegrees(plan.focal, uprightWidth))
    private val shots = ArrayList<PanoramaShot>()

    init {
        // What an earlier run could not clear up, if the app was killed part way through one. Not
        // anything recent: leaving the app and coming back can start a sweep while one still stitches.
        val longAgo = System.currentTimeMillis() - LEFTOVER_AGE_MS
        parent.listFiles()?.filter { it.lastModified() < longAgo }?.forEach { it.deleteRecursively() }
        folder.mkdirs()
    }

    private fun fileFor(index: Int) = File(folder, "frame_$index")

    /** @return true when the sweep has gone as far as it can. */
    fun offer(image: Image): Boolean {
        val (yaw, pitch) = heading() ?: return false
        if (!trigger.offer(yaw)) return false

        if (folder.usableSpace < (luma.size + chroma.size) * SPARE_FRAMES) {
            onProblem("The phone is out of storage, so the panorama stops here")
            return true
        }
        val planes = image.planes
        readLuma(planes[0].buffer, planes[0].rowStride, width, height, luma)
        readChroma(
            planes[2].buffer, planes[1].buffer, planes[1].pixelStride, planes[1].rowStride,
            width, height, scratchV, scratchU, chroma,
        )
        FileOutputStream(fileFor(shots.size)).use { out ->
            out.write(luma)
            out.write(chroma)
        }
        shots += PanoramaShot(yaw, pitch)
        publish()
        return trigger.complete
    }

    fun publish() {
        val pace = if (trigger.tooFast) " · slower" else ""
        onProgress(
            SequenceProgress(
                mode = SequenceMode.PANO,
                frames = shots.size,
                exposedNs = 0L,
                targetNs = null,
                paused = false,
                picture = null,
                sensorOrientation = plan.sensorOrientation,
                mirrored = false,
                holds = false,
                note = String.format(Locale.US, "%.0f° · %d%s", trigger.swept, shots.size, pace),
            )
        )
    }

    /** Stitch what was taken and let go of the frames, whatever happens. */
    fun finish() {
        try {
            when (shots.size) {
                0 -> onProblem("No frames arrived, so there is nothing to save")
                1 -> {
                    onProblem("The camera did not turn far enough for a second frame")
                    save(stitched())
                }
                else -> save(stitched())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot stitch ${shots.size} frames", t)
            onProblem("Cannot make the panorama: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            folder.deleteRecursively()
        }
    }

    /** At the size memory allows, and once more at half of that if memory says otherwise after all. */
    private fun stitched(): StitchedPanorama {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val budget = panoramaPixelBudget(free, (luma.size + chroma.size).toLong())
        return try {
            stitch(budget)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "No room to stitch at $budget pixels", e)
            onStatus("Not enough memory at full size, stitching smaller")
            stitch(budget / 2)
        }
    }

    private fun stitch(budget: Long): StitchedPanorama {
        val fromFile = ByteArray(luma.size + chroma.size)
        val rawLuma = luma
        val rawChroma = chroma
        return PanoramaStitcher.stitch(
            shots = shots,
            width = uprightWidth,
            height = uprightHeight,
            focal = plan.focal,
            maxPixels = budget,
            frames = { index, y, vu ->
                FileInputStream(fileFor(index)).use { input ->
                    var read = 0
                    while (read < fromFile.size) {
                        val got = input.read(fromFile, read, fromFile.size - read)
                        check(got > 0) { "Frame $index is cut short" }
                        read += got
                    }
                }
                fromFile.copyInto(rawLuma, 0, 0, rawLuma.size)
                fromFile.copyInto(rawChroma, 0, rawLuma.size, fromFile.size)
                rotateNv21(rawLuma, rawChroma, width, height, plan.turnDegrees, y, vu)
            },
            onFrame = { done, total -> onStatus("Stitching $done of $total") },
        )
    }

    private fun save(result: StitchedPanorama) {
        val area = Rect(result.left, result.top, result.left + result.width, result.top + result.height)
        val jpeg = encodeNv21Jpeg(result.pixels, result.canvasWidth, result.canvasHeight, area)
        val graded = film()?.let { StillGrader.grade(jpeg, false, it) } ?: jpeg
        onSaved(
            PhotoStore.saveMerged(
                appContext, graded, plan.baseName, 0, plan.pose, plan.iso, plan.exposureNs,
                String.format(Locale.US, "oCam PANO: %d frames over %.0f degrees", shots.size, trigger.swept),
            )
        )
        if (result.joinsBySensor > 0) {
            onProblem(
                "${result.joinsBySensor} of ${shots.size - 1} joins had too little detail to line " +
                    "up, and went where the motion sensor said"
            )
        }
    }

    private companion object {
        /** How many frames' worth of storage has to be left before another is written. */
        const val SPARE_FRAMES = 3L

        /** Far longer than any stitch takes. */
        const val LEFTOVER_AGE_MS = 60 * 60_000L
    }
}
