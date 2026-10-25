package com.novcloud.echo.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore

data class AudioFolder(val path: String, val count: Int, val music: Int)

class MediaScanner(private val context: Context) {

    private class Row(val song: Song, val isMusic: Boolean)

    fun folders(): List<AudioFolder> {
        val counts = HashMap<String, IntArray>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.IS_MUSIC), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val folder = c.getString(0).orEmpty().substringBeforeLast('/', "")
                    if (folder.isEmpty()) continue
                    val a = counts.getOrPut(folder) { IntArray(2) }
                    a[0]++
                    if (c.getInt(1) != 0) a[1]++
                }
            }
        }
        return counts.map { (p, a) -> AudioFolder(p, a[0], a[1]) }.sortedBy { it.path.lowercase() }
    }

    class Result(val songs: List<Song>, val knownPaths: Set<String>)

    fun scan(rules: FolderRules, minDurationMs: Long): Result {
        val rows = query()
        val known = rows.mapTo(HashSet(rows.size)) { it.song.path.lowercase() }
        val out = ArrayList<Song>(rows.size)
        var probes = 0
        for (r in rows) {
            if (!rules.accepts(r.song.folder, r.isMusic)) continue
            var s = r.song
            if (s.durationMs <= 0 && probes < 400) {
                probes++
                val d = TagProbe.duration(context, s.uri)
                if (d > 0) s = s.copy(durationMs = d)
            }
            if (s.durationMs < minDurationMs || s.durationMs <= 0) continue
            out += s
        }
        return Result(out, known)
    }

    private fun query(): List<Row> {
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
            add(MediaStore.Audio.Media.IS_MUSIC)
            if (Build.VERSION.SDK_INT >= 30) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()
        val out = ArrayList<Row>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
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
            val iMusic = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
            val iAlbumArtist = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val iDisc = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
            while (c.moveToNext()) {
                val path = c.getString(iData).orEmpty()
                val rawTrack = c.getInt(iTrack)
                val discFromCol = if (iDisc >= 0) c.getString(iDisc)?.substringBefore('/')?.trim()?.toIntOrNull() else null
                val song = Song(
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
                out += Row(song, c.getInt(iMusic) != 0)
            }
        }
        return out
    }

    companion object {
        fun cleanGenre(raw: String?): String {
            val first = raw.orEmpty().split('\u0000', ';', '；', '/', ',', '|', '、').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            if (first == UNKNOWN) return ""
            val numeric = Regex("^\\((\\d{1,3})\\).*$|^(\\d{1,3})$").find(first)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
            if (numeric != null) return ID3_GENRES.getOrNull(numeric) ?: ""
            return first
        }

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
