package com.novcloud.echo.data

import java.io.DataInputStream
import java.io.InputStream

/**
 * Offline Mandarin readings for CJK U+3400–U+9FFF, loaded from assets/pinyin.bin (built by tools/gen_pinyin.py from
 * pinyin-data). Readings are toneless with ü as v; a character keeps up to three, most common first.
 */
object Pinyin {
    private class Table(val syllables: Array<String>, val first: Int, val primary: ShortArray, val extra: Map<Int, IntArray>)

    @Volatile private var table: Table? = null
    @Volatile private var source: (() -> InputStream)? = null
    private var failed = false

    fun install(open: () -> InputStream) {
        source = open
    }

    private fun table(): Table? {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            if (failed) return null
            val open = source ?: return null
            return runCatching { open().use(::read) }.onFailure { failed = true }.getOrNull()?.also { table = it }
        }
    }

    private fun read(input: InputStream): Table {
        val d = DataInputStream(input.buffered())
        require(d.readInt() == 0x50594E31)
        val syllables = Array(d.readUnsignedShort()) { String(ByteArray(d.readUnsignedByte()).also(d::readFully), Charsets.US_ASCII) }
        val first = d.readUnsignedShort()
        val primary = ShortArray(d.readUnsignedShort()) { d.readShort() }
        val extra = HashMap<Int, IntArray>()
        repeat(d.readUnsignedShort()) {
            val off = d.readUnsignedShort()
            extra[off] = IntArray(d.readUnsignedByte()) { d.readUnsignedShort() }
        }
        return Table(syllables, first, primary, extra)
    }

    fun isHan(c: Char): Boolean = c.code in 0x3400..0x9FFF || c == '〇'

    /** All readings of [c], most common first; empty for anything without a Mandarin reading. */
    fun readings(c: Char): List<String> {
        if (c == '〇') return listOf("ling")
        val t = table() ?: return emptyList()
        val off = c.code - t.first
        if (off < 0 || off >= t.primary.size) return emptyList()
        val p = t.primary[off].toInt() and 0xFFFF
        if (p == 0) return emptyList()
        val more = t.extra[off] ?: return listOf(t.syllables[p - 1])
        return buildList(more.size + 1) {
            add(t.syllables[p - 1])
            more.forEach { add(t.syllables[it]) }
        }
    }

    /** Readings in a person's name: some polyphonic characters read differently as a surname. */
    private val surnames = mapOf(
        '曾' to "zeng", '单' to "shan", '解' to "xie", '仇' to "qiu", '朴' to "piao", '区' to "ou", '查' to "zha",
        '覃' to "qin", '翟' to "zhai", '盖' to "ge", '缪' to "miao", '种' to "chong", '柏' to "bai", '乐' to "yue",
        '召' to "shao", '秘' to "bi", '句' to "gou", '员' to "yun", '万' to "wan", '尉' to "yu", '藏' to "zang",
    )

    private fun primary(c: Char, nameStart: Boolean): String? =
        (if (nameStart) surnames[c] else null) ?: readings(c).firstOrNull()

    /**
     * Sort key that orders Han text by pinyin among Latin text (周杰伦 next to Zhou), case-insensitive. Text starting
     * with anything other than a letter or a Han character with a reading sorts after Z, like the # section.
     */
    fun sortKey(text: String, name: Boolean = false): String {
        val s = text.trim()
        val sb = StringBuilder(s.length * 3 + 2)
        var first = true
        for (c in s) {
            val r = if (isHan(c)) primary(c, name && first) else null
            if (r != null) sb.append(r).append(' ') else sb.append(c.lowercaseChar())
            if (c.isLetterOrDigit()) first = false
        }
        val key = sb.toString()
        val lead = key.firstOrNull { it.isLetterOrDigit() }
        val prefix = if (lead != null && lead in 'a'..'z') "" else "{"
        return prefix + key + "\u0000" + s
    }

    /** The A–Z section a sort key belongs to, '#' for everything else. */
    fun section(sortKey: String): Char {
        if (sortKey.startsWith("{")) return '#'
        val c = sortKey.firstOrNull { it.isLetterOrDigit() } ?: return '#'
        return if (c in 'a'..'z') c.uppercaseChar() else '#'
    }
}

/**
 * A string prepared for search: its letters, digits and Han characters (case folded), each Han character with its
 * readings. A query matches when, from some character on, every query character is consumed by the text in order:
 * a literal character, a whole reading, or the start of a reading (initials), so zjl, zhoujielun, zhoujl and 周jl all
 * find 周杰伦.
 */
class SearchText private constructor(private val chars: CharArray, private val readings: Array<List<String>?>) {
    val hasHan: Boolean get() = readings.any { it != null }

    /** Index of the first character the match starts at, or -1. */
    fun match(query: String): Int {
        val q = normalize(query)
        if (q.isEmpty()) return -1
        if (!hasHan || q.none { it in 'a'..'z' }) return literal(q)
        val n = chars.size
        val memo = ByteArray((n + 1) * (q.length + 1))
        for (start in 0 until n) if (from(start, 0, q, memo)) return start
        return -1
    }

    private fun literal(q: String): Int = String(chars).indexOf(q)

    private fun from(i: Int, k: Int, q: String, memo: ByteArray): Boolean {
        if (k == q.length) return true
        if (i == chars.size) return false
        val slot = i * (q.length + 1) + k
        if (memo[slot] != 0.toByte()) return memo[slot] == 1.toByte()
        var ok = q[k] == chars[i] && from(i + 1, k + 1, q, memo)
        val rs = readings[i]
        if (!ok && rs != null) {
            loop@ for (r in rs) {
                for (alt in if ('v' in r) listOf(r, r.replace('v', 'u')) else listOf(r)) {
                    var len = 0
                    while (len < alt.length && k + len < q.length && q[k + len] == alt[len]) {
                        len++
                        if (from(i + 1, k + len, q, memo)) { ok = true; break@loop }
                    }
                }
            }
        }
        memo[slot] = if (ok) 1 else 2
        return ok
    }

    companion object {
        fun of(text: String): SearchText {
            val cs = StringBuilder()
            val rs = ArrayList<List<String>?>()
            for (c in text) {
                if (!c.isLetterOrDigit()) continue
                cs.append(c.lowercaseChar())
                rs += if (Pinyin.isHan(c)) Pinyin.readings(c).ifEmpty { null } else null
            }
            return SearchText(cs.toString().toCharArray(), rs.toTypedArray())
        }

        fun normalize(query: String): String = buildString {
            for (c in query) if (c.isLetterOrDigit()) append(if (c == 'ü' || c == 'Ü') 'v' else c.lowercaseChar())
        }
    }
}
