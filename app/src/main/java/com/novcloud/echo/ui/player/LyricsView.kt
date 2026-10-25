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
    var loaded by remember(song.id, tree) { mutableStateOf(container.lyrics.peek(song, tree)) }
    LaunchedEffect(song.id, tree) {
        if (loaded == null) loaded = LyricsLoader.Loaded(container.lyrics.load(song, tree))
    }
    if (keepOn) {
        DisposableEffect(Unit) {
            val w = (context as? Activity)?.window
            w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
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
                if (tree == null) androidx.compose.material3.TextButton(onClick = { picker.launch(null) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("选择歌词文件夹", color = Color.White.copy(alpha = 0.85f))
                }
            }
            else -> key(song.id, l) { LyricsList(l) }
        }
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
private fun LyricsList(lyrics: Lyrics) {
    val actions = LocalActions.current
    val position by actions.player.position.collectAsState()
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(position + 250) } }
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
                            actions.player.seekTo(line.timeMs)
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
