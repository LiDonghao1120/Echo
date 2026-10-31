package com.novcloud.echo.data

import java.text.Normalizer
import java.util.Locale

/**
 * Tag text cleanup and the canonical key used to decide that two spellings name the same artist, album or genre.
 *
 * [clean] keeps what the user can see (it is what the UI shows): it drops characters that render as nothing
 * (NUL terminators and other control characters, BOMs, zero-width spaces, word joiners), turns odd spaces into
 * plain ones, collapses runs of spaces, trims, and applies NFC.
 *
 * [key] also folds what looks alike: NFKC (full-width letters and brackets, ideographic space, CJK compatibility
 * ideographs and Kangxi radicals become their ordinary forms), every default-ignorable character including
 * variation selectors, bracket shapes, spaces next to brackets, and letter case.
 */
object Names {
    private val spaces = Regex("[\\s\\p{Z}]+")
    private val interiorNul = Regex("\u0000+(?=[^\u0000])")

    /** ID3v2.4 and Vorbis-style multiple values arrive NUL-separated; a trailing NUL is just a terminator. */
    fun values(s: String): List<String> =
        if (s.indexOf('\u0000') < 0) listOf(s) else s.split(interiorNul).filter { it.isNotEmpty() }.ifEmpty { listOf(s) }
    private val spaceAroundBrackets = Regex(" ?([()]) ?")

    /** Renders as nothing in our text views; safe to drop from what we show. */
    fun isInvisible(c: Int): Boolean =
        (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) || c in 0x7F..0x9F ||
            c == 0x200B || c == 0x2060 || c == 0xFEFF || c in 0xFFF9..0xFFFB || c == 0x180E

    /** Default-ignorable or formatting: dropped from keys only (ZWJ, direction marks and selectors can matter for display). */
    private fun isIgnorable(c: Int): Boolean =
        (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) || c in 0x7F..0x9F || c == 0xAD || c == 0x034F || c == 0x061C || c in 0x115F..0x1160 ||
            c in 0x17B4..0x17B5 || c in 0x180B..0x180F || c in 0x200B..0x200F || c in 0x202A..0x202E ||
            c in 0x2060..0x206F || c == 0x3164 || c in 0xFE00..0xFE0F || c == 0xFEFF || c == 0xFFA0 ||
            c in 0xFFF0..0xFFFB || c in 0x1BCA0..0x1BCA3 || c in 0x1D173..0x1D17A || c in 0xE0000..0xE0FFF

    private fun isSpace(c: Int): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

    fun hasInvisible(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (isInvisible(c)) return true
            i += Character.charCount(c)
        }
        return false
    }

    fun clean(s: String): String {
        if (s.isEmpty()) return s
        if (isPlain(s)) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                isInvisible(c) -> Unit
                c == 0x3000 -> b.append('\u3000')
                isSpace(c) -> b.append(' ')
                else -> b.appendCodePoint(c)
            }
        }
        val t = Normalizer.normalize(b, Normalizer.Form.NFC).replace(spaces) { m -> if (m.value.all { it == '\u3000' }) m.value else " " }.trim()
        return if (t == s) s else t
    }

    /** ASCII printable without double spaces or edge spaces needs nothing done. */
    private fun isPlain(s: String): Boolean {
        if (s[0] == ' ' || s[s.length - 1] == ' ') return false
        var prevSpace = false
        for (ch in s) {
            if (ch.code < 0x20 || ch.code > 0x7E) return false
            if (ch == ' ' && prevSpace) return false
            prevSpace = ch == ' '
        }
        return true
    }

    fun key(s: String): String {
        if (s == UNKNOWN) return UNKNOWN
        if (s.all { it.code in 0x20..0x7E }) return fold(s.replace('[', '(').replace('{', '(').replace(']', ')').replace('}', ')'))
        val stripped = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            if (!isIgnorable(c)) stripped.appendCodePoint(c)
        }
        val n = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
        val b = StringBuilder(n.length)
        i = 0
        while (i < n.length) {
            val c = n.codePointAt(i)
            i += Character.charCount(c)
            when {
                isIgnorable(c) -> Unit
                isSpace(c) -> b.append(' ')
                c in OPEN -> b.append('(')
                c in CLOSE -> b.append(')')
                else -> b.appendCodePoint(c)
            }
        }
        return fold(b.toString())
    }

    private fun fold(s: String): String =
        s.replace(spaces, " ").trim().replace(spaceAroundBrackets, "$1").uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    /** Code points as hex, so a diagnostic log shows what differs between two names that look the same. */
    fun codepoints(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (isNotEmpty()) append(' ')
            append(String.format(Locale.ROOT, "%04X", c))
            i += Character.charCount(c)
        }
    }

    private val OPEN = intArrayOf('('.code, '['.code, '{'.code, 0x3010, 0x3014, 0x3016, 0x3018, 0x301A, 0x2985, 0x27E6, 0x2768, 0x276A, 0x2770, 0x2772).toSet()
    private val CLOSE = intArrayOf(')'.code, ']'.code, '}'.code, 0x3011, 0x3015, 0x3017, 0x3019, 0x301B, 0x2986, 0x27E7, 0x2769, 0x276B, 0x2771, 0x2773).toSet()
}

/** Artists named by raw tag or play-history text, split the way [Song.cleaned] + [Song.artists] split them (NUL values too). */
fun artistsOf(raw: String): List<String> =
    Names.values(raw).flatMap { splitArtists(Names.clean(it)) }.filter { it.isNotEmpty() }.distinct()

/** The artist, album or genre a lookup by any spelling of [name] should find. */
fun <V> Map<String, V>.forName(name: String): V? = this[Names.key(name)]

fun Song.cleaned(): Song {
    fun many(s: String) = Names.values(s).let { v -> if (v.size == 1) v[0] else v.joinToString("/") }
    fun one(s: String) = Names.values(s)[0]
    val t = Names.clean(one(title))
    val ar = Names.clean(many(artist)).ifEmpty { UNKNOWN }
    val al = Names.clean(one(album)).ifEmpty { UNKNOWN }
    val aa = Names.clean(many(albumArtist))
    val g = Names.clean(one(genre))
    if (t === title && ar === artist && al === album && aa === albumArtist && g === genre) return this
    return copy(title = t.ifEmpty { path.substringAfterLast('/').substringBeforeLast('.') }, artist = ar, album = al, albumArtist = aa, genre = g)
}

/**
 * Spellings in [songs] (as read, before cleaning) that differ but share a key: these become one artist, album or
 * genre in the library. Used for the diagnostic log.
 */
object NameVariants {
    data class Merge(val kind: String, val key: String, val variants: List<String>)

    fun find(songs: List<Song>): List<Merge> {
        val artists = HashMap<String, MutableSet<String>>()
        val albums = HashMap<String, MutableSet<String>>()
        val genres = HashMap<String, MutableSet<String>>()
        for (s in songs) {
            Names.values(s.artist).flatMap(::splitArtists).forEach { a -> artists.getOrPut(Names.key(a)) { LinkedHashSet() } += a }
            if (s.albumArtist.isNotEmpty()) Names.values(s.albumArtist).flatMap(::splitArtists).forEach { a -> artists.getOrPut(Names.key(a)) { LinkedHashSet() } += a }
            Names.values(s.album)[0].let { a -> albums.getOrPut(Names.key(a)) { LinkedHashSet() } += a }
            if (s.genre.isNotBlank()) Names.values(s.genre)[0].let { g -> genres.getOrPut(Names.key(g)) { LinkedHashSet() } += g }
        }
        fun out(kind: String, m: Map<String, Set<String>>) =
            m.filter { it.value.size > 1 && it.key.isNotEmpty() }.map { Merge(kind, it.key, it.value.sorted()) }
        return out("artist", artists) + out("album", albums) + out("genre", genres)
    }

    fun describe(m: Merge): String =
        "${m.kind} spellings folded together (${m.variants.size}): " + m.variants.joinToString(" | ") { "\"${Names.clean(it)}\" [${Names.codepoints(it)}]" }
}
