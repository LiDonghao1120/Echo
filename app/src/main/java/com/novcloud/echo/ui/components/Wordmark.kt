package com.novcloud.echo.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novcloud.echo.data.ArtistLogoInfo
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.displayStyle
import com.novcloud.echo.ui.theme.isCjk
import com.novcloud.echo.ui.theme.seededColor

fun logoSize(logo: ArtistLogoInfo, maxWidth: Dp, maxHeight: Dp): Pair<Dp, Dp> {
    val a = logo.aspect.coerceIn(0.2f, 12f)
    val w = minOf(maxWidth.value, maxHeight.value * a)
    return w.dp to (w / a).dp
}

/**
 * Colour filters every stored logo is drawn through. They work on the logo's alpha, so an image that still carries a
 * faint matte (a few percent of alpha across its whole canvas, see [com.novcloud.echo.data.LogoCleanup]) can never
 * show as a box: [image] drops alpha up to [CLEAR] and stretches the rest back to full; [halo] and [tint] build their
 * flat colour from a thresholded mask, never from "every pixel with any alpha" the way a SrcIn tint does.
 */
object LogoAlpha {
    /** Alpha (0..255) at or below which a pixel of a logo is never drawn. */
    const val CLEAR = 16f

    /** The halo is built only from pixels above this alpha, reaching full strength at [HALO_FULL]. */
    const val HALO_FROM = 76f
    const val HALO_FULL = 153f

    /** Alpha row (scale, offset in 0..255) mapping [from]..[full] onto 0..255. */
    private fun alphaRow(from: Float, full: Float): Pair<Float, Float> {
        val s = 255f / (full - from)
        return s to -from * s
    }

    fun imageMatrix(): ColorMatrix {
        val (s, o) = alphaRow(CLEAR, 255f)
        return ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, s, o,
            ),
        )
    }

    private fun flat(color: Color, from: Float, full: Float): ColorMatrix {
        val (s, o) = alphaRow(from, full)
        return ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, color.red * 255f,
                0f, 0f, 0f, 0f, color.green * 255f,
                0f, 0f, 0f, 0f, color.blue * 255f,
                0f, 0f, 0f, s * color.alpha, o * color.alpha,
            ),
        )
    }

    fun haloMatrix(color: Color): ColorMatrix = flat(color, HALO_FROM, HALO_FULL)

    /** The logo in one flat [color] with its anti-aliased edges, minus anything at or below [CLEAR]. */
    fun tintMatrix(color: Color): ColorMatrix = flat(color, CLEAR, 255f)

    val image: ColorFilter = ColorFilter.colorMatrix(imageMatrix())
    fun halo(color: Color): ColorFilter = ColorFilter.colorMatrix(haloMatrix(color))
    fun tint(color: Color): ColorFilter = ColorFilter.colorMatrix(tintMatrix(color))
}

fun markText(name: String): String = displayArtist(name).let { if (isCjk(it)) it else it.uppercase() }

@Composable
fun ArtistNameMark(
    name: String,
    logo: ArtistLogoInfo?,
    maxWidth: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 56.sp,
    minTextSize: TextUnit = 18.sp,
    color: Color = Color.White,
    halo: Boolean = true,
    maxLines: Int = 2,
) {
    if (logo != null) {
        val (w, h) = logoSize(logo, maxWidth, maxHeight)
        Box(modifier.size(w, h)) {
            if (halo && Build.VERSION.SDK_INT >= 31) {
                AsyncImage(
                    logo.file, null,
                    Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }.blur(10.dp, BlurredEdgeTreatment.Unbounded),
                    contentScale = ContentScale.Fit,
                    colorFilter = LogoAlpha.halo(if (logo.dark) Color.White else Ink),
                )
            }
            AsyncImage(logo.file, displayArtist(name), Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = LogoAlpha.image)
        }
    } else {
        val text = markText(name)
        val base = displayStyle(text, textSize)
        BasicText(
            text,
            modifier.widthIn(max = maxWidth),
            style = base.copy(
                color = color,
                textAlign = TextAlign.Center,
                shadow = if (halo) Shadow(Ink.copy(alpha = 0.45f), blurRadius = 30f) else null,
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = minTextSize, maxFontSize = textSize, stepSize = 2.sp),
        )
    }
}

@Composable
fun BoxScope.LogoScrim(logo: ArtistLogoInfo?, strength: Float = 1f) {
    val c = if (logo?.dark == true) Color.White else Ink
    Box(
        Modifier.matchParentSize().background(
            Brush.radialGradient(
                0f to c.copy(alpha = 0.38f * strength), 0.55f to c.copy(alpha = 0.16f * strength), 1f to Color.Transparent,
            ),
        ),
    )
}

@Composable
fun ArtistCard(
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = ArtistCard(name, modifier.size(width, height), onClick)

@Composable
fun ArtistCard(name: String, modifier: Modifier, onClick: () -> Unit) {
    val poster = rememberArtistPoster(name)
    val logo = rememberArtistLogo(name)
    val base = remember(name) { seededColor(name) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .echoClick(corner = 6.dp, onClick = onClick)
            .clip(EchoShape.card)
            .background(lerp(base, Ink, 0.55f)),
    ) {
        if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Ink.copy(alpha = if (logo?.dark == true) 0.15f else 0.82f)),
            ),
        )
        if (logo?.dark == true) Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.White.copy(alpha = 0.55f))),
        )
        ArtistNameMark(
            name, logo,
            maxWidth = maxWidth - 20.dp, maxHeight = maxHeight * 0.26f,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            textSize = 26.sp, minTextSize = 13.sp,
        )
    }
}

@Composable
fun ArtistRowMark(name: String, logo: ArtistLogoInfo?, modifier: Modifier = Modifier, color: Color) {
    if (logo != null && logo.aspect >= 2f) {
        val (w, h) = logoSize(logo, 180.dp, 26.dp)
        AsyncImage(
            logo.file, displayArtist(name), modifier.size(w, h), contentScale = ContentScale.Fit,
            colorFilter = LogoAlpha.tint(color),
        )
    } else {
        androidx.compose.material3.Text(
            displayArtist(name), modifier.fillMaxWidth(),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
