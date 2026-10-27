package com.novcloud.echo.data

import com.novcloud.echo.data.db.SongRef
import java.text.Normalizer

/**
 * Song keys survive what MediaStore _IDs do not: a media database rebuild, a reinstall, a restore on another phone.
 * The key is the file's path below its storage volume (case folded, NFC), so internal storage, an SD card or a
 * second user profile give the same key for the same folder layout. A document without a file path keys on its
 * document id. The runtime [Song.id] is a hash of the key.
 */
object SongKeys {
    private val volumeRoots = listOf(
        Regex("^/storage/emulated/\\d+/"),
        Regex("^/storage/self/primary/"),
        Regex("^/mnt/media_rw/[^/]+/"),
        Regex("^/mnt/sdcard/"),
        Regex("^/sdcard/"),
        Regex("^/storage/[^/]+/"),
    )

    fun relative(path: String): String {
        val p = path.replace('\\', '/')
        for (r in volumeRoots) r.find(p)?.let { return p.substring(it.range.last + 1) }
        return p.trimStart('/')
    }

    fun of(path: String): String = "p:" + fold(relative(path))

    fun ofDocument(documentId: String): String = "doc:$documentId"

    fun id(key: String): Long = SafPaths.stableId(key)

    fun legacyId(s: Song): Long = when {
        s.mediaId > 0 -> s.mediaId
        s.key.startsWith("doc:") -> SafPaths.stableId(s.key.removePrefix("doc:"))
        else -> SafPaths.stableId(s.path.lowercase())
    }

    fun legacyKey(oldId: Long): String = "id:$oldId"

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()

    /** Two files whose relative paths collide (same layout on two volumes): the one off primary storage gets its full path as key. */
    fun unique(songs: List<Song>): List<Song> {
        val groups = songs.groupBy { it.key }
        if (groups.size == songs.size) return songs
        val out = ArrayList<Song>(songs.size)
        for ((_, list) in groups) {
            if (list.size == 1) { out += list[0]; continue }
            val keep = list.firstOrNull { it.path.startsWith("/storage/emulated/") } ?: list[0]
            for (s in list) {
                if (s === keep) out += s else {
                    val k = "v:" + fold(s.path)
                    out += s.copy(key = k, id = id(k))
                }
            }
        }
        return out
    }
}

/**
 * Matches saved references (favorites, playlist entries, listening history, backups) to the current library.
 * A key in the library is kept. A legacy "id:" key from before 0.7 maps to the file that had that id. Any other
 * orphan, usually a moved or renamed file, maps to the one library song with the same title, artist, album and
 * duration (±2 s), using the file name to break a tie. Unmatched references stay as they are for a later scan.
 */
object Rematch {
    const val DURATION_SLACK_MS = 2_000L

    class Plan(val moves: Map<String, String>, val refs: List<SongRef>)

    fun plan(referenced: Collection<String>, refs: Map<String, SongRef>, lib: Library, now: Long = System.currentTimeMillis()): Plan {
        val byKey = lib.songByKey
        val moves = LinkedHashMap<String, String>()
        val orphans = referenced.filter { it !in byKey }.sorted()
        val byMeta by lazy { lib.songs.groupBy { meta(it.title, it.artist, it.album) } }
        for (o in orphans) {
            val legacy = if (o.startsWith("id:")) o.removePrefix("id:").toLongOrNull()?.let { lib.songByLegacyId[it] } else null
            val target = legacy ?: refs[o]?.let { r -> byMeta[meta(r.title, r.artist, r.album)]?.let { pick(r, it) } }
            if (target != null) moves[o] = target.key
        }
        val live = (referenced.filter { it in byKey } + moves.values).toSet()
        val update = live.mapNotNull { k ->
            val s = byKey[k] ?: return@mapNotNull null
            val old = refs[k]
            if (old != null && old.title == s.title && old.artist == s.artist && old.album == s.album && old.durationMs == s.durationMs) null
            else SongRef(k, s.title, s.artist, s.album, s.durationMs, now)
        }
        return Plan(moves, update)
    }

    private fun pick(r: SongRef, candidates: List<Song>): Song? {
        if (r.title.isBlank()) return null
        val close = candidates.filter { r.durationMs <= 0 || it.durationMs <= 0 || kotlin.math.abs(it.durationMs - r.durationMs) <= DURATION_SLACK_MS }
        if (close.size == 1) return close[0]
        val name = fileName(r.key)
        return close.filter { fileName(it.key) == name }.singleOrNull()
    }

    private fun fileName(key: String) = key.substringAfterLast('/').substringAfterLast(':')

    private val spaces = Regex("\\s+")

    fun meta(title: String, artist: String, album: String): String =
        listOf(title, artist, album).joinToString("|") { Normalizer.normalize(it, Normalizer.Form.NFC).trim().lowercase().replace(spaces, " ") }
}
