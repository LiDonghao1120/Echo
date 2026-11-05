package com.novcloud.echo.data.lyrics

import com.novcloud.echo.data.tags.ArraySource
import com.novcloud.echo.data.tags.ByteSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Inflater

/**
 * Lyrics stored inside the audio file. Reads only headers and the lyric fields themselves through a [ByteSource],
 * jumping over audio data and pictures, so a 100 MB file with a 5 MB cover costs a few small reads.
 *
 * - ID3v2.2–2.4 (MP3, and the ID3 chunk of WAV/AIFF, or an ID3 tag in front of FLAC): USLT/ULT, SYLT/SLT converted
 *   to LRC (word-timed when the entries are syllables), whole-tag and per-frame unsynchronisation, compressed frames.
 * - FLAC and Ogg (Vorbis, Opus, Ogg FLAC) Vorbis comments: LYRICS, UNSYNCEDLYRICS, SYNCEDLYRICS in any case, comment
 *   packets spread over many pages.
 * - MP4/M4A/ALAC: moov/udta/meta/ilst ©lyr (or a freeform ----:…:LYRICS) data atom, 64-bit atom sizes, mdat anywhere.
 *
 * Timed text (LRC, TTML) is preferred over plain text when a file carries both.
 */
object TagReader {
    /** Upper bound for one lyric field; anything larger is not lyrics. */
    internal const val MAX_TEXT = 4 * 1024 * 1024

    fun lyrics(src: ByteSource): String? = runCatching { read(src) }.getOrNull()?.trim('\u0000', ' ', '\n', '\r', '\uFEFF')?.takeIf { it.isNotBlank() }

    /** For callers holding a stream (tests): the stream is read into memory. */
    fun lyrics(input: InputStream): String? = lyrics(ArraySource(input.readBytes()))

    private fun read(src: ByteSource): String? {
        val head = src.read(0, 12)
        if (head.size < 4) return null
        val tag4 = String(head, 0, 4, Charsets.ISO_8859_1)
        return when {
            tag4.startsWith("ID3") -> {
                val id3Size = Id3.totalSize(head)
                val found = Id3.lyrics(src, 0)
                val after = if (id3Size > 0) String(src.read(id3Size, 4), Charsets.ISO_8859_1) else ""
                when {
                    found != null && found.timed -> found.text
                    after == "fLaC" -> Vorbis.flac(src, id3Size + 4)?.let { best(listOf(it), found) } ?: found?.text
                    else -> found?.text
                }
            }
            tag4 == "fLaC" -> Vorbis.flac(src, 4)?.text
            tag4 == "OggS" -> Vorbis.ogg(src)?.text
            tag4 == "RIFF" || tag4 == "RF64" -> if (head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") Riff.lyrics(src, littleEndian = true) else null
            tag4 == "FORM" -> if (head.size >= 12 && String(head, 8, 3, Charsets.ISO_8859_1) == "AIF") Riff.lyrics(src, littleEndian = false) else null
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in Mp4.TOP_LEVEL -> Mp4.lyrics(src)
            else -> null
        }
    }

    private fun best(found: List<Found>, other: Found?): String? = (found + listOfNotNull(other)).let { all -> (all.firstOrNull { it.timed } ?: all.firstOrNull())?.text }

    internal class Found(val text: String, val timed: Boolean)

    internal fun found(text: String?): Found? {
        val t = text?.trim('\u0000', ' ', '\n', '\r', '\uFEFF')?.takeIf { it.isNotBlank() } ?: return null
        return Found(t, LyricsParser.parse(t)?.synced == true)
    }

    /** First timed candidate, otherwise the first one. */
    internal fun pick(candidates: List<Found>): Found? = candidates.firstOrNull { it.timed } ?: candidates.firstOrNull()

    internal fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    internal fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}

/** ID3v2.2, 2.3 and 2.4 lyric frames. */
internal object Id3 {
    /** Bytes taken by the tag starting at [head] (header, body and v2.4 footer), or 0 when it is not an ID3v2 header. */
    fun totalSize(head: ByteArray): Long {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val body = syncsafe(head, 6)
        if (body < 0) return 0
        val footer = if (head[3].toInt() == 4 && head[5].toInt() and 0x10 != 0) 10 else 0
        return 10L + body + footer
    }

    fun lyrics(src: ByteSource, at: Long): TagReader.Found? {
        val h = src.read(at, 10)
        if (h.size < 10 || totalSize(h) == 0L) return null
        val major = h[3].toInt()
        if (major !in 2..4) return null
        val flags = h[5].toInt() and 0xFF
        val size = syncsafe(h, 6).toLong()
        val tagUnsync = flags and 0x80 != 0
        if (major == 2 && flags and 0x40 != 0) return null
        // v2.2 and v2.3 unsynchronise the whole tag body (frame headers included); v2.4 marks it per frame.
        val s = Id3Stream(src, at + 10, size, unsync = tagUnsync && major < 4)
        if (major >= 3 && flags and 0x40 != 0) {
            val ext = s.bytes(4) ?: return null
            if (major == 4) s.skip((syncsafe(ext, 0) - 4).toLong()) else s.skip(TagReader.be32(ext, 0))
        }
        val plain = ArrayList<TagReader.Found>()
        val synced = ArrayList<TagReader.Found>()
        val headerLen = if (major == 2) 6 else 10
        while (s.remaining >= headerLen) {
            val fh = s.bytes(headerLen) ?: break
            if (fh[0].toInt() == 0) break
            val id = String(fh, 0, if (major == 2) 3 else 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            var frameSize: Long
            var fFlags = 0
            if (major == 2) {
                frameSize = ((fh[3].toLong() and 0xFF) shl 16) or ((fh[4].toLong() and 0xFF) shl 8) or (fh[5].toLong() and 0xFF)
            } else {
                val raw = TagReader.be32(fh, 4)
                frameSize = if (major == 4) v24FrameSize(raw, s) else raw
                fFlags = ((fh[8].toInt() and 0xFF) shl 8) or (fh[9].toInt() and 0xFF)
            }
            if (frameSize <= 0 || frameSize > s.remaining) break
            val wanted = when (id) {
                "USLT", "ULT" -> 1
                "SYLT", "SLT" -> 2
                else -> 0
            }
            if (wanted == 0 || frameSize > TagReader.MAX_TEXT * 2L) {
                s.skip(frameSize)
                continue
            }
            val raw = s.bytes(frameSize.toInt()) ?: break
            val data = frameData(raw, major, fFlags, tagUnsync) ?: continue
            if (wanted == 1) uslt(data)?.let { TagReader.found(it) }?.let { if (it.timed) synced += it else plain += it }
            else sylt(data)?.let { TagReader.found(it) }?.let { synced += it }
        }
        return TagReader.pick(synced + plain)
    }

    /** iTunes once wrote plain sizes into v2.4 frames; a byte with its high bit set can only be that. */
    private fun v24FrameSize(raw: Long, s: Id3Stream): Long {
        if (raw and 0x80808080L != 0L) return raw
        return ((raw and 0x7F) or ((raw shr 8 and 0x7F) shl 7) or ((raw shr 16 and 0x7F) shl 14) or ((raw shr 24 and 0x7F) shl 21)).let { safe ->
            if (safe != raw && safe > s.remaining && raw <= s.remaining) raw else safe
        }
    }

    /** Undoes per-frame unsynchronisation, the v2.4 data length indicator, grouping and zlib compression. */
    private fun frameData(raw: ByteArray, major: Int, flags: Int, tagUnsync: Boolean): ByteArray? {
        var d = raw
        if (major == 4) {
            if (flags and 0x0004 != 0) return null
            var skip = 0
            if (flags and 0x0040 != 0) skip += 1
            if (flags and 0x0001 != 0) skip += 4
            if (flags and 0x0002 != 0 || tagUnsync) d = resync(d, 0, d.size)
            if (skip > d.size) return null
            if (skip > 0) d = d.copyOfRange(skip, d.size)
            if (flags and 0x0008 != 0) d = inflate(d) ?: return null
        } else if (major == 3) {
            if (flags and 0x0040 != 0) return null
            var skip = 0
            if (flags and 0x0080 != 0) skip += 4
            if (flags and 0x0020 != 0) skip += 1
            if (skip > d.size) return null
            if (skip > 0) d = d.copyOfRange(skip, d.size)
            if (flags and 0x0080 != 0) d = inflate(d) ?: return null
        }
        return d
    }

    private fun inflate(d: ByteArray): ByteArray? = runCatching {
        val inf = Inflater()
        inf.setInput(d)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > TagReader.MAX_TEXT * 2) return null
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    fun resync(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val v = b[i]
            out.write(v.toInt())
            if (v == 0xFF.toByte() && i + 1 < to && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun uslt(d: ByteArray): String? {
        if (d.size < 5) return null
        val enc = d[0].toInt()
        val descEnd = terminator(d, 4, enc)
        if (descEnd < 0) return null
        val bodyAt = descEnd + width(enc)
        if (bodyAt > d.size) return null
        return decode(d, bodyAt, d.size, enc).trim('\u0000', ' ', '\n', '\r').takeIf { it.isNotBlank() }
    }

    /**
     * SYLT as LRC. Entries are either whole lines or syllables; syllable entries mark a new line with a leading
     * line break, and come out as word-timed LRC (`[mm:ss.xx]<mm:ss.xx>syl<mm:ss.xx>syl`). Only millisecond
     * timestamps can be converted (MPEG frame counts depend on the stream).
     */
    private fun sylt(d: ByteArray): String? {
        if (d.size < 7) return null
        val enc = d[0].toInt()
        if (d[4].toInt() != 2) return null
        val descEnd = terminator(d, 6, enc)
        if (descEnd < 0) return null
        var p = descEnd + width(enc)
        val entries = ArrayList<Pair<String, Long>>()
        while (p < d.size) {
            val end = terminator(d, p, enc).let { if (it < 0) d.size else it }
            val text = decode(d, p, end, enc)
            p = end + width(enc)
            if (p + 4 > d.size) break
            entries += text to TagReader.be32(d, p)
            p += 4
        }
        if (entries.isEmpty()) return null
        return syltToLrc(entries)
    }

    internal fun syltToLrc(entries: List<Pair<String, Long>>): String? {
        val syllables = entries.any { (t, _) -> t.startsWith('\n') || t.startsWith('\r') || t.endsWith('\n') } && entries.size > 1
        val out = StringBuilder()
        if (!syllables) {
            entries.forEach { (t, ms) ->
                val line = t.trim('\n', '\r', '\u0000')
                if (line.isNotBlank()) out.append(stamp(ms, '[', ']')).append(line).append('\n')
            }
        } else {
            val lines = ArrayList<MutableList<Pair<String, Long>>>()
            entries.forEach { (t, ms) ->
                val breakBefore = t.startsWith('\n') || t.startsWith('\r')
                if (breakBefore || lines.isEmpty() || lines.last().lastOrNull()?.first?.endsWith('\n') == true) lines.add(ArrayList())
                lines.last().add(t.trimStart('\n', '\r') to ms)
            }
            lines.forEach { line ->
                val words = line.map { it.first.trimEnd('\n', '\r') to it.second }.filter { it.first.isNotEmpty() }
                if (words.none { it.first.isNotBlank() }) return@forEach
                out.append(stamp(words.first().second, '[', ']'))
                words.forEach { (t, ms) -> out.append(stamp(ms, '<', '>')).append(t) }
                out.append('\n')
            }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    private fun stamp(ms: Long, open: Char, close: Char): String {
        val m = ms / 60_000
        val s = ms / 1000 % 60
        val cs = ms % 1000 / 10
        return "$open${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}$close"
    }

    private fun width(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** Index of the string terminator at or after [from], or -1. */
    private fun terminator(d: ByteArray, from: Int, enc: Int): Int {
        if (width(enc) == 2) {
            var i = from
            while (i + 1 < d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) return i
                i += 2
            }
            return -1
        }
        for (i in from until d.size) if (d[i].toInt() == 0) return i
        return -1
    }

    private fun decode(d: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        val body = d.copyOfRange(from, to)
        return when (enc) {
            0 -> TextDecoder.latinOrGbk(body)
            1 -> when {
                body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() -> String(body, 2, body.size - 2, Charsets.UTF_16LE)
                body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() -> String(body, 2, body.size - 2, Charsets.UTF_16BE)
                else -> String(body, Charsets.UTF_16LE)
            }
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }.trimStart('\uFEFF')
    }

    fun syncsafe(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return -1
        return ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    }
}

/** Tag body reader that undoes whole-tag unsynchronisation on the fly, in bounded chunks. */
internal class Id3Stream(private val src: ByteSource, start: Long, private val length: Long, private val unsync: Boolean) {
    private var pos = start
    private val end = minOf(start + length, if (src.size >= 0) src.size else Long.MAX_VALUE)
    private var lastFF = false
    /** Decoded bytes still available, approximately (exact without unsynchronisation). */
    val remaining: Long get() = end - pos

    fun bytes(n: Int): ByteArray? {
        if (n < 0) return null
        if (!unsync) {
            if (n > remaining) return null
            val b = src.read(pos, n)
            pos += b.size
            return if (b.size == n) b else null
        }
        val out = ByteArrayOutputStream(n)
        while (out.size() < n && pos < end) {
            val chunk = src.read(pos, minOf(CHUNK.toLong(), end - pos).toInt())
            if (chunk.isEmpty()) return null
            var i = 0
            while (i < chunk.size && out.size() < n) {
                val v = chunk[i]
                if (!(lastFF && v.toInt() == 0)) out.write(v.toInt())
                lastFF = v == 0xFF.toByte() && !(lastFF && v.toInt() == 0)
                i++
            }
            pos += i
        }
        return if (out.size() == n) out.toByteArray() else null
    }

    fun skip(n: Long) {
        if (n <= 0) return
        if (!unsync) {
            pos = minOf(end, pos + n)
            lastFF = false
            return
        }
        var left = n
        while (left > 0 && pos < end) {
            val take = minOf(left, CHUNK.toLong()).toInt()
            val b = bytes(take) ?: return
            left -= b.size
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/** Vorbis comments in FLAC and in Ogg Vorbis / Opus / FLAC streams. */
internal object Vorbis {
    private val KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCED LYRICS")

    /** Native FLAC: metadata blocks from [at] (just after "fLaC"), skipping pictures and padding without reading them. */
    fun flac(src: ByteSource, at: Long): TagReader.Found? {
        var p = at
        repeat(512) {
            val h = src.read(p, 4)
            if (h.size < 4) return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            if (type == 4) return comments(SourceReader(src, p + 4, len), framed = false)
            if (type == 127) return null
            p += 4 + len
            if (last) return null
        }
        return null
    }

    /** First logical stream of an Ogg file: its second packet is the comment header. */
    fun ogg(src: ByteSource): TagReader.Found? {
        val packets = OggPackets(src)
        val first = packets.next() ?: return null
        val idHead = first.bytes(8) ?: return null
        val kind = when {
            idHead[0].toInt() == 1 && String(idHead, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 1
            String(idHead, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> 2
            idHead[0].toInt() == 0x7F && String(idHead, 1, 4, Charsets.ISO_8859_1) == "FLAC" -> 3
            else -> return null
        }
        val second = packets.next() ?: return null
        when (kind) {
            1 -> {
                val m = second.bytes(7) ?: return null
                if (m[0].toInt() != 3 || String(m, 1, 6, Charsets.ISO_8859_1) != "vorbis") return null
            }
            2 -> {
                val m = second.bytes(8) ?: return null
                if (String(m, 0, 8, Charsets.ISO_8859_1) != "OpusTags") return null
            }
            3 -> {
                val h = second.bytes(4) ?: return null
                if (h[0].toInt() and 0x7F != 4) return null
            }
        }
        return comments(second, framed = kind == 1)
    }

    /** Walks a comment list, reading only the values of lyric keys. */
    private fun comments(r: Reader, @Suppress("UNUSED_PARAMETER") framed: Boolean): TagReader.Found? {
        val vendor = r.bytes(4)?.let { TagReader.le32(it, 0) } ?: return null
        r.skip(vendor)
        val count = r.bytes(4)?.let { TagReader.le32(it, 0) } ?: return null
        val found = ArrayList<TagReader.Found>()
        var synced: TagReader.Found? = null
        for (i in 0 until minOf(count, 100_000L)) {
            val len = r.bytes(4)?.let { TagReader.le32(it, 0) } ?: break
            val peek = minOf(len, 64L).toInt()
            val head = r.bytes(peek) ?: break
            val eq = head.indexOf('='.code.toByte())
            val key = if (eq > 0) String(head, 0, eq, Charsets.UTF_8).uppercase() else ""
            if (key in KEYS && len <= TagReader.MAX_TEXT) {
                val rest = r.bytes((len - peek).toInt()) ?: break
                val all = head + rest
                val value = String(all, eq + 1, all.size - eq - 1, Charsets.UTF_8)
                val f = TagReader.found(value) ?: continue
                if (key.startsWith("SYNCED")) synced = synced ?: f else found += f
            } else {
                r.skip(len - peek)
            }
        }
        return TagReader.pick(listOfNotNull(synced) + found)
    }
}

/** Sequential reader over a byte range, or over one Ogg packet that spans pages. */
internal interface Reader {
    fun bytes(n: Int): ByteArray?
    fun skip(n: Long)
}

internal class SourceReader(private val src: ByteSource, private var pos: Long, length: Long) : Reader {
    private val end = pos + length
    override fun bytes(n: Int): ByteArray? {
        if (n < 0 || pos + n > end) return null
        val b = src.read(pos, n)
        pos += b.size
        return if (b.size == n) b else null
    }

    override fun skip(n: Long) {
        pos = minOf(end, pos + maxOf(0, n))
    }
}

/**
 * Packets of the first logical bitstream. A packet is the run of lacing values up to the first one below 255;
 * it can continue on following pages. Only page headers are read when skipping.
 */
internal class OggPackets(private val src: ByteSource) {
    private var pagePos = 0L
    private var serial = -1L
    private var segments = IntArray(0)
    private var segIndex = 0
    private var dataPos = 0L
    private var pages = 0

    private fun nextPage(): Boolean {
        while (pages++ < 100_000) {
            val h = src.read(pagePos, 27)
            if (h.size < 27 || String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return false
            val n = h[26].toInt() and 0xFF
            val table = src.read(pagePos + 27, n)
            if (table.size < n) return false
            val s = TagReader.le32(h, 14)
            val body = table.sumOf { it.toInt() and 0xFF }
            val here = pagePos
            pagePos += 27 + n + body
            if (serial == -1L) serial = s
            if (s != serial) continue
            segments = IntArray(n) { table[it].toInt() and 0xFF }
            segIndex = 0
            dataPos = here + 27 + n
            return true
        }
        return false
    }

    private var current: PacketReader? = null

    /** The next packet as a reader; whatever the previous packet's reader left unread is skipped first. */
    fun next(): Reader? {
        current?.skip(Long.MAX_VALUE)
        if (segIndex >= segments.size && !nextPage()) return null
        return PacketReader().also { current = it }
    }

    private inner class PacketReader : Reader {
        private var segLeft = 0
        private var lastLen = -1

        /** Moves to the next non-empty segment of this packet; false at the packet's end. */
        private fun advance(): Boolean {
            while (segLeft == 0) {
                if (lastLen in 0..254) return false
                if (segIndex >= segments.size && !nextPage()) { lastLen = 0; return false }
                lastLen = segments[segIndex++]
                segLeft = lastLen
            }
            return true
        }

        override fun bytes(n: Int): ByteArray? {
            val out = ByteArrayOutputStream(minOf(n, 1 shl 16))
            while (out.size() < n) {
                if (!advance()) return null
                val take = minOf(segLeft, n - out.size())
                val b = src.read(dataPos, take)
                if (b.size < take) return null
                out.write(b)
                dataPos += take
                segLeft -= take
            }
            return out.toByteArray()
        }

        override fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (!advance()) return
                val take = minOf(segLeft.toLong(), left).toInt()
                dataPos += take
                segLeft -= take
                left -= take
            }
        }
    }
}

/** MP4 / M4A / ALAC: only box headers are read until the lyric data atom. */
internal object Mp4 {
    val TOP_LEVEL = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pdin", "uuid", "moof")
    private val CONTAINERS = setOf("moov", "udta", "meta", "ilst")
    private val LYR = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
    private val FREEFORM_KEYS = setOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS")

    private class Box(val type: String, val raw: ByteArray, val start: Long, val bodyStart: Long, val end: Long)

    private fun boxes(src: ByteSource, from: Long, to: Long): Sequence<Box> = sequence {
        var p = from
        var guard = 0
        while (p + 8 <= to && guard++ < 10_000) {
            val h = src.read(p, 16)
            if (h.size < 8) break
            var size = TagReader.be32(h, 0)
            var header = 8L
            if (size == 1L) {
                if (h.size < 16) break
                size = (TagReader.be32(h, 8) shl 32) or TagReader.be32(h, 12)
                header = 16
            } else if (size == 0L) {
                size = to - p
            }
            if (size < header || p + size > to) break
            yield(Box(String(h, 4, 4, Charsets.ISO_8859_1), h.copyOfRange(4, 8), p, p + header, p + size))
            p += size
        }
    }

    fun lyrics(src: ByteSource): String? {
        val size = if (src.size >= 0) src.size else Long.MAX_VALUE
        val moov = boxes(src, 0, size).firstOrNull { it.type == "moov" } ?: return null
        val candidates = ArrayList<TagReader.Found>()
        fun ilst(meta: Box) {
            // ISO meta is a full box (4 bytes version/flags); QuickTime-style meta starts with its children.
            val probe = src.read(meta.bodyStart, 8)
            val first = if (probe.size == 8 && String(probe, 4, 4, Charsets.ISO_8859_1) in setOf("hdlr", "ilst", "keys", "free")) meta.bodyStart else meta.bodyStart + 4
            boxes(src, first, meta.end).filter { it.type == "ilst" }.forEach { list ->
                boxes(src, list.bodyStart, list.end).forEach { item ->
                    when {
                        item.raw.contentEquals(LYR) -> data(src, item)?.let { TagReader.found(it) }?.let { candidates += it }
                        item.type == "----" -> freeform(src, item)?.let { TagReader.found(it) }?.let { candidates += it }
                    }
                }
            }
        }
        boxes(src, moov.bodyStart, moov.end).forEach { child ->
            when (child.type) {
                "udta" -> boxes(src, child.bodyStart, child.end).filter { it.type == "meta" }.forEach(::ilst)
                "meta" -> ilst(child)
            }
        }
        return TagReader.pick(candidates)?.text
    }

    private fun data(src: ByteSource, item: Box): String? {
        val d = boxes(src, item.bodyStart, item.end).firstOrNull { it.type == "data" } ?: return null
        val len = d.end - d.bodyStart - 8
        if (len <= 0 || len > TagReader.MAX_TEXT) return null
        val head = src.read(d.bodyStart, 8)
        if (head.size < 8) return null
        val type = TagReader.be32(head, 0) and 0xFFFFFF
        val body = src.read(d.bodyStart + 8, len.toInt())
        return when (type) {
            2L -> String(body, Charsets.UTF_16BE)
            else -> TextDecoder.decode(body)
        }
    }

    private fun freeform(src: ByteSource, item: Box): String? {
        val name = boxes(src, item.bodyStart, item.end).firstOrNull { it.type == "name" } ?: return null
        val n = (name.end - name.bodyStart - 4).toInt()
        if (n <= 0 || n > 256) return null
        val key = String(src.read(name.bodyStart + 4, n), Charsets.UTF_8).uppercase()
        return if (key in FREEFORM_KEYS) data(src, item) else null
    }
}

/** WAV (RIFF/RF64) and AIFF: the ID3 chunk, if any. */
internal object Riff {
    fun lyrics(src: ByteSource, littleEndian: Boolean): String? {
        var p = 12L
        val size = if (src.size >= 0) src.size else Long.MAX_VALUE
        var guard = 0
        while (p + 8 <= size && guard++ < 10_000) {
            val h = src.read(p, 8)
            if (h.size < 8) break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            var len = if (littleEndian) TagReader.le32(h, 4) else TagReader.be32(h, 4)
            if (id.equals("id3 ", ignoreCase = true)) return Id3.lyrics(src, p + 8)?.text
            if (len == 0xFFFFFFFFL) {
                // RF64 data chunk: its size lives in the ds64 chunk; no ID3 chunk can be found past a chunk we cannot size.
                len = ds64DataSize(src) ?: return null
            }
            p += 8 + len + (len and 1)
        }
        return null
    }

    private fun ds64DataSize(src: ByteSource): Long? {
        val h = src.read(12, 8 + 16)
        if (h.size < 24 || String(h, 0, 4, Charsets.ISO_8859_1) != "ds64") return null
        return TagReader.le32(h, 16) or (TagReader.le32(h, 20) shl 32)
    }
}
