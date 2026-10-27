package com.novcloud.echo.data.tags

/**
 * ASF (WMA) metadata. The header object holds child objects; the ones read here are File Properties (duration),
 * Content Description (title, author), Extended Content Description (the WM descriptors, WM/Picture), and inside the
 * Header Extension the Metadata and Metadata Library objects, where large pictures and long values may live.
 * Values are read by offset, so a big embedded picture is only loaded when the cover is wanted.
 */
object Asf {
    private val HEADER = guid("75B22630-668E-11CF-A6D9-00AA0062CE6C")
    private val FILE_PROPERTIES = guid("8CABDCA1-A947-11CF-8EE4-00C00C205365")
    private val CONTENT_DESCRIPTION = guid("75B22633-668E-11CF-A6D9-00AA0062CE6C")
    private val EXTENDED_CONTENT = guid("D2D0A440-E307-11D2-97F0-00A0C95EA850")
    private val HEADER_EXTENSION = guid("5FBF03B5-A92E-11CF-8EE3-00C00C205365")
    private val METADATA = guid("C5F8CBEA-5BAF-4877-8467-AA8C44FA4CCA")
    private val METADATA_LIBRARY = guid("44231C94-9498-49D1-A141-1D134E457054")
    private const val MAX_VALUE = 16 * 1024 * 1024

    fun isAsf(head: ByteArray) = head.size >= 16 && head.copyOf(16).contentEquals(HEADER)

    /** GUID in its on-disk byte order: the first three groups little-endian, the rest as written. */
    fun guid(s: String): ByteArray {
        val hex = s.replace("-", "")
        val raw = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        return ByteArray(16) { raw[order[it]] }
    }

    private class Collector(val wantCover: Boolean) {
        val text = LinkedHashMap<String, String>()
        var cover: ByteArray? = null
        var coverType = Int.MAX_VALUE
        var durationMs = 0L
        var trackZeroBased: Int? = null

        fun put(name: String, value: String) {
            if (value.isBlank()) return
            text.putIfAbsent(name.uppercase(), value.trim())
        }

        fun putNumber(name: String, value: Long) {
            if (name.equals("WM/Track", true)) trackZeroBased = value.toInt() else put(name, value.toString())
        }

        fun picture(b: ByteArray) {
            if (b.size < 5) return
            val type = b[0].toInt() and 0xFF
            val len = le32(b, 1)
            var p = 5
            repeat(2) { p = skipWideString(b, p) }
            if (p >= b.size || len <= 0 || p + len > b.size) return
            val rank = if (type == 3) 0 else 1 + type
            if (rank < coverType) {
                coverType = rank
                cover = b.copyOfRange(p, p + len.toInt())
            }
        }
    }

    fun read(src: ByteSource, wantCover: Boolean): AudioTags? {
        val head = src.read(0, 30)
        if (head.size < 30 || !isAsf(head)) return null
        val headerSize = le64(head, 16)
        val count = le32(head, 24)
        val c = Collector(wantCover)
        children(src, 30, minOf(headerSize, src.size), count) { id, at, size -> obj(src, id, at, size, c) }
        val t = c.text
        val track = AudioTags.number(t["WM/TRACKNUMBER"]).takeIf { it > 0 } ?: c.trackZeroBased?.let { it + 1 } ?: 0
        val tags = AudioTags(
            title = t["TITLE"].orEmpty(),
            artist = t["AUTHOR"] ?: t["WM/ARTISTS"] ?: "",
            album = t["WM/ALBUMTITLE"].orEmpty(),
            albumArtist = t["WM/ALBUMARTIST"].orEmpty(),
            track = track,
            disc = AudioTags.number(t["WM/PARTOFSET"]),
            year = AudioTags.year(t["WM/YEAR"] ?: t["WM/ORIGINALRELEASEYEAR"]),
            genre = t["WM/GENRE"].orEmpty(),
            durationMs = c.durationMs,
            cover = c.cover,
            extra = t,
        )
        return tags.takeIf { !it.isEmpty || it.durationMs > 0 || it.cover != null }
    }

    private inline fun children(src: ByteSource, start: Long, end: Long, count: Long, each: (ByteArray, Long, Long) -> Unit) {
        var p = start
        var n = 0L
        while (p + 24 <= end && n < count) {
            val h = src.read(p, 24)
            if (h.size < 24) return
            val size = le64(h, 16)
            if (size < 24 || p + size > end) return
            each(h.copyOf(16), p, size)
            p += size
            n++
        }
    }

    private fun obj(src: ByteSource, id: ByteArray, at: Long, size: Long, c: Collector) {
        val body = at + 24
        when {
            id.contentEquals(FILE_PROPERTIES) -> {
                val b = src.read(body, 80)
                if (b.size < 72) return
                val play = le64(b, 40)
                val preroll = le64(b, 56)
                c.durationMs = (play / 10_000 - preroll).coerceAtLeast(0)
            }
            id.contentEquals(CONTENT_DESCRIPTION) -> {
                val b = src.read(body, (size - 24).toInt().coerceAtMost(64 * 1024))
                if (b.size < 10) return
                val lens = IntArray(5) { le16(b, it * 2) }
                var p = 10
                val names = listOf("TITLE", "AUTHOR", "COPYRIGHT", "DESCRIPTION", "RATING")
                for (i in 0 until 5) {
                    if (p + lens[i] > b.size) return
                    c.put(names[i], wide(b, p, lens[i]))
                    p += lens[i]
                }
            }
            id.contentEquals(EXTENDED_CONTENT) -> extended(src, body, at + size, c)
            id.contentEquals(HEADER_EXTENSION) -> {
                val b = src.read(body, 22)
                if (b.size < 22) return
                val dataSize = le32(b, 18)
                val start = body + 22
                children(src, start, minOf(start + dataSize, at + size), Long.MAX_VALUE) { cid, cat, csize ->
                    if (cid.contentEquals(METADATA) || cid.contentEquals(METADATA_LIBRARY)) records(src, cat + 24, cat + csize, c)
                }
            }
        }
    }

    private fun extended(src: ByteSource, start: Long, end: Long, c: Collector) {
        val n = le16(src.read(start, 2).takeIf { it.size == 2 } ?: return, 0)
        var p = start + 2
        repeat(n) {
            val nl = src.read(p, 2).takeIf { it.size == 2 } ?: return
            val nameLen = le16(nl, 0)
            val name = wide(src.read(p + 2, nameLen), 0, nameLen)
            val tv = src.read(p + 2 + nameLen, 4).takeIf { it.size == 4 } ?: return
            val type = le16(tv, 0)
            val valueLen = le16(tv, 2)
            val valueAt = p + 6 + nameLen
            if (valueAt + valueLen > end) return
            value(src, name, type, valueAt, valueLen.toLong(), c)
            p = valueAt + valueLen
        }
    }

    private fun records(src: ByteSource, start: Long, end: Long, c: Collector) {
        val n = le16(src.read(start, 2).takeIf { it.size == 2 } ?: return, 0)
        var p = start + 2
        repeat(n) {
            val h = src.read(p, 12).takeIf { it.size == 12 } ?: return
            val nameLen = le16(h, 4)
            val type = le16(h, 6)
            val dataLen = le32(h, 8)
            val name = wide(src.read(p + 12, nameLen), 0, nameLen)
            val valueAt = p + 12 + nameLen
            if (valueAt + dataLen > end) return
            value(src, name, type, valueAt, dataLen, c)
            p = valueAt + dataLen
        }
    }

    private fun value(src: ByteSource, name: String, type: Int, at: Long, len: Long, c: Collector) {
        when (type) {
            0 -> if (len <= 64 * 1024) c.put(name, wide(src.read(at, len.toInt()), 0, len.toInt()))
            1 -> if (name.equals("WM/Picture", true) && c.wantCover && len <= MAX_VALUE) c.picture(src.read(at, len.toInt()))
            3 -> if (len >= 4) c.putNumber(name, le32(src.read(at, 4), 0))
            4 -> if (len >= 8) c.putNumber(name, le64(src.read(at, 8), 0))
            5 -> if (len >= 2) c.putNumber(name, le16(src.read(at, 2), 0).toLong())
        }
    }

    private fun wide(b: ByteArray, off: Int, len: Int): String {
        if (len <= 0 || off + len > b.size) return ""
        return String(b, off, len - (len and 1), Charsets.UTF_16LE).trimEnd('\u0000').trim()
    }

    private fun skipWideString(b: ByteArray, from: Int): Int {
        var p = from
        while (p + 1 < b.size && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
        return p + 2
    }
}
