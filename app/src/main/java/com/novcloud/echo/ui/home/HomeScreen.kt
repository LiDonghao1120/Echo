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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.lerp
import com.novcloud.echo.ui.components.ArtistCard
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.SolidButton
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.reveal
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededColor
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val home by container.home.collectAsState()
    val lib by container.library.library.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    val p = palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
        item(key = "mast") {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp).reveal(0),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(dateLine(home.day))
                    Spacer(Modifier.height(4.dp))
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp))
                }
                IconButton(onClick = { actions.openSettings() }) {
                    Icon(Icons.Rounded.Tune, null, tint = p.muted)
                }
            }
        }
        if (!lib.loaded) {
            item(key = "loading") {
                androidx.compose.material3.LinearProgressIndicator(
                    Modifier.padding(horizontal = 20.dp, vertical = 24.dp).fillMaxWidth().height(2.dp),
                    color = p.signal, trackColor = p.rule,
                )
            }
            return@LazyColumn
        }
        if (lib.songs.isEmpty()) {
            item(key = "empty") {
                EmptyState(null, "未找到本地音乐", Modifier.padding(top = 60.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SolidButton("扫描文件夹") { actions.openScanFolders() }
                        SolidButton("重新扫描", primary = false) { if (!scanning) container.library.refresh() }
                    }
                }
            }
            return@LazyColumn
        }
        var n = 0
        home.daily?.let { daily ->
            item(key = "daily") { Box(Modifier.reveal(1)) { DailyPoster(daily, home.day) } }
        }
        if (home.mixes.isNotEmpty()) {
            val i = ++n
            item(key = "mixes_h") { SectionHeader("为你推荐", Modifier.reveal(2), index = i) }
            item(key = "mixes") {
                LazyRow(Modifier.reveal(3), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(home.mixes, key = { it.id }) { MixCard(it) }
                }
            }
        }
        if (home.recent.isNotEmpty()) {
            val i = ++n
            item(key = "recent_h") { SectionHeader("最近播放", index = i, onMore = { actions.openList("recent") }) }
            item(key = "recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(home.recent, key = { it.id }) { s -> SongTile(s) { actions.player.playSong(s, home.recent) } }
                }
            }
        }
        if (home.artists.isNotEmpty()) {
            val i = ++n
            item(key = "artists_h") { SectionHeader(if (home.hasHistory) "常听歌手" else "歌手", index = i) }
            item(key = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(home.artists, key = { it.name }) { a ->
                        ArtistCard(a.name, 138.dp, 184.dp) { actions.openArtist(a.name) }
                    }
                }
            }
        }
        if (home.mostPlayed.isNotEmpty()) {
            val i = ++n
            item(key = "most_h") { SectionHeader("最常播放", index = i, onMore = { actions.openList("most") }) }
            itemsIndexed(home.mostPlayed.take(5), key = { _, pr -> "most_${pr.first.id}" }) { idx, (s, c) ->
                RankRow(idx + 1, s, c) { actions.player.playSong(s, home.mostPlayed.map { it.first }) }
            }
        }
        if (home.recentlyAdded.isNotEmpty()) {
            val i = ++n
            item(key = "added_h") { SectionHeader("最近添加", index = i, onMore = { actions.openList("added") }) }
            item(key = "added") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(home.recentlyAdded, key = { it.id }) { AlbumTile(it) }
                }
            }
        }
    }
}

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun dateLine(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日 · ${weekdays[d.dayOfWeek.value - 1]}"

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

@Composable
private fun DailyPoster(mix: Mix, day: LocalDate) {
    val actions = LocalActions.current
    val arts = mix.songs.distinctBy { it.albumId }.take(3)
    val colors = rememberArtColors(arts.firstOrNull()?.art)
    val bg = lerp(colors.top, Ink, 0.15f)
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(236.dp)
            .echoClick(corner = 6.dp, color = colors.accent) { actions.openMix(mix.id) }
            .clip(EchoShape.card)
            .background(bg),
    ) {
        BoxWithConstraints(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(168.dp)) {
            val side = maxWidth - 36.dp
            arts.reversed().forEachIndexed { ri, s ->
                val i = arts.size - 1 - ri
                Artwork(
                    s.art,
                    Modifier
                        .offset(x = (36 - i * 18).dp, y = (i * 18).dp)
                        .size(side)
                        .graphicsLayer { alpha = 1f - i * 0.28f }
                        .shadow(if (i == 0) 18.dp else 0.dp, EchoShape.cover),
                    EchoShape.cover, s.album,
                )
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 18.dp)) {
            Text(months[day.monthValue - 1], style = EchoType.labelBold.copy(fontSize = 12.sp, letterSpacing = 3.sp), color = Paper.copy(alpha = 0.7f))
            Text("%02d".format(day.dayOfMonth), style = EchoType.poster(104.sp), color = Paper)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).echoClick(corner = 10.dp, color = Paper) { actions.player.play(mix.songs) }
                    .clip(EchoShape.key).background(Paper),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Ink, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(mix.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Paper)
                Text("${mix.songs.size} 首", style = EchoType.label, color = Paper.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun MixCard(mix: Mix) {
    val actions = LocalActions.current
    val p = palette
    val tone = remember(mix.id) { seededColor(mix.id) }
    Column(
        Modifier.width(150.dp).echoClick(corner = 6.dp, color = tone) { actions.openMix(mix.id) }
            .clip(EchoShape.card).background(p.card),
    ) {
        Box(Modifier.size(150.dp)) {
            Collage(mix.songs.distinctBy { it.albumId }.take(4).map { it.art }, Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, mix.id)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(tone))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Eyebrow(mix.subtitle, color = tone)
            Spacer(Modifier.height(2.dp))
            Text(displayArtist(mix.title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SongTile(song: Song, onClick: () -> Unit) {
    val actions = LocalActions.current
    Column(Modifier.width(128.dp).echoClick(corner = 4.dp, onLongClick = { actions.openSongMenu(song, com.novcloud.echo.ui.components.SongMenuContext()) }, onClick = onClick)) {
        Artwork(song.art, Modifier.size(128.dp), EchoShape.cover, song.album)
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displayArtist(song.artist), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AlbumTile(album: Album, size: androidx.compose.ui.unit.Dp = 148.dp) {
    val actions = LocalActions.current
    Column(Modifier.width(size).echoClick(corner = 4.dp) { actions.openAlbum(album.id) }) {
        Artwork(album.art, Modifier.size(size), EchoShape.cover, album.title)
        Spacer(Modifier.height(8.dp))
        Text(displayAlbum(album.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(displayArtist(album.artist), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankRow(rank: Int, song: Song, plays: Int, onClick: () -> Unit) {
    val actions = LocalActions.current
    val p = palette
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onLongClick = { actions.openSongMenu(song, com.novcloud.echo.ui.components.SongMenuContext()) }, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank", modifier = Modifier.width(38.dp),
            style = EchoType.poster(34.sp),
            color = if (rank <= 3) p.signal else p.faint,
        )
        Artwork(song.art, Modifier.size(50.dp), EchoShape.cover, song.album)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(displayArtist(song.artist), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
        }
        Text("$plays", style = EchoType.numeral(16.sp))
        Text(" 次", style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}
