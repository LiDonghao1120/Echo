package com.novcloud.echo.data

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.screenshots.Samples
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The artwork library on a device: copies, transparency, artists' own copies, auto-apply and backups. Fictional artists. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ArtLibraryStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val c get() = (ctx.applicationContext as EchoApp).container

    private fun picked(resource: String, name: String): File {
        val dir = File(ctx.cacheDir, "picked").apply { mkdirs() }
        return File(dir, name).also { f -> javaClass.classLoader!!.getResourceAsStream(resource)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
    }

    @Test
    fun deletingTheOriginalLeavesTheLibraryCopyWithItsTransparency() = runBlocking {
        val logo = picked("samples/library/Nova_Lark_logo.png", "Nova_Lark_logo.png")
        val poster = picked("samples/library/Juniper Vale_poster.jpg", "Juniper Vale_poster.jpg")
        val r = c.artLibrary.add(listOf(Uri.fromFile(logo), Uri.fromFile(poster)))
        assertEquals(LibraryAddReport(2, 0, 0), r)
        assertTrue(logo.delete())
        assertTrue(poster.delete())

        val items = c.db.artAssets().allNow()
        val l = items.single { it.name == "Nova Lark" }
        val p = items.single { it.name == "Juniper Vale" }
        assertEquals(ArtKind.Logo.name, l.kind)
        assertEquals(ArtKind.Poster.name, p.kind)
        assertTrue(File(l.path).exists() && File(l.path).parentFile!!.name == "artist_library")
        assertEquals("png", File(l.path).extension)
        assertEquals("jpg", File(p.path).extension)
        val bmp = BitmapFactory.decodeFile(l.path)
        assertTrue(bmp.hasAlpha())
        assertTrue(ArtLibraryStore.hasTransparency(bmp))
        assertEquals(bmp.width, l.width)
        assertTrue(l.luma > 0.5f)

        val again = picked("samples/library/Nova_Lark_logo.png", "Nova Lark (1).png")
        assertEquals(LibraryAddReport(0, 1, 0), c.artLibrary.add(listOf(Uri.fromFile(again))))
        val broken = File(ctx.cacheDir, "broken.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(LibraryAddReport(0, 0, 1), c.artLibrary.add(listOf(Uri.fromFile(broken))))
        assertEquals(2, c.db.artAssets().allNow().size)
    }

    @Test
    fun artistsKeepTheirCopyWhenTheLibraryImageIsDeleted() = runBlocking {
        val f = picked("samples/library/Otis Fenwick_wordmark.png", "Otis Fenwick_wordmark.png")
        c.artLibrary.add(listOf(Uri.fromFile(f)))
        val a = c.db.artAssets().allNow().single()
        assertEquals("Otis Fenwick", a.name)
        assertTrue(c.library.fillArt("Otis Fenwick", ArtKind.Logo, File(a.path)))
        c.artLibrary.delete(a.id)
        assertFalse(File(a.path).exists())
        val row = c.db.artistLogos().allNow().single { it.artist == "Otis Fenwick" }
        assertNotEquals(a.path, row.path)
        assertTrue(File(row.path).exists())
        assertTrue(BitmapFactory.decodeFile(row.path).hasAlpha())
    }

    @Test
    fun autoApplyFillsMissingArtAndNeverReplacesTheUsers() = runBlocking {
        c.library.publish(Samples.library)
        val mine = File(ctx.filesDir, "mine.png").apply { writeBytes(File(picked("samples/logo_kira.png", "k.png").path).readBytes()) }
        c.db.artistLogos().put(ArtistLogo("Kira Nox", mine.absolutePath, 3f, 0.9f, 1))
        c.artLibrary.add(
            listOf(
                Uri.fromFile(picked("samples/logo_ivy.png", "Kira Nox_logo.png")),
                Uri.fromFile(picked("samples/library/Otis Fenwick_wordmark.png", "otis_fenwick.png")),
                Uri.fromFile(picked("samples/library/Nova_Lark_logo.png", "Nova_Lark_logo.png")),
            ),
        )
        assertEquals(1, c.artLibrary.autoApplyNow())
        val logos = c.db.artistLogos().allNow().associateBy { it.artist }
        assertEquals(mine.absolutePath, logos.getValue("Kira Nox").path)
        assertTrue(File(logos.getValue("Otis Fenwick").path).exists())
        assertFalse("Nova Lark" in logos)
        assertEquals(0, c.artLibrary.autoApplyNow())

        c.prefs.setArtAutoApply(false)
        c.db.artistLogos().remove("Otis Fenwick")
        assertEquals(0, c.artLibrary.autoApplyNow())
        c.prefs.setArtAutoApply(true)
    }

    @Test
    fun backupRoundTripRestoresTheLibrary() = runBlocking {
        c.artLibrary.add(
            listOf(
                Uri.fromFile(picked("samples/library/温屿_logo.png", "温屿_logo.png")),
                Uri.fromFile(picked("samples/library/Juniper Vale_poster.jpg", "Juniper Vale_poster.jpg")),
            ),
        )
        val before = c.db.artAssets().allNow().sortedBy { it.name }
        val items = c.artLibrary.backupItems()
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupData(library = items.map { it.first }), items.associate { it.first.file to it.second }, out)
        val dir = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val back = BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir)

        assertEquals(0, c.artLibrary.restore(BackupMerge.libraryToAdd(before.map { BackupAsset(it.name, it.kind, it.path, hash = it.hash) }, back.library), dir))
        c.artLibrary.clearAll()
        assertTrue(c.db.artAssets().allNow().isEmpty())
        assertTrue(before.none { File(it.path).exists() })
        assertEquals(2, c.artLibrary.restore(back.library, dir))
        val after = c.db.artAssets().allNow().sortedBy { it.name }
        assertEquals(before.map { listOf(it.name, it.kind, it.hash, it.width, it.height) }, after.map { listOf(it.name, it.kind, it.hash, it.width, it.height) })
        assertTrue(after.all { File(it.path).exists() })
    }
}
