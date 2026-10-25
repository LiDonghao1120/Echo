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

    private val albums = listOf(
        AlbumSpec(1, "Midnights", "Taylor Swift", 2022, "Pop", listOf("Lavender Haze", "Maroon", "Anti-Hero", "Snow On The Beach", "Midnight Rain", "Karma")),
        AlbumSpec(2, "folklore", "Taylor Swift", 2020, "Indie Folk", listOf("cardigan", "exile", "august", "betty", "invisible string")),
        AlbumSpec(3, "After Laughter", "Paramore", 2017, "Rock", listOf("Hard Times", "Rose-Colored Boy", "Told You So", "Fake Happy")),
        AlbumSpec(4, "Riot!", "Paramore", 2007, "Rock", listOf("Misery Business", "crushcrushcrush", "That's What You Get")),
        AlbumSpec(5, "Planet Her", "Doja Cat", 2021, "R&B", listOf("Woman", "Kiss Me More", "Need To Know", "Streets")),
        AlbumSpec(6, "如也", "陈粒", 2015, "民谣", listOf("奇妙能力歌", "易燃易爆炸", "历历万乡", "走马")),
        AlbumSpec(7, "Carrie & Lowell", "Sufjan Stevens", 2015, "Indie Folk", listOf("Death With Dignity", "Should Have Known Better", "Fourth of July")),
        AlbumSpec(8, "Being Funny in a Foreign Language", "The 1975", 2022, "Pop", listOf("Happiness", "I'm In Love With You", "About You")),
        AlbumSpec(9, "城市", "陈粒", 2018, "民谣", listOf("小半", "光")),
        AlbumSpec(10, "Scarlet", "Doja Cat", 2023, "Hip-Hop", listOf("Paint The Town Red", "Agora Hills")),
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

    private val weights = mapOf("Taylor Swift" to 9, "Paramore" to 5, "Doja Cat" to 4, "陈粒" to 4, "Sufjan Stevens" to 2, "The 1975" to 2)

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
                    Triple("Taylor Swift", "poster_field.jpg", "logo_taylor.png"),
                    Triple("Paramore", "poster_garage.jpg", "logo_paramore.png"),
                    Triple("Doja Cat", "poster_stage.jpg", "logo_doja.png"),
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

    fun playing(context: Context, songTitle: String = "Anti-Hero") {
        val s = songs.first { it.title == songTitle }
        val queue = songs.take(12).mapIndexed { i, x -> QueueEntry(i, x) }
        app(context).player.preview(
            PlayerState(song = s, isPlaying = true, durationMs = s.durationMs, queue = queue, currentIndex = 2),
            s.durationMs * 37 / 100,
        )
    }
}
