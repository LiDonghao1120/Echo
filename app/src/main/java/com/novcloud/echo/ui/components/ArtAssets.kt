package com.novcloud.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novcloud.echo.R
import com.novcloud.echo.data.ArtKind
import com.novcloud.echo.data.ArtLibraryMatch
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import java.io.File

/** Dark logos sit on paper, light ones on ink (a raised ink in the dark theme, so the tile keeps its edge); posters fill the frame. */
@Composable
private fun ArtAsset.ground(): Color = when {
    ArtLibraryMatch.kindOf(this) == ArtKind.Logo && luma < 0.38f -> Paper
    com.novcloud.echo.ui.theme.LocalDarkTheme.current -> palette.cardHigh
    else -> Ink
}

@Composable
fun kindLabel(kind: ArtKind): String = stringResource(if (kind == ArtKind.Poster) R.string.art_kind_poster else R.string.art_kind_logo)

/** The image of a library asset in an inset square frame (回). */
@Composable
fun ArtAssetImage(asset: ArtAsset, modifier: Modifier = Modifier, ratio: Float = 4f / 3f, inset: Int = 16) {
    val ground = asset.ground()
    val line = if (ground == Paper) Ink.copy(alpha = 0.10f) else Paper.copy(alpha = 0.08f)
    val poster = ArtLibraryMatch.kindOf(asset) == ArtKind.Poster
    Box(
        modifier.fillMaxWidth().aspectRatio(ratio).clip(EchoShape.card).background(ground)
            .drawBehind {
                val d = 6.dp.toPx()
                drawRect(line, Offset(d, d), Size(size.width - 2 * d, size.height - 2 * d), style = Stroke(1.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (poster) {
            AsyncImage(File(asset.path), asset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            AsyncImage(File(asset.path), asset.name, Modifier.fillMaxSize().padding(inset.dp), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun ArtAssetTile(asset: ArtAsset, linked: Boolean, modifier: Modifier = Modifier, sameName: Boolean = false, onClick: () -> Unit) {
    val p = palette
    Column(modifier.echoClick(corner = 6.dp, pressedScale = 0.97f, onClick = onClick)) {
        Box {
            ArtAssetImage(asset)
            if (sameName) {
                Text(
                    stringResource(R.string.art_name_match).uppercase(),
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(p.signal).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = EchoType.labelBold, color = p.onSignal, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            asset.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(ArtLibraryMatch.kindOf(asset)).uppercase(), style = EchoType.label, color = p.muted, maxLines = 1)
            if (linked) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(5.dp).background(p.signal))
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(R.string.art_in_library).uppercase(), style = EchoType.label, color = p.signal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A search line in the style of the search tab, smaller. */
@Composable
fun SearchLine(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val p = palette
    val focus = LocalFocusManager.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = if (query.isEmpty()) p.faint else p.signal)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                if (query.isEmpty()) Text(hint, maxLines = 1, color = p.faint, style = style.copy(fontWeight = FontWeight.Medium))
                BasicTextField(
                    query, onQuery, singleLine = true,
                    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(p.signal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, null, Modifier.size(20.dp).bouncyClick { onQuery("") }, tint = p.muted)
        }
        val line by androidx.compose.animation.animateColorAsState(if (query.isEmpty()) p.rule else MaterialTheme.colorScheme.onSurface, label = "line")
        Box(Modifier.fillMaxWidth().height(2.dp).background(line))
    }
}
