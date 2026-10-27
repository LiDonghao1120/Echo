package com.novcloud.echo.data.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML lyrics as Apple Music exports them: one `<p begin end>` per line, optionally `<span begin end>` per word.
 * Translations come from `ttm:role="x-translation"` spans or the `<translations>` block in head metadata keyed by
 * `itunes:key`; romanisation and background-vocal spans are left out.
 */
object Ttml {
    fun parse(text: String): Lyrics? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.newDocumentBuilder().parse(text.trimStart('\uFEFF').trimStart().byteInputStream(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        if (root.localName() != "tt") return null
        val translations = HashMap<String, String>()
        root.descendants("translation").firstOrNull()?.let { tr ->
            tr.descendants("text").forEach { t -> t.getAttribute("for").takeIf { it.isNotEmpty() }?.let { translations[it] = t.textContent.trim() } }
        }
        val lines = ArrayList<LyricLine>()
        var synced = false
        root.descendants("p").forEach { p ->
            val begin = time(p.getAttribute("begin"))
            val end = time(p.getAttribute("end"))
            val sb = StringBuilder()
            val words = ArrayList<LyricWord>()
            var translation: String? = null
            fun walk(n: Node) {
                when (n.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(n.nodeValue.replace(Regex("\\s+"), " "))
                    Node.ELEMENT_NODE -> {
                        val e = n as Element
                        when (e.localName()) {
                            "br" -> sb.append(' ')
                            "span" -> {
                                when (e.attr("role")) {
                                    "x-translation" -> { translation = e.textContent.trim().ifEmpty { null }; return }
                                    "x-roman", "x-bg" -> return
                                }
                                val t0 = time(e.getAttribute("begin"))
                                if (t0 >= 0 && e.getElementsByTagNameNS("*", "span").length == 0) {
                                    val from = sb.length
                                    sb.append(e.textContent)
                                    val t1 = time(e.getAttribute("end"))
                                    words += LyricWord(from, sb.length, t0, t1)
                                } else {
                                    var c = e.firstChild
                                    while (c != null) { walk(c); c = c.nextSibling }
                                }
                            }
                            else -> { var c = e.firstChild; while (c != null) { walk(c); c = c.nextSibling } }
                        }
                    }
                }
            }
            var c = p.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
            val raw = sb.toString()
            val lead = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
            val body = raw.trim()
            if (body.isEmpty()) return@forEach
            val ws = words.mapNotNull { w ->
                var a = w.start
                var b = w.end
                while (a < b && raw[a].isWhitespace()) a++
                while (b > a && raw[b - 1].isWhitespace()) b--
                if (a >= b) null else w.copy(start = a - lead, end = b - lead)
            }
            val start = if (begin >= 0) begin else ws.firstOrNull()?.timeMs ?: -1
            if (start >= 0) synced = true
            val key = p.attr("key")
            lines += LyricLine(start, body, translation ?: translations[key], ws.takeIf { it.isNotEmpty() }, end)
        }
        if (lines.isEmpty()) return null
        return if (synced) Lyrics(lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }, synced = true)
        else Lyrics(lines.map { it.copy(timeMs = -1, words = null) }, synced = false)
    }

    /** Clock time `h:mm:ss.fff` / `mm:ss.fff` / `ss.fff`, or an offset `12.5s` / `12500ms` / `1.2m` / `0.1h`; -1 when absent. */
    fun time(v: String?): Long {
        val s = v?.trim().orEmpty()
        if (s.isEmpty()) return -1
        Regex("^([\\d.]+)(h|m|s|ms)$").matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: return -1
            return (n * when (m.groupValues[2]) { "h" -> 3_600_000.0; "m" -> 60_000.0; "s" -> 1000.0; else -> 1.0 }).toLong()
        }
        val parts = s.split(':')
        if (parts.size > 3) return -1
        var total = 0.0
        for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return -1)
        return Math.round(total * 1000)
    }

    private fun Element.localName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.attr(name: String): String {
        val a = attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            if ((n.localName ?: n.nodeName.substringAfter(':')) == name) return n.nodeValue
        }
        return ""
    }

    private fun Element.descendants(name: String): List<Element> {
        val nl = getElementsByTagNameNS("*", name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }
}
