package com.novcloud.echo.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.novcloud.echo.EchoApp
import com.novcloud.echo.R
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.domain.Ranked
import com.novcloud.echo.domain.Stats
import com.novcloud.echo.domain.StatsEngine
import com.novcloud.echo.domain.StatsRange
import com.novcloud.echo.domain.TrendPoint
import com.novcloud.echo.ui.components.AnimatedNumber
import com.novcloud.echo.ui.components.ArtistNameMark
import com.novcloud.echo.ui.components.ArtistThumb
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.EchoMark
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.Eyebrow
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.LogoScrim
import com.novcloud.echo.ui.components.count
import com.novcloud.echo.ui.components.echoClick
import com.novcloud.echo.ui.components.formatDate
import com.novcloud.echo.ui.components.locale
import com.novcloud.echo.ui.components.number
import com.novcloud.echo.ui.components.rememberArtistLogo
import com.novcloud.echo.ui.components.rememberArtistPoster
import com.novcloud.echo.ui.components.reveal
import com.novcloud.echo.ui.theme.Cobalt
import com.novcloud.echo.ui.theme.EchoShape
import com.novcloud.echo.ui.theme.EchoType
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import com.novcloud.echo.ui.theme.Sand
import com.novcloud.echo.ui.theme.Vermilion
import com.novcloud.echo.ui.theme.palette
import com.novcloud.echo.ui.theme.seededColor
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val riso = listOf(Vermilion, Cobalt, Sand, Color(0xFFE8B931), Color(0xFF2E8B6E), Color(0xFFD9475E))

@Composable
fun StatsScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val events by container.allEvents.collectAsState()
    val lib by container.library.library.collectAsState()
    var range by rememberSaveable { mutableStateOf(StatsRange.Week) }
    val stats by produceState<Stats?>(null, events, lib, range) {
        value = withContext(Dispatchers.Default) { StatsEngine.compute(events, lib, range) }
    }
    StatsReport(stats, range) { range = it }
}

@Composable
fun StatsReport(stats: Stats?, range: StatsRange, today: LocalDate = LocalDate.now(), onRange: (StatsRange) -> Unit) {
    val p = palette
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 28.dp)) {
        item(key = "mast") {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp).reveal(0)) {
                Eyebrow(rangeLine(range, today))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.stats_title), maxLines = 1, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp))
                Spacer(Modifier.height(14.dp))
                RangeTabs(range, onRange)
            }
        }
        val s = stats ?: return@LazyColumn
        if (s.empty) {
            item { EmptyState(null, stringResource(R.string.stats_empty), Modifier.padding(top = 40.dp)) }
            return@LazyColumn
        }
        item(key = "poster") { Box(Modifier.reveal(1)) { ReportPoster(s, range, today) } }
        item(key = "rhythm") {
            Chapter(1, stringResource(R.string.stats_rhythm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HourDial(s.hourly, Vermilion, Cobalt, p.rule, MaterialTheme.colorScheme.onSurface, Modifier.size(168.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stats_peak_hour), style = MaterialTheme.typography.labelMedium, color = p.muted)
                        Text(formatDate(java.time.LocalTime.of(s.peakHour, 0), R.string.time_peak_hour), style = EchoType.poster(48.sp), maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Text(periodName(s.peakHour), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black))
                        Text(stringArrayResource(R.array.stats_peak_weekday)[s.peakWeekday], style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    }
                }
            }
        }
        item(key = "trend") {
            Chapter(2, stringResource(R.string.stats_trend)) {
                RidgeChart(s.trend, trendLabels(s.trend, range), Vermilion, Cobalt, p.muted, MaterialTheme.colorScheme.onSurface)
            }
        }
        item(key = "week") {
            Chapter(3, stringResource(R.string.stats_week)) {
                TallyWeek(s.weekday, stringArrayResource(R.array.weekdays_short).toList(), p.signal, p.card, p.muted)
            }
        }
        if (s.topArtists.isNotEmpty()) item(key = "artists") { Chapter(4, stringResource(R.string.stats_top_artists)) { TopArtists(s.topArtists) } }
        if (s.topSongs.isNotEmpty()) item(key = "songs") { Chapter(5, stringResource(R.string.stats_on_repeat)) { TopSongs(s.topSongs) } }
        if (s.topAlbums.isNotEmpty()) item(key = "albums") { TopAlbums(s.topAlbums) }
        if (s.topGenres.isNotEmpty()) item(key = "genres") { Chapter(7, stringResource(R.string.stats_genres)) { Genres(s.topGenres) } }
        item(key = "habit") { Chapter(8, stringResource(R.string.stats_habits)) { Habits(s) } }
    }
}

@Composable
private fun rangeLine(range: StatsRange, today: LocalDate): String {
    val d = range.days ?: return stringResource(R.string.stats_all_time)
    val from = today.minusDays(d - 1)
    val pattern = if (from.year == today.year) R.string.date_range_day else R.string.date_range_day_year
    return stringResource(R.string.date_range, formatDate(from, pattern), formatDate(today, pattern))
}

@Composable
private fun periodName(h: Int): String = stringArrayResource(R.array.stats_periods)[
    when (h) {
        in 5..8 -> 0
        in 9..11 -> 1
        in 12..13 -> 2
        in 14..17 -> 3
        in 18..22 -> 4
        else -> 5
    }
]

@Composable
private fun trendLabels(points: List<TrendPoint>, range: StatsRange): List<String> {
    val res = LocalContext.current.resources
    val weekdays = stringArrayResource(R.array.weekdays_short)
    return remember(points, range, res.locale) {
        points.map { pt ->
            when {
                pt.monthly -> res.formatDate(pt.date, R.string.date_trend_month)
                range == StatsRange.Week -> weekdays[pt.date.dayOfWeek.value - 1]
                else -> res.formatDate(pt.date, R.string.date_trend_day)
            }
        }
    }
}

@Composable
private fun rangeLabel(r: StatsRange): String = stringResource(
    when (r) {
        StatsRange.Week -> R.string.stats_range_week
        StatsRange.Month -> R.string.stats_range_month
        StatsRange.Year -> R.string.stats_range_year
        StatsRange.All -> R.string.stats_range_all
    },
)

@Composable
private fun RangeTabs(range: StatsRange, onChange: (StatsRange) -> Unit) {
    val p = palette
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
        val w = maxWidth / StatsRange.entries.size
        val x by animateDpAsState(w * range.ordinal, spring(dampingRatio = 0.7f, stiffness = 480f), label = "seg")
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(p.rule))
        Box(Modifier.align(Alignment.BottomStart).offset(x = x).width(w).height(3.dp).background(p.signal))
        Row(Modifier.fillMaxSize()) {
            StatsRange.entries.forEach { r ->
                val c by animateColorAsState(if (r == range) MaterialTheme.colorScheme.onSurface else p.faint, label = "rc")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onChange(r) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(rangeLabel(r), maxLines = 1, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (r == range) FontWeight.Black else FontWeight.Medium), color = c)
                }
            }
        }
    }
}

@Composable
private fun ReportPoster(s: Stats, range: StatsRange, today: LocalDate) {
    val minutes = s.totalMs / 60_000
    val hours = minutes / 60
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth().clip(EchoShape.card).background(Ink).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EchoMark(Modifier.size(18.dp), color = Paper, core = Vermilion)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = Paper)
            Spacer(Modifier.weight(1f))
            Text(rangeLine(range, today), style = EchoType.label, color = Paper.copy(alpha = 0.55f))
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Paper.copy(alpha = 0.14f)))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(if (hours > 0) hours else minutes, style = EchoType.poster(132.sp), color = Paper)
            Column(Modifier.padding(start = 10.dp, bottom = 18.dp)) {
                Text(
                    if (hours > 0) pluralStringResource(R.plurals.label_hours, hours.toInt()) else pluralStringResource(R.plurals.label_minutes, minutes.toInt()),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Paper,
                )
                if (hours > 0) Text(stringResource(R.string.duration_minutes, number((minutes % 60).toInt())), style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = 0.6f))
            }
            Spacer(Modifier.weight(1f))
            if (range.days != null && s.previousMs > 0) {
                val pct = ((s.totalMs - s.previousMs) * 100 / s.previousMs)
                Box(
                    Modifier.padding(bottom = 26.dp).graphicsLayer { rotationZ = -6f }
                        .background(if (pct >= 0) Vermilion else Cobalt).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text((if (pct >= 0) "+" else "−") + "${kotlin.math.abs(pct)}%", style = EchoType.numeral(15.sp), color = Ink)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            PosterFigure(s.plays.toLong(), pluralStringResource(R.plurals.label_plays, s.plays), Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Paper.copy(alpha = 0.14f)))
            PosterFigure(s.songs.toLong(), pluralStringResource(R.plurals.label_songs_poster, s.songs), Modifier.weight(1f).padding(start = 14.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Paper.copy(alpha = 0.14f)))
            PosterFigure(s.artists.toLong(), pluralStringResource(R.plurals.label_artists, s.artists), Modifier.weight(1f).padding(start = 14.dp))
        }
        Spacer(Modifier.height(18.dp))
        DayStrip(s.recentDays, Vermilion, Paper.copy(alpha = 0.08f))
        Spacer(Modifier.height(8.dp))
        Row {
            Text(stringResource(R.string.stats_last_28_days), style = EchoType.label, color = Paper.copy(alpha = 0.45f))
            Spacer(Modifier.weight(1f))
            Text(count(R.plurals.stats_days_with_music, s.activeDays), style = EchoType.label, color = Paper.copy(alpha = 0.45f))
        }
    }
}

@Composable
private fun PosterFigure(value: Long, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        AnimatedNumber(value, style = EchoType.poster(34.sp), color = Paper)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Paper.copy(alpha = 0.55f), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Chapter(index: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 26.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%02d".format(index), style = EchoType.poster(30.sp), color = p.signal)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), modifier = Modifier.padding(bottom = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun TopArtists(list: List<Ranked>) {
    val actions = LocalActions.current
    val p = palette
    val first = list.first()
    val poster = rememberArtistPoster(first.key)
    val logo = rememberArtistLogo(first.key)
    val max = first.ms.coerceAtLeast(1)
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(1.25f).echoClick { actions.openArtist(first.key) }
            .clip(EchoShape.card).background(lerp(seededColor(first.key), Ink, 0.5f)),
    ) {
        if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0.35f), 0.35f to Color.Transparent, 0.6f to Color.Transparent, 1f to Ink.copy(alpha = 0.9f))))
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(maxHeight * 0.5f)) { LogoScrim(logo, 0.8f) }
        Text(stringResource(R.string.stats_number_one), Modifier.align(Alignment.TopStart).padding(16.dp), style = EchoType.poster(40.sp), color = Vermilion)
        ArtistNameMark(first.key, logo, maxWidth - 48.dp, maxHeight * 0.3f, Modifier.align(Alignment.Center), textSize = 52.sp, minTextSize = 22.sp)
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
            Text(shortDuration(first.ms), style = EchoType.poster(30.sp), color = Paper)
            Spacer(Modifier.width(10.dp))
            Text(count(R.plurals.plays_count_long, first.plays), style = MaterialTheme.typography.labelMedium, color = Paper.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    list.drop(1).take(5).forEachIndexed { i, r ->
        Row(
            Modifier.fillMaxWidth().clickable { actions.openArtist(r.key) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 2}", Modifier.width(30.dp), style = EchoType.poster(24.sp), color = p.faint)
            ArtistThumb(rememberArtistPoster(r.key), r.key, Modifier.size(42.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayArtist(r.title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortDuration(r.ms), style = EchoType.numeral(12.sp), color = p.muted)
                }
                Spacer(Modifier.height(6.dp))
                val grow = rememberGrow(r.key, 200 + i * 80)
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.card)) {
                    Box(Modifier.fillMaxWidth(r.ms.toFloat() / max * grow).height(3.dp).background(p.signal.copy(alpha = 0.85f - i * 0.1f)))
                }
            }
        }
    }
}

@Composable
private fun TopSongs(list: List<Ranked>) {
    val actions = LocalActions.current
    val lib by actions.repo.library.collectAsState()
    val p = palette
    list.take(8).forEachIndexed { i, r ->
        val song = r.key.toLongOrNull()?.let { lib.songById[it] }
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = song != null) { song?.let { actions.player.playSong(it, list.mapNotNull { x -> x.key.toLongOrNull()?.let { id -> lib.songById[id] } }) } }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%02d".format(i + 1), Modifier.width(44.dp), style = EchoType.poster(if (i == 0) 34.sp else 26.sp), color = if (i < 3) p.signal else p.faint)
            Artwork(r.art, Modifier.size(if (i == 0) 58.dp else 46.dp), EchoShape.cover, r.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = (if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(displayArtist(r.subtitle), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
            }
            Text(number(r.plays), style = EchoType.numeral(16.sp))
            Text(pluralStringResource(R.plurals.unit_plays, r.plays), style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
    }
}

@Composable
private fun TopAlbums(list: List<Ranked>) {
    val actions = LocalActions.current
    Column(Modifier.padding(top = 26.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("06", style = EchoType.poster(30.sp), color = palette.signal)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.stats_shelf), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
            Spacer(Modifier.height(16.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(list, key = { _, r -> r.key }) { i, r ->
                Column(Modifier.width(136.dp).echoClick(corner = 4.dp) { r.key.toLongOrNull()?.let(actions::openAlbum) }) {
                    Box {
                        Artwork(r.art, Modifier.size(136.dp), EchoShape.cover, r.title)
                        Text(
                            "${i + 1}", Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = 14.dp).padding(start = 8.dp),
                            style = EchoType.poster(56.sp).copy(shadow = androidx.compose.ui.graphics.Shadow(Ink.copy(alpha = 0.6f), blurRadius = 16f)), color = Paper,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortDuration(r.ms), style = EchoType.numeral(12.sp), color = palette.muted)
                }
            }
        }
    }
}

@Composable
private fun Genres(list: List<Ranked>) {
    val total = list.sumOf { it.ms }.coerceAtLeast(1)
    val p = palette
    GenreBand(list.map { it.ms }, riso, p.card)
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEachIndexed { i, g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(riso[i % riso.size]))
                Spacer(Modifier.width(6.dp))
                Text(g.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text("${g.ms * 100 / total}%", style = EchoType.numeral(12.sp), color = p.muted)
            }
        }
    }
}

@Composable
private fun Habits(s: Stats) {
    val p = palette
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(s.currentStreak.toLong(), style = EchoType.poster(56.sp))
                Text(pluralStringResource(R.plurals.unit_days, s.currentStreak), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(stringResource(R.string.stats_streak), style = MaterialTheme.typography.labelMedium, color = p.muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(minOf(s.longestStreak, 14)) { i ->
                    Box(Modifier.size(width = 6.dp, height = 18.dp).background(if (i < s.currentStreak) p.signal else p.card))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(count(R.plurals.stats_longest_streak, s.longestStreak), style = EchoType.label, color = p.faint)
        }
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            SquareGauge(s.completionRate, Cobalt, p.card, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedNumber((s.completionRate * 100).toLong(), format = { "$it%" }, style = EchoType.poster(30.sp))
                Text(stringResource(R.string.stats_completion), style = MaterialTheme.typography.labelSmall, color = p.muted)
            }
        }
    }
}
