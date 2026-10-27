package com.novcloud.echo.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.R
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.playback.QueueEntry
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.LocalActions

@Composable
fun QueueView(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val state by actions.player.state.collectAsState()
    val pos = state.queue.indexOfFirst { it.index == state.currentIndex }.coerceAtLeast(0)
    val upcoming = state.queue.drop(pos + 1)
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) { listState.scrollToItem(0) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_up_next), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            if (upcoming.isNotEmpty()) TextButton(onClick = { actions.player.clearQueue() }) {
                Text(stringResource(R.string.action_clear), color = Color.White.copy(alpha = 0.7f))
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.weight(1f)) {
            items(upcoming, key = { "${it.index}_${it.song.id}" }) { e ->
                QueueRow(e, onTop = { actions.player.moveInQueue(e.index, nextSlot(state.currentIndex, e.index)) })
            }
        }
    }
}

private fun nextSlot(current: Int, from: Int) = if (from > current) current + 1 else current

@Composable
private fun QueueRow(e: QueueEntry, onTop: () -> Unit) {
    val actions = LocalActions.current
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) { actions.player.removeAt(e.index); true } else false
    })
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val c by animateColorAsState(if (dismiss.targetValue == SwipeToDismissBoxValue.Settled) Color.Transparent else Color(0xFFE5484D), label = "bg")
            Box(Modifier.fillMaxSize().background(c).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.Delete, null, tint = Color.White)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { actions.player.playAt(e.index) }.padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(e.song.art, Modifier.size(44.dp), RoundedCornerShape(6.dp), e.song.album, 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.song.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(e.song.artist), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onTop) { Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Color.White.copy(alpha = 0.6f)) }
        }
    }
}

