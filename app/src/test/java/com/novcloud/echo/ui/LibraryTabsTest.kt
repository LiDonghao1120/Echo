package com.novcloud.echo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.novcloud.echo.ui.library.LibraryTabs
import com.novcloud.echo.ui.library.tabs
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.seededColor
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The library tab row must not move a single label while the selection animates to any tab, and must fit the screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
open class LibraryTabsTest {
    protected open val shot = "library_tabs_switching"

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tabPositionsAreIdenticalThroughEverySwitch() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoTheme(true, motion = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val pager = rememberPagerState { tabs.size }
                    val scope = rememberCoroutineScope()
                    val labels = tabs.map { stringResource(it) }
                    Column {
                        LibraryTabs(labels, pager) { i -> scope.launch { pager.animateScrollToPage(i) } }
                        HorizontalPager(pager, Modifier.fillMaxWidth().height(240.dp)) { page ->
                            Box(Modifier.fillMaxSize().background(seededColor(labels[page])))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        fun bounds() = tabs.indices.map { compose.onNodeWithTag("library_tab_$it").getUnclippedBoundsInRoot() }
        val start = bounds()
        val width = compose.onRoot().getUnclippedBoundsInRoot().right
        assertTrue("last tab ends at ${start.last().right}, screen is $width", start.last().right <= width)
        var captured = false
        for (target in listOf(1, 2, 3, 0, 4, 5, 2, 0)) {
            compose.onNodeWithTag("library_tab_$target").performClick()
            repeat(50) { frame ->
                compose.mainClock.advanceTimeByFrame()
                assertEquals("switching to tab $target, frame $frame", start, bounds())
                if (!captured && target == 2 && frame == 6) {
                    compose.onRoot().captureRoboImage("build/outputs/roborazzi/$shot.png")
                    captured = true
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "en-rUS-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class LibraryTabsEnglishTest : LibraryTabsTest() {
    override val shot = "library_tabs_switching_en"
}
