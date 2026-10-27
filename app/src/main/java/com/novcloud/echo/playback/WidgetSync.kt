package com.novcloud.echo.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.novcloud.echo.data.Song
import com.novcloud.echo.widget.WidgetState
import com.novcloud.echo.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Mirrors the current item and play state onto the home-screen widget; the cover is loaded once per track. */
class WidgetSync(private val context: Context, private val scope: CoroutineScope) {
    private var shown: WidgetState? = null
    private var coverFor: String? = null
    private var coverJob: Job? = null

    fun update(item: MediaItem?, song: Song?, playing: Boolean, trackChanged: Boolean) {
        if (item == null) return
        val title = song?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist = song?.artist ?: item.trackInfo()?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
        val base = shown ?: Widgets.load(context)
        val state = base.copy(title = title, artist = artist, playing = playing)
        val newCover = item.mediaId != coverFor
        if (state == shown && !newCover) return
        shown = state
        if (!newCover && !trackChanged) {
            Widgets.publish(context, state)
            return
        }
        coverFor = item.mediaId
        coverJob?.cancel()
        val data: Any? = song?.art ?: item.mediaMetadata.artworkUri
        if (data == null) {
            Widgets.publish(context, state, clearCover = true)
            shown = Widgets.load(context)
            return
        }
        Widgets.publish(context, state)
        coverJob = scope.launch {
            val bitmap = loadCover(data)
            val current = shown ?: return@launch
            Widgets.publish(context, current, cover = bitmap, clearCover = bitmap == null)
            shown = Widgets.load(context)
        }
    }

    fun stopped() {
        coverJob?.cancel()
        val s = shown ?: Widgets.load(context)
        if (s.playing) Widgets.publish(context, s.copy(playing = false))
    }

    private suspend fun loadCover(data: Any): Bitmap? = runCatching {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(data).size(256).allowHardware(false).build(),
        )
        (result as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()
}
