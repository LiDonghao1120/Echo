package com.novcloud.echo.domain

import com.novcloud.echo.data.ArtLibraryMatch
import com.novcloud.echo.data.ArtRef
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Names
import com.novcloud.echo.data.Pinyin
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.UNKNOWN
import com.novcloud.echo.data.albumTrackOrder
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.data.mostCommon
import com.novcloud.echo.data.nameSortKey
import com.novcloud.echo.data.sortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Every comparator the app sorts with is a total order: antisymmetric, transitive, and consistent from one call to the
 * next, over random inputs full of ties, names equal after normalisation, accents, misread (mojibake) spellings and
 * empty values. A violation is what TimSort throws "Comparison method violates its general contract!" on.
 */
class ComparatorContractTest {
    companion object {
        @BeforeClass @JvmStatic
        fun pinyin() {
            Pinyin.install { File("src/main/assets/pinyin.bin").inputStream() }
        }

        /** Fictional names and the spellings that fold onto them. */
        val NAMES = listOf(
            "Ivy Calder", "IVY CALDER", "Ivy  Calder", " Ivy Calder ", "\uFF29vy Calder", "Ivy\u200BCalder",
            "Zoë Arden", "Zoe\u0308 Arden", "Zo\uFFFD Arden", "Zoe Arden",
            "MARÍA VELL", "MAR\u866F VELL", "MAR\u00C3\u008DA VELL", "Maria Vell",
            "晚潮乐队", "晚潮樂隊", "晚潮乐队 ", "周景澜", "周景瀾", "沈星河", "Kai林雾",
            "あおぞら", "アオゾラ", "夜明けのMOMO〜STREET COVERS〜", "夜明けの MOMO",
            "Rust N' Ribbons", "Rust N’ Ribbons", "(Velvet Moth)", "[Velvet Moth]", "（Velvet Moth）",
            "", " ", "?", "？", "\u0000", UNKNOWN, "21 Harbor", "ZYA", "zya", "Ångström Lane", "Angstrom Lane",
        )

        fun sgn(x: Int) = Integer.signum(x)

        /** Checks antisymmetry, reflexivity, transitivity and repeatability over every pair and triple of [items]. */
        fun <T> assertTotalOrder(name: String, cmp: Comparator<T>, items: List<T>) {
            for (a in items) {
                if (cmp.compare(a, a) != 0) fail("$name: compare(a, a) != 0 for $a")
                for (b in items) {
                    val ab = sgn(cmp.compare(a, b))
                    if (ab != -sgn(cmp.compare(b, a))) fail("$name: not antisymmetric for $a / $b")
                    if (ab != sgn(cmp.compare(a, b))) fail("$name: not repeatable for $a / $b")
                    for (c in items) {
                        val bc = sgn(cmp.compare(b, c))
                        val ac = sgn(cmp.compare(a, c))
                        if (ab <= 0 && bc <= 0 && ac > 0) fail("$name: not transitive for $a <= $b <= $c")
                        if (ab == 0 && ac != bc) fail("$name: equal elements order differently against $c: $a / $b")
                    }
                }
            }
        }

        /** Sorting large shuffled lists is what made TimSort notice in the app. */
        fun <T> assertSorts(name: String, cmp: Comparator<T>, gen: (Random) -> T) {
            repeat(40) { round ->
                val r = Random(round)
                val list = List(600) { gen(r) }
                val sorted = try { list.sortedWith(cmp) } catch (e: IllegalArgumentException) { fail("$name: $e"); return }
                for (i in 1 until sorted.size) assertTrue("$name out of order", cmp.compare(sorted[i - 1], sorted[i]) <= 0)
            }
        }

        fun <T> check(name: String, cmp: Comparator<T>, gen: (Random) -> T) {
            repeat(6) { seed -> val r = Random(seed * 7919); assertTotalOrder(name, cmp, List(45) { gen(r) }) }
            assertSorts(name, cmp, gen)
        }
    }

    private fun Random.name() = NAMES[nextInt(NAMES.size)]

    private fun Random.song() = Song(
        id = nextLong(1, 12), title = name(), artist = name(), album = name(), albumId = nextLong(1, 4), albumArtist = "",
        genre = "", durationMs = 1000, track = nextInt(-1, 4), disc = nextInt(0, 3), year = 2020, dateAdded = 0,
        path = "/m/${nextInt(1000)}.mp3", folder = "/m", mimeType = "audio/mpeg", size = 1,
    )

    private fun Random.ranked(): Ranked {
        val k = name()
        return Ranked(k, k, name(), if (nextBoolean()) null else ArtRef(nextLong(1, 3), -1), nextLong(0, 4) * 1000, nextInt(0, 4))
    }

    private fun Random.asset() = ArtAsset(
        id = nextLong(1, 10), name = name(), kind = if (nextBoolean()) "logo" else "poster", path = "", width = 1, height = 1,
        luma = if (nextInt(5) == 0) Float.NaN else nextFloat(), hash = "", addedAt = 0, updatedAt = nextLong(0, 4),
    )

    @Test fun albumTracks() = check("albumTrackOrder", albumTrackOrder) { it.song() }
    @Test fun statsByPlays() = check("StatsOrder.byPlays", StatsOrder.byPlays) { it.ranked() }
    @Test fun statsByTime() = check("StatsOrder.byTime", StatsOrder.byTime) { it.ranked() }
    @Test fun artNewestFirst() = check("ArtLibraryMatch.newestFirst", ArtLibraryMatch.newestFirst) { it.asset() }

    @Test fun artPicker() = check("ArtLibraryMatch.pickerOrder", ArtLibraryMatch.pickerOrder) { r ->
        val a = r.asset()
        ArtLibraryMatch.Picked(a, r.nextBoolean(), r.nextBoolean(), nameSortKey(a.name))
    }

    @Test fun scoresWithNaN() {
        val byScore = compareByDescending<Pair<Long, Double>> { it.second }
        check("score selector", byScore) { r ->
            r.nextLong(0, 9) to when (r.nextInt(6)) { 0 -> Double.NaN; 1 -> -0.0; 2 -> 0.0; else -> r.nextInt(4) / 4.0 }
        }
    }

    @Test fun mostCommonIgnoresOrder() {
        repeat(200) { seed ->
            val r = Random(seed)
            val values = List(r.nextInt(1, 30)) { r.name() }
            assertEquals(mostCommon(values), mostCommon(values.shuffled(r)))
        }
    }

    /** Keys that the app sorts by (`sortedByKey`, `sortedBy`) are pure functions of the name. */
    @Test fun sortKeysAreStable() {
        NAMES.forEach { n ->
            assertEquals(n, sortKey(n), sortKey(String(n.toCharArray())))
            assertEquals(n, nameSortKey(n), nameSortKey(String(n.toCharArray())))
            assertEquals(n, Names.key(n), Names.key(String(n.toCharArray())))
        }
        assertEquals(Names.key("Ivy Calder"), Names.key("\uFF29vy  Calder"))
        assertEquals(Names.key("MARÍA VELL"), Names.key("MAR\u866F VELL"))
    }

    /** The checker itself: a selector that draws a random number per comparison (v0.13.2's "此刻常听") is caught. */
    @Test fun aRandomSelectorIsCaught() {
        val r = Random(1)
        val broken = compareByDescending<Map.Entry<Long, Int>> { it.value + r.nextDouble() }
        val entries = (1L..60L).associateWith { (it % 3).toInt() }.entries.toList()
        var caught = false
        try { assertTotalOrder("random", broken, entries) } catch (_: AssertionError) { caught = true }
        assertTrue(caught)
    }

    /**
     * Regression for the v0.13.2 crash: with dozens of songs played around the current hour, the time-of-day mix sorted
     * with a random key per comparison and TimSort threw on a background coroutine, killing playback. Build must not
     * throw and must give the same result twice.
     */
    @Test fun recommendationsWithALongHistoryAroundThisHour() {
        val zone = ZoneId.systemDefault()
        val songs = (1L..442L).map { id ->
            Song(id, "Song $id", NAMES[(id % NAMES.size).toInt()].ifBlank { "Ivy Calder" }, "Album ${id % 40}", id % 40, "", listOf("Pop", "Jazz", "")[(id % 3).toInt()],
                200_000, (id % 14).toInt(), 1, 2020, id * 1000, "/m/$id.mp3", "/m", "audio/mpeg", 1)
        }
        val lib = Library.build(songs)
        repeat(12) { seed ->
            val r = Random(seed)
            val now = LocalDateTime.of(2026, 11, 8, 19, 51).atZone(zone).toInstant().toEpochMilli() + seed * 3_600_000L
            val events = (0 until 900).map {
                val s = songs[r.nextInt(songs.size)]
                val t = now - r.nextLong(0, 40L * 86_400_000)
                PlayEvent(songId = s.id, title = s.title, artist = s.artist, album = s.album, albumId = s.albumId, genre = s.genre,
                    startedAt = t, listenedMs = r.nextLong(10_000, 200_000), songDurationMs = 200_000, completed = r.nextBoolean())
            }.sortedBy { it.startedAt }
            val a = Recommender.build(lib, events, now)
            val b = Recommender.build(lib, events, now)
            assertEquals(a.mixes.map { m -> m.id to m.songs.map { it.id } }, b.mixes.map { m -> m.id to m.songs.map { it.id } })
            assertTrue(a.mixes.any { it.kind == MixKind.Moment })
        }
    }
}
