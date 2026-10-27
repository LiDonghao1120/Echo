package com.novcloud.echo.data

import com.novcloud.echo.data.tags.ArraySource
import com.novcloud.echo.data.tags.Asf
import com.novcloud.echo.data.tags.AudioTags
import com.novcloud.echo.data.tags.ExtraTags
import com.novcloud.echo.data.tags.TagFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class TagFilesTest {
    private class Bytes {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        fun u64(v: Long) = apply { u32(v and 0xFFFFFFFFL); u32(v ushr 32) }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun ascii(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
        fun wide(s: String) = raw(s.toByteArray(Charsets.UTF_16LE))
        fun zeros(n: Int) = raw(ByteArray(n))
        fun bytes(): ByteArray = out.toByteArray()
        val size get() = out.size()
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    private fun apeTag(items: List<Triple<String, ByteArray, Int>>): ByteArray {
        val body = Bytes()
        items.forEach { (k, v, flags) -> body.u32(v.size.toLong()).u32(flags.toLong()).ascii(k).u8(0).raw(v) }
        val b = body.bytes()
        return Bytes().raw(b).ascii("APETAGEX").u32(2000).u32((b.size + 32).toLong()).u32(items.size.toLong()).u32(0).zeros(8).bytes()
    }

    private fun text(k: String, v: String) = Triple(k, v.toByteArray(Charsets.UTF_8), 0)

    /** Monkey's Audio 3.99 descriptor + header for 10 frames of 73728 blocks, last frame 44100 blocks, 44.1 kHz. */
    private fun macHeader(): ByteArray = Bytes()
        .ascii("MAC ").u16(3990).u16(0).u32(52).u32(24).u32(0).u32(0).u32(0).u32(0).u32(0).zeros(16)
        .u16(2000).u16(0).u32(73728).u32(44100).u32(10).u16(16).u16(2).u32(44100)
        .zeros(64).bytes()

    @Test
    fun readsApeFileTagsCoverAndDuration() {
        val tag = apeTag(
            listOf(
                text("Title", "风的形状"), text("Artist", "温屿"), text("Album", "海边的回声"), text("Album Artist", "温屿"),
                text("Track", "3/12"), text("Year", "2023-05-01"), text("Genre", "Folk"),
                text("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"),
                Triple("Cover Art (Front)", "cover.jpg".toByteArray() + byteArrayOf(0) + jpeg, 2),
            ),
        )
        val t = TagFiles.read(ArraySource(macHeader() + ByteArray(100) + tag), wantCover = true)!!
        assertEquals("风的形状", t.title)
        assertEquals("温屿", t.artist)
        assertEquals("海边的回声", t.album)
        assertEquals("温屿", t.albumArtist)
        assertEquals(3, t.track)
        assertEquals(2023, t.year)
        assertEquals("Folk", t.genre)
        assertEquals("-6.20 dB", t.extra["REPLAYGAIN_TRACK_GAIN"])
        assertArrayEquals(jpeg, t.cover)
        assertEquals((9L * 73728 + 44100) * 1000 / 44100, t.durationMs)
    }

    @Test
    fun readsApeV2BeforeId3v1OnMp3AndSkipsCoverWhenNotWanted() {
        val id3v1 = Bytes().ascii("TAG").zeros(125).bytes()
        val tag = apeTag(listOf(text("ARTIST", "Otis Fenwick"), text("TITLE", "Low Tide"), Triple("Cover Art (Front)", byteArrayOf(0) + jpeg, 2)))
        val t = TagFiles.read(ArraySource(ByteArray(400) { 0x55 } + tag + id3v1), wantCover = false)!!
        assertEquals("Low Tide", t.title)
        assertEquals("Otis Fenwick", t.artist)
        assertNull(t.cover)
        assertEquals(0L, t.durationMs)
    }

    @Test
    fun noTagsIsNull() {
        assertNull(TagFiles.read(ArraySource(ByteArray(2048) { 7 }), wantCover = true))
        assertNull(TagFiles.read(ArraySource(ByteArray(3)), wantCover = true))
    }

    @Test
    fun truncatedApeTagIsIgnored() {
        val tag = apeTag(listOf(text("Title", "x")))
        val broken = tag.copyOf().also { it[tag.size - 20] = 0x7F; it[tag.size - 19] = 0x7F }
        assertNull(TagFiles.read(ArraySource(broken), wantCover = false))
    }

    private fun obj(guid: String, body: ByteArray) = Bytes().raw(Asf.guid(guid)).u64((body.size + 24).toLong()).raw(body).bytes()

    private fun wideZ(s: String) = (s + "\u0000").toByteArray(Charsets.UTF_16LE)

    private fun asf(): ByteArray {
        val fileProps = Bytes().zeros(16).u64(0).u64(0).u64(0).u64(2_150_000_000L + 30_000_000L).u64(0).u64(3000).u32(2).u32(0).u32(0).u32(0).bytes()
        val title = wideZ("Neon Orchard")
        val author = wideZ("Kira Nox")
        val content = Bytes().u16(title.size).u16(author.size).u16(0).u16(0).u16(0).raw(title).raw(author).bytes()
        val picture = Bytes().u8(3).u32(jpeg.size.toLong()).raw(wideZ("image/jpeg")).raw(wideZ("")).raw(jpeg).bytes()
        fun desc(name: String, type: Int, value: ByteArray) = Bytes().u16(wideZ(name).size).raw(wideZ(name)).u16(type).u16(value.size).raw(value).bytes()
        val ext = Bytes().u16(5)
            .raw(desc("WM/AlbumTitle", 0, wideZ("Night Market")))
            .raw(desc("WM/AlbumArtist", 0, wideZ("Kira Nox")))
            .raw(desc("WM/TrackNumber", 3, Bytes().u32(7).bytes()))
            .raw(desc("WM/Year", 0, wideZ("2021")))
            .raw(desc("WM/Picture", 1, picture))
            .bytes()
        val genre = wideZ("Electronic")
        val libName = wideZ("WM/Genre")
        val library = Bytes().u16(1).u16(0).u16(0).u16(libName.size).u16(0).u32(genre.size.toLong()).raw(libName).raw(genre).bytes()
        val extData = obj("44231C94-9498-49D1-A141-1D134E457054", library)
        val headerExt = Bytes().zeros(16).u16(6).u32(extData.size.toLong()).raw(extData).bytes()
        val children = listOf(
            obj("8CABDCA1-A947-11CF-8EE4-00C00C205365", fileProps),
            obj("75B22633-668E-11CF-A6D9-00AA0062CE6C", content),
            obj("D2D0A440-E307-11D2-97F0-00A0C95EA850", ext),
            obj("5FBF03B5-A92E-11CF-8EE3-00C00C205365", headerExt),
        )
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        val header = Bytes().raw(Asf.guid("75B22630-668E-11CF-A6D9-00AA0062CE6C")).u64((30 + body.size).toLong()).u32(children.size.toLong()).u8(1).u8(2).raw(body).bytes()
        return header + ByteArray(256)
    }

    @Test
    fun readsWmaMetadataPictureAndDuration() {
        val t = TagFiles.read(ArraySource(asf()), wantCover = true)!!
        assertEquals("Neon Orchard", t.title)
        assertEquals("Kira Nox", t.artist)
        assertEquals("Night Market", t.album)
        assertEquals("Kira Nox", t.albumArtist)
        assertEquals(7, t.track)
        assertEquals(2021, t.year)
        assertEquals("Electronic", t.genre)
        assertArrayEquals(jpeg, t.cover)
        assertEquals(218_000L - 3000L, t.durationMs)
        assertNull(TagFiles.read(ArraySource(asf()), wantCover = false)!!.cover)
    }

    @Test
    fun guidIsInDiskByteOrder() {
        val g = Asf.guid("75B22630-668E-11CF-A6D9-00AA0062CE6C")
        assertEquals(listOf(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11), g.take(8).map { it.toInt() and 0xFF })
    }

    private fun song(path: String, title: String, artist: String = UNKNOWN, album: String = UNKNOWN) =
        Song(1, title, artist, album, 1, "", "", 200_000, 0, 1, 0, 0, path, path.substringBeforeLast('/'), "audio/x-ape", 1)

    @Test
    fun ownFormatTagsReplaceStoreGuessesButMp3OnlyFillsGaps() {
        val tags = AudioTags(title = "风的形状", artist = "温屿", album = "海边的回声", track = 3, year = 2023, genre = "(2)")
        val ape = ExtraTags.merge(song("/storage/emulated/0/Music/03 track.ape", "03 track"), tags, prefer = true)
        assertEquals("风的形状", ape.title)
        assertEquals("温屿", ape.artist)
        assertEquals(3, ape.track)
        assertEquals("Country", ape.genre)
        val mp3 = ExtraTags.merge(song("/storage/emulated/0/Music/a.mp3", "Real Title", artist = "Ivy Calder"), tags, prefer = false)
        assertEquals("Real Title", mp3.title)
        assertEquals("Ivy Calder", mp3.artist)
        assertEquals("海边的回声", mp3.album)
    }
}
