package com.novcloud.echo.ui.settings

import com.novcloud.echo.ui.theme.EchoMotion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.AudioFolder
import com.novcloud.echo.data.FolderRules
import com.novcloud.echo.data.SafPaths
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.SectionHeader
import com.novcloud.echo.ui.components.SolidButton
import com.novcloud.echo.ui.detail.CollapsingTopBar
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.launch

@Composable
fun ScanFoldersScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val repo = container.library
    val scope = rememberCoroutineScope()
    val trees by container.prefs.scanTrees.collectAsState(initial = emptySet())
    val included by container.prefs.scanIncluded.collectAsState(initial = emptySet())
    val excluded by container.prefs.scanExcluded.collectAsState(initial = emptySet())
    val lib by repo.library.collectAsState()
    val scanning by repo.scanning.collectAsState()
    val progress by repo.progress.collectAsState()
    var folders by remember { mutableStateOf<List<AudioFolder>?>(null) }
    LaunchedEffect(lib) { folders = repo.folders() }
    val rules = remember(included, excluded) { FolderRules(included, excluded) }
    val counts = remember(lib) { lib.folders.associate { it.path to it.songs.size } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch { repo.addTree(uri) }
    }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val collapse by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / with(density) { 56.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current + 24.dp)) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { PageTitle("扫描文件夹") }
                    IconButton(onClick = { if (!scanning) repo.refresh() }, Modifier.padding(bottom = 2.dp)) {
                        Icon(Icons.Rounded.Refresh, null, tint = if (scanning) palette.faint else palette.signal)
                    }
                }
            }
            item(key = "progress") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${lib.songs.size}", style = EchoType.numeral, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Text("首", style = MaterialTheme.typography.labelMedium, color = palette.muted)
                        Spacer(Modifier.weight(1f))
                        progress?.let { Text("${it.done} / ${it.total}", style = EchoType.numeral, color = palette.muted) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp)) { ScanBar(scanning, progress) }
                }
            }
            item(key = "trees_h") { SectionHeader("已添加", index = 1) }
            items(trees.sorted(), key = { "t_$it" }) { t ->
                val path = SafPaths.treePath(Uri.parse(t))
                val label = SafPaths.label(t)
                FolderRow(
                    name = label.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
                    parent = label.trimEnd('/').substringBeforeLast('/', "").ifEmpty { if (label == "/") "" else "/" },
                    count = counts.entries.filter { (k, _) -> if (path != null) FolderRules.under(k, path) else k.startsWith("$label/") || k == label }.sumOf { it.value },
                    trailing = {
                        IconButton(onClick = { scope.launch { repo.removeTree(t) } }) { Icon(Icons.Rounded.Close, null, tint = palette.muted) }
                    },
                )
            }
            item(key = "add") {
                SolidButton(
                    "添加文件夹", Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), icon = Icons.Rounded.Add,
                    primary = trees.isEmpty(),
                ) { picker.launch(null) }
            }
            item(key = "device_h") { SectionHeader("设备上的文件夹", index = 2) }
            val all = folders
            if (all != null) {
                items(all, key = { "f_${it.path}" }) { f ->
                    val on = rules.accepts(f.path, f.music > 0)
                    val display = f.path.removePrefix("/storage/emulated/0")
                    FolderRow(
                        name = display.substringAfterLast('/').ifEmpty { "/" },
                        parent = display.substringBeforeLast('/', "").ifEmpty { "/" },
                        count = if (on) counts[f.path] ?: 0 else f.count,
                        dim = !on,
                        leading = { SquareCheck(on) },
                        onClick = { scope.launch { repo.setFolder(f.path, !on) } },
                    )
                }
            }
        }
        CollapsingTopBar("扫描文件夹", collapse)
    }
}

@Composable
private fun FolderRow(
    name: String,
    parent: String,
    count: Int,
    dim: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val p = palette
    val fg by animateColorAsState(if (dim) p.faint else MaterialTheme.colorScheme.onSurface, tween(EchoMotion.Standard), label = "fg")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 20.dp, end = if (trailing != null) 8.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (parent.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(parent, style = MaterialTheme.typography.bodySmall, color = p.faint, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(count.toString(), style = EchoType.numeral, color = if (dim) p.faint else p.muted)
        if (trailing != null) { Spacer(Modifier.width(4.dp)); trailing() }
    }
}

@Composable
private fun SquareCheck(on: Boolean) {
    val p = palette
    val t by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "check")
    val stroke by animateColorAsState(if (on) p.signal else p.faint, tween(EchoMotion.Standard), label = "stroke")
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val w = 1.5.dp.toPx()
        drawRect(stroke, Offset(w / 2, w / 2), Size(s - w, s - w), style = Stroke(w))
        val inner = s * 0.46f * t
        if (inner > 0f) drawRect(p.signal, Offset((s - inner) / 2, (s - inner) / 2), Size(inner, inner))
    }
}
