package com.ocam.lut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream

private const val TAG = "StillGrader"

/**
 * Puts the same grade on the photograph that the preview was showing.
 *
 * The camera hands over a finished JPEG, so the file is decoded, graded on the GPU and encoded
 * again - one more generation than an ungraded shot, which is the price of letting the camera do
 * its own development and grading afterwards rather than taking the pixels raw and doing all of
 * it here.
 *
 * Not HEIC. Android can decode one but has no public way to write one back: Bitmap.compress
 * offers JPEG, PNG and WebP and nothing else, and the encoder behind the format is not API. A
 * HEIC is therefore written exactly as the camera made it, ungraded, and the settings sheet says
 * so rather than leaving it to be discovered.
 *
 * Every failure returns null and the caller writes the original. A photograph without its
 * simulation is a far better outcome than no photograph, and a full size still is exactly the
 * kind of thing that runs a phone out of memory.
 */
object StillGrader {

    /** Whether a file of this kind can carry the grade at all. */
    fun canGrade(heic: Boolean): Boolean = !heic

    fun grade(bytes: ByteArray, heic: Boolean, pipeline: LutPipeline): ByteArray? {
        if (!canGrade(heic)) return null

        var source: Bitmap? = null
        var graded: Bitmap? = null
        return try {
            source = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    // Mutable, so the pipeline grades into it rather than into a second copy.
                    inMutable = true
                },
            ) ?: return null
            graded = pipeline.grade(source) ?: return null
            ByteArrayOutputStream(bytes.size).use { out ->
                if (!graded.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
                out.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to grade a ${bytes.size} byte still", e)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot grade the still", t)
            null
        } finally {
            graded?.recycle()
            source?.recycle()
        }
    }

    /** Matches the quality the camera's own encoder was asked for. */
    private const val QUALITY = 97
}
