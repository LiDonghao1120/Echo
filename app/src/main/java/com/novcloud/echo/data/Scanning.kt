package com.novcloud.echo.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScanProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

class FolderRules(included: Collection<String>, excluded: Collection<String>) {
    private val inc = included.map(::norm).filter { it.isNotEmpty() }
    private val exc = excluded.map(::norm).filter { it.isNotEmpty() }

    fun accepts(folder: String, isMusic: Boolean): Boolean {
        val f = norm(folder)
        val i = inc.filter { under(f, it) }.maxOfOrNull { it.length } ?: -1
        val e = exc.filter { under(f, it) }.maxOfOrNull { it.length } ?: -1
        if (e > i) return false
        if (i >= 0) return true
        return isMusic
    }

    fun excludes(folder: String): Boolean {
        val f = norm(folder)
        val i = inc.filter { under(f, it) }.maxOfOrNull { it.length } ?: -1
        val e = exc.filter { under(f, it) }.maxOfOrNull { it.length } ?: -1
        return e > i
    }

    companion object {
        fun norm(p: String) = p.trim().trimEnd('/')
        fun under(folder: String, root: String) = folder == root || folder.startsWith("$root/")
    }
}

object SafPaths {
    private const val EXTERNAL = "com.android.externalstorage.documents"

    fun pathOf(authority: String?, documentId: String): String? {
        if (authority == EXTERNAL) {
            val volume = documentId.substringBefore(':', "")
            val rel = documentId.substringAfter(':', "").trim('/')
            val base = when (volume) {
                "" -> return null
                "primary" -> "/storage/emulated/0"
                "home" -> "/storage/emulated/0/Documents"
                else -> "/storage/$volume"
            }
            return if (rel.isEmpty()) base else "$base/$rel"
        }
        if (documentId.startsWith("raw:/")) return documentId.removePrefix("raw:")
        return null
    }

    fun treePath(tree: Uri): String? = runCatching {
        pathOf(tree.authority, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    fun label(tree: String): String {
        val u = Uri.parse(tree)
        val path = treePath(u)
        if (path != null) return path.removePrefix("/storage/emulated/0").ifEmpty { "/" }
        return Uri.decode(tree).substringAfterLast(':').ifEmpty { "/" }
    }

    fun stableId(key: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in key) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return -((h ushr 2) or 1L)
    }
}

object Dedupe {
    fun merge(primary: List<Song>, extra: List<Song>): List<Song> {
        val paths = HashSet<String>()
        val prints = HashSet<String>()
        val out = ArrayList<Song>(primary.size + extra.size)
        for (s in primary + extra) {
            val p = s.path.lowercase()
            if (p.isNotEmpty() && !paths.add(p)) continue
            if (!prints.add(fingerprint(s))) continue
            out += s
        }
        return out
    }

    fun fingerprint(s: Song): String =
        "${s.title.trim().lowercase()}|${s.artist.trim().lowercase()}|${s.durationMs / 1000}|${s.size}"
}

object TagProbe {
    fun duration(context: Context, uri: Uri): Long {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(context, uri)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { m.release() }
        }
    }
}

class TreeScanner(private val context: Context) {
    private class Doc(val uri: Uri, val docId: String, val name: String, val size: Long, val modified: Long, val path: String?, val folder: String)

    private class Tags(
        val modified: Long, val size: Long, val title: String, val artist: String, val album: String, val albumArtist: String,
        val genre: String, val duration: Long, val track: Int, val disc: Int, val year: Int, val mime: String,
    )

    private val cacheFile get() = File(context.filesDir, "tree_tags.json")
    private var cache: HashMap<String, Tags>? = null

    fun scan(trees: Collection<String>, rules: FolderRules, known: Set<String>, onProgress: (ScanProgress) -> Unit): List<Song> {
        val docs = ArrayList<Doc>()
        for (t in trees) {
            val tree = Uri.parse(t)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val rootLabel = SafPaths.label(t)
            walk(tree, rootId, rootLabel, rules, docs, 0)
        }
        val fresh = docs.distinctBy { it.path?.lowercase() ?: it.docId }.filter { d -> d.path == null || d.path.lowercase() !in known }
        val tags = loadCache()
        val out = ArrayList<Song>()
        var dirty = false
        fresh.forEachIndexed { i, d ->
            val key = d.uri.toString()
            var t = tags[key]
            if (t == null || t.modified != d.modified || t.size != d.size) {
                t = read(d)
                tags[key] = t
                dirty = true
            }
            if (i % 8 == 0 || i == fresh.lastIndex) onProgress(ScanProgress(i + 1, fresh.size))
            if (t.duration > 0) out += toSong(d, t)
        }
        if (dirty) saveCache(tags)
        return out
    }

    /**
     * Cheap change check: hashes every entry (id, mtime, size) listed in the tree root and its first-level folders.
     * A file added, removed or replaced up to three levels deep changes the mtime of a folder seen here.
     */
    fun signature(tree: String): Long? {
        val uri = Uri.parse(tree)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val root = list(uri, rootId) ?: return null
        var sum = root.size.toLong()
        for (e in root) {
            sum += e.hash
            if (e.dir) list(uri, e.id)?.forEach { sum += it.hash * 31 + 1 }
        }
        return sum
    }

    private class Entry(val id: String, val dir: Boolean, val hash: Long)

    private fun list(tree: Uri, docId: String): List<Entry>? = runCatching {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val size = if (c.isNull(2)) 0 else c.getLong(2)
                val modified = if (c.isNull(3)) 0 else c.getLong(3)
                out += Entry(id, c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR, SafPaths.stableId("$id|$modified|$size"))
            }
            out
        }
    }.getOrNull()

    private fun walk(tree: Uri, docId: String, folderLabel: String, rules: FolderRules, out: MutableList<Doc>, depth: Int) {
        if (depth > 14) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith('.')) continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += id to name
                        continue
                    }
                    if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXT) continue
                    val path = SafPaths.pathOf(tree.authority, id)
                    out += Doc(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id), id, name,
                        if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                        path, path?.substringBeforeLast('/') ?: folderLabel,
                    )
                }
            }
        }
        for ((id, name) in dirs) {
            val path = SafPaths.pathOf(tree.authority, id)
            if (path != null && rules.excludes(path)) continue
            walk(tree, id, "$folderLabel/$name", rules, out, depth + 1)
        }
    }

    private fun read(d: Doc): Tags {
        val m = MediaMetadataRetriever()
        fun k(key: Int) = runCatching { m.extractMetadata(key) }.getOrNull()?.trim().orEmpty()
        return try {
            m.setDataSource(context, d.uri)
            val track = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').trim().toIntOrNull() ?: 0
            val disc = k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').trim().toIntOrNull() ?: 1
            Tags(
                d.modified, d.size,
                title = k(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = MediaScanner.cleanGenre(k(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                duration = k(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                track = track, disc = disc,
                year = k(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                mime = k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        } catch (_: Throwable) {
            Tags(d.modified, d.size, "", "", "", "", "", 0, 0, 1, 0, "")
        } finally {
            runCatching { m.release() }
        }
    }

    private fun toSong(d: Doc, t: Tags): Song {
        val artist = t.artist.ifBlank { UNKNOWN }
        val album = t.album.ifBlank { UNKNOWN }
        val key = d.path?.let(SongKeys::of) ?: SongKeys.ofDocument(d.docId)
        return Song(
            id = SongKeys.id(key),
            title = t.title.ifBlank { d.name.substringBeforeLast('.') },
            artist = artist,
            album = album,
            albumId = SafPaths.stableId("album|${album.lowercase()}|${t.albumArtist.ifBlank { d.folder }.lowercase()}"),
            albumArtist = t.albumArtist,
            genre = t.genre,
            durationMs = t.duration,
            track = t.track,
            disc = t.disc,
            year = t.year,
            dateAdded = d.modified,
            path = d.path ?: "${d.folder}/${d.name}",
            folder = d.folder,
            mimeType = t.mime,
            size = d.size,
            docUri = d.uri.toString(),
            key = key,
        )
    }

    private fun loadCache(): HashMap<String, Tags> {
        cache?.let { return it }
        val map = HashMap<String, Tags>()
        runCatching {
            val arr = JSONArray(cacheFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("u")] = Tags(
                    o.getLong("m"), o.getLong("s"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                    o.optString("g"), o.optLong("d"), o.optInt("tr"), o.optInt("dc", 1), o.optInt("y"), o.optString("mi"),
                )
            }
        }
        cache = map
        return map
    }

    private fun saveCache(map: Map<String, Tags>) {
        val arr = JSONArray()
        map.forEach { (u, t) ->
            arr.put(
                JSONObject().put("u", u).put("m", t.modified).put("s", t.size).put("t", t.title).put("a", t.artist)
                    .put("al", t.album).put("aa", t.albumArtist).put("g", t.genre).put("d", t.duration).put("tr", t.track)
                    .put("dc", t.disc).put("y", t.year).put("mi", t.mime),
            )
        }
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(cacheFile)
        }
    }

    fun forget(tree: String) {
        val map = loadCache()
        if (map.keys.removeAll { it.startsWith(tree) }) saveCache(map)
    }

    companion object {
        val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "ape", "wma", "alac", "aiff", "aif")
    }
}
