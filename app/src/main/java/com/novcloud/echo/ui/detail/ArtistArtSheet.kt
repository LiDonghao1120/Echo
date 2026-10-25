package com.novcloud.echo.ui.detail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.ArtistLogoInfo
import com.novcloud.echo.data.ImportReport
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.ui.components.ArtistNameMark
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.LogoScrim
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.rememberArtistPoster
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededColor
import kotlinx.coroutines.launch

class ArtistArtActions(
    val pickPoster: () -> Unit,
    val clearPoster: () -> Unit,
    val pickLogoGallery: () -> Unit,
    val pickLogoFile: () -> Unit,
    val clearLogo: () -> Unit,
    val batch: () -> Unit,
)

@Composable
fun rememberArtistArtActions(name: String): ArtistArtActions {
    val context = LocalContext.current
    val repo = (context.applicationContext as EchoApp).container.library
    val poster = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) repo.setArtistImage(name, uri) }
    val logoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) repo.setArtistLogo(name, uri) }
    val logoFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) repo.setArtistLogo(name, uri) }
    val batch = rememberFolderImport()
    LaunchedEffect(name) {
        repo.artFailed.collect { a -> if (a == name) Toast.makeText(context, "无法读取这张图片", Toast.LENGTH_SHORT).show() }
    }
    return remember(name, poster, logoGallery, logoFile, batch) {
        ArtistArtActions(
            pickPoster = { poster.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            clearPoster = { repo.clearArtistImage(name) },
            pickLogoGallery = { logoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pickLogoFile = { logoFile.launch(arrayOf("image/png", "image/webp", "image/*")) },
            clearLogo = { repo.clearArtistLogo(name) },
            batch = batch,
        )
    }
}

@Composable
fun ArtistArtSheet(name: String, customPoster: String?, logo: ArtistLogoInfo?, actions: ArtistArtActions, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
        ArtistArtSheetContent(name, customPoster != null, logo, actions)
    }
}

@Composable
fun ArtistArtSheetContent(name: String, hasPoster: Boolean, logo: ArtistLogoInfo?, actions: ArtistArtActions) {
    val p = palette
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Eyebrow(displayArtist(name), color = p.muted)
        Spacer(Modifier.height(4.dp))
        Text("歌手形象", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black))
        Spacer(Modifier.height(16.dp))
        PosterPreview(name, logo)
        Spacer(Modifier.height(18.dp))
        ArtRow("海报") {
            SheetKey(Icons.Rounded.Image, if (hasPoster) "更换" else "选择", primary = true, onClick = actions.pickPoster)
            if (hasPoster) SheetKey(Icons.Rounded.Close, "移除", onClick = actions.clearPoster)
        }
        Spacer(Modifier.height(12.dp))
        ArtRow("名称标志") {
            SheetKey(Icons.Rounded.Image, "相册", primary = logo == null, onClick = actions.pickLogoGallery)
            SheetKey(Icons.Rounded.InsertDriveFile, "文件", onClick = actions.pickLogoFile)
            if (logo != null) SheetKey(Icons.Rounded.Close, "移除", onClick = actions.clearLogo)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.rule))
        Row(
            Modifier.fillMaxWidth().clip(EchoShape.card).echoClick(onClick = actions.batch).padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FolderOpen, null, tint = p.signal)
            Spacer(Modifier.width(12.dp))
            Text("从文件夹批量导入", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(end = 4.dp), tint = p.faint)
        }
    }
}

@Composable
private fun PosterPreview(name: String, logo: ArtistLogoInfo?) {
    val poster = rememberArtistPoster(name)
    val busy by (LocalContext.current.applicationContext as EchoApp).container.library.artBusy.collectAsState()
    val base = remember(name) { seededColor(name) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(EchoShape.card).background(lerp(base, Ink, 0.5f)),
    ) {
        if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ink.copy(alpha = 0.6f))))
        LogoScrim(logo, 0.9f)
        ArtistNameMark(
            name, logo, maxWidth - 48.dp, maxHeight * 0.38f,
            Modifier.align(Alignment.Center), textSize = 40.sp, minTextSize = 18.sp,
        )
        if (name in busy) LinearProgressIndicator(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
            color = palette.signal, trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun ArtRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SheetKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = palette
    val bg = if (primary) p.signal else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (primary) p.onSignal else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.echoClick(corner = 6.dp, onClick = onClick).clip(EchoShape.card).background(bg).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.padding(end = 6.dp).width(18.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun rememberFolderImport(): () -> Unit {
    val context = LocalContext.current
    val container = (context.applicationContext as EchoApp).container
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                report = container.library.importFolder(uri)
                busy = false
            }
        }
    }
    report?.let { r -> ImportReportDialog(r) { report = null } }
    if (busy) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("正在导入…") })
    return remember(launcher) { { launcher.launch(null) } }
}

@Composable
fun ImportReportDialog(r: ImportReport, onDismiss: () -> Unit) {
    val p = palette
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
        title = { Text("导入完成") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Figure(r.logos.toLong(), "个名称标志", size = 34.dp)
                    Figure(r.posters.toLong(), "张海报", size = 34.dp)
                }
                if (r.unmatched.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("未匹配 ${r.unmatched.size}", style = MaterialTheme.typography.labelLarge, color = p.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        r.unmatched.take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = p.faint,
                    )
                }
            }
        },
    )
}
