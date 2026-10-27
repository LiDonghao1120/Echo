package com.novcloud.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** values (Chinese, default) and values-en must carry the same translatable keys, the same arguments and full plural sets. */
class StringsTest {
    private data class Entry(val type: String, val values: Map<String, String>)

    private fun read(dir: String, translatableOnly: Boolean = true): Map<String, Entry> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("missing $file", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (translatableOnly && e.getAttribute("translatable") == "false") continue
            val name = e.getAttribute("name")
            val values = when (e.tagName) {
                "plurals" -> items(e).associate { it.getAttribute("quantity") to it.textContent }
                "string-array", "integer-array" -> items(e).mapIndexed { j, it -> "$j" to it.textContent }.toMap()
                else -> mapOf("" to e.textContent)
            }
            assertTrue("duplicate $name in $dir", out.put(name, Entry(e.tagName, values)) == null)
        }
        return out
    }

    private fun items(e: Element) = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }

    private val zh by lazy { read("values") }
    private val en by lazy { read("values-en") }

    @Test
    fun englishHasEveryKeyAndNoExtra() {
        assertEquals("missing in values-en", emptySet<String>(), zh.keys - en.keys)
        assertEquals("only in values-en", emptySet<String>(), en.keys - zh.keys)
        for ((k, v) in zh) assertEquals("type of $k", v.type, en.getValue(k).type)
    }

    @Test
    fun untranslatableStringsLiveOnlyInTheDefault() {
        val fixed = read("values", translatableOnly = false).keys - zh.keys
        assertTrue(fixed.isNotEmpty())
        assertEquals(emptySet<String>(), fixed intersect read("values-en", translatableOnly = false).keys)
    }

    @Test
    fun pluralsCoverEachLanguage() {
        for ((k, v) in zh.filterValues { it.type == "plurals" }) {
            assertEquals("zh $k", setOf("other"), v.values.keys)
            assertEquals("en $k", setOf("one", "other"), en.getValue(k).values.keys)
            assertTrue("empty plural $k", (v.values.values + en.getValue(k).values.values).all { it.isNotEmpty() })
        }
    }

    @Test
    fun formatArgumentsMatch() {
        val arg = Regex("""%(\d+\$)?[-+#0 ,(]*\d*(\.\d+)?[sdfx]""")
        for ((k, v) in zh) {
            if (v.type != "string" && v.type != "plurals") continue
            val expected = v.values.values.map { s -> arg.findAll(s).map { it.value }.sorted().toList() }.toSet()
            for (s in en.getValue(k).values.values) {
                val got = arg.findAll(s).map { it.value }.sorted().toList()
                assertTrue("arguments of $k: $got vs $expected", got in expected)
            }
        }
    }

    @Test
    fun arraysAreUsable() {
        for ((k, v) in zh.filterValues { it.type == "string-array" }) {
            if (k == "greetings") continue
            assertEquals("length of $k", v.values.size, en.getValue(k).values.size)
        }
        val from = en.getValue("greeting_from").values.values.map { it.trim().toInt() }
        assertEquals(from.size, en.getValue("greetings").values.size)
        assertEquals(from.sorted(), from)
        val zhFrom = zh.getValue("greeting_from").values.values.map { it.trim().toInt() }
        assertEquals(zhFrom.size, zh.getValue("greetings").values.size)
    }

    @Test
    fun noUntranslatedChineseInEnglish() {
        val han = Regex("[\\u3400-\\u9fff]")
        for ((k, v) in en) for (s in v.values.values) assertTrue("Chinese left in en $k: $s", !han.containsMatchIn(s))
    }
}
