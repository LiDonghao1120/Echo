package com.novcloud.echo.data

import android.content.ContentUris
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import okio.Buffer

class ArtFetcher(private val ref: ArtRef, private val options: Options) : Fetcher {
    private val context: Context get() = options.context

    override suspend fun fetch(): FetchResult {
        val px = (options.size.width as? Dimension.Pixels)?.px ?: 1024
        if (px > 700) embedded()?.let { return it }
        thumbnail(px)?.let { return it }
        legacyAlbumArt()?.let { return it }
        if (px <= 700) embedded()?.let { return it }
        throw IllegalStateException("no art")
    }

    private fun thumbnail(px: Int): FetchResult? {
        if (Build.VERSION.SDK_INT < 29 || ref.albumId < 0) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, ref.albumId)
        val size = px.coerceIn(96, 1024)
        val bmp = runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null) }.getOrNull()
            ?: return null
        return DrawableResult(BitmapDrawable(context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun legacyAlbumArt(): FetchResult? {
        if (ref.albumId < 0) return null
        val uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), ref.albumId)
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        return bytes?.takeIf { it.isNotEmpty() }?.let(::source)
    }

    private fun embedded(): FetchResult? {
        if (ref.songId < 0) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ref.songId)
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture?.let(::source)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun source(bytes: ByteArray) = SourceResult(
        source = ImageSource(Buffer().write(bytes), context),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory : Fetcher.Factory<ArtRef> {
        override fun create(data: ArtRef, options: Options, imageLoader: ImageLoader) = ArtFetcher(data, options)
    }
}

class ArtKeyer : Keyer<ArtRef> {
    override fun key(data: ArtRef, options: Options) = "art:${data.albumId}:${data.songId}"
}

fun albumArtUri(albumId: Long): Uri =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
