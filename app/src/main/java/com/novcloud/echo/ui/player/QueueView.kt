package com.novcloud.echo.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.novcloud.echo.R
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.playback.QueueEntry
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.Vermilion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A queue row's identity across moves: the song plus which copy of it (a song can be queued twice). */
internal fun queueKeys(entries: List<QueueEntry>): List<String> {
    val seen = HashMap<Long, Int>()
    val byIndex = entries.sortedBy { it.index }.associate { e -> e.index to "${e.song.id}#${seen.merge(e.song.id, 1, Int::plus)}" }
    return entries.map { byIndex.getValue(it.index) }
}

@Composable
fun QueueView(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val pos = state.queue.indexOfFirst { it.index == state.currentIndex }.coerceAtLeast(0)
    val upcoming = state.queue.drop(pos + 1)
    val keys = remember(state.queue) { queueKeys(state.queue).drop(pos + 1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 22.dp.toPx() }
    val latest = remember(upcoming, keys) { upcoming.zip(keys) }
    val reorder = remember { QueueReorder(listState, scope, haptics, edge, maxSpeed).also { it.sync(latest) } }
    reorder.onCommit = { from, to -> actions.player.moveMediaItem(from, to) }
    LaunchedEffect(latest) { reorder.sync(latest) }
    LaunchedEffect(state.currentIndex) { if (reorder.draggingKey == null) listState.scrollToItem(0) }
    val rows = reorder.rows
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            if (upcoming.isNotEmpty()) TextButton(onClick = { actions.player.clearQueue() }) {
                Text(stringResource(R.string.action_clear), color = Color.White.copy(alpha = 0.7f))
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.weight(1f).testTag("queue_list")) {
            itemsIndexed(rows, key = { _, r -> r.second }) { i, (e, key) ->
                val dragging = reorder.draggingKey == key
                val lift by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 900f), label = "lift")
                val base = Modifier.testTag("queue_row_$i")
                QueueRow(
                    e = e,
                    dragging = dragging,
                    lift = lift,
                    reorder = reorder,
                    key = key,
                    onTop = { actions.player.moveToNext(e.index) },
                    modifier = if (dragging) base.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) }
                    else base.animateItem(fadeInSpec = tween(EchoMotion.Quick), placementSpec = spring(stiffness = 700f, dampingRatio = 0.9f), fadeOutSpec = tween(EchoMotion.Quick)),
                )
            }
        }
    }
}

/**
 * Drag-to-reorder for the queue. While a row is held the sheet shows a local copy of the list; rows swap as the held
 * row's middle crosses a neighbour, the list scrolls when the row nears an edge, and on release one move is sent:
 * the held item goes to the place, in play order, of the item that was at its drop position.
 */
@Stable
internal class QueueReorder(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val edge: Float,
    private val maxSpeed: Float,
) {
    var rows by mutableStateOf<List<Pair<QueueEntry, String>>>(emptyList())
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    private var delta by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var original: List<Pair<QueueEntry, String>> = emptyList()
    private var held: List<String>? = null
    private var heldAt = 0L
    private var scroller: Job? = null
    var onCommit: (from: Int, to: Int) -> Unit = { _, _ -> }

    private var latest: List<Pair<QueueEntry, String>> = emptyList()

    /** Takes the player's queue unless a drag is in progress or a just-sent move has not come back yet. */
    fun sync(latest: List<Pair<QueueEntry, String>>) {
        this.latest = latest
        if (draggingKey != null) return
        val keys = latest.map { it.second }
        val waiting = held
        if (waiting != null && keys == original.map { it.second } && System.currentTimeMillis() - heldAt < HOLD_MS) return
        held = null
        if (rows != latest) rows = latest
    }

    fun offsetOf(key: String): Float {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return startOffset + delta - item.offset
    }

    fun start(key: String, longPress: Boolean) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        original = rows
        startOffset = item.offset
        delta = 0f
        draggingKey = key
        haptics.performHapticFeedback(if (longPress) HapticFeedbackType.LongPress else HapticFeedbackType.GestureThresholdActivate)
    }

    fun drag(dy: Float) {
        if (draggingKey == null) return
        delta += dy
        swapIfNeeded()
        autoScroll()
    }

    fun end() {
        val key = draggingKey ?: return
        scroller?.cancel()
        val from = original.indexOfFirst { it.second == key }
        val to = rows.indexOfFirst { it.second == key }
        draggingKey = null
        delta = 0f
        if (from >= 0 && to >= 0 && from != to) {
            held = rows.map { it.second }
            heldAt = System.currentTimeMillis()
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            onCommit(original[from].first.index, original[to].first.index)
            scope.launch {
                kotlinx.coroutines.delay(HOLD_MS + 50)
                sync(latest)
            }
        } else {
            sync(latest)
        }
    }

    private fun swapIfNeeded() {
        val key = draggingKey ?: return
        val info = list.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        // Until the list has laid out the last swap its positions are stale; swapping again would undo it.
        if (cur.index != rows.indexOfFirst { it.second == key }) return
        val top = cur.offset + offsetOf(key)
        val middle = top + cur.size / 2f
        val target = info.visibleItemsInfo.firstOrNull { it.key != key && middle >= it.offset && middle <= it.offset + it.size } ?: return
        val from = rows.indexOfFirst { it.second == key }
        val to = rows.indexOfFirst { it.second == target.key }
        if (from < 0 || to < 0) return
        val keepFirst = list.firstVisibleItemIndex == from || list.firstVisibleItemIndex == to
        val first = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        rows = rows.toMutableList().apply { add(to, removeAt(from)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        // Swapping with the first visible row would otherwise let the list follow the moved item.
        if (keepFirst) scope.launch { list.scrollToItem(first, firstOffset) }
    }

    private fun speed(): Float {
        val key = draggingKey ?: return 0f
        val info = list.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = cur.offset + offsetOf(key)
        val bottom = top + cur.size
        val start = info.viewportStartOffset.toFloat()
        val end = info.viewportEndOffset.toFloat() - info.afterContentPadding
        return when {
            top < start + edge && list.canScrollBackward -> -maxSpeed * ((start + edge - top) / edge).coerceIn(0f, 1f).let { it * it }
            bottom > end - edge && list.canScrollForward -> maxSpeed * ((bottom - (end - edge)) / edge).coerceIn(0f, 1f).let { it * it }
            else -> 0f
        }
    }

    private fun autoScroll() {
        if (scroller?.isActive == true || speed() == 0f) return
        scroller = scope.launch {
            while (isActive && draggingKey != null) {
                val v = speed()
                if (v == 0f) break
                withFrameNanos { }
                list.dispatchRawDelta(v)
                swapIfNeeded()
            }
        }
    }

    private companion object {
        const val HOLD_MS = 1500L
    }
}

@Composable
private fun QueueRow(
    e: QueueEntry,
    dragging: Boolean,
    lift: Float,
    reorder: QueueReorder,
    key: String,
    onTop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = LocalActions.current
    val latest by rememberUpdatedState(reorder)
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) { actions.player.removeAt(e.index); true } else false
    })
    val removeLabel = stringResource(R.string.queue_remove)
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !dragging,
        backgroundContent = {
            val c by animateColorAsState(if (dismiss.targetValue == SwipeToDismissBoxValue.Settled) Color.Transparent else Color(0xFFE5484D), label = "bg")
            // The row itself is transparent over the player background, so the icon only shows while swiping.
            if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(Modifier.fillMaxSize().background(c).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Rounded.Delete, removeLabel, tint = Color.White)
                }
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 1f + 0.02f * lift
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    if (lift > 0f) {
                        drawRect(Color(0xFF1E1C19).copy(alpha = 0.96f * lift))
                        val inset = 4.dp.toPx()
                        drawRect(
                            Color.White.copy(alpha = 0.14f * lift),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - 2 * inset, size.height - 2 * inset),
                            style = Stroke(1.dp.toPx()),
                        )
                        drawRect(Vermilion.copy(alpha = lift), size = Size(3.dp.toPx(), size.height))
                    }
                }
                .pointerInput(key) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { latest.start(key, longPress = true) },
                        onDrag = { change, amount -> change.consume(); latest.drag(amount.y) },
                        onDragEnd = { latest.end() },
                        onDragCancel = { latest.end() },
                    )
                }
                .clickable { actions.player.playAt(e.index) }
                .padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(e.song.art, Modifier.size(44.dp), RoundedCornerShape(6.dp), e.song.album, 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.song.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(e.song.artist), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onTop) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.queue_move_next), tint = Color.White.copy(alpha = 0.6f)) }
            DragHandle(
                active = dragging,
                label = stringResource(R.string.queue_drag),
                modifier = Modifier.pointerInput(key) {
                    detectDragGestures(
                        onDragStart = { latest.start(key, longPress = false) },
                        onDrag = { change, amount -> change.consume(); latest.drag(amount.y) },
                        onDragEnd = { latest.end() },
                        onDragCancel = { latest.end() },
                    )
                },
            )
        }
    }
}

/** Three square-ended bars; the middle one turns vermilion while the row is held. */
@Composable
private fun DragHandle(active: Boolean, label: String, modifier: Modifier = Modifier) {
    val accent by animateColorAsState(if (active) Vermilion else Color.White.copy(alpha = 0.45f), tween(EchoMotion.Quick), label = "handle")
    Box(modifier.size(44.dp).semantics { contentDescription = label }.testTag("queue_handle"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
            val h = 2.dp.toPx()
            val gap = (size.height - 3 * h) / 2
            for (i in 0 until 3) {
                drawRect(
                    if (i == 1) accent else Color.White.copy(alpha = 0.45f),
                    topLeft = Offset(0f, i * (h + gap)),
                    size = Size(size.width, h),
                )
            }
        }
    }
}
