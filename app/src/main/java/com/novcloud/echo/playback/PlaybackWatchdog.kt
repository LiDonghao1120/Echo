package com.novcloud.echo.playback

/**
 * Decides when the player needs a kick. A decoder or source that stops producing output leaves ExoPlayer
 * READY with playWhenReady set, so it still reports isPlaying while the position never moves; pause/play and
 * in-item seeks keep the same renderer and stay silent. Only re-preparing (or moving to another item) rebuilds it.
 */
class PlaybackWatchdog(
    private val stallMs: Long = 4_000,
    private val bufferingMs: Long = 8_000,
    private val retryWindowMs: Long = 60_000,
    private val maxRetries: Int = 2,
) {
    enum class Action { None, Reprepare, Skip, Stop }

    data class Snapshot(
        val itemKey: String?,
        val playWhenReady: Boolean,
        val ready: Boolean,
        val buffering: Boolean,
        val suppressed: Boolean,
        val positionMs: Long,
        val hasNext: Boolean,
    )

    private var lastKey: String? = null
    private var lastPosition = -1L
    private var stuckSince = -1L
    private var retryKey: String? = null
    private val retries = ArrayDeque<Long>()

    fun tick(now: Long, s: Snapshot): Action {
        val active = s.playWhenReady && !s.suppressed && s.itemKey != null && (s.ready || s.buffering)
        if (!active || s.itemKey != lastKey) {
            reset(s)
            return Action.None
        }
        val moved = s.ready && s.positionMs != lastPosition
        lastPosition = s.positionMs
        if (moved) {
            stuckSince = -1
            return Action.None
        }
        if (stuckSince < 0) {
            stuckSince = now
            return Action.None
        }
        val limit = if (s.buffering) bufferingMs else stallMs
        if (now - stuckSince < limit) return Action.None
        stuckSince = -1
        return escalate(now, s.itemKey, s.hasNext)
    }

    fun onError(now: Long, itemKey: String?, hasNext: Boolean, fatal: Boolean): Action {
        lastKey = null
        stuckSince = -1
        if (itemKey == null) return Action.Stop
        if (fatal) return if (hasNext) Action.Skip else Action.Stop
        return escalate(now, itemKey, hasNext)
    }

    private fun escalate(now: Long, key: String, hasNext: Boolean): Action {
        if (retryKey != key) {
            retryKey = key
            retries.clear()
        }
        while (retries.isNotEmpty() && now - retries.first() > retryWindowMs) retries.removeFirst()
        if (retries.size < maxRetries) {
            retries.addLast(now)
            return Action.Reprepare
        }
        retries.clear()
        return if (hasNext) Action.Skip else Action.Stop
    }

    private fun reset(s: Snapshot) {
        lastKey = s.itemKey
        lastPosition = s.positionMs
        stuckSince = -1
    }
}
