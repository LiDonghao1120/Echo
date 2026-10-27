package com.novcloud.echo.ui.player

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.novcloud.echo.ui.components.echoClick
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.lyrics.Lyrics
import com.novcloud.echo.data.lyrics.LyricsLoader
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.novcloud.echo.ui.components.LocalActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LyricsView(song: Song, accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val tree by container.lyricsTree.collectAsState()
    val keepOn by container.prefs.keepScreenOnLyrics.collectAsState(initial = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                container.lyrics.invalidate()
                container.prefs.setLyricsTree(uri.toString())
            }
        }
    }
    val override by remember(song.key) { container.db.lyricOverrides().observe(song.key) }.collectAsState(initial = null)
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    var reload by remember { mutableStateOf(0) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (container.lyrics.pick(song, uri)) reload++ else toast("不是可用的歌词文件")
        }
    }
    var loaded by remember(song.id, tree, reload) { mutableStateOf(container.lyrics.peek(song, tree)) }
    LaunchedEffect(song.id, tree, reload) {
        if (loaded == null) loaded = LyricsLoader.Loaded(container.lyrics.load(song, tree))
    }
    if (keepOn) {
        DisposableEffect(Unit) {
            val w = (context as? Activity)?.window
            w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    val offset = override?.offsetMs ?: 0L
    Box(modifier.fillMaxSize()) {
        val state = loaded
        val l = state?.lyrics
        when {
            state == null -> {
                var slow by remember(song.id) { mutableStateOf(false) }
                LaunchedEffect(song.id) { delay(500); slow = true }
                if (slow) Text("读取歌词…", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.Center))
            }
            l == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lyrics, null, tint = Color.White.copy(alpha = 0.35f))
                Text("暂无歌词", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.padding(top = 10.dp))
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp)) {
                    androidx.compose.material3.TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text("选择歌词文件", color = Color.White.copy(alpha = 0.85f))
                    }
                    if (tree == null) androidx.compose.material3.TextButton(onClick = { picker.launch(null) }) {
                        Text("选择歌词文件夹", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            else -> {
                key(song.id, l) { LyricsList(l, offset) }
                LyricsTools(
                    offsetMs = offset,
                    fileName = override?.fileName.takeIf { override?.text != null },
                    synced = l.synced,
                    accent = accent,
                    onOffset = { v -> scope.launch { container.lyrics.setOffset(song, v) } },
                    onPick = { filePicker.launch(arrayOf("*/*")) },
                    onUnpick = { scope.launch { container.lyrics.unpick(song); reload++ } },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** Offset steps are 0.1 s; positive shows each line earlier. */
internal fun formatOffset(ms: Long): String = when {
    ms == 0L -> "0.0s"
    ms > 0 -> "提前 %.1fs".format(ms / 1000.0)
    else -> "延后 %.1fs".format(-ms / 1000.0)
}

@Composable
fun LyricsTools(
    offsetMs: Long,
    fileName: String?,
    synced: Boolean,
    accent: Color,
    onOffset: (Long) -> Unit,
    onPick: () -> Unit,
    onUnpick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var local by remember(offsetMs) { mutableLongStateOf(offsetMs) }
    fun step(d: Long) {
        local = (local + d).coerceIn(-30_000L, 30_000L)
        onOffset(local)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
        androidx.compose.animation.AnimatedVisibility(
            open,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.novcloud.echo.ui.theme.EchoMotion.Standard)) +
                androidx.compose.animation.expandVertically(spring(dampingRatio = 0.85f, stiffness = 500f), expandFrom = Alignment.Bottom),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.novcloud.echo.ui.theme.EchoMotion.Quick)) +
                androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(com.novcloud.echo.ui.theme.EchoMotion.Standard), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                Modifier.padding(bottom = 8.dp).fillMaxWidth()
                    .background(com.novcloud.echo.ui.theme.Ink.copy(alpha = 0.94f), com.novcloud.echo.ui.theme.EchoShape.card)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), com.novcloud.echo.ui.theme.EchoShape.card)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                if (synced) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("时间", style = com.novcloud.echo.ui.theme.EchoType.labelBold, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.width(44.dp))
                        StepButton(Icons.Rounded.KeyboardArrowDown, "歌词延后 0.1 秒", accent) { step(-100) }
                        Box(Modifier.weight(1f).height(44.dp).clickable(enabled = local != 0L) { local = 0; onOffset(0) }, contentAlignment = Alignment.Center) {
                            androidx.compose.animation.AnimatedContent(
                                local,
                                transitionSpec = {
                                    val up = targetState > initialState
                                    (androidx.compose.animation.slideInVertically { if (up) -it else it } + androidx.compose.animation.fadeIn()) togetherWith
                                        (androidx.compose.animation.slideOutVertically { if (up) it else -it } + androidx.compose.animation.fadeOut())
                                },
                                label = "offset",
                            ) { v ->
                                Text(
                                    formatOffset(v),
                                    style = com.novcloud.echo.ui.theme.EchoType.numeral(18.sp),
                                    color = if (v == 0L) Color.White.copy(alpha = 0.6f) else Color.White,
                                )
                            }
                        }
                        StepButton(Icons.Rounded.KeyboardArrowUp, "歌词提前 0.1 秒", accent) { step(100) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                }
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文件", style = com.novcloud.echo.ui.theme.EchoType.labelBold, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.width(44.dp))
                    Text(
                        fileName ?: "自动匹配",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fileName != null) Color.White else Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (fileName != null) androidx.compose.material3.TextButton(onClick = onUnpick) { Text("移除", color = Color.White.copy(alpha = 0.7f)) }
                    androidx.compose.material3.TextButton(onClick = onPick) { Text("选择", color = accent) }
                }
            }
        }
        val on by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 0.7f), label = "tools")
        Box(
            Modifier.size(40.dp)
                .echoClick(corner = 4.dp, color = accent) { open = !open }
                .background(Color.White.copy(alpha = 0.06f + 0.10f * on), com.novcloud.echo.ui.theme.EchoShape.chip)
                .semantics { contentDescription = "歌词调整" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Tune, null,
                Modifier.size(20.dp).graphicsLayer { rotationZ = 90f * on },
                tint = if (offsetMs != 0L || fileName != null) accent else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).echoClick(corner = 4.dp, color = accent, onClick = onClick)
            .border(1.dp, Color.White.copy(alpha = 0.16f), com.novcloud.echo.ui.theme.EchoShape.chip)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
    }
}

private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    fun find() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    var item = find() ?: withTimeoutOrNull(400) { snapshotFlow { find() }.filterNotNull().first() }
    if (item == null) {
        scrollToItem(index)
        item = withTimeoutOrNull(400) { snapshotFlow { find() }.filterNotNull().first() } ?: return
        scrollBy(centerDelta(item))
        return
    }
    val d = centerDelta(item)
    if (animate) animateScrollBy(d, spring(dampingRatio = 1f, stiffness = 120f)) else scrollBy(d)
}

private fun LazyListState.centerDelta(item: LazyListItemInfo): Float {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return item.offset + item.size / 2f - center
}

@Composable
private fun LyricsList(lyrics: Lyrics, offsetMs: Long) {
    val actions = LocalActions.current
    val position by actions.player.position.collectAsState()
    val offset by androidx.compose.runtime.rememberUpdatedState(offsetMs)
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(position + 250 + offset) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (lyrics.synced) active.coerceAtLeast(0) else 0)
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUserScroll = System.currentTimeMillis()
                return Offset.Zero
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (lyrics.synced) {
            var first by remember { mutableStateOf(true) }
            LaunchedEffect(active) {
                if (System.currentTimeMillis() - lastUserScroll < 3500) return@LaunchedEffect
                listState.centerOn(active.coerceAtLeast(0), animate = !first)
                first = false
            }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(500)
                    val idle = System.currentTimeMillis() - lastUserScroll
                    if (lastUserScroll != 0L && idle >= 3500 && !listState.isScrollInProgress) {
                        lastUserScroll = 0L
                        listState.centerOn(active.coerceAtLeast(0), animate = true)
                    }
                }
            }
        }
        val pad = if (lyrics.synced) maxHeight / 2 else 24.dp
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = pad, bottom = pad),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScroll)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val isActive = i == active || !lyrics.synced
                val alpha by animateFloatAsState(if (isActive) 1f else if (lyrics.synced) 0.32f else 0.8f, label = "a")
                val scale by animateFloatAsState(if (i == active || !lyrics.synced) 1f else 0.94f, spring(dampingRatio = 0.75f, stiffness = 300f), label = "s")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = lyrics.synced) {
                            lastUserScroll = 0L
                            actions.player.seekTo((line.timeMs - offset).coerceAtLeast(0))
                        }
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                ) {
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = if (lyrics.synced) FontWeight.ExtraBold else FontWeight.Medium,
                        color = Color.White,
                    )
                    line.translation?.let {
                        Text(
                            it,
                            style = if (lyrics.synced) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.62f),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
