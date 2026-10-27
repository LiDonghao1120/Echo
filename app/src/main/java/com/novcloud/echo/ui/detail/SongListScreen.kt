package com.novcloud.echo.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicOff
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.AddToPlaylistDialog
import com.novcloud.echo.ui.components.Collage
import com.novcloud.echo.ui.components.ConfirmDialog
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.NameDialog
import com.novcloud.echo.ui.components.SongMenuContext
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.formatLong
import com.novcloud.echo.ui.library.PillButton
import com.novcloud.echo.ui.player.rememberArtColors
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededGradient

sealed interface ListSource {
    data class MixSrc(val id: String) : ListSource
    data class PlaylistSrc(val id: Long) : ListSource
    data class GenreSrc(val name: String) : ListSource
    data class FolderSrc(val path: String) : ListSource
    data class Kind(val kind: String) : ListSource
}

@Composable
fun SongListScreen(source: ListSource) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val home by container.home.collectAsState()
    val favorites by container.library.favoriteOrder.collectAsState()
    val events by container.allEvents.collectAsState()
    val state by actions.player.state.collectAsState()

    val playlistId = (source as? ListSource.PlaylistSrc)?.id
    val playlistFlow = remember(playlistId) { playlistId?.let { container.library.playlist(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
    val idsFlow = remember(playlistId) { playlistId?.let { container.library.playlistSongs(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
    val playlist by playlistFlow.collectAsState(initial = null)
    val playlistIds by idsFlow.collectAsState(initial = emptyList())

    val mixNow = (source as? ListSource.MixSrc)?.let { home.mix(it.id) }
    val mixName = mixNow?.let { com.novcloud.echo.ui.home.mixTitle(it) }.orEmpty()
    val mixEyebrow = mixNow?.let { com.novcloud.echo.ui.home.mixSubtitle(it) }.orEmpty()
    val playlistLabel = stringResource(R.string.playlist)
    val genreLabel = stringResource(R.string.genre)
    val folderLabel = stringResource(R.string.folder)
    val recentLabel = stringResource(R.string.recently_played)
    val mostLabel = stringResource(R.string.most_played)
    val addedLabel = stringResource(R.string.recently_added)
    val likedLabel = stringResource(R.string.liked_songs)
    val allSongsLabel = stringResource(R.string.all_songs)
    val (title, eyebrow, songs) = remember(source, lib, home, favorites, events, playlist, playlistIds, mixName) {
        when (source) {
            is ListSource.MixSrc -> Triple(mixName, mixEyebrow, mixNow?.songs.orEmpty())
            is ListSource.PlaylistSrc -> Triple(playlist?.name ?: "", playlistLabel, playlistIds.mapNotNull { lib.songById[it] })
            is ListSource.GenreSrc -> Triple(source.name, genreLabel, lib.genres.firstOrNull { it.name == source.name }?.songs.orEmpty())
            is ListSource.FolderSrc -> Triple(lib.folders.firstOrNull { it.path == source.path }?.name ?: "", folderLabel, lib.folders.firstOrNull { it.path == source.path }?.songs.orEmpty())
            is ListSource.Kind -> when {
                source.kind == "recent" -> {
                    val ids = LinkedHashSet<Long>()
                    events.asReversed().forEach { if (ids.size < 100) ids += it.songId }
                    Triple(recentLabel, "", ids.mapNotNull { lib.songById[it] })
                }
                source.kind == "most" -> Triple(mostLabel, "", home.playCounts.entries.sortedByDescending { it.value }.take(100).mapNotNull { lib.songById[it.key] })
                source.kind == "added" -> Triple(addedLabel, "", lib.songs.sortedByDescending { it.dateAdded }.take(100))
                source.kind == "favorites" -> Triple(likedLabel, "", favorites.mapNotNull { lib.songById[it] })
                source.kind.startsWith("artist:") -> {
                    val n = source.kind.removePrefix("artist:")
                    Triple(displayArtist(n), allSongsLabel, lib.artistByName[n]?.songs.orEmpty().sortedByDescending { home.playCounts[it.id] ?: 0 })
                }
                else -> Triple("", "", emptyList<Song>())
            }
        }
    }
    val isDaily = source is ListSource.MixSrc && source.id == "daily"
    val isFav = source is ListSource.Kind && source.kind == "favorites"
    val cover = songs.distinctBy { it.albumId }.take(4).map { it.art }
    val colors = rememberArtColors(cover.firstOrNull())
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val progress by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / with(density) { 260.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addAll by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(colors.top.copy(alpha = 0.9f), Color.Transparent)))) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(220.dp).graphicsLayer {
                            val p = (list.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 0.25f)
                            scaleX = 1 - p; scaleY = 1 - p
                        }.shadow(24.dp, EchoShape.cover, spotColor = colors.top)) {
                            when {
                                isFav -> Box(Modifier.fillMaxSize().clip(EchoShape.cover).background(palette.signal), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Favorite, null, tint = Ink, modifier = Modifier.size(84.dp))
                                }
                                else -> Collage(cover, Modifier.fillMaxSize(), EchoShape.cover, title)
                            }
                            if (isDaily) Box(
                                Modifier.fillMaxSize().clip(EchoShape.cover)
                                    .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Ink.copy(alpha = 0.75f))),
                            ) {
                                Text(
                                    "%02d".format(home.day.dayOfMonth), Modifier.align(Alignment.BottomStart).padding(14.dp),
                                    style = EchoType.poster(84.sp), color = Paper,
                                )
                            }
                            if (!isFav && !isDaily && source is ListSource.MixSrc) {
                                val g = remember(title) { seededGradient(title) }
                                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(g[0]))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        if (eyebrow.isNotEmpty()) com.novcloud.echo.ui.components.Eyebrow(eyebrow)
                        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
                        if (songs.isNotEmpty()) Text(
                            "${count(R.plurals.songs_short, songs.size)} · ${formatLong(songs.sumOf { it.durationMs })}",
                            style = EchoType.label, color = palette.muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
                            PillButton(Icons.Rounded.PlayArrow, stringResource(R.string.action_play), Modifier.weight(1f), primary = true) { actions.player.play(songs) }
                            Spacer(Modifier.width(12.dp))
                            PillButton(Icons.Rounded.Shuffle, stringResource(R.string.action_shuffle), Modifier.weight(1f)) { actions.player.play(songs, shuffle = true) }
                        }
                    }
                }
            }
            if (songs.isEmpty()) item { EmptyState(Icons.Rounded.MusicOff, stringResource(R.string.list_empty)) }
            itemsIndexed(songs, key = { i, s -> "${s.id}_$i" }) { i, s ->
                SongRow(
                    s, { actions.player.playSong(s, songs) },
                    index = if (source is ListSource.Kind && source.kind == "most") i + 1 else null,
                    current = state.song?.id == s.id, playing = state.isPlaying,
                    menuContext = SongMenuContext(playlistId),
                )
            }
        }
        CollapsingTopBar(title, progress) {
            Row {
                if (songs.isNotEmpty() && playlistId == null) IconButton(onClick = { addAll = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }
                if (playlistId != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, null) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
    }
    if (renaming && playlistId != null) NameDialog(stringResource(R.string.action_rename), title) { n -> renaming = false; if (n != null) container.library.renamePlaylist(playlistId, n) }
    if (deleting && playlistId != null) ConfirmDialog(stringResource(R.string.playlist_delete_confirm, title), stringResource(R.string.action_delete)) { ok ->
        deleting = false
        if (ok) { container.library.deletePlaylist(playlistId); actions.back() }
    }
    if (addAll) AddToPlaylistDialog(songs) { addAll = false }
}
