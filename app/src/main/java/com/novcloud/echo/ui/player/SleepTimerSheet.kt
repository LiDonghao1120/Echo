package com.novcloud.echo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.R
import com.novcloud.echo.playback.SleepState
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.theme.EchoMotion
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val presets = listOf(15, 30, 45, 60, 90)

/** Remaining time of the sleep timer, ticking once a second; null when no countdown is running. */
@Composable
fun rememberSleepRemaining(state: SleepState): Long? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.deadline) {
        while (state.deadline > 0) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return if (state.deadline > 0) (state.deadline - now).coerceAtLeast(0) else null
}

fun formatCountdown(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val state by actions.player.sleep.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(minutes: Int) {
        actions.player.setSleep(minutes)
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card) {
        SleepTimerContent(state, rememberSleepRemaining(state), ::pick) { actions.player.setSleep(0) }
    }
}

@Composable
fun SleepTimerContent(state: SleepState, remaining: Long?, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val p = palette
    var custom by remember { mutableStateOf(false) }
    Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        Eyebrow(stringResource(R.string.sleep_timer))
        Spacer(Modifier.height(14.dp))
        AnimatedVisibility(state.active, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = when {
                    remaining != null && state.totalMs > 0 -> remaining.toFloat() / state.totalMs
                    else -> 1f
                }
                EchoCountdownMark(fraction, Modifier.size(64.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (remaining != null) {
                        Text(formatCountdown(remaining), style = EchoType.poster(40.sp), color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.sleep_until_pause), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    } else {
                        Text(stringResource(R.string.sleep_end_of_song_state), style = EchoType.heavy(28.sp), color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.sleep_end_of_track_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                }
                Text(
                    stringResource(R.string.sleep_turn_off), style = MaterialTheme.typography.labelLarge, color = p.signal,
                    modifier = Modifier.echoClick(corner = 4.dp, onClick = onCancel).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.take(3).forEach { m -> MinuteTile(m, state.totalMs == m * 60_000L, Modifier.weight(1f)) { onPick(m) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.drop(3).forEach { m -> MinuteTile(m, state.totalMs == m * 60_000L, Modifier.weight(1f)) { onPick(m) } }
            Tile(Modifier.weight(1f), selected = custom, onClick = { custom = !custom }) { fg ->
                Text(stringResource(R.string.sleep_custom), style = EchoType.heavy(22.sp), color = fg, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sleep_any_length), style = EchoType.label.copy(letterSpacing = 0.6.sp), color = fg.copy(alpha = 0.6f))
            }
        }
        AnimatedVisibility(custom, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            CustomMinutes(onPick)
        }
        Spacer(Modifier.height(8.dp))
        Tile(Modifier.fillMaxWidth(), selected = state.endOfItem, height = 56.dp, onClick = { onPick(SleepState.END_OF_ITEM) }) { fg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, Modifier.size(20.dp), tint = fg)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sleep_end_of_track), style = MaterialTheme.typography.titleSmall, color = fg)
            }
        }
    }
}

@Composable
private fun MinuteTile(minutes: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile(modifier, selected = selected, onClick = onClick) { fg ->
        Text(minutes.toString(), style = EchoType.poster(30.sp), color = fg)
        Spacer(Modifier.height(4.dp))
        Text(pluralStringResource(R.plurals.label_minutes, minutes), style = EchoType.label.copy(letterSpacing = 0.6.sp), color = fg.copy(alpha = 0.6f))
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    selected: Boolean = false,
    height: Dp = 78.dp,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val p = palette
    val bg by animateColorAsState(if (selected) p.signal else MaterialTheme.colorScheme.surfaceContainerHigh, tween(EchoMotion.Standard), label = "tile")
    val fg = if (selected) p.onSignal else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.height(height).echoClick(corner = 6.dp, color = p.signal, onClick = onClick).clip(EchoShape.card).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content(fg) }
    }
}

/** 回 mark as a countdown: the outer square's outline drains clockwise from the top-left corner, the core stays. */
@Composable
fun EchoCountdownMark(fraction: Float, modifier: Modifier = Modifier) {
    val p = palette
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "drain")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2
        val side = size.minDimension - stroke
        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(inset + side, inset)
            lineTo(inset + side, inset + side)
            lineTo(inset, inset + side)
            close()
        }
        drawPath(path, track, style = Stroke(stroke))
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * f, part, true)
        drawPath(part, p.signal, style = Stroke(stroke, cap = StrokeCap.Square))
        val core = side * 0.34f
        drawRect(p.signal, Offset((size.width - core) / 2, (size.height - core) / 2), Size(core, core))
    }
}

@Composable
private fun CustomMinutes(onPick: (Int) -> Unit) {
    val p = palette
    var value by remember { mutableFloatStateOf(25f) }
    val minutes = value.roundToInt()
    val haptic = LocalHapticFeedback.current
    var last by remember { mutableStateOf(minutes) }
    LaunchedEffect(minutes) {
        if (minutes != last && minutes % 5 == 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        last = minutes
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = EchoType.label.copy(color = p.muted)
    val tick = p.faint
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(minutes.toString(), style = EchoType.poster(44.sp), color = onSurface)
            Spacer(Modifier.width(8.dp))
            Text(pluralStringResource(R.plurals.label_minutes, minutes), Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = p.muted)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.echoClick(corner = 4.dp, color = p.signal) { onPick(minutes) }.clip(EchoShape.card).background(p.signal)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.sleep_start), style = MaterialTheme.typography.labelLarge, color = p.onSignal) }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier.fillMaxWidth().height(54.dp).pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    value = (value - dx / 9.dp.toPx()).coerceIn(MIN_CUSTOM.toFloat(), MAX_CUSTOM.toFloat())
                }
            },
        ) {
            val step = 9.dp.toPx()
            val center = size.width / 2
            val first = (value - center / step).toInt() - 1
            val last2 = (value + center / step).toInt() + 1
            for (m in first..last2) {
                if (m < MIN_CUSTOM || m > MAX_CUSTOM) continue
                val x = center + (m - value) * step
                val major = m % 15 == 0
                val mid = m % 5 == 0
                val h = if (major) 26.dp.toPx() else if (mid) 18.dp.toPx() else 10.dp.toPx()
                drawRect(tick, Offset(x - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), h))
                if (major) {
                    val text = measurer.measure(m.toString(), labelStyle)
                    drawText(text, topLeft = Offset(x - text.size.width / 2f, size.height - text.size.height))
                }
            }
            drawRect(p.signal, Offset(center - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), 34.dp.toPx()))
        }
    }
}

private const val MIN_CUSTOM = 1
private const val MAX_CUSTOM = 240
