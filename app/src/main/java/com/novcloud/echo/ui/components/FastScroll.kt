package com.novcloud.echo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.data.Pinyin
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlin.math.roundToInt

val RAIL_LETTERS: List<Char> = ('A'..'Z').toList() + '#'

/** First list index of every section present in [sortKeys] (already in sort order). */
fun sectionStarts(sortKeys: List<String>): Map<Char, Int> {
    val out = LinkedHashMap<Char, Int>()
    sortKeys.forEachIndexed { i, k -> out.putIfAbsent(Pinyin.section(k), i) }
    return out
}

/**
 * A–Z index along the right edge. Touching or dragging jumps to the section under the finger (or the next one that
 * has items) with a tick, and a large 回-framed letter follows the finger. [current] is the section in view.
 */
@Composable
fun BoxScope.AlphabetRail(
    sections: Map<Char, Int>,
    current: Char?,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.size < 2) return
    val p = palette
    val haptic = LocalHapticFeedback.current
    var height by remember { mutableFloatStateOf(1f) }
    var touchY by remember { mutableFloatStateOf(-1f) }
    var bubbleY by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf<Char?>(null) }
    val pressed = touchY >= 0
    val shown by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium), label = "bubble")

    fun hit(y: Float) {
        touchY = y.coerceIn(0f, height)
        bubbleY = touchY
        val i = (touchY / height * RAIL_LETTERS.size).toInt().coerceIn(0, RAIL_LETTERS.lastIndex)
        val letter = RAIL_LETTERS.drop(i).firstOrNull { it in sections } ?: RAIL_LETTERS.take(i).lastOrNull { it in sections } ?: return
        if (letter != active) {
            active = letter
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            sections[letter]?.let(onJump)
        }
    }

    var railTop by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 6.dp, bottom = LocalBottomInset.current + 6.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(26.dp)
                .heightIn(max = 16.dp * RAIL_LETTERS.size)
                .fillMaxHeight()
                .onPlaced { railTop = it.positionInParent().y }
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(sections) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        active = null
                        hit(down.position.y)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (c.positionChange().y != 0f) hit(c.position.y)
                            c.consume()
                        }
                        touchY = -1f
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RAIL_LETTERS.forEach { l ->
                val on = l == (if (pressed) active else current)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        l.toString(),
                        style = EchoType.labelBold.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                        color = when {
                            on -> p.signal
                            l in sections -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            else -> p.faint.copy(alpha = 0.5f)
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Bubble(active, shown, railTop + bubbleY)
    }
}

@Composable
private fun Bubble(letter: Char?, shown: Float, centerY: Float) {
    val p = palette
    val density = LocalDensity.current
    if (letter != null && shown > 0.01f) {
        val size = 72.dp
        val y = with(density) { (centerY - size.toPx() / 2).roundToInt() }
        Box(
            Modifier
                .offset { IntOffset(-with(density) { 40.dp.roundToPx() }, y) }
                .graphicsLayer {
                    alpha = shown.coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * shown
                    scaleY = 0.6f + 0.4f * shown
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                }
                .size(size)
                .background(p.signal, EchoShape.card)
                .padding(7.dp)
                .border(1.5.dp, p.onSignal.copy(alpha = 0.35f), EchoShape.chip),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                letter,
                transitionSpec = {
                    val down = targetState > initialState
                    (slideInVertically(tween(EchoMotion.Quick)) { if (down) it / 2 else -it / 2 } + fadeIn(tween(EchoMotion.Quick))) togetherWith
                        (slideOutVertically(tween(EchoMotion.Quick)) { if (down) -it / 2 else it / 2 } + fadeOut(tween(EchoMotion.Quick)))
                },
                label = "railLetter",
            ) { c ->
                Text(c.toString(), style = EchoType.poster(38.sp), color = p.onSignal)
            }
        }
    }
}
