package com.ocam.sequence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.ocam.camera.Pose
import com.ocam.io.PhotoStore
import com.ocam.lut.LutPipeline
import com.ocam.lut.StillGrader
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val TAG = "SequenceRun"

/** How often the picture being built is put on screen, and how big. */
private const val PICTURE_INTERVAL_MS = 250L
private const val PICTURE_LONG_SIDE = 640

/** Matches the quality the camera's own encoder is asked for. */
internal const val MERGED_JPEG_QUALITY = 97

internal fun encodeNv21Jpeg(pixels: ByteArray, width: Int, height: Int): ByteArray =
    ByteArrayOutputStream(pixels.size / 4).use { out ->
        val image = YuvImage(pixels, ImageFormat.NV21, width, height, null)
        check(image.compressToJpeg(Rect(0, 0, width, height), MERGED_JPEG_QUALITY, out)) {
            "The JPEG encoder refused the frame"
        }
        out.toByteArray()
    }

/** Fixed when the shutter is pressed, like everything else a shot writes. */
class SequencePlan(
    val mode: SequenceMode,
    val baseName: String,
    val orientation: Int,
    val pose: Pose,
    val iso: Int,
    val exposureNs: Long,
    /** How much time to gather before stopping by itself, or null to run until told. */
    val targetNs: Long?,
    val video: Boolean,
    val sensorOrientation: Int,
    val mirrored: Boolean,
)

/**
 * One press of the shutter in a mode that builds a picture: frames in, one picture out. Everything
 * here happens on the thread the frames arrive on, one frame at a time.
 */
class SequenceRun(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val plan: SequencePlan,
    private val film: () -> LutPipeline?,
    private val onProgress: (SequenceProgress) -> Unit,
    private val onSaved: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val merger = FrameMerger.create(width, height, plan.mode)
    private val luma = ByteArray(width * height)
    private val chroma = ByteArray(width * height / 2)
    private val scratchV = ByteArray(width)
    private val scratchU = ByteArray(width)
    private var video: VideoWriter? = null
    private var videoTried = false

    @Volatile var paused = false

    private var exposedNs = 0L
    private var lastTimestamp = 0L
    private var lastVideoNs = -1L
    private var lastPictureMs = 0L
    private var picture: Bitmap? = null

    /** @return true when the run has gathered all it was asked to. */
    fun offer(image: Image): Boolean {
        if (paused) {
            // The light stops counting, and so does the time: the next frame starts afresh.
            lastTimestamp = 0L
            return false
        }

        val planes = image.planes
        readLuma(planes[0].buffer, planes[0].rowStride, width, height, luma)
        readChroma(
            planes[2].buffer, planes[1].buffer, planes[1].pixelStride, planes[1].rowStride,
            width, height, scratchV, scratchU, chroma,
        )
        merger.add(luma, chroma)

        val timestamp = image.timestamp
        exposedNs += if (lastTimestamp == 0L) plan.exposureNs else timestamp - lastTimestamp
        lastTimestamp = timestamp

        writeVideoFrame()
        val now = SystemClock.uptimeMillis()
        if (now - lastPictureMs >= PICTURE_INTERVAL_MS) {
            lastPictureMs = now
            picture = makePicture()
            publish()
        }
        return plan.targetNs?.let { exposedNs >= it } ?: false
    }

    /** Write what has been built. What was gathered is kept, even if the run was cut short. */
    fun finish() {
        if (merger.frames == 0) {
            video?.abandon()
            onProblem("No frames arrived, so there is nothing to save")
            return
        }
        try {
            onSaved(
                PhotoStore.saveMerged(
                    context, encode(), plan.baseName, plan.orientation, plan.pose, plan.iso,
                    plan.exposureNs, describe(),
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot save the picture", t)
            onProblem("Cannot save the picture: ${t.message ?: t.javaClass.simpleName}")
        }
        try {
            video?.finish()?.let(onSaved)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot finish the video", t)
            onProblem("The build-up video could not be finished")
        }
    }

    private fun encode(): ByteArray {
        val pixels = ByteArray(width * height * 3 / 2)
        merger.writeNv21(pixels)
        val jpeg = encodeNv21Jpeg(pixels, width, height)
        return film()?.let { StillGrader.grade(jpeg, false, it) } ?: jpeg
    }

    private fun writeVideoFrame() {
        if (!plan.video || !plan.mode.buildsUp) return
        if (lastVideoNs >= 0 && exposedNs - lastVideoNs < plan.mode.videoStepNs) return
        lastVideoNs = exposedNs
        try {
            if (!videoTried) {
                videoTried = true
                video = VideoWriter(context, plan.baseName, width, height, plan.orientation)
            }
            video?.add(merger)
        } catch (t: Throwable) {
            // The picture is what was asked for; the video is not worth losing it over.
            Log.e(TAG, "Cannot write the build-up video", t)
            runCatching { video?.abandon() }
            video = null
            onProblem("The build-up video stopped; the picture is still being built")
        }
    }

    private fun makePicture(): Bitmap {
        val small = merger.thumbnail(PICTURE_LONG_SIDE)
        val bitmap = Bitmap.createBitmap(small.width, small.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(small.argb, 0, small.width, 0, 0, small.width, small.height)
        return film()?.grade(bitmap) ?: bitmap
    }

    fun publish() {
        onProgress(
            SequenceProgress(
                mode = plan.mode,
                frames = merger.frames,
                exposedNs = exposedNs,
                targetNs = plan.targetNs,
                paused = paused,
                picture = picture,
                sensorOrientation = plan.sensorOrientation,
                mirrored = plan.mirrored,
            )
        )
    }

    private fun describe(): String = String.format(
        Locale.US, "oCam %s: %d frames over %.1f s",
        plan.mode.label, merger.frames, exposedNs / 1_000_000_000.0,
    )
}
