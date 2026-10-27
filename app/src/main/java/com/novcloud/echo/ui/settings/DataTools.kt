package com.novcloud.echo.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.BuildConfig
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.BackupException
import com.novcloud.echo.data.ImportMode
import com.novcloud.echo.data.PendingImport
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataTools(val exportBackup: () -> Unit, val importBackup: () -> Unit, val exportLog: () -> Unit)

private fun backupError(e: Throwable): Int = when ((e as? BackupException)?.reason) {
    BackupException.Reason.Invalid -> R.string.backup_error_invalid
    BackupException.Reason.NotEcho -> R.string.backup_error_not_echo
    BackupException.Reason.Newer -> R.string.backup_error_newer
    BackupException.Reason.Incomplete -> R.string.backup_error_incomplete
    BackupException.Reason.Unopenable -> R.string.backup_error_unopenable
    null -> R.string.backup_unreadable
}

/** "1,234 songs · 56 albums · 78 artists" in the current language. */
fun librarySummary(res: android.content.res.Resources, lib: com.novcloud.echo.data.Library): String = listOf(
    res.count(R.plurals.songs_short, lib.songs.size),
    res.count(R.plurals.albums_count, lib.albums.size),
    res.count(R.plurals.artists_count, lib.artists.size),
).joinToString(" · ")

private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

@Composable
fun rememberDataTools(): DataTools {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Int?>(null) }
    val res = context.resources
    var pending by remember { mutableStateOf<PendingImport?>(null) }

    fun toast(text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = R.string.backup_exporting
        scope.launch {
            val r = container.backup.export(uri)
            busy = null
            toast(if (r.isSuccess) R.string.backup_exported else R.string.export_failed)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = R.string.backup_reading
        scope.launch {
            val r = container.backup.read(uri)
            busy = null
            r.onSuccess { pending = it }.onFailure { toast(backupError(it)) }
        }
    }
    val logger = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val lib = container.library.library.value
            val header = listOf(
                res.getString(R.string.app_version_line, res.getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                DiagLog.device(),
                res.getString(R.string.diag_library, librarySummary(res, lib)),
                res.getString(R.string.diag_exported_at, SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())),
            ).joinToString("\n")
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { DiagLog.export(it, header) } }.isSuccess
            }
            toast(if (ok) R.string.diag_exported else R.string.export_failed)
        }
    }

    busy?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.signal)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(msg))
            }
        })
    }
    pending?.let { p ->
        ImportSheet(p, onDismiss = { pending = null; container.backup.discard() }) { mode ->
            pending = null
            busy = R.string.backup_restoring
            scope.launch {
                val r = container.backup.apply(p, mode)
                container.lyrics.invalidate()
                busy = null
                toast(if (r.isSuccess) R.string.backup_restored else R.string.restore_failed)
            }
        }
    }
    return remember(exporter, importer, logger) {
        DataTools(
            exportBackup = { exporter.launch(res.getString(R.string.backup_file_name, stamp())) },
            importBackup = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            exportLog = { logger.launch(res.getString(R.string.diag_file_name, stamp())) },
        )
    }
}

@Composable
fun ImportSheet(pending: PendingImport, onDismiss: () -> Unit, onChoose: (ImportMode) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
        ImportSheetContent(pending, onChoose)
    }
}

@Composable
fun ImportSheetContent(pending: PendingImport, onChoose: (ImportMode) -> Unit) {
    val p = palette
    var confirmReplace by remember { mutableStateOf(false) }
    Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        Eyebrow(stringResource(R.string.backup_restore_title))
        Spacer(Modifier.height(6.dp))
        val created = pending.data.createdAt.takeIf { it > 0 }?.let {
            com.novcloud.echo.ui.components.formatDate(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()), R.string.date_backup)
        }
        val version = pending.data.appVersion.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.backup_app_version, stringResource(R.string.app_name), it) }
        Text(
            listOfNotNull(created, version).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = p.muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(pending.favorites, pluralStringResource(R.plurals.label_liked, pending.favorites))
            Stat(pending.playlists, pluralStringResource(R.plurals.label_playlists, pending.playlists))
            Stat(pending.events, pluralStringResource(R.plurals.label_listens, pending.events))
            Stat(pending.artists, pluralStringResource(R.plurals.label_artist_images, pending.artists))
        }
        Spacer(Modifier.height(22.dp))
        Choice(stringResource(R.string.backup_merge), stringResource(R.string.backup_merge_detail), p.signal, p.onSignal) { onChoose(ImportMode.Merge) }
        Spacer(Modifier.height(8.dp))
        if (!confirmReplace) {
            Choice(stringResource(R.string.backup_replace), stringResource(R.string.backup_replace_detail), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) {
                confirmReplace = true
            }
        } else {
            Choice(stringResource(R.string.backup_replace_confirm), stringResource(R.string.backup_replace_confirm_detail), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surface) {
                onChoose(ImportMode.Replace)
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column {
        Text(com.novcloud.echo.ui.components.number(value), style = EchoType.poster(28.sp), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.muted)
    }
}

@Composable
private fun Choice(title: String, detail: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().echoClick(corner = 6.dp, color = palette.signal, pressedScale = 0.97f, onClick = onClick)
            .clip(EchoShape.card).background(bg).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
        }
    }
}
