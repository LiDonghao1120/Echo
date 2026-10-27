package com.novcloud.echo.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.novcloud.echo.playback.SleepState
import com.novcloud.echo.ui.player.SleepTimerContent
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

/** Rendering helpers shared by the Chinese and English screenshot suites. */
abstract class ScreenshotBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val ctx get() = compose.activity

    protected fun settle(rounds: Int = 4) {
        repeat(rounds) {
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
            Thread.sleep(400)
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    protected fun waitForHome() {
        val c = Samples.app(ctx)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !(c.home.value.hasHistory && c.home.value.daily != null)) Thread.sleep(100)
    }

    protected fun app(
        name: String,
        dark: Boolean = true,
        route: String = "home",
        expanded: Boolean = false,
        pane: com.novcloud.echo.ui.player.PlayerPane = com.novcloud.echo.ui.player.PlayerPane.Art,
        playing: Boolean = true,
        tall: Int? = null,
        withArt: Boolean = true,
        sleep: SleepState? = null,
        before: () -> Unit = {},
    ) {
        if (tall != null) RuntimeEnvironment.setQualifiers("+h${tall}dp")
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx, withArt)
        if (playing) Samples.playing(ctx)
        sleep?.let { Samples.app(ctx).player.previewSleep(it) }
        waitForHome()
        compose.setContent {
            EchoTheme(dark, motion = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    AppRoot(MutableStateFlow(0L), startRoute = route, startExpanded = expanded, startPane = pane)
                }
            }
        }
        settle()
        before()
        settle(2)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    protected fun sleepSheet(name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoTheme(true, motion = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF000000)), contentAlignment = Alignment.BottomCenter) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
                        Box(Modifier.padding(top = 24.dp)) {
                            SleepTimerContent(
                                SleepState(deadline = System.currentTimeMillis() + 1_421_000, totalMs = 30 * 60_000L),
                                remaining = 1_421_000, onPick = {}, onCancel = {},
                            )
                        }
                    }
                }
            }
        }
        settle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    protected fun widgets(name: String, playing: com.novcloud.echo.widget.WidgetState, paused: com.novcloud.echo.widget.WidgetState) {
        compose.mainClock.autoAdvance = false
        val cover = android.graphics.BitmapFactory.decodeStream(javaClass.getResourceAsStream("/samples/cover_3.jpg"))
        fun themed(dark: Boolean): android.content.Context {
            val conf = android.content.res.Configuration(ctx.resources.configuration)
            conf.uiMode = (conf.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            return ctx.createConfigurationContext(conf)
        }
        @Composable
        fun Widget(layout: Int, state: com.novcloud.echo.widget.WidgetState, dark: Boolean, height: Int, art: android.graphics.Bitmap?) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c ->
                    val host = android.widget.FrameLayout(themed(dark))
                    val views = com.novcloud.echo.widget.Widgets.views(themed(dark), layout, state, art)
                    host.addView(views.apply(themed(dark), host))
                    host
                },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(height.dp),
            )
        }
        compose.setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF5E6B73)).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Widget(R.layout.widget_small, playing, false, 64, cover)
                Widget(R.layout.widget_small, paused, true, 64, null)
                Widget(R.layout.widget_large, playing, true, 158, cover)
                Widget(R.layout.widget_large, paused, false, 190, cover)
            }
        }
        settle(1)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    protected fun primeEffects() {
        val fx = Samples.app(ctx).audioFx
        fx.publishCaps(com.novcloud.echo.playback.audio.EqCaps(true, listOf(60, 230, 910, 3600, 14000), -1500, 1500, bassBoost = true))
        fx.update {
            com.novcloud.echo.playback.audio.AudioSettings(
                eqEnabled = true, preset = "rock", bassStrength = 400,
                gainMode = com.novcloud.echo.playback.audio.GainMode.Track, preampDb = 2f,
            )
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = PHONE)
class ScreenshotTest : ScreenshotBase() {

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

    @Test fun lyrics_dark() {
        Samples.primeLyrics(ctx)
        app("lyrics_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Lyrics)
    }

    @Test fun lyrics_word_dark() {
        Samples.primeWordLyrics(ctx)
        app("lyrics_word_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Lyrics)
    }

    @Test fun lyrics_word_tools_dark() {
        Samples.primeWordLyrics(ctx)
        app("lyrics_word_tools_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Lyrics) {
            compose.onNode(androidx.compose.ui.test.hasContentDescription("歌词调整")).performClick()
        }
    }

    @Test fun library_artists_dark() = app("library_artists_dark", route = "library") {
        compose.onNodeWithText("歌手").performClick()
    }
    @Test fun library_songs_light() = app("library_songs_light", dark = false, route = "library")
    @Test fun album_dark() = app("album_dark", route = "album/1")
    @Test fun search_dark() = app("search_dark", route = "search")

    @Test fun search_pinyin_dark() = app("search_pinyin_dark", route = "search") {
        compose.onNode(hasSetTextAction()).performTextInput("wy")
    }

    @Test fun library_index_dark() = app("library_index_dark", route = "library") {
        compose.onRoot().performTouchInput { down(androidx.compose.ui.geometry.Offset(right - 26f, height * 0.52f)) }
    }

    @Test fun nowplaying_sleep_dark() = app("nowplaying_sleep_dark", expanded = true, sleep = SleepState(System.currentTimeMillis() + 1_421_000, totalMs = 30 * 60_000L))

    @Test fun sleep_sheet_dark() = sleepSheet("sleep_sheet_dark")

    @Test fun effects_dark() {
        primeEffects()
        app("effects_dark", route = "settings/effects", tall = 1400)
    }

    @Test fun effects_light() {
        primeEffects()
        app("effects_light", dark = false, route = "settings/effects", tall = 1400)
    }

    @Test fun effects_drag_dark() {
        primeEffects()
        app("effects_drag_dark", route = "settings/effects") {
            compose.onRoot().performTouchInput { down(androidx.compose.ui.geometry.Offset(width * 0.62f, height * 0.36f)) }
        }
    }

    @Test fun lyrics_offset_dark() {
        Samples.primeLyrics(ctx)
        val song = Samples.songs.first { it.title == "Paper Satellites" }
        kotlinx.coroutines.runBlocking { Samples.app(ctx).db.lyricOverrides().setOffset(song.key, 500) }
        app("lyrics_offset_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Lyrics) {
            compose.onNode(androidx.compose.ui.test.hasContentDescription("歌词调整")).performClick()
        }
    }

    @Test fun widgets() = widgets(
        "widgets",
        com.novcloud.echo.widget.WidgetState("Paper Satellites", "Ivy Calder", playing = true),
        com.novcloud.echo.widget.WidgetState("白鸟与北岸的长夜", "温屿", playing = false),
    )

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
