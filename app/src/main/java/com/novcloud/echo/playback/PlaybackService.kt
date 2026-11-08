package com.novcloud.echo.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.novcloud.echo.EchoApp
import com.novcloud.echo.MainActivity
import com.novcloud.echo.data.SavedQueue
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.playback.audio.AudioSettings
import com.novcloud.echo.playback.audio.EffectChain
import com.novcloud.echo.playback.audio.FadingPlayer
import com.novcloud.echo.playback.audio.GainProcessor
import com.novcloud.echo.playback.audio.ReplayGainSink
import com.novcloud.echo.playback.audio.VolumeMix
import com.novcloud.echo.widget.WidgetActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + DiagLog.handler("playback"))
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var tracker: PlayTracker
    private var restored = false
    private val watchdog = PlaybackWatchdog()
    private val sleep = SleepTimer(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private var sleepJob: kotlinx.coroutines.Job? = null

    private val app get() = application as EchoApp
    private val gain = GainProcessor()
    private var gainSink: ReplayGainSink? = null
    private var effects: EffectChain? = null
    private lateinit var mix: VolumeMix
    private lateinit var fading: FadingPlayer
    private lateinit var widget: WidgetSync

    override fun onCreate() {
        super.onCreate()
        val c = app.container
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val base = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gain))
                    .build()
                return ReplayGainSink(base, gain).also { gainSink = it }
            }
        }.forceDisableMediaCodecAsynchronousQueueing()
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.setShuffleOrder(QueueOrder())
        mix = VolumeMix(player)
        fading = FadingPlayer(player, mix, scope) { c.audioFx.settings.value.fade }
        widget = WidgetSync(this, scope)
        val writer = PlayEventWriter(c.db.events(), c.appScope)
        tracker = PlayTracker(writer::write, { c.library.library.value.songById[it] })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val track = mediaItem?.trackInfo()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                    tracker.onCompleted(track, player.isPlaying)
                else tracker.onTrack(track, player.isPlaying)
                saveQueue()
                syncWidget(trackChanged = true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                tracker.onPlaying(isPlaying)
                if (!isPlaying) saveQueue()
                syncWidget()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) tracker.onEnded()
                syncWidget()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = attachEffects(audioSessionId)

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep.endOfItem) {
                    DiagLog.i(TAG, "sleep timer: stopped at the end of ${player.currentMediaItem?.mediaId}")
                    endSleep()
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    applyResumedOrder()
                    shuffleNewQueue()
                    saveQueue()
                }
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

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && !applyingOrder) setOrder(currentOrder().reshuffledFrom(player.currentMediaItemIndex))
                saveQueue()
            }
            override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueuePlayer(fading, ::keepAppendedLast))
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()

        active = this
        DiagLog.i(TAG, "service created")
        attachEffects(player.audioSessionId)
        scope.launch {
            c.audioFx.awaitLoaded()
            c.audioFx.settings.collect { applyAudio(it) }
        }
        scope.launch { restore() }
        scope.launch {
            while (isActive) {
                delay(15_000)
                DiagLog.guard("listening checkpoint") { tracker.checkpoint() }
                DiagLog.guard("queue save") { if (player.isPlaying) saveQueue() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                DiagLog.guard("playback watchdog") {
                    val action = watchdog.tick(SystemClock.elapsedRealtime(), snapshot())
                    if (action != PlaybackWatchdog.Action.None) {
                        DiagLog.w(TAG, "playback stalled at ${player.currentPosition} ms on ${player.currentMediaItem?.mediaId} (state ${player.playbackState}, buffered ${player.bufferedPosition} ms): $action")
                        recover(action, resume = true)
                    }
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

    private class Restored(val saved: SavedQueue, val items: List<MediaItem>, val index: Int, val order: QueueOrder?)

    /** The saved queue as library songs, the saved current item's index among them (-1 if gone) and its play order. */
    private suspend fun savedQueue(): Restored? {
        val saved = app.container.prefs.loadQueue() ?: return null
        val repo = app.container.library
        val lib = repo.awaitLoaded()
        val songs = saved.keys.map { repo.resolve(it, lib) }
        val items = songs.mapNotNull { it?.toMediaItem() }
        val current = songs.getOrNull(saved.index)?.id
        var next = 0
        val kept = IntArray(songs.size) { if (songs[it] != null) next++ else -1 }
        val order = if (saved.shuffle) saved.order.takeIf { it.isNotEmpty() }?.let { QueueOrder.remap(it, kept) }?.let { QueueOrder.of(it) } else null
        return Restored(saved, items, items.indexOfFirst { it.songId() == current }, order)
    }

    private var applyingOrder = false
    /** Play order and modes for a queue handed to the session by [Callback.onPlaybackResumption], applied once it lands. */
    private var resumed: Restored? = null

    private fun currentOrder(): QueueOrder = player.shuffleOrder as? QueueOrder ?: QueueOrder.of(playOrder()) ?: QueueOrder(player.mediaItemCount)

    private fun playOrder(): List<Int> {
        val t = player.currentTimeline
        val out = ArrayList<Int>(t.windowCount)
        var i = t.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    private fun setOrder(order: QueueOrder) {
        if (order.length != player.mediaItemCount) return
        applyingOrder = true
        try { player.setShuffleOrder(order) } finally { applyingOrder = false }
    }

    /** A queue that replaced the old one while shuffle is on starts with the item the user picked. */
    private fun shuffleNewQueue() {
        val order = player.shuffleOrder as? QueueOrder ?: return
        if (!order.fresh || applyingOrder || !player.shuffleModeEnabled || player.mediaItemCount == 0) return
        setOrder(order.reshuffledFrom(player.currentMediaItemIndex))
    }

    private fun applyResumedOrder() {
        val r = resumed ?: return
        if (player.mediaItemCount != r.items.size) return
        resumed = null
        applyModes(r)
    }

    private fun applyModes(r: Restored) {
        applyingOrder = true
        try {
            r.order?.takeIf { it.length == player.mediaItemCount }?.let { player.setShuffleOrder(it) }
            player.shuffleModeEnabled = r.saved.shuffle
            if (r.saved.shuffle && r.order == null) player.setShuffleOrder(QueueOrder.startingWith(player.mediaItemCount, player.currentMediaItemIndex))
            player.repeatMode = r.saved.repeat
        } finally {
            applyingOrder = false
        }
    }

    /**
     * Moves playlist item [from] to where [to] is in play order. Without shuffle that is a playlist move; with shuffle
     * the playlist stays and only the play order changes, which is the order the queue sheet shows.
     */
    private fun moveInQueue(from: Int, to: Int) {
        val n = player.mediaItemCount
        if (from !in 0 until n || to !in 0 until n || from == to) return
        if (!player.shuffleModeEnabled) {
            player.moveMediaItem(from, to)
            return
        }
        val order = currentOrder()
        setOrder(order.moved(from, order.positionOf(to)))
        saveQueue()
    }

    private fun keepAppendedLast(start: Int, count: Int) {
        if (!player.shuffleModeEnabled || start + count > player.mediaItemCount) return
        setOrder(currentOrder().movedToEnd(start until start + count))
    }

    private suspend fun restore() {
        val r = savedQueue() ?: run { restored = true; return }
        val (saved, items, idx) = Triple(r.saved, r.items, r.index)
        if (player.mediaItemCount > 0) { restored = true; return }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, idx.coerceAtLeast(0), if (idx >= 0) saved.positionMs else 0)
            applyModes(r)
            val skip = pendingSkip
            pendingSkip = 0
            if (skip != 0 && idx >= 0) player.seekTo((idx + skip).mod(items.size), 0)
            player.prepare()
        }
        if (items.size < saved.keys.size) DiagLog.i(TAG, "restored ${items.size} of ${saved.keys.size} queued songs")
        restored = true
    }

    private fun saveQueue() {
        if (!restored) return
        val n = player.mediaItemCount
        val keys = (0 until n).mapNotNull { player.getMediaItemAt(it).songKey() }
        val order = if (player.shuffleModeEnabled && keys.size == n) (player.shuffleOrder as? QueueOrder)?.toList() ?: playOrder() else emptyList()
        val q = SavedQueue(keys, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode, order)
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
                mix.sleep = SleepTimer.volume(sleep.remaining(player.currentPosition, duration))
                delay(200)
            }
        }
    }

    private fun endSleep() {
        sleep.cancel()
        sleepJob?.cancel()
        sleepJob = null
        player.pauseAtEndOfMediaItems = false
        mix.sleep = 1f
        publishSleep()
    }

    private fun publishSleep() {
        session?.setSessionExtras(sleep.state().toBundle())
    }

    private fun attachEffects(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || effects?.session == sessionId) return
        effects?.release()
        val chain = EffectChain(sessionId)
        effects = chain
        app.container.audioFx.publishCaps(chain.caps)
        chain.apply(app.container.audioFx.settings.value)
        DiagLog.i(TAG, "audio effects on session $sessionId: eq=${chain.caps.available} bands=${chain.caps.centersHz.size} bass=${chain.caps.bassBoost}")
    }

    private fun applyAudio(s: AudioSettings) {
        effects?.apply(s)
        gainSink?.setSettings(s.gainMode, s.preampDb, s.clipPrevention)
        if (!s.fade) mix.fade = 1f
    }

    private fun syncWidget(trackChanged: Boolean = false) {
        val item = player.currentMediaItem
        val song = item?.songId()?.let { app.container.library.library.value.songById[it] }
        widget.update(item, song, player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE, trackChanged)
    }

    /** Widget buttons while the service runs with a queue; false sends the press through the cold-start path. */
    private fun handleWidget(action: String): Boolean {
        if (player.mediaItemCount == 0) return false
        when (action) {
            WidgetActions.ACTION_TOGGLE -> {
                if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) fading.pause() else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                    fading.play()
                }
            }
            WidgetActions.ACTION_NEXT -> fading.seekToNext()
            WidgetActions.ACTION_PREV -> fading.seekToPrevious()
            else -> return false
        }
        return true
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
        if (active === this) active = null
        saveQueue()
        tracker.flush()
        widget.stopped()
        effects?.release()
        effects = null
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EchoPlayback"
        private val SLEEP_COMMAND = SessionCommand(SleepState.ACTION, Bundle.EMPTY)
        private val MOVE_COMMAND = SessionCommand(QueueMove.ACTION, Bundle.EMPTY)

        @Volatile private var active: PlaybackService? = null

        /** Set by a widget prev/next press that has to start the service: applied to the resumed queue's start. */
        @Volatile var pendingSkip = 0

        /** Main thread only (broadcast receivers run there). */
        fun onWidget(action: String): Boolean = active?.handleWidget(action) ?: false

        /** The UI language changed: redraw the media notification with the new labels. */
        fun onLanguageChanged() { active?.refreshNotification() }
    }

    private fun refreshNotification() {
        val s = session ?: return
        val engaged = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        runCatching { onUpdateNotification(s, engaged) }.onFailure { DiagLog.w(TAG, "notification refresh failed", it) }
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
            if (q == null || q.items.isEmpty()) {
                val all = app.container.library.awaitLoaded().songs.shuffled().take(50).map { it.toMediaItem() }
                MediaSession.MediaItemsWithStartPosition(all, 0, 0)
            } else {
                val skip = pendingSkip
                pendingSkip = 0
                resumed = q
                if (skip != 0 && q.index >= 0) {
                    MediaSession.MediaItemsWithStartPosition(q.items, (q.index + skip).mod(q.items.size), 0)
                } else {
                    MediaSession.MediaItemsWithStartPosition(q.items, q.index.coerceAtLeast(0), if (q.index >= 0) q.saved.positionMs else 0)
                }
            }
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (controller.packageName != packageName || !base.isAccepted) return base
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SLEEP_COMMAND).add(MOVE_COMMAND).build())
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
            when (customCommand.customAction) {
                SleepState.ACTION -> setSleep(args.getInt(SleepState.ARG_MINUTES, 0))
                QueueMove.ACTION -> moveInQueue(args.getInt(QueueMove.ARG_FROM, -1), args.getInt(QueueMove.ARG_TO, -1))
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
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
