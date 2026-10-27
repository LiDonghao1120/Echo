package com.novcloud.echo.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.nameSortKey
import com.novcloud.echo.data.sortKey
import com.novcloud.echo.data.sortedByKey
import com.novcloud.echo.ui.components.AlphabetRail
import com.novcloud.echo.ui.components.sectionStarts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.ArtistAvatar
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.Collage
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.NameDialog
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.artistModel
import com.novcloud.echo.ui.components.bouncyClick
import com.novcloud.echo.ui.theme.seededGradient
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import com.novcloud.echo.ui.components.ArtistCard
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.reveal
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededColor
import kotlinx.coroutines.launch

internal val tabs = listOf("歌曲", "专辑", "歌手", "流派", "文件夹", "歌单")

@Composable
fun LibraryScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val lib by container.library.library.collectAsState()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp, bottom = 10.dp).reveal(0)) {
            Eyebrow("${lib.songs.size} 首 · ${lib.albums.size} 张专辑 · ${lib.artists.size} 位歌手")
            Spacer(Modifier.height(4.dp))
            Text("曲库", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp))
        }
        LibraryTabs(tabs, pager) { i -> scope.launch { pager.animateScrollToPage(i) } }
        Spacer(Modifier.height(6.dp))
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> SongsTab()
                1 -> AlbumsTab()
                2 -> ArtistsTab()
                3 -> GenresTab()
                4 -> FoldersTab()
                else -> PlaylistsTab()
            }
        }
    }
}

private val sorts = listOf("title" to "标题", "artist" to "歌手", "album" to "专辑", "added" to "添加时间", "duration" to "时长")

fun sortSongs(songs: List<Song>, sort: String): List<Song> = when (sort) {
    "artist" -> songs.sortedByKey { nameSortKey(it.artist) + "\u0001" + it.titleKey }
    "album" -> songs.sortedByKey { sortKey(it.album) + "\u0001" + "%04d%05d".format(it.disc, it.track) }
    "added" -> songs.sortedByDescending { it.dateAdded }
    "duration" -> songs.sortedByDescending { it.durationMs }
    else -> songs
}

@Composable
private fun SongsTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val sort by container.prefs.songSort.collectAsState(initial = "title")
    val state by actions.player.state.collectAsState()
    val songs = remember(lib, sort) { sortSongs(lib.songs, sort) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val sections = remember(songs, sort) {
        val keys = when (sort) {
            "title" -> songs.map { it.titleKey }
            "artist" -> songs.map { nameSortKey(it.artist) }
            "album" -> songs.map { sortKey(it.album) }
            else -> null
        }
        if (keys != null && songs.size >= RAIL_MIN) sectionStarts(keys) else emptyMap()
    }
    val current by remember(sections) {
        derivedStateOf { sections.entries.lastOrNull { it.value + 1 <= list.firstVisibleItemIndex }?.key ?: sections.keys.firstOrNull() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp, end = if (sections.isEmpty()) 0.dp else 18.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(Icons.Rounded.PlayArrow, "播放", Modifier.weight(1f), primary = true) { actions.player.play(songs) }
                    Spacer(Modifier.width(10.dp))
                    PillButton(Icons.Rounded.Shuffle, "随机", Modifier.weight(1f)) { actions.player.play(songs, shuffle = true) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, null) }
                        DropdownMenu(menu, { menu = false }) {
                            sorts.forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = if (k == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                    onClick = { menu = false; scope.launch { container.prefs.setSongSort(k) } },
                                )
                            }
                        }
                    }
                }
            }
            items(songs, key = { it.id }) { s ->
                SongRow(s, { actions.player.playSong(s, songs) }, current = state.song?.id == s.id, playing = state.isPlaying)
            }
        }
        AlphabetRail(sections, current, { i -> scope.launch { list.scrollToItem(i + 1) } })
    }
}

private const val RAIL_MIN = 24

@Composable
fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val p = palette
    val bg = if (primary) p.signal else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary) p.onSignal else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.echoClick(corner = 6.dp, color = if (primary) p.signal else p.muted, onClick = onClick).clip(EchoShape.card).background(bg).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = fg)
    }
}

@Composable
private fun AlbumsTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = LocalBottomInset.current + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(lib.albums, key = { it.id }) { a ->
            Column(Modifier.echoClick(corner = 4.dp) { actions.openAlbum(a.id) }) {
                Artwork(a.art, Modifier.fillMaxWidth().aspectRatio(1f), EchoShape.cover, a.title)
                Spacer(Modifier.height(8.dp))
                Text(displayAlbum(a.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(a.artist), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sections = remember(lib) { if (lib.artists.size >= RAIL_MIN) sectionStarts(lib.artists.map { nameSortKey(it.name) }) else emptyMap() }
    val current by remember(sections) {
        derivedStateOf { sections.entries.lastOrNull { it.value <= grid.firstVisibleItemIndex }?.key ?: sections.keys.firstOrNull() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = if (sections.isEmpty()) 16.dp else 30.dp, top = 6.dp, bottom = LocalBottomInset.current + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lib.artists, key = { it.name }) { a ->
                ArtistCard(a.name, Modifier.fillMaxWidth().aspectRatio(0.78f)) { actions.openArtist(a.name) }
            }
        }
        AlphabetRail(sections, current, { i -> scope.launch { grid.scrollToItem(i) } })
    }
}

@Composable
private fun GenresTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val probing by container.library.genreProbing.collectAsState()
    if (lib.genres.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = LocalBottomInset.current), contentAlignment = Alignment.Center) {
            com.novcloud.echo.ui.components.EmptyState(null, if (probing || !lib.loaded) "正在读取流派…" else "歌曲里没有流派标签")
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = LocalBottomInset.current + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(lib.genres, key = { it.name }) { g -> GenreTile(g.name, g.songs.firstOrNull()?.art) { actions.openGenre(g.name) } }
    }
}

@Composable
fun GenreTile(name: String, art: Any?, onClick: () -> Unit) {
    val c = remember(name) { seededColor(name) }
    Box(
        Modifier.fillMaxWidth().height(96.dp).echoClick(corner = 6.dp, color = c, onClick = onClick).clip(EchoShape.card)
            .background(lerp(c, Ink, 0.35f)),
    ) {
        Artwork(art, Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 14.dp).size(74.dp), EchoShape.cover, name)
        Box(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp).size(14.dp, 3.dp).background(c))
        Text(
            name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Paper,
            modifier = Modifier.padding(start = 14.dp, top = 24.dp, end = 70.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FoldersTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp)) {
        item(key = "manage") {
            Row(
                Modifier.fillMaxWidth().clickable { actions.openScanFolders() }.heightIn(min = 52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("扫描文件夹", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = palette.signal)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = palette.faint)
            }
        }
        items(lib.folders, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openFolder(f.path) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(EchoShape.card).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = palette.signal)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.path.removePrefix("/storage/emulated/0"), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                Text("${f.songs.size}", style = EchoType.numeral(14.sp), color = palette.muted)
            }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val playlists by container.library.playlists.collectAsState()
    val favorites by container.library.favoriteOrder.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { actions.openList("favorites") }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(64.dp).clip(EchoShape.cover).background(palette.signal),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Favorite, null, tint = Ink, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("我喜欢的音乐", style = MaterialTheme.typography.titleMedium)
                    Text("${favorites.size} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp).clip(EchoShape.cover).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = palette.signal, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("新建歌单", style = MaterialTheme.typography.titleMedium, color = palette.signal)
            }
        }
        items(playlists, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openPlaylist(p.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val first = p.firstSongKey?.let { container.library.resolve(it, lib) }
                Collage(listOfNotNull(first?.art), Modifier.size(64.dp), EchoShape.cover, p.name)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.count} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (creating) NameDialog("新建歌单", "") { name ->
        creating = false
        if (name != null) scope.launch { val id = container.library.createPlaylist(name); actions.openPlaylist(id) }
    }
}


/**
 * Every tab has a fixed width measured at its boldest weight, so the selected label's weight change can never
 * move its neighbours. One indicator follows the pager's fractional position (taps and swipes alike), stretching
 * between tabs mid-way; it and the label colours are read at draw time, so the row never re-lays out while moving.
 */
@Composable
internal fun LibraryTabs(labels: List<String>, pager: androidx.compose.foundation.pager.PagerState, onSelect: (Int) -> Unit) {
    val p = palette
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val base = MaterialTheme.typography.titleSmall
    val widths = remember(labels, base, density) {
        labels.map { with(density) { measurer.measure(it, base.copy(fontWeight = FontWeight.Black)).size.width.toDp() } + 16.dp }
    }
    val on = MaterialTheme.colorScheme.onSurface
    val off = p.faint
    val signal = p.signal
    fun position() = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (labels.size - 1).toFloat())
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(p.rule))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                .drawBehind {
                    val pos = position()
                    val i = kotlin.math.floor(pos).toInt().coerceAtMost(labels.size - 2).coerceAtLeast(0)
                    val f = if (labels.size < 2) 0f else pos - i
                    val px = widths.map { it.toPx() }
                    val left = px.take(i).sum()
                    val c0 = left + px[i] / 2
                    val c1 = if (labels.size < 2) c0 else left + px[i] + px[i + 1] / 2
                    val center = c0 + (c1 - c0) * f
                    val w = 28.dp.toPx() + (c1 - c0) * 0.55f * kotlin.math.sin(Math.PI * f).toFloat()
                    val h = 3.dp.toPx()
                    drawRect(signal, androidx.compose.ui.geometry.Offset(center - w / 2, size.height - h), androidx.compose.ui.geometry.Size(w, h))
                },
        ) {
            labels.forEachIndexed { i, t ->
                val selected = pager.currentPage == i
                Box(
                    Modifier.width(widths[i])
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                        .testTag("library_tab_$i")
                        .padding(top = 13.dp, bottom = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.text.BasicText(
                        t,
                        style = base.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium),
                        maxLines = 1,
                        softWrap = false,
                        color = { lerp(off, on, (1f - kotlin.math.abs(position() - i)).coerceIn(0f, 1f)) },
                    )
                }
            }
        }
    }
}
