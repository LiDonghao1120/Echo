package com.novcloud.echo.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class QueueEntry(val index: Int, val song: Song)

@Immutable
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)

class PlayerConnection(
    private val context: Context,
    private val library: StateFlow<Library>,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var connecting = false
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private var ticker: Job? = null
    private var pendingSeek: Long? = null

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = onLost(controller)
            })
            .buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                scope.launch { delay(1_000); if (pending.isNotEmpty()) connect() }
                return@addListener
            }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) pendingSeek = null
                    applyPendingSeek(c)
                    sync()
                }
            })
            sync()
            pending.forEach { it(c) }
            pending.clear()
            startTicker()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        ticker?.cancel()
        val c = controller
        controller = null
        c?.release()
    }

    /** The session went away (service destroyed or crashed): drop the dead controller instead of showing its last state. */
    private fun onLost(c: MediaController) {
        if (controller !== c) return
        controller = null
        ticker?.cancel()
        _state.value = _state.value.copy(isPlaying = false, buffering = false)
        connect()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller?.takeIf { it.isConnected }
        if (c != null) block(c) else {
            controller?.let { onLost(it) }
            pending += block
            connect()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.takeIf { it.isConnected && pendingSeek == null }?.let { _position.value = it.currentPosition }
                delay(if (_state.value.isPlaying) 200 else 600)
            }
        }
    }

    fun refresh() = sync()

    internal fun preview(state: PlayerState, positionMs: Long) {
        _state.value = state
        _position.value = positionMs
    }

    private fun sync() {
        val c = controller?.takeIf { it.isConnected } ?: return
        val lib = library.value
        val timeline = c.currentTimeline
        val queue = ArrayList<QueueEntry>()
        if (!timeline.isEmpty) {
            var i = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
            var guard = 0
            while (i != C.INDEX_UNSET && guard++ < 10_000) {
                val item = c.getMediaItemAt(i)
                songFor(item, lib)?.let { queue += QueueEntry(i, it) }
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        _state.value = PlayerState(
            song = c.currentMediaItem?.let { songFor(it, lib) },
            isPlaying = c.playingOrBuffering(),
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (c.currentMediaItem?.let { songFor(it, lib)?.durationMs } ?: 0),
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
        )
        if (pendingSeek == null) _position.value = c.currentPosition
    }

    private fun songFor(item: MediaItem, lib: Library): Song? {
        val id = item.songId() ?: return null
        lib.songById[id]?.let { return it }
        val m = item.mediaMetadata
        return Song(
            id = id, title = m.title?.toString() ?: "", artist = m.artist?.toString() ?: UNKNOWN,
            album = m.albumTitle?.toString() ?: UNKNOWN, albumId = m.extras?.getLong(EXTRA_ALBUM_ID, -1) ?: -1,
            albumArtist = "", genre = m.genre?.toString() ?: "", durationMs = m.durationMs ?: 0, track = 0, disc = 1,
            year = 0, dateAdded = 0, path = "", folder = "", mimeType = "", size = 0,
        )
    }

    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        withController { c ->
            val startIndex = if (shuffle) songs.indices.random() else start.coerceIn(songs.indices)
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    fun playSong(song: Song, context: List<Song>) {
        val idx = context.indexOfFirst { it.id == song.id }
        if (idx < 0) play(listOf(song)) else {
            withController { c ->
                c.setMediaItems(context.map { it.toMediaItem() }, idx, 0)
                c.prepare()
                c.play()
            }
        }
    }

    fun toggle() = withController { c ->
        if (c.playingOrBuffering()) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { c ->
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }
    /**
     * Before the current item is prepared its timeline window is a non-seekable placeholder, so the session does not
     * offer COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM and MediaController.seekTo is silently dropped. Prepare and seek once
     * the command shows up.
     */
    fun seekTo(ms: Long) = withController { c ->
        _position.value = ms
        if (c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            pendingSeek = null
            c.seekTo(ms)
        } else {
            pendingSeek = ms
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        }
    }

    private fun applyPendingSeek(c: MediaController) {
        val ms = pendingSeek ?: return
        if (!c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        pendingSeek = null
        c.seekTo(ms)
    }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playAt(index: Int) = withController { c ->
        c.seekTo(index, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }

    fun playNext(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) { play(songs); return@withController }
        val at = c.timelineNextIndex()
        c.addMediaItems(at, songs.map { it.toMediaItem() })
    }

    fun enqueue(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) { play(songs); return@withController }
        c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun clearQueue() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
        if (cur > 0) c.removeMediaItems(0, cur)
    }

    private fun Player.playingOrBuffering() = isPlaying || playWhenReady && playbackState == Player.STATE_BUFFERING

    private fun MediaController.timelineNextIndex(): Int =
        (currentMediaItemIndex + 1).coerceAtMost(mediaItemCount)
}
