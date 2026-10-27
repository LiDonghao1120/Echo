package com.novcloud.echo

import com.novcloud.echo.playback.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var now = 1_000L
    private val timer = SleepTimer({ now }, { 1_700_000_000_000 + now })

    @Test
    fun countdownExpiresAndFadesOverTheLastThirtySeconds() {
        timer.start(15)
        assertTrue(timer.active)
        assertEquals(15 * 60_000L, timer.state().totalMs)
        assertEquals(1_700_000_000_000 + now + 15 * 60_000L, timer.state().deadline)
        assertEquals(1f, SleepTimer.volume(timer.remaining(0, 0)), 0f)
        now += 15 * 60_000L - 15_000
        assertEquals(0.25f, SleepTimer.volume(timer.remaining(0, 0)), 0.001f)
        assertFalse(timer.expired())
        now += 15_000
        assertTrue(timer.expired())
        assertEquals(0f, SleepTimer.volume(timer.remaining(0, 0)), 0f)
    }

    @Test
    fun endOfItemFollowsThePlayingSong() {
        timer.startEndOfItem()
        assertTrue(timer.endOfItem)
        assertFalse(timer.expired())
        assertEquals(0L, timer.state().deadline)
        assertEquals(1f, SleepTimer.volume(timer.remaining(10_000, 200_000)), 0f)
        assertEquals(20_000L, timer.remaining(180_000, 200_000))
        assertNull(timer.remaining(0, 0))
        assertEquals(1f, SleepTimer.volume(timer.remaining(0, 0)), 0f)
    }

    @Test
    fun cancelAndSwitchModes() {
        timer.start(30)
        timer.startEndOfItem()
        assertEquals(0L, timer.state().totalMs)
        timer.start(45)
        assertFalse(timer.endOfItem)
        timer.cancel()
        assertFalse(timer.active)
        assertNull(timer.remaining(0, 100))
    }

    @Test
    fun fadeIsMonotonic() {
        var last = 0f
        for (ms in 0L..31_000L step 500) {
            val v = SleepTimer.volume(ms)
            assertTrue(v >= last)
            last = v
        }
        assertEquals(1f, last, 0f)
    }
}
