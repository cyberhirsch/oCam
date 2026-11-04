package com.ocam.sequence

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** One exposure of a bracket, in NV21's layout, and how much light it let in against the plan. */
class BracketFrame(val luma: ByteArray, val chroma: ByteArray, val relativeExposure: Float)

/** How much brighter than the picture each point can be shown on a screen that has the range. */
class GainMapPlane(val width: Int, val height: Int, val pixels: ByteArray, val ratioMax: Float)

/**
 * Exposure fusion, after Mertens, Kautz and Van Reeth: each frame is taken where it is well exposed,
 * and the frames are blended through Laplacian pyramids so that no seam shows between them. It
 * works on what the camera already developed, so nothing has to be known about its tone curve, and
 * what comes out is an ordinary picture rather than something that still has to be tone mapped.
 *
 * Weights are worked out per two by two block, the resolution chroma already has. Every pyramid
 * lives only while its channel is being blended, which keeps the peak at about four times a frame's
 * luma in floats, plus the frames themselves.
 */
object ExposureFusion {

    private const val MAX_LEVELS = 8
    private const val SMALLEST_LEVEL = 8

    /** Where a frame is clipped too hard to say anything about the light. */
    private const val CLIPPED = 245

    /** Matches the gain map's offsets, which keep black from dividing by nothing. */
    const val GAIN_EPSILON = 1f / 64f

    /** A gain map this flat would change nothing a screen could show. */
    private const val SMALLEST_USEFUL_RATIO = 1.1f

    private val EXPOSED = FloatArray(4 * 255 + 1) { sum ->
        val level = sum / (4f * 255f) - 0.5f
        exp(-level * level / (2f * 0.2f * 0.2f))
    }

    private val LINEAR = FloatArray(256) { value ->
        val level = value / 255f
        if (level <= 0.04045f) level / 12.92f else ((level + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Blend [frames] into [out], which is NV21. */
    fun fuse(frames: List<BracketFrame>, width: Int, height: Int, out: ByteArray) {
        require(frames.isNotEmpty()) { "Nothing to fuse" }
        require(width % 2 == 0 && height % 2 == 0) { "Chroma is subsampled: ${width}x$height" }
        val blockWidth = width / 2
        val blockHeight = height / 2
        val weights = weights(frames, width, blockWidth, blockHeight)
        val levels = levelCount(blockWidth, blockHeight)

        val luma = blend(frames.size, weights, blockWidth, blockHeight, levels + 1, halvedFirst = true) { frame ->
            val plane = Plane(width, height)
            val source = frames[frame].luma
            for (i in source.indices) plane.data[i] = (source[i].toInt() and 0xFF).toFloat()
            plane
        }
        for (i in luma.data.indices) out[i] = luma.data[i].toByteLevel()

        val chromaStart = width * height
        for (offset in 0..1) {
            val chroma = blend(frames.size, weights, blockWidth, blockHeight, levels, halvedFirst = false) { frame ->
                val plane = Plane(blockWidth, blockHeight)
                val source = frames[frame].chroma
                for (row in 0 until blockHeight) {
                    val base = row * width + offset
                    val target = row * blockWidth
                    for (column in 0 until blockWidth) {
                        plane.data[target + column] =
                            (source[base + column * 2].toInt() and 0xFF).toFloat()
                    }
                }
                plane
            }
            for (row in 0 until blockHeight) {
                val base = chromaStart + row * width + offset
                val source = row * blockWidth
                for (column in 0 until blockWidth) {
                    out[base + column * 2] = chroma.data[source + column].toByteLevel()
                }
            }
        }
    }

    /**
     * What a screen with more range than paper could add to the fused picture [fused]: the light
     * each frame measured, against what the picture shows. The frames are taken to be in sRGB, which
     * is only near what a camera's curve really is, so this is an estimate of the light rather than
     * a measurement. A picture is only ever brightened; shadows stay as they were fused.
     *
     * @return null where nothing in the frame would be brighter.
     */
    fun gainMap(
        frames: List<BracketFrame>,
        fused: ByteArray,
        width: Int,
        height: Int,
        limit: Float = 8f,
    ): GainMapPlane? {
        val mapWidth = max(1, width / 4)
        val mapHeight = max(1, height / 4)
        val gains = FloatArray(mapWidth * mapHeight)
        val darkest = frames.minByOrNull { it.relativeExposure } ?: return null
        inBands(mapHeight) { from, to ->
            for (row in from until to) {
                for (column in 0 until mapWidth) {
                    var light = 0f
                    var weight = 0f
                    for (frame in frames) {
                        val level = blockLevel(frame.luma, width, column, row)
                        if (level >= CLIPPED) continue
                        val share = (min(level, CLIPPED - level) + 1).toFloat()
                        light += share * LINEAR[level] / frame.relativeExposure
                        weight += share
                    }
                    // Clipped in every frame: the darkest says the least that can be true of it.
                    val seen = if (weight > 0f) light / weight
                    else LINEAR[blockLevel(darkest.luma, width, column, row)] / darkest.relativeExposure
                    val shown = LINEAR[blockLevel(fused, width, column, row)]
                    gains[row * mapWidth + column] =
                        ((seen + GAIN_EPSILON) / (shown + GAIN_EPSILON)).coerceIn(1f, limit)
                }
            }
        }
        val ratioMax = gains.maxOrNull() ?: return null
        if (ratioMax < SMALLEST_USEFUL_RATIO) return null
        val scale = 255f / ln(ratioMax)
        val pixels = ByteArray(gains.size) { (ln(gains[it]) * scale).roundToInt().toByte() }
        return GainMapPlane(mapWidth, mapHeight, pixels, ratioMax)
    }

    private fun blockLevel(luma: ByteArray, width: Int, column: Int, row: Int): Int {
        var sum = 0
        for (down in 0 until 4) {
            val base = (row * 4 + down) * width + column * 4
            for (across in 0 until 4) sum += luma[base + across].toInt() and 0xFF
        }
        return (sum + 8) / 16
    }

    private fun Float.toByteLevel(): Byte = roundToInt().coerceIn(0, 255).toByte()

    private fun levelCount(width: Int, height: Int): Int {
        var levels = 1
        var side = min(width, height)
        while (levels < MAX_LEVELS && side >= SMALLEST_LEVEL * 2) {
            side = (side + 1) / 2
            levels++
        }
        return levels
    }

    /**
     * How much each frame should count at each block, summing to one. Sixteen bits rather than
     * floats, because there is one of these per frame for as long as the blend runs.
     */
    private fun weights(
        frames: List<BracketFrame>,
        width: Int,
        blockWidth: Int,
        blockHeight: Int,
    ): List<ShortArray> {
        val result = List(frames.size) { ShortArray(blockWidth * blockHeight) }
        inBands(blockHeight) { from, to ->
            val here = FloatArray(frames.size)
            for (row in from until to) {
                for (column in 0 until blockWidth) {
                    var total = 0f
                    for (index in frames.indices) {
                        here[index] = weight(frames[index], width, blockWidth, blockHeight, column, row)
                        total += here[index]
                    }
                    val target = row * blockWidth + column
                    for (index in frames.indices) {
                        result[index][target] = (here[index] / total * 65535f).roundToInt().toShort()
                    }
                }
            }
        }
        return result
    }

    /** Well exposed, and among that, with detail and colour worth keeping. */
    private fun weight(
        frame: BracketFrame,
        width: Int,
        blockWidth: Int,
        blockHeight: Int,
        column: Int,
        row: Int,
    ): Float {
        val center = blockSum(frame.luma, width, column, row)
        val left = blockSum(frame.luma, width, max(column - 1, 0), row)
        val right = blockSum(frame.luma, width, min(column + 1, blockWidth - 1), row)
        val up = blockSum(frame.luma, width, column, max(row - 1, 0))
        val down = blockSum(frame.luma, width, column, min(row + 1, blockHeight - 1))
        val contrast = abs(4 * center - left - right - up - down) / (4f * 255f)

        val chroma = row * width + column * 2
        val v = (frame.chroma[chroma].toInt() and 0xFF) - 128
        val u = (frame.chroma[chroma + 1].toInt() and 0xFF) - 128
        val colour = (abs(u) + abs(v)) / 256f

        return EXPOSED[center] * (0.05f + contrast) * (0.1f + colour)
    }

    private fun blockSum(luma: ByteArray, width: Int, column: Int, row: Int): Int {
        val top = row * 2 * width + column * 2
        val bottom = top + width
        return (luma[top].toInt() and 0xFF) + (luma[top + 1].toInt() and 0xFF) +
            (luma[bottom].toInt() and 0xFF) + (luma[bottom + 1].toInt() and 0xFF)
    }

    internal class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height))

    /**
     * One channel through the pyramids.
     *
     * @param halvedFirst the channel is twice the size of the weights, as luma is: its finest level
     *   borrows the weight of the block it sits in, and its later levels line up with theirs.
     */
    private inline fun blend(
        frameCount: Int,
        weights: List<ShortArray>,
        weightWidth: Int,
        weightHeight: Int,
        levels: Int,
        halvedFirst: Boolean,
        source: (Int) -> Plane,
    ): Plane {
        val pyramid = ArrayList<Plane>(levels)
        for (index in 0 until frameCount) {
            var level = source(index)
            var weight = Plane(weightWidth, weightHeight).also { plane ->
                val shares = weights[index]
                for (i in shares.indices) plane.data[i] = (shares[i].toInt() and 0xFFFF) / 65535f
            }
            for (depth in 0 until levels) {
                if (pyramid.size <= depth) pyramid += Plane(level.width, level.height)
                val halved = halvedFirst && depth == 0
                if (depth == levels - 1) {
                    addWeighted(pyramid[depth], level, weight)
                } else {
                    val next = down(level)
                    addDetail(pyramid[depth], level, next, weight, halved)
                    level = next
                    if (!halved) weight = down(weight)
                }
            }
        }
        for (depth in levels - 2 downTo 0) addUpsampled(pyramid[depth], pyramid[depth + 1])
        return pyramid[0]
    }

    /** Burt and Adelson's five taps, kept to every other sample. */
    internal fun down(source: Plane): Plane {
        val width = source.width
        val height = source.height
        val data = source.data
        val out = Plane((width + 1) / 2, (height + 1) / 2)
        inBands(out.height) { from, to ->
            val line = FloatArray(width)
            for (row in from until to) {
                val middle = row * 2
                val r0 = max(middle - 2, 0) * width
                val r1 = max(middle - 1, 0) * width
                val r2 = middle * width
                val r3 = min(middle + 1, height - 1) * width
                val r4 = min(middle + 2, height - 1) * width
                for (x in 0 until width) {
                    line[x] = (data[r0 + x] + 4f * data[r1 + x] + 6f * data[r2 + x] +
                        4f * data[r3 + x] + data[r4 + x]) / 16f
                }
                var target = row * out.width
                for (column in 0 until out.width) {
                    val x = column * 2
                    out.data[target++] = (line[max(x - 2, 0)] + 4f * line[max(x - 1, 0)] + 6f * line[x] +
                        4f * line[min(x + 1, width - 1)] + line[min(x + 2, width - 1)]) / 16f
                }
            }
        }
        return out
    }

    private fun addWeighted(target: Plane, level: Plane, weight: Plane) {
        for (i in target.data.indices) target.data[i] += weight.data[i] * level.data[i]
    }

    /** The detail this level has and the next does not, in the share this frame was given. */
    private fun addDetail(target: Plane, level: Plane, next: Plane, weight: Plane, halved: Boolean) {
        val width = target.width
        val nextWidth = next.width
        inBands(target.height) { from, to ->
            for (row in from until to) {
                val y0 = row / 2
                val y1 = min(y0 + (row and 1), next.height - 1)
                val base = row * width
                val weightBase = if (halved) (row / 2) * weight.width else row * weight.width
                for (column in 0 until width) {
                    val x0 = column / 2
                    val x1 = min(x0 + (column and 1), nextWidth - 1)
                    val above = (next.data[y0 * nextWidth + x0] + next.data[y0 * nextWidth + x1] +
                        next.data[y1 * nextWidth + x0] + next.data[y1 * nextWidth + x1]) / 4f
                    val share = weight.data[weightBase + if (halved) column / 2 else column]
                    target.data[base + column] += share * (level.data[base + column] - above)
                }
            }
        }
    }

    internal fun addUpsampled(target: Plane, next: Plane) {
        val width = target.width
        val nextWidth = next.width
        inBands(target.height) { from, to ->
            for (row in from until to) {
                val y0 = row / 2
                val y1 = min(y0 + (row and 1), next.height - 1)
                val base = row * width
                for (column in 0 until width) {
                    val x0 = column / 2
                    val x1 = min(x0 + (column and 1), nextWidth - 1)
                    target.data[base + column] += (next.data[y0 * nextWidth + x0] +
                        next.data[y0 * nextWidth + x1] + next.data[y1 * nextWidth + x0] +
                        next.data[y1 * nextWidth + x1]) / 4f
                }
            }
        }
    }
}
