package com.novcloud.echo.data

data class SearchResults(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

/**
 * Library search over titles, artists and albums: plain substring first, then pinyin (full, initials or mixed with
 * Han characters, see [SearchText]). Prepared texts are cached per distinct string for the library's lifetime.
 */
class LibrarySearch(private val lib: Library) {
    private val texts = HashMap<String, SearchText>()

    private fun text(s: String): SearchText = synchronized(texts) { texts.getOrPut(s) { SearchText.of(s) } }

    /** 0 prefix, 1 substring, 2 pinyin from the start, 3 pinyin elsewhere; null for no match. */
    fun tier(s: String, q: String): Int? {
        val lower = s.lowercase()
        if (lower.startsWith(q)) return 0
        if (lower.contains(q)) return 1
        val at = text(s).match(q)
        return if (at < 0) null else if (at == 0) 2 else 3
    }

    fun query(raw: String): SearchResults {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        fun best(vararg fields: String): Int? = fields.withIndex().mapNotNull { (i, f) -> tier(f, q)?.let { it * 3 + i } }.minOrNull()
        val songs = lib.songs.mapNotNull { s -> best(s.title, s.artist, s.album)?.let { s to it } }
            .sortedWith(compareBy({ it.second }, { it.first.titleKey })).take(60).map { it.first }
        val albums = lib.albums.mapNotNull { a -> best(a.title, a.artist)?.let { a to it } }
            .sortedBy { it.second }.take(20).map { it.first }
        val artists = lib.artists.mapNotNull { a -> tier(a.name, q)?.let { a to it } }
            .sortedBy { it.second }.take(20).map { it.first }
        return SearchResults(songs, albums, artists)
    }
}
