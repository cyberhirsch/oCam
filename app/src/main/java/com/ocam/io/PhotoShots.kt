package com.ocam.io

/** One file in the album, as MediaStore lists it. */
class PhotoRow(val id: Long, val name: String, val mime: String)

/**
 * One press of the shutter as review sees it. RAW+JPEG writes two files that are one photograph, so
 * they are shown once and deleted together; [primary] is the one to look at.
 */
class ReviewShot(val baseName: String, val primary: PhotoRow, val rows: List<PhotoRow>) {
    /** What was written, such as JPEG+RAW. */
    val formats: String
        get() = rows.sortedBy { rank(it.mime) }.joinToString("+") { formatName(it.mime) }
}

/** Files into shots, keeping the order they came in, which is newest first. */
fun groupShots(rows: List<PhotoRow>): List<ReviewShot> {
    val byName = LinkedHashMap<String, MutableList<PhotoRow>>()
    for (row in rows) byName.getOrPut(row.name.substringBeforeLast('.')) { ArrayList() } += row
    return byName.map { (name, files) ->
        ReviewShot(name, files.minByOrNull { rank(it.mime) } ?: files.first(), files)
    }
}

/** What renders best first: a DNG is only for looking at when there is nothing else. */
private fun rank(mime: String): Int = when (mime) {
    "image/jpeg" -> 0
    "image/heic" -> 1
    else -> 2
}

private fun formatName(mime: String): String = when (mime) {
    "image/jpeg" -> "JPEG"
    "image/heic" -> "HEIC"
    "image/x-adobe-dng" -> "RAW"
    else -> mime.substringAfter('/').uppercase()
}
