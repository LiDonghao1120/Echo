package com.novcloud.echo.ui.player

import com.novcloud.echo.ui.theme.EchoMotion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.bouncyClick
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.palette
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SharedTransitionScope.MiniPlayer(avs: AnimatedVisibilityScope, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val position by actions.player.position.collectAsState()
    val song = state.song ?: return
    val colors = rememberArtColors(song.art)
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val progress = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .height(60.dp)
            .sharedBounds(rememberSharedContentState("player_bg"), avs, clipInOverlayDuringTransition = OverlayClip(EchoShape.card))
            .shadow(14.dp, EchoShape.card, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(EchoShape.card)
            .background(Ink)
            .bouncyClick(onClick = onExpand)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dragX.value
                        scope.launch {
                            if (abs(v) > 140) {
                                if (v < 0) actions.player.next() else actions.player.previous()
                            }
                            dragX.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                    },
                    onHorizontalDrag = { _, d -> scope.launch { dragX.snapTo(dragX.value + d * 0.6f) } },
                )
            },
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Paper.copy(alpha = 0.08f)))
        Box(Modifier.fillMaxWidth(progress).height(2.dp).background(colors.accent))
        Row(
            Modifier.fillMaxHeight().padding(start = 8.dp, end = 6.dp).offset { IntOffset(dragX.value.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                song.art,
                Modifier
                    .sharedElement(rememberSharedContentState("art"), avs)
                    .size(44.dp),
                EchoShape.cover, song.album, 18.dp,
            )
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = song,
                contentKey = { it.id },
                transitionSpec = {
                    (slideInVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(EchoMotion.Standard))) togetherWith
                        (slideOutVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(EchoMotion.Quick))) using
                        SizeTransform(clip = true)
                },
                modifier = Modifier
                    .weight(1f)
                    .sharedBounds(rememberSharedContentState("title"), avs)
                    .clipToBounds()
                    .graphicsLayer { alpha = 1f - (abs(dragX.value) / 400f).coerceIn(0f, 0.7f) },
                label = "mini_title",
            ) { s ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        s.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Paper, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        displayArtist(s.artist), style = MaterialTheme.typography.bodySmall,
                        color = Paper.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier.size(40.dp).echoClick(corner = 8.dp, color = colors.accent) { actions.player.toggle() }
                    .clip(EchoShape.card).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(state.isPlaying, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "pp") { p ->
                    Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(26.dp), tint = Ink)
                }
            }
            IconButton(onClick = { actions.player.next() }) {
                Icon(Icons.Rounded.SkipNext, null, Modifier.size(26.dp), tint = Paper.copy(alpha = 0.85f))
            }
        }
    }
}
