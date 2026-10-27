package com.novcloud.echo.ui.player

import com.novcloud.echo.ui.theme.EchoMotion

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.material.icons.rounded.Bedtime
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.novcloud.echo.ui.components.echoAura
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.LightSystemIcons
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.Vermilion
import com.novcloud.echo.ui.theme.displayStyle
import com.novcloud.echo.ui.theme.isCjk
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlayerPane { Art, Lyrics, Queue }

@Composable
fun SharedTransitionScope.NowPlayingScreen(avs: AnimatedVisibilityScope, onCollapse: () -> Unit, startPane: PlayerPane = PlayerPane.Art) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val favorites by actions.repo.favorites.collectAsState()
    val song = state.song
    var pane by rememberSaveable { mutableStateOf(startPane) }
    val colors = rememberArtColors(song?.art)
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var sleepSheet by remember { mutableStateOf(false) }
    LightSystemIcons(navigation = true)

    BackHandler(enabled = avs.transition.targetState == androidx.compose.animation.EnterExitState.Visible) {
        if (pane != PlayerPane.Art) pane = PlayerPane.Art else onCollapse()
    }

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
            .sharedBounds(rememberSharedContentState("player_bg"), avs, clipInOverlayDuringTransition = OverlayClip(EchoShape.card))
            .clip(RoundedCornerShape(if (dragY.value > 0) 18.dp else 0.dp))
            .background(Ink)
            .background(Brush.verticalGradient(0f to colors.top, 0.62f to lerp(colors.bottom, Ink, 0.35f), 1f to Ink))
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        if (song != null && Build.VERSION.SDK_INT >= 31) {
            AsyncImage(
                song.art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f; scaleX = 1.5f; scaleY = 1.5f }.blur(100.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))))
        if (song == null) return@Box
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).then(dismissDrag).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Paper.copy(alpha = 0.85f))
                }
                Spacer(Modifier.weight(1f))
                QueuePosition(state.currentIndex, state.queue.size)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.openSongMenu(song, SongMenuContext()) }) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Paper.copy(alpha = 0.85f))
                }
            }
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    (fadeIn(tween(EchoMotion.Medium, 60)) + slideInVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { it / 14 }) togetherWith fadeOut(tween(EchoMotion.Quick))
                },
                modifier = Modifier.weight(1f),
                label = "pane",
            ) { p ->
                when (p) {
                    PlayerPane.Art -> ArtPane(song, state.isPlaying, colors.accent, avs, dismissDrag)
                    PlayerPane.Lyrics -> Column {
                        CompactHeader(song) { pane = PlayerPane.Art }
                        LyricsView(song, colors.accent, Modifier.weight(1f))
                    }
                    PlayerPane.Queue -> Column {
                        CompactHeader(song) { pane = PlayerPane.Art }
                        QueueView(Modifier.weight(1f))
                    }
                }
            }
            if (pane == PlayerPane.Art) TitleBlock(song, song.id in favorites, avs) { actions.repo.toggleFavorite(song.id) }
            SeekRuler(state.durationMs, colors.accent, song.id)
            Transport(state.isPlaying, state.shuffle, state.repeat, colors.accent)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaneButton(Icons.Rounded.Lyrics, pane == PlayerPane.Lyrics, colors.accent) {
                    pane = if (pane == PlayerPane.Lyrics) PlayerPane.Art else PlayerPane.Lyrics
                }
                SleepButton(colors.accent) { sleepSheet = true }
                PaneButton(Icons.AutoMirrored.Rounded.QueueMusic, pane == PlayerPane.Queue, colors.accent) {
                    pane = if (pane == PlayerPane.Queue) PlayerPane.Art else PlayerPane.Queue
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (sleepSheet) com.novcloud.echo.ui.theme.EchoTheme(dark = true, motion = com.novcloud.echo.ui.theme.LocalMotion.current) {
        SleepTimerSheet { sleepSheet = false }
    }
}

@Composable
private fun SleepButton(accent: Color, onClick: () -> Unit) {
    val actions = LocalActions.current
    val sleep by actions.player.sleep.collectAsState()
    val remaining = rememberSleepRemaining(sleep)
    val on by animateFloatAsState(if (sleep.active) 1f else 0f, spring(dampingRatio = 0.7f), label = "sleep")
    Row(
        Modifier.height(44.dp).echoClick(corner = 8.dp, color = accent, onClick = onClick).clip(EchoShape.card)
            .background(Paper.copy(alpha = 0.10f * on)).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, null, Modifier.size(22.dp), tint = lerp(Paper.copy(alpha = 0.7f), Paper, on))
        val label = when {
            remaining != null -> formatCountdown(remaining)
            sleep.endOfItem -> "本曲"
            else -> null
        }
        var shown by remember { mutableStateOf("") }
        LaunchedEffect(label) { if (label != null) shown = label }
        androidx.compose.animation.AnimatedVisibility(
            label != null,
            enter = fadeIn(tween(EchoMotion.Standard)) + androidx.compose.animation.expandHorizontally(spring(dampingRatio = 0.8f)),
            exit = fadeOut(tween(EchoMotion.Quick)) + androidx.compose.animation.shrinkHorizontally(tween(EchoMotion.Standard)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(5.dp).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(label ?: shown, style = EchoType.numeral(14.sp), color = Paper)
            }
        }
    }
}

@Composable
private fun QueuePosition(index: Int, size: Int) {
    if (size <= 1 || index < 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index + 1), style = EchoType.labelBold, color = Paper)
        Box(Modifier.padding(horizontal = 8.dp).size(14.dp, 1.dp).background(Paper.copy(alpha = 0.4f)))
        Text("%02d".format(size), style = EchoType.label, color = Paper.copy(alpha = 0.5f))
    }
}

@Composable
private fun SharedTransitionScope.ArtPane(song: Song, playing: Boolean, accent: Color, avs: AnimatedVisibilityScope, dismissDrag: Modifier) {
    val actions = LocalActions.current
    val scale by animateFloatAsState(if (playing) 1f else 0.88f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "art")
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().then(dismissDrag), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 64.dp, maxHeight - 48.dp)
        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = swipe.value * 0.45f
                    rotationZ = swipe.value / 90f
                }
                .echoAura(playing, accent, corner = 4.dp, reach = 30.dp),
        ) {
            Artwork(
                song.art,
                Modifier
                    .fillMaxSize()
                    .sharedElement(rememberSharedContentState("art"), avs)
                    .shadow(if (playing) 28.dp else 8.dp, EchoShape.cover, spotColor = Color.Black)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val v = swipe.value
                                if (v < -180) actions.player.next() else if (v > 180) actions.player.previous()
                                scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.6f)) }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                            onHorizontalDrag = { _, d -> scope.launch { swipe.snapTo(swipe.value + d) } },
                        )
                    },
                EchoShape.cover, song.album, 72.dp,
            )
        }
    }
}

@Composable
private fun CompactHeader(song: Song, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.art, Modifier.size(56.dp), EchoShape.cover, song.album, 24.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Paper, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(displayArtist(song.artist), style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

@Composable
private fun SharedTransitionScope.TitleBlock(song: Song, liked: Boolean, avs: AnimatedVisibilityScope, onLike: () -> Unit) {
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                song,
                contentKey = { it.id },
                transitionSpec = {
                    (slideInVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(EchoMotion.Medium))) togetherWith
                        (slideOutVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(EchoMotion.Quick))) using
                        SizeTransform(clip = true)
                },
                modifier = Modifier.sharedBounds(rememberSharedContentState("title"), avs).clipToBounds(),
                label = "title",
            ) { s ->
                val text = markTitle(s.title)
                Text(
                    text,
                    style = displayStyle(text, 34.sp), color = Paper, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2200),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${displayArtist(song.artist)}  /  ${displayAlbum(song.album)}",
                style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.62f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { actions.openArtist(song.artists.first()) },
            )
        }
        val likeScale = remember { Animatable(1f) }
        val scope = rememberCoroutineScope()
        IconButton(onClick = {
            onLike()
            scope.launch { likeScale.snapTo(0.55f); likeScale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMedium)) }
        }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
                Modifier.size(28.dp).graphicsLayer { scaleX = likeScale.value; scaleY = likeScale.value },
                tint = if (liked) Vermilion else Paper.copy(alpha = 0.8f),
            )
        }
    }
}

private fun markTitle(t: String) = if (isCjk(t)) t else t.uppercase()

@Composable
private fun SeekRuler(durationMs: Long, accent: Color, seed: Long) {
    val actions = LocalActions.current
    val position by actions.player.position.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val lift by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.6f), label = "lift")
    val heights = remember(seed) {
        val r = java.util.Random(seed)
        FloatArray(64) { 0.32f + 0.68f * (0.5f + 0.5f * kotlin.math.sin(it * 0.37f + r.nextFloat() * 2.4f)) * (0.55f + 0.45f * r.nextFloat()) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(34.dp)
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
        ) {
            val n = heights.size
            val gap = size.width / n
            val w = gap * 0.42f
            val head = fraction * size.width
            for (i in 0 until n) {
                val x = i * gap + gap / 2
                val near = (1f - (kotlin.math.abs(x - head) / (gap * 5f))).coerceIn(0f, 1f) * lift
                val h = size.height * (heights[i] * (0.55f + 0.25f * lift) + near * 0.3f).coerceAtMost(1f)
                val played = x <= head
                drawRect(
                    if (played) accent else Paper.copy(alpha = 0.22f),
                    androidx.compose.ui.geometry.Offset(x - w / 2, (size.height - h) / 2),
                    androidx.compose.ui.geometry.Size(w, h),
                )
            }
            drawRect(Paper, androidx.compose.ui.geometry.Offset(head - 1.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val shown = if (dragging) (dragFraction * durationMs).toLong() else position
            Text(formatTime(shown), style = EchoType.numeral(12.sp), color = Paper.copy(alpha = 0.75f))
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime((durationMs - shown).coerceAtLeast(0)), style = EchoType.numeral(12.sp), color = Paper.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun Transport(playing: Boolean, shuffle: Boolean, repeat: Int, accent: Color) {
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleIcon(Icons.Rounded.Shuffle, shuffle, accent) { actions.player.toggleShuffle() }
        BigButton(Icons.Rounded.SkipPrevious) { actions.player.previous() }
        Box(
            Modifier.size(80.dp).echoClick(corner = 18.dp, reach = 22.dp, color = accent, pressedScale = 0.88f) { actions.player.toggle() }
                .clip(RoundedCornerShape(18.dp)).background(Paper),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(playing, transitionSpec = { scaleIn(spring(dampingRatio = 0.45f)) + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "play") { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(44.dp), tint = Ink)
            }
        }
        BigButton(Icons.Rounded.SkipNext) { actions.player.next() }
        ToggleIcon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, repeat != Player.REPEAT_MODE_OFF, accent) {
            actions.player.cycleRepeat()
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(62.dp).echoClick(corner = 12.dp, color = Paper.copy(alpha = 0.6f), pressedScale = 0.82f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(42.dp), tint = Paper) }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.size(44.dp).clip(EchoShape.card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(23.dp), tint = if (on) Paper else Paper.copy(alpha = 0.45f))
        Spacer(Modifier.height(4.dp))
        val dot by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f), label = "dot")
        Box(Modifier.size((5 * dot).dp).background(accent))
    }
}

@Composable
private fun PaneButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, accent: Color, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, label = "pane")
    Box(
        Modifier.size(44.dp).echoClick(corner = 8.dp, color = accent, onClick = onClick).clip(EchoShape.card).background(Paper.copy(alpha = 0.92f * bg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(23.dp), tint = lerp(Paper.copy(alpha = 0.7f), Ink, bg))
    }
}
