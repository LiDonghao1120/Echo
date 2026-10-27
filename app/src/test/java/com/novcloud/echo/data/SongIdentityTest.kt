package com.novcloud.echo.data

import com.novcloud.echo.data.db.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    private fun song(path: String, title: String = "Glasshouse", artist: String = "Ivy Calder", album: String = "Paper Satellites", duration: Long = 201_000, mediaId: Long = 0, doc: String = ""): Song {
        val key = if (doc.isNotEmpty()) SongKeys.ofDocument(doc) else SongKeys.of(path)
        return Song(
            SongKeys.id(key), title, artist, album, 1, "", "", duration, 1, 1, 2024, 0, path, path.substringBeforeLast('/'), "", 1,
            docUri = if (doc.isNotEmpty()) "content://x/$doc" else "", key = key, mediaId = mediaId,
        )
    }

    private fun ref(key: String, title: String = "Glasshouse", artist: String = "Ivy Calder", album: String = "Paper Satellites", duration: Long = 201_000) =
        SongRef(key, title, artist, album, duration, 0)

    @Test
    fun keyIgnoresVolumeCaseAndNormalization() {
        val k = SongKeys.of("/storage/emulated/0/Music/Ivy/Glasshouse.flac")
        assertEquals("p:music/ivy/glasshouse.flac", k)
        assertEquals(k, SongKeys.of("/storage/emulated/10/music/IVY/Glasshouse.FLAC"))
        assertEquals(k, SongKeys.of("/storage/1A2B-3C4D/Music/Ivy/Glasshouse.flac"))
        assertEquals(k, SongKeys.of("/sdcard/Music/Ivy/Glasshouse.flac"))
        assertEquals(SongKeys.of("/storage/emulated/0/音乐/温屿/白鸟.flac"), SongKeys.of("/storage/emulated/0/音乐/温屿/白鸟.flac"))
        val decomposed = "/storage/emulated/0/Music/Cafe\u0301.mp3"
        assertEquals(SongKeys.of("/storage/emulated/0/Music/Caf\u00e9.mp3"), SongKeys.of(decomposed))
    }

    @Test
    fun collidingRelativePathsStayDistinct() {
        val a = song("/storage/emulated/0/Music/a.mp3")
        val b = song("/storage/1A2B-3C4D/Music/a.mp3")
        val out = SongKeys.unique(listOf(b, a))
        assertEquals(2, out.map { it.key }.distinct().size)
        assertEquals(a.key, out.first { it.path == a.path }.key)
        assertNotEquals(a.id, out.first { it.path == b.path }.id)
    }

    @Test
    fun liveKeysStayAndGetTheirTagsRecorded() {
        val s = song("/storage/emulated/0/Music/a.flac")
        val plan = Rematch.plan(listOf(s.key), emptyMap(), Library.build(listOf(s)), now = 5)
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf(SongRef(s.key, s.title, s.artist, s.album, s.durationMs, 5)), plan.refs)
        val again = Rematch.plan(listOf(s.key), plan.refs.associateBy { it.key }, Library.build(listOf(s)))
        assertTrue(again.refs.isEmpty())
    }

    @Test
    fun legacyIdsMapToTheFileThatHadThem() {
        val store = song("/storage/emulated/0/Music/a.flac", mediaId = 42)
        val tree = song("/storage/1A2B-3C4D/Albums/b.flac", title = "Linen")
        val doc = song("Downloads/c.flac", title = "Driftwood", doc = "msf:77")
        val lib = Library.build(listOf(store, tree, doc))
        val legacyTree = SafPaths.stableId("/storage/1a2b-3c4d/albums/b.flac")
        val legacyDoc = SafPaths.stableId("msf:77")
        val plan = Rematch.plan(listOf("id:42", "id:$legacyTree", "id:$legacyDoc", "id:999"), emptyMap(), lib)
        assertEquals(mapOf("id:42" to store.key, "id:$legacyTree" to tree.key, "id:$legacyDoc" to doc.key), plan.moves)
    }

    @Test
    fun movedFileIsFoundByItsTags() {
        val old = SongKeys.of("/storage/emulated/0/Download/Glasshouse.flac")
        val moved = song("/storage/emulated/0/Music/Ivy Calder/01 Glasshouse.flac", duration = 201_900)
        val other = song("/storage/emulated/0/Music/other.flac", title = "Low Tide")
        val plan = Rematch.plan(listOf(old), mapOf(old to ref(old)), Library.build(listOf(moved, other)))
        assertEquals(mapOf(old to moved.key), plan.moves)
        assertTrue(plan.refs.any { it.key == moved.key })
    }

    @Test
    fun tagMatchToleratesCaseAndSpacing() {
        val old = SongKeys.of("/storage/emulated/0/x.flac")
        val moved = song("/storage/emulated/0/y.flac", title = "glasshouse ", artist = "Ivy  Calder")
        val plan = Rematch.plan(listOf(old), mapOf(old to ref(old)), Library.build(listOf(moved)))
        assertEquals(moved.key, plan.moves[old])
    }

    @Test
    fun ambiguousOrDistantMatchesAreLeftForLater() {
        val old = SongKeys.of("/storage/emulated/0/Old/Glasshouse.flac")
        val a = song("/storage/emulated/0/A/x.flac")
        val b = song("/storage/emulated/0/B/y.flac")
        assertTrue(Rematch.plan(listOf(old), mapOf(old to ref(old)), Library.build(listOf(a, b))).moves.isEmpty())
        val longer = song("/storage/emulated/0/A/Glasshouse.flac", duration = 260_000)
        assertTrue(Rematch.plan(listOf(old), mapOf(old to ref(old)), Library.build(listOf(longer))).moves.isEmpty())
        assertTrue(Rematch.plan(listOf(old), emptyMap(), Library.build(listOf(a))).moves.isEmpty())
    }

    @Test
    fun fileNameBreaksATie() {
        val old = SongKeys.of("/storage/emulated/0/Old/Glasshouse.flac")
        val a = song("/storage/emulated/0/A/Glasshouse.flac")
        val b = song("/storage/emulated/0/B/Glasshouse (copy).flac")
        assertEquals(a.key, Rematch.plan(listOf(old), mapOf(old to ref(old)), Library.build(listOf(a, b))).moves[old])
    }
}
