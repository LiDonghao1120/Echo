package com.novcloud.echo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.theme.EchoShape

@Composable
fun CollapsingTopBar(
    title: String,
    progress: Float,
    overImage: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val nav = LocalActions.current
    val bg = MaterialTheme.colorScheme.surface
    val iconTint = if (overImage) lerp(Color.White, MaterialTheme.colorScheme.onSurface, progress) else MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().background(bg.copy(alpha = progress * 0.97f))) {
        Row(Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(EchoShape.card)
                    .background(if (overImage) Color.Black.copy(alpha = 0.25f * (1 - progress)) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = iconTint) }
            }
            Text(
                title,
                Modifier.weight(1f).padding(horizontal = 8.dp).graphicsLayer { alpha = progress; translationY = (1 - progress) * 16f },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Box(
                Modifier.clip(EchoShape.card).background(if (overImage) Color.Black.copy(alpha = 0.25f * (1 - progress)) else Color.Transparent),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides iconTint) { actions() }
            }
            Spacer(Modifier.width(2.dp))
        }
        if (progress > 0.95f) HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = com.novcloud.echo.ui.theme.LocalPalette.current.rule)
    }
}
