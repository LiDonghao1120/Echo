package com.novcloud.echo.ui.player

import android.graphics.BlurMaskFilter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.novcloud.echo.data.lyrics.LineTiming
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.Vermilion
import kotlin.math.floor

object WordFill {
    val Base = Color.White.copy(alpha = 0.34f)
    val Fill = Paper
    val Edge = lerp(Vermilion, Paper, 0.18f)
    val Glow = Vermilion
}

/**
 * A lyric line sung word by word. Layout happens once; each frame only redraws: the muted line, the sung part per
 * visual line with a soft cream-to-vermilion leading edge, and the word being sung lifted with a glow.
 * [time] is read during drawing, so the clock never recomposes anything.
 */
@Composable
fun KaraokeText(text: String, style: TextStyle, timing: LineTiming, time: () -> Long, modifier: Modifier = Modifier) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = WordFill.Base,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithCache {
            val l = layout
            val edge = 22.dp.toPx()
            val lift = 3.dp.toPx()
            val glowRadius = 10.dp.toPx()
            val words = if (l == null) emptyList() else timing.segments.map { s -> wordShape(l, s.start, s.end) }
            val glowPaint = Paint().apply {
                color = WordFill.Glow
                asFrameworkPaint().maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            }
            onDrawWithContent {
                if (l == null) { drawContent(); return@onDrawWithContent }
                val t = time()
                val pos = timing.position(t)
                val raised = ArrayList<Pair<Int, Float>>(3)
                timing.segments.forEachIndexed { i, s ->
                    val e = envelope(t, s.t0, s.t1)
                    if (e > 0.01f && words[i] != null) raised += i to e
                }
                val hole = Path()
                raised.forEach { (i, _) -> hole.addRect(words[i]!!.box) }
                clipPath(hole, ClipOp.Difference) { drawLine(l, pos, edge) }
                raised.forEach { (i, e) ->
                    val w = words[i]!!
                    translate(top = -lift * e) {
                        drawIntoCanvas { c ->
                            glowPaint.alpha = 0.42f * e
                            c.drawPath(w.path, glowPaint)
                        }
                        clipRect(w.box.left, w.box.top, w.box.right, w.box.bottom) { drawLine(l, pos, edge) }
                    }
                }
            }
        },
    )
}

private class WordShape(val box: Rect, val path: Path)

private fun wordShape(l: TextLayoutResult, start: Int, end: Int): WordShape? {
    if (start >= end || end > l.layoutInput.text.length) return null
    val line = l.getLineForOffset(start)
    if (l.getLineForOffset(end - 1) != line) return null
    val a = l.getBoundingBox(start)
    val b = l.getBoundingBox(end - 1)
    val box = Rect(minOf(a.left, b.left), l.getLineTop(line), maxOf(a.right, b.right), l.getLineBottom(line))
    return WordShape(box, l.getPathForRange(start, end))
}

/** Rises as the word starts, holds while it is sung, settles shortly after it ends. */
private fun envelope(t: Long, t0: Long, t1: Long): Float {
    val rise = ((t - t0 + 60) / 160f).coerceIn(0f, 1f)
    val fall = ((t1 + 240 - t) / 240f).coerceIn(0f, 1f)
    val x = minOf(rise, fall)
    return x * x * (3 - 2 * x)
}

private fun DrawScope.drawLine(l: TextLayoutResult, pos: Float, edge: Float) {
    drawText(l, color = WordFill.Base)
    if (pos <= 0f) return
    val n = l.layoutInput.text.length
    for (line in 0 until l.lineCount) {
        val s = l.getLineStart(line)
        if (pos <= s) break
        val e = l.getLineEnd(line, visibleEnd = true)
        val x = if (pos >= e) l.getLineRight(line) + edge else {
            val i = floor(pos).toInt().coerceIn(0, n - 1)
            val box = l.getBoundingBox(i)
            box.left + (box.right - box.left) * (pos - i)
        }
        clipRect(0f, l.getLineTop(line), size.width, l.getLineBottom(line)) {
            drawText(
                l,
                brush = Brush.horizontalGradient(
                    0f to WordFill.Fill, 0.5f to WordFill.Edge, 1f to WordFill.Edge.copy(alpha = 0f),
                    startX = x - edge * 0.5f, endX = x + edge * 0.5f,
                ),
            )
        }
    }
}
