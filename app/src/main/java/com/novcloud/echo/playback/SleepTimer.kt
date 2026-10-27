package com.novcloud.echo.playback

import android.os.Bundle

/** What the UI shows for the sleep timer; [deadline] is wall-clock ms, 0 when no countdown runs, [totalMs] the length it was set to. */
data class SleepState(val deadline: Long = 0, val endOfItem: Boolean = false, val totalMs: Long = 0) {
    val active: Boolean get() = deadline > 0 || endOfItem

    fun toBundle() = Bundle().apply {
        putLong(KEY_DEADLINE, deadline)
        putBoolean(KEY_END_OF_ITEM, endOfItem)
        putLong(KEY_TOTAL, totalMs)
    }

    companion object {
        const val ACTION = "com.novcloud.echo.SLEEP"
        const val ARG_MINUTES = "minutes"
        const val END_OF_ITEM = -1
        private const val KEY_DEADLINE = "sleep_deadline"
        private const val KEY_END_OF_ITEM = "sleep_end_of_item"
        private const val KEY_TOTAL = "sleep_total"

        fun from(extras: Bundle?) = SleepState(extras?.getLong(KEY_DEADLINE) ?: 0, extras?.getBoolean(KEY_END_OF_ITEM) ?: false, extras?.getLong(KEY_TOTAL) ?: 0)
    }
}

/**
 * Sleep timer arithmetic, kept free of the player. A countdown runs on [clock] (elapsed realtime, so wall clock
 * changes do not move it); "end of item" stops when the current item ends. Volume fades out over the last [FADE_MS].
 */
class SleepTimer(private val clock: () -> Long, private val wall: () -> Long) {
    private var deadline = 0L
    private var total = 0L
    var endOfItem = false
        private set

    val active: Boolean get() = deadline > 0 || endOfItem

    fun start(minutes: Int) {
        endOfItem = false
        total = minutes * 60_000L
        deadline = clock() + total
    }

    fun startEndOfItem() {
        deadline = 0
        total = 0
        endOfItem = true
    }

    fun cancel() {
        deadline = 0
        total = 0
        endOfItem = false
    }

    /** Milliseconds until the stop, or null when it is unknown (no timer, or end of an item of unknown length). */
    fun remaining(positionMs: Long, durationMs: Long): Long? = when {
        deadline > 0 -> deadline - clock()
        endOfItem && durationMs > 0 -> (durationMs - positionMs).coerceAtLeast(0)
        else -> null
    }

    /** A countdown that has run out; end of item is reported by the player instead. */
    fun expired(): Boolean = deadline > 0 && clock() >= deadline

    fun state(): SleepState = SleepState(if (deadline > 0) wall() + (deadline - clock()) else 0, endOfItem, total)

    companion object {
        const val FADE_MS = 30_000L

        /** Full volume until the last [FADE_MS], then a quadratic ramp to silence, which sounds steadier than a linear one. */
        fun volume(remainingMs: Long?): Float {
            if (remainingMs == null || remainingMs >= FADE_MS) return 1f
            if (remainingMs <= 0) return 0f
            val x = remainingMs.toFloat() / FADE_MS
            return x * x
        }
    }
}
