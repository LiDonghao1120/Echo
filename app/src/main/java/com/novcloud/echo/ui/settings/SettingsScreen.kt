package com.novcloud.echo.ui.settings

import com.novcloud.echo.ui.theme.EchoMotion

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import com.novcloud.echo.ui.components.LocalActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.novcloud.echo.BuildConfig
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.ThemeMode
import com.novcloud.echo.ui.components.ConfirmDialog
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.detail.CollapsingTopBar
import androidx.compose.ui.unit.sp
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val theme by container.prefs.theme.collectAsState(initial = ThemeMode.System)
    val byWord by container.prefs.lyricsByWord.collectAsState(initial = true)
    val minDur by container.prefs.minDurationSec.collectAsState(initial = 30)
    val tree by container.prefs.lyricsTree.collectAsState(initial = null)
    val keepOn by container.prefs.keepScreenOnLyrics.collectAsState(initial = true)
    val trees by container.prefs.scanTrees.collectAsState(initial = emptySet())
    val excluded by container.prefs.scanExcluded.collectAsState(initial = emptySet())
    val lib by container.library.library.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    val progress by container.library.progress.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val importFolder = com.novcloud.echo.ui.detail.rememberFolderImport()
    val tools = rememberDataTools()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { container.prefs.setLyricsTree(uri.toString()); container.lyrics.invalidate() }
        }
    }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val collapse by remember { derivedStateOf { (scroll.value / with(density) { 56.dp.toPx() }).coerceIn(0f, 1f) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(top = 56.dp, bottom = LocalBottomInset.current + 24.dp)) {
            PageTitle("设置")
            Group("外观") {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.System to "跟随系统", ThemeMode.Dark to "深色", ThemeMode.Light to "浅色").forEach { (m, l) ->
                        Chip(l, theme == m, Modifier.weight(1f)) { scope.launch { container.prefs.setTheme(m) } }
                    }
                }
            }
            Group("播放") {
                val fx by container.audioFx.settings.collectAsState()
                NavRow("音效", listOfNotNull(
                    if (fx.eqEnabled) com.novcloud.echo.playback.audio.EqPresets.label(fx.preset) else null,
                    when (fx.gainMode) {
                        com.novcloud.echo.playback.audio.GainMode.Track -> "音量平衡 · 单曲"
                        com.novcloud.echo.playback.audio.GainMode.Album -> "音量平衡 · 专辑"
                        else -> null
                    },
                ).joinToString(" · ").ifEmpty { null }) { actions.openEffects() }
            }
            Group("曲库") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Count(lib.songs.size, "首歌曲")
                    Count(lib.albums.size, "张专辑")
                    Count(lib.artists.size, "位歌手")
                }
                Rule()
                NavRow("扫描文件夹", listOfNotNull(
                    trees.size.takeIf { it > 0 }?.let { "已添加 $it" },
                    excluded.size.takeIf { it > 0 }?.let { "已排除 $it" },
                ).joinToString(" · ").ifEmpty { null }) { actions.openScanFolders() }
                Rule()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !scanning) { container.library.refresh() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("重新扫描", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (scanning) palette.muted else palette.signal)
                    progress?.let { Text("${it.done} / ${it.total}", style = EchoType.numeral, color = palette.muted) }
                }
                ScanBar(scanning, progress)
                Rule()
                Text("忽略短于", Modifier.padding(start = 16.dp, top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 60).forEach { s ->
                        Chip(if (s == 0) "不限" else "$s 秒", minDur == s, Modifier.weight(1f)) {
                            scope.launch { container.prefs.setMinDuration(s); container.library.rescan(false) }
                        }
                    }
                }
            }
            Group("歌手形象") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { importFolder() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("从文件夹批量导入", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text("Artist_Name.png · Artist_Name_poster.jpg", style = EchoType.label.copy(letterSpacing = 0.4.sp), color = palette.muted)
                    }
                    Chevron()
                }
            }
            Group("歌词") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { treePicker.launch(null) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("歌词文件夹", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(tree?.let { Uri.decode(it).substringAfterLast(':') }?.ifEmpty { "/" } ?: "未选择", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                    if (tree != null) Text(
                        "移除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(EchoShape.card).clickable { scope.launch { container.prefs.setLyricsTree(null); container.lyrics.invalidate() } }.padding(12.dp),
                    )
                }
                Rule()
                Text("歌词显示", Modifier.padding(start = 16.dp, top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "逐行", true to "逐字").forEach { (v, l) ->
                        Chip(l, byWord == v, Modifier.weight(1f)) { scope.launch { container.prefs.setLyricsByWord(v) } }
                    }
                }
                Rule()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { scope.launch { container.prefs.setKeepScreenOnLyrics(!keepOn) } }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示歌词时保持亮屏", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(keepOn, { v -> scope.launch { container.prefs.setKeepScreenOnLyrics(v) } })
                }
            }
            Group("数据") {
                NavRow("导出备份", null, tools.exportBackup)
                Rule()
                NavRow("导入备份", null, tools.importBackup)
                Rule()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { clearing = true }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("清除收听记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            Group("关于") {
                NavRow("导出诊断日志", null, tools.exportLog)
                Rule()
                NavRow("开源许可", null) { licenses = true }
            }
            Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                com.novcloud.echo.ui.components.EchoMark(Modifier.size(28.dp), color = palette.faint, core = palette.signal)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${context.getString(R.string.app_name)}  ${BuildConfig.VERSION_NAME}",
                    style = EchoType.label, color = palette.faint,
                )
            }
        }
        CollapsingTopBar("设置", collapse)
    }
    if (licenses) LicensesDialog { licenses = false }
    if (clearing) ConfirmDialog("清除全部收听记录？", "清除") { ok ->
        clearing = false
        if (ok) scope.launch { container.db.events().clear() }
    }
}

@Composable
internal fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
}

@Composable
private fun Count(value: Int, label: String) {
    Column {
        Text(value.toString(), style = EchoType.poster(28.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.muted)
    }
}

@Composable
internal fun Rule() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(palette.rule))
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = palette.faint)
}

@Composable
private fun NavRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Spacer(Modifier.height(2.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
        }
        Chevron()
    }
}

@Composable
internal fun ScanBar(scanning: Boolean, progress: com.novcloud.echo.data.ScanProgress?) {
    val p = palette
    androidx.compose.animation.AnimatedVisibility(
        scanning,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(EchoMotion.Standard)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(EchoMotion.Emphasized)),
    ) {
        if (progress == null || progress.total <= 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.signal, trackColor = p.rule)
        } else {
            val f by androidx.compose.animation.core.animateFloatAsState(progress.fraction, androidx.compose.animation.core.tween(EchoMotion.Standard), label = "scan")
            LinearProgressIndicator({ f }, Modifier.fillMaxWidth().height(2.dp), color = p.signal, trackColor = p.rule, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp), style = EchoType.labelBold, color = palette.signal)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(EchoShape.card).background(MaterialTheme.colorScheme.surfaceContainer)) {
        content()
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = palette
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) p.signal else MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.animation.core.tween(EchoMotion.Standard), label = "chip",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) p.onSignal else MaterialTheme.colorScheme.onSurface, androidx.compose.animation.core.tween(EchoMotion.Standard), label = "chipfg",
    )
    Box(
        modifier.clip(EchoShape.chip)
            .background(bg)
            .clickable(onClick = onClick).heightIn(min = 40.dp).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("Anton" to "licenses/Anton-OFL.txt", "Space Grotesk" to "licenses/SpaceGrotesk-OFL.txt", "pinyin-data" to "licenses/pinyin-data-MIT.txt").joinToString("\n\n") { (n, f) ->
            "$n\n" + runCatching { context.assets.open(f).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("好") } },
        title = { Text("开源许可") },
        text = {
            Text(
                text, Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall, color = palette.muted,
            )
        },
    )
}
