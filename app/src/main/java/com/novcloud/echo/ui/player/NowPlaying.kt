package com.novcloud.echo.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.SongMenuContext
import com.novcloud.echo.ui.components.formatTime
import com.novcloud.echo.ui.components.pressScale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlayerPane { Art, Lyrics, Queue }

@Composable
fun SharedTransitionScope.NowPlayingScreen(avs: AnimatedVisibilityScope, onCollapse: () -> Unit) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val favorites by actions.repo.favorites.collectAsState()
    val song = state.song
    var pane by rememberSaveable { mutableStateOf(PlayerPane.Art) }
    val colors = rememberArtColors(song?.art)
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BackHandler { if (pane != PlayerPane.Art) pane = PlayerPane.Art else onCollapse() }

    val dismissDrag = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = {
                scope.launch {
                    if (dragY.value > with(density) { 140.dp.toPx() }) onCollapse()
                    dragY.animateTo(0f, spring(dampingRatio = 0.8f))
                }
            },
            onVerticalDrag = { _, d -> scope.launch { dragY.snapTo((dragY.value + d).coerceAtLeast(0f)) } },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .sharedBounds(rememberSharedContentState("player_bg"), avs, clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)))
            .clip(RoundedCornerShape(if (dragY.value > 0) 28.dp else 0.dp))
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom)))
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        if (song != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                song.art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.28f; scaleX = 1.4f; scaleY = 1.4f }.blur(90.dp),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.bottom.copy(alpha = 0.85f)))))
        }
        if (song == null) return@Box
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(48.dp).then(dismissDrag), contentAlignment = Alignment.Center) {
                IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Color.White.copy(alpha = 0.85f))
                }
                Box(Modifier.size(38.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                IconButton(onClick = { actions.openSongMenu(song, SongMenuContext()) }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White.copy(alpha = 0.85f))
                }
            }
            AnimatedContent(
                targetState = pane,
                transitionSpec = { fadeIn(tween(260)) + scaleIn(initialScale = 0.97f) togetherWith fadeOut(tween(160)) },
                modifier = Modifier.weight(1f),
                label = "pane",
            ) { p ->
                when (p) {
                    PlayerPane.Art -> ArtPane(song, state.isPlaying, avs, dismissDrag)
                    PlayerPane.Lyrics -> Column {
                        CompactHeader(song, avs) { pane = PlayerPane.Art }
                        LyricsView(song, colors.accent, Modifier.weight(1f))
                    }
                    PlayerPane.Queue -> Column {
                        CompactHeader(song, avs) { pane = PlayerPane.Art }
                        QueueView(Modifier.weight(1f))
                    }
                }
            }
            if (pane == PlayerPane.Art) TitleRow(song, song.id in favorites, avs) { actions.repo.toggleFavorite(song.id) }
            SeekSection(state.durationMs, colors.accent)
            Transport(state.isPlaying, state.shuffle, state.repeat, colors.accent)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaneButton(Icons.Rounded.Lyrics, pane == PlayerPane.Lyrics) {
                    pane = if (pane == PlayerPane.Lyrics) PlayerPane.Art else PlayerPane.Lyrics
                }
                PaneButton(Icons.AutoMirrored.Rounded.QueueMusic, pane == PlayerPane.Queue) {
                    pane = if (pane == PlayerPane.Queue) PlayerPane.Art else PlayerPane.Queue
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SharedTransitionScope.ArtPane(song: Song, playing: Boolean, avs: AnimatedVisibilityScope, dismissDrag: Modifier) {
    val actions = LocalActions.current
    val scale by animateFloatAsState(if (playing) 1f else 0.84f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "art")
    val elevation by animateDpAsState(if (playing) 30.dp else 10.dp, label = "elev")
    var swipe by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().then(dismissDrag), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 56.dp, maxHeight - 24.dp)
        Artwork(
            song.art,
            Modifier
                .size(side)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = swipe * 0.4f }
                .sharedElement(rememberSharedContentState("art"), avs)
                .shadow(elevation, RoundedCornerShape(14.dp), spotColor = Color.Black)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipe < -180) actions.player.next() else if (swipe > 180) actions.player.previous()
                            swipe = 0f
                        },
                        onDragCancel = { swipe = 0f },
                        onHorizontalDrag = { _, d -> swipe += d },
                    )
                },
            RoundedCornerShape(14.dp), song.album, 72.dp,
        )
    }
}

@Composable
private fun SharedTransitionScope.CompactHeader(song: Song, avs: AnimatedVisibilityScope, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.art, Modifier.size(60.dp), RoundedCornerShape(8.dp), song.album, 24.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(displayArtist(song.artist), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
        }
    }
}

@Composable
private fun SharedTransitionScope.TitleRow(song: Song, liked: Boolean, avs: AnimatedVisibilityScope, onLike: () -> Unit) {
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1,
                modifier = Modifier.sharedBounds(rememberSharedContentState("title"), avs).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
            )
            Text(
                "${displayArtist(song.artist)} — ${displayAlbum(song.album)}",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.65f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { actions.openArtist(song.artists.first()) },
            )
        }
        val likeScale = remember { Animatable(1f) }
        val scope = rememberCoroutineScope()
        IconButton(onClick = {
            onLike()
            scope.launch { likeScale.snapTo(0.6f); likeScale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }
        }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                Modifier.size(28.dp).graphicsLayer { scaleX = likeScale.value; scaleY = likeScale.value },
                tint = if (liked) Color(0xFFFF4F6D) else Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun SeekSection(durationMs: Long, accent: Color) {
    val actions = LocalActions.current
    val position by actions.player.position.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thickness by animateDpAsState(if (dragging) 10.dp else 5.dp, label = "thick")
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) actions.player.seekTo((o.x / size.width * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { actions.player.seekTo((dragFraction * durationMs).toLong()); dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { c, _ -> dragFraction = (c.position.x / size.width).coerceIn(0f, 1f) },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(thickness).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).height(thickness).background(if (dragging) Color.White else Color.White.copy(alpha = 0.85f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = if (dragging) (dragFraction * durationMs).toLong() else position
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime((durationMs - shown).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Transport(playing: Boolean, shuffle: Boolean, repeat: Int, accent: Color) {
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleIcon(Icons.Rounded.Shuffle, shuffle, accent) { actions.player.toggleShuffle() }
        BigButton(Icons.Rounded.SkipPrevious, 46.dp) { actions.player.previous() }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(78.dp).pressScale(interaction, 0.88f).clip(CircleShape).background(Color.White)
                .clickable(interaction, null) { actions.player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(playing, transitionSpec = { scaleIn(spring(dampingRatio = 0.5f)) + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "play") { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(44.dp), tint = Color.Black)
            }
        }
        BigButton(Icons.Rounded.SkipNext, 46.dp) { actions.player.next() }
        ToggleIcon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, repeat != Player.REPEAT_MODE_OFF, accent) {
            actions.player.cycleRepeat()
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(64.dp).pressScale(interaction, 0.8f).clip(CircleShape).clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size), tint = Color.White) }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, label = "tog")
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f * bg)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(24.dp), tint = if (on) Color.White else Color.White.copy(alpha = 0.55f)) }
}

@Composable
private fun PaneButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, label = "pane")
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.9f * bg)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (on) Color.Black else Color.White.copy(alpha = 0.75f))
    }
}

