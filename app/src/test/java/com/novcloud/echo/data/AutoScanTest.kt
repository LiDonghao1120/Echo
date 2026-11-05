package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScanTest {
    private fun song(n: Int, title: String = "Tide $n", size: Long = 5_000_000L + n, modified: Long = 1_700_000_000_000L + n) = Song(
        n.toLong(), title, "Nova Lark", "Low Water", 1, "", "Ambient", 200_000, n, 1, 2024,
        0, "/storage/emulated/0/Music/Low Water/$n.flac", "/storage/emulated/0/Music/Low Water", "audio/flac", size, modified = modified,
    )

    private val base = (1..6).map { song(it) }

    @Test fun unchangedLibraryHasNoDiff() {
        assertTrue(LibraryDiff.of(base, base.reversed()).isEmpty)
    }

    @Test fun addedRemovedRetaggedAndRewrittenAreTold() {
        val now = base.filter { it.id != 2L }.map {
            when (it.id) {
                3L -> it.copy(title = "Tide 3 (Live)", modified = it.modified + 5_000)
                4L -> it.copy(modified = it.modified + 9_000)
                5L -> it.copy(size = it.size + 1)
                else -> it
            }
        } + song(7)
        val d = LibraryDiff.of(base, now)
        assertEquals(listOf(7L), d.added.map { it.id })
        assertEquals(listOf(2L), d.removed.map { it.id })
        assertEquals(listOf(3L), d.retagged.map { it.id })
        assertEquals(listOf(4L, 5L), d.rewritten.map { it.id })
        assertEquals(setOf(2L, 3L, 4L, 5L), d.staleIds)
        assertEquals("+1 -1 ~1 retagged ~2 rewritten", d.describe())
    }

    @Test fun inPlaceTagEditWithTheSameSizeIsSeen() {
        val edited = base.map { if (it.id == 1L) it.copy(artist = "Nova Lark & Ash Meridian", modified = it.modified + 1) else it }
        assertEquals(listOf(1L), LibraryDiff.of(base, edited).retagged.map { it.id })
    }

    @Test fun firstModificationTimeAfterUpgradeIsNotAChange() {
        val legacy = base.map { it.copy(modified = 0) }
        assertTrue(LibraryDiff.of(legacy, base).isEmpty)
    }

    @Test fun storeChangesAreDebouncedWithACap() {
        val p = AutoScanPolicy(quietMs = 2_500, maxWaitMs = 15_000)
        assertEquals(2_500, p.onChange(0))
        assertEquals(2_500, p.onChange(1_000))
        assertEquals(2_500, p.onChange(12_000))
        assertEquals(1_000, p.onChange(14_000))
        assertEquals(0, p.onChange(16_000))
        p.onScan()
        assertEquals(2_500, p.onChange(20_000))
    }

    @Test fun folderChecksAreThrottled() {
        val p = AutoScanPolicy(checkEveryMs = 900_000)
        assertTrue(p.checkDue(1_000_000, 0))
        assertFalse(p.checkDue(1_000_000, 500_000))
        assertTrue(p.checkDue(1_400_000, 500_000))
        assertTrue("clock went back", p.checkDue(100, 500_000))
    }

    @Test fun deepWalkOnChangeOrWhenOverdue() {
        val p = AutoScanPolicy(deepEveryMs = 6 * 3_600_000L)
        val m = AutoScanPolicy.Mode.IfChanged
        val h = 3_600_000L
        assertTrue(p.walkTrees(m, signatureChanged = true, now = 10 * h, lastDeepWalk = 9 * h))
        assertFalse(p.walkTrees(m, signatureChanged = false, now = 10 * h, lastDeepWalk = 9 * h))
        assertTrue(p.walkTrees(m, signatureChanged = false, now = 16 * h, lastDeepWalk = 9 * h))
        assertTrue("never walked", p.walkTrees(m, false, 10 * h, 0))
        assertFalse(p.walkTrees(AutoScanPolicy.Mode.Never, true, 16 * h, 0))
        assertTrue(p.walkTrees(AutoScanPolicy.Mode.Always, false, 10 * h, 10 * h))
    }
}
