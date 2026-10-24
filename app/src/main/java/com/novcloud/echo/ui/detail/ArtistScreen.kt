package com.novcloud.echo.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.ArtistImages
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.AnimatedNumber
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.bouncyClick
import com.novcloud.echo.ui.home.AlbumTile
import com.novcloud.echo.ui.player.rememberArtColors
import com.novcloud.echo.ui.theme.seededGradient

@Composable
fun ArtistScreen(name: String) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val home by container.home.collectAsState()
    val events by container.allEvents.collectAsState()
    val state by actions.player.state.collectAsState()
    val artist = lib.artistByName[name] ?: return
    val model = remember(artist, custom[name], home.playCounts) { ArtistImages.source.image(artist, custom, home.playCounts) }
    val colors = rememberArtColors(model)
    val topSongs = remember(artist, home.playCounts) {
        artist.songs.sortedWith(compareByDescending<com.novcloud.echo.data.Song> { home.playCounts[it.id] ?: 0 }.thenByDescending { it.year }).take(5)
    }
    val mine = remember(events, name) {
        val l = events.filter { name in com.novcloud.echo.data.splitArtists(it.artist) }
        l.size to l.sumOf { it.listenedMs } / 60_000
    }
    var menu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) container.library.setArtistImage(name, uri)
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val posterH = maxWidth * 1.08f
        val density = LocalDensity.current
        val posterPx = with(density) { posterH.toPx() }
        val list = rememberLazyListState()
        val progress by remember {
            derivedStateOf {
                if (list.firstVisibleItemIndex > 0) 1f
                else ((list.firstVisibleItemScrollOffset - posterPx * 0.55f) / (posterPx * 0.3f)).coerceIn(0f, 1f)
            }
        }
        val bg = MaterialTheme.colorScheme.surface
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item(key = "poster") {
                Box(
                    Modifier.fillMaxWidth().height(posterH)
                        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = { pick() }) {},
                ) {
                    val g = remember(name) { seededGradient(name) }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = off * 0.45f
                        }.background(Brush.linearGradient(listOf(g[0], g[1]))),
                    ) {
                        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.25f), 0.25f to Color.Transparent,
                                0.62f to Color.Transparent, 0.86f to bg.copy(alpha = 0.75f), 1f to bg,
                            ),
                        ),
                    )
                    Text(
                        displayArtist(artist.name),
                        Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 62.dp)
                            .graphicsLayer { alpha = 1f - progress },
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = if (artist.name.length > 12) 34.sp else 46.sp,
                            shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 24f),
                        ),
                        fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center, maxLines = 2,
                    )
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircleAction(Icons.Rounded.PlayArrow, colors.accent, Color.White) { actions.player.play(artist.songs.sortedByDescending { home.playCounts[it.id] ?: 0 }) }
                        CircleAction(Icons.Rounded.Shuffle, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) { actions.player.play(artist.songs, shuffle = true) }
                    }
                }
            }
            item(key = "top_h") { SectionHeader("热门歌曲", onMore = { actions.openList("artist:${artist.name}") }) }
            items(topSongs, key = { "top_${it.id}" }) { s ->
                SongRow(s, { actions.player.playSong(s, topSongs) }, current = state.song?.id == s.id, playing = state.isPlaying,
                    subtitle = listOfNotNull(com.novcloud.echo.data.displayAlbum(s.album), home.playCounts[s.id]?.let { "$it 次" }).joinToString(" · "))
            }
            if (artist.albums.isNotEmpty()) {
                item(key = "albums_h") { SectionHeader("专辑") }
                item(key = "albums") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(artist.albums, key = { it.id }) { AlbumTile(it, 150.dp) }
                    }
                }
            }
            if (mine.first > 0) {
                item(key = "mine") {
                    SectionHeader("我的收听")
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatChip("播放", mine.first.toLong(), "次", Modifier.weight(1f))
                        StatChip("时长", mine.second, "分钟", Modifier.weight(1f))
                        StatChip("歌曲", artist.songs.size.toLong(), "首", Modifier.weight(1f))
                    }
                }
            }
        }
        CollapsingTopBar(displayArtist(artist.name), progress, overImage = true) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("更换歌手图片") }, onClick = { menu = false; pick() })
                    if (custom.containsKey(name)) DropdownMenuItem(text = { Text("恢复默认图片") }, onClick = { menu = false; container.library.clearArtistImage(name) })
                }
            }
        }
    }
}

@Composable
private fun CircleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).bouncyClick(onClick = onClick).shadow(10.dp, CircleShape).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun StatChip(label: String, value: Long, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(value, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}
