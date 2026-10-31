package com.novcloud.echo.data

import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.domain.StatsEngine
import com.novcloud.echo.domain.StatsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional names only. */
class NameVariantsTest {
    private val base = "云笙（白小舟）"

    private fun song(
        id: Long, artist: String, album: String = "雾港", albumId: Long = 1, title: String = "Song $id",
        albumArtist: String = "", genre: String = "",
    ) = Song(id, title, artist, album, albumId, albumArtist, genre, 200_000, id.toInt(), 1, 2020, id, "/m/$id.mp3", "/m", "audio/mpeg", 1000, mediaId = id)

    private fun assertOneArtist(vararg spellings: String) {
        val lib = Library.build(spellings.mapIndexed { i, a -> song(i + 1L, a) })
        assertEquals(spellings.joinToString { Names.codepoints(it) }, 1, lib.artists.size)
        assertEquals(spellings.size, lib.artists[0].songs.size)
        spellings.forEach { assertSame(lib.artists[0], lib.artist(it)) }
    }

    @Test fun trailingNulFromId3Terminator() = assertOneArtist(base, "$base\u0000")
    @Test fun nulSeparatedMultiValueKeepsArtistTogether() = assertOneArtist(base, "$base\u0000\u0000")
    @Test fun byteOrderMarkFromUtf8OrUtf16Frames() = assertOneArtist(base, "\uFEFF$base", "$base\uFEFF")
    @Test fun zeroWidthCharacters() = assertOneArtist(base, "云\u200B笙（白小舟）", "云笙\u200C（白小舟）", "云笙（白小舟）\u200D", "\u2060$base")
    @Test fun controlCharacters() = assertOneArtist(base, "$base\u0001", "\u001F$base", "$base\u0085")
    @Test fun directionMarksAndVariationSelectors() = assertOneArtist(base, "\u200E$base", "云\uFE00笙（白小舟）", "云\uDB40\uDD00笙（白小舟）")
    @Test fun fullAndHalfWidthBrackets() = assertOneArtist(base, "云笙(白小舟)", "云笙 (白小舟)", "云笙 （白小舟）", "云笙【白小舟】", "云笙[白小舟]")
    @Test fun spacesOfEveryKind() = assertOneArtist("Nova Lark", "Nova  Lark", " Nova Lark ", "Nova\u00A0Lark", "Nova\u3000Lark", "Nova\tLark")
    @Test fun latinCase() = assertOneArtist("Nova Lark", "NOVA LARK", "nova lark")
    @Test fun fullWidthLatin() = assertOneArtist("Nova Lark", "Ｎｏｖａ Ｌａｒｋ")
    @Test fun nfcAndNfd() = assertOneArtist("Élan Rue", "E\u0301lan Rue")
    @Test fun cjkCompatibilityIdeographsAndRadicals() = assertOneArtist("\u8C48人", "\uF900\u2F08")

    @Test
    fun nulSeparatedMultipleValuesSplit() {
        val lib = Library.build(listOf(song(1, "Nova Lark\u0000Ivo Fen"), song(2, "$base\u0000$base"), song(3, "\u0000Nova Lark")))
        assertEquals(listOf("Ivo Fen", "Nova Lark", base).sorted(), lib.artists.map { it.name }.sorted())
        assertEquals(2, lib.artist("Nova Lark")!!.songs.size)
        assertEquals("Nova Lark/Ivo Fen", lib.songById[1]!!.artist)
    }

    @Test
    fun differentNamesStayApart() {
        val lib = Library.build(listOf(song(1, "云笙"), song(2, base), song(3, "Nova Lark"), song(4, "Nova Larks"), song(5, "Nova (Lark)")))
        assertEquals(5, lib.artists.size)
    }

    @Test
    fun displayNameIsTheMostCommonCleanSpelling() {
        val lib = Library.build(listOf(song(1, base), song(2, "$base\u0000"), song(3, "\uFEFF$base"), song(4, "云笙 (白小舟)")))
        assertEquals(1, lib.artists.size)
        assertEquals(base, lib.artists[0].name)
        assertFalse(Names.hasInvisible(lib.artists[0].name))
    }

    @Test
    fun displayChoiceDoesNotDependOnOrder() {
        val a = Library.build(listOf(song(1, "云笙(白小舟)"), song(2, base))).artists.single().name
        val b = Library.build(listOf(song(2, base), song(1, "云笙(白小舟)"))).artists.single().name
        assertEquals(a, b)
    }

    @Test
    fun titlesAlbumsAndGenresAreCleanedOnBuild() {
        val s = song(1, "$base\u0000", album = "\uFEFF雾港\u200B", title = "潮汐\u0000", albumArtist = "$base\u0000", genre = "Pop\u0000")
        val c = Library.build(listOf(s)).songs.single()
        assertEquals("潮汐", c.title)
        assertEquals("雾港", c.album)
        assertEquals(base, c.artist)
        assertEquals(base, c.albumArtist)
        assertEquals("Pop", c.genre)
        val plain = song(2, base)
        assertSame(plain, plain.cleaned())
    }

    @Test
    fun albumSplitAcrossIdsBySpellingIsOneAlbum() {
        val lib = Library.build(
            listOf(
                song(1, base, album = "雾港", albumId = 10),
                song(2, base, album = "雾港", albumId = 10),
                song(3, "$base\uFEFF", album = "雾港\u0000", albumId = 11),
                song(4, base, album = "雾港", albumId = 12, albumArtist = "云笙 (白小舟)"),
            ),
        )
        assertEquals(1, lib.albums.size)
        val album = lib.albums.single()
        assertEquals(10L, album.id)
        assertEquals(4, album.songs.size)
        assertEquals(base, album.artist)
        assertSame(album, lib.albumById[11])
        assertSame(album, lib.albumById[12])
        assertEquals(listOf(album), lib.artist(base)!!.albums)
    }

    @Test
    fun sameAlbumTitleByDifferentArtistsStaysApart() {
        val lib = Library.build(listOf(song(1, "云笙", album = "雾港", albumId = 1), song(2, "Nova Lark", album = "雾港", albumId = 2)))
        assertEquals(2, lib.albums.size)
    }

    @Test
    fun unknownAlbumsAreNotFolded() {
        val lib = Library.build(listOf(song(1, "云笙", album = UNKNOWN, albumId = 1), song(2, "云笙", album = UNKNOWN, albumId = 2)))
        assertEquals(2, lib.albums.size)
    }

    @Test
    fun albumArtistSpellingDoesNotMakeASecondArtist() {
        val lib = Library.build(listOf(song(1, base, albumArtist = "云笙 (白小舟)\u0000"), song(2, base, albumArtist = base)))
        assertEquals(1, lib.artists.size)
        assertEquals(base, lib.albums.single().artist)
    }

    @Test
    fun genresFoldTogether() {
        val lib = Library.build(listOf(song(1, "a", genre = "Dream Pop"), song(2, "b", genre = "dream pop\u0000"), song(3, "c", genre = "Ｄｒｅａｍ Pop")))
        assertEquals(1, lib.genres.size)
        assertEquals(3, lib.genres[0].songs.size)
        assertNotNull(lib.genre("DREAM POP"))
    }

    @Test
    fun splitArtistsHandlesWidthAndSpacingVariants() {
        assertEquals(listOf(base), splitArtists(base))
        assertEquals(listOf("云笙(白小舟)"), splitArtists("云笙(白小舟)"))
        assertEquals(listOf("云笙", "白小舟"), splitArtists("云笙／白小舟"))
        assertEquals(listOf("云笙", "白小舟"), splitArtists("云笙\u3000x\u3000白小舟"))
        assertEquals(listOf("云笙", "白小舟"), splitArtists("云笙 × 白小舟"))
        assertEquals(listOf("Nova Lark", "Ivo Fen"), splitArtists("Nova Lark feat. Ivo Fen"))
        assertEquals(listOf("Max Rook"), splitArtists("Max Rook"))
        assertEquals(listOf("Lexx"), splitArtists("Lexx"))
    }

    @Test
    fun oneSongListingTwoSpellingsCountsOnce() {
        val lib = Library.build(listOf(song(1, "$base/云笙(白小舟)")))
        assertEquals(1, lib.artists.size)
        assertEquals(1, lib.artists[0].songs.size)
    }

    @Test
    fun keyIsStableAndFolds() {
        assertEquals(Names.key(base), Names.key("云笙 (白小舟)"))
        assertEquals("nova lark", Names.key("  NOVA\u3000Lark\u0000"))
        assertEquals(UNKNOWN, Names.key(UNKNOWN))
        assertNotEquals(Names.key("云笙"), Names.key(base))
    }

    @Test
    fun cleanKeepsVisibleText() {
        assertEquals(base, Names.clean("\uFEFF$base\u0000"))
        assertEquals("Nova Lark", Names.clean(" Nova \u00A0 Lark "))
        assertEquals("云笙\u3000白小舟", Names.clean("云笙\u3000白小舟"))
        assertEquals("Élan", Names.clean("E\u0301lan"))
        val s = "Nova Lark"
        assertSame(s, Names.clean(s))
    }

    @Test
    fun variantsAreReportedWithCodepoints() {
        val merges = NameVariants.find(listOf(song(1, base), song(2, "$base\u0000"), song(3, "Nova Lark")))
        val m = merges.single { it.kind == "artist" }
        assertEquals(2, m.variants.size)
        val line = NameVariants.describe(m)
        assertTrue(line, line.contains("FF09 0000]"))
        assertTrue(line, line.contains("4E91 7B19 FF08 767D 5C0F 821F FF09]"))
        assertTrue(NameVariants.find(listOf(song(1, base), song(2, base))).isEmpty())
    }

    @Test
    fun posterSavedUnderEitherSpellingIsFound() {
        val rows = listOf(ArtistImage("$base\u0000", "/a/p1.jpg", 100))
        val map = ArtistArtRows.latestByKey(rows, { it.artist }, { it.updatedAt })
        assertEquals("/a/p1.jpg", map.forName(base)?.path)
        assertEquals("/a/p1.jpg", map.forName("云笙 (白小舟)")?.path)
        assertTrue(ArtistArtRows.superseded(rows, { it.artist }, { it.updatedAt }).isEmpty())
        val lib = Library.build(listOf(song(1, base), song(2, "$base\u0000")))
        val custom = map.mapValues { it.value.path }
        assertEquals("/a/p1.jpg", custom.forName(lib.artists.single().name))
    }

    @Test
    fun posterMigrationKeepsTheMostRecentlySet() {
        val older = ArtistImage(base, "/a/old.jpg", 100)
        val newer = ArtistImage("$base\uFEFF", "/a/new.jpg", 200)
        val other = ArtistImage("Nova Lark", "/a/nova.jpg", 50)
        val rows = listOf(newer, older, other)
        val map = ArtistArtRows.latestByKey(rows, { it.artist }, { it.updatedAt })
        assertEquals(2, map.size)
        assertEquals("/a/new.jpg", map.forName(base)?.path)
        assertEquals(listOf(older), ArtistArtRows.superseded(rows, { it.artist }, { it.updatedAt }))
        assertEquals(listOf(older), ArtistArtRows.superseded(listOf(older, newer, other), { it.artist }, { it.updatedAt }))
    }

    @Test
    fun artFileMatcherIgnoresWidthAndInvisibles() {
        val index = ArtistFileMatcher.index(listOf("Nova Lark"))
        assertEquals("Nova Lark", ArtistFileMatcher.match("Ｎｏｖａ_Ｌａｒｋ_poster.jpg", index)?.artist)
    }

    @Test
    fun searchFindsAnySpelling() {
        val lib = Library.build(listOf(song(1, base), song(2, "$base\u0000"), song(3, "Nova Lark")))
        assertEquals(1, lib.search.query("云笙(白小舟").artists.size)
        assertEquals(1, lib.search.query("ＮＯＶＡ").artists.size)
    }

    @Test
    fun statsCountOneArtistAcrossSpellings() {
        val lib = Library.build(listOf(song(1, base), song(2, "$base\u0000")))
        val now = 1_700_000_000_000L
        fun ev(id: Long, artist: String, at: Long) = PlayEvent(songId = id, title = "t", artist = artist, album = "雾港", albumId = 1, genre = "", startedAt = at, listenedMs = 60_000, songDurationMs = 200_000, completed = true)
        val events = listOf(ev(1, base, now - 3_600_000), ev(2, "$base\u0000", now - 7_200_000), ev(2, "\uFEFF云笙 (白小舟)", now - 9_000_000))
        val stats = StatsEngine.compute(events, lib, StatsRange.All, now)
        assertEquals(1, stats.artists)
        assertEquals(base, stats.topArtists.single().title)
        assertEquals(3, stats.topArtists.single().plays)
    }

    @Test
    fun backupMergeTreatsSpellingsAsOneArtist() {
        val here = BackupData(artists = listOf(BackupArtist(base, poster = "p.jpg")))
        val inc = BackupData(artists = listOf(BackupArtist("$base\u0000", poster = "q.jpg", logo = "l.png")))
        val m = BackupMerge.combine(here, inc, ImportMode.Merge)
        assertEquals(1, m.artists.size)
        assertEquals(BackupArtist(base, "p.jpg", "l.png"), m.artists.single())
    }
}
