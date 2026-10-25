package com.novcloud.echo.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
    val scope = rememberCoroutineScope()
    val theme by container.prefs.theme.collectAsState(initial = ThemeMode.System)
    val minDur by container.prefs.minDurationSec.collectAsState(initial = 30)
    val tree by container.prefs.lyricsTree.collectAsState(initial = null)
    val keepOn by container.prefs.keepScreenOnLyrics.collectAsState(initial = true)
    val lib by container.library.library.collectAsState()
    val scanning by container.library.scanning.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val importFolder = com.novcloud.echo.ui.detail.rememberFolderImport()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { container.prefs.setLyricsTree(uri.toString()); container.lyrics.invalidate() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 56.dp, bottom = LocalBottomInset.current + 24.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            Group("外观") {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.System to "跟随系统", ThemeMode.Dark to "深色", ThemeMode.Light to "浅色").forEach { (m, l) ->
                        Chip(l, theme == m, Modifier.weight(1f)) { scope.launch { container.prefs.setTheme(m) } }
                    }
                }
            }
            Group("曲库") {
                Item("歌曲", "${lib.songs.size} 首 · ${lib.albums.size} 张专辑 · ${lib.artists.size} 位歌手")
                Row(Modifier.fillMaxWidth().clickable(enabled = !scanning) { container.library.refresh() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("重新扫描", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = palette.signal)
                    if (scanning) CircularProgressIndicator(Modifier.padding(start = 8.dp).width(18.dp).height(18.dp), strokeWidth = 2.dp)
                }
                Text("忽略短于", Modifier.padding(start = 16.dp, top = 6.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 60).forEach { s ->
                        Chip(if (s == 0) "不限" else "$s 秒", minDur == s, Modifier.weight(1f)) {
                            scope.launch { container.prefs.setMinDuration(s); container.library.rescan() }
                        }
                    }
                }
            }
            Group("歌手形象") {
                Row(Modifier.fillMaxWidth().clickable { importFolder() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("从文件夹批量导入", style = MaterialTheme.typography.bodyLarge)
                        Text("Artist_Name.png · Artist_Name_poster.jpg", style = EchoType.label.copy(letterSpacing = 0.4.sp), color = palette.muted)
                    }
                }
            }
            Group("歌词") {
                Row(Modifier.fillMaxWidth().clickable { treePicker.launch(null) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("歌词文件夹", style = MaterialTheme.typography.bodyLarge)
                        Text(tree?.let { Uri.decode(it).substringAfterLast(':') }?.ifEmpty { "/" } ?: "未选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tree != null) Text(
                        "移除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { container.prefs.setLyricsTree(null); container.lyrics.invalidate() } }.padding(8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { scope.launch { container.prefs.setKeepScreenOnLyrics(!keepOn) } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示歌词时保持亮屏", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(keepOn, { v -> scope.launch { container.prefs.setKeepScreenOnLyrics(v) } })
                }
            }
            Group("数据") {
                Row(Modifier.fillMaxWidth().clickable { clearing = true }.padding(16.dp)) {
                    Text("清除收听记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            Group("关于") {
                Row(Modifier.fillMaxWidth().clickable { licenses = true }.padding(16.dp)) {
                    Text("开源许可", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        CollapsingTopBar("设置", 0f)
    }
    if (licenses) LicensesDialog { licenses = false }
    if (clearing) ConfirmDialog("清除全部收听记录？", "清除") { ok ->
        clearing = false
        if (ok) scope.launch { container.db.events().clear() }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp), style = EchoType.labelBold, color = palette.signal)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(EchoShape.card).background(MaterialTheme.colorScheme.surfaceContainer)) {
        content()
    }
}

@Composable
private fun Item(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = palette
    Box(
        modifier.clip(EchoShape.chip)
            .background(if (selected) p.signal else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) p.onSignal else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("Anton" to "licenses/Anton-OFL.txt", "Space Grotesk" to "licenses/SpaceGrotesk-OFL.txt").joinToString("\n\n") { (n, f) ->
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
