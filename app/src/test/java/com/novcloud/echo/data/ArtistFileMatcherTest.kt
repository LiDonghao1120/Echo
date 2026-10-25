package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistFileMatcherTest {
    private val index = ArtistFileMatcher.index(listOf("Taylor Swift", "Guns N' Roses", "Doja Cat", "陈粒", "21 Savage", "SZA"))

    @Test
    fun logos() {
        assertEquals(ArtMatch("Taylor Swift", ArtKind.Logo), ArtistFileMatcher.match("Taylor_Swift.png", index))
        assertEquals(ArtMatch("Taylor Swift", ArtKind.Logo), ArtistFileMatcher.match("taylor swift.PNG", index))
        assertEquals(ArtMatch("Guns N' Roses", ArtKind.Logo), ArtistFileMatcher.match("Guns_N_Roses.webp", index))
        assertEquals(ArtMatch("21 Savage", ArtKind.Logo), ArtistFileMatcher.match("21_Savage-81a2dfc553ef.png", index))
        assertEquals(ArtMatch("陈粒", ArtKind.Logo), ArtistFileMatcher.match("陈粒.png", index))
        assertEquals(ArtMatch("SZA", ArtKind.Logo), ArtistFileMatcher.match("SZA_logo.jpg", index))
    }

    @Test
    fun posters() {
        assertEquals(ArtMatch("Doja Cat", ArtKind.Poster), ArtistFileMatcher.match("Doja_Cat_poster.jpg", index))
        assertEquals(ArtMatch("Doja Cat", ArtKind.Poster), ArtistFileMatcher.match("Doja Cat - Poster.png", index))
        assertEquals(ArtMatch("Taylor Swift", ArtKind.Poster), ArtistFileMatcher.match("Taylor_Swift.jpg", index))
        assertEquals(ArtMatch("陈粒", ArtKind.Poster), ArtistFileMatcher.match("陈粒_海报.jpeg", index))
        assertEquals(ArtMatch("SZA", ArtKind.Poster), ArtistFileMatcher.match("sza_bg.webp", index))
    }

    @Test
    fun misses() {
        assertNull(ArtistFileMatcher.match("Unknown_Band.png", index))
        assertNull(ArtistFileMatcher.match("Taylor_Swift.txt", index))
        assertNull(ArtistFileMatcher.match("00_总览.jpg", index))
    }
}
