package com.novcloud.echo.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOrderTest {
    private fun QueueOrder.walk(): List<Int> {
        val out = ArrayList<Int>()
        var i = firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = getNextIndex(i) }
        return out
    }

    private fun order(vararg o: Int) = QueueOrder.of(o.toList())!!

    @Test fun newQueueIsAShuffledPermutation() {
        val q = QueueOrder(0, seed = 7).cloneAndInsert(0, 20) as QueueOrder
        assertTrue(q.fresh)
        assertEquals((0 until 20).toList(), q.walk().sorted())
        assertTrue(q.walk() != (0 until 20).toList())
        val first = q.reshuffledFrom(13)
        assertEquals(13, first.firstIndex)
        assertEquals((0 until 20).toList(), first.walk().sorted())
    }

    /** The bug: with shuffle on, "play next" inserts after the current item in the playlist and must play right after it. */
    @Test fun playNextInShufflePlaysRightAfterTheCurrentItem() {
        // Playlist 0..5, play order 3 0 5 1 4 2, current = 5 (third in play order).
        val q = order(3, 0, 5, 1, 4, 2)
        val inserted = q.cloneAndInsert(6, 2) as QueueOrder // PlayerConnection.playNext: at current + 1 = 6
        assertEquals(listOf(3, 0, 5, 6, 7, 1, 4, 2), inserted.walk())
        assertEquals(6, inserted.getNextIndex(5))
        val mid = order(3, 0, 5, 1, 4, 2).cloneAndInsert(1, 1) as QueueOrder // current = 0, inserted at 1
        assertEquals(listOf(4, 0, 1, 6, 2, 5, 3), mid.walk())
        assertEquals(1, mid.getNextIndex(0))
    }

    @Test fun appendedItemsGoToTheEndAndRemovalsShift() {
        val inserted = order(2, 0, 1).cloneAndInsert(3, 2) as QueueOrder
        assertEquals(listOf(2, 3, 4, 0, 1), inserted.walk())
        val q = inserted.movedToEnd(3..4)
        assertEquals(listOf(2, 0, 1, 3, 4), q.walk())
        val removed = q.cloneAndRemove(0, 2) as QueueOrder
        assertEquals(listOf(0, 1, 2), removed.walk())
        assertEquals(0, (q.cloneAndClear() as QueueOrder).length)
    }

    @Test fun movesChangeOnlyThePlayOrder() {
        val q = order(3, 0, 5, 1, 4, 2)
        assertEquals(listOf(3, 0, 5, 2, 1, 4), q.moved(2, q.positionOf(1)).walk())
        assertEquals(listOf(3, 5, 1, 4, 0, 2), q.moved(0, q.positionOf(4)).walk())
        assertEquals(listOf(4, 3, 0, 5, 1, 2), q.moved(4, 0).walk())
    }

    @Test fun savedOrderSurvivesMissingSongs() {
        // Saved keys 0..4 played 4 2 0 3 1; key 2 no longer resolves, so playlist indices shift.
        val kept = intArrayOf(0, 1, -1, 2, 3)
        assertEquals(listOf(3, 0, 2, 1), QueueOrder.remap(listOf(4, 2, 0, 3, 1), kept))
        assertNull(QueueOrder.remap(listOf(0, 1), kept))
        assertNull(QueueOrder.of(listOf(0, 0, 1)))
    }

    @Test fun previousAndBoundsFollowThePlayOrder() {
        val q = order(2, 0, 1)
        assertEquals(2, q.firstIndex)
        assertEquals(1, q.lastIndex)
        assertEquals(C.INDEX_UNSET, q.getPreviousIndex(2))
        assertEquals(2, q.getPreviousIndex(0))
        assertEquals(C.INDEX_UNSET, q.getNextIndex(1))
    }
}
