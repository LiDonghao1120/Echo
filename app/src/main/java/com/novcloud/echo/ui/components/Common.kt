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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import coil.compose.AsyncImage
import com.novcloud.echo.data.ArtistImages
import com.novcloud.echo.data.ArtistLogoInfo
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.data.forName
import com.novcloud.echo.data.splitArtists
import com.novcloud.echo.domain.HomeData
import com.novcloud.echo.playback.PlayerConnection
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.LocalPalette
import com.novcloud.echo.ui.theme.displayStyle
import com.novcloud.echo.ui.theme.seededColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SongMenuContext(val playlistId: Long? = null, val nowPlaying: Boolean = false)

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

    private val _reselected = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Bottom bar tabs tapped while already showing; their screens scroll back to the top. */
    val reselected: kotlinx.coroutines.flow.SharedFlow<String> = _reselected

    fun reselectTab(route: String) {
        _reselected.tryEmit(route)
    }

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
        val lib = repo.library.value
        val target = (lib.artist(name) ?: splitArtists(name).firstNotNullOfOrNull { lib.artist(it) })?.name ?: name
        go("artist/${Uri.encode(target)}")
    }
    fun openMix(id: String) = go("mix/${Uri.encode(id)}")
    fun openPlaylist(id: Long) = go("playlist/$id")
    fun openGenre(name: String) = go("genre/${Uri.encode(name)}")
    fun openFolder(path: String) = go("folder/${Uri.encode(path)}")
    fun openList(kind: String) = go("list/${Uri.encode(kind)}")
    fun openSettings() = go("settings")
    fun openScanFolders() = go("settings/folders")
    fun openEffects() = go("settings/effects")
    fun openArtLibrary() = go("settings/art-library")
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
    slot: String? = null,
) {
    val base = remember(seed) { seededColor(seed) }
    Box(modifier.clip(shape).background(lerp(base, Ink, 0.62f)), contentAlignment = Alignment.Center) {
        EchoMark(Modifier.size(iconSize), color = base.copy(alpha = 0.75f), core = base)
        if (model != null) SwapImage(model, Modifier.fillMaxSize(), contentScale, slot = slot)
    }
}

/**
 * An image whose model can change in place (the now-playing cover on skip): the picture already on screen stays
 * until the new one has loaded and faded in over it, so the placeholder never flashes between two covers.
 * When the new model has no picture, the old one is dropped and whatever is behind this shows.
 */
@Composable
fun SwapImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    slot: String? = null,
) {
    var held by remember { mutableStateOf(slot?.let { SwapMemory.held[it] }) }
    var settled by remember { mutableStateOf<Any?>(null) }
    val retries = remember { androidx.compose.runtime.mutableStateMapOf<Any, Int>() }
    val current by androidx.compose.runtime.rememberUpdatedState(model)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val motion = LocalMotion.current
    if (slot != null) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            com.novcloud.echo.diag.DiagLog.i("EchoArt", "$slot enter model=${model.artTag()} held=${held?.model.artTag()}")
            onDispose { com.novcloud.echo.diag.DiagLog.i("EchoArt", "$slot leave") }
        }
        androidx.compose.runtime.LaunchedEffect(model) { SwapMemory.since[slot] = android.os.SystemClock.uptimeMillis() }
    }
    if (model == null) return
    Box(modifier.then(if (slot != null) Modifier.onSizeChanged { SwapMemory.size[slot] = it } else Modifier)) {
        val base = held
        if (base != null && settled != model) {
            androidx.compose.foundation.Image(
                base.bitmap, contentDescription = null, contentScale = contentScale, alpha = alpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
        androidx.compose.runtime.key(model, retries[model] ?: 0) {
            val fade = remember { androidx.compose.animation.core.Animatable(if (base == null || base.model == model || !motion) 1f else 0f) }
            val request = remember(model) { coil.request.ImageRequest.Builder(context).data(model).crossfade(false).build() }
            AsyncImage(
                model = request, contentDescription = null, contentScale = contentScale, alpha = alpha,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = fade.value },
                onSuccess = { ok ->
                    val m = model
                    if (slot != null) com.novcloud.echo.diag.DiagLog.i("EchoArt", "$slot loaded ${m.artTag()} from ${ok.result.dataSource} in ${android.os.SystemClock.uptimeMillis() - (SwapMemory.since[slot] ?: 0)}ms held=${held?.model.artTag()}")
                    val bmp = (ok.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap()
                    scope.launch {
                        if (fade.value < 1f) fade.animateTo(1f, androidx.compose.animation.core.tween(SWAP_FADE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        kotlinx.coroutines.delay(SWAP_HOLD_MS)
                        if (m == current) {
                            settled = m
                            if (bmp != null) {
                                val h = HeldImage(m, bmp)
                                held = h
                                if (slot != null) SwapMemory.held[slot] = h
                            }
                        }
                    }
                },
                onError = { err ->
                    val m = model
                    if (slot != null) com.novcloud.echo.diag.DiagLog.w("EchoArt", "$slot cover failed (try ${retries[m] ?: 0}): ${err.result.throwable.javaClass.simpleName} ${err.result.throwable.message}")
                    scope.launch {
                        if ((retries[m] ?: 0) < SWAP_RETRIES) {
                            kotlinx.coroutines.delay(SWAP_RETRY_MS)
                            if (m == current) retries[m] = (retries[m] ?: 0) + 1
                        } else if (m == current) {
                            settled = m
                            held = null
                            if (slot != null) SwapMemory.held.remove(slot)
                        }
                    }
                },
            )
        }
    }
    androidx.compose.runtime.SideEffect {
        if (retries.size > 4) retries.keys.filter { it != model }.forEach { retries.remove(it) }
    }
}

private fun Any?.artTag(): String = when (this) {
    null -> "-"
    is com.novcloud.echo.data.ArtRef -> if (albumId >= 0) "a$albumId" else "s$songId"
    else -> javaClass.simpleName
}

class HeldImage(val model: Any, val bitmap: androidx.compose.ui.graphics.ImageBitmap)

/** The picture each [SwapImage] slot last settled on and how big it is drawn, kept outside composition so a re-created slot starts from the picture already on screen. */
object SwapMemory {
    val held = HashMap<String, HeldImage>()
    val since = HashMap<String, Long>()
    val size = HashMap<String, androidx.compose.ui.unit.IntSize>()
}

private const val SWAP_FADE_MS = 320
private const val SWAP_HOLD_MS = 60L
private const val SWAP_RETRIES = 2
private const val SWAP_RETRY_MS = 150L
const val NOW_PLAYING_SLOT = "now_playing"
const val MINI_PLAYER_SLOT = "mini_player"
private val PREFETCH_SLOTS = listOf(NOW_PLAYING_SLOT)

/**
 * Warms the image cache with the covers of the songs before and after the current one, in play order, at the size
 * the now-playing cover is drawn, so a skip finds its cover already decoded instead of reading it from the file.
 */
@Composable
fun PrefetchNeighbourArt(state: com.novcloud.echo.playback.PlayerState) {
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val pos = state.queue.indexOfFirst { it.index == state.currentIndex }
    val wrap = state.repeat == androidx.media3.common.Player.REPEAT_MODE_ALL
    val neighbours = remember(state.queue, pos, wrap) {
        if (pos < 0) emptyList() else listOf(pos + 1, pos - 1, pos + 2).mapNotNull { i ->
            val n = state.queue.size
            val j = if (wrap && n > 0) ((i % n) + n) % n else i
            state.queue.getOrNull(j)?.song?.art
        }.distinct()
    }
    LaunchedEffect(neighbours, px) {
        val loader = coil.Coil.imageLoader(context)
        neighbours.forEach { art ->
            for (slot in PREFETCH_SLOTS) {
                val sz = SwapMemory.size[slot]?.takeIf { it.width > 0 && it.height > 0 }
                val req = coil.request.ImageRequest.Builder(context).data(art)
                    .scale(coil.size.Scale.FILL)
                    .precision(coil.size.Precision.INEXACT)
                if (sz != null) req.size(sz.width, sz.height) else if (slot == NOW_PLAYING_SLOT) req.size(px) else continue
                loader.enqueue(req.build())
            }
        }
    }
}

@Composable
fun artistModel(name: String, lib: Library, custom: Map<String, String>, playCounts: Map<Long, Int>): Any? {
    val artist = lib.artist(name) ?: return null
    return remember(artist, custom.forName(name), playCounts.size) { ArtistImages.source.image(artist, custom, playCounts) }
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
    return logos.forName(name)
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
    Text(text.uppercase(), modifier, style = EchoType.labelBold, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        Text(
            title, Modifier.widthIn(max = 260.dp), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
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
    format: (Long) -> String = rememberNumberFormat(),
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
        Text(text, style = MaterialTheme.typography.titleMedium, color = p.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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

@Composable
fun formatLong(ms: Long): String = LocalContext.current.resources.longDuration(ms)
