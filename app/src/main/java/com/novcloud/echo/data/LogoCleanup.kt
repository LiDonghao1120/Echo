package com.novcloud.echo.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tidies a logo before it is stored, on plain ARGB pixels (unpremultiplied, row-major) so it runs anywhere.
 *
 * Wordmarks found online often carry a faint matte: every pixel of the canvas holds a few percent of black or white.
 * Invisible on a white page, it shows up as a box the size of the image once the logo sits on a photo, and any effect
 * that recolours "every pixel that has alpha" (a tint, a halo) makes it worse. Logos saved as JPEG or flattened PNG
 * have the opposite problem: a solid white or black background around the letters.
 *
 * [clean] does three conservative things and leaves everything else alone:
 *  1. **Matte**: when the canvas edge and its corners sit at one low alpha level (1..[MATTE_MAX]), that level is
 *     taken off every pixel the way it was put on (un-composited, so anti-aliased edges and colours survive); what
 *     is left of the matte far from the letters is cleared.
 *  2. **Specks**: lone faint pixels with no neighbours are cleared.
 *  3. **Flat background**: a fully opaque logo whose whole border is one near-white or near-black colour has that
 *     colour keyed out with a soft edge, so it becomes a transparent wordmark.
 * Then the transparent edges are trimmed. A clean logo, a soft glow or a drop shadow comes out (almost) unchanged,
 * and cleaning a cleaned logo changes nothing.
 */
object LogoCleanup {
    /** Highest alpha read as a matte rather than artwork. */
    const val MATTE_MAX = 40

    /** Alpha at or below this is invisible and cleared. */
    const val INVISIBLE = 2

    /** Pixels of matte left over this far from the artwork are cleared. */
    private const val MATTE_REACH = 3

    class Result(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        /** Alpha level of the matte that was removed, or 0. */
        val matte: Int,
        /** True when a flat background colour was keyed out. */
        val keyed: Boolean,
        val specks: Int,
        /** True when any pixel or the size changed. */
        val changed: Boolean,
    )

    fun clean(src: IntArray, w: Int, h: Int): Result {
        require(w > 0 && h > 0 && src.size >= w * h)
        val n = w * h
        var px = src.copyOf(n)
        var pw = w
        var ph = h
        var matte = 0
        var keyed = false
        var specks = 0
        var opaque = 0
        for (c in px) if ((c ushr 24) >= 250) opaque++
        if (opaque >= n * 0.995) {
            // Keep a fully opaque image as it is unless its background is keyed out.
            keyed = keyFlat(px, w, h)
            if (!keyed) return Result(px, w, h, 0, false, 0, false)
        } else {
            clearInvisible(px)
            specks = despeckle(px, w, h)
            // Look for the matte on the visible part: one framed by a transparent margin counts too.
            trim(px, w, h)?.let { (t, tw, th) -> px = t; pw = tw; ph = th }
            matte = matteLevel(px, pw, ph)
            if (matte > 0) removeMatte(px, pw, ph, matte)
        }
        specks += despeckle(px, pw, ph)
        clearInvisible(px)
        trim(px, pw, ph)?.let { (t, tw, th) -> px = t; pw = tw; ph = th }
        val changed = pw != w || ph != h || !px.contentEquals(src.copyOf(n))
        return Result(px, pw, ph, matte, keyed, specks, changed)
    }

    private fun clearInvisible(px: IntArray) {
        for (i in px.indices) if ((px[i] ushr 24) in 1..INVISIBLE) px[i] = 0
    }

    // ---------------------------------------------------------------- matte

    /**
     * The alpha level of a matte covering the canvas, or 0. A matte is flat and reaches the canvas edge and at least
     * three of the four corners; a glow fades out towards the corners and leaves them (almost) empty.
     */
    internal fun matteLevel(px: IntArray, w: Int, h: Int): Int {
        if (w < 8 || h < 8) return 0
        val band = ArrayList<Int>()
        var borderLow = 0
        var borderZero = 0
        fun visit(x: Int, y: Int) {
            val a = px[y * w + x] ushr 24
            if (a >= 128) return
            borderLow++
            if (a == 0) borderZero++ else if (a <= MATTE_MAX) band += a
        }
        for (x in 0 until w) { visit(x, 0); visit(x, h - 1) }
        for (y in 1 until h - 1) { visit(0, y); visit(w - 1, y) }
        if (borderLow < 8 || band.size < borderLow * 0.6) return 0
        band.sort()
        val p10 = band[band.size / 10]
        val p50 = band[band.size / 2]
        val p95 = band[min(band.size - 1, band.size * 95 / 100)]
        if (p95 - p10 > max(10, p50)) return 0
        // Corners: small patches clear of the letters in almost every wordmark.
        val s = max(2, min(8, min(w, h) / 10))
        // A corner covered by a letter says nothing; an empty corner rules a matte out.
        var corners = 0
        for ((cx, cy) in listOf(0 to 0, w - s to 0, 0 to h - s, w - s to h - s)) {
            var inBand = 0
            var low = 0
            var zero = 0
            for (y in cy until cy + s) for (x in cx until cx + s) {
                val a = px[y * w + x] ushr 24
                if (a < 128) low++
                if (a == 0) zero++ else if (a <= MATTE_MAX) inBand++
            }
            if (low < s * s / 4) continue
            if (zero > low / 2) return 0
            if (inBand >= low / 2) corners++
        }
        if (corners < 2) return 0
        // The matte also has to be a real share of the picture, not a hairline.
        var share = 0
        for (c in px) if ((c ushr 24) in 1..MATTE_MAX) share++
        if (share < w * h * 0.08) return 0
        return min(MATTE_MAX, p95 + 1)
    }

    /** Takes a matte of alpha [level] off every pixel, keeping the letters' own colour and edges. */
    private fun removeMatte(px: IntArray, w: Int, h: Int, level: Int) {
        // Colour of the matte: the average of what lies in it.
        var r = 0L; var g = 0L; var b = 0L; var k = 0L
        for (c in px) {
            val a = c ushr 24
            if (a in 1..level) { r += c shr 16 and 0xff; g += c shr 8 and 0xff; b += c and 0xff; k++ }
        }
        val mr = if (k > 0) (r / k).toDouble() else 0.0
        val mg = if (k > 0) (g / k).toDouble() else 0.0
        val mb = if (k > 0) (b / k).toDouble() else 0.0
        val am = level / 255.0
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) / 255.0
            if (a <= am + 1e-9) { px[i] = 0; continue }
            val ag = (a - am) / (1 - am)
            fun ch(v: Int, m: Double) = ((v * a - m * am * (1 - ag)) / ag).roundToInt().coerceIn(0, 255)
            px[i] = argb((ag * 255).roundToInt().coerceIn(0, 255), ch(c shr 16 and 0xff, mr), ch(c shr 8 and 0xff, mg), ch(c and 0xff, mb))
        }
        // What is left of a noisy matte (pixels a little above the level) away from the letters goes too.
        val strong = BooleanArray(px.size) { (px[it] ushr 24) > MATTE_MAX }
        val near = dilate(strong, w, h, MATTE_REACH)
        for (i in px.indices) if (!near[i] && (px[i] ushr 24) <= MATTE_MAX) px[i] = 0
    }

    // ---------------------------------------------------------------- specks

    /** Clears faint pixels that have no other visible pixel around them. Returns how many. */
    internal fun despeckle(px: IntArray, w: Int, h: Int): Int {
        var n = 0
        val clear = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val a = px[y * w + x] ushr 24
            if (a == 0 || a > MATTE_MAX) continue
            var lonely = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if ((px[ny * w + nx] ushr 24) > INVISIBLE) { lonely = false; break@loop }
            }
            if (lonely) clear += y * w + x
        }
        for (i in clear) { px[i] = 0; n++ }
        return n
    }

    // ---------------------------------------------------------------- flat background

    /**
     * Keys out a solid near-white or near-black background of an opaque logo. Only when at least 97% of the border is
     * that one colour and what is left looks like a mark (not a photo, not an empty canvas). Returns true when done.
     */
    internal fun keyFlat(px: IntArray, w: Int, h: Int): Boolean {
        if (w < 8 || h < 8) return false
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { border += px[x]; border += px[(h - 1) * w + x] }
        for (y in 1 until h - 1) { border += px[y * w]; border += px[y * w + w - 1] }
        fun median(shift: Int) = border.map { it shr shift and 0xff }.sorted()[border.size / 2]
        val br = median(16); val bg = median(8); val bb = median(0)
        val luma = (0.2126 * br + 0.7152 * bg + 0.0722 * bb) / 255
        if (luma in 0.2..0.8) return false
        if (max(br, max(bg, bb)) - min(br, min(bg, bb)) > 40) return false
        fun dist(c: Int) = max(abs((c shr 16 and 0xff) - br), max(abs((c shr 8 and 0xff) - bg), abs((c and 0xff) - bb)))
        val tol = 28
        if (border.count { dist(it) <= tol } < border.size * 0.97) return false
        val lo = 12
        val d = IntArray(px.size) { dist(px[it]) }
        val hist = IntArray(256)
        for (v in d) hist[v]++
        var fgCount = 0
        for (v in 71..255) fgCount += hist[v]
        if (fgCount < px.size * 0.005 || fgCount > px.size * 0.7) return false
        // A mark has a few flat colours; a photo with a white frame has thousands. Leave photos alone.
        val bins = IntArray(4096)
        for (i in px.indices) if (d[i] > 70) {
            val c = px[i]
            bins[(c shr 20 and 0xf shl 8) or (c shr 12 and 0xf shl 4) or (c shr 4 and 0xf)]++
        }
        bins.sortDescending()
        var top = 0
        for (i in 0 until 8) top += bins[i]
        // Mostly background (a neon or glow mark on black) is a mark whatever its shades.
        var bgLike = 0
        for (v in 0..tol) bgLike += hist[v]
        if (bgLike < px.size / 2 && top < fgCount * 0.6) return false
        // Contrast of the letters against the background: the 90th percentile of the distance among them.
        var seen = 0
        var p90 = 255
        for (v in 71..255) { seen += hist[v]; if (seen >= fgCount * 0.9) { p90 = v; break } }
        val ref = max(80, p90).toDouble()
        for (i in px.indices) {
            val a = ((d[i] - lo) / (ref - lo)).coerceIn(0.0, 1.0)
            if (a <= 0.0) { px[i] = 0; continue }
            val c = px[i]
            fun ch(v: Int, bgv: Int) = ((v - bgv * (1 - a)) / a).roundToInt().coerceIn(0, 255)
            px[i] = argb((a * 255).roundToInt(), ch(c shr 16 and 0xff, br), ch(c shr 8 and 0xff, bg), ch(c and 0xff, bb))
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    /** Crops to the visible pixels; null when nothing needs cropping or nothing is visible. */
    internal fun trim(px: IntArray, w: Int, h: Int): Triple<IntArray, Int, Int>? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) != 0) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < l || b < t) return null
        if (l == 0 && t == 0 && r == w - 1 && b == h - 1) return null
        val cw = r - l + 1
        val ch = b - t + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(px, (y + t) * w + l, out, y * cw, cw)
        return Triple(out, cw, ch)
    }

    /** Square dilation of [m] by [r] pixels (separable running max). */
    internal fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(m.size)
        for (y in 0 until h) {
            var last = -1_000_000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= r) tmp[y * w + x] = true }
            last = 1_000_000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= r) tmp[y * w + x] = true }
        }
        val out = BooleanArray(m.size)
        for (x in 0 until w) {
            var last = -1_000_000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= r) out[y * w + x] = true }
            last = 1_000_000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= r) out[y * w + x] = true }
        }
        return out
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}
