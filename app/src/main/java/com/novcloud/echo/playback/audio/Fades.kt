package com.novcloud.echo.playback.audio

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Player volume is shared by the sleep timer and fades; each owns one factor. */
class VolumeMix(private val player: ExoPlayer) {
    var sleep = 1f
        set(v) { field = v; apply() }
    var fade = 1f
        set(v) { field = v; apply() }

    private fun apply() {
        player.volume = (sleep * fade).coerceIn(0f, 1f)
    }
}

/**
 * The player the media session controls. When fades are on, user pause ramps the volume down before pausing,
 * play ramps it up from silence, and a skip dips out and back in around the jump. Natural track ends, audio
 * focus loss and becoming-noisy go straight to ExoPlayer and are never faded, so gapless playback and instant
 * unplug pauses are untouched.
 */
class FadingPlayer(
    private val exo: ExoPlayer,
    private val mix: VolumeMix,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) : ForwardingPlayer(exo) {
    private var job: Job? = null
    private var pausing = false

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && job?.isActive != true && mix.fade < 1f) ramp(1f, FADE_IN_MS)
            }
        })
    }

    override fun play() {
        job?.cancel()
        pausing = false
        if (!enabled()) {
            mix.fade = 1f
            super.play()
            return
        }
        if (!exo.playWhenReady) mix.fade = 0f
        ramp(1f, FADE_IN_MS)
        super.play()
    }

    override fun pause() {
        if (!enabled() || !exo.isPlaying) {
            job?.cancel()
            pausing = false
            super.pause()
            mix.fade = 1f
            return
        }
        if (pausing) return
        pausing = true
        job?.cancel()
        job = scope.launch {
            glide(0f, FADE_OUT_MS)
            exo.pause()
            pausing = false
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun seekToNext() = around { super.seekToNext() }
    override fun seekToPrevious() = around { super.seekToPrevious() }
    override fun seekToNextMediaItem() = around { super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() = around { super.seekToPreviousMediaItem() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) = around(mediaItemIndex != exo.currentMediaItemIndex) { super.seekToDefaultPosition(mediaItemIndex) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = around(mediaItemIndex != exo.currentMediaItemIndex) { super.seekTo(mediaItemIndex, positionMs) }

    private fun around(jump: Boolean = true, action: () -> Unit) {
        if (!jump || !enabled() || !exo.isPlaying || pausing) {
            action()
            return
        }
        if (job?.isActive == true) {
            job?.cancel()
            action()
            ramp(1f, FADE_IN_MS)
            return
        }
        job = scope.launch {
            glide(0f, SKIP_OUT_MS)
            action()
            glide(1f, FADE_IN_MS)
        }
    }

    private fun ramp(to: Float, ms: Long) {
        job?.cancel()
        job = scope.launch { glide(to, ms) }
    }

    private suspend fun glide(to: Float, ms: Long) {
        val from = mix.fade
        val steps = (ms / STEP_MS).coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val eased = if (to > from) t * t else 1f - (1f - t) * (1f - t)
            mix.fade = from + (to - from) * eased
            delay(STEP_MS)
        }
        mix.fade = to
    }

    private companion object {
        const val STEP_MS = 16L
        const val FADE_IN_MS = 260L
        const val FADE_OUT_MS = 220L
        const val SKIP_OUT_MS = 140L
    }
}
