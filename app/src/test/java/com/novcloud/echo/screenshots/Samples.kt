package com.novcloud.echo.screenshots

import android.content.Context
import android.net.Uri
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.novcloud.echo.EchoApp
import com.novcloud.echo.data.ArtRef
import com.novcloud.echo.data.ArtistArtStore
import com.novcloud.echo.data.Library
import com.novcloud.echo.data.Song
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.PlayEvent
import com.novcloud.echo.playback.PlayerState
import com.novcloud.echo.playback.QueueEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

object Samples {
    private data class AlbumSpec(val id: Long, val title: String, val artist: String, val year: Int, val genre: String, val tracks: List<String>)

    // Fictional artists, albums and tracks. Sample art is self-made (see resources/samples).
    private val albums = listOf(
        AlbumSpec(1, "Paper Satellites", "Ivy Calder", 2024, "Pop", listOf("Glasshouse", "Neon Saints", "Paper Satellites", "Low Tide", "Midnight Mile", "Afterglow")),
        AlbumSpec(2, "Wildflower Static", "Ivy Calder", 2021, "Indie Folk", listOf("Linen", "Hollow Pines", "August Rain", "Driftwood", "Little Lanterns")),
        AlbumSpec(3, "Loud Little Rooms", "The Velvet Static", 2023, "Rock", listOf("Overdrive Heart", "Bad Reception", "Paper Cuts", "Fake Smile Parade")),
        AlbumSpec(4, "Garage Saints", "The Velvet Static", 2019, "Rock", listOf("Feedback Loop", "Basement Lights", "Static Bloom")),
        AlbumSpec(5, "Velvet Orbit", "Kira Nox", 2022, "R&B", listOf("Satellite Love", "Honey Drip", "Afterparty", "Moonlit")),
        AlbumSpec(6, "北岸", "温屿", 2020, "民谣", listOf("晚风邮局", "旧城南", "白鸟", "渡口")),
        AlbumSpec(7, "Small Hours", "Otis Fenwick", 2018, "Indie Folk", listOf("Kitchen Light", "Quiet Harbor", "Ninth of June")),
        AlbumSpec(8, "Neon Orchard", "Neon Orchard", 2023, "Pop", listOf("Sugar Rush", "Lemonade Sky", "Daydream Arcade")),
        AlbumSpec(9, "潮汐", "温屿", 2023, "民谣", listOf("小满", "灯塔")),
        AlbumSpec(10, "Red Signal", "Kira Nox", 2024, "Hip-Hop", listOf("Paint It Loud", "Gold Hills")),
    )

    val songs: List<Song> by lazy {
        var id = 100L
        albums.flatMap { a ->
            a.tracks.mapIndexed { i, t ->
                id++
                Song(
                    id = id, title = t, artist = a.artist, album = a.title, albumId = a.id, albumArtist = a.artist, genre = a.genre,
                    durationMs = 150_000L + (id * 7919 % 120) * 1000, track = i + 1, disc = 1, year = a.year,
                    dateAdded = 1_700_000_000L + id * 3600, path = "/music/${a.artist}/${a.title}/$t.flac", folder = "/music/${a.artist}",
                    mimeType = "audio/flac", size = 30_000_000,
                )
            }
        }
    }

    val library: Library by lazy { Library.build(songs) }

    private val weights = mapOf("Ivy Calder" to 9, "The Velvet Static" to 5, "Kira Nox" to 4, "温屿" to 4, "Otis Fenwick" to 2, "Neon Orchard" to 2)

    fun events(now: Long = System.currentTimeMillis()): List<PlayEvent> {
        val r = Random(42)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val pool = songs.flatMap { s -> List(weights[s.artist] ?: 1) { s } }
        val out = ArrayList<PlayEvent>()
        for (d in 0 until 70) {
            if (d > 6 && r.nextFloat() < 0.25f) continue
            val day = today.minusDays(d.toLong())
            val n = 4 + r.nextInt(if (d < 7) 14 else 8)
            repeat(n) {
                val hour = listOf(8, 9, 12, 13, 18, 20, 21, 21, 22, 22, 23, 0, 1)[r.nextInt(13)]
                val t = day.atTime(hour, r.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                if (t > now) return@repeat
                val s = pool[r.nextInt(pool.size)]
                val full = r.nextFloat() < 0.8f
                out += PlayEvent(
                    songId = s.id, title = s.title, artist = s.artist, album = s.album, albumId = s.albumId, genre = s.genre,
                    startedAt = t, listenedMs = if (full) s.durationMs else s.durationMs / 3, songDurationMs = s.durationMs, completed = full,
                )
            }
        }
        return out.sortedBy { it.startedAt }
    }

    private fun extract(context: Context, name: String): File {
        val dir = File(context.cacheDir, "samples").apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists()) {
            Samples::class.java.classLoader!!.getResourceAsStream("samples/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    fun installImageLoader(context: Context) {
        val covers = (1..10).associateWith { extract(context, "cover_$it.jpg") }
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(object : Mapper<ArtRef, File> {
                        override fun map(data: ArtRef, options: Options): File? = covers[((data.albumId - 1).mod(10L) + 1).toInt()]
                    })
                }
                .crossfade(false)
                .build(),
        )
    }

    fun app(context: Context) = (context.applicationContext as EchoApp).container

    fun seed(context: Context, withArt: Boolean = true) {
        installImageLoader(context)
        val c = app(context)
        runBlocking {
            events().forEach { c.db.events().insert(it) }
            if (withArt) {
                listOf(
                    Triple("Ivy Calder", "poster_field.jpg", "logo_ivy.png"),
                    Triple("The Velvet Static", "poster_garage.jpg", "logo_velvet.png"),
                    Triple("Kira Nox", "poster_stage.jpg", "logo_kira.png"),
                ).forEach { (artist, poster, logo) ->
                    val p = ArtistArtStore.savePoster(context, artist, Uri.fromFile(extract(context, poster)))!!
                    c.db.artistImages().put(ArtistImage(artist, p.absolutePath, 1))
                    val l = ArtistArtStore.saveLogo(context, artist, Uri.fromFile(extract(context, logo)))!!
                    c.db.artistLogos().put(ArtistLogo(artist, l.file.absolutePath, l.aspect, l.luma, 1))
                }
            }
        }
        c.library.publish(library)
    }

    fun playing(context: Context, songTitle: String = "Paper Satellites") {
        val s = songs.first { it.title == songTitle }
        val queue = songs.take(12).mapIndexed { i, x -> QueueEntry(i, x) }
        app(context).player.preview(
            PlayerState(song = s, isPlaying = true, durationMs = s.durationMs, queue = queue, currentIndex = 2),
            s.durationMs * 37 / 100,
        )
    }

    /** Self-written bilingual lines in the formats real LRC files use: inline, mixed Japanese/English, separator. */
    fun primeLyrics(context: Context, songTitle: String = "Paper Satellites") {
        val s = songs.first { it.title == songTitle }
        val step = s.durationMs / 14
        val raw = listOf(
            "Paper satellites over the bay 纸做的卫星飞过海湾",
            "夜明けまで待ってるよ 我会等到天亮",
            "ネオンの海でdancing alone 在霓虹之海独自起舞",
            "We folded every letter into wings 我们把每封信折成翅膀",
            "♪",
            "静かなstatic 君の声 寂静的杂音 你的声音",
            "Hold the signal, hold it close 抓住信号 紧紧抓住",
            "遠くへ 遠くへ 去远方 去远方",
            "Ich höre dich im Rauschen 我在杂音里听见你",
            "Paper satellites, come home / 纸卫星啊 回家吧",
        )
        val lrc = raw.mapIndexed { i, line ->
            val t = step * (i + 1)
            "[%02d:%02d.%02d]%s".format(t / 60_000, t / 1000 % 60, t % 1000 / 10, line)
        }.joinToString("\n")
        val c = app(context)
        c.lyrics.prime(s, c.lyricsTree.value, com.novcloud.echo.data.lyrics.LrcParser.parse(lrc))
    }

    /** Enhanced LRC around the preview position: the active line is half sung across its wrap. */
    fun primeWordLyrics(context: Context, songTitle: String = "Paper Satellites") {
        val s = songs.first { it.title == songTitle }
        val p = s.durationMs * 37 / 100
        fun t(ms: Long) = "%02d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
        val words = listOf("Paper ", "satellites ", "drifting ", "over ", "the ", "harbour ", "lights ", "tonight")
        val start = p - 3000
        val stamped = words.mapIndexed { i, w -> "<${t(start + i * 750L)}>$w" }.joinToString("") + "<${t(start + words.size * 750L)}>"
        val lrc = listOf(
            "[${t(p - 12_000)}]Hold the signal, hold it close",
            "[${t(p - 12_000)}]抓住信号 紧紧抓住",
            "[${t(p - 7_000)}]We folded every letter into wings",
            "[${t(p - 7_000)}]我们把每封信折成翅膀",
            "[${t(start)}]$stamped",
            "[${t(start)}]纸卫星飘过今夜港口的灯火",
            "[${t(p + 3_200)}]夜明けまで待ってるよ",
            "[${t(p + 3_200)}]我会等到天亮",
            "[${t(p + 7_000)}]遠くへ 遠くへ",
            "[${t(p + 7_000)}]去远方 去远方",
        ).joinToString("\n")
        val c = app(context)
        c.lyrics.prime(s, c.lyricsTree.value, com.novcloud.echo.data.lyrics.LyricsParser.parse(lrc))
    }
}
