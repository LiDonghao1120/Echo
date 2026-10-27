package com.novcloud.echo.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.playback.audio.AudioSettings
import com.novcloud.echo.playback.audio.Band
import com.novcloud.echo.playback.audio.EqCaps
import com.novcloud.echo.playback.audio.EqPresets
import com.novcloud.echo.playback.audio.GainMode
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.detail.CollapsingTopBar
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.palette
import kotlin.math.roundToInt

@Composable
fun AudioEffectsScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val fx = container.audioFx
    val s by fx.settings.collectAsState()
    val caps by fx.caps.collectAsState()
    LaunchedEffect(Unit) { fx.probe() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val collapse by remember { derivedStateOf { (scroll.value / with(density) { 56.dp.toPx() }).coerceIn(0f, 1f) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(top = 56.dp, bottom = LocalBottomInset.current + 24.dp)) {
            PageTitle(stringResource(R.string.audio_effects))
            Group(stringResource(R.string.fx_equalizer)) {
                val c = caps
                when {
                    c == null -> Spacer(Modifier.height(56.dp))
                    !c.available -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fx_eq_unavailable), style = MaterialTheme.typography.bodyLarge, color = palette.muted)
                    }
                    else -> {
                        SwitchRow(stringResource(R.string.fx_equalizer), s.eqEnabled) { v -> fx.update { it.copy(eqEnabled = v) } }
                        Rule()
                        Equalizer(c, s) { change -> fx.update(change) }
                    }
                }
            }
            if (caps?.bassBoost == true) Group(stringResource(R.string.fx_bass_boost)) {
                LevelSlider(
                    value = s.bassStrength / 10f, range = 0f..100f, steps = 19,
                    label = if (s.bassStrength == 0) stringResource(R.string.fx_bass_off) else stringResource(R.string.percent, s.bassStrength / 10),
                ) { v -> fx.update { it.copy(bassStrength = (v * 10).roundToInt().coerceIn(0, 1000)) } }
            }
            Group(stringResource(R.string.fx_replaygain)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(GainMode.Off to R.string.fx_off, GainMode.Track to R.string.fx_gain_track, GainMode.Album to R.string.fx_gain_album).forEach { (m, id) ->
                        val l = stringResource(id)
                        Chip(l, s.gainMode == m, Modifier.weight(1f)) { fx.update { it.copy(gainMode = m) } }
                    }
                }
                val on = s.gainMode != GainMode.Off
                val a by animateFloatAsState(if (on) 1f else 0.4f, tween(220), label = "rg")
                Column(Modifier.alpha(a)) {
                    Rule()
                    Text(stringResource(R.string.fx_preamp), Modifier.padding(start = 16.dp, top = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    LevelSlider(
                        value = s.preampDb, range = -12f..12f, steps = 47, enabled = on,
                        label = stringResource(R.string.decibels, s.preampDb),
                    ) { v -> fx.update { it.copy(preampDb = (v * 2).roundToInt() / 2f) } }
                    Rule()
                    SwitchRow(stringResource(R.string.fx_prevent_clipping), s.clipPrevention, enabled = on) { v -> fx.update { it.copy(clipPrevention = v) } }
                }
            }
            Group(stringResource(R.string.fx_transitions)) {
                SwitchRow(stringResource(R.string.fx_fade), s.fade) { v -> fx.update { it.copy(fade = v) } }
            }
        }
        CollapsingTopBar(stringResource(R.string.audio_effects), collapse)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun LevelSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    val p = palette
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value, onChange, Modifier.weight(1f), enabled = enabled, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = p.signal, activeTrackColor = p.signal, inactiveTrackColor = p.rule, activeTickColor = p.signal, inactiveTickColor = p.rule),
        )
        Text(label, Modifier.width(76.dp), style = EchoType.numeral(15.sp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun hzLabel(hz: Int): String = when {
    hz >= 10_000 -> "${hz / 1000}K"
    hz >= 1000 -> "%.1fK".format(hz / 1000f).replace(".0K", "K")
    else -> "$hz"
}

/**
 * The curve is drawn through one square handle per device band; dragging anywhere picks the nearest band and
 * moves it, switching the preset to custom. Levels spring between presets.
 */
@Composable
private fun Equalizer(caps: EqCaps, s: AudioSettings, update: ((AudioSettings) -> AudioSettings) -> Unit) {
    val p = palette
    val levels = s.levels(caps.centersHz, caps.minMb, caps.maxMb)
    val shown = levels.mapIndexed { i, mb ->
        val anim = remember(i) { Animatable(mb.toFloat()) }
        LaunchedEffect(mb) { anim.animateTo(mb.toFloat(), spring(dampingRatio = 0.72f, stiffness = 380f)) }
        anim.value
    }
    var active by remember { mutableIntStateOf(-1) }
    val enabled = s.eqEnabled
    val dim by animateFloatAsState(if (enabled) 1f else 0.45f, tween(220), label = "eq")
    var dragLevels by remember { mutableStateOf<List<Int>?>(null) }
    val latestLevels by rememberUpdatedState(levels)
    val latestUpdate by rememberUpdatedState(update)

    fun setBand(i: Int, mb: Int) {
        val base = dragLevels ?: latestLevels
        val next = base.toMutableList().also { it[i] = mb }
        dragLevels = next
        latestUpdate { it.copy(eqEnabled = true, preset = EqPresets.CUSTOM, custom = caps.centersHz.zip(next) { hz, v -> Band(hz, v) }) }
    }

    Column(Modifier.padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            val text = if (active >= 0) stringResource(R.string.decibels, shown[active] / 100f) else presetLabel(s.preset)
            Text(text, style = EchoType.numeral(18.sp), color = if (active >= 0) p.signal else MaterialTheme.colorScheme.onSurface)
        }
        Canvas(
            Modifier.fillMaxWidth().height(196.dp).padding(horizontal = 16.dp, vertical = 14.dp).alpha(dim)
                .pointerInput(caps) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val n = caps.centersHz.size
                        val slot = size.width / n.toFloat()
                        val i = (down.position.x / slot).toInt().coerceIn(0, n - 1)
                        active = i
                        dragLevels = null
                        fun level(y: Float): Int {
                            val t = (1f - y / size.height).coerceIn(0f, 1f)
                            val mb = caps.minMb + t * (caps.maxMb - caps.minMb)
                            return ((mb / 50f).roundToInt() * 50).coerceIn(caps.minMb, caps.maxMb)
                        }
                        setBand(i, level(down.position.y))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            setBand(i, level(change.position.y))
                        }
                        active = -1
                        dragLevels = null
                    }
                },
        ) {
            val n = shown.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            fun x(i: Int) = slot * (i + 0.5f)
            fun y(mb: Float) = size.height * (1f - (mb - caps.minMb) / (caps.maxMb - caps.minMb).toFloat())
            val zero = y(0f)
            drawLine(p.rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(p.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(p.faint, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            for (i in 0 until n) drawLine(p.rule, Offset(x(i), 0f), Offset(x(i), size.height), 1.dp.toPx())
            val pts = shown.mapIndexed { i, v -> Offset(x(i), y(v)) }
            val curve = Path().apply {
                moveTo(0f, pts.first().y)
                lineTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val mid = (a.x + b.x) / 2f
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
                lineTo(size.width, pts.last().y)
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(size.width, zero)
                lineTo(0f, zero)
                close()
            }
            drawPath(fill, p.signal.copy(alpha = 0.14f))
            drawPath(curve, p.signal, style = Stroke(2.dp.toPx()))
            pts.forEachIndexed { i, o ->
                val h = 5.dp.toPx()
                drawRect(p.signal, Offset(o.x - h, o.y - h), Size(h * 2, h * 2))
                if (i == active) {
                    val r = 11.dp.toPx()
                    drawRect(p.signal, Offset(o.x - r, o.y - r), Size(r * 2, r * 2), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            caps.centersHz.forEachIndexed { i, hz ->
                Text(
                    hzLabel(hz), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = EchoType.label, color = if (i == active) p.signal else palette.muted,
                )
            }
        }
        val ids = EqPresets.all.map { it.first } + if (s.preset == EqPresets.CUSTOM || s.custom.isNotEmpty()) listOf(EqPresets.CUSTOM) else emptyList()
        val row = rememberLazyListState(initialFirstVisibleItemIndex = (ids.indexOf(s.preset) - 1).coerceAtLeast(0))
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ids) { id ->
                Chip(presetLabel(id), s.preset == id, Modifier.widthIn(min = 72.dp)) {
                    update { it.copy(eqEnabled = true, preset = id) }
                }
            }
        }
    }
}

@Composable
fun presetLabel(id: String): String = stringResource(
    when (id) {
        EqPresets.CUSTOM -> R.string.eq_custom
        "bass" -> R.string.eq_bass
        "vocal" -> R.string.eq_vocal
        "pop" -> R.string.eq_pop
        "rock" -> R.string.eq_rock
        "electronic" -> R.string.eq_electronic
        "jazz" -> R.string.eq_jazz
        "classical" -> R.string.eq_classical
        "acoustic" -> R.string.eq_acoustic
        "treble" -> R.string.eq_treble
        else -> R.string.eq_flat
    },
)
