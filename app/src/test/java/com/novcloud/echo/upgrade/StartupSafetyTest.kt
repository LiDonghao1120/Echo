package com.novcloud.echo.upgrade

import com.novcloud.echo.diag.StartupSteps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupSafetyTest {
    private class MapStore : StartupSteps.Store {
        val map = HashMap<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    }

    @Test
    fun failingMigrationIsLoggedAndNeverRetried() = runBlocking {
        val store = MapStore()
        var runs = 0
        StartupSteps(store).once("m") { runs++; error("boom") }
        StartupSteps(store).once("m") { runs++ }
        assertEquals(1, runs)
        assertEquals(StartupSteps.FAILED, store.map["m"])
    }

    @Test
    fun migrationThatKilledTheLastLaunchIsSkipped() = runBlocking {
        val store = MapStore().apply { map["m"] = StartupSteps.RUNNING }
        var ran = false
        StartupSteps(store).once("m") { ran = true }
        assertFalse(ran)
        assertEquals(StartupSteps.FAILED, store.map["m"])
    }

    @Test
    fun finishedMigrationRunsOnce() = runBlocking {
        val store = MapStore()
        var runs = 0
        repeat(3) { StartupSteps(store).once("m") { runs++ } }
        assertEquals(1, runs)
        assertEquals(StartupSteps.DONE, store.map["m"])
    }

    @Test
    fun everyLaunchStepRecoversAfterACrashInsideIt() = runBlocking {
        val store = MapStore()
        var recovered = 0
        assertNull(StartupSteps(store).every("r", { recovered++ }) { error("boom") })
        assertNull(store.map["r"])
        store.map["r"] = StartupSteps.RUNNING
        assertEquals(7, StartupSteps(store).every("r", { recovered++ }) { 7 })
        assertEquals(1, recovered)
        assertNull(store.map["r"])
    }

    /**
     * Android compiles java.util.regex patterns with ICU, which accepts only the inline flags i, m, s, w, x and
     * rejects flags such as (?U) with a PatternSyntaxException. On the JVM those patterns compile, so unit tests
     * cannot see the failure; a pattern in a top-level or object property then breaks the whole class at launch.
     */
    @Test
    fun regexPatternsUseOnlyFlagsAndroidSupports() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        assertTrue(src.isDirectory)
        val inline = Regex("""\(\?([a-zA-Z]*)(?:-([a-zA-Z]*))?[:)]""")
        val bad = ArrayList<String>()
        src.walk().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                inline.findAll(line).forEach { m ->
                    val flags = m.groupValues[1] + m.groupValues[2]
                    if (flags.any { it !in "imswx" }) bad += "${f.name}:${i + 1}: ${m.value}"
                }
                if ("UNICODE_CHARACTER_CLASS" in line || "CANON_EQ" in line || "\\p{java" in line || "\\p{Is" in line) bad += "${f.name}:${i + 1}"
            }
        }
        assertTrue("patterns Android cannot compile:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
