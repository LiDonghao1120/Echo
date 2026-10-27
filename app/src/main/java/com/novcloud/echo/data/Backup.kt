package com.novcloud.echo.data

import com.novcloud.echo.data.db.Favorite
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.db.SongAlias
import com.novcloud.echo.data.db.SongRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupEntry(val key: String, val position: Int, val addedAt: Long)

data class BackupPlaylist(val name: String, val createdAt: Long, val entries: List<BackupEntry>)

data class BackupSettings(
    val theme: String? = null,
    val minDurationSec: Int? = null,
    val songSort: String? = null,
    val keepScreenOnLyrics: Boolean? = null,
    val scanIncluded: Set<String> = emptySet(),
    val scanExcluded: Set<String> = emptySet(),
    val scanTrees: Set<String> = emptySet(),
    val lyricsTree: String? = null,
)

/** [poster] and [logo] are file names inside the backup's art/ folder. */
data class BackupArtist(val artist: String, val poster: String? = null, val logo: String? = null)

data class BackupData(
    val createdAt: Long = 0,
    val appVersion: String = "",
    val favorites: List<Favorite> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val events: List<PlayEvent> = emptyList(),
    val refs: List<SongRef> = emptyList(),
    val aliases: List<SongAlias> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    val artists: List<BackupArtist> = emptyList(),
)

class BackupException(message: String) : Exception(message)

/**
 * A backup is a zip: manifest.json (format name + version), data.json, events.json and art/ with artist posters
 * and logos. Songs are referred to by stable song key with their tags (refs), so a restore on another phone or after
 * files moved can still find them. Readers accept any version up to [VERSION] and ignore unknown fields.
 */
object BackupCodec {
    const val FORMAT = "echo-backup"
    const val VERSION = 1
    const val ART_DIR = "art/"

    fun write(data: BackupData, art: Map<String, File>, out: OutputStream) {
        val zip = ZipOutputStream(out.buffered())
        fun put(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        put("manifest.json", JSONObject().put("format", FORMAT).put("version", VERSION).put("createdAt", data.createdAt).put("app", data.appVersion).toString())
        put("data.json", dataJson(data).toString())
        put("events.json", JSONArray().apply { data.events.forEach { put(event(it)) } }.toString())
        for ((name, file) in art) {
            if (!file.exists()) continue
            zip.putNextEntry(ZipEntry(ART_DIR + name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** Reads a backup, extracting art into [artDir]. Throws [BackupException] for anything that is not a usable backup. */
    fun read(input: InputStream, artDir: File): BackupData {
        var manifest: JSONObject? = null
        var data: JSONObject? = null
        var events: JSONArray? = null
        artDir.mkdirs()
        val root = artDir.canonicalFile
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == "manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        e.name == "data.json" -> data = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        e.name == "events.json" -> events = JSONArray(zip.readBytes().toString(Charsets.UTF_8))
                        e.name.startsWith(ART_DIR) -> {
                            val f = File(root, e.name.removePrefix(ART_DIR)).canonicalFile
                            if (f.parentFile == root) f.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("不是有效的备份文件")
        }
        val m = manifest ?: throw BackupException("不是回响的备份文件")
        if (m.optString("format") != FORMAT) throw BackupException("不是回响的备份文件")
        if (m.optInt("version") > VERSION) throw BackupException("备份来自更新版本的回响，请先升级")
        val d = data ?: throw BackupException("备份文件不完整")
        return parse(m, d, events ?: JSONArray())
    }

    private fun dataJson(b: BackupData) = JSONObject()
        .put("favorites", JSONArray().apply { b.favorites.forEach { put(JSONObject().put("key", it.songKey).put("addedAt", it.addedAt)) } })
        .put("playlists", JSONArray().apply {
            b.playlists.forEach { p ->
                put(
                    JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("entries", JSONArray().apply {
                        p.entries.forEach { put(JSONObject().put("key", it.key).put("position", it.position).put("addedAt", it.addedAt)) }
                    }),
                )
            }
        })
        .put("songs", JSONArray().apply {
            b.refs.forEach { put(JSONObject().put("key", it.key).put("title", it.title).put("artist", it.artist).put("album", it.album).put("durationMs", it.durationMs).put("updatedAt", it.updatedAt)) }
        })
        .put("aliases", JSONArray().apply { b.aliases.forEach { put(JSONObject().put("from", it.oldKey).put("to", it.newKey)) } })
        .put("settings", JSONObject().apply {
            val s = b.settings
            s.theme?.let { put("theme", it) }
            s.minDurationSec?.let { put("minDurationSec", it) }
            s.songSort?.let { put("songSort", it) }
            s.keepScreenOnLyrics?.let { put("keepScreenOnLyrics", it) }
            s.lyricsTree?.let { put("lyricsTree", it) }
            put("scanIncluded", JSONArray(s.scanIncluded.sorted()))
            put("scanExcluded", JSONArray(s.scanExcluded.sorted()))
            put("scanTrees", JSONArray(s.scanTrees.sorted()))
        })
        .put("artists", JSONArray().apply {
            b.artists.forEach { a -> put(JSONObject().put("name", a.artist).apply { a.poster?.let { put("poster", it) }; a.logo?.let { put("logo", it) } }) }
        })

    private fun event(e: PlayEvent) = JSONArray()
        .put(e.songKey).put(e.songId).put(e.title).put(e.artist).put(e.album).put(e.albumId).put(e.genre)
        .put(e.startedAt).put(e.listenedMs).put(e.songDurationMs).put(e.completed)

    private fun parse(m: JSONObject, d: JSONObject, ev: JSONArray): BackupData {
        fun JSONObject.strOrNull(k: String) = if (has(k) && !isNull(k)) getString(k) else null
        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }.toSet()
        val s = d.optJSONObject("settings") ?: JSONObject()
        return BackupData(
            createdAt = m.optLong("createdAt"),
            appVersion = m.optString("app"),
            favorites = d.optJSONArray("favorites").objects().map { Favorite(it.getString("key"), it.optLong("addedAt")) },
            playlists = d.optJSONArray("playlists").objects().map { p ->
                BackupPlaylist(
                    p.getString("name"), p.optLong("createdAt"),
                    p.optJSONArray("entries").objects().map { BackupEntry(it.getString("key"), it.optInt("position"), it.optLong("addedAt")) },
                )
            },
            events = (0 until ev.length()).mapNotNull { i ->
                val a = ev.optJSONArray(i) ?: return@mapNotNull null
                runCatching {
                    PlayEvent(
                        songKey = a.getString(0), songId = a.getLong(1), title = a.getString(2), artist = a.getString(3), album = a.getString(4),
                        albumId = a.getLong(5), genre = a.getString(6), startedAt = a.getLong(7), listenedMs = a.getLong(8),
                        songDurationMs = a.getLong(9), completed = a.getBoolean(10),
                    )
                }.getOrNull()
            },
            refs = d.optJSONArray("songs").objects().map {
                SongRef(it.getString("key"), it.optString("title"), it.optString("artist"), it.optString("album"), it.optLong("durationMs"), it.optLong("updatedAt"))
            },
            aliases = d.optJSONArray("aliases").objects().map { SongAlias(it.getString("from"), it.getString("to")) },
            settings = BackupSettings(
                theme = s.strOrNull("theme"),
                minDurationSec = if (s.has("minDurationSec")) s.getInt("minDurationSec") else null,
                songSort = s.strOrNull("songSort"),
                keepScreenOnLyrics = if (s.has("keepScreenOnLyrics")) s.getBoolean("keepScreenOnLyrics") else null,
                scanIncluded = s.optJSONArray("scanIncluded").strings(),
                scanExcluded = s.optJSONArray("scanExcluded").strings(),
                scanTrees = s.optJSONArray("scanTrees").strings(),
                lyricsTree = s.strOrNull("lyricsTree"),
            ),
            artists = d.optJSONArray("artists").objects().map { BackupArtist(it.getString("name"), it.strOrNull("poster"), it.strOrNull("logo")) },
        )
    }
}

enum class ImportMode { Merge, Replace }

/**
 * How an imported backup combines with what is on the phone. Replace takes the backup as it is. Merge keeps
 * everything here and adds what is missing: favorites and song tags by key, listening history by (start time,
 * title, artist) so importing the same file twice adds nothing, playlists by name with new songs appended, folder
 * rules where they do not contradict a local rule, and artist images only for artists without one.
 */
object BackupMerge {
    fun combine(current: BackupData, incoming: BackupData, mode: ImportMode): BackupData =
        if (mode == ImportMode.Replace) incoming else merge(current, incoming)

    private fun merge(cur: BackupData, inc: BackupData): BackupData {
        val favKeys = cur.favorites.mapTo(HashSet()) { it.songKey }
        val refKeys = cur.refs.mapTo(HashSet()) { it.key }
        val aliasKeys = cur.aliases.mapTo(HashSet()) { it.oldKey }
        fun eventKey(e: PlayEvent) = "${e.startedAt}|${e.title}|${e.artist}"
        val seen = cur.events.mapTo(HashSet(), ::eventKey)
        val byName = cur.playlists.associateBy { it.name }
        val playlists = cur.playlists.map { p ->
            val add = inc.playlists.firstOrNull { it.name == p.name } ?: return@map p
            val have = p.entries.mapTo(HashSet()) { it.key }
            var pos = (p.entries.maxOfOrNull { it.position } ?: -1) + 1
            p.copy(entries = p.entries + add.entries.sortedBy { it.position }.filter { have.add(it.key) }.map { it.copy(position = pos++) })
        } + inc.playlists.filter { it.name !in byName }
        val cs = cur.settings
        val ins = inc.settings
        val artists = LinkedHashMap<String, BackupArtist>()
        cur.artists.forEach { artists[it.artist] = it }
        inc.artists.forEach { a ->
            val c = artists[a.artist]
            artists[a.artist] = if (c == null) a else c.copy(poster = c.poster ?: a.poster, logo = c.logo ?: a.logo)
        }
        return cur.copy(
            favorites = cur.favorites + inc.favorites.filter { favKeys.add(it.songKey) },
            playlists = playlists,
            events = (cur.events + inc.events.filter { seen.add(eventKey(it)) }).sortedBy { it.startedAt },
            refs = cur.refs + inc.refs.filter { refKeys.add(it.key) },
            aliases = cur.aliases + inc.aliases.filter { aliasKeys.add(it.oldKey) },
            settings = cs.copy(
                scanIncluded = cs.scanIncluded + (ins.scanIncluded - cs.scanExcluded),
                scanExcluded = cs.scanExcluded + (ins.scanExcluded - cs.scanIncluded),
                scanTrees = cs.scanTrees + ins.scanTrees,
                lyricsTree = cs.lyricsTree ?: ins.lyricsTree,
            ),
            artists = artists.values.toList(),
        )
    }
}
