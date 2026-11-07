package com.novcloud.echo.ui.detail

import com.novcloud.echo.data.CoverStyle

/** [side] is the cover square; [width] × [height] the whole composition, all in dp. */
data class CoverFrame(val side: Float, val width: Float, val height: Float)

/**
 * Proportions of the album cover styles, in units of the cover side. Kept free of Compose so the fit-and-centre
 * rules are unit tested.
 */
object CoverGeometry {
    const val RECORD = 0.94f
    const val RECORD_OUT = 0.42f
    const val CASE_W = 142f / 120f
    const val CASE_H = 125f / 120f
    const val DISC = 0.98f
    const val DISC_OUT = 0.40f
    const val FILM_RAIL = 0.19f
    const val FILM_GAP = 0.06f
    const val FILM_SHARE = 0.70f
    const val JEWEL_CAP = 0.92f

    /** [w] is the room across, [h] the room down (infinite when the composition may grow), [margin] kept clear on each side. */
    fun of(style: CoverStyle, w: Float, h: Float, margin: Float, cap: Float): CoverFrame {
        val room = (w - 2 * margin).coerceAtLeast(1f)
        return when (style) {
            CoverStyle.Classic -> minOf(cap, room, h).let { CoverFrame(it, it, it) }
            CoverStyle.Vinyl -> minOf(cap, room / vinylWidth, h).let { CoverFrame(it, it * vinylWidth, it) }
            CoverStyle.Jewel -> minOf(cap * JEWEL_CAP, room / CASE_W, h / jewelHeight).let { CoverFrame(it, it * CASE_W, it * jewelHeight) }
            CoverStyle.Film -> minOf(cap, w * FILM_SHARE, h / filmHeight).let { CoverFrame(it, w, it * filmHeight) }
        }
    }

    private const val vinylWidth = 0.5f + RECORD_OUT + RECORD / 2
    private const val jewelHeight = CASE_H + DISC * DISC_OUT
    private const val filmHeight = 1f + 2 * FILM_RAIL

    /**
     * Where the sleeve's centre sits, in sides from the middle, when the record is [out] of the way out (0..1, a
     * spring may overshoot): sleeve and record together stay centred all the way.
     */
    fun sleeveShift(out: Float): Float = -(maxOf(0.5f, RECORD_OUT * out + RECORD / 2) - 0.5f) / 2

    fun recordShift(out: Float): Float = sleeveShift(out) + RECORD_OUT * out

    /** Top of the disc, in sides from the top of the composition: tucked inside the case at 0, peeking out at 1. */
    fun discTop(out: Float): Float {
        val inside = DISC * DISC_OUT + (CASE_H - DISC) / 2
        return inside * (1 - out)
    }
}
