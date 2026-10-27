package com.novcloud.echo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.AddToPlaylistDialog
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.formatLong
import com.novcloud.echo.ui.home.AlbumTile
import com.novcloud.echo.ui.library.PillButton
import com.novcloud.echo.ui.player.rememberArtColors
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette

@Composable
fun AlbumScreen(id: Long) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val state by actions.player.state.collectAsState()
    val album = lib.albumById[id] ?: return
    val colors = rememberArtColors(album.art)
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val progress by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f
            else (list.firstVisibleItemScrollOffset / with(density) { 300.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    var addAll by remember { mutableStateOf(false) }
    val multiDisc = album.songs.map { it.disc }.distinct().size > 1
    val more = lib.artistByName[album.artist]?.albums?.filter { it.id != album.id }.orEmpty()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(colors.top.copy(alpha = 0.85f), Color.Transparent)))) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(
                            album.art,
                            Modifier.size(250.dp).graphicsLayer {
                                val p = (list.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 0.25f)
                                scaleX = 1 - p; scaleY = 1 - p; alpha = 1 - p * 2
                            }.shadow(24.dp, EchoShape.cover, spotColor = colors.top),
                            EchoShape.cover, album.title, 64.dp,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(displayAlbum(album.title), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
                        Text(
                            displayArtist(album.artist), style = MaterialTheme.typography.titleMedium, color = palette.signal,
                            modifier = Modifier.clickable { actions.openArtist(album.artist) }.padding(4.dp),
                        )
                        val meta = listOfNotNull(album.songs.firstOrNull()?.genre?.ifBlank { null }, album.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta.uppercase(), style = EchoType.label, color = palette.muted)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton(Icons.Rounded.PlayArrow, stringResource(R.string.action_play), Modifier.weight(1f), primary = true) { actions.player.play(album.songs) }
                            Spacer(Modifier.width(12.dp))
                            PillButton(Icons.Rounded.Shuffle, stringResource(R.string.action_shuffle), Modifier.weight(1f)) { actions.player.play(album.songs, shuffle = true) }
                        }
                    }
                }
            }
            album.songs.forEachIndexed { i, s ->
                if (multiDisc && (i == 0 || album.songs[i - 1].disc != s.disc)) item(key = "disc_${s.disc}") {
                    Text(stringResource(R.string.album_disc, s.disc), style = EchoType.labelBold, color = palette.signal, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
                item(key = s.id) {
                    SongRow(
                        s, { actions.player.playSong(s, album.songs) }, index = if (s.track > 0) s.track else i + 1, showArt = false,
                        current = state.song?.id == s.id, playing = state.isPlaying,
                        subtitle = if (s.artist != album.artist) displayArtist(s.artist) else null,
                    )
                }
            }
            item {
                Text(
                    "${count(R.plurals.songs_long, album.songs.size)} · ${formatLong(album.durationMs)}",
                    style = EchoType.label, color = palette.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            if (more.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.album_more_by, displayArtist(album.artist)), onMore = { actions.openArtist(album.artist) }) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(more, key = { it.id }) { AlbumTile(it, 140.dp) }
                    }
                }
            }
        }
        CollapsingTopBar(displayAlbum(album.title), progress) {
            IconButton(onClick = { addAll = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }
        }
    }
    if (addAll) AddToPlaylistDialog(album.songs) { addAll = false }
}
