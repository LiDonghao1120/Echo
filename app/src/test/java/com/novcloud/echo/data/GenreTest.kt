package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreTest {
    @Test
    fun cleansTagValues() {
        assertEquals("Rock", MediaScanner.cleanGenre("(17)Rock"))
        assertEquals("Rock", MediaScanner.cleanGenre("(17)"))
        assertEquals("Pop", MediaScanner.cleanGenre("13"))
        assertEquals("Indie Folk", MediaScanner.cleanGenre(" Indie Folk ; Singer-Songwriter"))
        assertEquals("Alternative", MediaScanner.cleanGenre("Alternative\u0000Rock"))
        assertEquals("流行", MediaScanner.cleanGenre("流行、华语"))
        assertEquals("", MediaScanner.cleanGenre("<unknown>"))
        assertEquals("", MediaScanner.cleanGenre(null))
        assertEquals("", MediaScanner.cleanGenre("  "))
    }
}
