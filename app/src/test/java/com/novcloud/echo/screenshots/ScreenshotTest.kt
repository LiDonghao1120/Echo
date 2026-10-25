package com.novcloud.echo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.novcloud.echo.R
import com.novcloud.echo.ui.AppRoot
import com.novcloud.echo.ui.components.AppActions
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.detail.ArtistArtActions
import com.novcloud.echo.ui.detail.ArtistArtSheetContent
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.EchoShape
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav"

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = PHONE)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = compose.activity

    private fun settle(rounds: Int = 4) {
        repeat(rounds) {
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            Thread.sleep(400)
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    private fun waitForHome() {
        val c = Samples.app(ctx)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !(c.home.value.hasHistory && c.home.value.daily != null)) Thread.sleep(100)
    }

    private fun app(
        name: String,
        dark: Boolean = true,
        route: String = "home",
        expanded: Boolean = false,
        playing: Boolean = true,
        tall: Int? = null,
        withArt: Boolean = true,
        before: () -> Unit = {},
    ) {
        if (tall != null) RuntimeEnvironment.setQualifiers("+h${tall}dp")
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx, withArt)
        if (playing) Samples.playing(ctx)
        waitForHome()
        compose.setContent {
            EchoTheme(dark, motion = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    AppRoot(MutableStateFlow(0L), startRoute = route, startExpanded = expanded)
                }
            }
        }
        settle()
        before()
        settle(2)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun home_dark() = app("home_dark")
    @Test fun home_dark_tall() = app("home_dark_tall", tall = 1900)
    @Test fun home_light() = app("home_light", dark = false)

    @Test fun artist_ivy_dark() = app("artist_ivy_dark", route = "artist/Ivy%20Calder")
    @Test fun artist_kira_dark() = app("artist_kira_dark", route = "artist/Kira%20Nox")
    @Test fun artist_velvet_light() = app("artist_velvet_light", dark = false, route = "artist/The%20Velvet%20Static")
    @Test fun artist_text_dark() = app("artist_text_dark", route = "artist/%E6%B8%A9%E5%B1%BF")
    @Test fun artist_latin_text_dark() = app("artist_latin_text_dark", route = "artist/Otis%20Fenwick")
    @Test fun artist_ivy_tall() = app("artist_ivy_tall", route = "artist/Ivy%20Calder", tall = 1700)

    @Test fun artist_ivy_collapsed() = app("artist_ivy_collapsed", route = "artist/Ivy%20Calder") {
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.25f, durationMillis = 400) }
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test fun artist_velvet_mid() = app("artist_velvet_mid", route = "artist/The%20Velvet%20Static") {
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.55f, durationMillis = 600) }
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test fun stats_dark_tall() = app("stats_dark_tall", route = "stats", tall = 3300)
    @Test fun stats_dark() = app("stats_dark", route = "stats")
    @Test fun stats_light_tall() = app("stats_light_tall", dark = false, route = "stats", tall = 3300)

    @Test fun nowplaying_dark() = app("nowplaying_dark", expanded = true)

    @Test fun library_artists_dark() = app("library_artists_dark", route = "library") {
        compose.onNodeWithText("歌手").performClick()
    }
    @Test fun library_songs_light() = app("library_songs_light", dark = false, route = "library")
    @Test fun album_dark() = app("album_dark", route = "album/1")
    @Test fun search_dark() = app("search_dark", route = "search")
    @Test fun settings_dark() = app("settings_dark", route = "settings")
    @Test fun settings_light() = app("settings_light", dark = false, route = "settings")
    @Test fun folders_dark() = app("folders_dark", route = "settings/folders")
    @Test fun daily_dark() = app("daily_dark", route = "mix/daily")

    @Test fun artist_sheet_dark() {
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx)
        val c = Samples.app(ctx)
        compose.setContent {
            EchoTheme(true, motion = false) {
                val nav = androidx.navigation.compose.rememberNavController()
                val actions = AppActions(nav, c.player, c.library, c.home, { _, _ -> }, {}, {})
                androidx.compose.runtime.CompositionLocalProvider(LocalActions provides actions) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF000000)), contentAlignment = Alignment.BottomCenter) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
                            Box(Modifier.padding(top = 20.dp)) {
                                ArtistArtSheetContent(
                                    "Ivy Calder", true, c.library.artistLogos.value["Ivy Calder"],
                                    ArtistArtActions({}, {}, {}, {}, {}, {}),
                                )
                            }
                        }
                    }
                }
            }
        }
        settle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/artist_sheet_dark.png")
    }

    @Test fun icon() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF6E6A61)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Icon(CircleShape)
                    Icon(RoundedCornerShape(42.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(150.dp).clip(CircleShape).background(Color(0xFF2B3A2E)), contentAlignment = Alignment.Center) {
                        Image(
                            painterResource(R.drawable.ic_launcher_monochrome), null,
                            Modifier.wrapContentSize(unbounded = true).requiredSize(225.dp),
                            colorFilter = ColorFilter.tint(Color(0xFFCFE8C8)),
                        )
                    }
                    Icon(CircleShape, 72)
                    Icon(CircleShape, 48)
                }
            }
        }
        settle(1)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/icon.png")
    }

    @Composable
    private fun Icon(shape: androidx.compose.ui.graphics.Shape, size: Int = 150) {
        Box(Modifier.size(size.dp).clip(shape), contentAlignment = Alignment.Center) {
            val full = Modifier.wrapContentSize(unbounded = true).requiredSize((size * 1.5f).dp)
            Image(painterResource(R.drawable.ic_launcher_background), null, full)
            Image(painterResource(R.drawable.ic_launcher_foreground), null, full)
        }
    }
}
