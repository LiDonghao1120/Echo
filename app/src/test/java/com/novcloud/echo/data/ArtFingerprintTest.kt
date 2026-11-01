package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/** Same image after re-encoding, trimming and scaling; different images stay apart. Pure pixels, no Android. */
class ArtFingerprintTest {
    private class Img(val w: Int, val h: Int, val px: IntArray) {
        fun print() = ArtFingerprint.of(px, w, h)!!
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A wordmark-like logo: white letters on transparency, shaped by [seed]. */
    private fun logo(w: Int, h: Int, seed: Int, pad: Int = 0): Img {
        val px = IntArray((w + pad * 2) * (h + pad * 2))
        val rnd = Random(seed)
        val bars = List(7) { Triple(rnd.nextInt(w - 20), 6 + rnd.nextInt(16), 0.2f + rnd.nextFloat() * 0.8f) }
        val tw = w + pad * 2
        for (y in 0 until h) for (x in 0 until w) {
            val inside = bars.any { (x0, bw, hf) -> x in x0 until x0 + bw && y >= h * (1 - hf) } || (y in h / 2 - 2..h / 2 + 2 && x % 37 < 30)
            if (inside) px[(y + pad) * tw + x + pad] = argb(255, 245, 240, 230)
        }
        // pin the corners so the trimmed box is the whole logo
        px[pad * tw + pad] = argb(255, 245, 240, 230)
        px[(h - 1 + pad) * tw + w - 1 + pad] = argb(255, 245, 240, 230)
        return Img(tw, h + pad * 2, px)
    }

    /** A poster-like photo: smooth gradients and a few blobs, opaque. */
    private fun poster(w: Int, h: Int, seed: Int): Img {
        val rnd = Random(seed)
        val blobs = List(5) { Triple(rnd.nextInt(w), rnd.nextInt(h), 20 + rnd.nextInt(w / 3)) }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 40 + 120 * y / h
            blobs.forEach { (cx, cy, r) -> if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) v += 60 }
            val c = v.coerceIn(0, 255)
            px[y * w + x] = argb(255, c, (c * 0.8f).toInt(), (c * 0.6f).toInt())
        }
        return Img(w, h, px)
    }

    /** Area-average resize, like a decoder scaling down. */
    private fun scale(img: Img, f: Float): Img {
        val nw = (img.w * f).roundToInt().coerceAtLeast(1)
        val nh = (img.h * f).roundToInt().coerceAtLeast(1)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val x0 = x * img.w / nw; val x1 = maxOf(x0 + 1, (x + 1) * img.w / nw)
            val y0 = y * img.h / nh; val y1 = maxOf(y0 + 1, (y + 1) * img.h / nh)
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = img.px[yy * img.w + xx]
                val ca = c ushr 24
                a += ca; r += (c shr 16 and 0xff) * ca; g += (c shr 8 and 0xff) * ca; b += (c and 0xff) * ca; n++
            }
            out[y * nw + x] = if (a == 0) 0 else argb(a / n, r / a, g / a, b / a)
        }
        return Img(nw, nh, out)
    }

    /** Lossy re-encode stand-in: every channel off by up to [amount]. */
    private fun noisy(img: Img, amount: Int, seed: Int = 7): Img {
        val rnd = Random(seed)
        return Img(img.w, img.h, IntArray(img.px.size) { i ->
            val c = img.px[i]
            val a = c ushr 24
            if (a == 0) 0 else {
                fun ch(v: Int) = (v + rnd.nextInt(-amount, amount + 1)).coerceIn(0, 255)
                argb(a, ch(c shr 16 and 0xff), ch(c shr 8 and 0xff), ch(c and 0xff))
            }
        })
    }

    @Test
    fun theSameFileMatchesItself() {
        val l = logo(600, 120, 1).print()
        assertTrue(l.same(l))
        assertEquals(0, l.gradientDistance(l))
    }

    @Test
    fun aTrimmedScaledReEncodedCopyIsTheSameLogo() {
        val original = logo(600, 120, 1, pad = 60)
        val libraryCopy = noisy(scale(logo(600, 120, 1), 0.7f), 4)
        assertTrue(original.print().same(libraryCopy.print()))
    }

    @Test
    fun aReEncodedPosterIsTheSamePoster() {
        val original = poster(900, 1200, 3)
        val copy = noisy(scale(original, 0.45f), 6, seed = 11)
        assertTrue(original.print().same(copy.print()))
    }

    @Test
    fun differentLogosAndPostersStayApart() {
        val a = logo(600, 120, 1).print()
        assertFalse(a.same(logo(600, 120, 2).print()))
        assertFalse(a.same(logo(600, 120, 3).print()))
        assertFalse(a.same(logo(300, 120, 1).print()))
        val p = poster(900, 1200, 3).print()
        assertFalse(p.same(poster(900, 1200, 4).print()))
        assertFalse(p.same(a))
    }

    @Test
    fun aDarkLogoIsNotTheSameAsItsLightVersion() {
        val light = logo(600, 120, 5)
        val dark = Img(light.w, light.h, IntArray(light.px.size) { i -> if (light.px[i] == 0) 0 else argb(255, 20, 20, 24) })
        assertFalse(light.print().same(dark.print()))
    }

    @Test
    fun aSmallEditInOneCornerIsADifferentImage() {
        val p = poster(800, 800, 9)
        val edited = Img(p.w, p.h, p.px.copyOf().also { px -> for (y in 0 until 110) for (x in 0 until 110) px[y * p.w + x] = argb(255, 250, 250, 250) })
        assertFalse(p.print().same(edited.print()))
    }

    @Test
    fun encodesAndReadsBack() {
        val f = logo(640, 90, 4).print()
        val back = ArtFingerprint.parse(f.encode())
        assertNotNull(back)
        assertEquals(f.encode(), back!!.encode())
        assertTrue(back.same(f))
        assertNull(ArtFingerprint.parse("x1;1;0;0;;"))
        assertNull(ArtFingerprint.parse(null))
        assertNull(ArtFingerprint.parse("f1;nonsense"))
    }

    @Test
    fun emptyAndFullyTransparentImagesHaveNoFingerprint() {
        assertNull(ArtFingerprint.of(IntArray(0), 0, 0))
        assertNull(ArtFingerprint.of(IntArray(100), 10, 10))
    }

    @Test
    fun tinyImagesStillGetAFingerprint() {
        val px = IntArray(3 * 2) { argb(255, it * 40, 0, 0) }
        assertNotNull(ArtFingerprint.of(px, 3, 2))
    }
}
