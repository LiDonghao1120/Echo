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
import kotlinx.coroutines.launch

private val tabs = listOf("歌曲", "专辑", "歌手", "流派", "文件夹", "歌单")

@Composable
fun LibraryScreen() {
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text(
            "曲库", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                val selected = pager.currentPage == i
                Box(
                    Modifier.clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        t, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
                PillButton(Icons.Rounded.PlayArrow, "播放", Modifier.weight(1f)) { actions.player.play(songs) }
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
fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bouncyClick(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
            Column(Modifier.bouncyClick { actions.openAlbum(a.id) }) {
                Artwork(a.art, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp), a.title)
                Spacer(Modifier.height(8.dp))
                Text(displayAlbum(a.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(a.artist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val home by container.home.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp)) {
        items(lib.artists, key = { it.name }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openArtist(a.name) }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistAvatar(artistModel(a.name, lib, custom, home.playCounts), a.name, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayArtist(a.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.albums.size} 张专辑 · ${a.songs.size} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
    val c = remember(name) { seededGradient(name) }
    Box(
        Modifier.fillMaxWidth().height(100.dp).bouncyClick(onClick = onClick).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(c)),
    ) {
        Artwork(
            art,
            Modifier.align(Alignment.BottomEnd).offset(x = 18.dp, y = 10.dp).size(78.dp)
                .then(Modifier.clip(RoundedCornerShape(8.dp))),
            RoundedCornerShape(8.dp), name,
        )
        Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(14.dp), maxLines = 2)
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
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${f.songs.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF4F6D), Color(0xFF8E2DE2)))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
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
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("新建歌单", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        items(playlists, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.openPlaylist(p.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val first = p.firstSongId?.let { lib.songById[it] }
                Collage(listOfNotNull(first?.art), Modifier.size(64.dp), RoundedCornerShape(12.dp), p.name)
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

