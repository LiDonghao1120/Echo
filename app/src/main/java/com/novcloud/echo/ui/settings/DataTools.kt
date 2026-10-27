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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.BuildConfig
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.BackupException
import com.novcloud.echo.data.ImportMode
import com.novcloud.echo.data.PendingImport
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataTools(val exportBackup: () -> Unit, val importBackup: () -> Unit, val exportLog: () -> Unit)

private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

@Composable
fun rememberDataTools(): DataTools {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "正在导出…"
        scope.launch {
            val r = container.backup.export(uri)
            busy = null
            toast(if (r.isSuccess) "备份已导出" else "导出失败")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "正在读取…"
        scope.launch {
            val r = container.backup.read(uri)
            busy = null
            r.onSuccess { pending = it }.onFailure { toast((it as? BackupException)?.message ?: "无法读取这个文件") }
        }
    }
    val logger = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val lib = container.library.library.value
            val header = listOf(
                "回响 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                DiagLog.device(),
                "曲库 ${lib.songs.size} 首 · ${lib.albums.size} 张专辑 · ${lib.artists.size} 位歌手",
                "导出于 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}",
            ).joinToString("\n")
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { DiagLog.export(it, header) } }.isSuccess
            }
            toast(if (ok) "诊断日志已导出" else "导出失败")
        }
    }

    busy?.let { msg ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.signal)
                Spacer(Modifier.width(14.dp))
                Text(msg)
            }
        })
    }
    pending?.let { p ->
        ImportSheet(p, onDismiss = { pending = null; container.backup.discard() }) { mode ->
            pending = null
            busy = "正在恢复…"
            scope.launch {
                val r = container.backup.apply(p, mode)
                busy = null
                toast(if (r.isSuccess) "已恢复备份" else "恢复失败")
            }
        }
    }
    return remember(exporter, importer, logger) {
        DataTools(
            exportBackup = { exporter.launch("回响备份-${stamp()}.zip") },
            importBackup = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            exportLog = { logger.launch("回响诊断-${stamp()}.txt") },
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
        Eyebrow("恢复备份")
        Spacer(Modifier.height(6.dp))
        val created = pending.data.createdAt.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        Text(
            listOfNotNull(created, pending.data.appVersion.takeIf { it.isNotEmpty() }?.let { "回响 $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = p.muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(pending.favorites, "喜欢")
            Stat(pending.playlists, "歌单")
            Stat(pending.events, "次收听")
            Stat(pending.artists, "歌手形象")
        }
        Spacer(Modifier.height(22.dp))
        Choice("合并", "保留手机上的数据，补上备份里没有的", p.signal, p.onSignal) { onChoose(ImportMode.Merge) }
        Spacer(Modifier.height(8.dp))
        if (!confirmReplace) {
            Choice("替换", "用备份覆盖喜欢、歌单、收听记录和设置", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) {
                confirmReplace = true
            }
        } else {
            Choice("确认替换", "手机上现有的这些数据会被清除", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.surface) {
                onChoose(ImportMode.Replace)
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column {
        Text(value.toString(), style = EchoType.poster(28.sp), color = MaterialTheme.colorScheme.onSurface)
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
