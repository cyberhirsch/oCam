package com.ocam.lut

import java.io.BufferedReader
import java.io.InputStream

/**
 * A 3D colour lookup table read from a .cube file - the format film simulations are published in.
 *
 * The file is a cube of output colours sampled on a regular grid of input colours. Grading it is
 * a lookup with interpolation between the eight surrounding entries, which is exactly what a GPU
 * does for free when the cube is uploaded as a 3D texture with linear filtering, so nothing here
 * interpolates: this only reads the file and hands over the numbers in the order the texture
 * wants them.
 */
class CubeLut(
    val name: String,
    /** Entries per axis. 33 is the usual size; 17 and 65 are common too. */
    val size: Int,
    /** size^3 triples, red varying fastest, as the file and GL_TEXTURE_3D both order them. */
    val values: FloatArray,
) {
    companion object {
        /** Beyond this a cube is almost certainly not a lookup table, and would eat the heap. */
        private const val MAX_SIZE = 128

        /**
         * @throws IllegalArgumentException when the file is not a 3D cube this can use. The
         *   message is shown to the owner, so it says what is wrong with their file.
         */
        fun parse(name: String, stream: InputStream): CubeLut {
            var size = 0
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var written = 0

            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEachLine
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0].uppercase()) {
                        "TITLE" -> Unit
                        "LUT_1D_SIZE" ->
                            throw IllegalArgumentException("$name is a 1D curve, not a 3D cube")
                        "LUT_3D_SIZE" -> {
                            size = parts.getOrNull(1)?.toIntOrNull()
                                ?: throw IllegalArgumentException("$name has no cube size")
                            require(size in 2..MAX_SIZE) { "$name has a cube size of $size" }
                            values = FloatArray(size * size * size * 3)
                        }
                        "DOMAIN_MIN" -> domainMin = parts.drop(1).toFloats(domainMin)
                        "DOMAIN_MAX" -> domainMax = parts.drop(1).toFloats(domainMax)
                        else -> {
                            val target = values
                                ?: throw IllegalArgumentException("$name has values before its size")
                            if (written + 3 > target.size) return@forEachLine
                            for (channel in 0 until 3) {
                                val raw = parts.getOrNull(channel)?.toFloatOrNull()
                                    ?: throw IllegalArgumentException("$name has a bad row: $line")
                                // Most files are already 0..1; a domain is rare but legal, and
                                // ignoring it would silently wash the grade out.
                                val span = domainMax[channel] - domainMin[channel]
                                target[written + channel] =
                                    if (span > 0f) (raw - domainMin[channel]) / span else raw
                            }
                            written += 3
                        }
                    }
                }
            }

            val target = values ?: throw IllegalArgumentException("$name is not a .cube file")
            require(written == target.size) {
                "$name stops after ${written / 3} of ${target.size / 3} entries"
            }
            return CubeLut(name, size, target)
        }

        private fun List<String>.toFloats(fallback: FloatArray): FloatArray {
            val parsed = mapNotNull { it.toFloatOrNull() }
            return if (parsed.size >= 3) floatArrayOf(parsed[0], parsed[1], parsed[2]) else fallback
        }
    }
}
