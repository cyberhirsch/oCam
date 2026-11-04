package com.ocam.sequence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds one picture sharp from front to back out of frames focused at different distances, in
 * memory that does not grow with their number: every frame is merged as it arrives and let go.
 *
 * A lens changes its field of view as it focuses, so each frame is first lined up with the one before
 * it, which is the frame nearest it in focus and so the easiest to compare, and through that with the
 * first. Then, level by level of a Laplacian pyramid, each point keeps the detail of whichever frame
 * had the most around it. Below the coarsest level no detail is left to choose between, and the
 * frames are averaged. Colour goes with the finest detail: a block takes its chroma from the frame
 * that won it there, and the finest level is decided per block to keep that one choice.
 */
class FocusStack(val width: Int, val height: Int) {

    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Chroma is subsampled, so a frame has to be even: ${width}x$height"
        }
    }

    private val blockWidth = width / 2
    private val blockHeight = height / 2
    private val levelCount = run {
        var levels = 1
        var side = min(width, height)
        while (levels < MAX_LEVELS && side >= SMALLEST_LEVEL * 2) {
            side = (side + 1) / 2
            levels++
        }
        levels
    }
    private val widths = IntArray(levelCount).also { sizes ->
        sizes[0] = width
        for (level in 1 until levelCount) sizes[level] = (sizes[level - 1] + 1) / 2
    }
    private val heights = IntArray(levelCount).also { sizes ->
        sizes[0] = height
        for (level in 1 until levelCount) sizes[level] = (sizes[level - 1] + 1) / 2
    }

    /** The detail each point has kept; the last level is a running sum of what is left. */
    private val kept = Array(levelCount) { FloatArray(widths[it] * heights[it]) }

    /** How much detail won each point. The finest level has one number per two by two block. */
    private val strength = Array(levelCount - 1) { level ->
        if (level == 0) FloatArray(blockWidth * blockHeight) else FloatArray(widths[level] * heights[level])
    }
    private val chroma = ByteArray(width * height / 2)

    private val shrink = max(1, (max(width, height) + ALIGN_LONG_SIDE - 1) / ALIGN_LONG_SIDE)
    private var previous: GrayImage? = null
    private var toLatest = Similarity.IDENTITY
    private var warpedY: ByteArray? = null
    private var warpedVu: ByteArray? = null

    /** How many frames have gone in. */
    var frames = 0
        private set

    /** How many of them could not be lined up, and went in where they were. */
    var unaligned = 0
        private set

    /** Add one frame: [y] is width x height, [vu] is width x height / 2. */
    fun add(y: ByteArray, vu: ByteArray) {
        val small = GrayImage.fromLuma(y, width, height, shrink)
        val last = previous
        if (last == null) {
            merge(y, vu)
        } else {
            val found = ImageAligner.align(last, small, Similarity.IDENTITY, allowScale = true)
            val step = found.transform
            val believable = found.reliable && abs(step.a - 1f) < MOST_SIZE_CHANGE &&
                abs(step.b) < MOST_SIZE_CHANGE && abs(step.tx) < small.width * MOST_SHIFT &&
                abs(step.ty) < small.width * MOST_SHIFT
            if (believable) toLatest = step.after(toLatest) else unaligned++

            val full = toLatest.forScale(shrink.toFloat())
            val corner = max(width, height) / 2f
            val still = abs(full.a - 1f) * corner < STILL_PIXELS && abs(full.b) * corner < STILL_PIXELS &&
                abs(full.tx) < STILL_PIXELS && abs(full.ty) < STILL_PIXELS
            if (still) {
                merge(y, vu)
            } else {
                val outY = warpedY ?: ByteArray(y.size).also { warpedY = it }
                val outVu = warpedVu ?: ByteArray(vu.size).also { warpedVu = it }
                warpNv21(y, vu, width, height, full, outY, outVu)
                merge(outY, outVu)
            }
        }
        previous = small
        frames++
    }

    private fun merge(y: ByteArray, vu: ByteArray) {
        val first = frames == 0
        var level = ExposureFusion.Plane(width, height)
        val source = level.data
        inBands(height) { from, to ->
            for (i in from * width until to * width) source[i] = (y[i].toInt() and 0xFF).toFloat()
        }
        for (depth in 0 until levelCount - 1) {
            val next = ExposureFusion.down(level)
            subtractUpsampled(level, next)
            if (depth == 0) keepFinest(level.data, vu, first) else keepBest(depth, level.data, first)
            level = next
        }
        val base = kept[levelCount - 1]
        for (i in base.indices) base[i] += level.data[i]
    }

    /** The finest level, where one decision per block also settles the block's colour. */
    private fun keepFinest(detail: FloatArray, vu: ByteArray, first: Boolean) {
        val squares = FloatArray(blockWidth * blockHeight)
        inBands(blockHeight) { from, to ->
            for (row in from until to) {
                for (column in 0 until blockWidth) {
                    val top = row * 2 * width + column * 2
                    val bottom = top + width
                    squares[row * blockWidth + column] = detail[top] * detail[top] +
                        detail[top + 1] * detail[top + 1] + detail[bottom] * detail[bottom] +
                        detail[bottom + 1] * detail[bottom + 1]
                }
            }
        }
        val around = surrounding(squares, blockWidth, blockHeight)
        val best = strength[0]
        val finest = kept[0]
        inBands(blockHeight) { from, to ->
            for (row in from until to) {
                for (column in 0 until blockWidth) {
                    val block = row * blockWidth + column
                    if (!first && around[block] <= best[block]) continue
                    best[block] = around[block]
                    val top = row * 2 * width + column * 2
                    val bottom = top + width
                    finest[top] = detail[top]
                    finest[top + 1] = detail[top + 1]
                    finest[bottom] = detail[bottom]
                    finest[bottom + 1] = detail[bottom + 1]
                    val colour = row * width + column * 2
                    chroma[colour] = vu[colour]
                    chroma[colour + 1] = vu[colour + 1]
                }
            }
        }
    }

    private fun keepBest(depth: Int, detail: FloatArray, first: Boolean) {
        val levelWidth = widths[depth]
        val levelHeight = heights[depth]
        val squares = FloatArray(detail.size) { detail[it] * detail[it] }
        val around = surrounding(squares, levelWidth, levelHeight)
        val best = strength[depth]
        val target = kept[depth]
        inBands(levelHeight) { from, to ->
            for (i in from * levelWidth until to * levelWidth) {
                if (first || around[i] > best[i]) {
                    best[i] = around[i]
                    target[i] = detail[i]
                }
            }
        }
    }

    /** Each point and its eight neighbours, added up, so a single noisy sample cannot win a point. */
    private fun surrounding(values: FloatArray, planeWidth: Int, planeHeight: Int): FloatArray {
        val out = FloatArray(values.size)
        inBands(planeHeight) { from, to ->
            for (row in from until to) {
                val above = max(row - 1, 0) * planeWidth
                val here = row * planeWidth
                val below = min(row + 1, planeHeight - 1) * planeWidth
                for (column in 0 until planeWidth) {
                    val left = max(column - 1, 0)
                    val right = min(column + 1, planeWidth - 1)
                    out[here + column] = values[above + left] + values[above + column] + values[above + right] +
                        values[here + left] + values[here + column] + values[here + right] +
                        values[below + left] + values[below + column] + values[below + right]
                }
            }
        }
        return out
    }

    /** Leave in [level] what it has and [next], its half size copy, does not. */
    private fun subtractUpsampled(level: ExposureFusion.Plane, next: ExposureFusion.Plane) {
        val levelWidth = level.width
        val nextWidth = next.width
        inBands(level.height) { from, to ->
            for (row in from until to) {
                val y0 = row / 2
                val y1 = min(y0 + (row and 1), next.height - 1)
                val base = row * levelWidth
                for (column in 0 until levelWidth) {
                    val x0 = column / 2
                    val x1 = min(x0 + (column and 1), nextWidth - 1)
                    level.data[base + column] -= (next.data[y0 * nextWidth + x0] +
                        next.data[y0 * nextWidth + x1] + next.data[y1 * nextWidth + x0] +
                        next.data[y1 * nextWidth + x1]) / 4f
                }
            }
        }
    }

    /** The picture, as NV21. This uses up what was gathered, so it is the last thing to call. */
    fun writeNv21(out: ByteArray) {
        check(frames > 0) { "No frames went in" }
        val base = kept[levelCount - 1]
        for (i in base.indices) base[i] /= frames.toFloat()
        var above = ExposureFusion.Plane(widths[levelCount - 1], heights[levelCount - 1], base)
        for (depth in levelCount - 2 downTo 0) {
            val here = ExposureFusion.Plane(widths[depth], heights[depth], kept[depth])
            ExposureFusion.addUpsampled(here, above)
            above = here
        }
        val luma = kept[0]
        for (i in luma.indices) out[i] = luma[i].roundToInt().coerceIn(0, 255).toByte()
        chroma.copyInto(out, width * height)
    }

    private companion object {
        const val MAX_LEVELS = 6
        const val SMALLEST_LEVEL = 16

        /** The size frames are compared at: fine enough, and quick against the merge itself. */
        const val ALIGN_LONG_SIDE = 960

        /** More than this between neighbouring frames is a failed fit, not a lens breathing. */
        const val MOST_SIZE_CHANGE = 0.04f
        const val MOST_SHIFT = 0.04f

        /** Moving a frame by less than this at the corners would only soften it. */
        const val STILL_PIXELS = 0.25f
    }
}

/**
 * The focus distances of a stack, in diopters, nearest first. Evenly spaced in diopters, because the
 * depth a frame keeps sharp is close to constant in them: equal steps overlap equally.
 *
 * @param near the distance focus is set to.
 * @param reach how many times further away the stack ends, or 0 for as far as the lens goes.
 * @return nothing if focus is already so far away that there is no depth left to cover.
 */
fun stackDistances(near: Float, frames: Int, reach: Int): List<Float> {
    if (near < NEAREST_WORTH_STACKING || frames < 2) return emptyList()
    val far = if (reach == 0) 0f else near / reach
    return List(frames) { near + (far - near) * it / (frames - 1) }
}

/** Twenty metres: at a phone's aperture, everything past that is already sharp. */
private const val NEAREST_WORTH_STACKING = 0.05f
