package com.novcloud.echo.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.CoverStyle
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Grotesk
import com.novcloud.echo.ui.theme.LocalMotion
import com.novcloud.echo.ui.theme.Vermilion
import com.novcloud.echo.ui.theme.palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The album cover drawn in [style]: the plain square, a sleeve with its record, a jewel case with its disc, or a
 * frame on a strip of film. The cover square is always [Artwork], so covers load and swap the same way everywhere.
 * [spinning] turns the record or disc while this album plays; [live] plays the entry motion (off for previews).
 */
@Composable
fun AlbumCover(
    style: CoverStyle,
    art: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    live: Boolean = true,
    margin: Dp = 20.dp,
    cap: Dp = 250.dp,
    accent: Color = Color.Black,
    frame: Int = 1,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val h = if (constraints.hasBoundedHeight) maxHeight.value else Float.POSITIVE_INFINITY
        val f = CoverGeometry.of(style, maxWidth.value, h, margin.value, cap.value)
        val side = f.side.dp
        Box(Modifier.width(f.width.dp).height(f.height.dp), contentAlignment = Alignment.Center) {
            when (style) {
                CoverStyle.Classic -> Artwork(art, Modifier.size(side).shadow(side * 0.096f, EchoShape.cover, spotColor = accent), EchoShape.cover, seed, side * 0.26f)
                CoverStyle.Vinyl -> Vinyl(art, seed, side, spinning, live, accent)
                CoverStyle.Jewel -> Jewel(art, seed, side, spinning, live, accent)
                CoverStyle.Film -> Film(art, seed, side, live, frame)
            }
        }
    }
}

/** 0 → 1 once per style per page: the record slides out, the disc rises, the film advances. Static without motion. */
@Composable
private fun rememberEntry(live: Boolean, key: String, damping: Float, stiffness: Float): Animatable<Float, *> {
    val motion = LocalMotion.current
    var shown by rememberSaveable(key) { mutableStateOf(false) }
    val a = remember(key) { Animatable(if (!live || !motion || shown) 1f else 0f) }
    LaunchedEffect(key) {
        if (a.value < 1f) {
            delay(ENTRY_DELAY_MS)
            a.animateTo(1f, spring(dampingRatio = damping, stiffness = stiffness))
        }
        shown = true
    }
    return a
}

/** An angle that turns once per [periodMs] while [active], winds up and coasts down to rest where it is. */
@Composable
private fun rememberSpin(active: Boolean, periodMs: Int): FloatState {
    val motion = LocalMotion.current
    val angle = remember { mutableFloatStateOf(0f) }
    val speed = remember { Animatable(0f) }
    val on = motion && active
    LaunchedEffect(on) {
        if (on) speed.animateTo(1f, tween(SPIN_UP_MS, easing = FastOutSlowInEasing))
        else speed.animateTo(0f, tween(SPIN_DOWN_MS, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        snapshotFlow { speed.value > 0f }.collectLatest { running ->
            if (!running) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                angle.floatValue = (angle.floatValue + speed.value * 360f * ((now - last) / 1_000_000f) / periodMs) % 360f
                last = now
            }
        }
    }
    return angle
}

@Composable
private fun Vinyl(art: Any?, seed: String, side: Dp, spinning: Boolean, live: Boolean, accent: Color) {
    val out = rememberEntry(live, "vinyl", damping = 0.72f, stiffness = 120f)
    val angle = rememberSpin(spinning, VINYL_TURN_MS)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Record(
            art, seed, angle,
            Modifier.offset { IntOffset((CoverGeometry.recordShift(out.value) * side.toPx()).roundToInt(), 0) }
                .size(side * CoverGeometry.RECORD)
                .shadow(side * 0.05f, CircleShape),
        )
        Artwork(
            art,
            Modifier.offset { IntOffset((CoverGeometry.sleeveShift(out.value) * side.toPx()).roundToInt(), 0) }
                .size(side)
                .shadow(side * 0.08f, EchoShape.cover, spotColor = accent)
                .drawWithContent {
                    drawContent()
                    val edge = size.width * 0.03f
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), startX = size.width - edge, endX = size.width),
                        topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height),
                    )
                    drawRect(Color.White.copy(alpha = 0.14f), size = Size(size.width, 1.dp.toPx()))
                },
            EchoShape.cover, seed, side * 0.26f,
        )
    }
}

@Composable
private fun Record(art: Any?, seed: String, angle: FloatState, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val r = size.minDimension / 2
                val c = size.center
                val hair = 1.dp.toPx()
                val grooves = (0 until 64).map { i ->
                    val t = i / 63f
                    val wobble = ((i * 37) % 11) / 11f
                    r * (0.40f + 0.555f * t) to (0.025f + 0.04f * wobble)
                }
                val bands = listOf(0.52f, 0.63f, 0.74f, 0.86f).map { it * r }
                val body = Brush.radialGradient(
                    0f to Color(0xFF1C1C1F), 0.4f to Color(0xFF141416), 1f to Color(0xFF0B0B0C), center = c, radius = r,
                )
                val sheen = Brush.sweepGradient(
                    0f to Color.Transparent, 0.09f to Color.White.copy(alpha = 0.13f), 0.2f to Color.Transparent,
                    0.55f to Color.Transparent, 0.62f to Color.White.copy(alpha = 0.09f), 0.74f to Color.Transparent,
                    1f to Color.Transparent, center = c,
                )
                val label = r * LABEL
                onDrawWithContent {
                    drawCircle(body, r, c)
                    grooves.forEach { (gr, a) -> drawCircle(Color.White.copy(alpha = a), gr, c, style = Stroke(hair * 0.5f)) }
                    bands.forEach { b ->
                        drawCircle(Color.Black.copy(alpha = 0.7f), b, c, style = Stroke(hair * 1.6f))
                        drawCircle(Color.White.copy(alpha = 0.06f), b + hair * 1.2f, c, style = Stroke(hair * 0.6f))
                    }
                    drawCircle(sheen, r, c)
                    drawCircle(Color.White.copy(alpha = 0.16f), r - hair * 0.5f, c, style = Stroke(hair))
                    drawContent()
                    drawCircle(Color.Black.copy(alpha = 0.5f), label, c, style = Stroke(hair * 1.4f))
                    drawCircle(Vermilion.copy(alpha = 0.85f), label * 0.9f, c, style = Stroke(hair * 0.8f))
                    drawCircle(Color.White.copy(alpha = 0.35f), r * HOLE + hair, c, style = Stroke(hair))
                    drawCircle(Color.Black, r * HOLE, c, blendMode = BlendMode.Clear)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            art, Modifier.fillMaxSize(LABEL).graphicsLayer { rotationZ = angle.floatValue }, CircleShape, seed,
            iconSize = 14.dp,
        )
    }
}

@Composable
private fun Jewel(art: Any?, seed: String, side: Dp, spinning: Boolean, live: Boolean, accent: Color) {
    val out = rememberEntry(live, "jewel", damping = 0.78f, stiffness = 140f)
    val angle = rememberSpin(spinning, DISC_TURN_MS)
    val caseW = side * CoverGeometry.CASE_W
    val caseH = side * CoverGeometry.CASE_H
    val caseTop = side * (CoverGeometry.DISC * CoverGeometry.DISC_OUT)
    val m = (caseH - side) / 2
    val coverLeft = caseW - m - side
    val disc = side * CoverGeometry.DISC
    Box(Modifier.fillMaxSize()) {
        Disc(
            angle,
            Modifier.offset {
                IntOffset((coverLeft + (side - disc) / 2).roundToPx(), (CoverGeometry.discTop(out.value) * side.toPx()).roundToInt())
            }.size(disc),
        )
        Box(
            Modifier.offset(y = caseTop).size(caseW, caseH)
                .shadow(side * 0.06f, EchoShape.cover, spotColor = accent)
                .background(Color(0xFF141416), EchoShape.cover),
        )
        Artwork(art, Modifier.offset(coverLeft, caseTop + m).size(side), androidx.compose.ui.graphics.RectangleShape, seed, side * 0.26f)
        Box(
            Modifier.offset(y = caseTop).size(caseW, caseH).drawWithCache {
                val hair = 1.dp.toPx()
                val w = size.width
                val hh = size.height
                val hinge = side.toPx() * 0.075f
                val corner = CornerRadius(4.dp.toPx())
                val gloss = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.20f), 0.26f to Color.White.copy(alpha = 0.06f), 0.27f to Color.Transparent,
                    0.52f to Color.Transparent, 0.53f to Color.White.copy(alpha = 0.07f), 0.6f to Color.White.copy(alpha = 0.02f),
                    0.61f to Color.Transparent, 1f to Color.Transparent,
                    start = Offset.Zero, end = Offset(w, hh * 1.1f),
                )
                val ridges = generateSequence(hair * 2f) { it + hair * 2.4f }.takeWhile { it < hinge - hair }.toList()
                onDrawBehind {
                    drawRoundRect(Color.White.copy(alpha = 0.035f), cornerRadius = corner)
                    drawRect(Color.Black.copy(alpha = 0.28f), size = Size(hinge, hh))
                    ridges.forEach { x ->
                        drawLine(Color.White.copy(alpha = 0.16f), Offset(x, hh * 0.07f), Offset(x, hh * 0.93f), hair * 0.7f)
                        drawLine(Color.Black.copy(alpha = 0.35f), Offset(x + hair, hh * 0.07f), Offset(x + hair, hh * 0.93f), hair * 0.7f)
                    }
                    listOf(0f, hh * 0.93f).forEach { y -> drawRect(Color.White.copy(alpha = 0.09f), Offset(0f, y), Size(hinge, hh * 0.07f)) }
                    drawLine(Color.White.copy(alpha = 0.34f), Offset(hinge, 0f), Offset(hinge, hh), hair)
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(hinge + hair * 3, 0f), Offset(hinge + hair * 3, hh), hair)
                    drawRoundRect(gloss, cornerRadius = corner)
                    listOf(0.22f, 0.70f).forEach { y ->
                        drawRect(Color.White.copy(alpha = 0.18f), Offset(w - hair * 2.5f, hh * y), Size(hair * 2.5f, hh * 0.08f))
                    }
                    drawRoundRect(Color.White.copy(alpha = 0.30f), cornerRadius = corner, style = Stroke(hair))
                    drawRoundRect(
                        Color.White.copy(alpha = 0.08f), Offset(hair * 2.5f, hair * 2.5f), Size(w - hair * 5, hh - hair * 5),
                        cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(hair),
                    )
                }
            },
        )
    }
}

@Composable
private fun Disc(angle: FloatState, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val r = size.minDimension / 2
                val c = size.center
                val hair = 1.dp.toPx()
                val silver = Brush.radialGradient(
                    0.3f to Color(0xFFEEF0F2), 0.62f to Color(0xFFC4C9CF), 0.85f to Color(0xFFE2E5E9), 1f to Color(0xFFB3B9C0),
                    center = c, radius = r,
                )
                val rainbow = Brush.sweepGradient(
                    listOf(
                        Color(0x80FFB3C7), Color(0x80FFE3A3), Color(0x80B9F0C9), Color(0x80A8D7FF), Color(0x80CDB8FF),
                        Color(0x80FFB3C7), Color(0x80FFE3A3), Color(0x80A8D7FF), Color(0x80FFB3C7),
                    ),
                    center = c,
                )
                val gloss = Brush.sweepGradient(
                    0f to Color.Transparent, 0.1f to Color.White.copy(alpha = 0.55f), 0.17f to Color.Transparent,
                    0.6f to Color.Transparent, 0.66f to Color.White.copy(alpha = 0.4f), 0.73f to Color.Transparent, 1f to Color.Transparent,
                    center = c,
                )
                onDrawBehind {
                    drawCircle(silver, r, c)
                    rotate(angle.floatValue, c) { drawCircle(rainbow, r * 0.97f, c) }
                    drawCircle(gloss, r, c)
                    drawCircle(Color(0xFF8E959D).copy(alpha = 0.6f), r * 0.97f, c, style = Stroke(hair))
                    drawCircle(Color.White.copy(alpha = 0.9f), r - hair * 0.5f, c, style = Stroke(hair))
                    drawCircle(Color.Black, r * 0.36f, c, blendMode = BlendMode.Clear)
                    drawCircle(Color.White.copy(alpha = 0.22f), r * 0.36f, c)
                    drawCircle(Color(0xFF8E959D).copy(alpha = 0.7f), r * 0.36f, c, style = Stroke(hair))
                    drawCircle(Color.White.copy(alpha = 0.5f), r * 0.28f, c, style = Stroke(r * 0.025f))
                    drawCircle(Color.Black, r * 0.125f, c, blendMode = BlendMode.Clear)
                    drawCircle(Color(0xFF8E959D).copy(alpha = 0.7f), r * 0.125f, c, style = Stroke(hair))
                }
            },
    )
}

@Composable
private fun Film(art: Any?, seed: String, side: Dp, live: Boolean, frame: Int) {
    val advance = rememberEntry(live, "film", damping = 0.86f, stiffness = 90f)
    val text = rememberTextMeasurer()
    val density = LocalDensity.current
    val edge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = with(density) { (side * 0.046f).toSp() }, letterSpacing = with(density) { (side * 0.004f).toSp() })
    val ink = Vermilion.copy(alpha = 0.88f)
    val n = (frame - 1).mod(36) + 1
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                .drawWithCache {
                    val s = side.toPx()
                    val pitch = s * (1 + CoverGeometry.FILM_GAP)
                    val rail = s * CoverGeometry.FILM_RAIL
                    val holeW = s * 0.062f
                    val holeH = s * 0.085f
                    val holePitch = pitch / 5
                    val holeTop = s * 0.03f
                    val corner = CornerRadius(s * 0.012f)
                    val showText = side >= 120.dp
                    val marks = if (!showText) emptyList() else (-3..3).map { k ->
                        Triple(k, text.measure("${(n + k - 1).mod(36) + 1}", edge), text.measure("${(n + k - 1).mod(36) + 1}A", edge))
                    }
                    val brand = if (showText) text.measure("ECHO 400", edge) else null
                    val arrow = Path()
                    onDrawBehind {
                        val cx = size.width / 2
                        val dx = (1 - advance.value) * pitch
                        val h = size.height
                        drawRect(Color(0xFF15120F))
                        drawRect(Color.White.copy(alpha = 0.05f), size = Size(size.width, 1.dp.toPx()))
                        drawRect(Color.White.copy(alpha = 0.05f), Offset(0f, h - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                        for (k in -3..3) {
                            if (k == 0) continue
                            val left = cx + dx + k * pitch - s / 2
                            if (left > size.width || left + s < 0) continue
                            drawRect(
                                Brush.verticalGradient(listOf(Color(0xFF2B251F), Color(0xFF221D18)), startY = rail, endY = rail + s),
                                Offset(left, rail), Size(s, s),
                            )
                        }
                        val firstHole = ((-(cx + dx)) / holePitch).toInt() - 1
                        var i = firstHole
                        while (true) {
                            val x = cx + dx + i * holePitch - holeW / 2 + holePitch / 2
                            if (x > size.width) break
                            drawRoundRect(Color.Black, Offset(x, holeTop), Size(holeW, holeH), corner, blendMode = BlendMode.Clear)
                            drawRoundRect(Color.Black, Offset(x, h - holeTop - holeH), Size(holeW, holeH), corner, blendMode = BlendMode.Clear)
                            i++
                        }
                        if (showText) {
                            val bandTop = holeTop + holeH + (rail - holeTop - holeH) / 2
                            val bandBottom = h - bandTop
                            marks.forEach { (k, num, half) ->
                                val left = cx + dx + k * pitch - s / 2
                                drawText(num, ink, Offset(left + s * 0.08f, bandTop - num.size.height / 2f))
                                val ax = left + s * 0.08f + num.size.width + s * 0.03f
                                arrow.reset()
                                arrow.moveTo(ax, bandTop - s * 0.016f)
                                arrow.lineTo(ax + s * 0.026f, bandTop)
                                arrow.lineTo(ax, bandTop + s * 0.016f)
                                arrow.close()
                                drawPath(arrow, ink)
                                drawText(half, ink, Offset(left + s + (pitch - s) / 2 - half.size.width / 2f, bandTop - half.size.height / 2f))
                                if (brand != null && (k + n).mod(2) == 0) {
                                    drawText(brand, ink, Offset(left + s * 0.5f - brand.size.width / 2f, bandBottom - brand.size.height / 2f))
                                } else {
                                    drawText(num, ink, Offset(left + s * 0.5f - num.size.width / 2f, bandBottom - num.size.height / 2f))
                                }
                            }
                        }
                    }
                },
        )
        Artwork(
            art,
            Modifier.offset { IntOffset(((1 - advance.value) * side.toPx() * (1 + CoverGeometry.FILM_GAP)).roundToInt(), 0) }
                .size(side)
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = 0.45f), style = Stroke(1.dp.toPx()))
                },
            androidx.compose.ui.graphics.RectangleShape, seed, side * 0.26f,
        )
    }
}

@Composable
fun coverStyleLabel(style: CoverStyle): String = stringResource(
    when (style) {
        CoverStyle.Classic -> R.string.cover_style_classic
        CoverStyle.Vinyl -> R.string.cover_style_vinyl
        CoverStyle.Jewel -> R.string.cover_style_jewel
        CoverStyle.Film -> R.string.cover_style_film
    },
)

/** The four styles side by side, each drawn live with [art]; a tap applies the style at once. */
@Composable
fun CoverStylePicker(selected: CoverStyle, art: Any?, seed: String, hint: Boolean, onPick: (CoverStyle) -> Unit) {
    val p = palette
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.cover_style), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black))
        if (hint) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.cover_style_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Spacer(Modifier.height(16.dp))
        CoverStyle.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { style ->
                    val on = style == selected
                    Column(
                        Modifier.weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, EchoShape.card)
                            .border(if (on) 2.dp else 1.dp, if (on) p.signal else p.rule, EchoShape.card)
                            .clickable(role = Role.RadioButton) {
                                if (!on) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(style)
                            }
                            .semantics { this.selected = on },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.3f).padding(vertical = 12.dp).graphicsLayer { clip = true }) {
                            AlbumCover(style, art, seed, Modifier.fillMaxSize(), live = false, margin = 12.dp, cap = 400.dp)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                coverStyleLabel(style), Modifier.weight(1f), style = EchoType.labelBold,
                                color = if (on) p.signal else MaterialTheme.colorScheme.onSurface, maxLines = 1,
                            )
                            if (on) Box(Modifier.size(8.dp).background(p.signal))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The style picker as a bottom sheet; the choice is saved and every album page follows it. */
@Composable
fun CoverStyleSheet(art: Any?, seed: String, hint: Boolean, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val style by container.coverStyle.collectAsState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = EchoShape.card,
    ) {
        CoverStylePicker(style, art, seed, hint) { scope.launch { container.prefs.setCoverStyle(it) } }
    }
}

private const val LABEL = 0.36f
private const val HOLE = 0.024f
private const val VINYL_TURN_MS = 5_000
private const val DISC_TURN_MS = 4_000
private const val SPIN_UP_MS = 900
private const val SPIN_DOWN_MS = 1_800
private const val ENTRY_DELAY_MS = 240L
