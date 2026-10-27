package com.novcloud.echo.data

import com.novcloud.echo.data.db.Favorite
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.db.SongAlias
import com.novcloud.echo.data.db.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val k1 = "p:music/ivy calder/glasshouse.flac"
    private val k2 = "p:音乐/温屿/白鸟.flac"

    private fun event(key: String, at: Long, title: String = "Glasshouse") =
        PlayEvent(songId = SongKeys.id(key), title = title, artist = "Ivy Calder", album = "Paper Satellites", albumId = 3, genre = "Pop",
            startedAt = at, listenedMs = 180_000, songDurationMs = 201_000, completed = true, songKey = key)

    private val sample = BackupData(
        createdAt = 1_760_000_000_000,
        appVersion = "0.7.0",
        favorites = listOf(Favorite(k1, 10), Favorite(k2, 20)),
        playlists = listOf(BackupPlaylist("夜路", 5, listOf(BackupEntry(k2, 0, 6), BackupEntry(k1, 1, 7)))),
        events = listOf(event(k1, 1000), event(k2, 2000, "白鸟")),
        refs = listOf(SongRef(k1, "Glasshouse", "Ivy Calder", "Paper Satellites", 201_000, 9), SongRef(k2, "白鸟", "温屿", "北岸", 233_000, 9)),
        aliases = listOf(SongAlias("id:42", k1)),
        settings = BackupSettings("Dark", 15, "artist", false, setOf("/storage/emulated/0/Music"), setOf("/storage/emulated/0/Ringtones"), setOf("content://tree/1"), "content://tree/2", lyricsByWord = false),
        artists = listOf(BackupArtist("Ivy Calder", "poster_0.jpg", "logo_0.png"), BackupArtist("温屿", null, "logo_1.png")),
        lyrics = listOf(
            com.novcloud.echo.data.db.LyricOverride(k1, offsetMs = 300, updatedAt = 11),
            com.novcloud.echo.data.db.LyricOverride(k2, offsetMs = -200, fileName = "白鸟.lrc", text = "[00:01.00]白鸟飞过\n[00:03.00]北岸", updatedAt = 12),
        ),
    )

    private fun roundTrip(data: BackupData, art: Map<String, File>): Pair<BackupData, File> {
        val out = ByteArrayOutputStream()
        BackupCodec.write(data, art, out)
        val dir = tmp.newFolder()
        return BackupCodec.read(ByteArrayInputStream(out.toByteArray()), dir) to dir
    }

    @Test
    fun roundTripKeepsEverything() {
        val poster = tmp.newFile("p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val logo = tmp.newFile("l.png").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val (back, dir) = roundTrip(sample, mapOf("poster_0.jpg" to poster, "logo_0.png" to logo, "logo_1.png" to logo))
        assertEquals(sample, back)
        assertTrue(File(dir, "poster_0.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(5000L, File(dir, "logo_1.png").length())
    }

    @Test
    fun emptyAndUnsetValuesSurvive() {
        val (back, _) = roundTrip(BackupData(createdAt = 1), emptyMap())
        assertEquals(BackupData(createdAt = 1), back)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun rejects(bytes: ByteArray) {
        try {
            BackupCodec.read(ByteArrayInputStream(bytes), tmp.newFolder())
            fail("accepted")
        } catch (_: BackupException) {
        }
    }

    @Test
    fun rejectsForeignNewerAndBrokenFiles() {
        rejects("not a zip".toByteArray())
        rejects(zip("data.json" to "{}"))
        rejects(zip("manifest.json" to """{"format":"other","version":1}""", "data.json" to "{}"))
        rejects(zip("manifest.json" to """{"format":"echo-backup","version":99}""", "data.json" to "{}"))
        rejects(zip("manifest.json" to """{"format":"echo-backup","version":1}"""))
    }

    @Test
    fun ignoresArtEntriesThatEscapeTheFolder() {
        val dir = tmp.newFolder("x")
        val bytes = zip("manifest.json" to """{"format":"echo-backup","version":1}""", "data.json" to "{}", "art/../../evil.txt" to "boom", "art/ok.png" to "fine")
        BackupCodec.read(ByteArrayInputStream(bytes), dir)
        assertFalse(File(dir.parentFile, "evil.txt").exists())
        assertFalse(File(dir.parentFile.parentFile, "evil.txt").exists())
        assertTrue(File(dir, "ok.png").exists())
    }

    @Test
    fun replaceTakesTheBackupAsItIs() {
        val here = BackupData(favorites = listOf(Favorite("p:x", 1)))
        assertEquals(sample, BackupMerge.combine(here, sample, ImportMode.Replace))
    }

    @Test
    fun mergeAddsOnlyWhatIsMissing() {
        val k3 = "p:music/kira nox/honey drip.flac"
        val here = BackupData(
            favorites = listOf(Favorite(k1, 99)),
            playlists = listOf(BackupPlaylist("夜路", 1, listOf(BackupEntry(k1, 0, 2), BackupEntry(k3, 1, 3)))),
            events = listOf(event(k1, 1000), event(k3, 500, "Honey Drip")),
            refs = listOf(SongRef(k1, "Glasshouse", "Ivy Calder", "Paper Satellites", 201_000, 50)),
            settings = BackupSettings("Light", 30, "title", true, setOf("/storage/emulated/0/Ringtones"), emptySet(), emptySet(), null),
            artists = listOf(BackupArtist("Ivy Calder", "mine.jpg", null)),
            lyrics = listOf(com.novcloud.echo.data.db.LyricOverride(k1, offsetMs = -500, updatedAt = 1)),
        )
        val m = BackupMerge.combine(here, sample, ImportMode.Merge)
        assertEquals(-500L, m.lyrics.single { it.songKey == k1 }.offsetMs)
        assertEquals("白鸟.lrc", m.lyrics.single { it.songKey == k2 }.fileName)
        assertEquals(listOf(Favorite(k1, 99), Favorite(k2, 20)), m.favorites)
        val night = m.playlists.single { it.name == "夜路" }
        assertEquals(listOf(k1, k3, k2), night.entries.map { it.key })
        assertEquals(listOf(0, 1, 2), night.entries.map { it.position })
        assertEquals(listOf(500L, 1000L, 2000L), m.events.map { it.startedAt })
        assertEquals(50L, m.refs.single { it.key == k1 }.updatedAt)
        assertEquals(2, m.refs.size)
        assertEquals("Light", m.settings.theme)
        assertEquals(setOf("/storage/emulated/0/Ringtones", "/storage/emulated/0/Music"), m.settings.scanIncluded)
        assertTrue(m.settings.scanExcluded.isEmpty())
        assertEquals("content://tree/2", m.settings.lyricsTree)
        assertEquals(BackupArtist("Ivy Calder", "mine.jpg", "logo_0.png"), m.artists.first { it.artist == "Ivy Calder" })
        assertEquals(m, BackupMerge.combine(m, sample, ImportMode.Merge))
    }
}
