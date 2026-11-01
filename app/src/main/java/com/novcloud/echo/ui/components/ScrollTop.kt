package com.novcloud.echo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novcloud.echo.R
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** When the back-to-top button shows, and how far a long jump snaps before it animates. */
object ScrollTop {
    /** Shown once the list is this many screens down... */
    const val SHOW_SCREENS = 1.5f

    /** ...and hidden again only above this, so it does not flicker at the edge. */
    const val HIDE_SCREENS = 1.0f

    /** The key with its paper ring, and the room a list leaves at its end so its last row can rise clear of the key. */
    val RING = 3.dp
    val KEY = 46.dp + RING * 2
    val CLEARANCE = KEY + 12.dp

    /** Distance from the top in px, from the first visible item and the average height of a line of items. */
    fun scrolled(firstIndex: Int, firstOffset: Int, lineSize: Float, perLine: Int): Float =
        (firstIndex / perLine.coerceAtLeast(1)) * lineSize.coerceAtLeast(0f) + firstOffset

    fun visible(scrolled: Float, viewport: Int, wasVisible: Boolean): Boolean {
        if (viewport <= 0) return false
        val screens = scrolled / viewport
        return if (wasVisible) screens > HIDE_SCREENS else screens > SHOW_SCREENS
    }

    /**
     * Where to jump first so the animated part of the way up is about one screen long, or null when the list is close
     * enough to animate all of it. [onScreen] is how many items fit on a screen.
     */
    fun snapTo(firstIndex: Int, onScreen: Int): Int? {
        val near = onScreen.coerceAtLeast(1)
        return if (firstIndex > near * 2) near else null
    }
}

/** Whether a list is far enough down for the back-to-top button, and the jump back up. */
@Stable
class ScrollTopState internal constructor(
    internal val position: () -> Pair<Float, Int>,
    private val jump: suspend () -> Unit,
) {
    var visible by mutableStateOf(false)
        internal set

    suspend fun scrollToTop() = jump()
}

private fun LazyListState.position(): Pair<Float, Int> {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0f to 0
    val line = items.sumOf { it.size }.toFloat() / items.size + info.mainAxisItemSpacing
    return ScrollTop.scrolled(firstVisibleItemIndex, firstVisibleItemScrollOffset, line, 1) to info.viewportSize.height
}

private fun LazyGridState.position(): Pair<Float, Int> {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0f to 0
    val rows = items.groupBy { it.row }
    val line = rows.values.sumOf { r -> r.maxOf { it.size.height } }.toFloat() / rows.size + info.mainAxisItemSpacing
    val perLine = rows.values.maxOf { it.size }
    return ScrollTop.scrolled(firstVisibleItemIndex, firstVisibleItemScrollOffset, line, perLine) to info.viewportSize.height
}

/** Back to the top quickly: a long way down snaps to about a screen from the top first, then animates. */
suspend fun LazyListState.scrollToTopFast() {
    ScrollTop.snapTo(firstVisibleItemIndex, layoutInfo.visibleItemsInfo.size)?.let { scrollToItem(it) }
    animateScrollToItem(0)
}

suspend fun LazyGridState.scrollToTopFast() {
    ScrollTop.snapTo(firstVisibleItemIndex, layoutInfo.visibleItemsInfo.size)?.let { scrollToItem(it) }
    animateScrollToItem(0)
}

@Composable
fun rememberScrollTop(state: LazyListState): ScrollTopState =
    track(remember(state) { ScrollTopState({ state.position() }) { state.scrollToTopFast() } })

@Composable
fun rememberScrollTop(state: LazyGridState): ScrollTopState =
    track(remember(state) { ScrollTopState({ state.position() }) { state.scrollToTopFast() } })

@Composable
private fun track(s: ScrollTopState): ScrollTopState {
    LaunchedEffect(s) {
        snapshotFlow { s.position() }.collect { (scrolled, viewport) -> s.visible = ScrollTop.visible(scrolled, viewport, s.visible) }
    }
    return s
}

/**
 * A square back-to-top key in the 回 language: an ink (cream in the dark) square with a fine inner frame, an arrow
 * and a vermilion line it rises to. Springs in once the list is [ScrollTop.SHOW_SCREENS] screens down.
 */
@Composable
fun BoxScope.ScrollTopButton(
    state: ScrollTopState,
    modifier: Modifier = Modifier,
    bottom: Dp = LocalBottomInset.current + 16.dp,
    end: Dp = 16.dp,
    onClick: (suspend () -> Unit)? = null,
    ring: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val p = palette
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val label = stringResource(R.string.scroll_to_top)
    AnimatedVisibility(
        state.visible,
        modifier.align(Alignment.BottomEnd).padding(end = end, bottom = bottom),
        enter = fadeIn(tween(EchoMotion.Quick)) +
            scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) +
            slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it / 2 },
        exit = fadeOut(tween(EchoMotion.Quick)) + scaleOut(tween(EchoMotion.Quick), targetScale = 0.8f) +
            slideOutVertically(tween(EchoMotion.Standard)) { it / 3 },
    ) {
        Box(
            Modifier
                .size(ScrollTop.KEY)
                .testTag("scroll_top")
                .semantics { contentDescription = label; role = Role.Button }
                .echoClick(corner = 0.dp, color = p.signal) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { onClick?.invoke() ?: state.scrollToTop() }
                }
                // A paper ring first, so the ink key never runs into an ink tile or card behind it and reads as a stray
                // offset shadow; on the page itself the ring is invisible.
                .background(ring, RectangleShape)
                .padding(ScrollTop.RING)
                .background(ink, RectangleShape)
                .padding(4.dp)
                .border(1.dp, paper.copy(alpha = 0.28f), RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val s = 2.dp.toPx()
                drawRect(p.signal, Offset(w * 0.18f, 0f), Size(w * 0.64f, 2.5.dp.toPx()))
                val top = size.height * 0.30f
                drawLine(paper, Offset(w / 2, size.height), Offset(w / 2, top + s / 2), s, StrokeCap.Square)
                val arm = w * 0.26f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2 - arm, top + arm)
                    lineTo(w / 2, top)
                    lineTo(w / 2 + arm, top + arm)
                }
                drawPath(path, paper, style = Stroke(s, cap = StrokeCap.Square, join = androidx.compose.ui.graphics.StrokeJoin.Miter))
            }
        }
    }
}

/** Runs [action] whenever [signal] names [page]: the library's "this tab to the top" requests. */
@Composable
fun ScrollTopOn(signal: SharedFlow<Int>, page: Int, action: suspend () -> Unit) {
    val latest by rememberUpdatedState(action)
    LaunchedEffect(signal, page) { signal.collect { if (it == page) latest() } }
}

/** Runs [action] when the bottom bar's tab [route] is tapped while it is already showing. */
@Composable
fun OnTabReselect(route: String, action: suspend () -> Unit) {
    val actions = LocalActions.current
    val latest by rememberUpdatedState(action)
    LaunchedEffect(actions, route) { actions.reselected.collect { if (it == route) latest() } }
}
