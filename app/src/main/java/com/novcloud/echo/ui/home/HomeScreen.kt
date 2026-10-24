package com.novcloud.echo.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.Album
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.domain.Mix
import com.novcloud.echo.ui.components.ArtistAvatar
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.Collage
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.artistModel
import com.novcloud.echo.ui.components.bouncyClick
import com.novcloud.echo.ui.player.rememberArtColors
import com.novcloud.echo.ui.theme.seededGradient
import java.time.LocalTime

@Composable
fun HomeScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val home by container.home.collectAsState()
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting(), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.openSettings() }) {
                    Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (lib.loaded && lib.songs.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.LibraryMusic, "未找到本地音乐", Modifier.padding(top = 60.dp)) {
                    Button(onClick = { container.library.refresh() }, enabled = !scanning) { Text("重新扫描") }
                }
            }
            return@LazyColumn
        }
        home.daily?.let { daily ->
            item(key = "daily") {
                Appear(shown, 0) { DailyCard(daily, home.day.dayOfMonth, home.day.monthValue) }
            }
        }
        if (home.mixes.isNotEmpty()) {
            item(key = "mixes_h") { Appear(shown, 1) { SectionHeader("为你推荐") } }
            item(key = "mixes") {
                Appear(shown, 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(home.mixes, key = { it.id }) { MixCard(it) }
                    }
                }
            }
        }
        if (home.recent.isNotEmpty()) {
            item(key = "recent_h") { SectionHeader("最近播放", onMore = { actions.openList("recent") }) }
            item(key = "recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(home.recent, key = { it.id }) { s -> SongTile(s) { actions.player.playSong(s, home.recent) } }
                }
            }
        }
        if (home.mostPlayed.isNotEmpty()) {
            item(key = "most_h") { SectionHeader("最常播放", onMore = { actions.openList("most") }) }
            itemsIndexed(home.mostPlayed.take(5), key = { _, p -> "most_${p.first.id}" }) { i, (s, c) ->
                RankRow(i + 1, s, c) { actions.player.playSong(s, home.mostPlayed.map { it.first }) }
            }
        }
        if (home.artists.isNotEmpty()) {
            item(key = "artists_h") { SectionHeader(if (home.hasHistory) "常听歌手" else "歌手") }
            item(key = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(home.artists, key = { it.name }) { a ->
                        Column(Modifier.width(96.dp).bouncyClick { actions.openArtist(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistAvatar(artistModel(a.name, lib, custom, home.playCounts), a.name, Modifier.size(96.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(displayArtist(a.name), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (home.recentlyAdded.isNotEmpty()) {
            item(key = "added_h") { SectionHeader("最近添加", onMore = { actions.openList("added") }) }
            item(key = "added") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(home.recentlyAdded, key = { it.id }) { AlbumTile(it) }
                }
            }
        }
    }
}

@Composable
private fun Appear(shown: Boolean, order: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(500, order * 90)) + slideInVertically(tween(600, order * 90)) { it / 6 },
    ) { content() }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

@Composable
private fun DailyCard(mix: Mix, day: Int, month: Int) {
    val actions = LocalActions.current
    val cover = mix.songs.firstOrNull()?.art
    val colors = rememberArtColors(cover)
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(214.dp)
            .bouncyClick { actions.openMix(mix.id) }
            .shadow(18.dp, RoundedCornerShape(22.dp), spotColor = colors.top)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
    ) {
        val arts = mix.songs.distinctBy { it.albumId }.take(3)
        arts.reversed().forEachIndexed { ri, s ->
            val i = arts.size - 1 - ri
            Artwork(
                s.art,
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-18 - i * 46).dp)
                    .size((150 - i * 18).dp)
                    .graphicsLayer { rotationZ = (i * -6f) + 4f; alpha = 1f - i * 0.18f }
                    .shadow(14.dp, RoundedCornerShape(12.dp)),
                RoundedCornerShape(12.dp), s.album,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to colors.bottom.copy(alpha = 0.95f), 0.55f to colors.bottom.copy(alpha = 0.5f), 1f to Color.Transparent)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$day", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 64.sp)
                Text("/${month}月", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 10.dp, start = 4.dp))
            }
            Text(mix.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(18.dp).size(46.dp).clip(CircleShape).background(Color.White)
                .clickable { actions.player.play(mix.songs) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun MixCard(mix: Mix) {
    val actions = LocalActions.current
    val g = remember(mix.id) { seededGradient(mix.id) }
    Column(Modifier.width(158.dp).bouncyClick { actions.openMix(mix.id) }) {
        Box(Modifier.size(158.dp).shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
            Collage(mix.songs.distinctBy { it.albumId }.take(4).map { it.art }, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), mix.id)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to g[1].copy(alpha = 0.92f))))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(g[0].copy(alpha = 0.35f), Color.Transparent))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(mix.subtitle, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text(displayArtist(mix.title), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            mix.songs.take(3).joinToString("、") { displayArtist(it.artists.first()) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongTile(song: Song, onClick: () -> Unit) {
    val actions = LocalActions.current
    Column(Modifier.width(132.dp).bouncyClick(onLongClick = { actions.openSongMenu(song, com.novcloud.echo.ui.components.SongMenuContext()) }, onClick = onClick)) {
        Artwork(song.art, Modifier.size(132.dp).shadow(8.dp, RoundedCornerShape(12.dp)), RoundedCornerShape(12.dp), song.album)
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displayArtist(song.artist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AlbumTile(album: Album, size: androidx.compose.ui.unit.Dp = 150.dp) {
    val actions = LocalActions.current
    Column(Modifier.width(size).bouncyClick { actions.openAlbum(album.id) }) {
        Artwork(album.art, Modifier.size(size).shadow(8.dp, RoundedCornerShape(12.dp)), RoundedCornerShape(12.dp), album.title)
        Spacer(Modifier.height(8.dp))
        Text(displayAlbum(album.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displayArtist(album.artist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankRow(rank: Int, song: Song, plays: Int, onClick: () -> Unit) {
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onLongClick = { actions.openSongMenu(song, com.novcloud.echo.ui.components.SongMenuContext()) }, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.art, Modifier.size(52.dp), RoundedCornerShape(8.dp), song.album)
        Text(
            "$rank", modifier = Modifier.width(40.dp).padding(start = 14.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
            color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(displayArtist(song.artist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("$plays 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
