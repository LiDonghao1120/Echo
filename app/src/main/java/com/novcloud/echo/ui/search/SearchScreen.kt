package com.novcloud.echo.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.Album
import com.novcloud.echo.data.Artist
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.ArtistAvatar
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.artistModel
import com.novcloud.echo.ui.components.bouncyClick
import com.novcloud.echo.ui.home.AlbumTile
import com.novcloud.echo.ui.library.GenreTile
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.novcloud.echo.ui.components.reveal
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Results(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

private fun search(lib: Library, q: String): Results {
    val t = q.trim().lowercase()
    fun m(s: String) = s.lowercase().contains(t)
    fun rank(s: String) = if (s.lowercase().startsWith(t)) 0 else 1
    return Results(
        lib.songs.filter { m(it.title) || m(it.artist) || m(it.album) }.sortedBy { rank(it.title) }.take(60),
        lib.albums.filter { m(it.title) || m(it.artist) }.sortedBy { rank(it.title) }.take(20),
        lib.artists.filter { m(it.name) }.sortedBy { rank(it.name) }.take(20),
    )
}

@Composable
fun SearchScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val home by container.home.collectAsState()
    val state by actions.player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val results by produceState<Results?>(null, query, lib) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(120)
        value = withContext(Dispatchers.Default) { search(lib, query) }
    }

    Column(Modifier.fillMaxSize()) {
        val p = palette
        Text(
            "搜索", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp),
            modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 16.dp, bottom = 12.dp).reveal(0),
        )
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().reveal(1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(26.dp), tint = if (query.isEmpty()) p.faint else p.signal)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (query.isEmpty()) Text("歌曲、专辑、歌手", color = p.faint, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(p.signal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Icon(
                Icons.Rounded.Close, null, Modifier.size(22.dp).bouncyClick { query = "" },
                tint = p.muted,
            )
        }
        val line by androidx.compose.animation.animateColorAsState(if (query.isEmpty()) p.rule else MaterialTheme.colorScheme.onSurface, label = "line")
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(2.dp).background(line))
        Spacer(Modifier.height(8.dp))
        val r = results
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 16.dp)) {
            if (r == null) {
                if (lib.genres.isNotEmpty()) {
                    item { SectionHeader("流派") }
                    val rows = lib.genres.take(12).chunked(2)
                    items(rows) { row ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { g -> Box(Modifier.weight(1f)) { GenreTile(g.name, g.songs.firstOrNull()?.art) { actions.openGenre(g.name) } } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                return@LazyColumn
            }
            if (r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty()) {
                item { EmptyState(Icons.Rounded.SearchOff, "没有找到「${query.trim()}」") }
                return@LazyColumn
            }
            if (r.artists.isNotEmpty()) {
                item { SectionHeader("歌手") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(r.artists, key = { it.name }) { a ->
                            com.novcloud.echo.ui.components.ArtistCard(a.name, 112.dp, 148.dp) { actions.openArtist(a.name) }
                        }
                    }
                }
            }
            if (r.albums.isNotEmpty()) {
                item { SectionHeader("专辑") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(r.albums, key = { it.id }) { AlbumTile(it, 130.dp) }
                    }
                }
            }
            if (r.songs.isNotEmpty()) {
                item { SectionHeader("歌曲") }
                items(r.songs, key = { it.id }) { s ->
                    SongRow(s, { actions.player.playSong(s, r.songs) }, current = state.song?.id == s.id, playing = state.isPlaying)
                }
            }
        }
    }
}
