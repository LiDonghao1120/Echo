package com.novcloud.echo

import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.lyrics.LrcParser
import com.novcloud.echo.data.lyrics.TagReader
import com.novcloud.echo.domain.Recommender
import com.novcloud.echo.domain.StatsEngine
import com.novcloud.echo.domain.StatsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId

class CoreTest {
    private fun song(id: Long, artist: String, album: Long, genre: String) = Song(
        id, "Song $id", artist, "Album $album", album, "", genre, 200_000, (id % 12).toInt() + 1, 1, 2020,
        id * 1000, "/music/$id.mp3", "/music", "audio/mpeg", 1000,
    )

    private val lib = Library.build(
        (1L..300L).map { song(it, listOf("周杰伦", "林俊杰", "Taylor Swift", "陈奕迅", "Adele", "五月天")[(it % 6).toInt()], it % 25, listOf("Pop", "Rock", "Jazz")[(it % 3).toInt()]) },
    )

    @Test
    fun lrcParsesTranslationsAndOffsets() {
        val l = LrcParser.parse("[ti:x]\n[offset:500]\n[00:01.00]你好\n[00:01.00]Hello\n[00:03.50][00:05.00]副歌\n<00:06.00>")
        assertNotNull(l)
        l!!
        assertTrue(l.synced)
        assertEquals(500L, l.lines[0].timeMs)
        assertEquals("Hello", l.lines[0].translation)
        assertEquals(3, l.lines.size)
        assertEquals(1, l.indexAt(3100))
    }

    @Test
    fun plainLyricsAreUnsynced() {
        val l = LrcParser.parse("line one\nline two")!!
        assertFalse(l.synced)
        assertEquals(2, l.lines.size)
    }

    @Test
    fun readsId3UnsyncedLyrics() {
        val text = "[00:01.00]歌词".toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream().apply {
            write(3); write("chi".toByteArray()); write(0); write(text)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write("USLT".toByteArray())
            write(byteArrayOf(0, 0, 0, frame.size.toByte())); write(byteArrayOf(0, 0)); write(frame)
        }.toByteArray()
        val tag = ByteArrayOutputStream().apply {
            write("ID3".toByteArray()); write(3); write(0); write(0)
            write(byteArrayOf(0, 0, 0, body.size.toByte())); write(body); write(ByteArray(64))
        }.toByteArray()
        assertEquals("[00:01.00]歌词", TagReader.lyrics(tag.inputStream()))
    }

    @Test
    fun dailyIsStableWithinADayAndDiverse() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 10, 24).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = (0 until 200).map { i ->
            val s = lib.songs[(i * 7) % 60]
            PlayEvent(0, s.id, s.title, s.artist, s.album, s.albumId, s.genre, day - (i + 1) * 3_600_000L, 180_000, 200_000, true)
        }
        val a = Recommender.build(lib, events, day + 9 * 3_600_000L)
        val today = events + PlayEvent(0, 5, "x", "周杰伦", "a", 5, "Pop", day + 10 * 3_600_000L, 200_000, 200_000, true)
        val b = Recommender.build(lib, today, day + 20 * 3_600_000L)
        assertEquals(a.daily!!.songs.map { it.id }, b.daily!!.songs.map { it.id })
        assertEquals(30, a.daily!!.songs.size)
        assertTrue(a.daily!!.songs.take(18).groupBy { it.artists.first() }.values.all { it.size <= 3 })
        val next = Recommender.build(lib, events, day + 33 * 3_600_000L)
        assertTrue(next.daily!!.songs.map { it.id } != a.daily!!.songs.map { it.id })
        assertTrue(a.mixes.isNotEmpty())
    }

    @Test
    fun statsStreaksAndTotals() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 10, 24)
        val now = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val days = listOf(0L, 1, 2, 5, 6, 7, 8)
        val events = days.map { d ->
            val t = today.minusDays(d).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
            PlayEvent(0, 1, "a", "周杰伦/林俊杰", "b", 1, "Pop", t, 600_000, 600_000, true)
        }.sortedBy { it.startedAt }
        val s = StatsEngine.compute(events, lib, StatsRange.Week, now)
        assertEquals(3, s.currentStreak)
        assertEquals(4, s.longestStreak)
        assertEquals(21, s.peakHour)
        assertEquals(2, s.artists)
        assertEquals(7, s.trend.size)
        assertEquals(5 * 600_000L, s.totalMs)
    }
}
