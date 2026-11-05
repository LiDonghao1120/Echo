package com.novcloud.echo.data

import com.novcloud.echo.data.lyrics.LyricsParser
import com.novcloud.echo.data.lyrics.TagReader
import com.novcloud.echo.data.tags.ArraySource
import com.novcloud.echo.data.tags.ByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Synthetic files for every container the embedded lyric reader handles; lyrics are made up. */
class EmbeddedLyricsTest {
    private val lrc = "[00:01.00]Paper lanterns drift\n[00:04.50]Over the quiet bay\n"

    // ---------- byte helpers ----------
    private fun bytes(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    private fun ByteArrayOutputStream.be32(v: Long) = write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    private fun ByteArrayOutputStream.le32(v: Long) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    private fun ByteArrayOutputStream.be64(v: Long) { be32(v ushr 32); be32(v and 0xFFFFFFFFL) }
    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())

    /** A source of [prefix] followed by [zeros] virtual zero bytes and then [suffix], counting what was read. */
    private class Sparse(val prefix: ByteArray, val zeros: Long, val suffix: ByteArray) : ByteSource {
        var bytesRead = 0L
        override val size: Long get() = prefix.size + zeros + suffix.size
        override fun read(position: Long, length: Int): ByteArray {
            if (position < 0 || position >= size || length <= 0) return ByteArray(0)
            val n = minOf(size - position, length.toLong()).toInt()
            bytesRead += n
            return ByteArray(n) { i ->
                val p = position + i
                when {
                    p < prefix.size -> prefix[p.toInt()]
                    p < prefix.size + zeros -> 0
                    else -> suffix[(p - prefix.size - zeros).toInt()]
                }
            }
        }
    }

    private fun read(b: ByteArray) = TagReader.lyrics(ArraySource(b))

    // ---------- ID3 ----------
    private fun usltBody(text: String, enc: Int = 3): ByteArray = bytes {
        write(enc); ascii("eng")
        when (enc) {
            1 -> { write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)); write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); write(text.toByteArray(Charsets.UTF_16LE)) }
            else -> { write(0); write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun frame23(id: String, body: ByteArray, flags: Int = 0) = bytes { ascii(id); be32(body.size.toLong()); write(flags shr 8); write(flags) }
        .plus(body)

    private fun frame24(id: String, body: ByteArray, flags: Int = 0) = bytes { ascii(id); write(syncsafe(body.size)); write(flags shr 8); write(flags) }
        .plus(body)

    private fun id3(major: Int, frames: ByteArray, flags: Int = 0, padding: Int = 32) = bytes {
        ascii("ID3"); write(major); write(0); write(flags); write(syncsafe(frames.size + padding)); write(frames); write(ByteArray(padding))
    }

    private fun unsync(b: ByteArray): ByteArray = bytes {
        b.forEach { v -> write(v.toInt()); if (v == 0xFF.toByte()) write(0) }
    }

    private fun mp3(tag: ByteArray) = tag + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(400)

    @Test fun id3v23Uslt() {
        assertEquals(lrc.trim(), read(mp3(id3(3, frame23("TIT2", byteArrayOf(3) + "Harbor".toByteArray()) + frame23("USLT", usltBody(lrc))))))
    }

    @Test fun id3v23WholeTagUnsynchronisation() {
        // UTF-16LE puts 0xFF bytes in the frame (BOM and ÿ), so the unsynchronised tag differs from the plain one.
        val text = "[00:02.00]Ÿour ÿellow kite"
        val plain = frame23("TIT2", byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "ÿ".toByteArray(Charsets.UTF_16LE)) + frame23("USLT", usltBody(text, enc = 1))
        val body = unsync(plain)
        assertTrue(body.size > plain.size)
        val file = mp3(id3(3, body, flags = 0x80))
        assertEquals(text, read(file))
    }

    @Test fun id3v24PerFrameUnsynchronisationWithLengthIndicator() {
        val text = "[00:03.00]ÿ marks the shore"
        val data = usltBody(text, enc = 1)
        val body = syncsafe(data.size) + unsync(data)
        val file = mp3(id3(4, frame24("TIT2", byteArrayOf(3) + "Shore".toByteArray()) + frame24("USLT", body, flags = 0x0002 or 0x0001)))
        assertEquals(text, read(file))
    }

    @Test fun id3v24CompressedFrame() {
        val data = usltBody(lrc)
        val z = Deflater().run { setInput(data); finish(); val out = ByteArray(4096); val n = deflate(out); end(); out.copyOf(n) }
        val file = mp3(id3(4, frame24("USLT", syncsafe(data.size) + z, flags = 0x0008 or 0x0001)))
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun id3v22Ult() {
        val body = usltBody(lrc)
        val frame = bytes { ascii("TT2"); write(0); write(0); write(4); write(0); ascii("Bay") } +
            bytes { ascii("ULT"); write(body.size shr 16); write(body.size shr 8); write(body.size) } + body
        assertEquals(lrc.trim(), read(mp3(id3(2, frame))))
    }

    @Test fun id3v24ITunesPlainFrameSizes() {
        val body = usltBody("[00:01.00]" + "long line of lanterns ".repeat(8))
        assertTrue(body.size > 127)
        val raw = bytes { ascii("USLT"); be32(body.size.toLong()); write(0); write(0) } + body
        val text = read(mp3(id3(4, raw)))
        assertNotNull(text)
        assertTrue(text!!.startsWith("[00:01.00]long line"))
    }

    private fun syltBody(entries: List<Pair<String, Int>>, enc: Int = 3) = bytes {
        write(enc); ascii("eng"); write(2); write(1); write(0)
        entries.forEach { (t, ms) -> write(t.toByteArray(Charsets.UTF_8)); write(0); be32(ms.toLong()) }
    }

    @Test fun syltLinesBecomeLrc() {
        val file = mp3(id3(3, frame23("SYLT", syltBody(listOf("Paper lanterns drift" to 1000, "Over the quiet bay" to 4500)))))
        val lyrics = LyricsParser.parse(read(file)!!)!!
        assertTrue(lyrics.synced)
        assertEquals(listOf(1000L, 4500L), lyrics.lines.map { it.timeMs })
        assertEquals("Over the quiet bay", lyrics.lines[1].text)
    }

    @Test fun syltSyllablesBecomeWordTimedLines() {
        val entries = listOf("Pa" to 1000, "per " to 1300, "lan" to 1600, "terns" to 1900, "\nO" to 4500, "ver " to 4800, "the " to 5100, "bay" to 5400)
        val lyrics = LyricsParser.parse(read(mp3(id3(3, frame23("SYLT", syltBody(entries)))))!!)!!
        assertEquals(2, lyrics.lines.size)
        assertEquals("Paper lanterns", lyrics.lines[0].text)
        val words = lyrics.lines[0].words!!
        assertEquals(listOf(1000L, 1300L, 1600L, 1900L), words.map { it.timeMs })
        assertEquals(4500L, lyrics.lines[1].timeMs)
        assertEquals("Over the bay", lyrics.lines[1].text)
    }

    @Test fun syncedUsltWinsOverPlainAndSyltOverPlain() {
        val plain = frame23("USLT", usltBody("just words\nno timing"))
        val sylt = frame23("SYLT", syltBody(listOf("Timed line" to 2000)))
        assertTrue(read(mp3(id3(3, plain + sylt)))!!.startsWith("[00:02.00]Timed line"))
        assertEquals(lrc.trim(), read(mp3(id3(3, plain + frame23("USLT", usltBody(lrc))))))
    }

    @Test fun wavAndAiffId3Chunks() {
        val tag = id3(3, frame23("USLT", usltBody(lrc)))
        val pcm = ByteArray(1001)
        val wav = bytes {
            ascii("RIFF"); le32(0); ascii("WAVE")
            ascii("fmt "); le32(16); write(ByteArray(16))
            ascii("data"); le32(pcm.size.toLong()); write(pcm); write(0)
            ascii("id3 "); le32(tag.size.toLong()); write(tag)
        }
        assertEquals(lrc.trim(), read(wav))
        val aiff = bytes {
            ascii("FORM"); be32(0); ascii("AIFF")
            ascii("COMM"); be32(18); write(ByteArray(18))
            ascii("SSND"); be32(pcm.size.toLong()); write(pcm); write(0)
            ascii("ID3 "); be32(tag.size.toLong()); write(tag)
        }
        assertEquals(lrc.trim(), read(aiff))
    }

    // ---------- FLAC ----------
    private fun vorbisComment(vararg fields: String) = bytes {
        val vendor = "synthetic".toByteArray()
        le32(vendor.size.toLong()); write(vendor); le32(fields.size.toLong())
        fields.forEach { f -> val b = f.toByteArray(Charsets.UTF_8); le32(b.size.toLong()); write(b) }
    }

    @Test fun flacSkipsPicturesAndFindsLyricsInAnyCase() {
        val picture = ByteArray(300_000)
        val comment = vorbisComment("TITLE=Harbor", "Lyrics=$lrc")
        val file = bytes {
            ascii("fLaC")
            write(0); write(0); write(0); write(34); write(ByteArray(34))
            write(6); write(picture.size shr 16); write(picture.size shr 8); write(picture.size); write(picture)
            write(0x84); write(comment.size shr 16); write(comment.size shr 8); write(comment.size); write(comment)
        } + ByteArray(100)
        val src = Sparse(file, 0, ByteArray(0))
        assertEquals(lrc.trim(), TagReader.lyrics(src))
        assertTrue("read ${src.bytesRead}", src.bytesRead < 10_000)
    }

    // ---------- Ogg ----------
    /** Splits packets into pages of at most [maxSegments] lacing values, like an encoder would. */
    private fun ogg(packets: List<ByteArray>, maxSegments: Int = 255): ByteArray {
        val lacing = ArrayList<Pair<Int, ByteArray?>>()
        packets.forEach { p ->
            var at = 0
            while (p.size - at >= 255) { lacing += 255 to p.copyOfRange(at, at + 255); at += 255 }
            lacing += (p.size - at) to p.copyOfRange(at, p.size)
        }
        val out = ByteArrayOutputStream()
        var seq = 0L
        lacing.chunked(maxSegments).forEach { page ->
            out.ascii("OggS"); out.write(0); out.write(if (seq == 0L) 2 else 0); out.write(ByteArray(8))
            out.le32(0x1234); out.le32(seq++); out.le32(0); out.write(page.size)
            page.forEach { out.write(it.first) }
            page.forEach { out.write(it.second!!) }
        }
        return out.toByteArray()
    }

    @Test fun oggVorbisCommentAcrossManyPages() {
        val id = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)
        val art = "METADATA_BLOCK_PICTURE=" + "A".repeat(200_000)
        val comments = byteArrayOf(3) + "vorbis".toByteArray() + vorbisComment("TITLE=Bay", art, "unsyncedlyrics=plain words", "lyrics=$lrc") + byteArrayOf(1)
        val setup = byteArrayOf(5) + "vorbis".toByteArray() + ByteArray(50)
        val file = ogg(listOf(id, comments, setup))
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun opusTagsPreferSyncedLyrics() {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val tags = "OpusTags".toByteArray() + vorbisComment("UNSYNCEDLYRICS=words only", "SyncedLyrics=$lrc")
        val file = ogg(listOf(head, tags, ByteArray(700)), maxSegments = 2)
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun oggOtherStreamsAreIgnored() {
        val head = "OggSkel\u0000".toByteArray() + ByteArray(20)
        assertNull(read(ogg(listOf(head, ByteArray(30)))))
    }

    // ---------- MP4 ----------
    private fun box(type: String, body: ByteArray) = bytes { be32(8L + body.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }
    private fun boxRaw(type: ByteArray, body: ByteArray) = bytes { be32(8L + body.size); write(type); write(body) }
    private fun dataAtom(text: String, type: Int = 1) = box("data", bytes { be32(type.toLong()); be32(0); write(if (type == 2) text.toByteArray(Charsets.UTF_16BE) else text.toByteArray()) })
    private val lyr = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
    private val nam = byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
    private val hdlr = box("hdlr", ByteArray(25))

    private fun moov(ilst: ByteArray, isoMeta: Boolean = true): ByteArray {
        val meta = box("meta", (if (isoMeta) ByteArray(4) else ByteArray(0)) + hdlr + box("ilst", ilst))
        return box("moov", box("mvhd", ByteArray(100)) + box("trak", ByteArray(200)) + box("udta", meta))
    }

    private val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(4) + "M4A mp42isom".toByteArray())

    @Test fun mp4LyrAfterMdat() {
        val ilst = boxRaw(nam, dataAtom("Harbor")) + boxRaw(lyr, dataAtom(lrc))
        val file = ftyp + box("mdat", ByteArray(5000)) + moov(ilst)
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun mp4LargeMdatWith64BitSizeIsSkippedWithoutReading() {
        val mdatData = 5_000_000_000L
        val mdatHead = bytes { be32(1); ascii("mdat"); be64(16 + mdatData) }
        val tail = moov(boxRaw(lyr, dataAtom(lrc, type = 2)))
        val src = Sparse(ftyp + mdatHead, mdatData, tail)
        assertEquals(lrc.trim(), TagReader.lyrics(src))
        assertTrue("read ${src.bytesRead}", src.bytesRead < 4_000)
    }

    @Test fun mp4QuickTimeMetaAndFreeformLyrics() {
        val freeform = box("----", box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) + box("name", ByteArray(4) + "LYRICS".toByteArray()) + dataAtom(lrc))
        val file = ftyp + moov(freeform, isoMeta = false) + box("mdat", ByteArray(64))
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun mp4AppleTtmlLyricsKeepWordTimingAndTranslation() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <body><div>
            <p begin="00:01.000" end="00:03.000"><span begin="00:01.000" end="00:01.600">Paper </span><span begin="00:01.600" end="00:03.000">lanterns</span></p>
            </div></body></tt>"""
        val text = read(ftyp + moov(boxRaw(lyr, dataAtom(ttml))))!!
        val lyrics = LyricsParser.parse(text)!!
        assertEquals("Paper lanterns", lyrics.lines[0].text)
        assertEquals(listOf(1000L, 1600L), lyrics.lines[0].words!!.map { it.timeMs })
    }

    @Test fun embeddedBilingualLrcStillSplits() {
        val bilingual = "[00:01.00]Paper lanterns drift\n[00:01.00]纸灯笼漂流\n[00:04.00]Over the quiet bay\n[00:04.00]越过安静的海湾\n"
        val lyrics = LyricsParser.parse(read(ftyp + moov(boxRaw(lyr, dataAtom(bilingual))))!!)!!
        assertEquals("纸灯笼漂流", lyrics.lines[0].translation)
    }

    @Test fun id3InFrontOfFlacFallsThroughToVorbisComments() {
        val comment = vorbisComment("LYRICS=$lrc")
        val flac = bytes {
            ascii("fLaC")
            write(0x84); write(comment.size shr 16); write(comment.size shr 8); write(comment.size); write(comment)
        }
        val file = id3(3, frame23("TIT2", byteArrayOf(3) + "Bay".toByteArray())) + flac
        assertEquals(lrc.trim(), read(file))
    }

    @Test fun garbageAndTruncatedFilesGiveNothing() {
        assertNull(read(ByteArray(0)))
        assertNull(read("not audio at all".toByteArray()))
        val full = ftyp + moov(boxRaw(lyr, dataAtom(lrc)))
        assertNull(read(full.copyOf(full.size - 30)))
        val tag = mp3(id3(3, frame23("USLT", usltBody(lrc))))
        assertNull(read(tag.copyOf(30)))
    }
}
