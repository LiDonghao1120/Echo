package com.novcloud.echo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.db.ArtistLogo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Logos are cleaned when they are stored (artist page, artwork library) and once more by the v0.11.3 pass over logos
 * stored earlier: a faint matte, a solid white background. Generated wordmarks for fictional artists.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class LogoCleanupStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val c get() = (ctx.applicationContext as EchoApp).container
    private val boxW = SyntheticLogos.box[2] - SyntheticLogos.box[0]
    private val boxH = SyntheticLogos.box[3] - SyntheticLogos.box[1]

    @Before fun holdTheStartupPass() {
        // The app's own one-time pass would otherwise race the calls made by hand here.
        ctx.getSharedPreferences("startup", android.content.Context.MODE_PRIVATE).edit()
            .putString("artist_logo_cleanup_v1", "done").putString("art_library_logo_cleanup_v1", "done").commit()
    }

    private fun file(name: String, px: IntArray, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, w: Int = SyntheticLogos.W, h: Int = SyntheticLogos.H): File {
        val dir = File(ctx.cacheDir, "picked").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return File(dir, name).also { f -> f.outputStream().use { bmp.compress(format, 95, it) } }
    }

    private fun pixels(f: File): Triple<IntArray, Int, Int> {
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Triple(px, b.width, b.height)
    }

    /** Coverage of the letters cropped to their box, the size a cleaned logo is stored at. */
    private val letters: FloatArray by lazy {
        val cov = SyntheticLogos.coverage()
        FloatArray(boxW * boxH) { cov[(it / boxW + SyntheticLogos.box[1]) * SyntheticLogos.W + it % boxW + SyntheticLogos.box[0]] }
    }

    /**
     * The stored logo is the letters' box, background clear and letters opaque. A JPEG source rings around the letters,
     * so [slack] lets its box grow by a pixel or two and only checks the background further than that from a letter.
     */
    private fun assertClean(f: File, what: String, slack: Int = 0) {
        val (px, w, h) = pixels(f)
        assertTrue("$what width $w", abs(w - boxW) <= 2 * slack)
        assertTrue("$what height $h", abs(h - boxH) <= 2 * slack)
        val ox = (w - boxW) / 2
        val oy = (h - boxH) / 2
        val near = LogoCleanup.dilate(BooleanArray(letters.size) { letters[it] > 0f }, boxW, boxH, slack)
        val edge = LogoCleanup.dilate(BooleanArray(letters.size) { letters[it] < 1f }, boxW, boxH, slack)
        var core = 0
        for (y in 0 until boxH) for (x in 0 until boxW) {
            val i = y * boxW + x
            val xx = x + ox
            val yy = y + oy
            if (xx !in 0 until w || yy !in 0 until h) continue
            val p = px[yy * w + xx]
            if (!near[i]) assertTrue("$what: background ($x,$y) has alpha ${p ushr 24}", (p ushr 24) <= 8)
            if (!edge[i]) { assertTrue("$what: letter ($x,$y) alpha ${p ushr 24}", (p ushr 24) >= 240); core++ }
        }
        assertTrue(core > 1000)
    }

    @Test fun aLogoWithAFaintMatteIsStoredWithout() {
        val info = ArtistArtStore.saveLogo(ctx, "Wren Hollow", Uri.fromFile(file("wren.png", SyntheticLogos.matte(0xFFFFFF, 10))))!!
        assertClean(info.file, "artist logo")
        assertEquals(boxW.toFloat() / boxH, info.aspect, 0.01f)
        assertTrue("white logo, not darkened by the matte: ${info.luma}", info.luma > 0.9f)
        assertFalse(info.dark)
    }

    @Test fun aJpegLogoOnWhiteBecomesATransparentWordmark() {
        val info = ArtistArtStore.saveLogo(ctx, "Wren Hollow", Uri.fromFile(file("wren.jpg", SyntheticLogos.opaque(0x141414, 0xFFFFFF), Bitmap.CompressFormat.JPEG)))!!
        assertClean(info.file, "keyed logo", slack = 2)
        assertTrue("dark letters: ${info.luma}", info.dark)
    }

    @Test fun theLibraryCleansLogosButNotPosters() = runBlocking {
        val matte = file("Wren Hollow_logo.png", SyntheticLogos.matte(0x202020, 30, matteRgb = 0xFFFFFF))
        val jpeg = file("Ash Meridian_logo.jpg", SyntheticLogos.opaque(0xF0F0F0, 0x000000), Bitmap.CompressFormat.JPEG)
        // a different picture from the logo: the same pixels would be the same file, skipped by its hash
        val poster = file("Ash Meridian_poster.jpg", SyntheticLogos.opaque(0x202020, 0xE8E8E8), Bitmap.CompressFormat.JPEG)
        assertEquals(3, c.artLibrary.add(listOf(Uri.fromFile(matte), Uri.fromFile(jpeg), Uri.fromFile(poster))).added)
        val items = c.db.artAssets().allNow()
        val m = items.single { it.name == "Wren Hollow" }
        assertClean(File(m.path), "library matte logo")
        assertEquals(boxW, m.width)
        val j = items.single { it.name == "Ash Meridian" && it.kind == ArtKind.Logo.name }
        assertTrue("keyed logo kept as PNG", j.path.endsWith(".png"))
        assertClean(File(j.path), "library jpeg logo", slack = 2)
        val p = items.single { it.kind == ArtKind.Poster.name }
        assertTrue(p.path.endsWith(".jpg"))
        assertEquals(SyntheticLogos.W, p.width)
    }

    @Test fun theOriginalOfACleanedLibraryLogoIsStillRecognised() = runBlocking {
        val raw = SyntheticLogos.matte(0xFFFFFF, 10)
        val original = file("Wren Hollow_logo.png", raw)
        c.artLibrary.add(listOf(Uri.fromFile(original)))
        // the same picture, re-encoded with a transparent margin: other bytes, so only the fingerprint can match
        val b = Bitmap.createBitmap(raw, SyntheticLogos.W, SyntheticLogos.H, Bitmap.Config.ARGB_8888)
        val padded = Bitmap.createBitmap(SyntheticLogos.W + 60, SyntheticLogos.H + 60, Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(b, 30f, 30f, null)
        val f = File(ctx.cacheDir, "picked/wren.webp")
        f.outputStream().use { padded.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
        assertEquals(Mirror.Duplicate, c.artLibrary.mirror("Wren Hollow", ArtKind.Logo, Uri.fromFile(original)))
        assertEquals(Mirror.Duplicate, c.artLibrary.mirror("Wren Hollow", ArtKind.Logo, Uri.fromFile(f)))
        assertEquals(1, c.db.artAssets().allNow().size)
    }

    @Test fun logosStoredBeforeAreCleanedOnceKeepingTheirDate() = runBlocking {
        // An artist logo stored by an older version: the matte left inside the old >12 trim, kept as it was.
        val raw = SyntheticLogos.matte(0xFFFFFF, 9)
        val oldFile = File(ArtistArtStore.dir(ctx), "l_old.png").also { f ->
            f.outputStream().use { Bitmap.createBitmap(raw, SyntheticLogos.W, SyntheticLogos.H, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        c.db.artistLogos().put(ArtistLogo("Wren Hollow", oldFile.absolutePath, 3.0f, 0.2f, 1234L))
        val clean = ArtistArtStore.saveLogo(ctx, "Ash Meridian", Uri.fromFile(file("ash.png", SyntheticLogos.matte(0xFFFFFF, 0))))!!
        c.db.artistLogos().put(ArtistLogo("Ash Meridian", clean.file.absolutePath, clean.aspect, clean.luma, 99L))
        // and a library logo from before
        val libOld = File(ArtLibraryStore.dir(ctx), "a_old.png").also { oldFile.copyTo(it) }
        val id = c.db.artAssets().insert(
            ArtAsset(name = "Wren Hollow", kind = ArtKind.Logo.name, path = libOld.absolutePath, width = SyntheticLogos.W, height = SyntheticLogos.H, luma = 0.2f, hash = "abc", addedAt = 5, updatedAt = 6),
        )

        assertEquals(1, c.library.cleanStoredLogos())
        val row = c.db.artistLogos().get("Wren Hollow")!!
        assertNotEquals(oldFile.absolutePath, row.path)
        assertFalse("old file removed", oldFile.exists())
        assertClean(File(row.path), "migrated artist logo")
        assertEquals(boxW.toFloat() / boxH, row.aspect, 0.01f)
        assertTrue(row.luma > 0.9f)
        assertEquals(1234L, row.updatedAt)
        assertEquals(clean.file.absolutePath, c.db.artistLogos().get("Ash Meridian")!!.path)
        assertEquals(0, c.library.cleanStoredLogos())

        assertEquals(1, c.artLibrary.cleanLogosNow())
        val a = c.db.artAssets().get(id)!!
        assertNotEquals(libOld.absolutePath, a.path)
        assertFalse(libOld.exists())
        assertClean(File(a.path), "migrated library logo")
        assertEquals(boxW, a.width)
        assertEquals("abc", a.hash)
        assertEquals(6L, a.updatedAt)
        assertEquals(0, c.artLibrary.cleanLogosNow())
    }

    @Test fun theStartupPassRunsOnlyOnce() = runBlocking {
        val sp = ctx.getSharedPreferences("startup", android.content.Context.MODE_PRIVATE)
        sp.edit().remove("art_library_logo_cleanup_v1").commit()
        val raw = SyntheticLogos.matte(0xFFFFFF, 12)
        val f = File(ArtLibraryStore.dir(ctx), "a_old2.png").also { out ->
            out.outputStream().use { Bitmap.createBitmap(raw, SyntheticLogos.W, SyntheticLogos.H, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val id = c.db.artAssets().insert(ArtAsset(name = "Wren Hollow", kind = ArtKind.Logo.name, path = f.absolutePath, width = 1, height = 1, luma = 0f, hash = "h", addedAt = 1, updatedAt = 1))
        c.artLibrary.runLogoCleanup()
        assertEquals("done", sp.getString("art_library_logo_cleanup_v1", null))
        val first = c.db.artAssets().get(id)!!.path
        assertNotEquals(f.absolutePath, first)
        c.artLibrary.runLogoCleanup()
        assertEquals(first, c.db.artAssets().get(id)!!.path)
        assertTrue(abs(c.db.artAssets().get(id)!!.width - boxW) == 0)
    }
}
