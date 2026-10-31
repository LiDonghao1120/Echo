package com.novcloud.echo.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val genre: String,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val year: Int,
    val dateAdded: Long,
    val path: String,
    val folder: String,
    val mimeType: String,
    val size: Long,
    val docUri: String = "",
    val key: String = SongKeys.of(path),
    val mediaId: Long = 0,
) {
    val uri: Uri get() = if (docUri.isNotEmpty()) Uri.parse(docUri) else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
    val art: ArtRef get() = ArtRef(albumId, artId, docUri)
    internal val artId: Long get() = if (docUri.isEmpty()) mediaId else id
    val artists: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { splitArtists(artist) }
    val titleKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { sortKey(title) }
}

@Immutable
data class ArtRef(val albumId: Long, val songId: Long, val docUri: String = "")

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val art: ArtRef get() = songs.firstOrNull().let { ArtRef(id, it?.artId ?: -1, it?.docUri.orEmpty()) }
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val dateAdded: Long get() = songs.maxOfOrNull { it.dateAdded } ?: 0
}

@Immutable
data class Artist(
    val name: String,
    val songs: List<Song>,
    val albums: List<Album>,
) {
    val art: ArtRef? get() = albums.firstOrNull()?.art ?: songs.firstOrNull()?.art
}

@Immutable
data class Genre(val name: String, val songs: List<Song>)

@Immutable
data class Folder(val path: String, val songs: List<Song>) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}

@Immutable
data class Library(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val loaded: Boolean = false,
) {
    val songById: Map<Long, Song> by lazy { songs.associateBy { it.id } }
    /** Includes the ids of albums that were folded into another (same title and artist under several ids). */
    val albumById: Map<Long, Album> by lazy {
        HashMap<Long, Album>().apply { albums.forEach { a -> a.songs.forEach { s -> putIfAbsent(s.albumId, a) }; put(a.id, a) } }
    }
    /** By [Names.key]; look artists up with [artist] so any spelling of the name finds them. */
    val artistByKey: Map<String, Artist> by lazy { artists.associateBy { Names.key(it.name) } }
    val genreByKey: Map<String, Genre> by lazy { genres.associateBy { Names.key(it.name) } }
    val songByKey: Map<String, Song> by lazy { songs.associateBy { it.key } }
    val search: LibrarySearch by lazy { LibrarySearch(this) }
    /** Ids this library's files had before 0.7 (MediaStore _ID, or the hash SAF songs used), for one-time conversion. */
    val songByLegacyId: Map<Long, Song> by lazy { songs.associateBy { SongKeys.legacyId(it) } }

    fun artist(name: String): Artist? = artistByKey.forName(name)
    fun genre(name: String): Genre? = genreByKey.forName(name)

    companion object {
        fun build(input: List<Song>): Library {
            val songs = input.map { it.cleaned() }

            val artistSpellings = HashMap<String, MutableList<String>>()
            val songsByArtist = LinkedHashMap<String, MutableList<Song>>()
            songs.forEach { s ->
                s.artists.map { it to Names.key(it) }.distinctBy { it.second }.forEach { (a, k) ->
                    songsByArtist.getOrPut(k) { mutableListOf() }.add(s)
                    artistSpellings.getOrPut(k) { mutableListOf() }.add(a)
                }
            }
            val artistName = artistSpellings.mapValues { mostCommon(it.value) }
            fun artistDisplay(raw: String) = artistName[Names.key(raw)] ?: raw

            val albumGroups = LinkedHashMap<String, MutableList<List<Song>>>()
            songs.groupBy { it.albumId }.forEach { (id, list) ->
                val title = mostCommon(list.map { it.album })
                val artist = mostCommon(list.map { it.albumArtist }.filter { it.isNotBlank() }).ifEmpty { mostCommon(list.map { it.artist }) }
                val key = if (title == UNKNOWN || title.isBlank()) "id:$id" else Names.key(title) + "\u0000" + Names.key(artist)
                albumGroups.getOrPut(key) { mutableListOf() }.add(list)
            }
            val albums = albumGroups.values.map { groups ->
                val main = groups.maxWith(compareBy<List<Song>> { it.size }.thenByDescending { it.first().albumId })
                val list = groups.flatten()
                val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
                val albumArtist = mostCommon(list.map { it.albumArtist }.filter { it.isNotBlank() })
                Album(
                    id = main.first().albumId,
                    title = mostCommon(list.map { it.album }),
                    artist = artistDisplay(albumArtist.ifBlank { mostCommon(list.map { it.artist }) }),
                    year = list.maxOf { it.year },
                    songs = sorted,
                )
            }.sortedByKey { sortKey(it.title) }
            val albumOfSong = HashMap<Long, Album>().apply { albums.forEach { a -> a.songs.forEach { put(it.id, a) } } }
            val albumsByArtist = albums.groupBy { Names.key(it.artist) }
            val artists = songsByArtist.map { (key, list) ->
                val own = albumsByArtist[key].orEmpty()
                val featured = list.mapNotNull { albumOfSong[it.id] }.distinctBy { it.id }
                Artist(
                    name = artistName.getValue(key),
                    songs = list.sortedByKey { it.titleKey },
                    albums = (own + featured).distinctBy { it.id }.sortedByDescending { it.year },
                )
            }.sortedByKey { nameSortKey(it.name) }
            val genres = songs.filter { it.genre.isNotBlank() }.groupBy { Names.key(it.genre) }
                .map { (_, l) -> Genre(mostCommon(l.map { it.genre }), l) }.sortedByDescending { it.songs.size }
            val folders = songs.groupBy { it.folder }.map { (p, l) -> Folder(p, l.sortedByKey { it.titleKey }) }
                .sortedByKey { sortKey(it.name) }
            return Library(songs.sortedByKey { it.titleKey }, albums, artists, genres, folders, loaded = true)
        }
    }
}

const val UNKNOWN = "<unknown>"

/**
 * Android compiles patterns with ICU, which rejects Java-only inline flags such as U (PatternSyntaxException at class
 * init, so every caller of this file fails). Spaces are spelled out (\s plus Unicode spaces) so the JVM and ICU agree.
 */
private const val SP = "[\\s\\p{Z}]"
private val ARTIST_SEPARATORS = Regex("$SP*(?:/|／|、|;|；|&|＆|$SP(?:feat|ft)\\.$SP|$SP[xX×]$SP)$SP*", RegexOption.IGNORE_CASE)

fun splitArtists(raw: String): List<String> {
    val parts = raw.split(ARTIST_SEPARATORS)
        .map { it.trim() }.filter { it.isNotEmpty() }
    return parts.ifEmpty { listOf(raw) }.distinct()
}

/** The most frequent value; ties go to the one that sorts first, so the choice does not depend on scan order. */
fun mostCommon(values: List<String>): String =
    values.groupingBy { it }.eachCount().entries.minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key ?: ""

inline fun <T> List<T>.sortedByKey(key: (T) -> String): List<T> =
    map { it to key(it) }.sortedBy { it.second }.map { it.first }

fun sortKey(s: String): String = Pinyin.sortKey(s, name = false)
fun nameSortKey(s: String): String = Pinyin.sortKey(s, name = true)

/** Placeholder names in the current UI language; [com.novcloud.echo.EchoApp] loads them from resources. */
object UnknownNames {
    @Volatile var artist = "未知歌手"
    @Volatile var album = "未知专辑"
}

fun displayArtist(s: String) = if (s == UNKNOWN || s.isBlank()) UnknownNames.artist else s
fun displayAlbum(s: String) = if (s == UNKNOWN || s.isBlank()) UnknownNames.album else s
