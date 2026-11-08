package com.novcloud.echo.screenshots

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.novcloud.echo.ui.player.PlayerPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The now-playing cover morphing into the lyrics / queue header thumbnail, with motion on and real-device insets.
 * Frame by frame the one cover must sit on the straight path between the two slots with its size and position moving
 * together, land exactly in each slot, reverse from where it is when interrupted, and stay put between lyrics and queue.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class NowPlayingMorphTest : ScreenshotBase() {

    private val d get() = ctx.resources.displayMetrics.density

    private fun cover(): Rect = compose.onNodeWithTag("np_cover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun frame() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    /** Where the header thumbnail must be: 24dp in, 10dp below the 52dp top row under the status bar, 56dp square. */
    private fun thumbSlot(): Rect {
        val top = (STATUS_DP + 52 + 10) * d
        return Rect(24 * d, top, 80 * d, top + 56 * d)
    }

    private fun assertNear(what: String, want: Rect, got: Rect, tol: Float = 2.5f) {
        val ok = abs(want.left - got.left) <= tol && abs(want.top - got.top) <= tol && abs(want.right - got.right) <= tol && abs(want.bottom - got.bottom) <= tol
        assertTrue("$what: want $want got $got", ok)
    }

    private fun fraction(big: Rect, thumb: Rect, r: Rect) = (big.width - r.width) / (big.width - thumb.width)

    /** Same morph fraction read from the size and from the centre: one element moving and resizing together. */
    private fun assertOnPath(big: Rect, thumb: Rect, r: Rect) {
        val fw = fraction(big, thumb, r)
        val fx = (big.center.x - r.center.x) / (big.center.x - thumb.center.x)
        val fy = (big.center.y - r.center.y) / (big.center.y - thumb.center.y)
        assertTrue("size $fw vs x $fx at $r", abs(fw - fx) < 0.02f)
        assertTrue("size $fw vs y $fy at $r", abs(fw - fy) < 0.02f)
    }

    private fun bigRest(): Rect {
        val big = cover()
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertEquals("big cover centred", width / 2, big.center.x, 2f)
        assertTrue("big cover is big: $big", big.width > 220 * d)
        assertTrue("big cover below the top row: $big", big.top > (STATUS_DP + 52) * d)
        return big
    }

    /** Runs frames until the cover stops moving; returns how many frames it took and checks every one on the path. */
    private fun run(big: Rect, thumb: Rect, toThumb: Boolean, shots: Map<Float, String> = emptyMap()): Int {
        val pending = shots.toSortedMap().toMutableMap()
        var last = fraction(big, thumb, cover())
        var frames = 0
        var still = 0
        val target = if (toThumb) 1f else 0f
        while (frames < 120 && still < 3) {
            frame()
            frames++
            val r = cover()
            assertOnPath(big, thumb, r)
            val f = fraction(big, thumb, r)
            assertTrue("no jump between frames: $last -> $f", abs(f - last) < 0.2f)
            pending.keys.firstOrNull { if (toThumb) f >= it else f <= it }?.let { k ->
                compose.onRoot().captureRoboImage("build/outputs/roborazzi/${pending.remove(k)}.png")
            }
            still = if (abs(f - target) < 0.003f && abs(f - last) < 0.0005f) still + 1 else 0
            last = f
        }
        return frames
    }

    @Test fun lyrics_morph_frames() {
        Samples.primeLyrics(ctx)
        app(
            "nowplaying_morph_lyrics", expanded = true, insets = true, motion = true, capture = false,
        ) {
            val big = bigRest()
            val thumb = thumbSlot()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/morph_lyrics_000.png")
            tap("pane_lyrics")
            val frames = run(big, thumb, toThumb = true, mapOf(0.25f to "morph_lyrics_025", 0.5f to "morph_lyrics_050", 0.75f to "morph_lyrics_075"))
            assertTrue("morph takes a visible while: ${frames * 16}ms", frames * 16 in 300..1_400)
            assertNear("thumbnail at rest", thumb, cover())
            val title = compose.onNodeWithTag("np_small_title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("title beside the thumbnail", thumb.right + 14 * d, title.left, 2.5f)
            assertTrue("title centred on the thumbnail", abs(title.center.y - thumb.center.y) < 3 * d)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/morph_lyrics_100.png")
            tap("pane_lyrics")
            run(big, thumb, toThumb = false, mapOf(0.5f to "morph_lyrics_closing_050"))
            assertNear("big cover back at rest", big, cover())
        }
    }

    @Test fun interrupted_morph_reverses_from_where_it_is() {
        Samples.primeLyrics(ctx)
        app(
            "nowplaying_morph_interrupt", expanded = true, insets = true, motion = true, capture = false,
        ) {
            val big = bigRest()
            val thumb = thumbSlot()
            tap("pane_lyrics")
            repeat(10) { frame() }
            val before = fraction(big, thumb, cover())
            assertTrue("mid-way after 160ms: $before", before in 0.15f..0.9f)
            tap("pane_lyrics")
            frame()
            val after = fraction(big, thumb, cover())
            assertTrue("reverses without a jump: $before -> $after", abs(after - before) < 0.1f)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/morph_interrupt_dark.png")
            run(big, thumb, toThumb = false)
            assertNear("big cover back at rest", big, cover())
        }
    }

    @Test fun queue_and_lyrics_share_the_thumbnail() {
        Samples.primeLyrics(ctx)
        app(
            "nowplaying_morph_queue", expanded = true, insets = true, motion = true, capture = false,
        ) {
            val big = bigRest()
            val thumb = thumbSlot()
            tap("pane_queue")
            run(big, thumb, toThumb = true, mapOf(0.5f to "morph_queue_050"))
            assertNear("queue thumbnail at rest", thumb, cover())
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/morph_queue_100.png")
            tap("pane_lyrics")
            repeat(30) {
                frame()
                assertNear("cover stays the thumbnail switching queue to lyrics", thumb, cover())
                if (it == 6) compose.onRoot().captureRoboImage("build/outputs/roborazzi/morph_queue_to_lyrics.png")
            }
        }
    }

    @Test fun reduced_motion_switches_at_once() {
        Samples.primeLyrics(ctx)
        app(
            "nowplaying_morph_reduced", expanded = true, insets = true, motion = false, capture = false,
        ) {
            val big = cover()
            tap("pane_lyrics")
            frame()
            frame()
            assertNear("thumbnail straight away", thumbSlot(), cover())
            tap("pane_lyrics")
            frame()
            frame()
            assertNear("big cover straight away", big, cover())
        }
    }

    @Test fun lyrics_rest_without_motion() {
        Samples.primeLyrics(ctx)
        app("nowplaying_morph_lyrics_rest", expanded = true, pane = PlayerPane.Lyrics, insets = true) {
            assertNear("opened on lyrics: thumbnail in place", thumbSlot(), cover())
        }
    }
}
