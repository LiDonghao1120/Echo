package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArtistArtTest {
    private val songs = listOf(
        Song(1, "a", "X", "Al1", 10, "X", "", 1000, 1, 1, 2020, 0, "/m/1.mp3", "/m", "", 1),
        Song(2, "b", "X", "Al2", 20, "X", "", 1000, 1, 1, 2021, 0, "/m/2.mp3", "/m", "", 1),
    )
    private val artist = Library.build(songs).artistByName.getValue("X")

    @Test
    fun replacedPosterGetsANewFileName() {
        val a = ArtFiles.name(ArtKind.Poster, "X", 1000)
        val b = ArtFiles.name(ArtKind.Poster, "X", 1001)
        assertNotEquals(a, b)
        assertTrue(a.endsWith(".jpg"))
        assertTrue(ArtFiles.name(ArtKind.Logo, "X", 1).endsWith(".png"))
    }

    @Test
    fun customPosterWinsAndChangesModel() {
        val dir = Files.createTempDirectory("art").toFile()
        val first = File(dir, ArtFiles.name(ArtKind.Poster, "X", 1)).apply { writeText("1") }
        val second = File(dir, ArtFiles.name(ArtKind.Poster, "X", 2)).apply { writeText("2") }
        val src = LocalArtistImageSource()
        val m1 = src.image(artist, mapOf("X" to first.path), emptyMap())
        val m2 = src.image(artist, mapOf("X" to second.path), emptyMap())
        assertEquals(first, m1)
        assertEquals(second, m2)
        assertNotEquals(m1, m2)
    }

    @Test
    fun missingPosterFallsBackToMostPlayedAlbum() {
        val src = LocalArtistImageSource()
        val m = src.image(artist, mapOf("X" to "/nope/p.jpg"), mapOf(2L to 5))
        assertEquals(ArtRef(20, 2), m)
    }
}
