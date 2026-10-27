package com.novcloud.echo.data

import com.novcloud.echo.data.lyrics.LrcParser
import com.novcloud.echo.data.lyrics.LyricLine
import com.novcloud.echo.data.lyrics.LyricsParser
import com.novcloud.echo.data.lyrics.SmoothClock
import com.novcloud.echo.data.lyrics.Ttml
import com.novcloud.echo.data.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLyricsTest {
    private fun LyricLine.spans() = words!!.map { text.substring(it.start, it.end) to it.timeMs }

    @Test
    fun enhancedLrcAngleTags() {
        val l = LrcParser.parse("[00:12.00]<00:12.00>Paper <00:12.50>satel<00:12.90>lites <00:13.60>\n[00:14.00]下一行")!!
        val line = l.lines[0]
        assertEquals("Paper satellites", line.text)
        assertEquals(listOf("Paper" to 12_000L, "satel" to 12_500L, "lites" to 12_900L), line.spans())
        assertEquals(12_500L, line.words!![0].endMs)
        assertEquals(13_600L, line.words!![2].endMs)
        assertEquals(13_600L, line.endMs)
        assertNull(l.lines[1].words)
    }

    @Test
    fun bracketInlineVariantIsNotRepeatedLines() {
        val l = LrcParser.parse("[00:01.00]夜[00:01.40]明[00:01.80]け[00:02.30]\n[00:03.00][00:09.00]副歌")!!
        assertEquals(3, l.lines.size)
        assertEquals("夜明け", l.lines[0].text)
        assertEquals(listOf("夜" to 1000L, "明" to 1400L, "け" to 1800L), l.lines[0].spans())
        assertEquals(2300L, l.lines[0].words!![2].endMs)
        assertEquals(listOf(3000L, 9000L), l.lines.drop(1).map { it.timeMs })
        assertEquals("副歌", l.lines[2].text)
    }

    @Test
    fun textBeforeTheFirstStampStartsWithTheLine() {
        val l = LrcParser.parse("[00:05.00]Hold <00:05.60>the <00:06.00>signal")!!
        assertEquals(listOf("Hold" to 5000L, "the" to 5600L, "signal" to 6000L), l.lines[0].spans())
        assertEquals(-1L, l.lines[0].words!!.last().endMs)
    }

    @Test
    fun offsetShiftsWordStamps() {
        val l = LrcParser.parse("[offset:500]\n[00:02.00]<00:02.00>a <00:02.50>b<00:03.00>")!!
        assertEquals(1500L, l.lines[0].timeMs)
        assertEquals(listOf(1500L, 2000L), l.lines[0].words!!.map { it.timeMs })
        assertEquals(2500L, l.lines[0].words!![1].endMs)
    }

    @Test
    fun translationLineKeepsLineLevelAndOriginalKeepsWords() {
        val l = LrcParser.parse("[00:01.00]<00:01.00>君の<00:01.50>声<00:02.00>\n[00:01.00]你的声音")!!
        val line = l.lines.single()
        assertEquals("君の声", line.text)
        assertEquals("你的声音", line.translation)
        assertEquals(2, line.words!!.size)
    }

    @Test
    fun inlineTranslationSplitKeepsWordsOfTheOriginal() {
        val text = (0 until 4).joinToString("\n") { i ->
            "[00:0$i.00]<00:0$i.00>Hello <00:0$i.50>world 你好世界"
        }
        val l = LrcParser.parse(text)!!
        l.lines.forEach {
            assertEquals("Hello world", it.text)
            assertEquals("你好世界", it.translation)
            assertEquals(listOf("Hello", "world"), it.words!!.map { w -> it.text.substring(w.start, w.end) })
        }
    }

    @Test
    fun qrcAndKrcWords() {
        val q = LrcParser.parse("[12000,1500]纸(12000,400)飞(12400,500)机(12900,600)")!!
        assertEquals("纸飞机", q.lines[0].text)
        assertEquals(listOf("纸" to 12_000L, "飞" to 12_400L, "机" to 12_900L), q.lines[0].spans())
        assertEquals(13_500L, q.lines[0].words!![2].endMs)
        val k = LrcParser.parse("[3000,1000]<0,300,0>Stay <300,700,0>here")!!
        assertEquals("Stay here", k.lines[0].text)
        assertEquals(listOf("Stay" to 3000L, "here" to 3300L), k.lines[0].spans())
        assertEquals(4000L, k.lines[0].words!![1].endMs)
    }

    @Test
    fun plainLrcHasNoWords() {
        val l = LrcParser.parse("[00:01.00]你好\n[00:02.00]世界")!!
        assertTrue(l.lines.all { it.words == null })
    }

    private val ttml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
          <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation type="replacement" xml:lang="zh-Hans"><text for="L2">回家吧</text></translation></translations>
          </iTunesMetadata></metadata></head>
          <body dur="00:30.000"><div>
            <p begin="00:01.000" end="00:03.200" itunes:key="L1"><span begin="00:01.000" end="00:01.400">Pa</span><span begin="00:01.400" end="00:01.900">per</span> <span begin="00:02.000" end="00:03.200">moon</span><span ttm:role="x-translation">纸月亮</span><span ttm:role="x-bg"><span begin="2.5s" end="3s">(ooh)</span></span></p>
            <p begin="4.5s" end="6s" itunes:key="L2"><span begin="4.5s" end="5s">come</span> <span begin="5s" end="6s">home</span></p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun ttmlSyllablesTranslationsAndBackgroundVocals() {
        val l = LyricsParser.parse(ttml)!!
        assertTrue(l.synced)
        assertEquals(2, l.lines.size)
        val a = l.lines[0]
        assertEquals("Paper moon", a.text)
        assertEquals("纸月亮", a.translation)
        assertEquals(listOf("Pa" to 1000L, "per" to 1400L, "moon" to 2000L), a.spans())
        assertEquals(3200L, a.endMs)
        val b = l.lines[1]
        assertEquals(4500L, b.timeMs)
        assertEquals("回家吧", b.translation)
        assertEquals(listOf("come" to 4500L, "home" to 5000L), b.spans())
    }

    @Test
    fun ttmlLineOnlyAndClockFormats() {
        val l = Ttml.parse("""<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1:02.5" end="1:04">一行</p><p begin="70s">二行</p></div></body></tt>""")!!
        assertEquals(listOf(62_500L, 70_000L), l.lines.map { it.timeMs })
        assertNull(l.lines[0].words)
        assertEquals(3_723_500L, Ttml.time("01:02:03.5"))
        assertEquals(1500L, Ttml.time("1500ms"))
        assertEquals(-1L, Ttml.time(""))
        assertNull(Ttml.parse("<html><body/></html>"))
    }

    @Test
    fun stampedTimingFillsWordsAndJumpsGaps() {
        val l = LrcParser.parse("[00:10.00]<00:10.00>Hold, <00:11.00>me<00:12.00>\n[00:15.00]x")!!
        val t = WordTiming.of(l.lines, 0)!!
        assertEquals(0f, t.position(9_000), 0f)
        assertEquals(2.5f, t.position(10_500), 0.01f)
        assertEquals(5f, t.position(10_999), 0.01f)
        assertEquals(6f, t.position(11_000), 0.01f)
        assertEquals(7f, t.position(11_500), 0.01f)
        assertEquals(8f, t.position(12_000), 0f)
        assertEquals(0, t.active(10_200))
        assertEquals(1, t.active(11_200))
        assertEquals(-1, t.active(12_500))
    }

    @Test
    fun missingLastEndUsesTheNextLineOrAPerSyllableTail() {
        val l = LrcParser.parse("[00:05.00]Hold <00:05.60>the <00:06.00>signal\n[00:06.40]next")!!
        val seg = WordTiming.of(l.lines, 0)!!.segments.last()
        assertEquals(6000L, seg.t0)
        assertEquals(6400L, seg.t1)
        val alone = LrcParser.parse("[00:05.00]Hold <00:05.60>the <00:06.00>signal")!!
        assertEquals(6000L + 2 * 450L, WordTiming.of(alone.lines, 0)!!.segments.last().t1)
    }

    @Test
    fun estimateWeighsCjkCharsAndLatinSyllablesAndSkipsPunctuation() {
        val units = WordTiming.tokenize("我们, paper satellites! 夜明けー")
        val spans = units.map { "我们, paper satellites! 夜明けー".substring(it.first, it.second) to it.third }
        assertEquals(
            listOf("我" to 1.0, "们" to 1.0, "paper" to 2.0, "satellites" to 3.0, "夜" to 1.0, "明" to 1.0, "け" to 1.0, "ー" to 0.5),
            spans,
        )
        assertEquals(listOf("don't" to 1.0, "time" to 1.0, "2024" to 2.4), WordTiming.tokenize("don't time 2024").map { "don't time 2024".substring(it.first, it.second) to it.third })
    }

    @Test
    fun estimateSpansTheLineButIsCappedBeforeLongBreaks() {
        val packed = WordTiming.estimate("你好世界", 1000, 3000)
        assertEquals(4, packed.segments.size)
        assertEquals(1000L, packed.segments.first().t0)
        assertEquals(3000L, packed.segments.last().t1)
        assertEquals(2f, packed.position(2000), 0.01f)
        val sparse = WordTiming.estimate("你好", 0, 60_000)
        assertEquals(2 * WordTiming.MS_PER_UNIT + WordTiming.LINE_PAD_MS, sparse.segments.last().t1)
        assertEquals(2f, sparse.position(10_000), 0f)
        val gaps = WordTiming.estimate("a, b", 0, 1000)
        assertEquals(1f, gaps.position(499), 0.01f)
        assertEquals(3f, gaps.position(500), 0.01f)
        assertTrue(WordTiming.estimate("…!", 0, 1000).segments.isEmpty())
    }

    @Test
    fun timingOfFallsBackToEstimateAndSkipsBlankLines() {
        val l = LrcParser.parse("[00:01.00]你好\n[00:02.00]\n[00:03.00]世界")!!
        val t = WordTiming.of(l.lines, 0)
        assertNotNull(t)
        assertEquals(2000L, t!!.segments.last().t1)
        assertNull(WordTiming.of(l.lines, 1))
        assertEquals(3000L + 2 * WordTiming.MS_PER_UNIT + WordTiming.LINE_PAD_MS, WordTiming.of(l.lines, 2, songEndMs = 60_000)!!.segments.last().t1)
    }

    @Test
    fun smoothClockExtrapolatesAbsorbsJitterAndFollowsSeeks() {
        val c = SmoothClock()
        assertEquals(1000L, c.frame(0, 1000, true))
        assertEquals(1100L, c.frame(100, 1000, true))
        assertEquals(1600L, c.frame(2000, 1000, true))
        assertEquals(1600L, c.frame(2016, 1500, true))
        assertEquals(1616L, c.frame(2132, 1500, true))
        assertEquals(30_000L, c.frame(2150, 30_000, true))
        assertEquals(500L, c.frame(2160, 500, true))
        assertEquals(500L, c.frame(5000, 500, false))
    }
}
