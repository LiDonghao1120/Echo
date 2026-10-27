package com.novcloud.echo.data.tags

/**
 * APEv2 tag (Monkey's Audio, WavPack, Musepack, sometimes MP3): a 32-byte "APETAGEX" footer at the end of the file,
 * or just before a 128-byte ID3v1 tag, pointing back at the items. Each item is value size, flags, a NUL-terminated
 * ASCII key and the value; text values are UTF-8 (multiple values NUL-separated), "Cover Art (Front)" is binary:
 * a file name, NUL, then the image.
 */
object ApeV2 {
    private const val FOOTER = 32
    private const val MAX_TAG = 16 * 1024 * 1024

    fun read(src: ByteSource, wantCover: Boolean): AudioTags? {
        val footerAt = footer(src) ?: return null
        val f = src.read(footerAt, FOOTER)
        val tagSize = le32(f, 12)
        val count = le32(f, 16)
        if (tagSize < FOOTER || tagSize > MAX_TAG || count > 10_000) return null
        val itemsAt = footerAt + FOOTER - tagSize
        if (itemsAt < 0) return null
        val data = src.read(itemsAt, (tagSize - FOOTER).toInt())
        val text = LinkedHashMap<String, String>()
        var cover: ByteArray? = null
        var p = 0
        repeat(count.toInt()) {
            if (p + 9 > data.size) return@repeat
            val valueSize = le32(data, p).toInt()
            val flags = le32(data, p + 4).toInt()
            var k = p + 8
            while (k < data.size && data[k].toInt() != 0) k++
            if (k >= data.size || valueSize < 0 || k + 1 + valueSize > data.size) return@repeat
            val key = String(data, p + 8, k - p - 8, Charsets.ISO_8859_1)
            val v = k + 1
            when ((flags shr 1) and 3) {
                0 -> text[key.uppercase()] = String(data, v, valueSize, Charsets.UTF_8).split('\u0000').first().trim()
                1 -> if (wantCover && key.equals("Cover Art (Front)", true) && cover == null) {
                    var n = v
                    while (n < v + valueSize && data[n].toInt() != 0) n++
                    if (n < v + valueSize) cover = data.copyOfRange(n + 1, v + valueSize)
                }
            }
            p = v + valueSize
        }
        if (text.isEmpty() && cover == null) return null
        return AudioTags(
            title = text["TITLE"].orEmpty(),
            artist = text["ARTIST"].orEmpty(),
            album = text["ALBUM"].orEmpty(),
            albumArtist = text["ALBUM ARTIST"] ?: text["ALBUMARTIST"] ?: "",
            track = AudioTags.number(text["TRACK"] ?: text["TRACKNUMBER"]),
            disc = AudioTags.number(text["DISC"] ?: text["DISCNUMBER"]),
            year = AudioTags.year(text["YEAR"] ?: text["DATE"]),
            genre = text["GENRE"].orEmpty(),
            cover = cover,
            extra = text,
        )
    }

    private fun footer(src: ByteSource): Long? {
        for (at in listOf(src.size - FOOTER, src.size - 128 - FOOTER)) {
            if (at < 0) continue
            val b = src.read(at, 8)
            if (b.size == 8 && String(b, Charsets.ISO_8859_1) == "APETAGEX") return at
        }
        return null
    }
}

/** Monkey's Audio stream header, for the duration. */
object Ape {
    fun skipId3(src: ByteSource): Long {
        val h = src.read(0, 10)
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        val size = ((h[6].toInt() and 0x7F) shl 21) or ((h[7].toInt() and 0x7F) shl 14) or ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F)
        val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
        return 10L + size + footer
    }

    fun durationMs(src: ByteSource, start: Long): Long {
        val b = src.read(start, 80)
        if (b.size < 32) return 0
        val version = le16(b, 4)
        val blocksPerFrame: Long
        val finalFrameBlocks: Long
        val totalFrames: Long
        val sampleRate: Long
        if (version >= 3980) {
            val descriptor = le32(b, 8).toInt()
            val h = src.read(start + descriptor, 24)
            if (h.size < 24) return 0
            blocksPerFrame = le32(h, 4)
            finalFrameBlocks = le32(h, 8)
            totalFrames = le32(h, 12)
            sampleRate = le32(h, 20)
        } else {
            val compression = le16(b, 6)
            sampleRate = le32(b, 12)
            totalFrames = le32(b, 24)
            finalFrameBlocks = le32(b, 28)
            blocksPerFrame = when {
                version >= 3950 -> 73728L * 4
                version >= 3900 || (version >= 3800 && compression == 4000) -> 73728L
                else -> 9216L
            }
        }
        if (totalFrames <= 0 || sampleRate <= 0) return 0
        val blocks = (totalFrames - 1) * blocksPerFrame + finalFrameBlocks
        return blocks * 1000 / sampleRate
    }
}
