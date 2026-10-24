package com.novcloud.echo.ui.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.novcloud.echo.data.ArtistImages
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.playback.PlayerConnection
import com.novcloud.echo.ui.theme.seededGradient

data class SongMenuContext(val playlistId: Long? = null)

class AppActions(
    val nav: NavController,
    val player: PlayerConnection,
    val repo: LibraryRepository,
    val openSongMenu: (Song, SongMenuContext) -> Unit,
    val expandPlayer: () -> Unit,
    val collapsePlayer: () -> Unit,
) {
    private fun go(route: String) {
        collapsePlayer()
        nav.navigate(route) { launchSingleTop = true }
    }
    fun openAlbum(id: Long) = go("album/$id")
    fun openArtist(name: String) = go("artist/${Uri.encode(name)}")
    fun openMix(id: String) = go("mix/${Uri.encode(id)}")
    fun openPlaylist(id: Long) = go("playlist/$id")
    fun openGenre(name: String) = go("genre/${Uri.encode(name)}")
    fun openFolder(path: String) = go("folder/${Uri.encode(path)}")
    fun openList(kind: String) = go("list/${Uri.encode(kind)}")
    fun openSettings() = go("settings")
    fun back() = nav.popBackStack()
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    seed: String = "",
    iconSize: Dp = 28.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = remember(seed) { seededGradient(seed) }
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors[0].copy(alpha = 0.55f), colors[1].copy(alpha = 0.35f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(iconSize), tint = Color.White.copy(alpha = 0.7f))
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun artistModel(name: String, lib: Library, custom: Map<String, String>, playCounts: Map<Long, Int>): Any? {
    val artist = lib.artistByName[name] ?: return null
    return remember(artist, custom[name], playCounts.size) { ArtistImages.source.image(artist, custom, playCounts) }
}

@Composable
fun ArtistAvatar(model: Any?, name: String, modifier: Modifier = Modifier) {
    val colors = remember(name) { seededGradient(name) }
    Box(modifier.clip(CircleShape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun Collage(models: List<Any?>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), seed: String = "") {
    Box(modifier.clip(shape)) {
        if (models.size < 4) Artwork(models.firstOrNull(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), seed)
        else Column(Modifier.fillMaxSize()) {
            for (r in 0..1) Row(Modifier.weight(1f)) {
                for (c in 0..1) Artwork(models[r * 2 + c], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp), seed)
            }
        }
    }
}

fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

fun Modifier.bouncyClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    pressScale(interaction).combinedClickable(interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onMore != null) Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 360).map { delay ->
        t.animateFloat(
            0.25f, 1f,
            infiniteRepeatable(tween(520, delay, FastOutSlowInEasing), RepeatMode.Reverse), label = "b$delay",
        )
    }
    Canvas(modifier.size(16.dp)) {
        val w = size.width / 5
        phases.forEachIndexed { i, p ->
            val h = size.height * (if (playing) p.value else 0.3f + 0.2f * i % 2)
            drawRoundRect(color, Offset(i * w * 2f, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    current: Boolean = false,
    playing: Boolean = false,
    subtitle: String? = null,
    menuContext: SongMenuContext = SongMenuContext(),
) {
    val actions = LocalActions.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = { actions.openSongMenu(song, menuContext) }, onClick = onClick)
            .padding(start = 20.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (current) PlayingBars(playing = playing)
                else Text(
                    "$index", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showArt) {
            Box {
                Artwork(song.art, Modifier.size(48.dp), RoundedCornerShape(6.dp), seed = song.album, iconSize = 20.dp)
                if (current && index == null) Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(playing = playing, color = Color.White) }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle ?: "${displayArtist(song.artist)} · ${displayAlbum(song.album)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { actions.openSongMenu(song, menuContext) }) {
            Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AnimatedNumber(value: Long, modifier: Modifier = Modifier, format: (Long) -> String = { it.toString() }, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineMedium, color: Color = LocalContentColor.current) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { anim.animateTo(value.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(format(anim.value.toLong()), modifier, style = style, color = color)
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatLong(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分钟" else "$m 分钟"
}
