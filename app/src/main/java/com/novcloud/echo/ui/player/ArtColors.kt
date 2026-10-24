package com.novcloud.echo.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class ArtColors(val top: Color, val bottom: Color, val accent: Color)

val DefaultArtColors = ArtColors(Color(0xFF2A2A33), Color(0xFF0E0E12), Color(0xFFFF4F6D))

private val cache = HashMap<Any, ArtColors>()

@Composable
fun rememberArtColors(model: Any?): ArtColors {
    val context = LocalContext.current
    var colors by remember(model) { mutableStateOf(model?.let { synchronized(cache) { cache[it] } } ?: DefaultArtColors) }
    LaunchedEffect(model) {
        if (model == null) { colors = DefaultArtColors; return@LaunchedEffect }
        synchronized(cache) { cache[model] }?.let { colors = it; return@LaunchedEffect }
        val req = ImageRequest.Builder(context).data(model).size(160).allowHardware(false).build()
        val result = context.imageLoader.execute(req)
        val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val c = withContext(Dispatchers.Default) { extract(bmp) }
        synchronized(cache) { cache[model] = c }
        colors = c
    }
    val top by animateColorAsState(colors.top, tween(700), label = "top")
    val bottom by animateColorAsState(colors.bottom, tween(700), label = "bottom")
    val accent by animateColorAsState(colors.accent, tween(700), label = "accent")
    return ArtColors(top, bottom, accent)
}

private fun extract(bmp: Bitmap): ArtColors {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val dominant = Color(p.getDominantColor(0xFF2A2A33.toInt()))
    val vibrant = p.vibrantSwatch?.rgb?.let { Color(it) } ?: p.lightVibrantSwatch?.rgb?.let { Color(it) } ?: dominant
    val muted = p.darkMutedSwatch?.rgb?.let { Color(it) } ?: p.mutedSwatch?.rgb?.let { Color(it) } ?: dominant
    val top = darkenTo(lerp(dominant, vibrant, 0.35f), 0.22f)
    val bottom = darkenTo(muted, 0.06f)
    val accent = if (vibrant.luminance() < 0.25f) lerp(vibrant, Color.White, 0.45f) else vibrant
    return ArtColors(top, bottom, accent)
}

private fun darkenTo(c: Color, maxLum: Float): Color {
    var x = c
    var i = 0
    while (x.luminance() > maxLum && i++ < 12) x = lerp(x, Color.Black, 0.18f)
    return x
}
