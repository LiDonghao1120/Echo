package com.novcloud.echo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.novcloud.echo.EchoApp
import com.novcloud.echo.MainActivity
import com.novcloud.echo.data.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var tracker: PlayTracker
    private var restored = false
    private val watchdog = PlaybackWatchdog()

    private val app get() = application as EchoApp

    override fun onCreate() {
        super.onCreate()
        val c = app.container
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).forceDisableMediaCodecAsynchronousQueueing())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val writer = PlayEventWriter(c.db.events(), c.appScope)
        tracker = PlayTracker(writer::write, { c.library.library.value.songById[it] })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val track = mediaItem?.trackInfo()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                    tracker.onCompleted(track, player.isPlaying)
                else tracker.onTrack(track, player.isPlaying)
                saveQueue()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                tracker.onPlaying(isPlaying)
                if (!isPlaying) saveQueue()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) tracker.onEnded()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "player error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId}", error)
                val fatal = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) app.container.library.onMissingFile()
                val action = watchdog.onError(SystemClock.elapsedRealtime(), player.currentMediaItem?.mediaId, player.hasNextMediaItem(), fatal)
                scope.launch { recover(action, resume = true) }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) saveQueue()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()
            override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()

        scope.launch { restore() }
        scope.launch {
            while (isActive) {
                delay(15_000)
                tracker.checkpoint()
                if (player.isPlaying) saveQueue()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val action = watchdog.tick(SystemClock.elapsedRealtime(), snapshot())
                if (action != PlaybackWatchdog.Action.None) {
                    Log.w(TAG, "playback stalled at ${player.currentPosition} ms on ${player.currentMediaItem?.mediaId}: $action")
                    recover(action, resume = true)
                }
            }
        }
    }

    private fun snapshot() = PlaybackWatchdog.Snapshot(
        itemKey = player.currentMediaItem?.mediaId,
        playWhenReady = player.playWhenReady,
        ready = player.playbackState == Player.STATE_READY,
        buffering = player.playbackState == Player.STATE_BUFFERING,
        suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        positionMs = player.currentPosition,
        hasNext = player.hasNextMediaItem(),
    )

    /** stop() + prepare() releases the renderers and the source, so a hung decoder or read is rebuilt. */
    private fun recover(action: PlaybackWatchdog.Action, resume: Boolean) {
        when (action) {
            PlaybackWatchdog.Action.None -> return
            PlaybackWatchdog.Action.Reprepare -> {
                val index = player.currentMediaItemIndex
                val position = player.currentPosition
                player.stop()
                player.seekTo(index, position)
                player.prepare()
            }
            PlaybackWatchdog.Action.Skip -> {
                player.seekToNextMediaItem()
                player.prepare()
            }
            PlaybackWatchdog.Action.Stop -> {
                player.pause()
                return
            }
        }
        if (resume) player.playWhenReady = true
    }

    private suspend fun restore() {
        val saved = app.container.prefs.loadQueue() ?: run { restored = true; return }
        val lib = app.container.library.awaitLoaded()
        if (player.mediaItemCount > 0) { restored = true; return }
        val items = saved.ids.mapNotNull { lib.songById[it]?.toMediaItem() }
        if (items.isNotEmpty()) {
            val currentId = saved.ids.getOrNull(saved.index)
            val idx = items.indexOfFirst { it.songId() == currentId }.coerceAtLeast(0)
            player.setMediaItems(items, idx, if (items[idx].songId() == currentId) saved.positionMs else 0)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeat
            player.prepare()
        }
        restored = true
    }

    private fun saveQueue() {
        if (!restored) return
        val n = player.mediaItemCount
        val ids = (0 until n).mapNotNull { player.getMediaItemAt(it).songId() }
        val q = SavedQueue(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        app.container.appScope.launch { app.container.prefs.saveQueue(q) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        tracker.flush()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "EchoPlayback"
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            restored = true
            MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = app.container.prefs.loadQueue()
            val lib = app.container.library.awaitLoaded()
            val items = saved?.ids?.mapNotNull { lib.songById[it]?.toMediaItem() }.orEmpty()
            if (saved == null || items.isEmpty()) {
                val all = lib.songs.shuffled().take(50).map { it.toMediaItem() }
                MediaSession.MediaItemsWithStartPosition(all, 0, 0)
            } else {
                val currentId = saved.ids.getOrNull(saved.index)
                val idx = items.indexOfFirst { it.songId() == currentId }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(items, idx, saved.positionMs)
            }
        }

        private suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
            if (items.all { it.localConfiguration != null }) return items
            val lib = app.container.library.awaitLoaded()
            return items.mapNotNull { item ->
                if (item.localConfiguration != null) item else item.songId()?.let { lib.songById[it]?.toMediaItem() }
            }
        }
    }
}
