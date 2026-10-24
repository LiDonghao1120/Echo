package com.novcloud.echo.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.domain.TrendPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
private fun rememberGrow(key: Any): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    return a.value
}

fun shortDuration(ms: Long): String {
    val m = ms / 60_000
    return when {
        m >= 600 -> "${m / 60}h"
        m >= 60 -> "${m / 60}h${m % 60}m"
        else -> "${m}m"
    }
}

@Composable
fun BarChart(points: List<TrendPoint>, color: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(points)
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    var selected by remember(points) { mutableIntStateOf(points.indices.maxByOrNull { points[it].ms } ?: -1) }
    val maxV = max(1L, points.maxOfOrNull { it.ms } ?: 1L)
    Canvas(
        modifier.fillMaxWidth().height(190.dp).pointerInput(points) {
            detectTapGestures { o ->
                val slot = size.width / points.size.coerceAtLeast(1)
                selected = (o.x / slot).toInt().coerceIn(0, points.lastIndex)
            }
        },
    ) {
        if (points.isEmpty()) return@Canvas
        val top = 30.dp.toPx()
        val bottom = 22.dp.toPx()
        val h = size.height - top - bottom
        val slot = size.width / points.size
        val barW = min(slot * 0.62f, 22.dp.toPx())
        val step = when { points.size > 20 -> 5; points.size > 12 -> 2; else -> 1 }
        points.forEachIndexed { i, p ->
            val cx = slot * i + slot / 2
            val frac = p.ms.toFloat() / maxV
            val bh = max(3.dp.toPx(), h * frac * grow)
            drawRoundRect(track, Offset(cx - barW / 2, top), Size(barW, h), CornerRadius(barW / 2))
            val c = if (i == selected) color else color.copy(alpha = 0.55f)
            drawRoundRect(
                Brush.verticalGradient(listOf(c, c.copy(alpha = c.alpha * 0.6f)), startY = top + h - bh, endY = top + h),
                Offset(cx - barW / 2, top + h - bh), Size(barW, bh), CornerRadius(barW / 2),
            )
            if (i % step == 0 || i == points.lastIndex) {
                val t = measurer.measure(p.label, TextStyle(fontSize = 10.sp, color = labelColor))
                if (i % step == 0) drawText(t, topLeft = Offset(cx - t.size.width / 2f, size.height - t.size.height))
            }
        }
        if (selected in points.indices) {
            val cx = slot * selected + slot / 2
            val t = measurer.measure(shortDuration(points[selected].ms), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onSurface))
            val x = (cx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, 4.dp.toPx()))
        }
    }
}

@Composable
fun RadialClock(hourly: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(hourly)
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = max(1L, hourly.maxOrNull() ?: 1L)
    val peak = hourly.indices.maxByOrNull { hourly[it] } ?: 0
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2
        val center = Offset(size.width / 2, size.height / 2)
        val inner = r * 0.36f
        val outer = r * 0.86f
        val stroke = (2 * PI * inner / 24 * 0.55).toFloat()
        drawCircle(track, inner - stroke, center, style = Stroke(1.dp.toPx()))
        for (h in 0 until 24) {
            val ang = (h / 24f) * 2 * PI - PI / 2
            val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
            val len = (outer - inner) * (hourly[h].toFloat() / maxV) * grow
            drawLine(track, center + dir * inner, center + dir * outer, stroke, StrokeCap.Round)
            if (hourly[h] > 0) drawLine(
                if (h == peak) color else color.copy(alpha = 0.55f),
                center + dir * inner, center + dir * (inner + max(len, 1f)), stroke, StrokeCap.Round,
            )
        }
        listOf(0, 6, 12, 18).forEach { h ->
            val ang = (h / 24f) * 2 * PI - PI / 2
            val t = measurer.measure("$h", TextStyle(fontSize = 10.sp, color = label))
            val p = center + Offset(cos(ang).toFloat(), sin(ang).toFloat()) * (r * 0.97f)
            drawText(t, topLeft = Offset(p.x - t.size.width / 2, p.y - t.size.height / 2))
        }
        val pt = measurer.measure("$peak:00", TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = color))
        drawText(pt, topLeft = Offset(center.x - pt.size.width / 2, center.y - pt.size.height / 2))
    }
}

@Composable
fun WeekdayChart(values: List<Long>, labels: List<String>, color: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(values)
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = max(1L, values.maxOrNull() ?: 1L)
    val peak = values.indices.maxByOrNull { values[it] } ?: 0
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        val slot = size.width / 7
        val bottom = 20.dp.toPx()
        val h = size.height - bottom
        val d = min(slot * 0.5f, 26.dp.toPx())
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            drawRoundRect(track, Offset(cx - d / 2, 0f), Size(d, h), CornerRadius(d / 2))
            val bh = max(d, h * (v.toFloat() / maxV) * grow)
            drawRoundRect(if (i == peak) color else color.copy(alpha = 0.45f), Offset(cx - d / 2, h - bh), Size(d, bh), CornerRadius(d / 2))
            val t = measurer.measure(labels[i], TextStyle(fontSize = 11.sp, color = label))
            drawText(t, topLeft = Offset(cx - t.size.width / 2, size.height - t.size.height))
        }
    }
}

@Composable
fun Donut(values: List<Long>, colors: List<Color>, modifier: Modifier = Modifier) {
    val grow = rememberGrow(values)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val total = values.sum().coerceAtLeast(1L).toFloat()
    Canvas(modifier) {
        val w = size.minDimension * 0.16f
        val d = size.minDimension - w
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
        var start = -90f
        values.forEachIndexed { i, v ->
            val sweep = 360f * v / total * grow
            if (sweep > 0.5f) drawArc(colors[i % colors.size], start + 1f, max(0.1f, sweep - 2f), false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

@Composable
fun HeatStrip(days: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val maxV = max(1L, days.maxOrNull() ?: 1L)
    val grow = rememberGrow(days)
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val n = days.size
        val gap = 3.dp.toPx()
        val cell = (size.width - gap * (n - 1)) / n
        days.forEachIndexed { i, v ->
            val a = if (v == 0L) 0f else (0.25f + 0.75f * v / maxV) * grow
            drawRoundRect(empty, Offset(i * (cell + gap), 0f), Size(cell, size.height), CornerRadius(4.dp.toPx()))
            if (a > 0) drawRoundRect(color.copy(alpha = a), Offset(i * (cell + gap), 0f), Size(cell, size.height), CornerRadius(4.dp.toPx()))
        }
    }
}
