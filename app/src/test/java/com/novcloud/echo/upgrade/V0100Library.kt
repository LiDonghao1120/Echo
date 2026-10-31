package com.novcloud.echo.upgrade

import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.PlayEvent

/**
 * A library as v0.10.0 saved it, with the tag text it read (fictional names): one artist under several spellings
 * (trailing NUL, BOM, full/half-width brackets), each with its own album id for the same album title, an ID3v2.4
 * NUL multi-value artist, a NUL value that repeats the same artist, and genres that differ only by case and NUL.
 */
object V0100Library {
    const val BASE = "云笙（白小舟）"
    val spellings = listOf(BASE, "$BASE\u0000", "\uFEFF$BASE", "云笙 (白小舟)")

    private var nextId = 1000L

    private fun song(title: String, artist: String, album: String, albumId: Long, genre: String, track: Int, albumArtist: String = "") = (nextId++).let { id ->
        Song(
            id = id, title = title, artist = artist, album = album, albumId = albumId, albumArtist = albumArtist, genre = genre,
            durationMs = 180_000L + id % 60 * 1000, track = track, disc = 1, year = 2019 + (albumId % 5).toInt(),
            dateAdded = 1_700_000_000L + id * 60, path = "/storage/emulated/0/Music/a$albumId/$id.mp3",
            folder = "/storage/emulated/0/Music/a$albumId", mimeType = "audio/mpeg", size = 5_000_000 + id, mediaId = id,
        )
    }

    val songs: List<Song> = buildList {
        spellings.forEachIndexed { i, a ->
            repeat(4) { t -> add(song("雾港 ${i}_$t", a, "雾港", 10L + i, if (i % 2 == 0) "Pop" else "pop\u0000", t + 1, albumArtist = a)) }
            add(song("Single $i", a, "单曲 $i", 20L + i, "Folk", 1))
        }
        repeat(3) { t -> add(song("Duet $t", "Nova Lark\u0000Ivo Fen", "Two Rooms", 30, "Indie", t + 1)) }
        repeat(2) { t -> add(song("Echo $t", "$BASE\u0000$BASE", "雾港", 31, "Pop", t + 5)) }
        add(song("Feat", "Nova Lark feat. $BASE\u0000", "Lights", 32, "Pop", 1))
        add(song("Spaced", "Ivo Fen\u00A0x\u00A0Nova Lark", "Lights", 32, "Pop", 2))
        add(song("Untagged", "<unknown>", "<unknown>", 33, "", 1))
        add(song("Blank genre", "Ivo Fen", "Lights", 32, "\uFEFF", 3))
        for (n in 0 until 150) add(song("Filler $n", "Artist ${n % 90}", "Album ${n % 40}", 100L + n % 40, listOf("Rock", "Jazz", "R&B")[n % 3], n / 40 + 1))
    }

    fun events(now: Long): List<PlayEvent> = songs.flatMapIndexed { i, s ->
        List(1 + i % 4) { k ->
            val t = now - (i * 7L + k * 31L) % (60L * 86_400_000L) - 60_000
            PlayEvent(
                songId = s.id, title = s.title, artist = s.artist, album = s.album, albumId = s.albumId, genre = s.genre,
                startedAt = t, listenedMs = s.durationMs * (1 + k % 2) / 2, songDurationMs = s.durationMs, completed = k % 2 == 1, songKey = s.key,
            )
        }
    }.sortedBy { it.startedAt }
}
