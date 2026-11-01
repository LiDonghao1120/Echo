package com.novcloud.echo.data

import com.novcloud.echo.data.db.ArtAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Fictional artists only. */
class ArtLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun table() {
        Pinyin.install { File("src/main/assets/pinyin.bin").inputStream() }
    }

    private fun parsed(file: String) = ArtLibraryNames.parse(file).let { it.name to it.kind }

    @Test
    fun fileNamesBecomeArtistNames() {
        val logo = ArtKind.Logo
        val poster = ArtKind.Poster
        mapOf(
            "Nova_Lark_logo.png" to ("Nova Lark" to logo),
            "Nova Lark.png" to ("Nova Lark" to logo),
            "Marlow & the Tides - Logo.png" to ("Marlow & the Tides" to logo),
            "Pale Orchid (2).png" to ("Pale Orchid" to logo),
            "Pale Orchid（3）.PNG" to ("Pale Orchid" to logo),
            "Otis Fenwick_wordmark.png" to ("Otis Fenwick" to logo),
            "Otis Fenwick Logo 2.png" to ("Otis Fenwick" to logo),
            "Otis Fenwick (Logo).webp" to ("Otis Fenwick" to logo),
            "logo_Nova Lark.png" to ("Nova Lark" to logo),
            "温屿_logo.png" to ("温屿" to logo),
            "温屿标志.png" to ("温屿" to logo),
            "云笙（白小舟）-标志 (1).png" to ("云笙（白小舟）" to logo),
            "Juniper Vale_poster.jpg" to ("Juniper Vale" to poster),
            "Juniper Vale - Background.jpeg" to ("Juniper Vale" to poster),
            "温屿_海报.jpg" to ("温屿" to poster),
            "Nova Lark copy.png" to ("Nova Lark" to logo),
            "Nova Lark - 副本.png" to ("Nova Lark" to logo),
            "Nova_Lark_1.png" to ("Nova Lark" to logo),
            "Nova Lark-3fa9c2d1e8.png" to ("Nova Lark" to logo),
            "Nova Lark_1730000000123.png" to ("Nova Lark" to logo),
            "Nova Lark.logo.png" to ("Nova Lark" to logo),
            "Maroon Static 5.png" to ("Maroon Static 5" to logo),
            "Sum 41ish_logo.png" to ("Sum 41ish" to logo),
            "Glass-182_logo.png" to ("Glass-182" to logo),
            "Glass-182.png" to ("Glass-182" to logo),
            "M.I.L.O..png" to ("M.I.L.O." to logo),
            "Ivy Calder\u200B_logo.png" to ("Ivy Calder" to logo),
            "Logo.png" to ("Logo" to logo),
            "/storage/emulated/0/Pictures/Kira_Nox_logo.png" to ("Kira Nox" to logo),
        ).forEach { (file, want) -> assertEquals(file, want, parsed(file)) }
    }

    @Test
    fun invisibleAndWidthVariantsMatchOneArtist() {
        val k = ArtLibraryMatch.key("Nova Lark")
        listOf("Nova Lark\u0000", "\uFEFFNova Lark", "Nova\u200B Lark", "Ｎｏｖａ Ｌａｒｋ", "nova_lark", "NOVA-LARK", "Nova  Lark ", "Nova Lark\uFE0F")
            .forEach { assertEquals(it, k, ArtLibraryMatch.key(it)) }
        assertEquals(ArtLibraryMatch.key("云笙（白小舟）"), ArtLibraryMatch.key("云笙(白小舟)\u0000"))
        assertFalse(ArtLibraryMatch.key("Nova Lark") == ArtLibraryMatch.key("Nova Larks"))
        assertTrue(ArtLibraryMatch.key("÷").isNotEmpty())
    }

    private var nextId = 1L
    private fun asset(name: String, kind: ArtKind = ArtKind.Logo, at: Long = 1, hash: String = "h${nextId}") =
        ArtAsset(id = nextId++, name = name, kind = kind.name, path = "/x/$name.png", width = 400, height = 100, luma = 0.9f, hash = hash, addedAt = at, updatedAt = at)

    @Test
    fun autoApplyFillsOnlyWhatIsMissing() {
        val ivyLogo = asset("Ivy Calder")
        val ivyPoster = asset("Ivy_Calder", ArtKind.Poster)
        val nova = asset("Ｎｏｖａ Ｌａｒｋ")
        val stranger = asset("Somebody Else")
        val plan = ArtLibraryMatch.plan(
            artists = listOf("Ivy Calder", "Nova Lark\u200B", "Kira Nox"),
            posters = setOf(Names.key("Ivy Calder")),
            logos = setOf(Names.key("Kira Nox")),
            assets = listOf(ivyLogo, ivyPoster, nova, stranger),
        )
        assertEquals(
            listOf(Triple("Ivy Calder", ArtKind.Logo, ivyLogo.id), Triple("Nova Lark\u200B", ArtKind.Logo, nova.id)),
            plan.map { Triple(it.artist, it.kind, it.asset.id) },
        )
    }

    @Test
    fun autoApplyNeverOverwritesAndRespectsRemovals() {
        val old = asset("Kira Nox", at = 10)
        val newer = asset("kira nox", at = 20)
        val poster = asset("Kira Nox", ArtKind.Poster, at = 5)
        val all = listOf(old, newer, poster)
        assertTrue(ArtLibraryMatch.plan(listOf("Kira Nox"), setOf("kira nox"), setOf("kira nox"), all).isEmpty())
        assertEquals(newer.id, ArtLibraryMatch.plan(listOf("Kira Nox"), setOf("kira nox"), emptySet(), all).single().asset.id)
        val declined = setOf(ArtLibraryMatch.declined(ArtKind.Logo, "Kira Nox", newer.hash))
        assertEquals(old.id, ArtLibraryMatch.plan(listOf("Kira Nox"), setOf("kira nox"), emptySet(), all, declined).single().asset.id)
        val both = declined + ArtLibraryMatch.declined(ArtKind.Logo, "KIRA NOX", old.hash)
        assertTrue(ArtLibraryMatch.plan(listOf("Kira Nox"), setOf("kira nox"), emptySet(), all, both).isEmpty())
        assertTrue(ArtLibraryMatch.plan(listOf(UNKNOWN, ""), emptySet(), emptySet(), listOf(asset(UNKNOWN))).isEmpty())
        assertEquals(1, ArtLibraryMatch.plan(listOf("Kira Nox", "Kira Nox\u0000"), setOf("kira nox"), emptySet(), all).size)
    }

    @Test
    fun pickerPutsTheArtistsOwnImagesFirstAndSearchesPinyin() {
        val a = asset("Nova Lark")
        val b = asset("Marlow & the Tides")
        val c = asset("温屿", ArtKind.Poster)
        val d = asset("nova_lark", ArtKind.Poster)
        val e = asset("Ａlpha Kid")
        val list = listOf(a, b, c, d, e)
        val forNova = ArtLibraryMatch.forPicker(list, "Nova Lark", ArtKind.Poster)
        assertEquals(listOf(d.id, a.id), forNova.take(2).map { it.first.id })
        assertTrue(forNova.take(2).all { it.second })
        assertFalse(forNova.drop(2).any { it.second })
        assertEquals(c.id, forNova[2].first.id)
        assertEquals(listOf(c.id), ArtLibraryMatch.forPicker(list, "Nova Lark", ArtKind.Logo, "wy").map { it.first.id })
        assertEquals(listOf(c.id), ArtLibraryMatch.forPicker(list, "Nova Lark", ArtKind.Logo, "wenyu").map { it.first.id })
        assertEquals(setOf(a.id, d.id), ArtLibraryMatch.forPicker(list, "x", ArtKind.Logo, "novala").map { it.first.id }.toSet())
        assertEquals(listOf(b.id), ArtLibraryMatch.forPicker(list, "x", ArtKind.Logo, "marlow&").map { it.first.id })
    }

    @Test
    fun backupKeepsTheLibraryAndMergesByContent() {
        val lib = listOf(
            BackupAsset("Nova Lark", "Logo", "lib_0.png", 772, 287, 0.92f, "aa", 1, 2),
            BackupAsset("Juniper Vale", "Poster", "lib_1.jpg", 1200, 800, 0.4f, "bb", 3, 4),
        )
        val data = BackupData(createdAt = 5, appVersion = "0.11.0", library = lib, settings = BackupSettings(artAutoApply = false))
        val png = tmp.newFile("a.png").apply { writeBytes(ByteArray(300) { (it * 7).toByte() }) }
        val jpg = tmp.newFile("b.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val out = ByteArrayOutputStream()
        BackupCodec.write(data, mapOf("lib_0.png" to png, "lib_1.jpg" to jpg), out)
        val dir = tmp.newFolder()
        val back = BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir)
        assertEquals(data, back)
        assertTrue(File(dir, "lib_0.png").readBytes().contentEquals(png.readBytes()))
        assertEquals(3L, File(dir, "lib_1.jpg").length())

        val here = listOf(BackupAsset("Nova Lark", "Logo", "/data/a.png", hash = "aa"))
        assertEquals(listOf("bb"), BackupMerge.libraryToAdd(here, lib).map { it.hash })
        val merged = BackupMerge.combine(BackupData(library = here), back, ImportMode.Merge)
        assertEquals(listOf("aa", "bb"), merged.library.map { it.hash })
        assertEquals(lib, BackupMerge.combine(BackupData(library = here), back, ImportMode.Replace).library)
        val twice = lib + lib.first().copy(name = "again")
        assertEquals(2, BackupMerge.libraryToAdd(emptyList(), twice).size)
    }

    @Test
    fun oldBackupsWithoutALibraryStillRead() {
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupData(createdAt = 1), emptyMap(), out)
        val back = BackupCodec.read(ByteArrayInputStream(out.toByteArray()), tmp.newFolder())
        assertTrue(back.library.isEmpty())
        assertEquals(null, back.settings.artAutoApply)
    }
}
