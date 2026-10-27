package com.novcloud.echo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.novcloud.echo.diag.DiagLog
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
    private val sleep = SleepTimer(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private var sleepJob: kotlinx.coroutines.Job? = null

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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep.endOfItem) {
                    DiagLog.i(TAG, "sleep timer: stopped at the end of ${player.currentMediaItem?.mediaId}")
                    endSleep()
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagLog.w(TAG, "player error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId} at ${player.currentPosition} ms", error)
                val fatal = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) app.container.library.onMissingFile()
                val action = watchdog.onError(SystemClock.elapsedRealtime(), player.currentMediaItem?.mediaId, player.hasNextMediaItem(), fatal)
                DiagLog.w(TAG, "recovering from error: $action")
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

        DiagLog.i(TAG, "service created")
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
                    DiagLog.w(TAG, "playback stalled at ${player.currentPosition} ms on ${player.currentMediaItem?.mediaId} (state ${player.playbackState}, buffered ${player.bufferedPosition} ms): $action")
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

    /** The saved queue as library songs, with the index of the saved current item among them (-1 if it is gone). */
    private suspend fun savedQueue(): Triple<SavedQueue, List<MediaItem>, Int>? {
        val saved = app.container.prefs.loadQueue() ?: return null
        val repo = app.container.library
        val lib = repo.awaitLoaded()
        val songs = saved.keys.map { repo.resolve(it, lib) }
        val items = songs.mapNotNull { it?.toMediaItem() }
        val current = songs.getOrNull(saved.index)?.id
        return Triple(saved, items, items.indexOfFirst { it.songId() == current })
    }

    private suspend fun restore() {
        val (saved, items, idx) = savedQueue() ?: run { restored = true; return }
        if (player.mediaItemCount > 0) { restored = true; return }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, idx.coerceAtLeast(0), if (idx >= 0) saved.positionMs else 0)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeat
            player.prepare()
        }
        if (items.size < saved.keys.size) DiagLog.i(TAG, "restored ${items.size} of ${saved.keys.size} queued songs")
        restored = true
    }

    private fun saveQueue() {
        if (!restored) return
        val n = player.mediaItemCount
        val keys = (0 until n).mapNotNull { player.getMediaItemAt(it).songKey() }
        val q = SavedQueue(keys, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        app.container.appScope.launch { app.container.prefs.saveQueue(q) }
    }

    private fun setSleep(minutes: Int) {
        when {
            minutes == SleepState.END_OF_ITEM -> {
                sleep.startEndOfItem()
                player.pauseAtEndOfMediaItems = true
            }
            minutes > 0 -> {
                sleep.start(minutes)
                player.pauseAtEndOfMediaItems = false
            }
            else -> {
                DiagLog.i(TAG, "sleep timer cancelled")
                endSleep()
                return
            }
        }
        DiagLog.i(TAG, "sleep timer set: ${if (minutes > 0) "$minutes min" else "end of current song"}")
        publishSleep()
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (isActive && sleep.active) {
                if (sleep.expired()) {
                    DiagLog.i(TAG, "sleep timer: paused at ${player.currentPosition} ms")
                    player.pause()
                    endSleep()
                    break
                }
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
                player.volume = SleepTimer.volume(sleep.remaining(player.currentPosition, duration))
                delay(200)
            }
        }
    }

    private fun endSleep() {
        sleep.cancel()
        sleepJob?.cancel()
        sleepJob = null
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        publishSleep()
    }

    private fun publishSleep() {
        session?.setSessionExtras(sleep.state().toBundle())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        DiagLog.i(TAG, "service destroyed")
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
        val SLEEP_COMMAND = SessionCommand(SleepState.ACTION, Bundle.EMPTY)
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
            val q = savedQueue()
            if (q == null || q.second.isEmpty()) {
                val all = app.container.library.awaitLoaded().songs.shuffled().take(50).map { it.toMediaItem() }
                MediaSession.MediaItemsWithStartPosition(all, 0, 0)
            } else {
                val (saved, items, idx) = q
                MediaSession.MediaItemsWithStartPosition(items, idx.coerceAtLeast(0), if (idx >= 0) saved.positionMs else 0)
            }
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (controller.packageName != packageName || !base.isAccepted) return base
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SLEEP_COMMAND).build())
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .setSessionExtras(sleep.state().toBundle())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != SleepState.ACTION) return super.onCustomCommand(session, controller, customCommand, args)
            setSleep(args.getInt(SleepState.ARG_MINUTES, 0))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
