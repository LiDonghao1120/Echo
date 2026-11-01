package com.novcloud.echo.data

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generated test wordmarks (no real artwork): bars, a ring and a block with anti-aliased edges, drawn as plain ARGB.
 * [matte] lays the faint full-canvas matte some downloaded logos carry; [opaque] flattens letters onto a solid
 * background the way a JPEG logo arrives.
 */
object SyntheticLogos {
    const val W = 480
    const val H = 160

    /** Where the letters are: left, top, right (exclusive), bottom (exclusive). */
    val box = intArrayOf(40, 30, 431, 130)

    fun coverage(w: Int = W, h: Int = H): FloatArray {
        val out = FloatArray(w * h)
        fun inside(x: Double, y: Double): Boolean {
            val gx = x - 40
            val gy = y - 30
            if (gy < 0 || gy > 100) return false
            if (gx in 0.0..18.4) return true
            if (gx in 40.0..58.6 && gy > 14) return true
            val dx = gx - 125
            val dy = gy - 50
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            if (r in 32.0..50.0) return true
            if (gx in 330.0..390.6 && gy in 8.0..92.0) return true
            return false
        }
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (sy in 0 until 4) for (sx in 0 until 4) if (inside(x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)) n++
            out[y * w + x] = n / 16f
        }
        return out
    }

    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun matte(color: Int, alpha: Int, matteRgb: Int = 0, noise: Int = 2, cov: FloatArray = coverage()): IntArray {
        val rnd = Random(5)
        return IntArray(cov.size) { i ->
            val am = if (alpha == 0) 0.0 else (alpha + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0).coerceIn(1, 255) / 255.0
            val ag = cov[i].toDouble()
            val ao = ag + am * (1 - ag)
            if (ao <= 0.0) return@IntArray 0
            fun ch(s: Int) = (((color shr s and 0xff) * ag + (matteRgb shr s and 0xff) * am * (1 - ag)) / ao).roundToInt()
            argb((ao * 255).roundToInt(), ch(16), ch(8), ch(0))
        }
    }

    fun opaque(fg: Int, bg: Int, noise: Int = 4, cov: FloatArray = coverage()): IntArray {
        val rnd = Random(9)
        return IntArray(cov.size) { i ->
            fun ch(s: Int) = ((fg shr s and 0xff) * cov[i] + (bg shr s and 0xff) * (1 - cov[i]) + rnd.nextInt(-noise, noise + 1)).roundToInt().coerceIn(0, 255)
            argb(255, ch(16), ch(8), ch(0))
        }
    }
}
