package com.novcloud.echo.playback

import android.os.SystemClock
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.db.PlayEventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the player knows about an item, used when the song is not (or no longer) in the library snapshot. */
data class TrackInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val durationMs: Long,
    val key: String = "",
)

/**
 * Turns player callbacks into listening sessions. A session starts when the current item starts playing and ends on
 * an item change, the end of the queue or [flush]. Once it reaches [MIN_LISTEN_MS] it is written and then rewritten
 * with the same key on every pause and [checkpoint], so a killed process loses at most one checkpoint interval.
 */
class PlayTracker(
    private val write: (key: Long, event: PlayEvent, final: Boolean) -> Unit,
    private val lookup: (Long) -> Song?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
) {
    private class Session(val key: Long, val track: TrackInfo, val startedAt: Long, var playingSince: Long?) {
        var accumulated = 0L
    }

    private var current: TrackInfo? = null
    private var session: Session? = null
    private var nextKey = 1L

    /** The current item changed for any reason other than playing through to the next one. */
    fun onTrack(track: TrackInfo?, playing: Boolean) = change(track, playing, completed = false)

    /** Playback ran through the previous item (auto advance or repeat) into [next]. */
    fun onCompleted(next: TrackInfo?, playing: Boolean) = change(next, playing, completed = true)

    /** The queue ran out. The item stays current, so playing it again from the start counts as a new listen. */
    fun onEnded() {
        end(completed = true)
    }

    fun onPlaying(playing: Boolean) {
        val s = session
        if (playing) {
            if (s == null) start() else if (s.playingSince == null) s.playingSince = clock()
        } else if (s != null) {
            s.playingSince?.let { s.accumulated += clock() - it }
            s.playingSince = null
            save(s, completed = false, final = false)
        }
    }

    fun checkpoint() {
        val s = session ?: return
        if (s.playingSince != null) save(s, completed = false, final = false)
    }

    fun flush() {
        end(completed = false)
    }

    private fun change(track: TrackInfo?, playing: Boolean, completed: Boolean) {
        end(completed)
        current = track
        if (playing) start()
    }

    private fun start() {
        val t = current ?: return
        session = Session(nextKey++, t, wall(), clock())
    }

    private fun end(completed: Boolean) {
        val s = session ?: return
        session = null
        save(s, completed, final = true)
    }

    private fun save(s: Session, completed: Boolean, final: Boolean) {
        val listened = s.accumulated + (s.playingSince?.let { clock() - it } ?: 0L)
        if (listened < MIN_LISTEN_MS) return
        val song = lookup(s.track.id)
        val duration = song?.durationMs?.takeIf { it > 0 } ?: s.track.durationMs
        write(
            s.key,
            PlayEvent(
                songId = s.track.id,
                title = song?.title ?: s.track.title,
                artist = song?.artist ?: s.track.artist,
                album = song?.album ?: s.track.album,
                albumId = song?.albumId ?: s.track.albumId,
                genre = song?.genre ?: s.track.genre,
                startedAt = s.startedAt,
                listenedMs = listened,
                songDurationMs = duration,
                completed = completed || duration > 0 && listened >= duration * 0.85,
                songKey = song?.key ?: s.track.key,
            ),
            final,
        )
    }

    companion object {
        const val MIN_LISTEN_MS = 10_000L
    }
}

/** Writes sessions in call order; the first write of a session inserts a row, later ones update it. */
class PlayEventWriter(private val dao: PlayEventDao, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val rows = HashMap<Long, Long>()

    fun write(key: Long, event: PlayEvent, final: Boolean) {
        scope.launch {
            lock.withLock {
                val row = rows[key]
                if (row == null) {
                    val id = dao.insert(event)
                    if (!final) rows[key] = id
                } else {
                    dao.update(event.copy(id = row))
                    if (final) rows.remove(key)
                }
            }
        }
    }
}
