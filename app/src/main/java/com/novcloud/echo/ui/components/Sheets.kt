package com.novcloud.echo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import kotlinx.coroutines.launch

@Composable
fun SongMenuSheet(song: Song, ctx: SongMenuContext, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val favorites by actions.repo.favorites.collectAsState()
    val liked = song.id in favorites
    var addTo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); then() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.art, Modifier.size(56.dp), RoundedCornerShape(8.dp), song.album)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${displayArtist(song.artist)} · ${displayAlbum(song.album)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "下一首播放") { actions.player.playNext(listOf(song)); close() }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "添加到播放队列") { actions.player.enqueue(listOf(song)); close() }
            MenuItem(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "取消喜欢" else "喜欢") {
                actions.repo.toggleFavorite(song.id); close()
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "添加到歌单") { addTo = true }
            if (ctx.playlistId != null) MenuItem(Icons.Rounded.RemoveCircleOutline, "从歌单移除") {
                actions.repo.removeFromPlaylist(ctx.playlistId, song.id); close()
            }
            MenuItem(Icons.Rounded.Album, "查看专辑") { close { actions.openAlbum(song.albumId) } }
            song.artists.forEach { a ->
                MenuItem(Icons.Rounded.Person, displayArtist(a)) { close { actions.openArtist(a) } }
            }
            MenuItem(Icons.Rounded.Info, "歌曲信息") { info = true }
        }
    }
    if (addTo) AddToPlaylistDialog(listOf(song)) { addTo = false; close() }
    if (info) SongInfoDialog(song) { info = false }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDone: () -> Unit) {
    val actions = LocalActions.current
    val playlists by actions.repo.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (creating) {
        NameDialog("新建歌单", "") { name ->
            if (name != null) scope.launch { actions.repo.createPlaylist(name, songs.map { it.id }); onDone() } else creating = false
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } },
        title = { Text("添加到歌单") },
        text = {
            LazyColumn {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text("新建歌单", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.repo.addToPlaylist(p.id, songs.map { it.id }); onDone() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("${p.count}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onResult(text.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(title: String, confirm: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("取消") } },
    )
}

@Composable
private fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val rows = listOf(
        "标题" to song.title,
        "歌手" to displayArtist(song.artist),
        "专辑" to displayAlbum(song.album),
        "流派" to song.genre.ifBlank { "—" },
        "年份" to if (song.year > 0) song.year.toString() else "—",
        "时长" to formatTime(song.durationMs),
        "格式" to song.mimeType.substringAfter('/').uppercase(),
        "大小" to "%.1f MB".format(song.size / 1048576.0),
        "路径" to song.path,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(k, Modifier.width(52.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

