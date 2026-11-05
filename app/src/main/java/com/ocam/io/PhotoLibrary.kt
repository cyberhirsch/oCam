package com.ocam.io

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "PhotoLibrary"

/** What a file's EXIF says about how it was taken, as far as it says anything. */
class PhotoDetails(
    val iso: Int?,
    val exposureNs: Long?,
    val aperture: Float?,
    val description: String?,
)

/**
 * Reads back what [PhotoStore] wrote. Android shows an app its own files without any permission, so
 * none is asked for. That also sets the limit: after a reinstall the older files belong to nobody
 * this app can prove it is, and only a file browser can still see them.
 */
object PhotoLibrary {

    val collection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun uri(row: PhotoRow): Uri = ContentUris.withAppendedId(collection, row.id)

    /** Every shot in the album that this app can see, newest first. */
    fun shots(context: Context): List<ReviewShot> {
        val rows = ArrayList<PhotoRow>()
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
            ),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/oCam/%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += PhotoRow(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
            }
        }
        return groupShots(rows)
    }

    fun thumbnail(context: Context, row: PhotoRow, side: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri(row), Size(side, side), null) }
            .onFailure { Log.w(TAG, "No thumbnail for ${row.name}", it) }
            .getOrNull()

    /**
     * The picture to look at, turned the way its EXIF says, in pixels that can be read back. Not
     * necessarily all of them: a texture has limits, and a panorama would pass them. A DNG the
     * platform cannot develop falls back on the preview inside it.
     */
    fun picture(context: Context, row: PhotoRow, longSide: Int, maxPixels: Long): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri(row))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = min(
                    1f,
                    min(
                        longSide.toFloat() / max(width, height),
                        sqrt(maxPixels.toFloat() / (width.toLong() * height)),
                    ),
                )
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decode ${row.name}", e)
            null
        }
        return decoded ?: thumbnail(context, row, longSide / 2)
    }

    fun details(context: Context, row: PhotoRow): PhotoDetails? = runCatching {
        context.contentResolver.openInputStream(uri(row))?.use { stream ->
            val exif = ExifInterface(stream)
            PhotoDetails(
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                exposureNs = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    .takeIf { it > 0.0 }?.let { (it * 1_000_000_000.0).toLong() },
                aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    .takeIf { it > 0.0 }?.toFloat(),
                description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            )
        }
    }.onFailure { Log.w(TAG, "Cannot read the EXIF of ${row.name}", it) }.getOrNull()

    /**
     * Deletes every file of the shot, for good: there is no bin to get it back from.
     *
     * @return how many files of it are gone.
     */
    fun delete(context: Context, shot: ReviewShot): Int = shot.rows.sumOf { row ->
        runCatching { context.contentResolver.delete(uri(row), null, null) }
            .onFailure { Log.e(TAG, "Cannot delete ${row.name}", it) }
            .getOrDefault(0)
    }
}
