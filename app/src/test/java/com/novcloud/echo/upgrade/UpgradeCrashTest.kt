package com.novcloud.echo.upgrade

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.data.ArtistArtStore
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.LibrarySnapshot
import com.novcloud.echo.data.Names
import com.novcloud.echo.data.SnapshotStore
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.Favorite
import com.novcloud.echo.data.db.Playlist
import com.novcloud.echo.diag.StartupSteps
import com.novcloud.echo.screenshots.Samples
import com.novcloud.echo.ui.AppRoot
import com.novcloud.echo.ui.theme.EchoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Installing over v0.10.0: its saved library (one artist under several spellings, NUL multi-values), a poster under
 * two spellings, history, favorites and a playlist. Restores through [LibraryRepository] (snapshot load, art merge)
 * and composes every screen that lists artists or albums.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class UpgradeCrashTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = compose.activity

    private fun sample(name: String): File {
        val f = File(ctx.cacheDir, name)
        if (!f.exists()) javaClass.classLoader!!.getResourceAsStream("samples/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun seedV0100(): LibraryRepository {
        val c = Samples.app(ctx)
        Samples.installImageLoader(ctx)
        val songs = V0100Library.songs
        val older = "${V0100Library.BASE}\u0000"
        runBlocking { withTimeout(20_000) { c.library.awaitLoaded() } }
        runBlocking {
            V0100Library.events(System.currentTimeMillis()).forEach { c.db.events().insert(it) }
            c.db.favorites().addAll(songs.filter { Names.key(it.artist).startsWith(Names.key(V0100Library.BASE)) }.map { Favorite(it.key, 1) })
            val pl = c.db.playlists().insert(Playlist(name = "Mix", createdAt = 1))
            c.db.playlists().add(pl, songs.take(12).map { it.key })
            val p1 = ArtistArtStore.savePoster(ctx, older, Uri.fromFile(sample("poster_field.jpg")))!!
            c.db.artistImages().put(ArtistImage(older, p1.absolutePath, 100))
            val p2 = ArtistArtStore.savePoster(ctx, V0100Library.BASE, Uri.fromFile(sample("poster_stage.jpg")))!!
            c.db.artistImages().put(ArtistImage(V0100Library.BASE, p2.absolutePath, 200))
        }
        SnapshotStore(ctx.filesDir).save(LibrarySnapshot(songs, emptyList(), emptyMap(), emptyMap()))
        ctx.getSharedPreferences("startup", 0).edit().clear().commit()
        val repo = LibraryRepository(ctx, c.db, c.prefs, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val lib = runBlocking { withTimeout(20_000) { repo.awaitLoaded() } }
        assertTrue(lib.loaded)
        c.library.publish(lib)
        return repo
    }

    private fun show(route: String, after: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoTheme(true, motion = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { AppRoot(MutableStateFlow(0L), startRoute = route) }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeBy(1_500); compose.waitForIdle() }
        after()
        repeat(3) { compose.mainClock.advanceTimeBy(1_500); compose.waitForIdle() }
    }

    @Test
    fun restoreMergesArtAndKeepsData() {
        val repo = seedV0100()
        val c = Samples.app(ctx)
        val lib: Library = repo.library.value
        assertEquals(1, lib.artists.count { Names.key(it.name) == Names.key(V0100Library.BASE) })
        val rows = runBlocking { c.db.artistImages().allNow() }.filter { Names.key(it.artist) == Names.key(V0100Library.BASE) }
        assertEquals(1, rows.size)
        assertEquals(200, rows[0].updatedAt)
        assertTrue(File(rows[0].path).exists())
        assertEquals(StartupSteps.DONE, ctx.getSharedPreferences("startup", 0).getString("merge_artist_art_v1", null))
        assertTrue(runBlocking { c.db.favorites().keys().first() }.isNotEmpty())
    }

    @Test fun home() { seedV0100(); show("home") }
    @Test fun stats() { seedV0100(); show("stats") }
    @Test fun artist() { seedV0100(); show("artist/${Uri.encode(V0100Library.BASE)}") }
    @Test fun album() { seedV0100(); show("album/10") }
    @Test fun search() { seedV0100(); show("search") }

    @Test
    fun libraryTabs() {
        seedV0100()
        show("library") {
            listOf("专辑", "歌手", "流派", "文件夹").forEach { tab ->
                compose.onAllNodesWithText(tab).onFirst().performClick()
                repeat(3) { compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle() }
            }
        }
    }
}
