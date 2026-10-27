package com.novcloud.echo.data.tags

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to a file's bytes, so tag readers can jump to a footer without streaming the whole file. */
interface ByteSource {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), position + length).toInt()
        return bytes.copyOfRange(position.toInt(), end)
    }
}

class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= size || length <= 0) return ByteArray(0)
        val n = minOf(size - position, length.toLong()).toInt()
        val buf = ByteBuffer.allocate(n)
        var p = position
        while (buf.hasRemaining()) {
            val r = channel.read(buf, p)
            if (r <= 0) break
            p += r
        }
        return buf.array().copyOf(buf.position())
    }
}

data class AudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    val durationMs: Long = 0,
    val cover: ByteArray? = null,
    /** Every text field by upper-cased key, for things the named fields do not cover (ReplayGain). */
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = title.isEmpty() && artist.isEmpty() && album.isEmpty()

    override fun equals(other: Any?): Boolean = other is AudioTags && title == other.title && artist == other.artist &&
        album == other.album && albumArtist == other.albumArtist && track == other.track && disc == other.disc &&
        year == other.year && genre == other.genre && durationMs == other.durationMs && extra == other.extra &&
        (cover?.contentEquals(other.cover ?: ByteArray(0)) ?: (other.cover == null))

    override fun hashCode(): Int = listOf(title, artist, album, albumArtist, track, disc, year, genre, durationMs, extra).hashCode()

    internal companion object {
        fun number(s: String?): Int = s?.trim()?.substringBefore('/')?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        fun year(s: String?): Int = s?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..9999 } ?: 0
    }
}

/** Picks the reader by the file's signature: Monkey's Audio, ASF/WMA, or an APEv2 footer on anything else (MP3). */
object TagFiles {
    fun read(src: ByteSource, wantCover: Boolean): AudioTags? {
        val head = src.read(0, 16)
        if (head.size >= 16 && Asf.isAsf(head)) return Asf.read(src, wantCover)
        val start = Ape.skipId3(src)
        val mac = src.read(start, 4)
        val ape = ApeV2.read(src, wantCover)
        if (mac.size == 4 && String(mac, Charsets.ISO_8859_1) == "MAC ") {
            val duration = Ape.durationMs(src, start)
            return (ape ?: AudioTags()).copy(durationMs = duration.takeIf { it > 0 } ?: ape?.durationMs ?: 0)
        }
        return ape
    }
}

internal fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
internal fun le32(b: ByteArray, o: Int): Long =
    (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
internal fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
