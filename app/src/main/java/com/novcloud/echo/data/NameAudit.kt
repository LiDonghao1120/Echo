package com.novcloud.echo.data

import com.novcloud.echo.data.tags.TagText

/**
 * What a scan did to misread tag text and what still looks wrong, for the diagnostic log (tag EchoNames): fields
 * whose text was repaired, artists that became one because of it, and names that still look like mojibake.
 */
object NameAudit {
    enum class Kind { Repaired, Merged, Suspicious }

    data class Finding(val kind: Kind, val field: String, val text: String, val fixed: String, val songs: Int, val sample: String)

    private val FIELDS: List<Pair<String, (Song) -> String>> = listOf(
        "title" to Song::title, "artist" to Song::artist, "album" to Song::album, "albumArtist" to Song::albumArtist, "genre" to Song::genre,
    )

    /** [raw] are songs as read, [cleaned] the same songs after [Song.cleaned] (same order). */
    fun find(raw: List<Song>, cleaned: List<Song>): List<Finding> {
        val repaired = LinkedHashMap<Pair<String, String>, MutableList<Song>>()
        val suspicious = LinkedHashMap<Pair<String, String>, MutableList<Song>>()
        val spellings = HashMap<String, MutableSet<String>>()
        for (i in raw.indices) {
            val r = raw[i]
            val c = cleaned.getOrNull(i) ?: continue
            for ((field, get) in FIELDS) {
                val before = get(r)
                if (before.isEmpty()) continue
                Names.values(before).forEach { v ->
                    if (TagText.repair(v) != null) repaired.getOrPut(field to v) { ArrayList() } += r
                }
                val after = get(c)
                if (TagText.unexplained(after) || TagText.cjkInsideWord(after)) suspicious.getOrPut(field to after) { ArrayList() } += r
            }
            for (a in Names.values(r.artist).flatMap(::splitArtists) + Names.values(r.albumArtist).flatMap(::splitArtists)) {
                if (a.isNotBlank()) spellings.getOrPut(Names.key(a)) { LinkedHashSet() } += a
            }
        }
        val out = ArrayList<Finding>()
        suspicious.forEach { (k, l) -> out += Finding(Kind.Suspicious, k.first, k.second, "", l.size, sample(l)) }
        spellings.values.filter { set -> set.size > 1 && set.any { ArtistArtRows.misread(it) } }.forEach { set ->
            val shown = set.firstOrNull { !ArtistArtRows.misread(it) } ?: Names.clean(set.first())
            out += Finding(Kind.Merged, "artist", set.joinToString(" | "), shown, set.size, "")
        }
        repaired.forEach { (k, l) -> out += Finding(Kind.Repaired, k.first, k.second, Names.clean(k.second), l.size, sample(l)) }
        return out
    }

    private fun sample(songs: List<Song>) = songs.first().path.substringAfterLast('/')

    fun describe(f: Finding): String = when (f.kind) {
        Kind.Suspicious -> "suspicious ${f.field} \"${f.text}\" [${Names.codepoints(f.text)}] (${f.songs} songs, e.g. ${f.sample})"
        Kind.Merged -> "merged after repair: ${f.text} -> \"${f.fixed}\""
        Kind.Repaired -> "repaired ${f.field} \"${f.text}\" [${Names.codepoints(f.text)}] -> \"${f.fixed}\" (${f.songs} songs, e.g. ${f.sample})"
    }

    fun summary(found: List<Finding>): String {
        val r = found.filter { it.kind == Kind.Repaired }
        return "audit: repaired ${r.size} distinct field values in ${r.sumOf { it.songs }} song fields, " +
            "${found.count { it.kind == Kind.Merged }} artists merged, ${found.count { it.kind == Kind.Suspicious }} names still suspicious"
    }
}
