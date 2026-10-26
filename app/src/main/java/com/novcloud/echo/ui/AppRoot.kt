package com.novcloud.echo.ui

import com.novcloud.echo.ui.theme.EchoMotion

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.LocalMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.compose.runtime.rememberCoroutineScope
import com.novcloud.echo.ui.components.NavMotion
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.Song
import com.novcloud.echo.ui.components.AppActions
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SongMenuContext
import com.novcloud.echo.ui.components.SongMenuSheet
import com.novcloud.echo.ui.detail.AlbumScreen
import com.novcloud.echo.ui.detail.ArtistScreen
import com.novcloud.echo.ui.detail.ListSource
import com.novcloud.echo.ui.detail.SongListScreen
import com.novcloud.echo.ui.home.HomeScreen
import com.novcloud.echo.ui.library.LibraryScreen
import com.novcloud.echo.ui.player.MiniPlayer
import com.novcloud.echo.ui.player.NowPlayingScreen
import com.novcloud.echo.ui.search.SearchScreen
import com.novcloud.echo.ui.settings.ScanFoldersScreen
import com.novcloud.echo.ui.settings.SettingsScreen
import com.novcloud.echo.ui.stats.StatsScreen
import kotlinx.coroutines.flow.StateFlow

private data class Tab(val route: String, val label: String)

private val tabs = listOf(Tab("home", "首页"), Tab("library", "曲库"), Tab("search", "搜索"), Tab("stats", "统计"))
private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
fun AppRoot(
    openPlayer: StateFlow<Long>,
    startRoute: String = "home",
    startExpanded: Boolean = false,
    startPane: com.novcloud.echo.ui.player.PlayerPane = com.novcloud.echo.ui.player.PlayerPane.Art,
) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val nav = rememberNavController()
    val playerState by container.player.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    var menuFor by remember { mutableStateOf<Pair<Song, SongMenuContext>?>(null) }
    val openTick by openPlayer.collectAsState()
    LaunchedEffect(Unit) { if (startRoute != "home") nav.navigate(startRoute) }
    LaunchedEffect(openTick) { if (openTick > 0 && container.player.state.value.song != null) expanded = true }

    val scope = rememberCoroutineScope()
    val actions = remember(nav) {
        AppActions(
            nav, container.player, container.library, container.home, { s, c -> menuFor = s to c }, { expanded = true }, { expanded = false },
            isPlayerOpen = { expanded }, scope = scope,
        )
    }
    val hasSong = playerState.song != null
    val entry by nav.currentBackStackEntryAsState()
    val barVisible = entry?.destination?.route.let { it == null || it in tabRoutes }
    LaunchedEffect(entry) {
        kotlinx.coroutines.delay(EchoMotion.Emphasized.toLong())
        actions.motion = com.novcloud.echo.ui.components.NavMotion.Push
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navInset + (if (barVisible) 58.dp else 0.dp) + if (hasSong) 70.dp else 0.dp

    CompositionLocalProvider(LocalActions provides actions, LocalBottomInset provides bottomInset) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Black)) {
            val contentScale by animateFloatAsState(if (expanded) 0.94f else 1f, spring(dampingRatio = 0.9f, stiffness = 300f), label = "behind")
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = contentScale; scaleY = contentScale }) {
                    NavHost(
                        nav, startDestination = "home",
                        enterTransition = { enter(actions.motion) }, exitTransition = { exit(actions.motion) },
                        popEnterTransition = { EnterTransition.None }, popExitTransition = { popExit(actions.motion) },
                    ) {
                        screen("home") { HomeScreen() }
                        screen("library") { LibraryScreen() }
                        screen("search") { SearchScreen() }
                        screen("stats") { StatsScreen() }
                        screen("settings") { SettingsScreen() }
                        screen("settings/folders") { ScanFoldersScreen() }
                        screen("album/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                            AlbumScreen(it.arguments?.getLong("id") ?: -1)
                        }
                        screen("artist/{name}") { ArtistScreen(arg(it, "name")) }
                        screen("mix/{id}") { SongListScreen(ListSource.MixSrc(arg(it, "id"))) }
                        screen("playlist/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                            SongListScreen(ListSource.PlaylistSrc(it.arguments?.getLong("id") ?: -1))
                        }
                        screen("genre/{name}") { SongListScreen(ListSource.GenreSrc(arg(it, "name"))) }
                        screen("folder/{path}") { SongListScreen(ListSource.FolderSrc(arg(it, "path"))) }
                        screen("list/{kind}") { SongListScreen(ListSource.Kind(arg(it, "kind"))) }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = hasSong && !expanded,
                        enter = fadeIn(tween(EchoMotion.Standard)) + slideInVertically { it / 2 },
                        exit = fadeOut(tween(EchoMotion.Standard)),
                    ) {
                        val surface = MaterialTheme.colorScheme.surface
                        val veil by animateFloatAsState(if (barVisible) 1f else 0f, tween(EchoMotion.Medium), label = "veil")
                        Box(
                            Modifier.fillMaxWidth().drawBehind {
                                if (veil > 0f) {
                                    val top = 30.dp.toPx()
                                    drawRect(
                                        Brush.verticalGradient(
                                            0f to surface.copy(alpha = 0f),
                                            0.45f to surface.copy(alpha = 0.55f * veil),
                                            1f to surface.copy(alpha = veil),
                                            startY = top, endY = size.height,
                                        ),
                                        topLeft = Offset(0f, top),
                                        size = Size(size.width, size.height - top),
                                    )
                                }
                            },
                        ) {
                            MiniPlayer(this@AnimatedVisibility, onExpand = { expanded = true }, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                    AnimatedVisibility(
                        visible = barVisible,
                        enter = expandVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(EchoMotion.Standard, 80)),
                        exit = shrinkVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(EchoMotion.Quick)),
                    ) { BottomBar(nav, actions) }
                    AnimatedVisibility(
                        visible = !barVisible,
                        enter = expandVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing)),
                        exit = shrinkVertically(tween(EchoMotion.Medium, easing = FastOutSlowInEasing)),
                    ) { Spacer(Modifier.fillMaxWidth().navigationBarsPadding()) }
                }
                AnimatedVisibility(
                    visible = expanded && hasSong,
                    enter = fadeIn(tween(EchoMotion.Standard)),
                    exit = fadeOut(tween(EchoMotion.Standard)),
                ) {
                    NowPlayingScreen(this, onCollapse = { expanded = false }, startPane = startPane)
                }
            }
        }
        menuFor?.let { (s, c) ->
            if (expanded) EchoTheme(dark = true, motion = LocalMotion.current) { SongMenuSheet(s, c) { menuFor = null } }
            else SongMenuSheet(s, c) { menuFor = null }
        }
    }
}

private fun arg(entry: NavBackStackEntry, key: String) = Uri.decode(entry.arguments?.getString(key).orEmpty())

private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content(entry) }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(motion: NavMotion) =
    motion == NavMotion.Tab || initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(motion: NavMotion): EnterTransition = when {
    motion == NavMotion.Instant -> EnterTransition.None
    isTabSwitch(motion) -> fadeIn(tween(EchoMotion.Standard, 60)) + slideInVertically(tween(EchoMotion.Emphasized, 40, FastOutSlowInEasing)) { it / 24 }
    else -> slideInHorizontally(tween(EchoMotion.Emphasized, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(EchoMotion.Standard))
}

private fun exit(motion: NavMotion): ExitTransition =
    if (motion == NavMotion.Instant) ExitTransition.None else fadeOut(tween(durationMillis = 1, delayMillis = EchoMotion.Emphasized))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(motion: NavMotion): ExitTransition = when {
    motion == NavMotion.Instant -> ExitTransition.None
    isTabSwitch(motion) -> fadeOut(tween(EchoMotion.Standard))
    else -> slideOutHorizontally(tween(EchoMotion.Medium, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(EchoMotion.Standard))
}

@Composable
private fun BottomBar(nav: androidx.navigation.NavHostController, actions: AppActions) {
    val stack by nav.currentBackStack.collectAsState()
    val current = stack.lastOrNull()?.destination?.route
    val selectedRoute = stack.lastOrNull { it.destination.route in tabRoutes }?.destination?.route ?: tabs.first().route
    val p = palette
    val selectedIndex = tabs.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().height(57.dp)) {
            val slot = maxWidth / tabs.size
            val x by animateDpAsState(slot * selectedIndex, spring(dampingRatio = 0.62f, stiffness = 420f), label = "tabx")
            Box(Modifier.offset(x = x + slot / 2 - 9.dp, y = 0.dp).size(18.dp, 3.dp).background(p.signal))
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, t ->
                    val selected = i == selectedIndex
                    val tint by androidx.compose.animation.animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onSurface else p.faint, tween(EchoMotion.Standard), label = "tab",
                    )
                    val lift by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "lift")
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                            when {
                                current == t.route -> Unit
                                selectedRoute == t.route -> actions.popToTab(t.route)
                                else -> actions.switchTab(t.route)
                            }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            t.label,
                            Modifier.graphicsLayer { translationY = -lift * 2.dp.toPx(); scaleX = 1f + lift * 0.06f; scaleY = 1f + lift * 0.06f },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium),
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}
