package com.ocam.sequence

import android.content.Context
import android.media.Image
import android.util.Log
import com.ocam.camera.Pose
import com.ocam.io.PhotoStore
import com.ocam.lut.LutPipeline
import com.ocam.lut.StillGrader
import java.util.Locale

private const val TAG = "StackRun"

/** Fixed when the shutter is pressed, like everything else a shot writes. */
class StackPlan(
    val baseName: String,
    val orientation: Int,
    val pose: Pose,
    val iso: Int,
    val exposureNs: Long,
    /** Where to focus for each frame, nearest first, in diopters. */
    val distances: List<Float>,
    val sensorOrientation: Int,
    val mirrored: Boolean,
)

/**
 * One press of the shutter in STACK: frames at different focus distances in, one picture out. Every
 * call but [publish] happens on the thread the frames arrive on, and the next frame is only asked for
 * once the last has been merged, so the merge can take as long as it takes.
 */
class StackRun(
    private val context: Context,
    width: Int,
    height: Int,
    val plan: StackPlan,
    private val film: () -> LutPipeline?,
    private val onProgress: (SequenceProgress) -> Unit,
    private val onSaved: (String) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val stack = FocusStack(width, height)
    private val luma = ByteArray(width * height)
    private val chroma = ByteArray(width * height / 2)
    private val scratchV = ByteArray(width)
    private val scratchU = ByteArray(width)

    fun offer(image: Image) {
        val planes = image.planes
        readLuma(planes[0].buffer, planes[0].rowStride, stack.width, stack.height, luma)
        readChroma(
            planes[2].buffer, planes[1].buffer, planes[1].pixelStride, planes[1].rowStride,
            stack.width, stack.height, scratchV, scratchU, chroma,
        )
        stack.add(luma, chroma)
        publish()
    }

    fun publish() {
        onProgress(
            SequenceProgress(
                mode = SequenceMode.STACK,
                frames = stack.frames,
                exposedNs = 0L,
                targetNs = null,
                paused = false,
                picture = null,
                sensorOrientation = plan.sensorOrientation,
                mirrored = plan.mirrored,
                holds = false,
                note = "${stack.frames} of ${plan.distances.size}",
            )
        )
    }

    /** Write what has been merged. A stack cut short is still sharper than one frame. */
    fun finish() {
        if (stack.frames == 0) {
            onProblem("No frames arrived, so there is nothing to save")
            return
        }
        try {
            val pixels = ByteArray(stack.width * stack.height * 3 / 2)
            stack.writeNv21(pixels)
            val jpeg = encodeNv21Jpeg(pixels, stack.width, stack.height)
            val graded = film()?.let { StillGrader.grade(jpeg, false, it) } ?: jpeg
            onSaved(
                PhotoStore.saveMerged(
                    context, graded, plan.baseName, plan.orientation, plan.pose, plan.iso,
                    plan.exposureNs,
                    String.format(Locale.US, "oCam STACK: %d frames", stack.frames),
                )
            )
            if (stack.unaligned > 0) {
                onProblem("${stack.unaligned} of ${stack.frames} frames could not be lined up")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot save the picture", t)
            onProblem("Cannot save the picture: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
