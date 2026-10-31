package com.novcloud.echo.upgrade

import com.novcloud.echo.data.Library
import com.novcloud.echo.data.LibrarySnapshot
import com.novcloud.echo.data.Names
import com.novcloud.echo.data.SnapshotStore
import com.novcloud.echo.data.splitArtists
import com.novcloud.echo.domain.Recommender
import com.novcloud.echo.domain.StatsEngine
import com.novcloud.echo.domain.StatsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Opening v0.10.0's saved library and history with the v0.10.x model: everything Compose lists key by must be unique
 * (a repeated LazyList/LazyGrid key throws during the first frame).
 */
class UpgradeFromV0100Test {
    private val now = 1_790_000_000_000L

    private fun <T> assertUnique(what: String, keys: List<T>) {
        val dup = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("$what has repeated keys: $dup", dup.isEmpty())
    }

    private fun restored(): Library {
        val dir = Files.createTempDirectory("snap").toFile()
        SnapshotStore(dir).save(LibrarySnapshot(V0100Library.songs, emptyList(), emptyMap(), emptyMap()))
        val snap = SnapshotStore(dir).load()!!
        assertEquals(V0100Library.songs, snap.songs)
        return Library.build(snap.songs)
    }

    @Test
    fun artistSeparatorsCompileAndSplitUnicodeSpaces() {
        assertEquals(listOf("Ivo Fen", "Nova Lark"), splitArtists("Ivo Fen\u00A0x\u00A0Nova Lark"))
        assertEquals(listOf("Nova Lark", "Ivo Fen"), splitArtists("Nova Lark\u3000feat.\u3000Ivo Fen"))
        assertEquals(listOf("A", "B", "C"), splitArtists("A／B、C"))
        assertEquals(listOf("Max Power"), splitArtists("Max Power"))
    }

    @Test
    fun libraryListsHaveUniqueKeys() {
        val lib = restored()
        assertUnique("songs", lib.songs.map { it.id })
        assertUnique("albums", lib.albums.map { it.id })
        assertUnique("artists", lib.artists.map { it.name })
        assertUnique("genres", lib.genres.map { it.name })
        assertUnique("folders", lib.folders.map { it.path })
        lib.artists.forEach { a ->
            assertUnique("albums of ${a.name}", a.albums.map { it.id })
            assertUnique("songs of ${a.name}", a.songs.map { it.id })
        }
        lib.albums.forEach { assertUnique("songs of album ${it.id}", it.songs.map { s -> s.id }) }
        val one = lib.artist(V0100Library.BASE)!!
        V0100Library.spellings.forEach { assertEquals(one, lib.artist(it)) }
        assertEquals(1, lib.artists.count { Names.key(it.name) == Names.key(V0100Library.BASE) })
        assertEquals(1, lib.albums.count { it.title == "雾港" && Names.key(it.artist) == Names.key(V0100Library.BASE) })
        (10L..13L).forEach { assertEquals("old album id $it", lib.albumById[10]!!.id, lib.albumById[it]!!.id) }
        assertNotNull(lib.artist("Nova Lark"))
        assertNotNull(lib.artist("Ivo Fen"))
        assertEquals(1, lib.genres.count { Names.key(it.name) == "pop" })
    }

    @Test
    fun homeAndStatsHaveUniqueKeys() {
        val lib = restored()
        val events = V0100Library.events(now)
        val home = Recommender.build(lib, events, now)
        assertTrue(home.ready)
        assertUnique("mixes", (listOfNotNull(home.daily) + home.mixes).map { it.id })
        home.mixes.forEach { m -> assertUnique("mix ${m.id}", m.songs.map { it.id }) }
        assertUnique("recent", home.recent.map { it.id })
        assertUnique("home artists", home.artists.map { it.name })
        assertUnique("most played", home.mostPlayed.map { it.first.id })
        assertUnique("recently added", home.recentlyAdded.map { it.id })
        StatsRange.entries.forEach { r ->
            val s = StatsEngine.compute(events, lib, r, now)
            assertUnique("top artists $r", s.topArtists.map { it.key })
            assertUnique("top songs $r", s.topSongs.map { it.key })
            assertUnique("top albums $r", s.topAlbums.map { it.key })
            assertUnique("top genres $r", s.topGenres.map { it.key })
            assertTrue(s.topArtists.none { '\u0000' in it.title || '\uFEFF' in it.title })
        }
        val all = StatsEngine.compute(events, lib, StatsRange.All, now)
        assertTrue(all.topArtists.none { it.title == "Nova LarkIvo Fen" })
    }
}
