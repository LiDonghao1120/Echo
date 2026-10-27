package com.novcloud.echo.screenshots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.playback.SleepState
import com.novcloud.echo.ui.player.PlayerPane
import com.novcloud.echo.widget.WidgetState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE_EN = "en-rUS-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav"

/** The same app in English: copy, plurals, dates and the layouts that grow with longer words. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = PHONE_EN)
class EnglishScreenshotTest : ScreenshotBase() {

    @Test fun home_en() = app("home_en")
    @Test fun home_tall_en() = app("home_tall_en", tall = 1900)
    @Test fun home_light_en() = app("home_light_en", dark = false)

    @Test fun library_songs_en() = app("library_songs_en", route = "library")
    @Test fun library_artists_en() = app("library_artists_en", route = "library") {
        compose.onNodeWithText("Artists").performClick()
    }
    @Test fun library_playlists_en() = app("library_playlists_en", route = "library") {
        compose.onNodeWithText("Playlists").performClick()
    }

    @Test fun artist_en() = app("artist_en", route = "artist/Ivy%20Calder", tall = 1700)
    @Test fun artist_kira_en() = app("artist_kira_en", route = "artist/Kira%20Nox")
    @Test fun album_en() = app("album_en", route = "album/1", tall = 1400)
    @Test fun daily_en() = app("daily_en", route = "mix/daily")
    @Test fun search_en() = app("search_en", route = "search")

    @Test fun nowplaying_en() = app("nowplaying_en", expanded = true)

    @Test fun lyrics_en() {
        Samples.primeWordLyrics(ctx)
        app("lyrics_en", expanded = true, pane = PlayerPane.Lyrics)
    }

    @Test fun lyrics_tools_en() {
        Samples.primeLyrics(ctx)
        val song = Samples.songs.first { it.title == "Paper Satellites" }
        kotlinx.coroutines.runBlocking { Samples.app(ctx).db.lyricOverrides().setOffset(song.key, 500) }
        app("lyrics_tools_en", expanded = true, pane = PlayerPane.Lyrics) {
            compose.onNode(hasContentDescription("Lyrics settings")).performClick()
        }
    }

    @Test fun nowplaying_sleep_en() = app("nowplaying_sleep_en", expanded = true, sleep = SleepState(System.currentTimeMillis() + 1_421_000, totalMs = 30 * 60_000L))
    @Test fun sleep_sheet_en() = sleepSheet("sleep_sheet_en")

    @Test fun stats_en() = app("stats_en", route = "stats")
    @Test fun stats_tall_en() = app("stats_tall_en", route = "stats", tall = 3900)
    @Test fun stats_light_tall_en() = app("stats_light_tall_en", dark = false, route = "stats", tall = 3900)

    @Test fun settings_en() = app("settings_en", route = "settings")
    @Test fun settings_tall_en() = app("settings_tall_en", route = "settings", tall = 2200)
    @Test fun folders_en() = app("folders_en", route = "settings/folders")

    @Test fun effects_en() {
        primeEffects()
        app("effects_en", route = "settings/effects", tall = 1400)
    }

    @Test fun widgets_en() = widgets(
        "widgets_en",
        WidgetState("Paper Satellites", "Ivy Calder", playing = true),
        WidgetState(),
    )
}
