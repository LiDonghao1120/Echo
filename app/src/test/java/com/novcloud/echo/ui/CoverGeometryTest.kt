package com.novcloud.echo.ui

import com.novcloud.echo.data.CoverStyle
import com.novcloud.echo.ui.detail.CoverGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGeometryTest {
    private val widths = listOf(320f, 340f, 360f, 393f, 400f, 412f, 480f, 600f)

    @Test
    fun everyStyleFitsTheWidthWithItsMargins() {
        for (w in widths) for (style in CoverStyle.entries) {
            val f = CoverGeometry.of(style, w, Float.POSITIVE_INFINITY, 20f, 250f)
            if (style == CoverStyle.Film) assertEquals(w, f.width, 0.01f)
            else assertTrue("$style at $w: ${f.width}", f.width <= w - 40f + 0.01f)
            assertTrue("$style at $w", f.side in 100f..250f)
        }
    }

    @Test
    fun sleeveAndRecordStayCentredAsTheRecordSlidesOut() {
        for (out in listOf(0f, 0.25f, 0.5f, 0.8f, 1f, 1.04f)) {
            val left = CoverGeometry.sleeveShift(out) - 0.5f
            val right = maxOf(CoverGeometry.sleeveShift(out) + 0.5f, CoverGeometry.recordShift(out) + CoverGeometry.RECORD / 2)
            assertEquals("out $out", 0f, (left + right) / 2, 1e-4f)
        }
        val side = CoverGeometry.of(CoverStyle.Vinyl, 360f, Float.POSITIVE_INFINITY, 20f, 250f).side
        val span = (CoverGeometry.recordShift(1f) + CoverGeometry.RECORD / 2 - (CoverGeometry.sleeveShift(1f) - 0.5f)) * side
        assertTrue("span $span", span <= 320.01f)
    }

    @Test
    fun theRecordStartsTuckedInsideTheSleeve() {
        assertEquals(0f, CoverGeometry.sleeveShift(0f), 0f)
        assertTrue(CoverGeometry.recordShift(0f) + CoverGeometry.RECORD / 2 <= 0.5f)
    }

    @Test
    fun theDiscRisesFromInsideTheCaseToTheTop() {
        val caseTop = CoverGeometry.DISC * CoverGeometry.DISC_OUT
        assertTrue(CoverGeometry.discTop(0f) >= caseTop)
        assertTrue(CoverGeometry.discTop(0f) + CoverGeometry.DISC <= caseTop + CoverGeometry.CASE_H + 1e-4f)
        assertEquals(0f, CoverGeometry.discTop(1f), 1e-6f)
    }

    @Test
    fun previewsFitTheirTile() {
        for (style in CoverStyle.entries) {
            val f = CoverGeometry.of(style, 160f, 100f, 12f, 400f)
            assertTrue("$style ${f.height}", f.height <= 100.01f)
            assertTrue("$style ${f.width}", f.width <= 160.01f)
        }
    }

    @Test
    fun unknownNamesFallBackToClassic() {
        assertEquals(CoverStyle.Classic, CoverStyle.of(null))
        assertEquals(CoverStyle.Classic, CoverStyle.of("Cassette"))
        assertEquals(CoverStyle.Film, CoverStyle.of("Film"))
    }
}
