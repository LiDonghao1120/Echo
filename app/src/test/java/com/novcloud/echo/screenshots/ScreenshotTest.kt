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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import com.novcloud.echo.playback.SleepState
import com.novcloud.echo.ui.player.SleepTimerContent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
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

internal const val STATUS_DP = 40
internal const val NAV_DP = 24

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

    /** Far down the artwork library: the search bar pinned under the title and the back-to-top key showing. */
    protected fun scrollArtLibrary() {
        compose.onNodeWithTag("art_library_grid").performScrollToIndex(44)
        settle(2)
        compose.onNodeWithTag("art_library_grid").performScrollToIndex(46)
    }

    /**
     * Real-device edge-to-edge: a 40dp status bar and a 24dp gesture bar, dispatched to the Compose view the way the
     * system does. Robolectric gives no insets by default, which is how v0.11.1's pinned search passed CI.
     */
    protected fun edgeToEdge(statusDp: Int = STATUS_DP, navDp: Int = NAV_DP) {
        compose.runOnUiThread {
            val d = ctx.resources.displayMetrics.density
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, (statusDp * d).toInt(), 0, 0))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, (navDp * d).toInt()))
                .build()
            val content = ctx.findViewById<android.view.ViewGroup>(android.R.id.content)
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** Scrolls the artwork library the way a finger does (drags and flings through nested scrolling), not by jumping. */
    protected fun dragArtLibrary(times: Int = 6) {
        repeat(times) {
            compose.onNodeWithTag("art_library_grid").performTouchInput {
                swipeUp(startY = height * 0.62f, endY = height * 0.12f, durationMillis = 350)
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitForIdle()
        }
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
        artLibrary: Boolean = false,
        insets: Boolean = false,
        capture: Boolean = true,
        motion: Boolean = false,
        seedMore: () -> Unit = {},
        before: () -> Unit = {},
    ) {
        if (tall != null) RuntimeEnvironment.setQualifiers("+h${tall}dp")
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx, withArt)
        seedMore()
        if (artLibrary) Samples.seedArtLibrary(ctx)
        if (playing) Samples.playing(ctx)
        sleep?.let { Samples.app(ctx).player.previewSleep(it) }
        waitForHome()
        compose.setContent {
            EchoTheme(dark, motion = motion) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    AppRoot(MutableStateFlow(0L), startRoute = route, startExpanded = expanded, startPane = pane)
                }
            }
        }
        if (insets) edgeToEdge()
        settle()
        before()
        settle(2)
        if (capture) compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * Holds the drag handle of upcoming row [row] and moves it down by [rows] row heights without lifting the finger,
     * the way a person reorders the queue; returns the titles of the upcoming songs as they were before.
     */
    protected fun dragQueue(row: Int = 1, rows: Float = 2.6f): List<String> {
        val state = Samples.app(ctx).player.state.value
        val pos = state.queue.indexOfFirst { it.index == state.currentIndex }
        val titles = state.queue.drop(pos + 1).map { it.song.title }
        val h = compose.onNodeWithTag("queue_row_0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val handle = compose.onAllNodesWithTag("queue_handle", useUnmergedTree = true)[row]
        handle.performTouchInput { down(center) }
        repeat(24) {
            compose.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, h * rows / 24), delayMillis = 16) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        return titles
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

    /** A bottom sheet's content on its own, the way the sheet shows it. */
    protected fun sheet(name: String, dark: Boolean = true, seed: () -> Unit = {}, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx)
        seed()
        val c = Samples.app(ctx)
        compose.setContent {
            EchoTheme(dark, motion = false) {
                val nav = androidx.navigation.compose.rememberNavController()
                val actions = AppActions(nav, c.player, c.library, c.home, { _, _ -> }, {}, {})
                androidx.compose.runtime.CompositionLocalProvider(LocalActions provides actions) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF000000)), contentAlignment = Alignment.BottomCenter) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
                            Box(Modifier.padding(top = 20.dp)) { content() }
                        }
                    }
                }
            }
        }
        settle()
        before()
        settle(1)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The artwork library picker on an artist without a logo: the artist's own wordmark first. */
    protected fun picker(name: String) = sheet(name, seed = { Samples.seedArtLibrary(ctx) }) {
        val c = Samples.app(ctx)
        val assets by c.artLibrary.assets.collectAsState()
        val linked = com.novcloud.echo.ui.settings.rememberLinkedArtists()
        com.novcloud.echo.ui.detail.ArtLibraryPickerContent(
            "Otis Fenwick", com.novcloud.echo.data.ArtKind.Logo, assets, linked.keys, {}, {}, {},
        )
    }

    /** Editing one library image: name, kind, the jump to its artist. */
    protected fun assetSheet(name: String) = sheet(name, seed = { Samples.seedArtLibrary(ctx) }) {
        val c = Samples.app(ctx)
        val assets by c.artLibrary.assets.collectAsState()
        val linked = com.novcloud.echo.ui.settings.rememberLinkedArtists()
        assets.firstOrNull { it.name == "温屿" }?.let { a ->
            com.novcloud.echo.ui.settings.AssetSheetContent(a, linked[com.novcloud.echo.data.ArtLibraryMatch.key(a.name)]) {}
        }
    }

    /** Saves the album cover style and waits until the app has picked it up. */
    protected fun coverStyle(style: com.novcloud.echo.data.CoverStyle) {
        val c = Samples.app(ctx)
        kotlinx.coroutines.runBlocking { c.prefs.setCoverStyle(style) }
        awaitCoverStyle(style)
    }

    protected fun awaitCoverStyle(style: com.novcloud.echo.data.CoverStyle) {
        val c = Samples.app(ctx)
        val deadline = System.currentTimeMillis() + 5_000
        while (c.coverStyle.value != style && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        check(c.coverStyle.value == style) { "cover style stayed ${c.coverStyle.value}" }
    }

    /** The cover style picker as its sheet shows it, previews drawn with a sample album's cover. */
    protected fun coverPicker(name: String, dark: Boolean = true) = sheet(name, dark = dark) {
        val album = Samples.library.albumById.getValue(1)
        com.novcloud.echo.ui.detail.CoverStylePicker(com.novcloud.echo.data.CoverStyle.Vinyl, album.art, album.title, hint = true) {}
    }

    /** The stats page's Today card on its own, with fixed fictional numbers (the app's own Today depends on the clock). */
    protected fun todayCard(name: String, dark: Boolean = true, empty: Boolean = false) {
        compose.mainClock.autoAdvance = false
        Samples.seed(ctx, withArt = true)
        val c = Samples.app(ctx)
        val song = Samples.songs.first { it.artist == "Ivy Calder" }
        val min = 60_000L
        val hourly = LongArray(24).also { h ->
            if (!empty) mapOf(8 to 12, 9 to 6, 12 to 4, 13 to 3, 18 to 8, 19 to 14).forEach { (k, v) -> h[k] = v * min }
        }.toList()
        val stats = if (empty) com.novcloud.echo.domain.TodayStats(java.time.LocalDate.of(2026, 11, 8), averageMs = 35 * min)
        else com.novcloud.echo.domain.TodayStats(
            date = java.time.LocalDate.of(2026, 11, 8), totalMs = 47 * min, plays = 14, songs = 11, artists = 6, hourly = hourly,
            topSong = com.novcloud.echo.domain.Ranked(song.id.toString(), song.title, song.artist, song.art, 11 * min, 3),
            topArtist = com.novcloud.echo.domain.Ranked("Ivy Calder", "Ivy Calder", "", "Ivy Calder", 21 * min, 6),
            averageMs = 35 * min,
        )
        compose.setContent {
            EchoTheme(dark, motion = false) {
                val nav = androidx.navigation.compose.rememberNavController()
                val actions = AppActions(nav, c.player, c.library, c.home, { _, _ -> }, {}, {})
                androidx.compose.runtime.CompositionLocalProvider(LocalActions provides actions) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().testTag("today_shot").padding(bottom = 18.dp)) {
                            com.novcloud.echo.ui.stats.TodayCard(stats, nowHour = 19)
                        }
                    }
                }
            }
        }
        settle(2)
        compose.onNodeWithTag("today_shot").captureRoboImage("build/outputs/roborazzi/$name.png")
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
    /** A logo with a faint dark matte stored by an older version and never cleaned: no box behind it on the photo. */
    @Test fun artist_matte_raw_dark() = app("artist_matte_raw_dark", route = "artist/Ivy%20Calder", seedMore = { Samples.matteLogo(ctx, "Ivy Calder", cleaned = false) })
    /** The same kind of logo (a stronger white matte) set on the artist page now: stored clean. */
    @Test fun artist_matte_light() = app("artist_matte_light", dark = false, route = "artist/Ivy%20Calder", seedMore = { Samples.matteLogo(ctx, "Ivy Calder", cleaned = true) })
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
    @Test fun stats_today_dark() = todayCard("stats_today_dark")
    @Test fun stats_today_light() = todayCard("stats_today_light", dark = false)
    @Test fun stats_today_empty_dark() = todayCard("stats_today_empty_dark", empty = true)

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

    @Test fun queue_dark() = app("queue_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Queue, insets = true)

    /** Row 2 of Up Next held and dragged down past two rows: it floats lifted under the finger, the rows it passed moved up. */
    @Test fun queue_drag_dark() = app("queue_drag_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Queue, insets = true) {
        val titles = dragQueue(row = 1, rows = 2.6f)
        fun top(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("held row moved below the rows it passed", top(titles[1]) > top(titles[2]) && top(titles[1]) > top(titles[3]))
        org.junit.Assert.assertTrue("rows passed shifted up", top(titles[2]) < top(titles[1]) && top(titles[3]) < top(titles[4]))
        val statusPx = STATUS_DP * ctx.resources.displayMetrics.density
        org.junit.Assert.assertTrue("held row stays below the status bar", top(titles[1]) > statusPx)
    }

    @Test fun lyrics_embedded_dark() {
        Samples.primeEmbeddedLyrics(ctx)
        app("lyrics_embedded_dark", expanded = true, pane = com.novcloud.echo.ui.player.PlayerPane.Lyrics, insets = true)
    }

    @Test fun library_artists_dark() = app("library_artists_dark", route = "library") {
        compose.onNodeWithText("歌手").performClick()
    }
    @Test fun library_songs_light() = app("library_songs_light", dark = false, route = "library")
    @Test fun album_dark() = app("album_dark", route = "album/1", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Classic) })
    @Test fun album_vinyl_dark() = app("album_vinyl_dark", route = "album/1", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Vinyl) })
    @Test fun album_vinyl_light() = app("album_vinyl_light", dark = false, route = "album/5", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Vinyl) })
    @Test fun album_jewel_dark() = app("album_jewel_dark", route = "album/1", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Jewel) })
    @Test fun album_jewel_light() = app("album_jewel_light", dark = false, route = "album/3", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Jewel) })
    @Test fun album_film_dark() = app("album_film_dark", route = "album/1", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Film) })
    @Test fun album_film_light() = app("album_film_light", dark = false, route = "album/6", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Film) })
    @Test fun album_vinyl_narrow_dark() {
        RuntimeEnvironment.setQualifiers("+w340dp")
        app("album_vinyl_narrow_dark", route = "album/1", seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Vinyl) })
    }
    @Test fun cover_style_sheet_dark() = coverPicker("cover_style_sheet_dark")
    @Test fun cover_style_sheet_light() = coverPicker("cover_style_sheet_light", dark = false)

    @Test fun album_cover_long_press_opens_the_picker() = app(
        "album_cover_long_press_dark", route = "album/1", capture = false, seedMore = { coverStyle(com.novcloud.echo.data.CoverStyle.Film) },
    ) {
        compose.onNodeWithTag("album_cover").performTouchInput { longClick() }
        settle(2)
        compose.onAllNodesWithText(ctx.getString(R.string.cover_style)).onFirst().assertExists()
        compose.onAllNodesWithText(ctx.getString(R.string.cover_style_vinyl)).onFirst().performClick()
        settle(1)
        awaitCoverStyle(com.novcloud.echo.data.CoverStyle.Vinyl)
        compose.onAllNodesWithText(ctx.getString(R.string.cover_style)).onFirst().assertExists()
        settle(1)
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/album_cover_long_press_dark.png")
    }
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
                                    "Ivy Calder", true, c.library.artistLogos.value[com.novcloud.echo.data.Names.key("Ivy Calder")],
                                    ArtistArtActions({}, {}, {}, {}, {}, {}), onLibrary = {},
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

    @Test fun art_library_dark() = app("art_library_dark", route = "settings/art-library", artLibrary = true)
    @Test fun art_library_light() = app("art_library_light", dark = false, route = "settings/art-library", artLibrary = true)
    @Test fun art_library_empty_dark() = app("art_library_empty_dark", route = "settings/art-library")
    @Test fun art_library_search_dark() = app("art_library_search_dark", route = "settings/art-library", artLibrary = true) {
        compose.onNode(hasSetTextAction()).performTextInput("wy")
    }
    @Test fun art_library_scrolled_dark() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_scrolled_dark", route = "settings/art-library") { scrollArtLibrary() }
    }
    @Test fun art_library_scrolled_light() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_scrolled_light", dark = false, route = "settings/art-library") { scrollArtLibrary() }
    }
    // v0.11.2: scrolled by finger with real-device insets and the mini player — the state v0.11.1 broke.
    @Test fun art_library_e2e_light() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_e2e_light", dark = false, route = "settings/art-library", insets = true) { dragArtLibrary() }
    }
    @Test fun art_library_e2e_dark() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_e2e_dark", route = "settings/art-library", insets = true) { dragArtLibrary() }
    }
    @Test fun art_library_e2e_folding_light() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_e2e_folding_light", dark = false, route = "settings/art-library", insets = true) {
            compose.onNodeWithTag("art_library_grid").performTouchInput { swipeUp(startY = height * 0.5f, endY = height * 0.42f, durationMillis = 900) }
        }
    }
    @Test fun art_library_e2e_filtered_light() {
        Samples.seedLargeArtLibrary(ctx)
        app("art_library_e2e_filtered_light", dark = false, route = "settings/art-library", insets = true) {
            compose.onAllNodesWithText("海报").onFirst().performClick()
            compose.mainClock.advanceTimeBy(1_000)
            dragArtLibrary(2)
        }
    }
    @Test fun songlist_e2e_light() = app("songlist_e2e_light", dark = false, route = "list/recent", insets = true) {
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.6f, endY = height * 0.1f, durationMillis = 350) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.6f, endY = height * 0.1f, durationMillis = 350) }
    }
    @Test fun library_albums_e2e_light() = app("library_albums_e2e_light", dark = false, route = "library", insets = true) {
        compose.onAllNodesWithText("专辑").onFirst().performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.6f, endY = height * 0.15f, durationMillis = 350) }
    }
    @Test fun art_picker_scrolled_light() = sheet(
        "art_picker_scrolled_light", dark = false, seed = { Samples.seedLargeArtLibrary(ctx) },
        before = {
            repeat(3) {
                compose.onNodeWithTag("art_picker_grid").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.1f, durationMillis = 300) }
                compose.mainClock.advanceTimeBy(1_500)
            }
        },
    ) {
        val c = Samples.app(ctx)
        val assets by c.artLibrary.assets.collectAsState()
        val linked = com.novcloud.echo.ui.settings.rememberLinkedArtists()
        com.novcloud.echo.ui.detail.ArtLibraryPickerContent(
            "Otis Fenwick", com.novcloud.echo.data.ArtKind.Logo, assets, linked.keys, {}, {}, {},
        )
    }
    @Test fun art_picker_dark() = picker("art_picker_dark")
    @Test fun art_asset_sheet_dark() = assetSheet("art_asset_sheet_dark")

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
