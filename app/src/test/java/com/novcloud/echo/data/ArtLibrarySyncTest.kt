package com.novcloud.echo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.screenshots.Samples
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Logos and posters set on an artist page from the phone are kept in the artwork library once: same file,
 * re-encoded or trimmed copies are recognised, different images are added. Fictional artists.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ArtLibrarySyncTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val c get() = (ctx.applicationContext as EchoApp).container

    private fun picked(resource: String, name: String): File {
        val dir = File(ctx.cacheDir, "picked").apply { mkdirs() }
        return File(dir, name).also { f -> javaClass.classLoader!!.getResourceAsStream(resource)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    /** [source] decoded, given a transparent margin and scaled, then written again in another encoding. */
    private fun reEncoded(source: File, name: String, scale: Float, margin: Int, format: Bitmap.CompressFormat, quality: Int): File {
        val bmp = BitmapFactory.decodeFile(source.absolutePath)
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val out = Bitmap.createBitmap(w + margin * 2, h + margin * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(Bitmap.createScaledBitmap(bmp, w, h, true), margin.toFloat(), margin.toFloat(), null)
        val f = File(File(ctx.cacheDir, "picked").apply { mkdirs() }, name)
        f.outputStream().use { out.compress(format, quality, it) }
        return f
    }

    private suspend fun library() = c.db.artAssets().allNow()

    @Test
    fun aLogoSetOnAnArtistPageIsKeptUnderTheArtistsNameOnce() = runBlocking {
        val f = picked("samples/library/Nova_Lark_logo.png", "IMG_2041.png")
        assertTrue(c.library.setArtFromPhone("Nova Lark", ArtKind.Logo, Uri.fromFile(f)))
        val one = library().single()
        assertEquals("Nova Lark", one.name)
        assertEquals(ArtKind.Logo.name, one.kind)
        assertTrue(File(one.path).exists())

        assertTrue(c.library.setArtFromPhone("Nova Lark", ArtKind.Logo, Uri.fromFile(f)))
        assertEquals(Mirror.Duplicate, c.artLibrary.mirror("Nova Lark", ArtKind.Logo, Uri.fromFile(f)))
        assertEquals(1, library().size)
    }

    @Test
    fun aReEncodedOrTrimmedCopyOfALibraryImageIsNotAddedAgain() = runBlocking {
        val logo = picked("samples/library/Otis Fenwick_wordmark.png", "Otis Fenwick_wordmark.png")
        val poster = picked("samples/library/Juniper Vale_poster.jpg", "Juniper Vale_poster.jpg")
        c.artLibrary.add(listOf(Uri.fromFile(logo), Uri.fromFile(poster)))
        assertEquals(2, library().size)

        val paddedWebp = reEncoded(logo, "otis.webp", 0.8f, 48, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        val smallJpeg = reEncoded(poster, "juniper.jpg", 0.5f, 0, Bitmap.CompressFormat.JPEG, 72)
        assertEquals(Mirror.Duplicate, c.artLibrary.mirror("Otis Fenwick", ArtKind.Logo, Uri.fromFile(paddedWebp)))
        assertEquals(Mirror.Duplicate, c.artLibrary.mirror("Juniper Vale", ArtKind.Poster, Uri.fromFile(smallJpeg)))
        // the library's own stored copy, offered back as if it were picked from the phone
        library().forEach { a -> assertEquals(Mirror.Duplicate, c.artLibrary.mirror(a.name, ArtLibraryMatch.kindOf(a), Uri.fromFile(File(a.path)))) }
        assertEquals(2, library().size)
    }

    @Test
    fun aDifferentImageIsAdded() = runBlocking {
        c.artLibrary.add(listOf(Uri.fromFile(picked("samples/library/Nova_Lark_logo.png", "Nova_Lark_logo.png"))))
        val other = picked("samples/library/Pale Orchid (2).png", "Screenshot_1.png")
        assertEquals(Mirror.Added, c.artLibrary.mirror("Nova Lark", ArtKind.Logo, Uri.fromFile(other)))
        val poster = picked("samples/poster_field.jpg", "DSC0001.jpg")
        assertEquals(Mirror.Added, c.artLibrary.mirror("Nova Lark", ArtKind.Poster, Uri.fromFile(poster)))
        val items = library()
        assertEquals(3, items.size)
        assertEquals(listOf("Nova Lark", "Nova Lark", "Nova Lark"), items.map { it.name })
        assertEquals(setOf(ArtKind.Logo.name, ArtKind.Poster.name), items.map { it.kind }.toSet())
    }

    @Test
    fun switchedOffNothingIsAdded() = runBlocking {
        c.artLibrary.setSyncFromArtist(false)
        val f = picked("samples/library/温屿_logo.png", "温屿.png")
        assertTrue(c.library.setArtFromPhone("温屿", ArtKind.Logo, Uri.fromFile(f)))
        assertEquals(Mirror.Off, c.artLibrary.mirror("温屿", ArtKind.Logo, Uri.fromFile(f)))
        assertTrue(library().isEmpty())
        assertEquals(0, c.artLibrary.backfillNow())
        assertTrue(library().isEmpty())
        c.artLibrary.setSyncFromArtist(true)
        assertEquals(Mirror.Added, c.artLibrary.mirror("温屿", ArtKind.Logo, Uri.fromFile(f)))
    }

    @Test
    fun keepingAnArtistsImageNeverStartsAutoApply() = runBlocking {
        c.library.publish(Samples.library)
        val f = picked("samples/logo_kira.png", "kira.png")
        assertTrue(c.library.setArtFromPhone("Kira Nox", ArtKind.Logo, Uri.fromFile(f)))
        val row = c.db.artistLogos().allNow().single { it.artist == "Kira Nox" }
        assertEquals(1, library().size)
        assertEquals(0, c.artLibrary.autoApplyNow())
        assertEquals(row.path, c.db.artistLogos().allNow().single { it.artist == "Kira Nox" }.path)
        assertEquals(1, library().size)
    }

    @Test
    fun backfillAddsEarlierArtistImagesOnceWithoutDuplicates() = runBlocking {
        fun logo(artist: String, res: String) {
            val l = ArtistArtStore.saveLogo(ctx, artist, Uri.fromFile(picked(res, "$artist.png")))!!
            runBlocking { c.db.artistLogos().put(ArtistLogo(artist, l.file.absolutePath, l.aspect, l.luma, 1)) }
        }
        logo("Ivy Calder", "samples/logo_ivy.png")
        logo("The Velvet Static", "samples/logo_velvet.png")
        logo("Marlow & the Tides", "samples/library/Marlow & the Tides - Logo.png")
        val p = ArtistArtStore.savePoster(ctx, "Ivy Calder", Uri.fromFile(picked("samples/poster_field.jpg", "p.jpg")))!!
        c.db.artistImages().put(ArtistImage("Ivy Calder", p.absolutePath, 1))
        // already in the library as the original file; the artist holds a re-encoded copy
        c.artLibrary.add(listOf(Uri.fromFile(picked("samples/library/Marlow & the Tides - Logo.png", "Marlow & the Tides - Logo.png"))))

        c.artLibrary.runBackfill()
        val items = library()
        assertEquals(4, items.size)
        assertEquals(
            setOf("Ivy Calder|Logo", "The Velvet Static|Logo", "Marlow & the Tides|Logo", "Ivy Calder|Poster"),
            items.map { "${it.name}|${it.kind}" }.toSet(),
        )

        logo("Kira Nox", "samples/logo_kira.png")
        c.artLibrary.runBackfill()
        assertEquals(4, library().size)
        // a pass run by hand still adds only what is missing
        assertEquals(1, c.artLibrary.backfillNow())
        assertEquals(0, c.artLibrary.backfillNow())
        assertEquals(5, library().size)
    }
}
