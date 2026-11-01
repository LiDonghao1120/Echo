package com.novcloud.echo.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What an image looks like once re-encoding stops mattering: transparent edges trimmed, laid over mid grey and
 * averaged down to a few cells. A library copy (trimmed, scaled, PNG or JPEG) and the file it was made from come out
 * the same; a different logo or poster of the same artist does not.
 *
 * Three checks must all pass: the trimmed aspect ratio, a gradient hash (dHash, 9×8) over the brightness, and the 8×8
 * brightness and coverage thumbnails, both on average and cell by cell. Gradients between cells of nearly equal
 * brightness are left out of the hash so flat areas cannot flip bits.
 */
class ArtFingerprint internal constructor(
    val aspect: Float,
    val gradient: Long,
    val sure: Long,
    val luma: IntArray,
    val alpha: IntArray,
) {
    fun encode(): String = buildString {
        append(VERSION).append(';')
        append("%.4f".format(java.util.Locale.ROOT, aspect)).append(';')
        append(java.lang.Long.toHexString(gradient)).append(';')
        append(java.lang.Long.toHexString(sure)).append(';')
        luma.forEach { append(HEX[it shr 4]).append(HEX[it and 15]) }
        append(';')
        alpha.forEach { append(HEX[it shr 4]).append(HEX[it and 15]) }
    }

    /** Distance between the gradient hashes, counting only gradients both images are sure of. */
    fun gradientDistance(o: ArtFingerprint): Int = java.lang.Long.bitCount((gradient xor o.gradient) and sure and o.sure)

    fun same(o: ArtFingerprint): Boolean {
        if (abs(aspect - o.aspect) / max(aspect, o.aspect) > ASPECT_TOLERANCE) return false
        if (gradientDistance(o) > GRADIENT_TOLERANCE) return false
        return close(luma, o.luma) && close(alpha, o.alpha)
    }

    override fun toString() = encode()

    companion object {
        const val VERSION = "f1"
        const val ASPECT_TOLERANCE = 0.08f
        const val GRADIENT_TOLERANCE = 5
        const val MEAN_TOLERANCE = 6
        const val CELL_TOLERANCE = 28
        private const val SURE = 2.0
        private const val THUMB = 8
        private val HEX = "0123456789abcdef".toCharArray()

        private fun close(a: IntArray, b: IntArray): Boolean {
            var sum = 0
            for (i in a.indices) {
                val d = abs(a[i] - b[i])
                if (d > CELL_TOLERANCE) return false
                sum += d
            }
            return sum <= MEAN_TOLERANCE * a.size
        }

        /** Fingerprint of [argb] (row-major ARGB, [w]×[h]); null for an empty or fully transparent image. */
        fun of(argb: IntArray, w: Int, h: Int): ArtFingerprint? {
            if (w <= 0 || h <= 0 || argb.size < w * h) return null
            var l = 0; var t = 0; var r = w - 1; var b = h - 1
            if (argb.any { (it ushr 24) != 0xFF }) {
                l = w; t = h; r = -1; b = -1
                for (y in 0 until h) for (x in 0 until w) {
                    if ((argb[y * w + x] ushr 24) > 12) {
                        if (x < l) l = x
                        if (x > r) r = x
                        if (y < t) t = y
                        if (y > b) b = y
                    }
                }
                if (r < l || b < t) return null
            }
            val cw = r - l + 1
            val ch = b - t + 1
            val lum = FloatArray(cw * ch)
            val cov = FloatArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val c = argb[(y + t) * w + x + l]
                val a = (c ushr 24) / 255.0
                val red = (c shr 16 and 0xff) / 255.0
                val green = (c shr 8 and 0xff) / 255.0
                val blue = (c and 0xff) / 255.0
                val lin = 0.2126 * red + 0.7152 * green + 0.0722 * blue
                lum[y * cw + x] = ((a * lin + (1 - a) * 0.5) * 255.0).toFloat()
                cov[y * cw + x] = (a * 255.0).toFloat()
            }
            val grid = average(lum, cw, ch, 9, 8)
            var gradient = 0L
            var sure = 0L
            for (y in 0 until 8) for (x in 0 until 8) {
                val bit = y * 8 + x
                val d = grid[y * 9 + x + 1] - grid[y * 9 + x]
                if (d > 0) gradient = gradient or (1L shl bit)
                if (abs(d) >= SURE) sure = sure or (1L shl bit)
            }
            val luma = average(lum, cw, ch, THUMB, THUMB).map { it.toInt().coerceIn(0, 255) }.toIntArray()
            val alpha = average(cov, cw, ch, THUMB, THUMB).map { it.toInt().coerceIn(0, 255) }.toIntArray()
            return ArtFingerprint(cw.toFloat() / ch, gradient, sure, luma, alpha)
        }

        /** Box average of [v] ([w]×[h]) into [cols]×[rows] cells; cells smaller than a pixel take the pixel under them. */
        internal fun average(v: FloatArray, w: Int, h: Int, cols: Int, rows: Int): DoubleArray {
            val out = DoubleArray(cols * rows)
            for (gy in 0 until rows) {
                val y0 = gy * h / rows
                val y1 = max(y0 + 1, min(h, (gy + 1) * h / rows))
                for (gx in 0 until cols) {
                    val x0 = gx * w / cols
                    val x1 = max(x0 + 1, min(w, (gx + 1) * w / cols))
                    var s = 0.0
                    for (y in y0 until y1) for (x in x0 until x1) s += v[y * w + x]
                    out[gy * cols + gx] = s / ((y1 - y0) * (x1 - x0))
                }
            }
            return out
        }

        fun parse(s: String?): ArtFingerprint? = runCatching {
            val p = s?.split(';') ?: return null
            if (p.size != 6 || p[0] != VERSION) return null
            fun bytes(hex: String) = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            val luma = bytes(p[4])
            val alpha = bytes(p[5])
            if (luma.size != THUMB * THUMB || alpha.size != THUMB * THUMB) return null
            ArtFingerprint(p[1].toFloat(), java.lang.Long.parseUnsignedLong(p[2], 16), java.lang.Long.parseUnsignedLong(p[3], 16), luma, alpha)
        }.getOrNull()
    }
}
