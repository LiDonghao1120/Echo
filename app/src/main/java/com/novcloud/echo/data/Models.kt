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
    val albumById: Map<Long, Album> by lazy { albums.associateBy { it.id } }
    val artistByName: Map<String, Artist> by lazy { artists.associateBy { it.name } }
    val songByKey: Map<String, Song> by lazy { songs.associateBy { it.key } }
    val search: LibrarySearch by lazy { LibrarySearch(this) }
    /** Ids this library's files had before 0.7 (MediaStore _ID, or the hash SAF songs used), for one-time conversion. */
    val songByLegacyId: Map<Long, Song> by lazy { songs.associateBy { SongKeys.legacyId(it) } }

    companion object {
        fun build(songs: List<Song>): Library {
            val albums = songs.groupBy { it.albumId }.map { (id, list) ->
                val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
                val first = sorted.first()
                Album(
                    id = id,
                    title = first.album,
                    artist = first.albumArtist.ifBlank { mostCommon(list.map { it.artist }) },
                    year = list.maxOf { it.year },
                    songs = sorted,
                )
            }.sortedByKey { sortKey(it.title) }
            val albumMap = albums.associateBy { it.id }
            val albumsByArtist = albums.groupBy { it.artist }
            val songsByArtist = HashMap<String, MutableList<Song>>()
            songs.forEach { s -> s.artists.forEach { a -> songsByArtist.getOrPut(a) { mutableListOf() }.add(s) } }
            val artists = songsByArtist.map { (name, list) ->
                val own = albumsByArtist[name].orEmpty()
                val featured = list.map { it.albumId }.distinct().mapNotNull { albumMap[it] }
                Artist(
                    name = name,
                    songs = list.sortedByKey { it.titleKey },
                    albums = (own + featured).distinctBy { it.id }.sortedByDescending { it.year },
                )
            }.sortedByKey { nameSortKey(it.name) }
            val genres = songs.filter { it.genre.isNotBlank() }.groupBy { it.genre }
                .map { (g, l) -> Genre(g, l) }.sortedByDescending { it.songs.size }
            val folders = songs.groupBy { it.folder }.map { (p, l) -> Folder(p, l.sortedByKey { it.titleKey }) }
                .sortedByKey { sortKey(it.name) }
            return Library(songs.sortedByKey { it.titleKey }, albums, artists, genres, folders, loaded = true)
        }
    }
}

const val UNKNOWN = "<unknown>"

private val ARTIST_SEPARATORS = Regex("\\s*(?:/|、|;|；|&|＆| feat\\. | ft\\. | x )\\s*", RegexOption.IGNORE_CASE)

fun splitArtists(raw: String): List<String> {
    val parts = raw.split(ARTIST_SEPARATORS)
        .map { it.trim() }.filter { it.isNotEmpty() }
    return parts.ifEmpty { listOf(raw) }.distinct()
}

fun mostCommon(values: List<String>): String =
    values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""

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
