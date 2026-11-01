package com.novcloud.echo.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.ui.AppRoot
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.Ink
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The artwork library scrolled by finger with real-device insets and the mini player playing: where the pinned search
 * sits, that nothing shows through the status bar or the bar under the mini player, and where the back-to-top key
 * lands. v0.11.1 passed its screenshots and broke on a phone; these pin the geometry down with numbers.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class ArtLibraryLayoutTest : ScreenshotBase() {

    private fun open(dark: Boolean) {
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx)
        Samples.seedLargeArtLibrary(ctx)
        Samples.playing(ctx)
        waitForHome()
        compose.setContent {
            EchoTheme(dark, motion = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    AppRoot(MutableStateFlow(0L), startRoute = "settings/art-library")
                }
            }
        }
        edgeToEdge()
        settle()
    }

    private fun near(expected: Dp, actual: Dp, what: String) =
        assertTrue("$what: expected $expected, was $actual", kotlin.math.abs((expected - actual).value) <= 1f)

    /** Every sampled pixel in rows [fromPx, toPx) is the page colour: nothing drawn or showing through there. */
    private fun assertPlain(fromPx: Int, toPx: Int, surface: Color, what: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val want = surface.toArgb()
        for (y in fromPx until toPx.coerceAtMost(bmp.height) step 2) for (x in 0 until bmp.width step 3) {
            val c = bmp.getPixel(x, y)
            val d = maxOf(
                kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(want)),
                kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(want)),
                kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(want)),
            )
            assertTrue("$what: pixel ($x,$y) is #${Integer.toHexString(c)}, not the page colour", d <= 3)
        }
    }

    private fun check(dark: Boolean) {
        open(dark)
        val surface = if (dark) Ink else Color(0xFFF2EDE3)
        val density = ctx.resources.displayMetrics.density
        val barBottom = (STATUS_DP + 56).dp

        // Before scrolling: the search sits under the title block, well below the bar.
        val openTop = compose.onNodeWithTag("art_library_search").getBoundsInRoot().top
        assertTrue("search starts under the title block, was $openTop", openTop > barBottom + 80.dp)

        dragArtLibrary()
        settle(1)
        val search = compose.onNodeWithTag("art_library_search").getBoundsInRoot()
        val grid = compose.onNodeWithTag("art_library_grid").getBoundsInRoot()
        near(barBottom, search.top, "pinned search top = top bar bottom")
        assertTrue("grid starts below the pinned search (${grid.top} vs ${search.bottom})", grid.top >= search.bottom - 0.5.dp)

        // Status bar and top bar: opaque, nothing from the grid showing through (only the back arrow/title live there).
        assertPlain(0, (STATUS_DP * density).toInt(), surface, "status bar")

        // Under the mini player down to the screen edge: covered by the veil and the gesture-bar fill.
        val mini = compose.onNodeWithTag("mini_player").getBoundsInRoot()
        val rootH = compose.onRoot().getBoundsInRoot().bottom
        assertPlain(((rootH - NAV_DP.dp).value * density).toInt() + 1, (rootH.value * density).toInt(), surface, "gesture bar")

        // The back-to-top key: shown, clear above the mini player, inside the right margin.
        val key = compose.onNodeWithTag("scroll_top").getBoundsInRoot()
        assertTrue("key bottom ${key.bottom} clears mini player top ${mini.top}", key.bottom <= mini.top - 12.dp)
        val rootW = compose.onRoot().getBoundsInRoot().right
        near(rootW - 16.dp, key.right, "key right edge")

        // Back to the top: list at its first row and the title block open again.
        compose.onNodeWithTag("scroll_top").performClick()
        settle(2)
        val back = compose.onNodeWithTag("art_library_search").getBoundsInRoot().top
        near(openTop, back, "search back under the open title block")
    }

    @Test fun pinnedSearchLight() = check(dark = false)

    @Test fun pinnedSearchDark() = check(dark = true)

    @Test fun keepsPinnedThroughFilterChange() {
        open(dark = false)
        dragArtLibrary(3)
        settle(1)
        compose.onNodeWithTag("art_library_search").getBoundsInRoot().top.let { near((STATUS_DP + 56).dp, it, "pinned before filter") }
        // Filter down to a short list: the pinned bar stays a normal block, never drifting to where its slot was.
        compose.onAllNodesWithText("海报").onFirst().performClick()
        settle(1)
        val top = compose.onNodeWithTag("art_library_search").getBoundsInRoot().top
        val grid = compose.onNodeWithTag("art_library_grid").getBoundsInRoot().top
        val bottom = compose.onNodeWithTag("art_library_search").getBoundsInRoot().bottom
        assertTrue("search at $top stays between bar and grid", top >= (STATUS_DP + 56).dp - 1.dp && grid >= bottom - 0.5.dp)
    }
}
