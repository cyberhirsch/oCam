package com.ocam.io

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos to Pictures/oCam through MediaStore, so no storage permission is needed. */
object PhotoStore {

    private const val ALBUM = "oCam"

    fun newBaseName(timestampMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMillis))
        return "IMG_$stamp"
    }

    fun saveJpeg(context: Context, bytes: ByteArray, baseName: String): String {
        val name = "$baseName.jpg"
        write(context, name, "image/jpeg") { it.write(bytes) }
        return name
    }

    /**
     * Writes a DNG built from the raw frame and the exact capture result that produced it - the
     * result carries the black level, colour matrices and noise profile the file needs.
     */
    fun saveDng(
        context: Context,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        orientationDegrees: Int,
        baseName: String,
    ): String {
        val name = "$baseName.dng"
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation(orientationDegrees))
            write(context, name, "image/x-adobe-dng") { dng.writeImage(it, image) }
        }
        return name
    }

    private fun write(context: Context, name: String, mimeType: String, body: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create $name")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Cannot open $name for writing")
            stream.use(body)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
