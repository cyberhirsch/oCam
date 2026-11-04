package com.ocam.sequence

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Which way the lens pointed as a frame of a sweep was taken, in degrees. */
class PanoramaShot(val yawDegrees: Float, val pitchDegrees: Float)

/** Hands over a frame of a sweep, upright, as NV21: width x height, then width x height / 2. */
fun interface PanoramaFrames {
    fun load(index: Int, y: ByteArray, vu: ByteArray)
}

/**
 * What came out. [pixels] is NV21 at [canvasWidth] x [canvasHeight], and the picture is the rectangle
 * at [left], [top] inside it, where every point was seen.
 *
 * @property originX where the middle of the first frame landed on the canvas, as [originY] is.
 * @property scale how much smaller the canvas is than the frames it was made from.
 * @property joinsBySensor how many frames were placed by the motion sensor alone, having too little
 *   in them to line up with the one before.
 */
class StitchedPanorama(
    val pixels: ByteArray,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val originX: Float,
    val originY: Float,
    val scale: Float,
    val joinsBySensor: Int,
)

/**
 * Lays frames from a turning camera onto a cylinder around it, where turning is only a sideways
 * shift, and joins them there.
 *
 * Two passes, one frame in memory at a time. The first compares small copies of neighbouring frames
 * to learn where each one goes: the motion sensor gets it close, and the pictures finish the job, or
 * the sensor's word stands where a frame has too little in it to compare. With every place known,
 * the canvas can be allocated once and at its full size, and the second pass warps each frame onto
 * it and cuts the cheapest seam through its overlap with what is there, so that something that moved
 * appears once rather than twice. A short feather across the seam hides the join. Exposure is fixed
 * for a whole sweep, so nothing has to be evened out; a seam near the middle of an overlap also sits
 * equally far out on both frames, so what vignetting there is matches across it.
 */
object PanoramaStitcher {

    /** Long side of the copies frames are compared at. */
    private const val ALIGN_LONG_SIDE = 640

    /** How far the pictures may disagree with the sensor, as a share of a frame and in sine of a turn. */
    private const val MOST_DISAGREEMENT = 0.15f
    private const val MOST_ROLL = 0.1f

    /** Half the width of the blend across a seam, in two by two blocks. */
    private const val FEATHER = 16

    /** What staying in the middle of an overlap is worth, in brightness levels. */
    private const val CENTRE_PULL = 16f

    /** A level line through a sweep cannot be further off than this; past it, leave it alone. */
    private const val MOST_LEVELLING = 0.2f

    fun stitch(
        shots: List<PanoramaShot>,
        width: Int,
        height: Int,
        focal: Float,
        maxPixels: Long,
        frames: PanoramaFrames,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StitchedPanorama {
        require(shots.isNotEmpty()) { "Nothing to stitch" }
        require(width % 2 == 0 && height % 2 == 0) { "Chroma is subsampled: ${width}x$height" }
        val y = ByteArray(width * height)
        val vu = ByteArray(width * height / 2)

        var joinsBySensor = 0
        val places = ArrayList<Similarity>(shots.size)
        places += Similarity.IDENTITY
        val shrink = max(1, (max(width, height) + ALIGN_LONG_SIDE - 1) / ALIGN_LONG_SIDE)
        var previous: GrayImage? = null
        for (index in shots.indices) {
            // A single frame needs no comparing, which is one more read off the disk saved.
            if (shots.size == 1) break
            frames.load(index, y, vu)
            val small = onCylinder(GrayImage.fromLuma(y, width, height, shrink), focal / shrink)
            val last = previous
            if (last != null) {
                val seed = seedBetween(shots[index - 1], shots[index], focal / shrink)
                val step = believable(ImageAligner.align(small, last, seed, allowScale = false), seed, small.width)
                if (step == null) joinsBySensor++
                places += places.last().after((step ?: seed).forScale(shrink.toFloat()))
            }
            previous = small
        }
        val level = levelling(places)
        val placed = places.map { level.after(it) }

        // The rectangle every frame's cylinder image sits in, before any turn it has.
        val halfAcross = focal * atan(width / 2f / focal)
        val halfDown = height / 2f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (place in placed) {
            for (cornerX in floatArrayOf(-halfAcross, halfAcross)) {
                for (cornerY in floatArrayOf(-halfDown, halfDown)) {
                    minX = min(minX, place.mapX(cornerX, cornerY))
                    maxX = max(maxX, place.mapX(cornerX, cornerY))
                    minY = min(minY, place.mapY(cornerX, cornerY))
                    maxY = max(maxY, place.mapY(cornerX, cornerY))
                }
            }
        }
        val needed = (maxX - minX) * (maxY - minY)
        val scale = min(1f, sqrt(maxPixels / needed))
        val canvasWidth = (ceil((maxX - minX) * scale).toInt() + 3) and 1.inv()
        val canvasHeight = (ceil((maxY - minY) * scale).toInt() + 3) and 1.inv()

        val canvas = Canvas(canvasWidth, canvasHeight)
        val warp = Warper(width, height, focal, scale, minX, minY)
        for (index in shots.indices) {
            frames.load(index, y, vu)
            val incoming = warp.onto(canvasWidth, canvasHeight, placed[index], y, vu)
            val movingRight = index == 0 || placed[index].tx >= placed[index - 1].tx
            canvas.add(incoming, movingRight)
            onFrame(index + 1, shots.size)
        }

        val rectangle = largestCovered(canvas.covered, canvasWidth / 2, canvasHeight / 2)
        return StitchedPanorama(
            pixels = canvas.pixels,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            left = rectangle.left * 2,
            top = rectangle.top * 2,
            width = rectangle.width * 2,
            height = rectangle.height * 2,
            originX = (level.tx - minX) * scale,
            originY = (level.ty - minY) * scale,
            scale = scale,
            joinsBySensor = joinsBySensor,
        )
    }

    /**
     * How a point of [next] on the cylinder lands on [before]'s, as far as the sensor knows. Turning
     * right slides what is ahead to the left, so a point of the new frame is further right in the old
     * one; tilting up slides it down, so it is higher in the old one.
     */
    private fun seedBetween(before: PanoramaShot, next: PanoramaShot, focal: Float): Similarity {
        val turn = Math.toRadians(wrappedDegrees(next.yawDegrees - before.yawDegrees).toDouble()).toFloat()
        val tilt = Math.toRadians((next.pitchDegrees - before.pitchDegrees).toDouble()).toFloat()
        return Similarity(1f, 0f, focal * turn, -focal * tilt)
    }

    private fun believable(found: Aligned, seed: Similarity, width: Int): Similarity? {
        val move = found.transform
        val most = width * MOST_DISAGREEMENT
        return move.takeIf {
            found.reliable && abs(move.tx - seed.tx) < most && abs(move.ty - seed.ty) < most &&
                abs(move.b) < MOST_ROLL
        }
    }

    /** A camera held slightly askew climbs across a sweep. Turning the whole of it undoes that. */
    private fun levelling(places: List<Similarity>): Similarity {
        val last = places.last()
        if (places.size < 2 || abs(last.tx) < 1f) return Similarity.IDENTITY
        val slope = last.ty / last.tx
        if (abs(slope) > MOST_LEVELLING) return Similarity.IDENTITY
        val angle = -atan(slope)
        return Similarity(cos(angle), sin(angle), 0f, 0f)
    }

    private fun onCylinder(flat: GrayImage, focal: Float): GrayImage {
        val across = ceil(2f * focal * atan(flat.width / 2f / focal)).toInt().coerceAtLeast(4)
        val out = GrayImage(across, flat.height)
        val cx = (across - 1) / 2f
        val cy = (flat.height - 1) / 2f
        val fx = (flat.width - 1) / 2f
        for (row in 0 until out.height) {
            val v = row - cy
            for (column in 0 until across) {
                val x = focal * tan((column - cx) / focal)
                val py = v * sqrt(x * x + focal * focal) / focal
                val sx = x + fx
                val sy = py + cy
                val inside = sx >= 0f && sy >= 0f && sx <= flat.width - 1.001f && sy <= flat.height - 1.001f
                out.data[row * across + column] = if (inside) {
                    val x0 = sx.toInt()
                    val y0 = sy.toInt()
                    val base = y0 * flat.width + x0
                    val top = flat.data[base] + (flat.data[base + 1] - flat.data[base]) * (sx - x0)
                    val bottom = flat.data[base + flat.width] +
                        (flat.data[base + flat.width + 1] - flat.data[base + flat.width]) * (sx - x0)
                    top + (bottom - top) * (sy - y0)
                } else {
                    -1f
                }
            }
        }
        return out
    }

    /** A frame on the canvas: the patch it covers, whose edges fall on whole blocks. */
    private class Patch(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val vu: ByteArray,
        val valid: BooleanArray,
    )

    private class Warper(
        private val width: Int,
        private val height: Int,
        private val focal: Float,
        private val scale: Float,
        private val originX: Float,
        private val originY: Float,
    ) {
        private var y = ByteArray(0)
        private var vu = ByteArray(0)
        private var valid = BooleanArray(0)

        fun onto(
            canvasWidth: Int,
            canvasHeight: Int,
            place: Similarity,
            sourceY: ByteArray,
            sourceVu: ByteArray,
        ): Patch {
            val halfAcross = focal * atan(width / 2f / focal)
            val halfDown = height / 2f
            var low = Float.MAX_VALUE
            var high = -Float.MAX_VALUE
            var upper = Float.MAX_VALUE
            var lower = -Float.MAX_VALUE
            for (cornerX in floatArrayOf(-halfAcross, halfAcross)) {
                for (cornerY in floatArrayOf(-halfDown, halfDown)) {
                    low = min(low, place.mapX(cornerX, cornerY))
                    high = max(high, place.mapX(cornerX, cornerY))
                    upper = min(upper, place.mapY(cornerX, cornerY))
                    lower = max(lower, place.mapY(cornerX, cornerY))
                }
            }
            val left = floor((low - originX) * scale).toInt().coerceIn(0, canvasWidth - 2) and 1.inv()
            val top = floor((upper - originY) * scale).toInt().coerceIn(0, canvasHeight - 2) and 1.inv()
            val right = min(canvasWidth, (ceil((high - originX) * scale).toInt() + 2) and 1.inv())
            val bottom = min(canvasHeight, (ceil((lower - originY) * scale).toInt() + 2) and 1.inv())
            val patchWidth = right - left
            val patchHeight = bottom - top
            val blocks = patchWidth / 2 * (patchHeight / 2)
            if (y.size < patchWidth * patchHeight) {
                y = ByteArray(patchWidth * patchHeight)
                vu = ByteArray(patchWidth * patchHeight / 2)
            }
            if (valid.size < blocks) valid = BooleanArray(blocks)

            val back = place.inverse()
            val cx = (width - 1) / 2f
            val cy = (height - 1) / 2f
            val blockWidth = patchWidth / 2
            val outY = y
            val outVu = vu
            val outValid = valid
            inBands(patchHeight / 2) { from, to ->
                val sampleX = FloatArray(4)
                val sampleY = FloatArray(4)
                for (row in from until to) {
                    for (column in 0 until blockWidth) {
                        var all = true
                        for (corner in 0 until 4) {
                            val across = column * 2 + (corner and 1)
                            val down = row * 2 + (corner shr 1)
                            val worldX = (left + across) / scale + originX
                            val worldY = (top + down) / scale + originY
                            val u = back.mapX(worldX, worldY)
                            val v = back.mapY(worldX, worldY)
                            val angle = u / focal
                            if (abs(angle) >= 1.5f) {
                                all = false
                                break
                            }
                            val x = focal * tan(angle)
                            val py = v * sqrt(x * x + focal * focal) / focal
                            val sx = x + cx
                            val sy = py + cy
                            if (sx < 0f || sy < 0f || sx > width - 1.001f || sy > height - 1.001f) {
                                all = false
                                break
                            }
                            sampleX[corner] = sx
                            sampleY[corner] = sy
                        }
                        val block = row * blockWidth + column
                        outValid[block] = all
                        if (!all) continue
                        for (corner in 0 until 4) {
                            val target = (row * 2 + (corner shr 1)) * patchWidth + column * 2 + (corner and 1)
                            outY[target] = bilinear(sourceY, width, 1, 0, sampleX[corner], sampleY[corner]).toByte()
                        }
                        // A chroma sample sits in the middle of its block, half a pixel in.
                        val middleX = (sampleX[0] + sampleX[1] + sampleX[2] + sampleX[3]) / 4f
                        val middleY = (sampleY[0] + sampleY[1] + sampleY[2] + sampleY[3]) / 4f
                        val chromaX = ((middleX - 0.5f) / 2f).coerceIn(0f, width / 2 - 1.001f)
                        val chromaY = ((middleY - 0.5f) / 2f).coerceIn(0f, height / 2 - 1.001f)
                        val target = row * patchWidth + column * 2
                        outVu[target] = bilinear(sourceVu, width, 2, 0, chromaX, chromaY).toByte()
                        outVu[target + 1] = bilinear(sourceVu, width, 2, 1, chromaX, chromaY).toByte()
                    }
                }
            }
            return Patch(left, top, patchWidth, patchHeight, y, vu, valid)
        }
    }

    private class Canvas(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height * 3 / 2).also {
            it.fill(128.toByte(), width * height, it.size)
        }
        val covered = BooleanArray(width / 2 * (height / 2))
        private val blockStride = width / 2
        private val chromaStart = width * height

        fun add(patch: Patch, movingRight: Boolean) {
            val blockWidth = patch.width / 2
            val blockHeight = patch.height / 2
            val seam = seamThrough(patch, blockWidth, blockHeight)
            val side = if (movingRight) 1 else -1
            inBands(blockHeight) { from, to ->
                for (row in from until to) {
                    val canvasRow = patch.top / 2 + row
                    for (column in 0 until blockWidth) {
                        if (!patch.valid[row * blockWidth + column]) continue
                        val canvasColumn = patch.left / 2 + column
                        val block = canvasRow * blockStride + canvasColumn
                        // Out of 256; the new frame's share of this block.
                        val share = if (!covered[block]) 256 else {
                            val past = side * (column - seam[row])
                            ((past + FEATHER) * 256 / (2 * FEATHER)).coerceIn(0, 256)
                        }
                        if (share > 0) blendBlock(patch, column, row, canvasColumn, canvasRow, share)
                        covered[block] = true
                    }
                }
            }
        }

        private fun blendBlock(patch: Patch, column: Int, row: Int, canvasColumn: Int, canvasRow: Int, share: Int) {
            val keep = 256 - share
            for (corner in 0 until 4) {
                val across = (corner and 1)
                val down = (corner shr 1)
                val from = (row * 2 + down) * patch.width + column * 2 + across
                val to = (canvasRow * 2 + down) * width + canvasColumn * 2 + across
                pixels[to] = (((pixels[to].toInt() and 0xFF) * keep +
                    (patch.y[from].toInt() and 0xFF) * share + 128) shr 8).toByte()
            }
            val from = row * patch.width + column * 2
            val to = chromaStart + canvasRow * width + canvasColumn * 2
            for (offset in 0..1) {
                pixels[to + offset] = (((pixels[to + offset].toInt() and 0xFF) * keep +
                    (patch.vu[from + offset].toInt() and 0xFF) * share + 128) shr 8).toByte()
            }
        }

        /** Per row of blocks, where in the patch the seam runs. Rows with no overlap get one anyway. */
        private fun seamThrough(patch: Patch, blockWidth: Int, blockHeight: Int): IntArray {
            val cost = IntArray(blockWidth * blockHeight) { NO_OVERLAP }
            inBands(blockHeight) { from, to ->
                for (row in from until to) {
                    val canvasRow = patch.top / 2 + row
                    var first = -1
                    var last = -1
                    for (column in 0 until blockWidth) {
                        val here = patch.valid[row * blockWidth + column] &&
                            covered[canvasRow * blockStride + patch.left / 2 + column]
                        if (here) {
                            if (first < 0) first = column
                            last = column
                        }
                    }
                    if (first < 0) continue
                    val middle = (first + last) / 2f
                    val half = max(1f, (last - first) / 2f)
                    for (column in first..last) {
                        val canvasColumn = patch.left / 2 + column
                        val here = patch.valid[row * blockWidth + column] &&
                            covered[canvasRow * blockStride + canvasColumn]
                        if (!here) continue
                        val pull = CENTRE_PULL * abs(column - middle) / half
                        cost[row * blockWidth + column] =
                            difference(patch, column, row, canvasColumn, canvasRow) + pull.toInt()
                    }
                }
            }
            return cheapestSeam(cost, blockWidth, blockHeight)
        }

        private fun difference(patch: Patch, column: Int, row: Int, canvasColumn: Int, canvasRow: Int): Int {
            val top = (row * 2) * patch.width + column * 2
            val canvasTop = (canvasRow * 2) * width + canvasColumn * 2
            val luma = abs((patch.y[top].toInt() and 0xFF) - (pixels[canvasTop].toInt() and 0xFF))
            val chroma = chromaStart + canvasRow * width + canvasColumn * 2
            val from = row * patch.width + column * 2
            val red = abs((patch.vu[from].toInt() and 0xFF) - (pixels[chroma].toInt() and 0xFF))
            val blue = abs((patch.vu[from + 1].toInt() and 0xFF) - (pixels[chroma + 1].toInt() and 0xFF))
            return luma + red + blue
        }
    }
}

/** How far apart two compass readings are, the short way round. */
fun wrappedDegrees(degrees: Float): Float {
    var turned = degrees % 360f
    if (turned > 180f) turned -= 360f
    if (turned < -180f) turned += 360f
    return turned
}

private const val NO_OVERLAP = -1
private const val OFF_THE_OVERLAP = 1 shl 20

/**
 * The top to bottom path through [cost], moving at most one column per row, with the smallest total.
 * [NO_OVERLAP] marks a cell that cannot be crossed if the row has anywhere better; a row with nothing
 * in it costs nothing, so the seam just carries on through.
 */
internal fun cheapestSeam(cost: IntArray, width: Int, height: Int): IntArray {
    var totals = IntArray(width)
    var next = IntArray(width)
    val cameFrom = ByteArray(width * height)
    for (row in 0 until height) {
        val base = row * width
        var empty = true
        for (column in 0 until width) if (cost[base + column] != NO_OVERLAP) empty = false
        for (column in 0 until width) {
            var best = totals[column]
            var step = 0
            if (column > 0 && totals[column - 1] < best) {
                best = totals[column - 1]
                step = -1
            }
            if (column < width - 1 && totals[column + 1] < best) {
                best = totals[column + 1]
                step = 1
            }
            cameFrom[base + column] = step.toByte()
            val here = cost[base + column]
            next[column] = when {
                empty -> best
                here == NO_OVERLAP -> min(best + OFF_THE_OVERLAP, Int.MAX_VALUE / 2)
                else -> best + here
            }
        }
        val swap = totals
        totals = next
        next = swap
    }
    val seam = IntArray(height)
    var column = totals.indices.minByOrNull { totals[it] } ?: 0
    for (row in height - 1 downTo 0) {
        seam[row] = column
        column += cameFrom[row * width + column]
    }
    return seam
}

internal class CoveredRectangle(val left: Int, val top: Int, val width: Int, val height: Int)

/** The largest rectangle in which every cell is covered, by the standard histogram walk. */
internal fun largestCovered(covered: BooleanArray, width: Int, height: Int): CoveredRectangle {
    val heights = IntArray(width)
    val stack = IntArray(width + 1)
    var bestArea = 0L
    var best = CoveredRectangle(0, 0, 0, 0)
    for (row in 0 until height) {
        for (column in 0 until width) {
            heights[column] = if (covered[row * width + column]) heights[column] + 1 else 0
        }
        var depth = 0
        for (column in 0..width) {
            val current = if (column == width) 0 else heights[column]
            while (depth > 0 && heights[stack[depth - 1]] >= current) {
                val tall = heights[stack[--depth]]
                val start = if (depth == 0) 0 else stack[depth - 1] + 1
                val area = tall.toLong() * (column - start)
                if (area > bestArea) {
                    bestArea = area
                    best = CoveredRectangle(start, row - tall + 1, column - start, tall)
                }
            }
            if (column < width) stack[depth++] = column
        }
    }
    return best
}

/**
 * How far to turn between frames of a sweep, in degrees: four tenths of what the frame takes in, so
 * each overlaps the last by six tenths, which leaves room to lay a seam away from the moving subject.
 */
fun panoramaStepDegrees(focal: Float, uprightWidth: Int): Float =
    Math.toDegrees(2.0 * atan2(uprightWidth / 2.0, focal.toDouble())).toFloat() * 0.4f

/**
 * Decides which frames of a stream to keep, from the way the lens is pointing. The first frame is
 * always kept. The first turn sets the direction, and after that only further progress counts, so
 * swinging back a little does not lay frames over each other.
 */
class PanoramaTrigger(private val stepDegrees: Float) {
    private var lastYaw = Float.NaN
    private var direction = 0

    /** How far the sweep has come, in degrees. */
    var swept = 0f
        private set

    /** Whether the last frame kept was further on than it should have been. */
    var tooFast = false
        private set

    /** Whether the sweep has come all the way around. */
    val complete: Boolean get() = swept >= 360f - stepDegrees

    fun offer(yawDegrees: Float): Boolean {
        if (lastYaw.isNaN()) {
            lastYaw = yawDegrees
            return true
        }
        val turned = wrappedDegrees(yawDegrees - lastYaw)
        if (abs(turned) < stepDegrees) return false
        if (direction == 0) direction = if (turned > 0f) 1 else -1
        if (turned * direction < 0f) return false
        tooFast = abs(turned) > stepDegrees * TOO_FAR
        swept += abs(turned)
        lastYaw = yawDegrees
        return true
    }

    private companion object {
        /** Past this, neighbours overlap by a third of a frame or less. */
        const val TOO_FAR = 1.6f
    }
}

/** How many pixels a canvas may hold in [freeBytes], leaving room for what stitching keeps beside it. */
fun panoramaPixelBudget(freeBytes: Long, frameBytes: Long): Long {
    // Two frames' worth for the one being read and the one being warped; 1.75 bytes a pixel for the
    // picture and which of its blocks are covered; a third kept back for everything else on the heap.
    val spare = (freeBytes * 2 / 3 - frameBytes * 2).coerceAtLeast(frameBytes)
    return spare * 4 / 7
}

/**
 * Turn NV21 clockwise by [degrees], a multiple of ninety. A chroma sample is a V and a U side by
 * side, and they stay in that order.
 */
fun rotateNv21(
    y: ByteArray,
    vu: ByteArray,
    width: Int,
    height: Int,
    degrees: Int,
    outY: ByteArray,
    outVu: ByteArray,
) {
    val blockWidth = width / 2
    val blockHeight = height / 2
    when (degrees) {
        90 -> {
            for (row in 0 until height) for (column in 0 until width) {
                outY[column * height + (height - 1 - row)] = y[row * width + column]
            }
            for (row in 0 until blockHeight) for (column in 0 until blockWidth) {
                val to = column * height + (blockHeight - 1 - row) * 2
                outVu[to] = vu[row * width + column * 2]
                outVu[to + 1] = vu[row * width + column * 2 + 1]
            }
        }
        180 -> {
            for (i in y.indices) outY[y.size - 1 - i] = y[i]
            for (row in 0 until blockHeight) for (column in 0 until blockWidth) {
                val to = (blockHeight - 1 - row) * width + (blockWidth - 1 - column) * 2
                outVu[to] = vu[row * width + column * 2]
                outVu[to + 1] = vu[row * width + column * 2 + 1]
            }
        }
        270 -> {
            for (row in 0 until height) for (column in 0 until width) {
                outY[(width - 1 - column) * height + row] = y[row * width + column]
            }
            for (row in 0 until blockHeight) for (column in 0 until blockWidth) {
                val to = (blockWidth - 1 - column) * height + row * 2
                outVu[to] = vu[row * width + column * 2]
                outVu[to + 1] = vu[row * width + column * 2 + 1]
            }
        }
        else -> {
            y.copyInto(outY)
            vu.copyInto(outVu)
        }
    }
}
