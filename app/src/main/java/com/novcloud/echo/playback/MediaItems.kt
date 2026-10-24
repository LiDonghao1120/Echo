package com.novcloud.echo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.albumArtUri
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist

const val EXTRA_ALBUM_ID = "album_id"

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
            .setArtworkUri(albumArtUri(albumId))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
            .build()
    )
    .build()

fun MediaItem.songId(): Long? = mediaId.toLongOrNull()
