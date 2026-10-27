package com.novcloud.echo.data.tags

import android.content.Context
import android.net.Uri
import com.novcloud.echo.data.MediaScanner
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Tags MediaStore and MediaMetadataRetriever usually cannot read: APE and WMA files, and APEv2 tags on MP3 files
 * that have no ID3 text. Results are cached by path and size in filesDir/extra_tags.json.
 */
class ExtraTags(private val context: Context) {
    private val file get() = File(context.filesDir, "extra_tags.json")
    private var cache: HashMap<String, AudioTags>? = null
    private var dirty = false
    private val seen = HashSet<String>()

    fun open(uri: Uri, wantCover: Boolean): AudioTags? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { TagFiles.read(ChannelSource(it.channel), wantCover) }
        }
    }.getOrNull()

    @Synchronized
    fun tagsFor(uri: Uri, cacheKey: String): AudioTags? {
        val map = load()
        seen += cacheKey
        map[cacheKey]?.let { return it.takeUnless { t -> t.isEmpty && t.durationMs == 0L } }
        val t = open(uri, wantCover = false) ?: AudioTags()
        map[cacheKey] = t.copy(cover = null)
        dirty = true
        return t.takeUnless { it.isEmpty && it.durationMs == 0L }
    }

    /** Fills a MediaStore song from its own file when the store has nothing useful for it. */
    fun complete(s: Song): Song {
        val ext = s.path.substringAfterLast('.', "").lowercase()
        val own = ext in OWN_FORMATS
        val missing = s.artist == UNKNOWN || s.album == UNKNOWN
        if (!own && !(ext == "mp3" && missing)) return s
        val t = tagsFor(s.uri, "${s.path}|${s.size}") ?: return s
        return merge(s, t, prefer = own)
    }

    @Synchronized
    fun save() {
        val map = cache ?: return
        if (map.keys.retainAll(seen)) dirty = true
        seen.clear()
        if (!dirty) return
        val arr = JSONArray()
        map.forEach { (k, t) ->
            arr.put(
                JSONObject().put("k", k).put("t", t.title).put("a", t.artist).put("al", t.album).put("aa", t.albumArtist)
                    .put("tr", t.track).put("dc", t.disc).put("y", t.year).put("g", t.genre).put("d", t.durationMs),
            )
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
            dirty = false
        }
    }

    private fun load(): HashMap<String, AudioTags> {
        cache?.let { return it }
        val map = HashMap<String, AudioTags>()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("k")] = AudioTags(
                    o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                    o.optInt("tr"), o.optInt("dc"), o.optInt("y"), o.optString("g"), o.optLong("d"),
                )
            }
        }
        cache = map
        return map
    }

    companion object {
        val OWN_FORMATS = setOf("ape", "wma", "asf")

        /** [prefer]: the file's own tags win over what the store guessed (for formats the store cannot parse). */
        fun merge(s: Song, t: AudioTags, prefer: Boolean): Song {
            fun pick(cur: String, new: String, blank: (String) -> Boolean) = when {
                new.isBlank() -> cur
                prefer || blank(cur) -> new
                else -> cur
            }
            val fileTitle = s.path.substringAfterLast('/').substringBeforeLast('.')
            return s.copy(
                title = pick(s.title, t.title) { it.isBlank() || it == fileTitle },
                artist = pick(s.artist, t.artist) { it.isBlank() || it == UNKNOWN },
                album = pick(s.album, t.album) { it.isBlank() || it == UNKNOWN },
                albumArtist = pick(s.albumArtist, t.albumArtist) { it.isBlank() },
                genre = pick(s.genre, MediaScanner.cleanGenre(t.genre)) { it.isBlank() },
                track = if (t.track > 0 && (prefer || s.track <= 0)) t.track else s.track,
                disc = if (t.disc > 0 && (prefer || s.disc <= 1)) t.disc else s.disc,
                year = if (t.year > 0 && (prefer || s.year <= 0)) t.year else s.year,
            )
        }
    }
}
