package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistFileMatcherTest {
    private val index = ArtistFileMatcher.index(listOf("Ivy Calder", "Rust N' Ribbons", "Velvet Moth", "林间", "21 Harbor", "ZYA"))

    @Test
    fun logos() {
        assertEquals(ArtMatch("Ivy Calder", ArtKind.Logo), ArtistFileMatcher.match("Ivy_Calder.png", index))
        assertEquals(ArtMatch("Ivy Calder", ArtKind.Logo), ArtistFileMatcher.match("ivy calder.PNG", index))
        assertEquals(ArtMatch("Rust N' Ribbons", ArtKind.Logo), ArtistFileMatcher.match("Rust_N_Ribbons.webp", index))
        assertEquals(ArtMatch("21 Harbor", ArtKind.Logo), ArtistFileMatcher.match("21_Harbor-81a2dfc553ef.png", index))
        assertEquals(ArtMatch("林间", ArtKind.Logo), ArtistFileMatcher.match("林间.png", index))
        assertEquals(ArtMatch("ZYA", ArtKind.Logo), ArtistFileMatcher.match("ZYA_logo.jpg", index))
    }

    @Test
    fun posters() {
        assertEquals(ArtMatch("Velvet Moth", ArtKind.Poster), ArtistFileMatcher.match("Velvet_Moth_poster.jpg", index))
        assertEquals(ArtMatch("Velvet Moth", ArtKind.Poster), ArtistFileMatcher.match("Velvet Moth - Poster.png", index))
        assertEquals(ArtMatch("Ivy Calder", ArtKind.Poster), ArtistFileMatcher.match("Ivy_Calder.jpg", index))
        assertEquals(ArtMatch("林间", ArtKind.Poster), ArtistFileMatcher.match("林间_海报.jpeg", index))
        assertEquals(ArtMatch("ZYA", ArtKind.Poster), ArtistFileMatcher.match("zya_bg.webp", index))
    }

    @Test
    fun misses() {
        assertNull(ArtistFileMatcher.match("Unknown_Band.png", index))
        assertNull(ArtistFileMatcher.match("Ivy_Calder.txt", index))
        assertNull(ArtistFileMatcher.match("00_总览.jpg", index))
    }
}
