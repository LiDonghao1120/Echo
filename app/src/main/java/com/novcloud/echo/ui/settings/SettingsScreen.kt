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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.AppLanguage
import com.novcloud.echo.BuildConfig
import com.novcloud.echo.EchoApp
import com.novcloud.echo.Languages
import com.novcloud.echo.R
import com.novcloud.echo.data.ThemeMode
import com.novcloud.echo.ui.components.ConfirmDialog
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.number
import com.novcloud.echo.ui.detail.CollapsingTopBar
import com.novcloud.echo.ui.theme.EchoMotion
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
            PageTitle(stringResource(R.string.settings))
            Group(stringResource(R.string.settings_appearance)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.System to R.string.follow_system, ThemeMode.Dark to R.string.theme_dark, ThemeMode.Light to R.string.theme_light).forEach { (m, l) ->
                        Chip(stringResource(l), theme == m, Modifier.weight(1f)) { scope.launch { container.prefs.setTheme(m) } }
                    }
                }
            }
            Group(stringResource(R.string.settings_language)) {
                val activity = context as? android.app.Activity
                var current by remember { mutableStateOf(Languages.current(context)) }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppLanguage.System to stringResource(R.string.follow_system),
                        AppLanguage.Chinese to stringResource(R.string.language_chinese),
                        AppLanguage.English to stringResource(R.string.language_english),
                    ).forEach { (lang, l) ->
                        Chip(l, current == lang, Modifier.weight(1f)) {
                            if (activity != null && current != lang) { current = lang; Languages.set(activity, lang) }
                        }
                    }
                }
            }
            Group(stringResource(R.string.settings_playback)) {
                val fx by container.audioFx.settings.collectAsState()
                val gain = stringResource(R.string.fx_replaygain)
                NavRow(stringResource(R.string.audio_effects), listOfNotNull(
                    if (fx.eqEnabled) presetLabel(fx.preset) else null,
                    when (fx.gainMode) {
                        com.novcloud.echo.playback.audio.GainMode.Track -> "$gain · ${stringResource(R.string.fx_gain_track)}"
                        com.novcloud.echo.playback.audio.GainMode.Album -> "$gain · ${stringResource(R.string.fx_gain_album)}"
                        else -> null
                    },
                ).joinToString(" · ").ifEmpty { null }) { actions.openEffects() }
            }
            Group(stringResource(R.string.tab_library)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Count(lib.songs.size, pluralStringResource(R.plurals.label_songs, lib.songs.size))
                    Count(lib.albums.size, pluralStringResource(R.plurals.label_albums, lib.albums.size))
                    Count(lib.artists.size, pluralStringResource(R.plurals.label_artists, lib.artists.size))
                }
                Rule()
                val added = trees.size.takeIf { it > 0 }?.let { count(R.plurals.folders_added_count, it) }
                val skipped = excluded.size.takeIf { it > 0 }?.let { count(R.plurals.folders_excluded_count, it) }
                NavRow(stringResource(R.string.music_folders), listOfNotNull(
                    added,
                    skipped,
                ).joinToString(" · ").ifEmpty { null }) { actions.openScanFolders() }
                Rule()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !scanning) { container.library.refresh() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.action_rescan), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (scanning) palette.muted else palette.signal)
                    progress?.let { Text("${it.done} / ${it.total}", style = EchoType.numeral, color = palette.muted) }
                }
                ScanBar(scanning, progress)
                Rule()
                Text(stringResource(R.string.settings_min_duration), Modifier.padding(start = 16.dp, top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 60).forEach { s ->
                        Chip(if (s == 0) stringResource(R.string.min_duration_any) else stringResource(R.string.seconds_short, s), minDur == s, Modifier.weight(1f)) {
                            scope.launch { container.prefs.setMinDuration(s); container.library.rescan(false) }
                        }
                    }
                }
            }
            Group(stringResource(R.string.artist_art)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { importFolder() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.art_import_folder), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.art_file_pattern), style = EchoType.label.copy(letterSpacing = 0.4.sp), color = palette.muted)
                    }
                    Chevron()
                }
            }
            Group(stringResource(R.string.lyrics)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { treePicker.launch(null) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lyrics_folder), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(tree?.let { Uri.decode(it).substringAfterLast(':') }?.ifEmpty { "/" } ?: stringResource(R.string.not_set), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                    if (tree != null) Text(
                        stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(EchoShape.card).clickable { scope.launch { container.prefs.setLyricsTree(null); container.lyrics.invalidate() } }.padding(12.dp),
                    )
                }
                Rule()
                Text(stringResource(R.string.lyrics_display), Modifier.padding(start = 16.dp, top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to R.string.lyrics_by_line, true to R.string.lyrics_by_word).forEach { (v, l) ->
                        Chip(stringResource(l), byWord == v, Modifier.weight(1f)) { scope.launch { container.prefs.setLyricsByWord(v) } }
                    }
                }
                Rule()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { scope.launch { container.prefs.setKeepScreenOnLyrics(!keepOn) } }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lyrics_keep_screen_on), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(keepOn, { v -> scope.launch { container.prefs.setKeepScreenOnLyrics(v) } })
                }
            }
            Group(stringResource(R.string.settings_data)) {
                NavRow(stringResource(R.string.backup_export), null, tools.exportBackup)
                Rule()
                NavRow(stringResource(R.string.backup_import), null, tools.importBackup)
                Rule()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { clearing = true }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.history_clear), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            Group(stringResource(R.string.settings_about)) {
                NavRow(stringResource(R.string.diag_export), null, tools.exportLog)
                Rule()
                NavRow(stringResource(R.string.licenses), null) { licenses = true }
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
        CollapsingTopBar(stringResource(R.string.settings), collapse)
    }
    if (licenses) LicensesDialog { licenses = false }
    if (clearing) ConfirmDialog(stringResource(R.string.history_clear_confirm), stringResource(R.string.action_clear_history)) { ok ->
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
        Text(number(value), style = EchoType.poster(28.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.muted, maxLines = 1)
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
            .clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        val style = MaterialTheme.typography.labelLarge
        androidx.compose.foundation.text.BasicText(
            label, style = style, color = { fg }, maxLines = 1, softWrap = false,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
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
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.licenses)) },
        text = {
            Text(
                text, Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall, color = palette.muted,
            )
        },
    )
}
