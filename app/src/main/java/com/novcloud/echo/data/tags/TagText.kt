package com.novcloud.echo.data.tags

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Tag text whose bytes do not say their charset: ID3 frames declared ISO-8859-1, ID3v1, and what MediaStore or
 * MediaMetadataRetriever made of them. Phones set to Chinese guess GBK for such bytes, so a Latin-1 "MARÍA VELL"
 * (… 52 CD 41 …) comes back as "MAR虯 VELL": the accented letter's byte swallows the following "A" as a GBK trail byte.
 *
 * Bytes are taken as GBK only when they are a valid GBK sequence and the result reads as Chinese (see [plausibleGbk]);
 * a lone high byte inside an otherwise Latin word is an accented Latin-1 letter.
 */
object TagText {
    private val gbk: Charset = runCatching { Charset.forName("GB18030") }.getOrElse { Charset.forName("GBK") }
    private val cp1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** Decodes bytes a tag declares as ISO-8859-1: ASCII, then UTF-8 written into a Latin-1 frame, then Chinese GBK, else Latin-1. */
    fun latin1(bytes: ByteArray): String {
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.ISO_8859_1)
        strict(bytes, Charsets.UTF_8)?.let { return it }
        if (plausibleGbk(bytes)) strict(bytes, gbk)?.let { return it }
        return singleByte(bytes)
    }

    /** Latin-1, with 0x80–0x9F read as Windows-1252 (’, “, –, Œ…) instead of invisible control characters. */
    private fun singleByte(bytes: ByteArray): String {
        val c1 = bytes.any { (it.toInt() and 0xFF) in 0x80..0x9F }
        return if (c1) strict(bytes, cp1252) ?: String(bytes, Charsets.ISO_8859_1) else String(bytes, Charsets.ISO_8859_1)
    }

    private fun isAsciiLetter(b: Int) = b in 0x41..0x5A || b in 0x61..0x7A

    /**
     * True when [bytes] are GBK and read as Chinese: every high byte starts a two-byte GBK character, at least one is a
     * Han character, and none sits inside a Latin word. A pair whose trail byte is an ASCII letter or digit next to
     * other ASCII letters ("MAR"+CD 41, "Ren"+E9 65), and any pair with ASCII letters on both sides ("Gr"+F6 DF+"e"),
     * is a Latin-1 accented letter in a word, not a Chinese character.
     */
    fun plausibleGbk(bytes: ByteArray): Boolean {
        var han = 0
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) { i++; continue }
            if (b == 0x80 || b == 0xFF || i + 1 >= n) return false
            val t = bytes[i + 1].toInt() and 0xFF
            if (t < 0x40 || t == 0x7F || t == 0xFF) return false
            val ch = strict(byteArrayOf(bytes[i], bytes[i + 1]), gbk) ?: return false
            if (ch.length != 1) return false
            val c = ch[0]
            if (!cjkish(c)) return false
            val prev = if (i > 0) bytes[i - 1].toInt() and 0xFF else -1
            val next = if (i + 2 < n) bytes[i + 2].toInt() and 0xFF else -1
            if (t < 0x80 && (isAsciiLetter(prev) || isAsciiLetter(next))) return false
            if (isAsciiLetter(prev) && isAsciiLetter(next)) return false
            if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN) han++
            i += 2
        }
        return han > 0
    }

    private fun cjkish(c: Char): Boolean {
        val s = Character.UnicodeScript.of(c.code)
        if (s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA ||
            s == Character.UnicodeScript.BOPOMOFO || s == Character.UnicodeScript.GREEK || s == Character.UnicodeScript.CYRILLIC
        ) return true
        val code = c.code
        return code in 0x3000..0x303F || code in 0xFF00..0xFFEF || code in 0x2000..0x2BFF || code in 0x00A4..0x00F7 || code in 0x02C7..0x02D9
    }

    private fun isCjk(c: Int) = c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF || c in 0x20000..0x3FFFF

    /** Kana, Han and the private-use code points GBK's user-defined rows decode to: what a misread accented letter becomes. */
    private fun isIdeo(c: Int) = c in 0x3040..0x9FFF || c in 0xF900..0xFAFF || c in 0xE000..0xF8FF || c in 0x20000..0x3FFFF

    /**
     * Quick test for text that may be mojibake: U+FFFD, C1 control characters, "Ã©"-style UTF-8 read as Latin-1, or a
     * CJK character glued to an ASCII letter. Most names pass in one scan without allocating.
     */
    fun suspicious(s: String): Boolean {
        if (s.isEmpty()) return false
        var prevLetter = false
        var prevCjk = false
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val len = Character.charCount(c)
            if (c == 0xFFFD || c in 0x80..0x9F) return true
            if ((c == 0xC2 || c == 0xC3) && i + len < s.length && s[i + len].code in 0x80..0xBF) return true
            val letter = c < 0x80 && isAsciiLetter(c)
            val cjk = isIdeo(c)
            if ((cjk && prevLetter) || (letter && prevCjk)) return true
            prevLetter = letter
            prevCjk = cjk
            i += len
        }
        return false
    }

    /**
     * The text [s] stood for before a GBK (or Latin-1) misreading, or null when it does not look misread or cannot be
     * recovered. GBK case: the text encodes back to the exact GBK bytes, those bytes do not read as Chinese by
     * [plausibleGbk], and they decode as UTF-8 or Latin-1 to letters only. "Ã©" case: the text is Latin-1/Windows-1252
     * whose bytes are valid UTF-8 with multi-byte characters.
     */
    fun repair(s: String): String? {
        if (!suspicious(s)) return null
        return fromGbk(s) ?: fromLatin1(s)
    }

    /**
     * [suspicious] text that [repair] cannot put right and that is not plain Chinese next to Latin ("Jay周杰伦"): U+FFFD,
     * control characters, or CJK that does not encode back to sensible GBK. Only the file's own bytes can tell.
     */
    fun unexplained(s: String): Boolean {
        if (!suspicious(s) || repair(s) != null) return false
        if (s.any { it.code == 0xFFFD || it.code in 0x80..0x9F }) return true
        val bytes = encode(s, gbk) ?: return true
        return !plausibleGbk(bytes)
    }

    /** A CJK character with ASCII letters right before and after it ("Gr虯ner"): never how Chinese is written. */
    fun cjkInsideWord(s: String): Boolean {
        for (i in 1 until s.length - 1) {
            if (isIdeo(s[i].code) && isAsciiLetter(s[i - 1].code) && isAsciiLetter(s[i + 1].code)) return true
        }
        return false
    }

    /** [repair] when it applies, otherwise [s] itself. */
    fun repaired(s: String): String = repair(s) ?: s

    private fun fromGbk(s: String): String? {
        if (s.none { isIdeo(it.code) }) return null
        val bytes = encode(s, gbk) ?: return null
        if (plausibleGbk(bytes)) return null
        val out = strict(bytes, Charsets.UTF_8)?.takeIf { u -> u.any { it.code >= 0x80 } } ?: singleByte(bytes)
        return out.takeIf { it != s && readsAsLatin(it, QUOTES) }
    }

    private fun fromLatin1(s: String): String? {
        if (s.none { it.code in 0xC2..0xF4 }) return null
        val bytes = encode(s, cp1252) ?: encode(s, Charsets.ISO_8859_1) ?: return null
        val out = strict(bytes, Charsets.UTF_8) ?: return null
        return out.takeIf { it != s && it.any { c -> c.code >= 0x80 } && readsAsLatin(it, LATIN_MARKS) }
    }

    private const val LATIN_MARKS = "¡¿«»°ºª´·–—‘’‚“”„…•€"
    private const val QUOTES = "‘’‚“”„–—…"

    /** Every non-ASCII character is a letter (or one of [marks]) of a non-CJK script, and nothing is a control. */
    private fun readsAsLatin(s: String, marks: String): Boolean = s.all { c ->
        val code = c.code
        when {
            code < 0x20 -> false
            code < 0x7F -> true
            code in 0x7F..0x9F || code == 0xFFFD -> false
            isCjk(code) -> false
            else -> c.isLetter() || c in marks || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
        }
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun encode(s: String, cs: Charset): ByteArray? = try {
        val buf = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(s))
        ByteArray(buf.remaining()).also { buf.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }
}
