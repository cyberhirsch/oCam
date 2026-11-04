package com.ocam.sequence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A turn, a change of size and a shift, about the middle of the picture:
 * x' = a x - b y + tx, y' = b x + a y + ty.
 */
internal class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {

    fun mapX(x: Float, y: Float) = a * x - b * y + tx

    fun mapY(x: Float, y: Float) = b * x + a * y + ty

    /** This, applied to what [first] gives. */
    fun after(first: Similarity) = Similarity(
        a * first.a - b * first.b,
        b * first.a + a * first.b,
        mapX(first.tx, first.ty),
        mapY(first.tx, first.ty),
    )

    fun inverse(): Similarity {
        val size = a * a + b * b
        val ia = a / size
        val ib = -b / size
        return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
    }

    /** The same movement in a picture [factor] times the size. */
    fun forScale(factor: Float) = Similarity(a, b, tx * factor, ty * factor)

    companion object {
        val IDENTITY = Similarity(1f, 0f, 0f, 0f)
    }
}

/** Brightness as floats; anything negative means there is nothing at that point. */
internal class GrayImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {

    /** Half the size. A point with a gap under it is a gap, so a border cannot pull on the fit. */
    fun halved(): GrayImage {
        val out = GrayImage(width / 2, height / 2)
        for (row in 0 until out.height) {
            val top = row * 2 * width
            val bottom = top + width
            for (column in 0 until out.width) {
                val p0 = data[top + column * 2]
                val p1 = data[top + column * 2 + 1]
                val p2 = data[bottom + column * 2]
                val p3 = data[bottom + column * 2 + 1]
                out.data[row * out.width + column] =
                    if (p0 < 0f || p1 < 0f || p2 < 0f || p3 < 0f) -1f else (p0 + p1 + p2 + p3) / 4f
            }
        }
        return out
    }

    companion object {
        /** A plane of luma made [factor] times smaller, averaged rather than picked. */
        fun fromLuma(luma: ByteArray, lumaWidth: Int, lumaHeight: Int, factor: Int): GrayImage {
            val out = GrayImage(lumaWidth / factor, lumaHeight / factor)
            val area = (factor * factor).toFloat()
            for (row in 0 until out.height) {
                for (column in 0 until out.width) {
                    var sum = 0
                    for (down in 0 until factor) {
                        val base = (row * factor + down) * lumaWidth + column * factor
                        for (across in 0 until factor) sum += luma[base + across].toInt() and 0xFF
                    }
                    out.data[row * out.width + column] = sum / area
                }
            }
            return out
        }
    }
}

/**
 * @property transform carries a point in the fixed picture to the same point in the moving one.
 * @property reliable whether there was enough overlap and detail to believe it, and it fits better
 *   than where it started. Blue sky fits anything, and this is how that is noticed.
 */
internal class Aligned(val transform: Similarity, val reliable: Boolean)

/**
 * Lucas and Kanade's fit, from coarse to fine, in its inverse compositional form. It needs a start
 * within a few percent of the answer, which a motion sensor or a tripod gives it. There is no Android
 * in it, and it is small enough to run on a picture a few hundred pixels across in a fraction of a
 * second, which is all the precision a merge needs.
 */
internal object ImageAligner {

    private const val MAX_LEVELS = 5
    private const val SMALLEST_SIDE = 40
    private const val ITERATIONS = 15
    private const val SMALLEST_STEP = 0.01f

    /** A difference past this is something that moved, and stops counting for more. */
    private const val OUTLIER_LEVELS = 24f

    /** Least share of the picture that has to overlap for the fit to mean anything. */
    private const val LEAST_OVERLAP = 0.05f

    /** Least gradient, in brightness levels squared, in the direction with the least of it. */
    private const val LEAST_DETAIL = 2f

    fun align(fixed: GrayImage, moving: GrayImage, seed: Similarity, allowScale: Boolean): Aligned {
        val fixedLevels = arrayListOf(fixed)
        val movingLevels = arrayListOf(moving)
        while (fixedLevels.size < MAX_LEVELS &&
            min(min(fixedLevels.last().width, fixedLevels.last().height),
                min(movingLevels.last().width, movingLevels.last().height)) / 2 >= SMALLEST_SIDE
        ) {
            fixedLevels += fixedLevels.last().halved()
            movingLevels += movingLevels.last().halved()
        }

        val coarsest = fixedLevels.size - 1
        var current = seed.forScale(1f / (1 shl coarsest))
        for (level in coarsest downTo 0) {
            current = refine(fixedLevels[level], movingLevels[level], current, allowScale)
            if (level > 0) current = current.forScale(2f)
        }

        val before = Normal().also { it.measure(fixed, moving, seed) }
        val after = Normal().also { it.measure(fixed, moving, current) }
        val reliable = after.count >= LEAST_OVERLAP * fixed.width * fixed.height &&
            after.meanError <= before.meanError + 0.5f &&
            after.leastDetail >= LEAST_DETAIL
        return Aligned(current, reliable)
    }

    private fun refine(
        fixed: GrayImage,
        moving: GrayImage,
        start: Similarity,
        allowScale: Boolean,
    ): Similarity {
        var current = start
        val normal = Normal()
        for (iteration in 0 until ITERATIONS) {
            normal.measure(fixed, moving, current)
            if (normal.count < 16) break
            val step = normal.solve(allowScale) ?: break
            val spread = normal.spread
            val turn = step[1] / spread
            val delta = Similarity(
                if (allowScale) 1f + step[0] / spread else sqrt(max(0f, 1f - turn * turn)),
                turn,
                step[2],
                step[3],
            )
            current = current.after(delta.inverse())
            val corner = max(abs(step[0]), abs(step[1]))
            if (corner < SMALLEST_STEP && abs(step[2]) < SMALLEST_STEP && abs(step[3]) < SMALLEST_STEP) break
        }
        return current
    }

    /** One pass over the overlap: the normal equations of the next step, and how well this fits. */
    private class Normal {
        val h = FloatArray(16)
        val g = FloatArray(4)
        var count = 0
        var spread = 1f
        private var errorSum = 0f

        val meanError: Float get() = if (count == 0) Float.MAX_VALUE else errorSum / count

        /** The smaller eigenvalue of the shift's part of the equations, per pixel. */
        val leastDetail: Float
            get() {
                if (count == 0) return 0f
                val xx = h[2 * 4 + 2] / count
                val yy = h[3 * 4 + 3] / count
                val xy = h[2 * 4 + 3] / count
                val half = (xx + yy) / 2f
                val gap = (xx - yy) / 2f
                return half - sqrt(gap * gap + xy * xy)
            }

        fun measure(fixed: GrayImage, moving: GrayImage, transform: Similarity) {
            h.fill(0f)
            g.fill(0f)
            count = 0
            errorSum = 0f
            val width = fixed.width
            val height = fixed.height
            val cx = (width - 1) / 2f
            val cy = (height - 1) / 2f
            val mx = (moving.width - 1) / 2f
            val my = (moving.height - 1) / 2f
            val maxX = moving.width - 1.001f
            val maxY = moving.height - 1.001f
            val data = fixed.data
            val other = moving.data
            spread = max(cx, cy)
            val j = FloatArray(4)
            for (row in 1 until height - 1) {
                val y = row - cy
                for (column in 1 until width - 1) {
                    val i = row * width + column
                    val value = data[i]
                    val left = data[i - 1]
                    val right = data[i + 1]
                    val up = data[i - width]
                    val down = data[i + width]
                    if (value < 0f || left < 0f || right < 0f || up < 0f || down < 0f) continue
                    val x = column - cx
                    val sx = transform.mapX(x, y) + mx
                    val sy = transform.mapY(x, y) + my
                    if (sx < 0f || sy < 0f || sx > maxX || sy > maxY) continue
                    val x0 = sx.toInt()
                    val y0 = sy.toInt()
                    val base = y0 * moving.width + x0
                    val p0 = other[base]
                    val p1 = other[base + 1]
                    val p2 = other[base + moving.width]
                    val p3 = other[base + moving.width + 1]
                    if (p0 < 0f || p1 < 0f || p2 < 0f || p3 < 0f) continue
                    val fx = sx - x0
                    val fy = sy - y0
                    val top = p0 + (p1 - p0) * fx
                    val bottom = p2 + (p3 - p2) * fx
                    val difference = top + (bottom - top) * fy - value

                    val size = abs(difference)
                    val weight = if (size > OUTLIER_LEVELS) OUTLIER_LEVELS / size else 1f
                    val gx = (right - left) / 2f
                    val gy = (down - up) / 2f
                    j[0] = (x * gx + y * gy) / spread
                    j[1] = (x * gy - y * gx) / spread
                    j[2] = gx
                    j[3] = gy
                    for (p in 0 until 4) {
                        g[p] += weight * j[p] * difference
                        for (q in p until 4) h[p * 4 + q] += weight * j[p] * j[q]
                    }
                    count++
                    errorSum += size
                }
            }
        }

        /** Gaussian elimination over the parameters in play; null if they cannot be told apart. */
        fun solve(allowScale: Boolean): FloatArray? {
            val first = if (allowScale) 0 else 1
            val size = 4 - first
            val m = Array(size) { p ->
                DoubleArray(size + 1) { q ->
                    if (q == size) g[p + first].toDouble()
                    else {
                        val low = min(p, q) + first
                        val high = max(p, q) + first
                        h[low * 4 + high].toDouble()
                    }
                }
            }
            for (pivot in 0 until size) {
                val best = (pivot until size).maxByOrNull { abs(m[it][pivot]) } ?: return null
                if (abs(m[best][pivot]) < 1e-6) return null
                val swap = m[pivot]
                m[pivot] = m[best]
                m[best] = swap
                for (row in pivot + 1 until size) {
                    val share = m[row][pivot] / m[pivot][pivot]
                    for (column in pivot..size) m[row][column] -= share * m[pivot][column]
                }
            }
            val answer = FloatArray(4)
            for (row in size - 1 downTo 0) {
                var sum = m[row][size]
                for (column in row + 1 until size) sum -= m[row][column] * answer[column + first]
                answer[row + first] = (sum / m[row][row]).toFloat()
            }
            return answer
        }
    }
}

/**
 * [y] and [vu] moved so that they line up with the picture [transform] starts from, into [outY] and
 * [outVu]. A point that falls off the frame takes the nearest edge: it adds no detail of its own.
 */
internal fun warpNv21(
    y: ByteArray,
    vu: ByteArray,
    width: Int,
    height: Int,
    transform: Similarity,
    outY: ByteArray,
    outVu: ByteArray,
) {
    val cx = (width - 1) / 2f
    val cy = (height - 1) / 2f
    inBands(height) { from, to ->
        for (row in from until to) {
            val dy = row - cy
            for (column in 0 until width) {
                val dx = column - cx
                val sx = (transform.mapX(dx, dy) + cx).coerceIn(0f, width - 1.001f)
                val sy = (transform.mapY(dx, dy) + cy).coerceIn(0f, height - 1.001f)
                outY[row * width + column] = bilinear(y, width, 1, 0, sx, sy).toByte()
            }
        }
    }
    val blockWidth = width / 2
    val blockHeight = height / 2
    inBands(blockHeight) { from, to ->
        for (row in from until to) {
            val dy = row * 2 + 0.5f - cy
            for (column in 0 until blockWidth) {
                val dx = column * 2 + 0.5f - cx
                val sx = ((transform.mapX(dx, dy) + cx - 0.5f) / 2f).coerceIn(0f, blockWidth - 1.001f)
                val sy = ((transform.mapY(dx, dy) + cy - 0.5f) / 2f).coerceIn(0f, blockHeight - 1.001f)
                val target = row * width + column * 2
                outVu[target] = bilinear(vu, width, 2, 0, sx, sy).toByte()
                outVu[target + 1] = bilinear(vu, width, 2, 1, sx, sy).toByte()
            }
        }
    }
}

/**
 * One plane out of a byte array, which may be every [step]th byte of each row starting at [offset].
 * [x] and [y] must leave room for the next sample along in both directions.
 */
internal fun bilinear(data: ByteArray, stride: Int, step: Int, offset: Int, x: Float, y: Float): Int {
    val x0 = x.toInt()
    val y0 = y.toInt()
    val base = y0 * stride + x0 * step + offset
    val p0 = data[base].toInt() and 0xFF
    val p1 = data[base + step].toInt() and 0xFF
    val p2 = data[base + stride].toInt() and 0xFF
    val p3 = data[base + stride + step].toInt() and 0xFF
    val fx = x - x0
    val fy = y - y0
    val top = p0 + (p1 - p0) * fx
    val bottom = p2 + (p3 - p2) * fx
    return (top + (bottom - top) * fy + 0.5f).toInt()
}
