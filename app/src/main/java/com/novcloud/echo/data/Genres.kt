package com.novcloud.echo.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MediaStore's GENRE column is empty on many devices (Android 11+ MediaProvider and several OEM scanners skip it),
 * so genres are filled from the legacy Genres tables first and then from the file tags, cached by path + size.
 */
class GenreResolver(private val context: Context) {
    private class Entry(val size: Long, val genre: String)

    private val file = File(context.filesDir, "genre_cache.json")
    private var cache: HashMap<String, Entry>? = null

    fun apply(songs: List<Song>): List<Song> {
        if (songs.none { it.genre.isBlank() }) return songs
        val map = load()
        val members = if (songs.any { it.genre.isBlank() && it.docUri.isEmpty() && map[key(it)]?.size != it.size }) storeGenres() else emptyMap()
        return songs.map { s ->
            if (s.genre.isNotBlank()) s
            else members[s.mediaId]?.let { s.copy(genre = it) }
                ?: map[key(s)]?.takeIf { it.size == s.size && it.genre.isNotEmpty() }?.let { s.copy(genre = it.genre) }
                ?: s
        }
    }

    fun unresolved(songs: List<Song>): List<Song> {
        val map = load()
        return songs.filter { s -> s.genre.isBlank() && map[key(s)]?.size != s.size }
    }

    fun probe(song: Song): String {
        val m = MediaMetadataRetriever()
        val g = try {
            m.setDataSource(context, song.uri)
            MediaScanner.cleanGenre(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { m.release() }
        }
        synchronized(this) { load()[key(song)] = Entry(song.size, g) }
        return g
    }

    fun save() = synchronized(this) {
        val map = cache ?: return@synchronized
        val arr = JSONArray()
        map.forEach { (k, e) -> arr.put(JSONObject().put("p", k).put("s", e.size).put("g", e.genre)) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    private fun key(s: Song) = s.path.lowercase()

    private fun load(): HashMap<String, Entry> = synchronized(this) {
        cache?.let { return it }
        val map = HashMap<String, Entry>()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("p")] = Entry(o.getLong("s"), o.optString("g"))
            }
        }
        cache = map
        map
    }

    private fun storeGenres(): Map<Long, String> {
        val out = HashMap<Long, String>()
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null,
            )?.use { g ->
                while (g.moveToNext()) {
                    val name = MediaScanner.cleanGenre(g.getString(1))
                    if (name.isEmpty()) continue
                    runCatching {
                        resolver.query(
                            MediaStore.Audio.Genres.Members.getContentUri("external", g.getLong(0)),
                            arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null,
                        )?.use { m -> while (m.moveToNext()) out.putIfAbsent(m.getLong(0), name) }
                    }
                }
            }
        }
        return out
    }
}
