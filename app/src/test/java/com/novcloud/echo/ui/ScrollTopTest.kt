package com.novcloud.echo.ui

import com.novcloud.echo.ui.components.ScrollTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the back-to-top button shows and how far a long jump snaps first. */
class ScrollTopTest {
    private val screen = 2000

    @Test
    fun hiddenAtTheTopAndWithinTheFirstScreens() {
        assertFalse(ScrollTop.visible(0f, screen, wasVisible = false))
        assertFalse(ScrollTop.visible(1.0f * screen, screen, wasVisible = false))
        assertFalse(ScrollTop.visible(1.5f * screen, screen, wasVisible = false))
    }

    @Test
    fun showsPastOneAndAHalfScreens() {
        assertTrue(ScrollTop.visible(1.51f * screen, screen, wasVisible = false))
        assertTrue(ScrollTop.visible(40f * screen, screen, wasVisible = false))
    }

    @Test
    fun staysUntilBackWithinTheFirstScreenSoItDoesNotFlicker() {
        assertTrue(ScrollTop.visible(1.2f * screen, screen, wasVisible = true))
        assertFalse(ScrollTop.visible(1.2f * screen, screen, wasVisible = false))
        assertFalse(ScrollTop.visible(0.99f * screen, screen, wasVisible = true))
    }

    @Test
    fun nothingBeforeTheListIsLaidOut() {
        assertFalse(ScrollTop.visible(5000f, 0, wasVisible = false))
        assertFalse(ScrollTop.visible(5000f, 0, wasVisible = true))
    }

    @Test
    fun scrolledDistanceCountsLinesNotItemsInAGrid() {
        assertEquals(10 * 120f + 30, ScrollTop.scrolled(10, 30, 120f, 1), 0.01f)
        assertEquals(5 * 300f + 12, ScrollTop.scrolled(10, 12, 300f, 2), 0.01f)
        assertEquals(30f, ScrollTop.scrolled(1, 30, 300f, 2), 0.01f)
        assertEquals(0f, ScrollTop.scrolled(0, 0, 300f, 0), 0.01f)
    }

    @Test
    fun aLongWaySnapsToAboutAScreenFirst() {
        assertNull(ScrollTop.snapTo(0, 12))
        assertNull(ScrollTop.snapTo(24, 12))
        assertEquals(12, ScrollTop.snapTo(25, 12))
        assertEquals(12, ScrollTop.snapTo(800, 12))
        assertEquals(1, ScrollTop.snapTo(5, 0))
    }
}
