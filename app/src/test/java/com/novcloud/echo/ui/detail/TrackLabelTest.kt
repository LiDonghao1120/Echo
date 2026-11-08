package com.novcloud.echo.ui.detail

import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelTest {
    private fun song(id: Long, title: String, track: Int, disc: Int = 1) =
        Song(id, title, "Wren Hollow", "Quiet Rooms", 3, "", "", 180_000, track, disc, 2025, 0, "/m/q/$id.mp3", "/m/q", "audio/mpeg", 100 + id)

    @Test
    fun untaggedTracksShowADashAfterTheNumberedOnes() {
        val album = Library.build(listOf(song(1, "Alder", 1), song(2, "Birch", 0), song(3, "Cedar", 2), song(4, "Damson", 3))).albums.single()
        val numbered = album.songs.any { it.track > 0 }
        assertEquals(listOf("Alder", "Cedar", "Damson", "Birch"), album.songs.map { it.title })
        assertEquals(listOf(1, 2, 3, 0), album.songs.mapIndexed { i, s -> trackLabel(s.track, i, numbered) })
    }

    @Test
    fun albumsWithoutTrackNumbersCountPositions() {
        assertEquals(listOf(1, 2), listOf(0, 0).mapIndexed { i, t -> trackLabel(t, i, albumNumbered = false) })
    }
}
