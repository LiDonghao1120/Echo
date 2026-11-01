package com.novcloud.echo.data

import com.novcloud.echo.ui.components.LogoAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * [LogoCleanup] on synthetic wordmarks (bars and a ring drawn with anti-aliased edges, no real artwork): a faint matte
 * across the canvas, the same matte after the old >12 trim cut it down to the letters, opaque white and black
 * backgrounds, a clean logo, a soft glow, specks and a photo with a white frame. Also the alpha matrices the logos are
 * drawn through ([LogoAlpha]): the halo of an uncleaned matte logo covers nothing outside the letters.
 */
class LogoCleanupTest {

    private val w = 320
    private val h = 120

    /** Coverage 0..1 of the letters: two bars and a ring on the left, a block far right, 4×4 supersampled. */
    private fun glyphs(w: Int = this.w, h: Int = this.h, ox: Int = 20, oy: Int = 20): FloatArray {
        val out = FloatArray(w * h)
        fun inside(x: Double, y: Double): Boolean {
            val gx = x - ox
            val gy = y - oy
            if (gy < 0 || gy > 80) return false
            if (gx in 0.0..14.3) return true
            if (gx in 30.0..44.5 && gy > 10) return true
            val dx = gx - 95
            val dy = gy - 40
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            if (r in 26.0..40.0) return true
            if (gx in 230.0..270.7 && gy in 5.0..75.0) return true
            return false
        }
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (sy in 0 until 4) for (sx in 0 until 4) if (inside(x + (sx + 0.5) / 4, y + (sy + 0.5) / 4)) n++
            out[y * w + x] = n / 16f
        }
        return out
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun a(c: Int) = c ushr 24
    private fun r(c: Int) = c shr 16 and 0xff

    /** Letters of [color] over a matte of [matte] alpha (± [noise]) in [matteRgb], composited the way an editor does. */
    private fun logo(cov: FloatArray, color: Int, matte: Int, matteRgb: Int = 0, noise: Int = 0, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        return IntArray(cov.size) { i ->
            val am = if (matte == 0) 0.0 else ((matte + if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0).coerceIn(1, 255)) / 255.0
            val ag = cov[i].toDouble()
            val ao = ag + am * (1 - ag)
            if (ao <= 0.0) return@IntArray 0
            fun ch(s: Int) = (((color shr s and 0xff) * ag + (matteRgb shr s and 0xff) * am * (1 - ag)) / ao).roundToInt()
            argb((ao * 255).roundToInt(), ch(16), ch(8), ch(0))
        }
    }

    /** True for pixels more than [reach] px away from any letter coverage. */
    private fun far(cov: FloatArray, w: Int, h: Int, reach: Int): BooleanArray {
        val near = LogoCleanup.dilate(BooleanArray(cov.size) { cov[it] > 0f }, w, h, reach)
        return BooleanArray(cov.size) { !near[it] }
    }

    /** The old store-time trim: crop to alpha > 12. */
    private fun oldTrim(px: IntArray, w: Int, h: Int): Triple<IntArray, Int, Int> {
        var l = w; var t = h; var rr = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (a(px[y * w + x]) > 12) {
            l = minOf(l, x); rr = maxOf(rr, x); t = minOf(t, y); b = maxOf(b, y)
        }
        val cw = rr - l + 1
        val ch = b - t + 1
        return Triple(IntArray(cw * ch) { px[(it / cw + t) * w + it % cw + l] }, cw, ch)
    }

    private fun crop(v: FloatArray, w: Int, l: Int, t: Int, cw: Int, ch: Int) = FloatArray(cw * ch) { v[(it / cw + t) * w + it % cw + l] }

    private val glyphBox = intArrayOf(20, 20, 291, 100) // l, t, r(excl), b(excl) of the coverage

    @Test fun faintDarkMatteAcrossTheCanvasIsRemoved() {
        val cov = glyphs()
        val src = logo(cov, 0xFFFFFF, matte = 10, matteRgb = 0x000000, noise = 2)
        val res = LogoCleanup.clean(src, w, h)
        assertTrue("matte found", res.matte in 10..13)
        assertEquals("trimmed to the letters", glyphBox[2] - glyphBox[0], res.width)
        assertEquals(glyphBox[3] - glyphBox[1], res.height)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        val farMask = far(c, res.width, res.height, 3)
        for (i in res.argb.indices) {
            if (farMask[i]) assertEquals("pixel $i away from the letters is clear", 0, a(res.argb[i]))
            if (c[i] == 1f) {
                assertTrue("letter core stays opaque", a(res.argb[i]) >= 250)
                assertTrue("letter core stays white", r(res.argb[i]) >= 245)
            }
            if (c[i] == 0f) assertTrue("no matte left on the background", a(res.argb[i]) <= 8)
        }
        // Anti-aliased edges keep roughly their coverage.
        for (i in res.argb.indices) if (c[i] in 0.3f..0.7f) assertTrue(abs(a(res.argb[i]) / 255f - c[i]) < 0.08f)
    }

    @Test fun strongerWhiteMatteIsRemoved() {
        val cov = glyphs()
        val src = logo(cov, 0x202020, matte = 30, matteRgb = 0xFFFFFF, noise = 3)
        val res = LogoCleanup.clean(src, w, h)
        assertTrue(res.matte in 30..34)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        for (i in res.argb.indices) {
            if (c[i] == 0f) assertTrue(a(res.argb[i]) <= 10)
            if (c[i] == 1f) assertTrue("dark letter colour survives", r(res.argb[i]) <= 0x28)
        }
    }

    /** The case from the phone: a matte at or below 12 that the old trim cut down to the letters' box. */
    @Test fun matteLeftInsideTheOldTrimIsRemoved() {
        val cov = glyphs()
        val (px, tw, th) = oldTrim(logo(cov, 0xFFFFFF, matte = 9, matteRgb = 0x101010, noise = 2), w, h)
        assertEquals(271, tw)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], tw, th)
        val res = LogoCleanup.clean(px, tw, th)
        assertTrue(res.matte > 0)
        assertEquals(tw, res.width)
        for (i in res.argb.indices) if (c[i] == 0f) assertEquals(0, a(res.argb[i]))
    }

    /** A matte that stops short of the canvas edge, inside a transparent margin. */
    @Test fun matteFramedByATransparentMarginIsRemoved() {
        val cov = glyphs()
        val inner = logo(cov, 0xFFFFFF, matte = 14, matteRgb = 0x000000, noise = 2)
        val m = 30
        val pw = w + 2 * m
        val ph = h + 2 * m
        val px = IntArray(pw * ph)
        for (y in 0 until h) System.arraycopy(inner, y * w, px, (y + m) * pw + m, w)
        val res = LogoCleanup.clean(px, pw, ph)
        assertTrue(res.matte > 0)
        assertEquals(glyphBox[2] - glyphBox[0], res.width)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        for (i in res.argb.indices) if (c[i] == 0f) assertTrue(a(res.argb[i]) <= 8)
    }

    @Test fun cleanLogoIsLeftAsItIs() {
        val cov = glyphs()
        val src = logo(cov, 0xFFFFFF, matte = 0)
        val res = LogoCleanup.clean(src, w, h)
        assertEquals(0, res.matte)
        assertFalse(res.keyed)
        assertEquals(0, res.specks)
        assertEquals(glyphBox[2] - glyphBox[0], res.width) // transparent margin trimmed, nothing else
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        val cropped = LogoCleanup.trim(src, w, h)!!.first
        for (i in res.argb.indices) if (a(cropped[i]) > LogoCleanup.INVISIBLE) assertEquals(cropped[i], res.argb[i])
        assertTrue(c.isNotEmpty())
    }

    /** A soft glow fades towards the corners: not a matte, kept almost whole even after the old trim. */
    @Test fun softGlowSurvives() {
        val cov = glyphs(w, h + 40, oy = 40)
        val hh = h + 40
        val glow = FloatArray(cov.size)
        val sigma = 7.0
        val k = (-21..21).map { exp(-(it * it) / (2 * sigma * sigma)) }
        val ks = k.sum()
        val tmp = FloatArray(cov.size)
        for (y in 0 until hh) for (x in 0 until w) {
            var s = 0.0
            for (d in -21..21) { val xx = x + d; if (xx in 0 until w) s += cov[y * w + xx] * k[d + 21] }
            tmp[y * w + x] = (s / ks).toFloat()
        }
        for (y in 0 until hh) for (x in 0 until w) {
            var s = 0.0
            for (d in -21..21) { val yy = y + d; if (yy in 0 until hh) s += tmp[yy * w + x] * k[d + 21] }
            glow[y * w + x] = (s / ks * 0.7).toFloat()
        }
        val src = IntArray(cov.size) { i ->
            val ag = cov[i]
            val ao = ag + glow[i] * (1 - ag)
            if (ao <= 0f) 0 else argb((ao * 255).roundToInt(), 255, 255, 255)
        }
        for ((px, pw, ph) in listOf(Triple(src, w, hh), oldTrim(src, w, hh))) {
            val res = LogoCleanup.clean(px, pw, ph)
            assertEquals("glow is not a matte", 0, res.matte)
            val before = px.sumOf { a(it).toLong() }
            val after = res.argb.sumOf { a(it).toLong() }
            assertTrue("glow kept: $after of $before", after >= before * 0.99)
        }
    }

    @Test fun loneSpecksAreCleared() {
        val cov = glyphs()
        val src = logo(cov, 0xFFFFFF, matte = 0)
        val spots = listOf(5 to 5, 300 to 110, 160 to 3, 3 to 115, 315 to 60, 200 to 110)
        spots.forEach { (x, y) -> src[y * w + x] = argb(25, 255, 255, 255) }
        val res = LogoCleanup.clean(src, w, h)
        assertEquals(spots.size, res.specks)
        assertEquals(glyphBox[2] - glyphBox[0], res.width)
    }

    private fun opaque(cov: FloatArray, fg: Int, bg: Int, noise: Int): IntArray {
        val rnd = Random(7)
        return IntArray(cov.size) { i ->
            fun ch(s: Int) = ((fg shr s and 0xff) * cov[i] + (bg shr s and 0xff) * (1 - cov[i]) + rnd.nextInt(-noise, noise + 1)).roundToInt().coerceIn(0, 255)
            argb(255, ch(16), ch(8), ch(0))
        }
    }

    @Test fun opaqueWhiteBackgroundIsKeyedOut() {
        val cov = glyphs()
        val res = LogoCleanup.clean(opaque(cov, 0x111111, 0xFFFFFF, noise = 4), w, h)
        assertTrue(res.keyed)
        assertEquals(glyphBox[2] - glyphBox[0], res.width)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        for (i in res.argb.indices) {
            if (c[i] == 0f) assertEquals("background gone", 0, a(res.argb[i]))
            if (c[i] == 1f) { assertTrue(a(res.argb[i]) >= 240); assertTrue(r(res.argb[i]) <= 0x30) }
            if (c[i] in 0.3f..0.7f) assertTrue("soft edge", abs(a(res.argb[i]) / 255f - c[i]) < 0.12f)
        }
    }

    @Test fun opaqueBlackBackgroundIsKeyedOut() {
        val cov = glyphs()
        val res = LogoCleanup.clean(opaque(cov, 0xF4F4F4, 0x000000, noise = 3), w, h)
        assertTrue(res.keyed)
        val c = crop(cov, w, glyphBox[0], glyphBox[1], res.width, res.height)
        for (i in res.argb.indices) {
            if (c[i] == 0f) assertEquals(0, a(res.argb[i]))
            if (c[i] == 1f) { assertTrue(a(res.argb[i]) >= 240); assertTrue(r(res.argb[i]) >= 0xE0) }
        }
    }

    @Test fun photoWithWhiteFrameIsNotKeyed() {
        val rnd = Random(3)
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 12 || y < 12 || x >= w - 12 || y >= h - 12) argb(255, 255, 255, 255)
            else argb(255, (x * 255 / w + rnd.nextInt(-20, 20)).coerceIn(0, 255), (y * 2 + rnd.nextInt(-20, 20)).coerceIn(0, 255), rnd.nextInt(0, 256))
        }
        val res = LogoCleanup.clean(px, w, h)
        assertFalse(res.keyed)
        assertFalse(res.changed)
    }

    @Test fun cleaningTwiceChangesNothing() {
        val cov = glyphs()
        listOf(
            logo(cov, 0xFFFFFF, matte = 10, noise = 2),
            logo(cov, 0x202020, matte = 30, matteRgb = 0xFFFFFF, noise = 3),
            opaque(cov, 0x111111, 0xFFFFFF, noise = 4),
            opaque(cov, 0xF4F4F4, 0x000000, noise = 3),
            logo(cov, 0xFFFFFF, matte = 0),
        ).forEach { src ->
            val once = LogoCleanup.clean(src, w, h)
            val twice = LogoCleanup.clean(once.argb, once.width, once.height)
            assertFalse("second pass changed something", twice.changed)
        }
    }

    /** Applies a 4×5 colour matrix (offsets in 0..255) to one unpremultiplied ARGB pixel, like ColorMatrixColorFilter. */
    private fun apply(m: FloatArray, c: Int): Int {
        val v = floatArrayOf(r(c).toFloat(), (c shr 8 and 0xff).toFloat(), (c and 0xff).toFloat(), a(c).toFloat())
        val o = IntArray(4) { row -> (m[row * 5] * v[0] + m[row * 5 + 1] * v[1] + m[row * 5 + 2] * v[2] + m[row * 5 + 3] * v[3] + m[row * 5 + 4]).roundToInt().coerceIn(0, 255) }
        return argb(o[3], o[0], o[1], o[2])
    }

    /** Uncleaned logos (a matte up to the cleanup limit): the halo mask is empty off the letters, the logo itself too up to 16. */
    @Test fun haloOfAnUncleanedMatteLogoStaysOnTheLetters() {
        val cov = glyphs()
        val halo = LogoAlpha.haloMatrix(androidx.compose.ui.graphics.Color.Black).values
        val image = LogoAlpha.imageMatrix().values
        val tint = LogoAlpha.tintMatrix(androidx.compose.ui.graphics.Color.White).values
        for (matte in listOf(4, 12, 16, 30, 40)) {
            val src = logo(cov, 0xFFFFFF, matte = matte, matteRgb = 0, noise = 0)
            for (i in src.indices) {
                if (cov[i] == 0f) {
                    assertEquals("halo outside the letters, matte $matte", 0, a(apply(halo, src[i])))
                    if (matte <= 16) {
                        assertEquals("logo matte $matte drawn", 0, a(apply(image, src[i])))
                        assertEquals("tinted matte $matte drawn", 0, a(apply(tint, src[i])))
                    }
                }
                if (cov[i] == 1f) {
                    assertEquals(255, a(apply(halo, src[i])))
                    assertEquals(255, a(apply(image, src[i])))
                    assertEquals("image colours untouched", src[i] and 0xFFFFFF, apply(image, src[i]) and 0xFFFFFF)
                }
            }
        }
        // A clean anti-aliased edge keeps most of its alpha.
        assertTrue(a(apply(image, argb(128, 255, 255, 255))) in 116..128)
        assertEquals(max(0, 0), a(apply(halo, argb(60, 255, 255, 255))))
    }
}
