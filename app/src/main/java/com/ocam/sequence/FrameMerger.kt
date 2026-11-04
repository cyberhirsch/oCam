package com.ocam.sequence

import java.nio.ByteBuffer
import java.util.stream.IntStream
import kotlin.math.max

/**
 * Builds one picture out of any number of frames, in memory that does not grow with their number:
 * a ten minute light painting costs what one frame costs.
 *
 * Works on the camera's own luma and chroma, 8 bits a sample with chroma at half resolution, in
 * the layout of NV21: a plane of luma, then rows of alternating V and U. Nothing here touches
 * Android, so all of it can be tested without a phone.
 */
abstract class FrameMerger protected constructor(val width: Int, val height: Int) {

    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Chroma is subsampled, so a frame has to be even: ${width}x$height"
        }
    }

    /** How many frames have gone in. */
    var frames = 0
        private set

    /** Add one frame: [y] is width x height, [vu] is width x height / 2. */
    fun add(y: ByteArray, vu: ByteArray) {
        if (frames == 0) first(y, vu) else inBands(height) { from, to -> merge(y, vu, from, to) }
        frames++
    }

    protected abstract fun first(y: ByteArray, vu: ByteArray)

    /** Merge rows [fromRow] until [toRow], which are always whole pairs of rows. */
    protected abstract fun merge(y: ByteArray, vu: ByteArray, fromRow: Int, toRow: Int)

    protected abstract fun luma(index: Int): Int

    protected abstract fun chroma(index: Int): Int

    /** The picture so far, as NV21. */
    fun writeNv21(out: ByteArray) = writeScaledNv21(1, width, height, out)

    /**
     * The picture so far, made [factor] times smaller. Luma is averaged rather than picked, or a
     * thin stroke of light would flicker in and out of a smaller picture. The size asked for may
     * be less than a whole division, which crops what is left over.
     */
    fun writeScaledNv21(factor: Int, outWidth: Int, outHeight: Int, out: ByteArray) {
        require(outWidth * factor <= width && outHeight * factor <= height) {
            "${outWidth}x$outHeight x$factor does not fit in ${width}x$height"
        }
        val area = factor * factor
        inBands(outHeight) { from, to ->
            for (row in from until to) {
                var target = row * outWidth
                for (column in 0 until outWidth) {
                    out[target++] = (boxLuma(column, row, factor) / area).toByte()
                }
            }
        }
        val chromaStart = outWidth * outHeight
        for (row in 0 until outHeight / 2) {
            val source = row * factor * width
            val target = chromaStart + row * outWidth
            for (column in 0 until outWidth / 2) {
                val from = source + column * factor * 2
                out[target + column * 2] = chroma(from).toByte()
                out[target + column * 2 + 1] = chroma(from + 1).toByte()
            }
        }
    }

    /** The picture so far as ARGB pixels, no longer than [longSide], for the screen. */
    fun thumbnail(longSide: Int): Thumbnail {
        val factor = max(1, (max(width, height) + longSide - 1) / longSide)
        val outWidth = width / factor
        val outHeight = height / factor
        val pixels = IntArray(outWidth * outHeight)
        val area = factor * factor
        inBands(outHeight) { from, to ->
            for (row in from until to) {
                val chromaRow = (row * factor / 2) * width
                for (column in 0 until outWidth) {
                    val chromaColumn = (column * factor / 2) * 2
                    pixels[row * outWidth + column] = yuvToArgb(
                        boxLuma(column, row, factor) / area,
                        chroma(chromaRow + chromaColumn + 1),
                        chroma(chromaRow + chromaColumn),
                    )
                }
            }
        }
        return Thumbnail(outWidth, outHeight, pixels)
    }

    private fun boxLuma(column: Int, row: Int, factor: Int): Int {
        var sum = 0
        for (down in 0 until factor) {
            val base = (row * factor + down) * width + column * factor
            for (across in 0 until factor) sum += luma(base + across)
        }
        return sum
    }

    companion object {
        fun create(width: Int, height: Int, mode: SequenceMode): FrameMerger =
            when (mode.blend) {
                Blend.LIGHTEN -> LightenMerger(width, height, mode.gate)
                Blend.AVERAGE -> AverageMerger(width, height)
                null -> throw IllegalArgumentException("$mode does not merge anything")
            }
    }
}

class Thumbnail(val width: Int, val height: Int, val argb: IntArray)

/**
 * The brightest light each point has seen. Noise only ever pushes a maximum upwards, so a pixel
 * has to rise by more than [gate] to count: without that, the shadows of a long run climb until
 * they are grey.
 *
 * Colour goes with the light. A two by two block takes its chroma from the frame in which that
 * block was brightest, so a red torch leaves a red stroke rather than one that changes hue
 * wherever a neighbouring pixel happened to win.
 */
private class LightenMerger(width: Int, height: Int, private val gate: Int) :
    FrameMerger(width, height) {

    private val lumaKept = ByteArray(width * height)
    private val chromaKept = ByteArray(width * height / 2)
    private val blockLuma = ShortArray(width / 2 * (height / 2))

    override fun first(y: ByteArray, vu: ByteArray) {
        y.copyInto(lumaKept)
        vu.copyInto(chromaKept)
        var block = 0
        for (row in 0 until height step 2) {
            val base = row * width
            for (column in 0 until width step 2) {
                val here = base + column
                blockLuma[block++] = (
                    (y[here].toInt() and 0xFF) + (y[here + 1].toInt() and 0xFF) +
                        (y[here + width].toInt() and 0xFF) + (y[here + width + 1].toInt() and 0xFF)
                    ).toShort()
            }
        }
    }

    override fun merge(y: ByteArray, vu: ByteArray, fromRow: Int, toRow: Int) {
        val blockGate = gate * 4
        var row = fromRow
        while (row < toRow) {
            val base = row * width
            val chromaBase = row / 2 * width
            val blockBase = row / 2 * (width / 2)
            var column = 0
            while (column < width) {
                val a = base + column
                val b = a + 1
                val c = a + width
                val d = c + 1
                val p0 = y[a].toInt() and 0xFF
                val p1 = y[b].toInt() and 0xFF
                val p2 = y[c].toInt() and 0xFF
                val p3 = y[d].toInt() and 0xFF
                if (p0 > (lumaKept[a].toInt() and 0xFF) + gate) lumaKept[a] = p0.toByte()
                if (p1 > (lumaKept[b].toInt() and 0xFF) + gate) lumaKept[b] = p1.toByte()
                if (p2 > (lumaKept[c].toInt() and 0xFF) + gate) lumaKept[c] = p2.toByte()
                if (p3 > (lumaKept[d].toInt() and 0xFF) + gate) lumaKept[d] = p3.toByte()

                val block = blockBase + column / 2
                val sum = p0 + p1 + p2 + p3
                if (sum > blockLuma[block] + blockGate) {
                    blockLuma[block] = sum.toShort()
                    val chroma = chromaBase + column
                    chromaKept[chroma] = vu[chroma]
                    chromaKept[chroma + 1] = vu[chroma + 1]
                }
                column += 2
            }
            row += 2
        }
    }

    override fun luma(index: Int): Int = lumaKept[index].toInt() and 0xFF

    override fun chroma(index: Int): Int = chromaKept[index].toInt() and 0xFF
}

/**
 * What the sensor would have gathered behind an ND filter, as a running sum. An int holds eight
 * million frames of eight bits, which at thirty frames a second is three days.
 */
private class AverageMerger(width: Int, height: Int) : FrameMerger(width, height) {

    private val lumaSum = IntArray(width * height)
    private val chromaSum = IntArray(width * height / 2)

    override fun first(y: ByteArray, vu: ByteArray) {
        for (i in y.indices) lumaSum[i] = y[i].toInt() and 0xFF
        for (i in vu.indices) chromaSum[i] = vu[i].toInt() and 0xFF
    }

    override fun merge(y: ByteArray, vu: ByteArray, fromRow: Int, toRow: Int) {
        for (i in fromRow * width until toRow * width) lumaSum[i] += y[i].toInt() and 0xFF
        for (i in fromRow / 2 * width until toRow / 2 * width) {
            chromaSum[i] += vu[i].toInt() and 0xFF
        }
    }

    override fun luma(index: Int): Int = (lumaSum[index] + frames / 2) / frames

    override fun chroma(index: Int): Int = (chromaSum[index] + frames / 2) / frames
}

/**
 * Split [rows] between the processors, on whole pairs of rows. The camera does not wait: a merge
 * that takes longer than a frame shows up as a gap in the light.
 */
internal inline fun inBands(rows: Int, crossinline work: (from: Int, to: Int) -> Unit) {
    val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    IntStream.range(0, bands).parallel().forEach { band ->
        val from = (rows.toLong() * band / bands).toInt() and 1.inv()
        val to = if (band == bands - 1) rows else (rows.toLong() * (band + 1) / bands).toInt() and 1.inv()
        if (to > from) work(from, to)
    }
}

/** JFIF's full range conversion, which is what a camera's YUV_420_888 is in. */
fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val cb = u - 128
    val cr = v - 128
    val red = (y + ((91881 * cr + 32768) shr 16)).coerceIn(0, 255)
    val green = (y - ((22554 * cb + 46802 * cr + 32768) shr 16)).coerceIn(0, 255)
    val blue = (y + ((116130 * cb + 32768) shr 16)).coerceIn(0, 255)
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/** One plane of luma, whatever padding the camera put between its rows. */
fun readLuma(plane: ByteBuffer, rowStride: Int, width: Int, height: Int, out: ByteArray) {
    for (row in 0 until height) {
        plane.position(row * rowStride)
        plane.get(out, row * width, width)
    }
}

/**
 * Two planes of chroma into NV21's alternating rows. The camera may hand them over interleaved or
 * apart, which [pixelStride] says, and the last row of a plane may stop short of its stride, so
 * only the bytes that hold samples are read.
 *
 * @param scratchV a row's worth of bytes to read through, [width] long, as is [scratchU].
 */
fun readChroma(
    v: ByteBuffer,
    u: ByteBuffer,
    pixelStride: Int,
    rowStride: Int,
    width: Int,
    height: Int,
    scratchV: ByteArray,
    scratchU: ByteArray,
    out: ByteArray,
) {
    val samples = width / 2
    val span = (samples - 1) * pixelStride + 1
    var target = 0
    for (row in 0 until height / 2) {
        v.position(row * rowStride)
        v.get(scratchV, 0, span)
        u.position(row * rowStride)
        u.get(scratchU, 0, span)
        for (sample in 0 until samples) {
            out[target++] = scratchV[sample * pixelStride]
            out[target++] = scratchU[sample * pixelStride]
        }
    }
}
