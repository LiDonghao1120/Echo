package com.novcloud.echo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novcloud.echo.R
import com.novcloud.echo.data.ArtKind
import com.novcloud.echo.data.ArtLibraryMatch
import com.novcloud.echo.data.Pinyin
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.ArtAssetTile
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.SearchLine
import com.novcloud.echo.ui.components.SolidButton
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.kindLabel
import com.novcloud.echo.ui.components.EchoMark
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.palette

/**
 * Picks an artwork library image for [artist]'s [kind]: images with the artist's name first, then images of that
 * kind, searchable by name (pinyin too).
 */
@Composable
fun ArtLibraryPickerContent(
    artist: String,
    kind: ArtKind,
    assets: List<ArtAsset>,
    linked: Set<String>,
    onPick: (ArtAsset) -> Unit,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val p = palette
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(assets, artist, kind, query) {
        ArtLibraryMatch.forPicker(assets, artist, kind, query) { Pinyin.sortKey(it, name = true) }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Eyebrow("${displayArtist(artist)} · ${kindLabel(kind)}", color = p.muted)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.art_choose_from_library),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (assets.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EchoMark(Modifier.size(56.dp), color = p.faint, core = p.signal)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.art_library_empty), style = MaterialTheme.typography.titleMedium, color = p.muted)
                Spacer(Modifier.height(16.dp))
                SolidButton(stringResource(R.string.art_manage_library), icon = Icons.Rounded.Collections, onClick = onManage)
            }
            return@Column
        }
        SearchLine(query, { query = it }, stringResource(R.string.art_library_search), Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))
        if (shown.isEmpty()) {
            Text(stringResource(R.string.art_no_results), Modifier.padding(horizontal = 20.dp, vertical = 24.dp), style = MaterialTheme.typography.titleMedium, color = p.muted)
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.fillMaxWidth().heightIn(max = 470.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown, key = { it.first.id }) { (a, same) ->
                    ArtAssetTile(a, ArtLibraryMatch.key(a.name) in linked, sameName = same) { onPick(a) }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(p.rule))
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(EchoShape.card).echoClick(onClick = onManage).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Collections, null, tint = p.signal)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.art_manage_library), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(end = 4.dp), tint = p.faint)
        }
    }
}
