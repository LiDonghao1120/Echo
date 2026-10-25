package com.novcloud.echo

import com.novcloud.echo.playback.PlaybackWatchdog
import com.novcloud.echo.playback.PlaybackWatchdog.Action
import com.novcloud.echo.playback.PlaybackWatchdog.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackWatchdogTest {
    private fun snap(pos: Long, key: String? = "1", ready: Boolean = true, buffering: Boolean = false, pwr: Boolean = true, hasNext: Boolean = true) =
        Snapshot(key, pwr, ready, buffering, suppressed = false, positionMs = pos, hasNext = hasNext)

    private fun run(w: PlaybackWatchdog, from: Long, to: Long, s: (Long) -> Snapshot): List<Action> =
        (from..to step 1000).map { w.tick(it, s(it)) }.filter { it != Action.None }

    @Test fun advancingPlaybackIsLeftAlone() {
        val w = PlaybackWatchdog()
        assertEquals(emptyList<Action>(), run(w, 0, 3_600_000) { snap(it) })
    }

    @Test fun frozenPositionWhilePlayingIsRePrepared() {
        val w = PlaybackWatchdog()
        run(w, 0, 10_000) { snap(it) }
        val actions = run(w, 11_000, 20_000) { snap(10_000) }
        assertEquals(Action.Reprepare, actions.first())
    }

    @Test fun pausedOrSuppressedIsNotAStall() {
        val w = PlaybackWatchdog()
        assertEquals(emptyList<Action>(), run(w, 0, 60_000) { snap(5_000, pwr = false) })
        assertEquals(emptyList<Action>(), run(w, 0, 60_000) { Snapshot("1", true, true, false, suppressed = true, positionMs = 5_000, hasNext = true) })
    }

    @Test fun longBufferingIsRePrepared() {
        val w = PlaybackWatchdog()
        val actions = run(w, 0, 7_000) { snap(3_000, ready = false, buffering = true) }
        assertEquals(emptyList<Action>(), actions)
        assertEquals(listOf(Action.Reprepare), run(w, 8_000, 10_000) { snap(3_000, ready = false, buffering = true) })
    }

    @Test fun repeatedStallsOnOneItemSkip() {
        val w = PlaybackWatchdog()
        val all = run(w, 0, 30_000) { snap(1_000) }
        assertEquals(listOf(Action.Reprepare, Action.Reprepare, Action.Skip), all.take(3))
    }

    @Test fun errorsRetryThenSkipOrStop() {
        val w = PlaybackWatchdog()
        assertEquals(Action.Reprepare, w.onError(0, "a", hasNext = true, fatal = false))
        assertEquals(Action.Reprepare, w.onError(1_000, "a", hasNext = true, fatal = false))
        assertEquals(Action.Skip, w.onError(2_000, "a", hasNext = true, fatal = false))
        assertEquals(Action.Reprepare, w.onError(3_000, "b", hasNext = false, fatal = false))
        assertEquals(Action.Stop, w.onError(4_000, "c", hasNext = false, fatal = true))
        assertEquals(Action.Skip, w.onError(5_000, "d", hasNext = true, fatal = true))
    }

    @Test fun retriesExpire() {
        val w = PlaybackWatchdog()
        w.onError(0, "a", true, false)
        w.onError(1_000, "a", true, false)
        assertEquals(Action.Reprepare, w.onError(120_000, "a", true, false))
    }
}
