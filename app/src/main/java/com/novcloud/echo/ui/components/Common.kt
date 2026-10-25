package com.novcloud.echo.ui.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.novcloud.echo.data.ArtistImages
import com.novcloud.echo.data.splitArtists
import com.novcloud.echo.data.ArtistLogoInfo
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.domain.HomeData
import com.novcloud.echo.playback.PlayerConnection
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.LocalPalette
import com.novcloud.echo.ui.theme.displayStyle
import com.novcloud.echo.ui.theme.seededColor
import kotlinx.coroutines.flow.StateFlow

data class SongMenuContext(val playlistId: Long? = null)

enum class NavMotion { Push, Tab, Instant }

class AppActions(
    val nav: NavController,
    val player: PlayerConnection,
    val repo: LibraryRepository,
    val home: StateFlow<HomeData>,
    val openSongMenu: (Song, SongMenuContext) -> Unit,
    val expandPlayer: () -> Unit,
    val collapsePlayer: () -> Unit,
    private val isPlayerOpen: () -> Boolean = { false },
    private val scope: CoroutineScope? = null,
) {
    var motion by mutableStateOf(NavMotion.Push)

    private fun go(route: String) {
        if (!isPlayerOpen()) {
            motion = NavMotion.Push
            nav.navigate(route) { launchSingleTop = true }
            return
        }
        if (nav.currentBackStackEntry?.concreteRoute() != route) {
            motion = NavMotion.Instant
            nav.navigate(route) { launchSingleTop = true }
        }
        if (scope == null) collapsePlayer() else scope.launch {
            delay(48)
            collapsePlayer()
        }
    }

    fun switchTab(route: String) {
        motion = NavMotion.Tab
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun popToTab(route: String) {
        motion = NavMotion.Tab
        nav.popBackStack(route, inclusive = false)
    }

    fun openAlbum(id: Long) = go("album/$id")
    fun openArtist(name: String) {
        val known = repo.library.value.artistByName
        val target = if (name in known) name else splitArtists(name).firstOrNull { it in known } ?: name
        go("artist/${Uri.encode(target)}")
    }
    fun openMix(id: String) = go("mix/${Uri.encode(id)}")
    fun openPlaylist(id: Long) = go("playlist/$id")
    fun openGenre(name: String) = go("genre/${Uri.encode(name)}")
    fun openFolder(path: String) = go("folder/${Uri.encode(path)}")
    fun openList(kind: String) = go("list/${Uri.encode(kind)}")
    fun openSettings() = go("settings")
    fun openScanFolders() = go("settings/folders")
    fun back() {
        val entry = nav.currentBackStackEntry ?: return
        if (nav.previousBackStackEntry == null) return
        if (!entry.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        motion = NavMotion.Push
        nav.popBackStack()
    }
}

private val routeArg = Regex("\\{(\\w+)\\}")

fun NavBackStackEntry.concreteRoute(): String? = destination.route?.let { r ->
    routeArg.replace(r) { m ->
        when (val v = arguments?.get(m.groupValues[1])) {
            is String -> Uri.encode(v)
            null -> ""
            else -> v.toString()
        }
    }
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

@Composable
fun EchoMark(modifier: Modifier = Modifier, color: Color = LocalContentColor.current, core: Color = color) {
    Canvas(modifier) {
        val u = size.minDimension / 45.7f
        val o = Offset((size.width - 45.7f * u) / 2, (size.height - 45.7f * u) / 2)
        fun ring(l: Float, t: Float, r: Float, b: Float, w: Float, c: Color) =
            drawRect(c, o + Offset(l * u, t * u), Size((r - l) * u, (b - t) * u), style = Stroke(w * u))
        ring(0.65f, 0.65f, 45.05f, 45.05f, 1.3f, color.copy(alpha = color.alpha * 0.66f))
        ring(5.25f, 12.65f, 33.05f, 40.45f, 2.3f, color)
        drawRect(core, o + Offset(9.2f * u, 24f * u), Size(12.5f * u, 12.5f * u))
    }
}

@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = EchoShape.cover,
    seed: String = "",
    iconSize: Dp = 28.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val base = remember(seed) { seededColor(seed) }
    Box(modifier.clip(shape).background(lerp(base, Ink, 0.62f)), contentAlignment = Alignment.Center) {
        EchoMark(Modifier.size(iconSize), color = base.copy(alpha = 0.75f), core = base)
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun artistModel(name: String, lib: Library, custom: Map<String, String>, playCounts: Map<Long, Int>): Any? {
    val artist = lib.artistByName[name] ?: return null
    return remember(artist, custom[name], playCounts.size) { ArtistImages.source.image(artist, custom, playCounts) }
}

@Composable
fun rememberArtistPoster(name: String): Any? {
    val a = LocalActions.current
    val lib by a.repo.library.collectAsState()
    val custom by a.repo.artistImages.collectAsState()
    val home by a.home.collectAsState()
    return artistModel(name, lib, custom, home.playCounts)
}

@Composable
fun rememberArtistLogo(name: String): ArtistLogoInfo? {
    val logos by LocalActions.current.repo.artistLogos.collectAsState()
    return logos[name]
}

@Composable
fun ArtistThumb(model: Any?, name: String, modifier: Modifier = Modifier, shape: Shape = EchoShape.cover) {
    val base = remember(name) { seededColor(name) }
    Box(modifier.clip(shape).background(lerp(base, Ink, 0.5f)), contentAlignment = Alignment.Center) {
        val initial = displayArtist(name).trim().take(1).uppercase()
        Text(initial, style = displayStyle(initial, 26.sp), color = base)
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ArtistAvatar(model: Any?, name: String, modifier: Modifier = Modifier) = ArtistThumb(model, name, modifier)

@Composable
fun Collage(models: List<Any?>, modifier: Modifier = Modifier, shape: Shape = EchoShape.cover, seed: String = "") {
    Box(modifier.clip(shape)) {
        if (models.size < 4) Artwork(models.firstOrNull(), Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, seed)
        else Column(Modifier.fillMaxSize()) {
            for (r in 0..1) Row(Modifier.weight(1f)) {
                for (c in 0..1) Artwork(models[r * 2 + c], Modifier.weight(1f).fillMaxSize(), androidx.compose.ui.graphics.RectangleShape, seed)
            }
        }
    }
}

fun Modifier.bouncyClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    echoClick(onLongClick = onLongClick, onClick = onClick)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.signal) {
    Text(text.uppercase(), modifier, style = EchoType.labelBold, color = color, maxLines = 1)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, index: Int? = null, onMore: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier)
            .padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("%02d".format(index), style = EchoType.labelBold, color = p.signal)
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(p.rule))
        if (onMore != null) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = p.muted)
        }
    }
}

@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean, color: Color = LocalPalette.current.signal) =
    EchoMeter(playing, modifier, color)

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
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = { actions.openSongMenu(song, menuContext) }, onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                if (current) EchoMeter(playing)
                else Text("%02d".format(index), style = EchoType.numeral(13.sp), color = p.faint)
            }
        }
        if (showArt) {
            Box {
                Artwork(song.art, Modifier.size(46.dp), EchoShape.cover, seed = song.album, iconSize = 20.dp)
                if (current && index == null) Box(
                    Modifier.size(46.dp).clip(EchoShape.cover).background(Ink.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) { EchoMeter(playing, color = p.signal) }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) p.signal else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle ?: "${displayArtist(song.artist)} · ${displayAlbum(song.album)}",
                style = MaterialTheme.typography.bodySmall,
                color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { actions.openSongMenu(song, menuContext) }) {
            Icon(Icons.Rounded.MoreVert, null, tint = p.faint)
        }
    }
}

@Composable
fun AnimatedNumber(
    value: Long,
    modifier: Modifier = Modifier,
    format: (Long) -> String = { it.toString() },
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = LocalContentColor.current,
) {
    val motion = com.novcloud.echo.ui.theme.LocalMotion.current
    val anim = remember { Animatable(if (motion) 0f else value.toFloat()) }
    LaunchedEffect(value) { if (motion) anim.animateTo(value.toFloat(), tween(1100, easing = FastOutSlowInEasing)) else anim.snapTo(value.toFloat()) }
    Text(format(anim.value.toLong()), modifier, style = style, color = color, maxLines = 1)
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EchoMark(Modifier.size(72.dp), color = p.faint, core = p.signal)
        Spacer(Modifier.height(20.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = p.muted)
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

@Composable
fun SolidButton(label: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, primary: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg = if (primary) p.signal else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary) p.onSignal else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.echoClick(corner = 8.dp, onClick = onClick).clip(EchoShape.card).background(bg).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = fg)
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
