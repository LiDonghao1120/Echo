package com.novcloud.echo

import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.playback.PlayTracker
import com.novcloud.echo.playback.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private val writes = ArrayList<Triple<Long, PlayEvent, Boolean>>()
    private val library = HashMap<Long, Song>()

    private val tracker = PlayTracker(
        write = { key, e, final -> writes += Triple(key, e, final) },
        lookup = { library[it] },
        clock = { now },
        wall = { 1_000_000_000L + now },
    )

    /** What the play_event table holds: the last write of each session. */
    private val rows get() = writes.groupBy { it.first }.values.map { it.last().second }

    private fun track(id: Long, durationMs: Long = 200_000) =
        TrackInfo(id, "Item $id", "Player Artist", "Player Album", 7, "", durationMs)

    private fun song(id: Long, durationMs: Long = 200_000) = Song(
        id = id, title = "Song $id", artist = "Library Artist", album = "Library Album", albumId = 3, albumArtist = "",
        genre = "Pop", durationMs = durationMs, track = 1, disc = 1, year = 2020, dateAdded = 0, path = "/m/$id.mp3",
        folder = "/m", mimeType = "audio/mpeg", size = 1,
    )

    private fun advance(ms: Long) {
        now += ms
    }

    @Test fun autoAdvanceRecordsEachItem() {
        tracker.onTrack(track(1), playing = false)
        tracker.onPlaying(true)
        advance(200_000)
        tracker.onCompleted(track(2), playing = true)
        advance(90_000)
        tracker.onCompleted(track(3), playing = true)
        advance(5_000)
        tracker.onTrack(track(4), playing = true)
        assertEquals(listOf(200_000L, 90_000L), rows.map { it.listenedMs })
        assertEquals(listOf(true, true), rows.map { it.completed })
        assertEquals(listOf(1L, 2L), rows.map { it.songId })
    }

    @Test fun pauseAndResumeAccumulateIntoOneRow() {
        tracker.onTrack(track(1), playing = true)
        advance(30_000)
        tracker.onPlaying(false)
        advance(600_000)
        tracker.onPlaying(true)
        advance(40_000)
        tracker.onTrack(track(2), playing = true)
        assertEquals(1, rows.size)
        assertEquals(70_000L, rows.single().listenedMs)
        assertFalse(rows.single().completed)
    }

    @Test fun inProgressListenIsSavedBeforeTheItemEnds() {
        tracker.onTrack(track(1, durationMs = 3_600_000), playing = true)
        advance(15_000)
        tracker.checkpoint()
        advance(15_000)
        tracker.checkpoint()
        assertEquals(30_000L, rows.single().listenedMs)
        advance(20_000)
        tracker.onPlaying(false)
        assertEquals(50_000L, rows.single().listenedMs)
        assertTrue(writes.none { it.third })
    }

    @Test fun checkpointWhilePausedWritesNothingNew() {
        tracker.onTrack(track(1), playing = true)
        advance(20_000)
        tracker.onPlaying(false)
        val before = writes.size
        advance(60_000)
        tracker.checkpoint()
        assertEquals(before, writes.size)
    }

    @Test fun repeatOneCountsEveryLoop() {
        tracker.onTrack(track(1, durationMs = 60_000), playing = true)
        repeat(3) {
            advance(60_000)
            tracker.onCompleted(track(1, durationMs = 60_000), playing = true)
        }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.completed && it.songId == 1L })
        assertEquals(3, writes.map { it.first }.distinct().size)
    }

    @Test fun watchdogReprepareKeepsTheSession() {
        tracker.onTrack(track(1), playing = true)
        advance(50_000)
        tracker.onPlaying(false)
        advance(300)
        tracker.onPlaying(true)
        advance(50_000)
        tracker.onCompleted(track(2), playing = true)
        assertEquals(1, rows.size)
        assertEquals(100_000L, rows.single().listenedMs)
    }

    @Test fun watchdogSkipEndsTheSession() {
        tracker.onTrack(track(1), playing = true)
        advance(25_000)
        tracker.onPlaying(false)
        tracker.onTrack(track(2), playing = false)
        tracker.onPlaying(true)
        advance(25_000)
        tracker.flush()
        assertEquals(listOf(1L, 2L), rows.map { it.songId })
        assertEquals(listOf(25_000L, 25_000L), rows.map { it.listenedMs })
    }

    @Test fun itemSetWhilePausedStartsCountingOnPlay() {
        tracker.onTrack(track(1), playing = false)
        advance(3_600_000)
        tracker.onPlaying(true)
        advance(30_000)
        tracker.flush()
        assertEquals(30_000L, rows.single().listenedMs)
        assertEquals(1_000_000_000L + 3_600_000L, rows.single().startedAt)
    }

    @Test fun shortListensAreDropped() {
        tracker.onTrack(track(1), playing = true)
        advance(9_000)
        tracker.onTrack(track(2), playing = true)
        advance(9_999)
        tracker.onPlaying(false)
        tracker.flush()
        assertTrue(writes.isEmpty())
    }

    @Test fun unknownSongIsRecordedFromPlayerMetadata() {
        tracker.onTrack(track(42), playing = true)
        advance(60_000)
        tracker.flush()
        val e = rows.single()
        assertEquals(42L, e.songId)
        assertEquals("Item 42", e.title)
        assertEquals("Player Artist", e.artist)
        assertEquals(7L, e.albumId)
        assertEquals(200_000L, e.songDurationMs)
    }

    @Test fun librarySongWinsOverPlayerMetadata() {
        library[5] = song(5, durationMs = 100_000)
        tracker.onTrack(track(5, durationMs = 0), playing = true)
        advance(90_000)
        tracker.flush()
        val e = rows.single()
        assertEquals("Song 5", e.title)
        assertEquals("Library Artist", e.artist)
        assertEquals("Pop", e.genre)
        assertEquals(100_000L, e.songDurationMs)
        assertTrue(e.completed)
    }

    @Test fun replayAfterQueueEndedIsANewListen() {
        tracker.onTrack(track(1, durationMs = 60_000), playing = true)
        advance(60_000)
        tracker.onPlaying(false)
        tracker.onEnded()
        tracker.onPlaying(true)
        advance(60_000)
        tracker.onEnded()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.songId == 1L && it.completed })
    }

    @Test fun finalWriteClosesTheSession() {
        tracker.onTrack(track(1), playing = true)
        advance(20_000)
        tracker.checkpoint()
        advance(20_000)
        tracker.onTrack(null, playing = false)
        assertEquals(listOf(false, true), writes.map { it.third })
        assertEquals(1, writes.map { it.first }.distinct().size)
        tracker.onPlaying(true)
        advance(60_000)
        tracker.flush()
        assertEquals(2, writes.size)
    }
}
