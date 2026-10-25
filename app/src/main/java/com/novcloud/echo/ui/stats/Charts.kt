package com.novcloud.echo.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.domain.TrendPoint
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.LocalMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun rememberGrow(key: Any, delayMs: Int = 0): Float {
    val motion = LocalMotion.current
    val a = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key) { if (motion) a.animateTo(1f, tween(1100, delayMs, FastOutSlowInEasing)) }
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
fun HourDial(hourly: List<Long>, day: Color, night: Color, track: Color, ink: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(hourly)
    val measurer = rememberTextMeasurer()
    val maxV = max(1L, hourly.maxOrNull() ?: 1L)
    val peak = hourly.indices.maxByOrNull { hourly[it] } ?: 0
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2
        val c = Offset(size.width / 2, size.height / 2)
        val inner = r * 0.34f
        val outer = r * 0.9f
        val wedge = 360f / 24f
        for (h in 0 until 24) {
            val start = h * wedge - 90f + 1.2f
            val sweep = wedge - 2.4f
            val frac = hourly[h].toFloat() / maxV
            drawArc(track, start, sweep, false, Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2), style = Stroke(1.dp.toPx()))
            val len = inner + (outer - inner) * max(0.04f, frac) * grow
            val col = if (h in 6..17) day else night
            val path = Path().apply {
                moveTo(c.x + cos(Math.toRadians(start.toDouble())).toFloat() * inner, c.y + sin(Math.toRadians(start.toDouble())).toFloat() * inner)
                arcTo(Rect(c, len), start, sweep, false)
                arcTo(Rect(c, inner), start + sweep, -sweep, false)
                close()
            }
            drawPath(path, if (h == peak) col else col.copy(alpha = 0.32f + 0.5f * frac))
        }
        listOf(0, 6, 12, 18).forEach { h ->
            val ang = (h / 24f) * 2 * PI - PI / 2
            val t = measurer.measure("%02d".format(h), EchoType.label.copy(fontSize = 9.sp, color = track.copy(alpha = 1f)))
            val pt = c + Offset(cos(ang).toFloat(), sin(ang).toFloat()) * (inner * 0.7f)
            drawText(t, topLeft = Offset(pt.x - t.size.width / 2, pt.y - t.size.height / 2))
        }
        drawCircle(ink, inner * 0.18f, c)
    }
}

@Composable
fun RidgeChart(points: List<TrendPoint>, line: Color, fill: Color, label: Color, ink: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(points)
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableIntStateOf(points.indices.maxByOrNull { points[it].ms } ?: -1) }
    val maxV = max(1L, points.maxOfOrNull { it.ms } ?: 1L)
    Canvas(
        modifier.fillMaxWidth().height(180.dp).pointerInput(points) {
            detectTapGestures { o ->
                if (points.isEmpty()) return@detectTapGestures
                val step = size.width / max(1, points.size - 1).toFloat()
                selected = ((o.x + step / 2) / step).toInt().coerceIn(0, points.lastIndex)
            }
        },
    ) {
        if (points.isEmpty()) return@Canvas
        val top = 34.dp.toPx()
        val bottom = 20.dp.toPx()
        val h = size.height - top - bottom
        val n = points.size
        val step = if (n > 1) size.width / (n - 1) else size.width
        fun xy(i: Int) = Offset(if (n > 1) i * step else size.width / 2, top + h - h * (points[i].ms.toFloat() / maxV) * grow)
        val stripes = 7
        for (s in 0..stripes) {
            val y = top + h * s / stripes
            drawLine(label.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        val area = Path().apply {
            moveTo(0f, top + h)
            for (i in 0 until n) {
                val p = xy(i)
                if (i == 0) lineTo(p.x, p.y) else {
                    val prev = xy(i - 1)
                    val mx = (prev.x + p.x) / 2
                    cubicTo(mx, prev.y, mx, p.y, p.x, p.y)
                }
            }
            lineTo(size.width, top + h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fill.copy(alpha = 0.85f), fill.copy(alpha = 0.15f)), startY = top, endY = top + h))
        val ridge = Path().apply {
            for (i in 0 until n) {
                val p = xy(i)
                if (i == 0) moveTo(p.x, p.y) else {
                    val prev = xy(i - 1)
                    val mx = (prev.x + p.x) / 2
                    cubicTo(mx, prev.y, mx, p.y, p.x, p.y)
                }
            }
        }
        drawPath(ridge, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val labelEvery = when { n > 20 -> 7; n > 12 -> 3; else -> 1 }
        for (i in 0 until n) {
            if (i % labelEvery != 0 && i != n - 1) continue
            if (i == n - 1 && n > 12 && (n - 1) % labelEvery != 0 && (n - 1) % labelEvery < labelEvery / 2 + 1) continue
            val t = measurer.measure(points[i].label, EchoType.label.copy(fontSize = 9.sp, letterSpacing = 0.5.sp, color = label))
            val x = (xy(i).x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, size.height - t.size.height))
        }
        if (selected in points.indices) {
            val p = xy(selected)
            drawLine(ink.copy(alpha = 0.6f), Offset(p.x, top - 4.dp.toPx()), Offset(p.x, top + h), 1.dp.toPx())
            val sq = 9.dp.toPx()
            drawRect(ink, Offset(p.x - sq / 2, p.y - sq / 2), Size(sq, sq))
            drawRect(line, Offset(p.x - sq / 2 + 2.dp.toPx(), p.y - sq / 2 + 2.dp.toPx()), Size(sq - 4.dp.toPx(), sq - 4.dp.toPx()))
            val t = measurer.measure(shortDuration(points[selected].ms), EchoType.numeral(13.sp).copy(color = ink))
            val x = (p.x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, 4.dp.toPx()))
        }
    }
}

@Composable
fun TallyWeek(values: List<Long>, labels: List<String>, color: Color, track: Color, label: Color, modifier: Modifier = Modifier, cells: Int = 9) {
    val grow = rememberGrow(values, 150)
    val measurer = rememberTextMeasurer()
    val maxV = max(1L, values.maxOrNull() ?: 1L)
    val peak = values.indices.maxByOrNull { values[it] } ?: 0
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val bottom = 20.dp.toPx()
        val h = size.height - bottom
        val slot = size.width / 7
        val gap = 4.dp.toPx()
        val cell = min(slot - gap * 2, (h - gap * (cells - 1)) / cells)
        values.forEachIndexed { i, v ->
            val lit = if (v == 0L) 0 else max(1, (cells * v.toFloat() / maxV * grow + 0.5f).toInt())
            val x = slot * i + (slot - cell) / 2
            for (k in 0 until cells) {
                val y = h - (k + 1) * cell - k * gap
                val on = k < lit
                drawRoundRect(
                    if (on) (if (i == peak) color else color.copy(alpha = 0.5f)) else track,
                    Offset(x, y), Size(cell, cell), CornerRadius(2.dp.toPx()),
                )
            }
            val t = measurer.measure(labels[i], TextStyle(fontSize = 11.sp, color = if (i == peak) color else label))
            drawText(t, topLeft = Offset(slot * i + slot / 2 - t.size.width / 2, size.height - t.size.height))
        }
    }
}

@Composable
fun GenreBand(values: List<Long>, colors: List<Color>, gapColor: Color, modifier: Modifier = Modifier, height: Dp = 46.dp) {
    val grow = rememberGrow(values, 200)
    val total = values.sum().coerceAtLeast(1L).toFloat()
    Canvas(modifier.fillMaxWidth().height(height)) {
        var x = 0f
        val gap = 3.dp.toPx()
        values.forEachIndexed { i, v ->
            val w = size.width * v / total * grow
            if (w > gap) drawRect(colors[i % colors.size], Offset(x, 0f), Size(w - gap, size.height))
            x += w
        }
        if (grow < 1f) drawRect(gapColor, Offset(x, 0f), Size(size.width - x, size.height))
    }
}

@Composable
fun SquareGauge(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    val grow = rememberGrow(fraction, 250)
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        val inset = stroke / 2
        val rr = RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(size.minDimension * 0.16f))
        val full = Path().apply { addRoundRect(rr) }
        drawPath(full, track, style = Stroke(stroke))
        val measure = PathMeasure().apply { setPath(full, false) }
        val seg = Path()
        val len = measure.length
        val start = len * 0.0f
        measure.getSegment(start, start + len * fraction.coerceIn(0f, 1f) * grow, seg, true)
        drawPath(seg, color, style = Stroke(stroke, cap = StrokeCap.Butt))
    }
}

@Composable
fun DayStrip(days: List<Long>, color: Color, empty: Color, modifier: Modifier = Modifier) {
    val maxV = max(1L, days.maxOrNull() ?: 1L)
    val grow = rememberGrow(days, 300)
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val n = days.size
        val gap = 3.dp.toPx()
        val cell = min((size.width - gap * (n - 1)) / n, size.height)
        val y = (size.height - cell) / 2
        days.forEachIndexed { i, v ->
            val x = i * (cell + gap)
            drawRect(empty, Offset(x, y), Size(cell, cell))
            if (v > 0) {
                val a = (0.3f + 0.7f * v / maxV) * grow
                drawRect(color.copy(alpha = a), Offset(x, y), Size(cell, cell))
            }
        }
    }
}
