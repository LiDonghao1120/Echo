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
import com.novcloud.echo.ui.components.LocalActions
import kotlinx.coroutines.delay

@Composable
fun LyricsView(song: Song, accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val tree by container.prefs.lyricsTree.collectAsState(initial = null)
    val keepOn by container.prefs.keepScreenOnLyrics.collectAsState(initial = true)
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id, tree) {
        lyrics = container.lyrics.load(song, tree)
        loaded = true
    }
    if (keepOn) {
        DisposableEffect(Unit) {
            val w = (context as? Activity)?.window
            w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    Box(modifier.fillMaxSize()) {
        val l = lyrics
        when {
            !loaded -> Unit
            l == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lyrics, null, tint = Color.White.copy(alpha = 0.35f))
                Text("暂无歌词", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.padding(top = 10.dp))
            }
            else -> LyricsList(l, accent)
        }
    }
}

@Composable
private fun LyricsList(lyrics: Lyrics, accent: Color) {
    val actions = LocalActions.current
    val position by actions.player.position.collectAsState()
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(position + 250) } }
    val listState = rememberLazyListState()
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUserScroll = System.currentTimeMillis()
                return Offset.Zero
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anchor = with(density) { (maxHeight * 0.3f).roundToPx() }
        LaunchedEffect(active) {
            if (active < 0) return@LaunchedEffect
            if (System.currentTimeMillis() - lastUserScroll < 3500) return@LaunchedEffect
            listState.animateScrollToItem(active, -anchor)
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                if (active >= 0 && lastUserScroll != 0L && System.currentTimeMillis() - lastUserScroll in 3500..4600 && !listState.isScrollInProgress) {
                    listState.animateScrollToItem(active, -anchor)
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.6f),
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
                val scale by animateFloatAsState(if (i == active) 1f else 0.94f, spring(dampingRatio = 0.75f, stiffness = 300f), label = "s")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = lyrics.synced) { actions.player.seekTo(line.timeMs) }
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
                        Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
