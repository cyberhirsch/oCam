package com.ocam.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ocam.io.PhotoDetails
import com.ocam.io.PhotoLibrary
import com.ocam.io.ReviewShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/** What a texture can be counted on to hold, and what is worth keeping in memory per picture. */
private const val REVIEW_LONG_SIDE = 4096
private const val REVIEW_MAX_PIXELS = 16_000_000L

/** How far a picture can be enlarged, and how far a double tap goes. */
private const val MOST_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 3f

/** The shot that opens review: the newest one, small, where the eye goes after the shutter. */
@Composable
fun ReviewThumbnail(shot: ReviewShot, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picture by produceState<Bitmap?>(null, shot.primary.id) {
        value = withContext(Dispatchers.IO) { PhotoLibrary.thumbnail(context, shot.primary, 144) }
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        picture?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Review photos",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The shots in the album, newest first, each one a swipe from the next. It sits over the camera rather
 * than in a window of its own, so the volume keys still reach the app and take it straight back to
 * shooting. A picture is looked at no larger than [REVIEW_LONG_SIDE], so a 50 megapixel still can be
 * enlarged but stops short of its last pixel.
 */
@Composable
fun ReviewScreen(
    shots: List<ReviewShot>,
    onDelete: (ReviewShot) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    val pager = rememberPagerState { shots.size }
    var zoomed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf("") }
    var histogram by remember { mutableStateOf(FrameStats.EMPTY) }
    var deleting by remember { mutableStateOf(false) }
    val current = shots.getOrNull(pager.currentPage)

    LaunchedEffect(current?.baseName) {
        zoomed = false
        deleting = false
        details = ""
        histogram = FrameStats.EMPTY
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Nothing on this screen may fall through to the sliders under it.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        HorizontalPager(
            state = pager,
            // While a picture is enlarged a drag moves around in it.
            userScrollEnabled = !zoomed,
            key = { shots[it].baseName },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val shot = shots[page]
            val isCurrent = page == pager.currentPage
            ReviewPage(
                shot = shot,
                onZoomed = { if (isCurrent) zoomed = it },
                onLoaded = { text, stats ->
                    if (shots.getOrNull(pager.currentPage)?.baseName == shot.baseName) {
                        details = text
                        histogram = stats
                    }
                },
                isCurrent = isCurrent,
            )
        }

        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatButton(label = "BACK", active = false, onClick = onClose)
                FlatButton(
                    label = "SHARE",
                    active = false,
                    enabled = current != null,
                    onClick = { current?.let { sharePhoto(context, it) } },
                )
                FlatButton(
                    // Nothing brings a deleted file back, so it takes a second press to say so.
                    label = if (deleting) "DELETE FOR GOOD" else "DELETE",
                    active = deleting,
                    enabled = current != null,
                    onClick = {
                        if (deleting) {
                            deleting = false
                            current?.let(onDelete)
                        } else {
                            deleting = true
                        }
                    },
                )
                FlatButton(label = "FILES", active = false, onClick = { openPhotoFolder(context) })
            }

            Box(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (shots.isEmpty()) "Nothing here yet" else
                            "${pager.currentPage + 1} of ${shots.size}" +
                                (current?.let { " · ${it.baseName} · ${it.formats}" } ?: ""),
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (details.isNotEmpty()) {
                        Text(text = details, color = Color(0xBBFFFFFF), fontSize = 10.sp, maxLines = 3)
                    }
                }
                Histogram(stats = histogram, modifier = Modifier.width(96.dp))
            }
        }
    }
}

@Composable
private fun ReviewPage(
    shot: ReviewShot,
    isCurrent: Boolean,
    onZoomed: (Boolean) -> Unit,
    onLoaded: (String, FrameStats) -> Unit,
) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val loaded by produceState<LoadedPicture?>(null, shot.primary.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val bitmap = PhotoLibrary.picture(context, shot.primary, REVIEW_LONG_SIDE, REVIEW_MAX_PIXELS)
                if (bitmap == null) {
                    null
                } else {
                    val small = Bitmap.createScaledBitmap(bitmap, 120, 90, true)
                    val stats = analyseFrame(small)
                    if (small !== bitmap) small.recycle()
                    LoadedPicture(
                        bitmap,
                        describe(PhotoLibrary.details(context, shot.primary), bitmap),
                        stats,
                    )
                }
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        failed = value == null
    }

    // Asked for again as a page becomes the current one: a neighbour loads before it is looked at.
    LaunchedEffect(loaded, isCurrent) {
        val picture = loaded
        if (isCurrent && picture != null) onLoaded(picture.details, picture.stats)
    }

    Box(modifier = Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val picture = loaded
        when {
            picture != null -> ZoomablePicture(picture.bitmap, onZoomed)
            failed -> Text("This file cannot be shown here", color = Color(0x99FFFFFF), fontSize = 12.sp)
            else -> Text("…", color = Color(0x99FFFFFF), fontSize = 12.sp)
        }
    }
}

private class LoadedPicture(val bitmap: Bitmap, val details: String, val stats: FrameStats)

private fun describe(exif: PhotoDetails?, bitmap: Bitmap): String {
    val parts = listOfNotNull(
        "${bitmap.width}×${bitmap.height} shown",
        exif?.iso?.let { "ISO $it" },
        exif?.exposureNs?.let(::formatShutter),
        formatAperture(exif?.aperture),
    )
    val line = parts.joinToString(" · ")
    return exif?.description?.takeIf { it.isNotBlank() }?.let { String.format(Locale.US, "%s\n%s", line, it) } ?: line
}

/**
 * One finger on a picture at its normal size belongs to the pager, so it is left alone. Two fingers,
 * or any finger once enlarged, belong to the picture.
 */
@Composable
private fun ZoomablePicture(bitmap: Bitmap, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    Image(
        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            panX = 0f
                            panY = 0f
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                            panX = panToKeep(tap.x, scale, size.width.toFloat())
                            panY = panToKeep(tap.y, scale, size.height.toFloat())
                        }
                        onZoomed(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MOST_ZOOM)
                            val move = event.calculatePan()
                            val grew = next / scale
                            scale = next
                            panX = clampPan(panX * grew + move.x, scale, size.width.toFloat())
                            panY = clampPan(panY * grew + move.y, scale, size.height.toFloat())
                            onZoomed(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = panX
                translationY = panY
            },
    )
}

/** The file to look at, which for RAW+JPEG is the JPEG: most apps cannot show a DNG. */
private fun sharePhoto(context: Context, shot: ReviewShot) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = shot.primary.mime
        putExtra(Intent.EXTRA_STREAM, PhotoLibrary.uri(shot.primary))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, shot.baseName)) }
}
