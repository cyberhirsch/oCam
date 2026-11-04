package com.ocam.sequence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.media.Image
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ocam.camera.Pose
import com.ocam.io.PhotoStore
import com.ocam.lut.LutPipeline
import com.ocam.lut.StillGrader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

private const val TAG = "BracketRun"

/** Fixed when the shutter is pressed, like everything else a shot writes. */
class BracketPlan(
    val baseName: String,
    val orientation: Int,
    val pose: Pose,
    val iso: Int,
    val exposureNs: Long,
    val frames: Int,
    val keepFrames: Boolean,
    val ultraHdr: Boolean,
)

/**
 * One press of the shutter in HDR: a few frames at different exposures in, one picture out. Every
 * call happens on the thread the frames arrive on. A frame only counts once the camera has also
 * said what it was really exposed at, because a lens may not have reached what it was asked for.
 */
class BracketRun(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val plan: BracketPlan,
    private val film: () -> LutPipeline?,
    private val onSaved: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private class Pixels(val luma: ByteArray, val chroma: ByteArray)
    private class Reading(val iso: Int, val exposureNs: Long)

    private val pixels = HashMap<Long, Pixels>()
    private val readings = HashMap<Long, Reading>()
    private var failures = 0

    private val complete: Boolean
        get() = pixels.keys.count { it in readings } + failures >= plan.frames

    /** @return true when nothing more is on its way. */
    fun offer(image: Image): Boolean {
        val planes = image.planes
        val luma = ByteArray(width * height)
        val chroma = ByteArray(width * height / 2)
        readLuma(planes[0].buffer, planes[0].rowStride, width, height, luma)
        readChroma(
            planes[2].buffer, planes[1].buffer, planes[1].pixelStride, planes[1].rowStride,
            width, height, ByteArray(width), ByteArray(width), chroma,
        )
        pixels[image.timestamp] = Pixels(luma, chroma)
        return complete
    }

    /** @return true when nothing more is on its way. */
    fun offerReading(timestamp: Long, iso: Int, exposureNs: Long): Boolean {
        readings[timestamp] = Reading(iso, exposureNs)
        return complete
    }

    /** @return true when nothing more is on its way. */
    fun offerFailure(): Boolean {
        failures++
        return complete
    }

    /** Write what arrived. Fewer frames than planned is still a picture. */
    fun finish() {
        val light = (plan.iso.toDouble() * plan.exposureNs).toFloat()
        val frames = pixels.mapNotNull { (timestamp, taken) ->
            readings[timestamp]?.let { reading ->
                val relative = (reading.iso.toDouble() * reading.exposureNs).toFloat() / light
                Taken(BracketFrame(taken.luma, taken.chroma, relative), reading)
            }
        }.sortedBy { it.frame.relativeExposure }

        if (frames.isEmpty()) {
            onProblem("No frames arrived, so there is nothing to save")
            return
        }
        if (plan.keepFrames) frames.forEach(::saveFrame)
        if (frames.size == 1) {
            onProblem("Only one frame arrived, so nothing was merged")
            if (!plan.keepFrames) saveFrame(frames[0])
            return
        }

        try {
            val fused = ByteArray(width * height * 3 / 2)
            ExposureFusion.fuse(frames.map { it.frame }, width, height, fused)
            PhotoStore.saveMerged(
                context, encode(frames, fused), plan.baseName, plan.orientation, plan.pose, plan.iso,
                plan.exposureNs, describe(frames),
            ).let(onSaved)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "No room to fuse at ${width}x$height", e)
            // The picture was taken; losing it to the merge would be the worse of the two.
            onProblem("Not enough memory to merge, so the middle frame was saved instead")
            if (!plan.keepFrames) {
                saveFrame(frames.minByOrNull { abs(ln(it.frame.relativeExposure)) } ?: frames[0])
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot save the picture", t)
            onProblem("Cannot save the picture: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private class Taken(val frame: BracketFrame, val reading: Reading)

    private fun encode(frames: List<Taken>, fused: ByteArray): ByteArray {
        if (plan.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val gain = ExposureFusion.gainMap(frames.map { it.frame }, fused, width, height)
                if (gain != null) return encodeUltraHdr(fused, gain)
            } catch (t: Throwable) {
                Log.e(TAG, "Cannot write an Ultra HDR file", t)
                onProblem("Could not write Ultra HDR, so this is a plain JPEG")
            }
        }
        val jpeg = encodeNv21Jpeg(fused, width, height)
        return film()?.let { StillGrader.grade(jpeg, false, it) } ?: jpeg
    }

    /**
     * The fused picture, with the gain map that lets a screen with the range show the light the
     * bracket really saw. Screens without it, and anything that does not know the format, get the
     * picture. The simulation grades the picture alone: the gain on top of it is the scene's.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun encodeUltraHdr(fused: ByteArray, gain: GainMapPlane): ByteArray {
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val line = IntArray(width)
        val chromaStart = width * height
        for (row in 0 until height) {
            val chromaRow = chromaStart + row / 2 * width
            for (column in 0 until width) {
                val chroma = chromaRow + column / 2 * 2
                line[column] = yuvToArgb(
                    fused[row * width + column].toInt() and 0xFF,
                    fused[chroma + 1].toInt() and 0xFF,
                    fused[chroma].toInt() and 0xFF,
                )
            }
            base.setPixels(line, 0, width, 0, row, width, 1)
        }
        val graded = film()?.grade(base) ?: base

        val contents = Bitmap.createBitmap(gain.width, gain.height, Bitmap.Config.ALPHA_8)
        val rows = ByteBuffer.allocate(contents.rowBytes * gain.height)
        for (row in 0 until gain.height) {
            rows.position(row * contents.rowBytes)
            rows.put(gain.pixels, row * gain.width, gain.width)
        }
        rows.rewind()
        contents.copyPixelsFromBuffer(rows)

        val map = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(gain.ratioMax, gain.ratioMax, gain.ratioMax)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(ExposureFusion.GAIN_EPSILON, ExposureFusion.GAIN_EPSILON, ExposureFusion.GAIN_EPSILON)
            setEpsilonHdr(ExposureFusion.GAIN_EPSILON, ExposureFusion.GAIN_EPSILON, ExposureFusion.GAIN_EPSILON)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = gain.ratioMax
        }
        graded.gainmap = map
        return ByteArrayOutputStream(fused.size / 4).use { out ->
            check(graded.compress(Bitmap.CompressFormat.JPEG, MERGED_JPEG_QUALITY, out)) {
                "The JPEG encoder refused the picture"
            }
            out.toByteArray()
        }.also {
            contents.recycle()
            if (graded !== base) graded.recycle()
            base.recycle()
        }
    }

    /** A frame as the camera gave it, without the simulation, for merging elsewhere. */
    private fun saveFrame(taken: Taken) {
        try {
            val stops = ln(taken.frame.relativeExposure) / ln(2f)
            val nv21 = ByteArray(width * height * 3 / 2)
            taken.frame.luma.copyInto(nv21)
            taken.frame.chroma.copyInto(nv21, width * height)
            PhotoStore.saveMerged(
                context, encodeNv21Jpeg(nv21, width, height),
                String.format(Locale.US, "%s_EV%+.1f", plan.baseName, stops),
                plan.orientation, plan.pose, taken.reading.iso, taken.reading.exposureNs,
                String.format(Locale.US, "oCam HDR frame at %+.1f stops", stops),
            ).let(onSaved)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot save a frame", t)
            onProblem("Cannot save a bracket frame: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun describe(frames: List<Taken>): String {
        val spread = ln(frames.last().frame.relativeExposure / frames.first().frame.relativeExposure) / ln(2f)
        return String.format(Locale.US, "oCam HDR: %d frames over %.1f stops", frames.size, spread)
    }
}
