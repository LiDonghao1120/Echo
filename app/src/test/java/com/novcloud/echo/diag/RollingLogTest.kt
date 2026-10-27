package com.novcloud.echo.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class RollingLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun staysUnderTwiceTheCapAndKeepsTheNewestLines() {
        val log = RollingLog(tmp.root, 4_000)
        repeat(2_000) { log.append("line %05d %s".format(it, "x".repeat(40))) }
        assertTrue(log.size() <= 2 * 4_000 + 200)
        val text = ByteArrayOutputStream().also(log::copyTo).toString()
        assertTrue(text.trimEnd().endsWith("line 01999 " + "x".repeat(40)))
        val numbers = text.lines().filter { it.isNotEmpty() }.map { it.substring(5, 10).toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.last() - numbers.first() + 1, numbers.size)
    }

    @Test
    fun clearRemovesBothFiles() {
        val log = RollingLog(tmp.root, 100)
        repeat(50) { log.append("abcdefghij") }
        log.clear()
        assertEquals(0L, log.size())
    }
}
