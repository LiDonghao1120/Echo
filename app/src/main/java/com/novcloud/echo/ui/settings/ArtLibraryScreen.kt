package com.novcloud.echo.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.ArtKind
import com.novcloud.echo.data.ArtLibraryMatch
import com.novcloud.echo.data.LibraryAddReport
import com.novcloud.echo.data.Pinyin
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.ui.components.ArtAssetImage
import com.novcloud.echo.ui.components.ArtAssetTile
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SearchLine
import com.novcloud.echo.ui.components.SolidButton
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.kindLabel
import com.novcloud.echo.ui.components.number
import com.novcloud.echo.ui.detail.CollapsingTopBar
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.launch

private enum class KindFilter { All, Logo, Poster }

/** Library artist names by [ArtLibraryMatch.key], for the "in your music" mark and the jump to the artist page. */
@Composable
fun rememberLinkedArtists(): Map<String, String> {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val lib by container.library.library.collectAsState()
    return remember(lib) {
        val out = HashMap<String, String>()
        lib.artists.forEach { a -> ArtLibraryMatch.key(a.name).takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, a.name) } }
        out
    }
}

/** Opens the photo picker or the file picker for several images and adds them to the artwork library. */
class LibraryAdder(val fromPhotos: () -> Unit, val fromFiles: () -> Unit)

@Composable
fun rememberLibraryAdder(): LibraryAdder {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val res = context.resources
    fun report(r: LibraryAddReport) {
        val parts = listOfNotNull(
            r.added.takeIf { it > 0 }?.let { res.count(R.plurals.art_added, it) },
            r.duplicates.takeIf { it > 0 }?.let { res.count(R.plurals.art_duplicates, it) },
            r.failed.takeIf { it > 0 }?.let { res.count(R.plurals.art_unreadable, it) },
        )
        if (parts.isNotEmpty()) Toast.makeText(context, parts.joinToString(" · "), Toast.LENGTH_SHORT).show()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) container.appScope.launch { report(container.artLibrary.add(uris)) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) container.appScope.launch { report(container.artLibrary.add(uris)) }
    }
    return remember(photos, files) {
        LibraryAdder(
            fromPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            fromFiles = { files.launch(arrayOf("image/png", "image/webp", "image/jpeg", "image/*")) },
        )
    }
}

@Composable
fun ArtLibraryScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val art = container.artLibrary
    val assets by art.assets.collectAsState()
    val adding by art.adding.collectAsState()
    val linked = rememberLinkedArtists()
    val adder = rememberLibraryAdder()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(KindFilter.All) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    val shown = remember(assets, query, filter) {
        assets.filter { a ->
            (filter == KindFilter.All || ArtLibraryMatch.kindOf(a).name == filter.name) && ArtLibraryMatch.matches(a.name, query)
        }.sortedWith(compareBy<ArtAsset> { Pinyin.sortKey(it.name, name = true) }.thenByDescending { it.updatedAt })
    }
    val linkedCount = remember(assets, linked) { assets.map { ArtLibraryMatch.key(it.name) }.distinct().count { it in linked } }
    val grid = rememberLazyGridState()
    val density = LocalDensity.current
    val collapse by remember {
        derivedStateOf {
            if (grid.firstVisibleItemIndex > 0) 1f else (grid.firstVisibleItemScrollOffset / with(density) { 56.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp
    val p = palette
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = LocalBottomInset.current + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "title", span = full) {
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        stringResource(R.string.art_library),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(number(assets.size), style = EchoType.numeral, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(5.dp))
                        Text(pluralStringResource(R.plurals.label_images, assets.size), style = MaterialTheme.typography.labelMedium, color = p.muted)
                        if (linkedCount > 0) {
                            Spacer(Modifier.width(14.dp))
                            Box(Modifier.width(6.dp).height(6.dp).background(p.signal))
                            Spacer(Modifier.width(6.dp))
                            Text(count(R.plurals.art_library_linked, linkedCount), style = MaterialTheme.typography.labelMedium, color = p.muted)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SolidButton(stringResource(R.string.art_from_photos), Modifier.weight(1f), icon = Icons.Rounded.Image, onClick = adder.fromPhotos)
                        SolidButton(stringResource(R.string.art_from_files), Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.InsertDriveFile, primary = false, onClick = adder.fromFiles)
                    }
                    adding?.let { a ->
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val f by animateFloatAsState(a.fraction, tween(EchoMotion.Standard), label = "adding")
                            LinearProgressIndicator({ f }, Modifier.weight(1f).height(2.dp), color = p.signal, trackColor = p.rule, gapSize = 0.dp, drawStopIndicator = {})
                            Spacer(Modifier.width(10.dp))
                            Text("${a.done} / ${a.total}", style = EchoType.numeral, color = p.muted)
                        }
                    }
                }
            }
            if (assets.isNotEmpty()) {
                item(key = "search", span = full) {
                    Column {
                        SearchLine(query, { query = it }, stringResource(R.string.art_library_search), Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(KindFilter.All to R.string.art_kind_all, KindFilter.Logo to R.string.art_kind_logo, KindFilter.Poster to R.string.art_kind_poster).forEach { (k, l) ->
                                Chip(stringResource(l), filter == k, Modifier.width(76.dp)) { filter = k }
                            }
                        }
                    }
                }
            }
            if (assets.isEmpty() && adding == null) {
                item(key = "empty", span = full) { EmptyState(null, stringResource(R.string.art_library_empty)) }
            } else if (shown.isEmpty()) {
                item(key = "none", span = full) {
                    Text(stringResource(R.string.art_no_results), Modifier.padding(4.dp, 24.dp), style = MaterialTheme.typography.titleMedium, color = p.muted)
                }
            }
            items(shown, key = { it.id }) { a ->
                ArtAssetTile(a, ArtLibraryMatch.key(a.name) in linked, Modifier.animateItem()) { editing = a.id }
            }
        }
        CollapsingTopBar(stringResource(R.string.art_library), collapse)
    }
    editing?.let { id ->
        val asset = assets.firstOrNull { it.id == id }
        if (asset == null) editing = null else AssetSheet(asset, linked[ArtLibraryMatch.key(asset.name)]) { editing = null }
    }
}

@Composable
private fun AssetSheet(asset: ArtAsset, artist: String?, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
        AssetSheetContent(asset, artist, onDismiss)
    }
}

@Composable
fun AssetSheetContent(asset: ArtAsset, artist: String?, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val p = palette
    var name by rememberSaveable(asset.id) { mutableStateOf(asset.name) }
    var kind by rememberSaveable(asset.id) { mutableStateOf(ArtLibraryMatch.kindOf(asset)) }
    var confirm by remember { mutableStateOf(false) }
    fun save() {
        if (name.isNotBlank() && (name.trim() != asset.name || kind != ArtLibraryMatch.kindOf(asset))) {
            container.appScope.launch { container.artLibrary.edit(asset.id, name.trim(), kind) }
        }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Eyebrow(stringResource(R.string.art_library), color = p.muted)
        Spacer(Modifier.height(12.dp))
        ArtAssetImage(asset.copy(kind = kind.name), ratio = 16f / 9f, inset = 28)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.art_asset_name).uppercase(), style = EchoType.labelBold, color = p.signal)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                name, { name = it }, singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(p.signal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (name.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface))
        if (artist != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(EchoShape.card)
                    .echoClick { save(); onDismiss(); actions.openArtist(artist) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(6.dp).height(6.dp).background(p.signal))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.art_in_library), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = p.signal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.art_open_artist), style = MaterialTheme.typography.labelLarge, color = p.muted)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 4.dp).width(16.dp), tint = p.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.art_asset_kind).uppercase(), style = EchoType.labelBold, color = p.signal)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtKind.entries.forEach { k -> Chip(kindLabel(k), kind == k, Modifier.weight(1f)) { kind = k } }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            SolidButton(stringResource(R.string.action_done)) { save(); onDismiss() }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.art_asset_delete_confirm, asset.name)) },
        text = { Text(stringResource(R.string.art_asset_delete_note), color = p.muted) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                container.appScope.launch { container.artLibrary.delete(asset.id) }
                onDismiss()
            }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
