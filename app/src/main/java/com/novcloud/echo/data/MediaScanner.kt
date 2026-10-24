package com.novcloud.echo.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore

class MediaScanner(private val context: Context) {

    fun scan(minDurationMs: Long): List<Song> {
        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= 30) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val out = ArrayList<Song>()
        resolver.query(uri, cols, selection, arrayOf(minDurationMs.toString()), null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAlbumArtist = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val iDisc = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
            while (c.moveToNext()) {
                val path = c.getString(iData).orEmpty()
                val rawTrack = c.getInt(iTrack)
                val discFromCol = if (iDisc >= 0) c.getString(iDisc)?.substringBefore('/')?.trim()?.toIntOrNull() else null
                out += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.'),
                    artist = c.getString(iArtist)?.takeIf { it.isNotBlank() } ?: UNKNOWN,
                    album = c.getString(iAlbum)?.takeIf { it.isNotBlank() } ?: UNKNOWN,
                    albumId = c.getLong(iAlbumId),
                    albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist).orEmpty() else "",
                    genre = if (iGenre >= 0) cleanGenre(c.getString(iGenre)) else "",
                    durationMs = c.getLong(iDur),
                    track = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack,
                    disc = discFromCol ?: if (rawTrack >= 1000) rawTrack / 1000 else 1,
                    year = c.getInt(iYear),
                    dateAdded = c.getLong(iAdded) * 1000,
                    path = path,
                    folder = path.substringBeforeLast('/', ""),
                    mimeType = c.getString(iMime).orEmpty(),
                    size = c.getLong(iSize),
                )
            }
        }
        if (Build.VERSION.SDK_INT < 30 && out.isNotEmpty()) return withLegacyGenres(out)
        return out
    }

    private fun withLegacyGenres(songs: List<Song>): List<Song> {
        val map = HashMap<Long, String>()
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null,
            )?.use { g ->
                while (g.moveToNext()) {
                    val gid = g.getLong(0)
                    val name = cleanGenre(g.getString(1))
                    if (name.isEmpty()) continue
                    resolver.query(
                        MediaStore.Audio.Genres.Members.getContentUri("external", gid),
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null,
                    )?.use { m -> while (m.moveToNext()) map[m.getLong(0)] = name }
                }
            }
        }
        return if (map.isEmpty()) songs else songs.map { s -> map[s.id]?.let { s.copy(genre = it) } ?: s }
    }

    private fun cleanGenre(raw: String?): String {
        val g = raw?.trim().orEmpty()
        if (g.isEmpty() || g == UNKNOWN) return ""
        val numeric = Regex("^\\(?(\\d{1,3})\\)?$").find(g)?.groupValues?.get(1)?.toIntOrNull()
        if (numeric != null) return ID3_GENRES.getOrNull(numeric) ?: ""
        return g.split(';', '/', ',').first().trim()
    }

    companion object {
        private val ID3_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
            "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
            "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
            "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        )
    }
}
