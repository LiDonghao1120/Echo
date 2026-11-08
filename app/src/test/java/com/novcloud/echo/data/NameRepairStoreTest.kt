package com.novcloud.echo.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.PlayEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.charset.Charset

/** Stored data under a GBK-misread artist name after v0.13.2 repairs it. Fictional artists, synthetic images. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class NameRepairStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val c get() = (ctx.applicationContext as EchoApp).container
    private val good = "MARÍA VELL"
    private val bad = String(good.toByteArray(Charsets.ISO_8859_1), Charset.forName("GB18030"))

    private fun resource(path: String, name: String): File {
        val dir = File(ctx.filesDir, "fixtures").apply { mkdirs() }
        return File(dir, name).also { f -> javaClass.classLoader!!.getResourceAsStream(path)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    private fun song(n: Long, artist: String) =
        Song(n, "Track $n", artist, "Lumen Fields", 7, "", "Pop", 200_000, n.toInt(), 1, 2025, 0, "/m/lumen/$n.mp3", "/m/lumen", "audio/mpeg", 1000 + n)

    private fun event(artist: String, at: Long) = PlayEvent(songId = 1, title = "Track 1", artist = artist, album = "Lumen Fields", albumId = 7, genre = "Pop", startedAt = at, listenedMs = 60_000, songDurationMs = 200_000, completed = false, songKey = "p:m/lumen/1.mp3")

    @Test
    fun repairMergesHistoryAndArtAndRedoesAutoApply() = runBlocking {
        val lib = Library.build(listOf(song(1, bad), song(2, bad), song(3, good)).map { it.cleaned() })
        assertEquals(listOf(good), lib.artists.map { it.name })
        c.library.publish(lib)

        val logo = resource("samples/library/Otis Fenwick_wordmark.png", "a_logo.png")
        val assetId = c.db.artAssets().insert(
            ArtAsset(name = bad, kind = ArtKind.Logo.name, path = logo.absolutePath, width = 10, height = 4, luma = 0.5f, hash = "h1", addedAt = 1, updatedAt = 1),
        )
        assertTrue(c.library.fillArt(bad, ArtKind.Logo, logo))
        assertEquals("", c.db.artistLogos().get(bad)!!.auto)
        val poster = resource("samples/library/Juniper Vale_poster.jpg", "p.jpg")
        c.db.artistImages().put(ArtistImage(bad, poster.absolutePath, 5))
        c.db.events().insert(event(bad, 10))
        c.db.events().insert(event(good, 20))

        c.artLibrary.repairNames()

        assertNull(c.db.artistLogos().get(bad))
        assertNull(c.db.artistImages().get(bad))
        assertEquals(poster.absolutePath, c.db.artistImages().get(good)!!.path)
        assertTrue(poster.exists())
        assertEquals(listOf(good, good), c.db.events().all().map { it.artist })

        assertEquals(1, c.artLibrary.autoApplyNow())
        val row = c.db.artistLogos().get(good)!!
        assertEquals(ArtLibraryMatch.key(good), row.auto)
        assertTrue(File(row.path).exists())
        assertTrue(c.db.artAssets().get(assetId) != null)
    }

    @Test
    fun artTheUserPickedIsKeptAndMovedNotRemoved() = runBlocking {
        c.library.publish(Library.build(listOf(song(1, bad)).map { it.cleaned() }))
        val mine = resource("samples/logo_kira.png", "mine.png")
        c.db.artistLogos().put(ArtistLogo(bad, mine.absolutePath, 3f, 0.9f, 5))
        val other = resource("samples/library/Nova_Lark_logo.png", "n.png")
        c.db.artAssets().insert(ArtAsset(name = "Dov Kessel", kind = ArtKind.Logo.name, path = other.absolutePath, width = 10, height = 4, luma = 0.5f, hash = "h2", addedAt = 1, updatedAt = 1))

        c.artLibrary.repairNames()

        assertNull(c.db.artistLogos().get(bad))
        assertEquals(mine.absolutePath, c.db.artistLogos().get(good)!!.path)
        assertTrue(mine.exists())
        assertEquals(0, c.artLibrary.autoApplyNow())
        assertEquals(mine.absolutePath, c.db.artistLogos().get(good)!!.path)
    }

    @Test
    fun anAutoAppliedImageMatchedOnAnOldNameIsTakenOff() = runBlocking {
        c.library.publish(Library.build(listOf(song(1, good)).map { it.cleaned() }))
        val f = resource("samples/logo_kira.png", "stale.png")
        c.db.artistLogos().put(ArtistLogo(good, f.absolutePath, 3f, 0.9f, 5, auto = "cara"))
        assertEquals(1, c.library.dropStaleAutoArt())
        assertNull(c.db.artistLogos().get(good))
        assertFalse(f.exists())
        c.db.artistLogos().put(ArtistLogo(good, resource("samples/logo_kira.png", "fresh.png").absolutePath, 3f, 0.9f, 6, auto = ArtLibraryMatch.key(good)))
        assertEquals(0, c.library.dropStaleAutoArt())
    }
}
