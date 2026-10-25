package com.novcloud.echo.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.novcloud.echo.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    fun indexAt(posMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

object LrcParser {
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val offsetTag = Regex("\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("^\\[(ti|ar|al|by|re|ve|length|au|offset|tool|#):.*]$", RegexOption.IGNORE_CASE)

    fun parse(text: String): Lyrics? {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val offset = offsetTag.find(clean)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val raw = ArrayList<Pair<Long, String>>()
        val plain = ArrayList<String>()
        clean.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty() || metaTag.matches(l)) return@forEach
            val tags = timeTag.findAll(l).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) {
                plain += l
                return@forEach
            }
            val body = l.substring(tags.last().range.last + 1).replace(wordTag, "").trim()
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                raw += (min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0) to body
            }
        }
        if (raw.isEmpty()) {
            val lines = plain.filter { it.isNotBlank() }
            return if (lines.isEmpty()) null else Lyrics(Bilingual.split(lines.map { LyricLine(-1, it) }), synced = false)
        }
        val grouped = raw.sortedBy { it.first }.groupBy { it.first }
        val lines = grouped.map { (t, items) ->
            val texts = items.map { it.second }.filter { it.isNotBlank() }.distinct()
            LyricLine(t, texts.firstOrNull() ?: "", texts.drop(1).firstOrNull())
        }
        val trimmed = Bilingual.split(lines.dropWhile { it.text.isBlank() })
        return if (trimmed.none { it.text.isNotBlank() }) null else Lyrics(trimmed, synced = true)
    }
}

object Bilingual {
    private enum class Script { Latin, Han, Kana, Hangul, None }

    private val separators = listOf(" // ", " / ", " | ", "｜", "\t", " ／ ")
    private const val OPENERS = "(（[【「『“\"'《〈"

    /**
     * Inline "original translation" lines are only split when the song as a whole looks bilingual: at least three
     * such lines, covering half of the lines in a foreign script, and more of them than plain Chinese lines.
     * That keeps Chinese songs with an English phrase here and there ("Baby 我想你") intact.
     */
    fun split(lines: List<LyricLine>): List<LyricLine> {
        val bySeparator = lines.map { l -> if (l.translation == null) splitSeparator(l) else l }
        val open = bySeparator.filter { it.translation == null && it.text.isNotBlank() }
        val inline = open.count { inlineIndex(it.text) > 0 }
        val foreign = open.count { t -> t.text.any { isKana(it) || isHangul(it) || isLatin(it) } }
        val plainHan = open.count { t -> t.text.any(::isHan) && t.text.none { isKana(it) || isHangul(it) || isLatin(it) } }
        if (inline < 3 || inline * 2 < foreign || plainHan >= inline) return bySeparator
        return bySeparator.map { l ->
            if (l.translation != null) return@map l
            val i = inlineIndex(l.text)
            if (i <= 0) l else l.copy(text = l.text.substring(0, i).trim(), translation = l.text.substring(i).trim())
        }
    }

    private fun splitSeparator(l: LyricLine): LyricLine {
        for (sep in separators) {
            val i = l.text.indexOf(sep)
            if (i <= 0) continue
            val a = l.text.substring(0, i).trim()
            val b = l.text.substring(i + sep.length).trim()
            if (a.isEmpty() || b.isEmpty()) continue
            val sa = script(a)
            val sb = script(b)
            if (sa != sb && sa != Script.None && sb != Script.None) return l.copy(text = a, translation = b)
        }
        return l
    }

    /**
     * Start of a trailing translation inside one line, or -1. Candidates are whitespace boundaries (including the
     * full-width space) and direct Latin-to-CJK transitions; the leftmost one whose tail is a translation wins, so
     * "dirtyはnothingだ　歪んだbrain 脑子不值一提" cuts before 脑, not inside the Japanese.
     * A tail is a translation when it has no Latin letters and its script is missing from the head:
     * pure Han after kana/hangul/Latin (Chinese), kana after Latin/hangul (Japanese), hangul after anything else (Korean).
     */
    fun inlineIndex(text: String): Int {
        var i = 1
        while (i < text.length) {
            val boundary = when {
                text[i - 1].isWhitespace() && !text[i].isWhitespace() -> true
                isLatin(text[i - 1]) && (isHan(text[i]) || isKana(text[i]) || isHangul(text[i])) -> true
                else -> false
            }
            if (boundary) {
                var cut = i
                while (cut > 0 && text[cut - 1] in OPENERS) cut--
                if (isTranslation(text.substring(0, cut), text.substring(cut))) return cut
            }
            i++
        }
        return -1
    }

    private fun isTranslation(head: String, tail: String): Boolean {
        val h = head.trim()
        if (h.isEmpty() || tail.isBlank() || tail.any(::isLatin)) return false
        val hKana = h.any(::isKana)
        val hHangul = h.any(::isHangul)
        val hHan = h.any(::isHan)
        val hLatin = h.count(::isLatin)
        val tKana = tail.any(::isKana)
        val tHangul = tail.any(::isHangul)
        val tHan = tail.any(::isHan)
        return when {
            tHangul -> !tKana && !hHangul && (hKana || hHan || hLatin >= 2)
            tKana -> !hKana && !hHan && (hHangul || hLatin >= 2)
            tHan -> hKana || hHangul || (!hHan && hLatin >= 2)
            else -> false
        }
    }

    private fun script(s: String): Script = when {
        s.any(::isKana) -> Script.Kana
        s.any(::isHangul) -> Script.Hangul
        s.any(::isHan) -> Script.Han
        s.any { it.isLetter() } -> Script.Latin
        else -> Script.None
    }

    private fun isHan(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF' || c == '\u3005'
    private fun isKana(c: Char) = c in '\u3040'..'\u30FF'
    /** Letters of alphabetic scripts (Latin, Cyrillic, Greek…), i.e. anything that is not CJK. */
    private fun isLatin(c: Char) = c.isLetter() && !isHan(c) && !isKana(c) && !isHangul(c) && c.code < 0x2E80
    private fun isHangul(c: Char) = c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'
}

class LyricsLoader(private val context: Context) {
    class Loaded(val lyrics: Lyrics?)

    private val cache = object : LinkedHashMap<String, Loaded>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>?) = size > 40
    }
    @Volatile private var treeIndex: Pair<String, Map<String, Uri>>? = null

    private fun key(song: Song, tree: String?) = "${song.id}|${tree.orEmpty()}"

    internal fun prime(song: Song, tree: String?, lyrics: Lyrics?) = synchronized(cache) { cache[key(song, tree)] = Loaded(lyrics) }

    fun peek(song: Song, tree: String?): Loaded? = synchronized(cache) { cache[key(song, tree)] }

    suspend fun load(song: Song, lyricsTree: String?): Lyrics? = withContext(Dispatchers.IO) {
        peek(song, lyricsTree)?.let { return@withContext it.lyrics }
        val result = sidecar(song) ?: fromTree(song, lyricsTree) ?: embedded(song)
        synchronized(cache) { cache[key(song, lyricsTree)] = Loaded(result) }
        result
    }

    suspend fun warmTree(tree: String?) = withContext(Dispatchers.IO) {
        if (tree != null && treeIndex?.first != tree) treeIndex = tree to buildIndex(tree)
    }

    fun invalidate() {
        synchronized(cache) { cache.clear() }
        treeIndex = null
    }

    private fun sidecar(song: Song): Lyrics? {
        if (song.path.isEmpty()) return null
        val base = song.path.substringBeforeLast('.')
        for (ext in listOf(".lrc", ".LRC", ".txt")) {
            val f = File(base + ext)
            val bytes = runCatching { if (f.canRead()) f.readBytes() else null }.getOrNull() ?: continue
            LrcParser.parse(TextDecoder.decode(bytes))?.let { return it }
        }
        return null
    }

    private fun fromTree(song: Song, tree: String?): Lyrics? {
        if (tree == null) return null
        val index = treeIndex?.takeIf { it.first == tree }?.second ?: buildIndex(tree).also { treeIndex = tree to it }
        val base = song.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val keys = listOf(base, "${song.artist} - ${song.title}".lowercase(), song.title.lowercase())
        for (k in keys) {
            val uri = index[k] ?: continue
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: continue
            LrcParser.parse(TextDecoder.decode(bytes))?.let { return it }
        }
        return null
    }

    private fun buildIndex(tree: String): Map<String, Uri> {
        val map = HashMap<String, Uri>()
        val treeUri = runCatching { Uri.parse(tree) }.getOrNull() ?: return map
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return map
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun walk(docId: String, depth: Int) {
            val dirs = ArrayList<String>()
            runCatching {
                context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id
                        else if (name.endsWith(".lrc", true)) {
                            map.putIfAbsent(name.substringBeforeLast('.').lowercase(), DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                        }
                    }
                }
            }
            if (depth < 3) dirs.forEach { walk(it, depth + 1) }
        }
        walk(rootId, 0)
        return map
    }

    private fun embedded(song: Song): Lyrics? {
        val text = runCatching {
            context.contentResolver.openInputStream(song.uri)?.use { TagReader.lyrics(it) }
        }.getOrNull() ?: return null
        return LrcParser.parse(text)
    }
}

object TextDecoder {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return strict(bytes, Charsets.UTF_8) ?: String(bytes, gbk)
    }

    fun latinOrGbk(bytes: ByteArray): String {
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.ISO_8859_1)
        return strict(bytes, Charsets.UTF_8) ?: String(bytes, gbk)
    }

    private val gbk: Charset = runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

object TagReader {
    fun lyrics(input: InputStream): String? {
        val s = DataInputStream(input.buffered(64 * 1024))
        val head = ByteArray(4)
        s.readFully(head)
        return when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(s, head[3].toInt())
            String(head, Charsets.US_ASCII) == "fLaC" -> flac(s)
            else -> null
        }
    }

    private fun id3(s: DataInputStream, major: Int): String? {
        s.readUnsignedByte()
        val flags = s.readUnsignedByte()
        val size = syncsafe(s.readInt())
        var read = 0
        if (flags and 0x40 != 0) {
            val ext = s.readInt()
            val extSize = if (major == 4) syncsafe(ext) - 4 else ext
            s.skipFully(extSize); read += 4 + extSize
        }
        val headerLen = if (major == 2) 6 else 10
        while (read + headerLen < size) {
            val id: String
            val frameSize: Int
            if (major == 2) {
                val b = ByteArray(3); s.readFully(b); id = String(b, Charsets.US_ASCII)
                frameSize = (s.readUnsignedByte() shl 16) or (s.readUnsignedByte() shl 8) or s.readUnsignedByte()
            } else {
                val b = ByteArray(4); s.readFully(b); id = String(b, Charsets.US_ASCII)
                val raw = s.readInt()
                frameSize = if (major == 4) syncsafe(raw) else raw
                s.readUnsignedShort()
            }
            read += headerLen
            if (id[0].code == 0 || frameSize <= 0 || read + frameSize > size) return null
            if (id == "USLT" || id == "ULT") {
                val data = ByteArray(frameSize); s.readFully(data)
                return uslt(data)
            }
            s.skipFully(frameSize)
            read += frameSize
        }
        return null
    }

    private fun uslt(d: ByteArray): String? {
        if (d.size < 5) return null
        val enc = d[0].toInt()
        var i = 4
        val wide = enc == 1 || enc == 2
        if (wide) {
            while (i + 1 < d.size && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < d.size && d[i].toInt() != 0) i++
            i += 1
        }
        if (i >= d.size) return null
        val body = d.copyOfRange(i, d.size)
        val text = when (enc) {
            0 -> TextDecoder.latinOrGbk(body)
            1 -> String(body, Charsets.UTF_16)
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }
        return text.trim('\u0000', ' ', '\n').takeIf { it.isNotBlank() }
    }

    private fun flac(s: DataInputStream): String? {
        while (true) {
            val h = s.readUnsignedByte()
            val last = h and 0x80 != 0
            val type = h and 0x7F
            val len = (s.readUnsignedByte() shl 16) or (s.readUnsignedByte() shl 8) or s.readUnsignedByte()
            if (type == 4) {
                val d = ByteArray(len); s.readFully(d)
                val bb = ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val vendor = bb.int; bb.position(bb.position() + vendor)
                val n = bb.int
                repeat(n) {
                    val l = bb.int
                    val b = ByteArray(l); bb.get(b)
                    val c = String(b, Charsets.UTF_8)
                    val k = c.substringBefore('=').uppercase()
                    if (k == "LYRICS" || k == "UNSYNCEDLYRICS") return c.substringAfter('=').takeIf { it.isNotBlank() }
                }
                return null
            }
            s.skipFully(len)
            if (last) return null
        }
    }

    private fun syncsafe(v: Int) = (v and 0x7F) or ((v shr 8 and 0x7F) shl 7) or ((v shr 16 and 0x7F) shl 14) or ((v shr 24 and 0x7F) shl 21)

    private fun DataInputStream.skipFully(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val k = skip(left)
            if (k <= 0) { if (read() < 0) return; left-- } else left -= k
        }
    }
}
