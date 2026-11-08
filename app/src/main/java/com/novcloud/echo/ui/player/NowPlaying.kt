package com.novcloud.echo.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.novcloud.echo.R
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.SongMenuContext
import com.novcloud.echo.ui.components.echoAura
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.formatTime
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.LightSystemIcons
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.Vermilion
import com.novcloud.echo.ui.theme.displayStyle
import com.novcloud.echo.ui.theme.isCjk
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class PlayerPane { Art, Lyrics, Queue }

@Composable
fun SharedTransitionScope.NowPlayingScreen(avs: AnimatedVisibilityScope, onCollapse: () -> Unit, startPane: PlayerPane = PlayerPane.Art) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    com.novcloud.echo.ui.components.PrefetchNeighbourArt(state)
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
            com.novcloud.echo.ui.components.SwapImage(
                song.art, slot = "now_playing_backdrop", modifier =
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f; scaleX = 1.5f; scaleY = 1.5f }.blur(100.dp),
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
                IconButton(onClick = { actions.openSongMenu(song, SongMenuContext(nowPlaying = true)) }) {
                    Icon(Icons.Rounded.MoreHoriz, null, tint = Paper.copy(alpha = 0.85f))
                }
            }
            PlayerStage(
                song, pane, state.isPlaying, song.id in favorites, colors.accent, avs,
                dismissDrag = dismissDrag,
                onLike = { actions.repo.toggleFavorite(song.id) },
                onArt = { pane = PlayerPane.Art },
                modifier = Modifier.weight(1f),
            )
            SeekRuler(state.durationMs, colors.accent, song.id)
            Transport(state.isPlaying, state.shuffle, state.repeat, colors.accent)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaneButton(Icons.Rounded.Lyrics, pane == PlayerPane.Lyrics, colors.accent, "pane_lyrics") {
                    pane = if (pane == PlayerPane.Lyrics) PlayerPane.Art else PlayerPane.Lyrics
                }
                SleepButton(colors.accent) { sleepSheet = true }
                PaneButton(Icons.AutoMirrored.Rounded.QueueMusic, pane == PlayerPane.Queue, colors.accent, "pane_queue") {
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
            sleep.endOfItem -> stringResource(R.string.sleep_this_track)
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

/**
 * The middle of the player: the cover, the title, the heart and the lyrics or queue panel laid out together.
 * One cover is drawn once and morphs between the big centred slot and the header thumbnail as the morph runs 0 → 1;
 * both slots come from this one layout pass, and every per-frame value is read in placement layers only.
 */
@Composable
private fun SharedTransitionScope.PlayerStage(
    song: Song,
    pane: PlayerPane,
    playing: Boolean,
    liked: Boolean,
    accent: Color,
    avs: AnimatedVisibilityScope,
    dismissDrag: Modifier,
    onLike: () -> Unit,
    onArt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = LocalActions.current
    val motion = com.novcloud.echo.ui.theme.LocalMotion.current
    val morph = remember { Animatable(if (pane == PlayerPane.Art) 0f else 1f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(pane, motion) {
        val target = if (pane == PlayerPane.Art) 0f else 1f
        if (motion) morph.animateTo(target, MORPH_SPRING) else morph.snapTo(target)
    }
    val atArt by remember { derivedStateOf { morph.value == 0f } }
    val lastPanel = remember { arrayOf(if (pane == PlayerPane.Art) PlayerPane.Lyrics else pane) }
    val panel = if (pane != PlayerPane.Art) pane.also { lastPanel[0] = it } else lastPanel[0]
    val panelShown = pane != PlayerPane.Art || !atArt
    val play = animateFloatAsState(if (playing) 1f else 0.88f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "art")
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val geo = remember { CoverGeometry() }
    val f = { morph.value }

    Layout(
        modifier = modifier.fillMaxWidth().then(if (pane == PlayerPane.Art) dismissDrag else Modifier),
        content = {
            if (panelShown) {
                Box(Modifier.layoutId(Slot.Panel)) {
                    AnimatedContent(
                        targetState = panel,
                        transitionSpec = {
                            (fadeIn(tween(EchoMotion.Medium, 60)) + slideInVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { it / 14 }) togetherWith
                                fadeOut(tween(EchoMotion.Quick))
                        },
                        label = "panel",
                    ) { p ->
                        if (p == PlayerPane.Queue) QueueView(Modifier.fillMaxSize()) else LyricsView(song, accent, Modifier.fillMaxSize())
                    }
                }
            }
            AnimatedContent(
                song,
                contentKey = { it.id },
                transitionSpec = {
                    (slideInVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(EchoMotion.Medium))) togetherWith
                        (slideOutVertically(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(EchoMotion.Quick))) using
                        SizeTransform(clip = true)
                },
                modifier = Modifier.layoutId(Slot.Title).testTag("np_title")
                    .then(if (atArt) Modifier.sharedBounds(rememberSharedContentState("title"), avs) else Modifier).clipToBounds(),
                label = "title",
            ) { s ->
                val text = markTitle(s.title)
                Text(
                    text,
                    style = displayStyle(text, 34.sp), color = Paper, maxLines = 1,
                    modifier = if (pane == PlayerPane.Art) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2200) else Modifier,
                )
            }
            val byline = "${displayArtist(song.artist)}  /  ${displayAlbum(song.album)}"
            Text(
                byline,
                style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.62f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(Slot.BigByline).clickable(enabled = pane == PlayerPane.Art) { actions.openArtist(song.artists.first()) },
            )
            Text(
                byline,
                style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.62f), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(Slot.SmallByline).testTag("np_small_byline"),
            )
            if (pane != PlayerPane.Art) Box(Modifier.layoutId(Slot.Header).clickable(onClick = onArt))
            Box(Modifier.layoutId(Slot.Cover).echoAura(playing, accent, corner = 4.dp, reach = 30.dp, amount = { 1f - f() })) {
                Artwork(
                    song.art,
                    Modifier
                        .fillMaxSize()
                        .then(if (atArt) Modifier.sharedElement(rememberSharedContentState("art"), avs) else Modifier)
                        .graphicsLayer {
                            val t = f().coerceIn(0f, 1f)
                            val s = geo.scale(t, play.value).coerceAtLeast(0.01f)
                            val level = ((play.value - 0.88f) / 0.12f).coerceIn(0f, 1f)
                            val rest = (8f + 20f * level).dp.toPx()
                            shadowElevation = rest + (3.dp.toPx() - rest) * t
                            spotShadowColor = Color.Black
                            shape = RoundedCornerShape(4.dp.toPx() / s)
                            clip = true
                        }
                        .testTag("np_cover")
                        .then(
                            if (pane == PlayerPane.Art) Modifier.pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val v = swipe.value
                                        if (v < -180) actions.player.next() else if (v > 180) actions.player.previous()
                                        scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.6f)) }
                                    },
                                    onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                                    onHorizontalDrag = { _, d -> scope.launch { swipe.snapTo(swipe.value + d) } },
                                )
                            } else Modifier,
                        ),
                    RectangleShape, song.album, 72.dp, slot = com.novcloud.echo.ui.components.NOW_PLAYING_SLOT,
                )
            }
            Box(Modifier.layoutId(Slot.Heart)) { LikeButton(liked, onLike) }
        },
    ) { ms, c ->
        val w = c.maxWidth
        val h = c.maxHeight
        fun m(slot: Slot) = ms.firstOrNull { it.layoutId == slot }
        val headerH = 76.dp.roundToPx()
        val thumb = 56.dp.roundToPx()
        val thumbX = 24.dp.roundToPx()
        val thumbY = 10.dp.roundToPx()
        val heart = m(Slot.Heart)!!.measure(Constraints())
        val heartX = w - 14.dp.roundToPx() - heart.width
        val bigX = 28.dp.roundToPx()
        val title = m(Slot.Title)!!.measure(Constraints(maxWidth = (heartX - bigX).coerceAtLeast(0)))
        val bigBy = m(Slot.BigByline)!!.measure(Constraints(maxWidth = (heartX - bigX).coerceAtLeast(0)))
        val smallX = thumbX + thumb + 14.dp.roundToPx()
        val smallBy = m(Slot.SmallByline)!!.measure(Constraints(maxWidth = (heartX - 4.dp.roundToPx() - smallX).coerceAtLeast(0)))
        val bigBlock = title.height + 6.dp.roundToPx() + bigBy.height
        val rowH = 6.dp.roundToPx() + maxOf(bigBlock, heart.height)
        val artH = (h - rowH).coerceAtLeast(0)
        val side = minOf(w - 64.dp.roundToPx(), artH - 48.dp.roundToPx()).coerceAtLeast(1)
        val cover = m(Slot.Cover)!!.measure(Constraints.fixed(side, side))
        val panelP = m(Slot.Panel)?.measure(Constraints.fixed(w, (h - headerH).coerceAtLeast(0)))
        val hit = m(Slot.Header)?.measure(Constraints.fixed(heartX.coerceAtLeast(0), headerH))
        val coverX = (w - side) / 2
        val coverY = (artH - side) / 2
        geo.side = side.toFloat()
        geo.thumb = thumb.toFloat()
        val titleBigY = h - bigBlock
        val bigByY = h - bigBy.height
        val smallTitleH = title.height * TITLE_SCALE
        val gap = 2.dp.toPx()
        val titleSmallY = thumbY + (thumb - (smallTitleH + gap + smallBy.height)) / 2f
        val smallByY = (titleSmallY + smallTitleH + gap).roundToInt()
        val heartBigY = h - heart.height
        val heartSmallY = thumbY + (thumb - heart.height) / 2
        val lift = 28.dp.toPx()
        layout(w, h) {
            panelP?.placeWithLayer(0, headerH) {
                val t = f()
                alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
                translationY = (1f - t.coerceIn(0f, 1f)) * lift
            }
            title.placeWithLayer(bigX, titleBigY) {
                val t = f()
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (smallX - bigX) * t
                translationY = (titleSmallY - titleBigY) * t
                val sc = 1f + (TITLE_SCALE - 1f) * t
                scaleX = sc; scaleY = sc
            }
            bigBy.placeWithLayer(bigX, bigByY) {
                val t = f()
                translationX = (smallX - bigX) * t
                translationY = (smallByY - bigByY) * t
                alpha = 1f - ((t - 0.55f) / 0.15f).coerceIn(0f, 1f)
            }
            smallBy.placeWithLayer(smallX, smallByY) {
                val t = f()
                translationX = (bigX - smallX) * (1f - t)
                translationY = (bigByY - smallByY) * (1f - t)
                alpha = ((t - 0.4f) / 0.15f).coerceIn(0f, 1f)
            }
            hit?.place(0, 0)
            cover.placeWithLayer(coverX, coverY) {
                val t = f()
                val rest = 1f - t.coerceIn(0f, 1f)
                val s = geo.scale(t, play.value)
                scaleX = s; scaleY = s
                translationX = (thumbX + thumb / 2f - (coverX + side / 2f)) * t + swipe.value * 0.45f * rest
                translationY = (thumbY + thumb / 2f - (coverY + side / 2f)) * t
                rotationZ = swipe.value / 90f * rest
            }
            heart.placeWithLayer(heartX, heartBigY) {
                translationY = (heartSmallY - heartBigY) * f()
            }
        }
    }
}

private val MORPH_SPRING = spring<Float>(dampingRatio = 0.9f, stiffness = 140f)

private enum class Slot { Panel, Title, BigByline, SmallByline, Header, Cover, Heart }

/** The Anton title shrinks from 34sp under the big cover to 20sp beside the thumbnail. */
private const val TITLE_SCALE = 20f / 34f

/** Size of the big cover slot and of the header thumbnail in px, from the last layout pass. */
private class CoverGeometry {
    var side by mutableFloatStateOf(1f)
    var thumb by mutableFloatStateOf(1f)

    /** Scale of the big-slot cover at morph [t]: the playing scale in the big slot, the thumbnail at 1. */
    fun scale(t: Float, play: Float): Float = (side * play + (thumb - side * play) * t) / side
}

@Composable
private fun LikeButton(liked: Boolean, onLike: () -> Unit) {
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
private fun PaneButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, label = "pane")
    Box(
        Modifier.testTag(tag).size(44.dp).echoClick(corner = 8.dp, color = accent, onClick = onClick).clip(EchoShape.card).background(Paper.copy(alpha = 0.92f * bg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(23.dp), tint = lerp(Paper.copy(alpha = 0.7f), Ink, bg))
    }
}
