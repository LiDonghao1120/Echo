package com.novcloud.echo.playback

import android.os.SystemClock
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.db.PlayEventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PlayTracker(
    private val dao: PlayEventDao,
    private val library: () -> Library,
    private val scope: CoroutineScope,
) {
    private var songId: Long? = null
    private var startedAt = 0L
    private var accumulated = 0L
    private var playingSince: Long? = null

    fun onTrack(id: Long?, playing: Boolean) {
        commit(completed = false)
        songId = id
        startedAt = System.currentTimeMillis()
        accumulated = 0
        playingSince = if (playing && id != null) SystemClock.elapsedRealtime() else null
    }

    fun onPlaying(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            if (playingSince == null && songId != null) playingSince = now
        } else {
            playingSince?.let { accumulated += now - it }
            playingSince = null
        }
    }

    fun onCompleted(nextId: Long?, playing: Boolean) {
        commit(completed = true)
        songId = nextId
        startedAt = System.currentTimeMillis()
        accumulated = 0
        playingSince = if (playing && nextId != null) SystemClock.elapsedRealtime() else null
    }

    fun flush() = commit(completed = false)

    private fun commit(completed: Boolean) {
        val id = songId ?: return
        playingSince?.let {
            val now = SystemClock.elapsedRealtime()
            accumulated += now - it
            playingSince = now
        }
        val listened = accumulated
        accumulated = 0
        if (listened < 10_000) return
        val song = library().songById[id] ?: return
        val done = completed || listened >= song.durationMs * 0.85
        val event = PlayEvent(
            songId = id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            genre = song.genre,
            startedAt = startedAt,
            listenedMs = listened.coerceAtMost(song.durationMs.coerceAtLeast(listened)),
            songDurationMs = song.durationMs,
            completed = done,
        )
        startedAt = System.currentTimeMillis()
        scope.launch { dao.insert(event) }
    }
}
