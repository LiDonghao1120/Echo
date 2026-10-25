package com.novcloud.echo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novcloud.echo.ui.theme.LocalMotion
import com.novcloud.echo.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun DrawScope.echoRing(color: Color, grow: Float, corner: Float, stroke: Float) {
    drawRoundRect(
        color,
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + grow * 2, size.height + grow * 2),
        cornerRadius = CornerRadius(corner + grow * 0.6f),
        style = Stroke(stroke),
    )
}

fun Modifier.echoClick(
    corner: Dp = 6.dp,
    reach: Dp = 16.dp,
    color: Color? = null,
    pressedScale: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow), label = "press",
    )
    val rings = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    val tint = color ?: LocalPalette.current.signal
    val motion = LocalMotion.current
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .drawWithContent {
            drawContent()
            rings.forEach { a ->
                val p = a.value
                echoRing(tint.copy(alpha = (1f - p) * 0.75f), reach.toPx() * p, corner.toPx(), 1.6.dp.toPx() * (1f - p * 0.6f))
            }
        }
        .combinedClickable(interaction, null, onLongClick = onLongClick) {
            if (motion) repeat(2) { i ->
                val a = Animatable(0f)
                rings += a
                scope.launch {
                    delay(i * 110L)
                    a.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
                    rings.remove(a)
                }
            }
            onClick()
        }
}

fun Modifier.echoAura(active: Boolean, color: Color, corner: Dp = 6.dp, reach: Dp = 26.dp, count: Int = 3): Modifier = composed {
    val motion = LocalMotion.current
    val level by animateFloatAsState(if (active) 1f else 0f, tween(700), label = "aura")
    val phase = if (motion) {
        val t = rememberInfiniteTransition(label = "aura")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "phase").value
    } else 0.18f
    drawBehind {
        if (level <= 0.01f) return@drawBehind
        for (i in 0 until count) {
            val p = (phase + i.toFloat() / count) % 1f
            echoRing(color.copy(alpha = (1f - p) * 0.42f * level), reach.toPx() * p, corner.toPx(), 1.4.dp.toPx())
        }
    }
}

fun Modifier.reveal(index: Int = 0, distance: Dp = 26.dp): Modifier = composed {
    val motion = LocalMotion.current
    val a = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) {
            delay(index * 60L)
            a.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
    }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * distance.toPx()
    }
}

@Composable
fun EchoMeter(playing: Boolean, modifier: Modifier = Modifier, color: Color = LocalPalette.current.signal) {
    val motion = LocalMotion.current && playing
    val t = rememberInfiniteTransition(label = "meter")
    val levels = listOf(0, 140, 290).map { d ->
        if (motion) t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(460, d, FastOutSlowInEasing), RepeatMode.Reverse), label = "m$d").value
        else 0.35f + (d % 3) * 0.1f
    }
    Canvas(modifier.size(16.dp)) {
        val cols = 3
        val rows = 4
        val gap = size.width * 0.12f
        val cw = (size.width - gap * (cols - 1)) / cols
        val ch = (size.height - gap * (rows - 1)) / rows
        for (c in 0 until cols) {
            val lit = (levels[c] * rows + 0.5f).toInt().coerceIn(1, rows)
            for (r in 0 until rows) {
                val on = r < lit
                drawRect(
                    color.copy(alpha = if (on) 1f else 0.18f),
                    Offset(c * (cw + gap), size.height - (r + 1) * ch - r * gap),
                    Size(cw, ch),
                )
            }
        }
    }
}
