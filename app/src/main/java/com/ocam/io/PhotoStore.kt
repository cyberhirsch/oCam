package com.ocam.io

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.ocam.camera.Pose
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos to Pictures/oCam through MediaStore, so no storage permission is needed. */
object PhotoStore {

    private const val ALBUM = "oCam"
    private const val TAG = "PhotoStore"

    fun newBaseName(timestampMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMillis))
        return "IMG_$stamp"
    }

    /**
     * The rendered image. HEIC is roughly half the size of a JPEG at the same quality and carries
     * ten bits per channel instead of eight, which is where the headroom in skies and skin comes
     * from; the camera hands both over already encoded.
     */
    fun saveStill(
        context: Context,
        bytes: ByteArray,
        baseName: String,
        heic: Boolean,
        pose: Pose = Pose.NONE,
        /**
         * The camera's own file, when [bytes] is a re-encoding of it. Bitmap.compress writes no
         * EXIF at all, so the orientation, position and exposure the camera recorded are copied
         * across from here - without the orientation tag the picture lands on its side.
         */
        exifSource: ByteArray? = null,
    ): String {
        val name = baseName + if (heic) ".heic" else ".jpg"
        val uri = write(context, name, if (heic) "image/heic" else "image/jpeg") { it.write(bytes) }
        // The camera writes the position itself, from the request. Which way the camera was
        // pointing is not something it knows, so it is added here - and only to JPEG, because
        // ExifInterface can rewrite that container and not HEIF.
        if (!heic && (exifSource != null || pose.hasOrientation)) {
            runCatching { writeTags(context, uri, exifSource, pose) }
                .onFailure { Log.w(TAG, "Cannot write EXIF into $name", it) }
        }
        return name
    }

    /** What a camera's EXIF says about the shot, as opposed to about the encoding. */
    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    /**
     * The camera's EXIF, when the file is a re-encoding, plus where the camera was pointing: the
     * heading in GPSImgDirection, and the full attitude in UserComment, which has no standard tag
     * of its own.
     */
    private fun writeTags(context: Context, uri: Uri, exifSource: ByteArray?, pose: Pose) {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            exifSource?.let { source ->
                val original = ExifInterface(ByteArrayInputStream(source))
                for (tag in COPIED_TAGS) {
                    original.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
                }
            }
            pose.yawDegrees?.let { writeHeading(exif, it, pose) }
            exif.saveAttributes()
        }
    }

    private fun writeHeading(exif: ExifInterface, yaw: Float, pose: Pose) {
        exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, degreesAsRational(yaw))
        exif.setAttribute(
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            if (pose.trueNorth) "T" else "M",
        )
        val pitch = pose.pitchDegrees
        val roll = pose.rollDegrees
        if (pitch != null && roll != null) {
            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "oCam yaw=%.1f pitch=%.1f roll=%.1f deg".format(Locale.US, yaw, pitch, roll),
            )
        }
    }

    /** EXIF rationals are a fraction; one decimal place is finer than any phone compass. */
    private fun degreesAsRational(degrees: Float): String =
        "${(degrees * 10f).toInt().coerceIn(0, 3600)}/10"

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
        pose: Pose = Pose.NONE,
    ): String {
        val name = "$baseName.dng"
        DngCreator(characteristics, result).use { dng ->
            dng.setOrientation(exifOrientation(orientationDegrees))
            // A DNG is a TIFF that no EXIF writer here can reopen, so both halves of the pose go
            // in as the file is written: the position through the writer's own tag, and the
            // attitude as the description, which is the only free text DngCreator offers.
            pose.location?.let { runCatching { dng.setLocation(it) } }
            if (pose.hasOrientation) {
                dng.setDescription(
                    "oCam yaw=%.1f pitch=%.1f roll=%.1f deg %s".format(
                        Locale.US,
                        pose.yawDegrees,
                        pose.pitchDegrees ?: 0f,
                        pose.rollDegrees ?: 0f,
                        if (pose.trueNorth) "true" else "magnetic",
                    )
                )
            }
            write(context, name, "image/x-adobe-dng") { dng.writeImage(it, image) }
        }
        return name
    }

    /** @return the MediaStore row the bytes landed in, so EXIF can be added to it afterwards. */
    private fun write(
        context: Context,
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Uri {
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
            return uri
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
