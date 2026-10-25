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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
                    colorFilter = ColorFilter.tint(if (logo.dark) Color.White else Ink, BlendMode.SrcIn),
                )
            }
            AsyncImage(logo.file, displayArtist(name), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
            colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
        )
    } else {
        androidx.compose.material3.Text(
            displayArtist(name), modifier.fillMaxWidth(),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
