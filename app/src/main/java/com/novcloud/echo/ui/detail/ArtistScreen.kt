package com.novcloud.echo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.novcloud.echo.data.displayArtist
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.ArtistImages
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.splitArtists
import com.novcloud.echo.ui.components.AnimatedNumber
import com.novcloud.echo.ui.components.ArtistNameMark
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.LogoScrim
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.SongRow
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.logoSize
import com.novcloud.echo.ui.home.AlbumTile
import com.novcloud.echo.ui.player.rememberArtColors
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.LightSystemIcons
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededGradient
import kotlin.math.roundToInt

@Composable
fun ArtistScreen(name: String) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val logos by container.library.artistLogos.collectAsState()
    val home by container.home.collectAsState()
    val events by container.allEvents.collectAsState()
    val state by actions.player.state.collectAsState()
    LightSystemIcons()
    var editing by rememberSaveable { mutableStateOf(false) }
    val artActions = rememberArtistArtActions(name)
    val artist = lib.artistByName[name]
    if (artist == null) {
        if (lib.loaded) Box(Modifier.fillMaxSize()) {
            com.novcloud.echo.ui.components.EmptyState(null, displayArtist(name), Modifier.align(Alignment.Center))
            CollapsingTopBar("", 0f)
        }
        return
    }
    val logo = logos[name]
    val model = remember(artist, custom[name], home.playCounts) { ArtistImages.source.image(artist, custom, home.playCounts) }
    val colors = rememberArtColors(model)
    val topSongs = remember(artist, home.playCounts) {
        artist.songs.sortedWith(compareByDescending<Song> { home.playCounts[it.id] ?: 0 }.thenByDescending { it.year }).take(5)
    }
    val mine = remember(events, name) {
        val l = events.filter { name in splitArtists(it.artist) }
        l.size to l.sumOf { it.listenedMs } / 60_000
    }
    val p = palette
    val bg = MaterialTheme.colorScheme.surface
    val barTone = lerp(colors.bottom, Ink, 0.25f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = maxWidth
        val posterH = width * 1.16f
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val markMaxW = width - 56.dp
        val markMaxH = 128.dp
        val markBottom = posterH - 104.dp
        val list = rememberLazyListState()
        var markPx by remember { mutableIntStateOf(0) }
        var markWPx by remember { mutableIntStateOf(0) }
        val px = with(density) {
            object {
                val posterBottomMark = markBottom.toPx()
                val bar = (statusTop + 28.dp).toPx()
                val range = 150.dp.toPx()
                val collapsedH = (if (logo != null) 28.dp else 26.dp).toPx()
                val collapsedW = (width - 128.dp).toPx()
            }
        }
        val scroll by remember {
            derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE / 4 else list.firstVisibleItemScrollOffset.toFloat() }
        }
        val centerExpanded = px.posterBottomMark - markPx / 2f
        val dist = centerExpanded - scroll - px.bar
        val t = (1f - dist / px.range).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t)
        val collapsedScale = if (markPx > 0) minOf(px.collapsedH / markPx, px.collapsedW / markWPx.coerceAtLeast(1)) else 1f
        val markScale = 1f + (collapsedScale - 1f) * eased
        val markCenter = px.bar + maxOf(dist, 0f) * (1f - eased)

        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp)) {
            item(key = "poster") {
                Box(
                    Modifier.fillMaxWidth().height(posterH).clipToBounds()
                        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = { editing = true }) {},
                ) {
                    val g = remember(name) { seededGradient(name) }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
                            translationY = off * 0.5f
                            val s = 1f + (off / size.height) * 0.08f
                            scaleX = s; scaleY = s
                        }.background(Brush.linearGradient(listOf(g[0], g[1]))),
                    ) {
                        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Ink.copy(alpha = 0.45f), 0.22f to Color.Transparent,
                                0.5f to Color.Transparent, 0.84f to bg.copy(alpha = 0.82f), 1f to bg,
                            ),
                        ),
                    )
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = markBottom - markMaxH - 24.dp)
                            .size(width, markMaxH + 48.dp),
                    ) { LogoScrim(logo, 0.9f) }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${artist.songs.size} 首  ·  ${artist.albums.size} 张专辑",
                            style = EchoType.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HeaderKey(Icons.Rounded.PlayArrow, "播放", colors.accent, Ink, Modifier.width(132.dp)) {
                                actions.player.play(artist.songs.sortedByDescending { home.playCounts[it.id] ?: 0 })
                            }
                            HeaderKey(Icons.Rounded.Shuffle, "随机", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), MaterialTheme.colorScheme.onSurface, Modifier.width(132.dp)) {
                                actions.player.play(artist.songs, shuffle = true)
                            }
                        }
                    }
                }
            }
            item(key = "top_h") { SectionHeader("热门歌曲", index = 1, onMore = { actions.openList("artist:${artist.name}") }) }
            items(topSongs, key = { "top_${it.id}" }) { s ->
                SongRow(
                    s, { actions.player.playSong(s, topSongs) }, current = state.song?.id == s.id, playing = state.isPlaying,
                    subtitle = listOfNotNull(displayAlbum(s.album), home.playCounts[s.id]?.let { "$it 次" }).joinToString(" · "),
                )
            }
            if (artist.albums.isNotEmpty()) {
                item(key = "albums_h") { SectionHeader("专辑", index = 2) }
                item(key = "albums") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artist.albums, key = { it.id }) { AlbumTile(it, 148.dp) }
                    }
                }
            }
            if (mine.first > 0) {
                item(key = "mine") {
                    SectionHeader("我的收听", index = 3)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Figure(mine.first.toLong(), "次播放", Modifier.weight(1f))
                        Figure(mine.second, "分钟", Modifier.weight(1f))
                        Figure(artist.songs.size.toLong(), "首歌曲", Modifier.weight(1f))
                    }
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().height(statusTop + 56.dp).background(barTone.copy(alpha = eased))
                .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f * (1 - eased)), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(0, (markCenter - markPx / 2f).roundToInt()) }
                .graphicsLayer { scaleX = markScale; scaleY = markScale },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.onSizeChanged { markPx = it.height; markWPx = it.width }) {
                ArtistNameMark(name, logo, markMaxW, markMaxH, textSize = 64.sp, minTextSize = 30.sp, halo = eased < 0.6f)
                if (logo?.dark == true && eased > 0.01f) {
                    val (w, h) = logoSize(logo, markMaxW, markMaxH)
                    AsyncImage(
                        logo.file, null, Modifier.size(w, h).graphicsLayer { alpha = eased },
                        contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Paper, BlendMode.SrcIn),
                    )
                }
            }
        }
        Row(Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon { IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Paper) } }
            Spacer(Modifier.weight(1f))
            BarIcon { IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.AutoFixHigh, null, tint = Paper) } }
        }
    }
    if (editing) ArtistArtSheet(name, custom[name], logo, artActions) { editing = false }
}

@Composable
private fun BarIcon(content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).clip(EchoShape.card).background(Ink.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HeaderKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.echoClick(corner = 8.dp, color = if (bg.alpha > 0.5f) bg else fg, onClick = onClick).clip(EchoShape.card).background(bg)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = fg)
    }
}

@Composable
fun Figure(value: Long, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val p = palette
    Column(modifier) {
        AnimatedNumber(value, style = EchoType.poster(with(LocalDensity.current) { size.toSp() }), color = MaterialTheme.colorScheme.onSurface)
        Box(Modifier.padding(vertical = 6.dp).size(18.dp, 2.dp).background(p.signal))
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted)
    }
}
