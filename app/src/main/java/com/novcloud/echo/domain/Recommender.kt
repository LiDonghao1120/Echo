package com.novcloud.echo.domain

import androidx.compose.runtime.Immutable
import com.novcloud.echo.data.Album
import com.novcloud.echo.data.Artist
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import com.novcloud.echo.data.db.PlayEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class MixKind { Daily, Genre, Artist, Rediscover, Fresh, Moment, Favorites }

@Immutable
data class Mix(val id: String, val title: String, val subtitle: String, val kind: MixKind, val songs: List<Song>)

@Immutable
data class HomeData(
    val day: LocalDate = LocalDate.now(),
    val daily: Mix? = null,
    val mixes: List<Mix> = emptyList(),
    val recent: List<Song> = emptyList(),
    val mostPlayed: List<Pair<Song, Int>> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val hasHistory: Boolean = false,
    val playCounts: Map<Long, Int> = emptyMap(),
) {
    fun mix(id: String): Mix? = if (daily?.id == id) daily else mixes.firstOrNull { it.id == id }
}

class Affinity(events: List<PlayEvent>, now: Long) {
    val artist = HashMap<String, Double>()
    val genre = HashMap<String, Double>()
    val album = HashMap<Long, Double>()
    val song = HashMap<Long, Double>()
    val plays = HashMap<Long, Int>()
    val lastPlayed = HashMap<Long, Long>()

    init {
        for (e in events) {
            val age = (now - e.startedAt).coerceAtLeast(0) / 86_400_000.0
            val decay = 0.5.pow(age / 30.0)
            val ratio = if (e.songDurationMs > 0) min(1.0, e.listenedMs.toDouble() / e.songDurationMs) else 0.5
            val w = decay * (if (e.completed) 1.0 else 0.4 + 0.6 * ratio)
            com.novcloud.echo.data.splitArtists(e.artist).forEach { if (it != UNKNOWN) artist.merge(it, w, Double::plus) }
            if (e.genre.isNotBlank()) genre.merge(e.genre, w, Double::plus)
            album.merge(e.albumId, w, Double::plus)
            song.merge(e.songId, w, Double::plus)
            if (e.completed || e.listenedMs >= 30_000) plays.merge(e.songId, 1, Int::plus)
            lastPlayed.merge(e.songId, e.startedAt, ::max)
        }
        normalize(artist); normalize(genre); normalize(album); normalize(song)
    }

    private fun <K> normalize(m: HashMap<K, Double>) {
        val top = m.values.maxOrNull() ?: return
        if (top <= 0) return
        m.replaceAll { _, v -> v / top }
    }
}

object Recommender {

    fun build(lib: Library, events: List<PlayEvent>, now: Long = System.currentTimeMillis()): HomeData {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (lib.songs.isEmpty()) return HomeData(day = today)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val past = events.filter { it.startedAt < dayStart }
        val aff = Affinity(past, dayStart)
        val live = Affinity(events, now)
        val seed = today.toEpochDay()
        val hasHistory = events.isNotEmpty()

        val daily = Mix("daily", "每日推荐", "", MixKind.Daily, daily(lib, aff, dayStart, Random(seed)))

        val mixes = ArrayList<Mix>()
        val genres = if (aff.genre.isNotEmpty()) aff.genre.entries.sortedByDescending { it.value }.map { it.key }
        else lib.genres.map { it.name }
        genres.take(3).forEachIndexed { i, g ->
            val pool = lib.genres.firstOrNull { it.name == g }?.songs.orEmpty()
            val r = Random(seed * 31 + i)
            val picked = diverse(pool.sortedByDescending { scoreOf(it, aff, dayStart, r, 0.6) }, 25, 3)
            if (picked.size >= 5) mixes += Mix("genre:$g", g, "精选", MixKind.Genre, picked)
        }
        val topArtists = if (aff.artist.isNotEmpty()) aff.artist.entries.sortedByDescending { it.value }.map { it.key }
        else lib.artists.filter { it.name != UNKNOWN }.sortedByDescending { it.songs.size }.map { it.name }
        topArtists.take(3).forEachIndexed { i, name ->
            val artist = lib.artistByName[name] ?: return@forEachIndexed
            val r = Random(seed * 17 + i)
            val own = artist.songs.shuffled(r).take(12)
            val artistGenres = artist.songs.map { it.genre }.filter { it.isNotBlank() }.toSet()
            val similarArtists = lib.artists.filter { a -> a.name != name && a.songs.any { it.genre in artistGenres } }
                .sortedByDescending { aff.artist[it.name] ?: 0.0 }
                .take(8).map { it.name }.toSet()
            val similar = lib.songs.filter { s -> s.artists.any { it in similarArtists } && s.genre in artistGenres }
                .shuffled(r).let { diverse(it, 13, 2) }
            val songs = interleave(own, similar)
            if (songs.size >= 5) mixes += Mix("artist:$name", name, "电台", MixKind.Artist, songs)
        }
        if (past.isNotEmpty()) {
            val monthAgo = dayStart - 30L * 86_400_000
            val rediscover = aff.plays.entries.filter { (id, c) -> c >= 3 && (aff.lastPlayed[id] ?: 0) < monthAgo }
                .sortedByDescending { it.value }.mapNotNull { lib.songById[it.key] }
            if (rediscover.size >= 5) mixes += Mix("rediscover", "重温", "好久没听", MixKind.Rediscover, diverse(rediscover, 25, 3))

            val hour = Instant.ofEpochMilli(now).atZone(zone).hour
            val momentCounts = HashMap<Long, Int>()
            events.forEach { e ->
                val h = Instant.ofEpochMilli(e.startedAt).atZone(zone).hour
                val d = min((h - hour + 24) % 24, (hour - h + 24) % 24)
                if (d <= 2) momentCounts.merge(e.songId, 1, Int::plus)
            }
            val r = Random(seed * 7 + hour / 3)
            val moment = momentCounts.entries.sortedByDescending { it.value + r.nextDouble() }.mapNotNull { lib.songById[it.key] }
            if (moment.size >= 6) mixes += Mix("moment", momentTitle(hour), "此刻常听", MixKind.Moment, diverse(moment, 25, 3))
        }
        val fresh = lib.songs.filter { it.id !in live.plays }.sortedByDescending { it.dateAdded }
            .take(120).shuffled(Random(seed * 13)).let { diverse(it, 25, 2) }
        if (fresh.size >= 5) mixes += Mix("fresh", "新发现", "还没听过", MixKind.Fresh, fresh)

        val recentIds = LinkedHashSet<Long>()
        for (e in events.asReversed()) {
            recentIds += e.songId
            if (recentIds.size >= 20) break
        }
        val recent = recentIds.mapNotNull { lib.songById[it] }
        val most = live.plays.entries.sortedByDescending { it.value }.take(10).mapNotNull { (id, c) -> lib.songById[id]?.let { it to c } }
        val added = lib.albums.sortedByDescending { it.dateAdded }.take(12)
        val artistOrder = if (live.artist.isNotEmpty()) live.artist.entries.sortedByDescending { it.value }.map { it.key } else topArtists
        val artists = artistOrder.mapNotNull { lib.artistByName[it] }.filter { it.name != UNKNOWN }.take(12)

        return HomeData(today, daily, mixes, recent, most, added, artists, hasHistory, live.plays)
    }

    private fun daily(lib: Library, aff: Affinity, now: Long, r: Random): List<Song> {
        val ranked = lib.songs.map { it to scoreOf(it, aff, now, r, 0.45) }.sortedByDescending { it.second }.map { it.first }
        return diverse(ranked, 30, 3, albumCap = 2)
    }

    private fun scoreOf(s: Song, aff: Affinity, now: Long, r: Random, noise: Double): Double {
        val a = s.artists.maxOf { aff.artist[it] ?: 0.0 }
        val g = aff.genre[s.genre] ?: 0.0
        val al = aff.album[s.albumId] ?: 0.0
        val last = aff.lastPlayed[s.id]
        val recentPenalty = if (last != null && now - last < 36 * 3_600_000L) 0.5 else 0.0
        val overplay = min(0.3, (aff.song[s.id] ?: 0.0) * 0.3)
        val explore = if (last == null) 0.15 else 0.0
        return 0.4 * a + 0.25 * g + 0.15 * al + explore + r.nextDouble() * noise - recentPenalty - overplay
    }

    private fun diverse(songs: List<Song>, limit: Int, artistCap: Int, albumCap: Int = Int.MAX_VALUE): List<Song> {
        val out = ArrayList<Song>()
        val byArtist = HashMap<String, Int>()
        val byAlbum = HashMap<Long, Int>()
        for (s in songs) {
            if (out.size >= limit) break
            val a = s.artists.first()
            if ((byArtist[a] ?: 0) >= artistCap || (byAlbum[s.albumId] ?: 0) >= albumCap) continue
            out += s
            byArtist.merge(a, 1, Int::plus)
            byAlbum.merge(s.albumId, 1, Int::plus)
        }
        if (out.size < limit) {
            val taken = out.mapTo(HashSet()) { it.id }
            for (s in songs) { if (out.size >= limit) break; if (taken.add(s.id)) out += s }
        }
        return out
    }

    private fun interleave(a: List<Song>, b: List<Song>): List<Song> {
        val out = ArrayList<Song>()
        val n = max(a.size, b.size)
        for (i in 0 until n) {
            a.getOrNull(i)?.let { out += it }
            b.getOrNull(i)?.let { if (it !in out) out += it }
        }
        return out.distinctBy { it.id }
    }

    private fun momentTitle(hour: Int) = when (hour) {
        in 5..10 -> "清晨"
        in 11..13 -> "午间"
        in 14..17 -> "午后"
        in 18..22 -> "夜晚"
        else -> "深夜"
    }
}
