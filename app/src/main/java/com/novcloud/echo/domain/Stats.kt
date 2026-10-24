package com.novcloud.echo.domain

import androidx.compose.runtime.Immutable
import com.novcloud.echo.data.ArtRef
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.UNKNOWN
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.displayAlbum
import com.novcloud.echo.data.splitArtists
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class StatsRange(val label: String, val days: Long?) {
    Week("7天", 7), Month("30天", 30), Year("一年", 365), All("全部", null)
}

@Immutable
data class Ranked(val key: String, val title: String, val subtitle: String, val art: Any?, val ms: Long, val plays: Int)

@Immutable
data class TrendPoint(val label: String, val ms: Long)

@Immutable
data class Stats(
    val range: StatsRange,
    val totalMs: Long = 0,
    val previousMs: Long = 0,
    val plays: Int = 0,
    val songs: Int = 0,
    val artists: Int = 0,
    val activeDays: Int = 0,
    val trend: List<TrendPoint> = emptyList(),
    val hourly: List<Long> = List(24) { 0L },
    val weekday: List<Long> = List(7) { 0L },
    val topSongs: List<Ranked> = emptyList(),
    val topArtists: List<Ranked> = emptyList(),
    val topAlbums: List<Ranked> = emptyList(),
    val topGenres: List<Ranked> = emptyList(),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val completionRate: Float = 0f,
    val recentDays: List<Long> = List(28) { 0L },
) {
    val empty get() = plays == 0
    val peakHour get() = hourly.indices.maxByOrNull { hourly[it] } ?: 0
    val peakWeekday get() = weekday.indices.maxByOrNull { weekday[it] } ?: 0
}

object StatsEngine {
    private const val DAY = 86_400_000L

    fun compute(all: List<PlayEvent>, lib: Library, range: StatsRange, now: Long = System.currentTimeMillis()): Stats {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = range.days?.let { today.minusDays(it - 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
        val events = all.filter { it.startedAt >= from }
        val previousMs = range.days?.let { d ->
            val pFrom = from - d * DAY
            all.filter { it.startedAt in pFrom until from }.sumOf { it.listenedMs }
        } ?: 0L

        val days = all.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.toSortedSet()
        val (current, longest) = streaks(days, today)

        val hourly = LongArray(24)
        val weekday = LongArray(7)
        events.forEach {
            val z = Instant.ofEpochMilli(it.startedAt).atZone(zone)
            hourly[z.hour] += it.listenedMs
            weekday[z.dayOfWeek.value - 1] += it.listenedMs
        }

        val trend = trend(events, range, today, zone, all.firstOrNull()?.startedAt)

        val songAgg = events.groupBy { it.songId }.map { (id, l) ->
            val e = l.last()
            val s = lib.songById[id]
            Ranked(id.toString(), s?.title ?: e.title, s?.artist ?: e.artist, s?.art ?: ArtRef(e.albumId, id), l.sumOf { it.listenedMs }, l.size)
        }.sortedWith(compareByDescending<Ranked> { it.plays }.thenByDescending { it.ms }).take(10)

        val artistMs = HashMap<String, Long>()
        val artistPlays = HashMap<String, Int>()
        events.forEach { e ->
            splitArtists(e.artist).filter { it != UNKNOWN }.forEach {
                artistMs.merge(it, e.listenedMs, Long::plus)
                artistPlays.merge(it, 1, Int::plus)
            }
        }
        val artistAgg = artistMs.entries.sortedByDescending { it.value }.take(10).map { (name, ms) ->
            Ranked(name, name, "", name, ms, artistPlays[name] ?: 0)
        }

        val albumAgg = events.groupBy { it.albumId }.map { (id, l) ->
            val al = lib.albumById[id]
            Ranked(id.toString(), displayAlbum(al?.title ?: l.last().album), al?.artist ?: l.last().artist, al?.art ?: ArtRef(id, l.last().songId), l.sumOf { it.listenedMs }, l.size)
        }.sortedByDescending { it.ms }.take(10)

        val genreAgg = events.filter { it.genre.isNotBlank() }.groupBy { it.genre }.map { (g, l) ->
            Ranked(g, g, "", null, l.sumOf { it.listenedMs }, l.size)
        }.sortedByDescending { it.ms }.take(6)

        val dayMs = all.filter { it.startedAt >= today.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli() }
            .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, l) -> l.sumOf { it.listenedMs } }
        val recentDays = (27 downTo 0).map { dayMs[today.minusDays(it.toLong())] ?: 0L }

        return Stats(
            recentDays = recentDays,
            range = range,
            totalMs = events.sumOf { it.listenedMs },
            previousMs = previousMs,
            plays = events.size,
            songs = events.map { it.songId }.distinct().size,
            artists = artistMs.size,
            activeDays = events.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.distinct().size,
            trend = trend,
            hourly = hourly.toList(),
            weekday = weekday.toList(),
            topSongs = songAgg,
            topArtists = artistAgg,
            topAlbums = albumAgg,
            topGenres = genreAgg,
            currentStreak = current,
            longestStreak = longest,
            completionRate = if (events.isEmpty()) 0f else events.count { it.completed } / events.size.toFloat(),
        )
    }

    private fun trend(events: List<PlayEvent>, range: StatsRange, today: LocalDate, zone: ZoneId, first: Long?): List<TrendPoint> {
        fun dayOf(e: PlayEvent) = Instant.ofEpochMilli(e.startedAt).atZone(zone).toLocalDate()
        return when (range) {
            StatsRange.Week, StatsRange.Month -> {
                val n = range.days!!.toInt()
                val byDay = events.groupBy { dayOf(it) }.mapValues { (_, l) -> l.sumOf { it.listenedMs } }
                (n - 1 downTo 0).map { i ->
                    val d = today.minusDays(i.toLong())
                    val label = if (range == StatsRange.Week) WEEKDAYS[d.dayOfWeek.value - 1] else "${d.monthValue}/${d.dayOfMonth}"
                    TrendPoint(label, byDay[d] ?: 0)
                }
            }
            StatsRange.Year, StatsRange.All -> {
                val start = when {
                    range == StatsRange.Year || first == null -> today.withDayOfMonth(1).minusMonths(11)
                    else -> Instant.ofEpochMilli(first).atZone(zone).toLocalDate().withDayOfMonth(1)
                }
                val months = (ChronoUnit.MONTHS.between(start, today.withDayOfMonth(1)) + 1).toInt().coerceIn(1, 120)
                val firstMonth = if (months > 24) today.withDayOfMonth(1).minusMonths(23) else start
                val count = months.coerceAtMost(24)
                val byMonth = events.groupBy { dayOf(it).withDayOfMonth(1) }.mapValues { (_, l) -> l.sumOf { it.listenedMs } }
                (0 until count).map { i ->
                    val m = firstMonth.plusMonths(i.toLong())
                    TrendPoint("${m.monthValue}月", byMonth[m] ?: 0)
                }
            }
        }
    }

    private fun streaks(days: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        var current = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) { current++; d = d.minusDays(1) }
        return current to longest
    }

    val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")
}
