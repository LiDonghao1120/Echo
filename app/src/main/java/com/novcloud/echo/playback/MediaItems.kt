package com.novcloud.echo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import com.novcloud.echo.data.albumArtUri
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist

const val EXTRA_ALBUM_ID = "album_id"
const val EXTRA_SONG_KEY = "song_key"

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMimeType(mimeType.ifBlank { null })
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(displayArtist(artist))
            .setAlbumTitle(displayAlbum(album))
            .setAlbumArtist(albumArtist.ifBlank { null })
            .setGenre(genre.ifBlank { null })
            .setDurationMs(durationMs)
            .setArtworkUri(if (docUri.isEmpty() && albumId >= 0) albumArtUri(albumId) else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId); putString(EXTRA_SONG_KEY, key) })
            .build()
    )
    .build()

fun MediaItem.songId(): Long? = mediaId.toLongOrNull()

fun MediaItem.songKey(): String? = mediaMetadata.extras?.getString(EXTRA_SONG_KEY)?.takeIf { it.isNotEmpty() }

fun MediaItem.trackInfo(): TrackInfo? {
    val id = songId() ?: return null
    val m = mediaMetadata
    return TrackInfo(
        id = id,
        title = m.title?.toString().orEmpty(),
        artist = m.artist?.toString()?.takeUnless { it == displayArtist(UNKNOWN) } ?: UNKNOWN,
        album = m.albumTitle?.toString()?.takeUnless { it == displayAlbum(UNKNOWN) } ?: UNKNOWN,
        albumId = m.extras?.getLong(EXTRA_ALBUM_ID, -1) ?: -1,
        genre = m.genre?.toString().orEmpty(),
        durationMs = m.durationMs ?: 0,
        key = songKey().orEmpty(),
    )
}
