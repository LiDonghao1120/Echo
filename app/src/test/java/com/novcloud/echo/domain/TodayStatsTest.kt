package com.novcloud.echo.domain

import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TodayStatsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private val min = 60_000L

    private fun song(id: Long, artist: String) = Song(
        id, "Tide $id", artist, "Harbor Lights", 7, "", "Pop", 4 * min, id.toInt(), 1, 2024,
        0, "/music/$id.flac", "/music", "audio/flac", 1000,
    )

    private val lib = Library.build(listOf(song(1, "Ivy Calder"), song(2, "Kira Nox / Ivy Calder"), song(3, "温屿")))

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun play(songId: Long, start: Long, ms: Long): PlayEvent {
        val s = lib.songById.getValue(songId)
        return PlayEvent(songId = songId, title = s.title, artist = s.artist, album = s.album, albumId = s.albumId, genre = s.genre,
            startedAt = start, listenedMs = ms, songDurationMs = s.durationMs, completed = ms >= s.durationMs)
    }

    private fun today(events: List<PlayEvent>, now: Long, zone: ZoneId) = StatsEngine.compute(events, lib, StatsRange.Today, now, zone)

    @Test
    fun aPlayAcrossMidnightIsSplitBetweenTheDays() {
        val now = at(shanghai, 2026, 11, 8, 10)
        val e = play(1, at(shanghai, 2026, 11, 7, 23, 59), 3 * min)
        val t = today(listOf(e), now, shanghai)
        assertEquals(LocalDate.of(2026, 11, 8), t.date)
        assertEquals(StatsRange.Today, t.range)
        assertEquals(2 * min, t.totalMs)
        assertEquals(2 * min, t.hourly[0])
        assertEquals(2 * min, t.hourly.sum())
        assertEquals(1, t.plays)
        assertEquals(min / 7, t.averageMs)
        val yesterday = today(listOf(e), at(shanghai, 2026, 11, 7, 23, 59) + 30_000, shanghai)
        assertEquals(min, yesterday.totalMs)
        assertEquals(min, yesterday.hourly[23])
    }

    @Test
    fun midnightItselfBelongsToTheNewDay() {
        val now = at(shanghai, 2026, 11, 8, 9)
        val endsAtMidnight = play(1, at(shanghai, 2026, 11, 7, 23, 57), 3 * min)
        val startsAtMidnight = play(2, at(shanghai, 2026, 11, 8, 0), min)
        val t = today(listOf(endsAtMidnight, startsAtMidnight), now, shanghai)
        assertEquals(min, t.totalMs)
        assertEquals(1, t.plays)
        assertEquals(1, t.songs)
        assertEquals(3 * min / 7, t.averageMs)
    }

    @Test
    fun theDeviceTimezoneDecidesTheDay() {
        val e = play(3, at(shanghai, 2026, 11, 8, 1, 30), 4 * min)
        val inShanghai = today(listOf(e), at(shanghai, 2026, 11, 8, 12), shanghai)
        assertEquals(4 * min, inShanghai.totalMs)
        assertEquals(4 * min, inShanghai.hourly[1])
        val inNewYork = today(listOf(e), at(newYork, 2026, 11, 8, 12), newYork)
        assertTrue(inNewYork.empty)
        assertEquals(0, inNewYork.plays)
        assertEquals(4 * min / 7, inNewYork.averageMs)
        val nyDayBefore = today(listOf(e), at(newYork, 2026, 11, 7, 18), newYork)
        assertEquals(4 * min, nyDayBefore.hourly[12])
    }

    @Test
    fun theRepeatedHourOnTheNightClocksGoBackLandsOnItsClockHour() {
        val now = at(newYork, 2026, 11, 1, 20)
        val e = play(1, at(newYork, 2026, 11, 1, 0, 30), 3 * 60 * min)
        val t = today(listOf(e), now, newYork)
        assertEquals(180 * min, t.totalMs)
        assertEquals(30 * min, t.hourly[0])
        assertEquals(120 * min, t.hourly[1])
        assertEquals(30 * min, t.hourly[2])
    }

    @Test
    fun countsTopPicksAndTheSevenDayAverage() {
        val now = at(shanghai, 2026, 11, 8, 22)
        val events = listOf(
            play(1, at(shanghai, 2026, 11, 8, 8), 4 * min),
            play(2, at(shanghai, 2026, 11, 8, 9), 4 * min),
            play(2, at(shanghai, 2026, 11, 8, 21), 4 * min),
            play(3, at(shanghai, 2026, 11, 8, 21, 10), 2 * min),
            play(3, at(shanghai, 2026, 11, 1, 12), 14 * min),
            play(3, at(shanghai, 2026, 10, 31, 23, 59), 70 * min),
            play(3, at(shanghai, 2026, 11, 8, 23), 4 * min),
        )
        val t = today(events, now, shanghai)
        assertEquals(14 * min + 4 * min, t.totalMs)
        assertEquals(5, t.plays)
        assertEquals(3, t.songs)
        assertEquals(3, t.artists)
        assertEquals(listOf("2", "3", "1"), t.topSongs.map { it.key })
        assertEquals(8 * min, t.topSongs.first().ms)
        assertEquals(2, t.topSongs.first().plays)
        assertEquals(listOf("Ivy Calder", "Kira Nox", "温屿"), t.topArtists.map { it.key })
        assertEquals(12 * min, t.topArtists.first().ms)
        assertEquals(18 * min, t.topAlbums.sumOf { it.ms })
        assertEquals(listOf("Pop"), t.topGenres.map { it.title })
        assertEquals(1, t.activeDays)
        assertEquals((14 * min + 69 * min) / 7, t.averageMs)
        assertEquals(4 * min, t.hourly[8])
        assertEquals(6 * min, t.hourly[21])
        assertEquals(4 * min, t.hourly[23])
    }

    @Test
    fun nothingTodayAndNothingThisWeek() {
        val now = at(shanghai, 2026, 11, 8, 10)
        val t = today(listOf(play(1, at(shanghai, 2026, 10, 1, 10), 4 * min)), now, shanghai)
        assertTrue(t.empty)
        assertEquals(0L, t.totalMs)
        assertTrue(t.topSongs.isEmpty())
        assertTrue(t.topArtists.isEmpty())
        assertNull(t.averageMs)
        assertEquals(List(24) { 0L }, t.hourly)
    }

    @Test
    fun tiesPickTheSameSongEveryTime() {
        val now = at(shanghai, 2026, 11, 8, 22)
        val a = play(1, at(shanghai, 2026, 11, 8, 8), 4 * min)
        val b = play(3, at(shanghai, 2026, 11, 8, 9), 4 * min)
        assertEquals(listOf("1", "3"), today(listOf(a, b), now, shanghai).topSongs.map { it.key })
        assertEquals(listOf("1", "3"), today(listOf(b, a), now, shanghai).topSongs.map { it.key })
    }

    @Test
    fun thePlayInProgressCountsUpToNow() {
        val now = at(shanghai, 2026, 11, 8, 19, 40)
        val playing = play(2, at(shanghai, 2026, 11, 8, 19, 38), 2 * min)
        val t = today(listOf(playing), now, shanghai)
        assertEquals(2 * min, t.totalMs)
        assertEquals(2 * min, t.hourly[19])
        assertEquals(1, t.plays)
        val later = today(listOf(playing.copy(listenedMs = 3 * min)), now + min, shanghai)
        assertEquals(3 * min, later.totalMs)
    }

    @Test
    fun todayIsTheFirstRangeAndOnlyItHasADailyAverage() {
        assertEquals(StatsRange.Today, StatsRange.entries.first())
        val now = at(shanghai, 2026, 11, 8, 10)
        val events = listOf(play(1, at(shanghai, 2026, 11, 7, 10), 7 * min), play(1, at(shanghai, 2026, 11, 8, 9), min))
        assertEquals(min, today(events, now, shanghai).averageMs)
        assertNull(StatsEngine.compute(events, lib, StatsRange.Week, now, shanghai).averageMs)
        assertEquals(24, StatsEngine.compute(events, lib, StatsRange.Week, now, shanghai).hourly.size)
    }
}
