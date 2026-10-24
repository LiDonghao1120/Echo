package com.novcloud.echo.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.displayArtist
import com.novcloud.echo.domain.Ranked
import com.novcloud.echo.domain.Stats
import com.novcloud.echo.domain.StatsEngine
import com.novcloud.echo.domain.StatsRange
import com.novcloud.echo.ui.components.AnimatedNumber
import com.novcloud.echo.ui.components.ArtistAvatar
import com.novcloud.echo.ui.components.Artwork
import com.novcloud.echo.ui.components.EmptyState
import com.novcloud.echo.ui.components.LocalActions
import com.novcloud.echo.ui.components.LocalBottomInset
import com.novcloud.echo.ui.components.artistModel
import com.novcloud.echo.ui.components.bouncyClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val chartColors = listOf(Color(0xFFFF4F6D), Color(0xFF7F5AF0), Color(0xFF2CB1BC), Color(0xFFFFB347), Color(0xFF38EF7D), Color(0xFF5B8CFF))

@Composable
fun StatsScreen() {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val events by container.allEvents.collectAsState()
    val lib by container.library.library.collectAsState()
    var range by rememberSaveable { mutableStateOf(StatsRange.Week) }
    val stats by produceState<Stats?>(null, events, lib, range) {
        value = withContext(Dispatchers.Default) { StatsEngine.compute(events, lib, range) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 20.dp)) {
        item {
            Text("统计", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 14.dp, bottom = 12.dp))
            RangeSelector(range) { range = it }
        }
        val s = stats ?: return@LazyColumn
        if (s.empty) {
            item { EmptyState(Icons.Rounded.Equalizer, "暂无收听记录", Modifier.padding(top = 40.dp)) }
            return@LazyColumn
        }
        item(key = "hero") { Hero(s) }
        item(key = "tiles") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("播放", s.plays.toLong(), "次", Modifier.weight(1f))
                Tile("歌曲", s.songs.toLong(), "首", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("歌手", s.artists.toLong(), "位", Modifier.weight(1f))
                Tile("活跃", s.activeDays.toLong(), "天", Modifier.weight(1f))
            }
        }
        item(key = "streak") { StreakCard(s) }
        item(key = "trend") {
            Card("收听趋势") { BarChart(s.trend, MaterialTheme.colorScheme.primary) }
        }
        item(key = "clock") {
            Card("时段") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadialClock(s.hourly, MaterialTheme.colorScheme.primary, Modifier.size(170.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("高峰", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(periodName(s.peakHour), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text("最爱", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("周" + StatsEngine.WEEKDAYS[s.peakWeekday], style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text("完整播放", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedNumber((s.completionRate * 100).toLong(), format = { "$it%" }, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        item(key = "weekday") {
            Card("星期") { WeekdayChart(s.weekday, StatsEngine.WEEKDAYS, MaterialTheme.colorScheme.primary) }
        }
        if (s.topArtists.isNotEmpty()) item(key = "artists") { TopArtists(s.topArtists) }
        if (s.topSongs.isNotEmpty()) item(key = "songs") { TopSongs(s.topSongs) }
        if (s.topAlbums.isNotEmpty()) item(key = "albums") { TopAlbums(s.topAlbums) }
        if (s.topGenres.isNotEmpty()) item(key = "genres") { Genres(s.topGenres) }
    }
}

private fun periodName(h: Int) = when (h) {
    in 5..8 -> "清晨 $h 点"
    in 9..11 -> "上午 $h 点"
    in 12..13 -> "中午 $h 点"
    in 14..17 -> "下午 $h 点"
    in 18..22 -> "晚上 $h 点"
    else -> "深夜 $h 点"
}

@Composable
private fun RangeSelector(range: StatsRange, onChange: (StatsRange) -> Unit) {
    val entries = StatsRange.entries
    BoxWithConstraints(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
    ) {
        val w = maxWidth / entries.size
        val x by animateDpAsState(w * range.ordinal, spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest))
        Row(Modifier.fillMaxSize()) {
            entries.forEach { r ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).clickable { onChange(r) }, contentAlignment = Alignment.Center) {
                    Text(
                        r.label, style = MaterialTheme.typography.labelLarge,
                        color = if (r == range) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(s: Stats) {
    val minutes = s.totalMs / 60_000
    Box(
        Modifier.padding(16.dp).fillMaxWidth().shadow(20.dp, RoundedCornerShape(24.dp), spotColor = MaterialTheme.colorScheme.primary)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF4F6D), Color(0xFFB44DFF), Color(0xFF4A3AFF))))
            .padding(22.dp),
    ) {
        Column {
            Text("收听时长", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Row(verticalAlignment = Alignment.Bottom) {
                if (minutes >= 60) {
                    AnimatedNumber(minutes / 60, style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp), color = Color.White)
                    Text("小时", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, end = 10.dp, bottom = 12.dp))
                }
                AnimatedNumber(minutes % 60, style = MaterialTheme.typography.displayLarge.copy(fontSize = if (minutes >= 60) 40.sp else 64.sp), color = Color.White)
                Text("分钟", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = if (minutes >= 60) 6.dp else 12.dp))
            }
            if (s.range.days != null && s.previousMs > 0) {
                val pct = ((s.totalMs - s.previousMs) * 100 / s.previousMs)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text((if (pct >= 0) "↑ " else "↓ ") + "${kotlin.math.abs(pct)}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: Long, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedNumber(value, style = MaterialTheme.typography.headlineMedium)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun StreakCard(s: Stats) {
    Card("连续收听") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFFB347), Color(0xFFFF4F6D)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedNumber(s.currentStreak.toLong(), style = MaterialTheme.typography.headlineMedium)
                    Text("天", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("最长", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${s.longestStreak} 天", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        HeatStrip(s.recentDays, Color(0xFFFF6B4F))
    }
}

@Composable
private fun TopArtists(list: List<Ranked>) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    val custom by container.library.artistImages.collectAsState()
    val home by container.home.collectAsState()
    val max = list.first().ms.coerceAtLeast(1)
    Card("最爱歌手") {
        val first = list.first()
        Row(Modifier.fillMaxWidth().bouncyClick { actions.openArtist(first.key) }, verticalAlignment = Alignment.CenterVertically) {
            Box {
                ArtistAvatar(artistModel(first.key, lib, custom, home.playCounts), first.key, Modifier.size(88.dp))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("1", color = Color.White, fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(displayArtist(first.title), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${shortDuration(first.ms)} · ${first.plays} 次", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        list.drop(1).forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { actions.openArtist(r.key) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 2}", Modifier.width(26.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArtistAvatar(artistModel(r.key, lib, custom, home.playCounts), r.key, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayArtist(r.title), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(r.ms.toFloat() / max).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)))
                    }
                }
                Text(shortDuration(r.ms), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopSongs(list: List<Ranked>) {
    val container = (LocalContext.current.applicationContext as EchoApp).container
    val actions = LocalActions.current
    val lib by container.library.library.collectAsState()
    Card("最爱歌曲") {
        list.forEachIndexed { i, r ->
            val song = r.key.toLongOrNull()?.let { lib.songById[it] }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = song != null) { song?.let { actions.player.playSong(it, list.mapNotNull { x -> x.key.toLongOrNull()?.let { id -> lib.songById[id] } }) } }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}", Modifier.width(26.dp), style = MaterialTheme.typography.titleSmall,
                    color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Black,
                )
                Artwork(r.art, Modifier.size(46.dp), RoundedCornerShape(8.dp), r.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(displayArtist(r.subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text("${r.plays} 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopAlbums(list: List<Ranked>) {
    val actions = LocalActions.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("最爱专辑", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(list, key = { _, r -> r.key }) { i, r ->
                Column(Modifier.width(130.dp).bouncyClick { r.key.toLongOrNull()?.let(actions::openAlbum) }) {
                    Box {
                        Artwork(r.art, Modifier.size(130.dp).shadow(8.dp, RoundedCornerShape(12.dp)), RoundedCornerShape(12.dp), r.title)
                        Box(
                            Modifier.padding(8.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortDuration(r.ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Genres(list: List<Ranked>) {
    val total = list.sumOf { it.ms }.coerceAtLeast(1)
    Card("流派") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Donut(list.map { it.ms }, chartColors, Modifier.size(140.dp))
                AnimatedContent(list.first().title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "g") {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.width(80.dp), overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(chartColors[i % chartColors.size]))
                        Spacer(Modifier.width(8.dp))
                        Text(g.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${g.ms * 100 / total}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

