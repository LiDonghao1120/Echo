package com.novcloud.echo.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
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
import com.novcloud.echo.ui.settings.SettingsScreen
import com.novcloud.echo.ui.stats.StatsScreen
import kotlinx.coroutines.flow.StateFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Rounded.Home),
    Tab("library", "曲库", Icons.Rounded.LibraryMusic),
    Tab("search", "搜索", Icons.Rounded.Search),
    Tab("stats", "统计", Icons.Rounded.BarChart),
)
private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
fun AppRoot(openPlayer: StateFlow<Long>) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val nav = rememberNavController()
    val playerState by container.player.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<Song, SongMenuContext>?>(null) }
    val openTick by openPlayer.collectAsState()
    LaunchedEffect(openTick) { if (openTick > 0 && container.player.state.value.song != null) expanded = true }

    val actions = remember(nav) {
        AppActions(nav, container.player, container.library, { s, c -> menuFor = s to c }, { expanded = true }, { expanded = false })
    }
    val hasSong = playerState.song != null
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = navInset + 60.dp + if (hasSong) 72.dp else 0.dp

    CompositionLocalProvider(LocalActions provides actions, LocalBottomInset provides bottomInset) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            val contentScale by animateFloatAsState(if (expanded) 0.94f else 1f, spring(dampingRatio = 0.9f, stiffness = 300f), label = "behind")
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = contentScale; scaleY = contentScale }) {
                    NavHost(
                        nav, startDestination = "home",
                        enterTransition = { enter() }, exitTransition = { exit() },
                        popEnterTransition = { popEnter() }, popExitTransition = { popExit() },
                    ) {
                        composable("home") { HomeScreen() }
                        composable("library") { LibraryScreen() }
                        composable("search") { SearchScreen() }
                        composable("stats") { StatsScreen() }
                        composable("settings") { SettingsScreen() }
                        composable("album/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                            AlbumScreen(it.arguments?.getLong("id") ?: -1)
                        }
                        composable("artist/{name}") { ArtistScreen(arg(it, "name")) }
                        composable("mix/{id}") { SongListScreen(ListSource.MixSrc(arg(it, "id"))) }
                        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                            SongListScreen(ListSource.PlaylistSrc(it.arguments?.getLong("id") ?: -1))
                        }
                        composable("genre/{name}") { SongListScreen(ListSource.GenreSrc(arg(it, "name"))) }
                        composable("folder/{path}") { SongListScreen(ListSource.FolderSrc(arg(it, "path"))) }
                        composable("list/{kind}") { SongListScreen(ListSource.Kind(arg(it, "kind"))) }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = hasSong && !expanded,
                        enter = fadeIn(tween(250)) + slideInVertically { it / 2 },
                        exit = fadeOut(tween(200)),
                    ) {
                        MiniPlayer(this, onExpand = { expanded = true }, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    BottomBar(nav)
                }
                AnimatedVisibility(
                    visible = expanded && hasSong,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250)),
                ) {
                    NowPlayingScreen(this, onCollapse = { expanded = false })
                }
            }
        }
        menuFor?.let { (s, c) -> SongMenuSheet(s, c) { menuFor = null } }
    }
}

private fun arg(entry: NavBackStackEntry, key: String) = Uri.decode(entry.arguments?.getString(key).orEmpty())

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (isTabSwitch()) fadeIn(tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220))
    else slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(260))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (isTabSwitch()) fadeOut(tween(160))
    else slideOutHorizontally(tween(340)) { -it / 8 } + fadeOut(tween(260))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (isTabSwitch()) fadeIn(tween(220))
    else slideInHorizontally(tween(340)) { -it / 8 } + fadeIn(tween(260))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (isTabSwitch()) fadeOut(tween(160))
    else slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(220)) + scaleOut(targetScale = 0.98f)

@Composable
private fun BottomBar(nav: androidx.navigation.NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    var lastTab by rememberSaveable { mutableStateOf("home") }
    if (current != null && current in tabRoutes && current != lastTab) lastTab = current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
            tabs.forEach { t ->
                val selected = lastTab == t.route
                val tint by androidx.compose.animation.animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tab",
                )
                val s by animateFloatAsState(if (selected) 1.08f else 1f, spring(dampingRatio = 0.5f), label = "tabs")
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                        if (current == t.route) return@clickable
                        nav.navigate(t.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(t.icon, null, Modifier.size(26.dp).graphicsLayer { scaleX = s; scaleY = s }, tint = tint)
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

