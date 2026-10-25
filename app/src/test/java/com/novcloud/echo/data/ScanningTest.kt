package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanningTest {
    private fun song(id: Long, path: String, title: String = "T$id", size: Long = id * 100) = Song(
        id, title, "A", "Al", 1, "", "", 200_000, 1, 1, 2020, 0, path, path.substringBeforeLast('/'), "audio/mpeg", size,
    )

    @Test
    fun rulesDefaultToMusicFlag() {
        val r = FolderRules(emptySet(), emptySet())
        assertTrue(r.accepts("/storage/emulated/0/Music", true))
        assertFalse(r.accepts("/storage/emulated/0/Recordings", false))
    }

    @Test
    fun includedFolderAcceptsNonMusicAndSubfolders() {
        val r = FolderRules(setOf("/storage/emulated/0/Download/"), emptySet())
        assertTrue(r.accepts("/storage/emulated/0/Download", false))
        assertTrue(r.accepts("/storage/emulated/0/Download/albums/x", false))
        assertFalse(r.accepts("/storage/emulated/0/Downloads", false))
    }

    @Test
    fun mostSpecificRuleWins() {
        val r = FolderRules(setOf("/sd/Music/Keep"), setOf("/sd/Music"))
        assertFalse(r.accepts("/sd/Music", true))
        assertFalse(r.accepts("/sd/Music/Other", true))
        assertTrue(r.accepts("/sd/Music/Keep/Sub", false))
        assertTrue(r.excludes("/sd/Music/Other"))
        assertFalse(r.excludes("/sd/Music/Keep"))
    }

    @Test
    fun safDocumentIdsMapToPaths() {
        val ext = "com.android.externalstorage.documents"
        assertEquals("/storage/emulated/0/Music/a.flac", SafPaths.pathOf(ext, "primary:Music/a.flac"))
        assertEquals("/storage/emulated/0", SafPaths.pathOf(ext, "primary:"))
        assertEquals("/storage/1A2B-3C4D/Songs", SafPaths.pathOf(ext, "1A2B-3C4D:Songs"))
        assertEquals("/storage/emulated/0/x.mp3", SafPaths.pathOf("com.android.providers.downloads.documents", "raw:/storage/emulated/0/x.mp3"))
        assertNull(SafPaths.pathOf("com.android.providers.downloads.documents", "msf:42"))
    }

    @Test
    fun stableIdsAreNegativeAndDeterministic() {
        val a = SafPaths.stableId("/storage/emulated/0/music/a.mp3")
        assertTrue(a < 0)
        assertEquals(a, SafPaths.stableId("/storage/emulated/0/music/a.mp3"))
        assertNotEquals(a, SafPaths.stableId("/storage/emulated/0/music/b.mp3"))
    }

    @Test
    fun dedupeByPathAndFingerprint() {
        val store = listOf(song(1, "/m/a.mp3"), song(2, "/m/b.mp3"))
        val extra = listOf(
            song(-1, "/M/A.mp3"),
            song(-2, "/other/b.mp3", title = "T2", size = 200),
            song(-3, "/other/c.mp3"),
        )
        val merged = Dedupe.merge(store, extra)
        assertEquals(listOf(1L, 2L, -3L), merged.map { it.id })
    }
}
