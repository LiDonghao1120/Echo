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
import androidx.compose.ui.text.font.FontWeight
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

private val tabs = listOf("歌曲", "专辑", "歌手", "流派", "文件夹", "歌单")

@Composable
fun LibraryScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val lib by container.library.library.collectAsState()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val p = palette
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp, bottom = 10.dp).reveal(0)) {
            Eyebrow("${lib.songs.size} 首 · ${lib.albums.size} 张专辑 · ${lib.artists.size} 位歌手")
            Spacer(Modifier.height(4.dp))
            Text("曲库", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp))
        }
        val tabScroll = rememberScrollState()
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(p.rule))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(tabScroll).padding(horizontal = 12.dp),
            ) {
                tabs.forEachIndexed { i, t ->
                    val selected = pager.currentPage == i
                    val c by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else p.faint, label = "lt")
                    val bar by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), label = "lb")
                    Column(
                        Modifier.clickable(remember { MutableInteractionSource() }, null) { scope.launch { pager.animateScrollToPage(i) } }
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            t, Modifier.padding(vertical = 10.dp),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium), color = c,
                        )
                        Box(Modifier.width(28.dp * bar).height(3.dp).background(p.signal))
                    }
                }
            }
        }
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
    "artist" -> songs.sortedBy { it.artist.lowercase() }
    "album" -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
}

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
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = LocalBottomInset.current + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lib.artists, key = { it.name }) { a ->
            ArtistCard(a.name, Modifier.fillMaxWidth().aspectRatio(0.78f)) { actions.openArtist(a.name) }
        }
    }
}

@Composable
private fun GenresTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
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
                    Text(f.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                val first = p.firstSongId?.let { lib.songById[it] }
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

