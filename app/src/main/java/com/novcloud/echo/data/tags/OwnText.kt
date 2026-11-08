package com.novcloud.echo.data.tags

import android.content.Context
import android.net.Uri
import com.novcloud.echo.data.MediaScanner
import com.novcloud.echo.data.Names
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import com.novcloud.echo.data.lyrics.Id3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** Title, artist, album, album artist and genre as the file's own ID3 tags spell them, decoded by [TagText]. */
data class OwnText(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
) {
    companion object {
        /** ID3v2 text frames, with ID3v1 filling fields v2 does not have. Null when the file has neither. */
        fun read(src: ByteSource): OwnText? {
            val v2 = runCatching { Id3.textFrames(src, 0) }.getOrNull()?.mapValues { Id3.decodeText(it.value) }
            val v1 = runCatching { id3v1(src) }.getOrNull()
            if (v2 == null && v1 == null) return null
            fun f(name: String) = v2?.get(name)?.takeIf { it.isNotBlank() } ?: v1?.get(name).orEmpty()
            return OwnText(f("title"), f("artist"), f("album"), f("albumArtist"), f("genre"))
        }

        private fun id3v1(src: ByteSource): Map<String, String>? {
            if (src.size < 128) return null
            val b = src.read(src.size - 128, 128)
            if (b.size < 128 || b[0] != 'T'.code.toByte() || b[1] != 'A'.code.toByte() || b[2] != 'G'.code.toByte()) return null
            fun field(from: Int, len: Int): String {
                var end = from
                while (end < from + len && b[end].toInt() != 0) end++
                return TagText.latin1(b.copyOfRange(from, end)).trim()
            }
            return mapOf("title" to field(3, 30), "artist" to field(33, 30), "album" to field(63, 30))
        }

        /**
         * [s] with each text field the store misread replaced by the file's own reading: only fields that look misread
         * ([TagText.suspicious]) where [own] reads cleanly and names something else.
         */
        fun merge(s: Song, own: OwnText): Song {
            fun pick(cur: String, mine: String): String {
                if (mine.isBlank() || !TagText.suspicious(cur) || TagText.suspicious(mine)) return cur
                return if (Names.clean(cur) != Names.clean(mine)) mine else cur
            }
            val artist = if (s.artist == UNKNOWN) s.artist else pick(s.artist, own.artist)
            val album = if (s.album == UNKNOWN) s.album else pick(s.album, own.album)
            val genre = pick(s.genre, MediaScanner.cleanGenre(own.genre))
            val title = pick(s.title, own.title)
            val albumArtist = pick(s.albumArtist, own.albumArtist)
            if (title === s.title && artist === s.artist && album === s.album && albumArtist === s.albumArtist && genre === s.genre) return s
            return s.copy(title = title, artist = artist, album = album, albumArtist = albumArtist, genre = genre)
        }

        /** Some field looks misread and the text alone cannot be put right ([TagText.unexplained]). */
        fun needsCheck(s: Song): Boolean =
            TagText.unexplained(s.title) || TagText.unexplained(s.artist) || TagText.unexplained(s.album) ||
                TagText.unexplained(s.albumArtist) || TagText.unexplained(s.genre)
    }
}

/**
 * Re-reads the ID3 text of songs whose store text looks misread (see [OwnText.merge]); results are cached by path,
 * size and modification time in filesDir/[fileName], pruned to the songs seen in the last pass.
 */
class OwnTextFix(private val context: Context, private val fileName: String) {
    private val file get() = File(context.filesDir, fileName)
    private var cache: HashMap<String, OwnText>? = null
    private val seen = HashSet<String>()
    private var dirty = false

    var reads = 0
        private set
    var fixed = 0
        private set

    @Synchronized
    fun fix(s: Song): Song {
        if (!OwnText.needsCheck(s)) return s
        val map = load()
        val key = "${s.path}|${s.size}|${s.modified}"
        seen += key
        val own = map[key] ?: (open(s.uri) ?: OwnText()).also { map[key] = it; dirty = true; reads++ }
        return OwnText.merge(s, own).also { if (it !== s) fixed++ }
    }

    private fun open(uri: Uri): OwnText? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { OwnText.read(ChannelSource(it.channel)) }
        }
    }.getOrNull()

    @Synchronized
    fun save() {
        if (reads > 0) com.novcloud.echo.diag.DiagLog.i("EchoNames", "own tags: read $reads files, took the file's own text for $fixed songs")
        reads = 0
        fixed = 0
        val map = cache ?: return
        if (map.keys.retainAll(seen)) dirty = true
        seen.clear()
        if (!dirty) return
        val arr = JSONArray()
        map.forEach { (k, t) ->
            arr.put(JSONObject().put("k", k).put("t", t.title).put("a", t.artist).put("al", t.album).put("aa", t.albumArtist).put("g", t.genre))
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
            dirty = false
        }
    }

    private fun load(): HashMap<String, OwnText> {
        cache?.let { return it }
        val map = HashMap<String, OwnText>()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("k")] = OwnText(o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"), o.optString("g"))
            }
        }
        cache = map
        return map
    }
}
