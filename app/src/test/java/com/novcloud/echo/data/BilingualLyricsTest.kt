package com.novcloud.echo.data

import com.novcloud.echo.data.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilingualLyricsTest {

    @Test
    fun inlineLatinThenChinese() {
        val l = LrcParser.parse(
            """
            [00:10.00](One, two, three) (一二三)
            [00:12.00]My blue bucket of gold 我的蓝色金桶
            [00:15.00]Friend, why don't you love me? 朋友 为何你不爱我
            [00:18.00]Tell me you want me in your life 告诉我 你渴望我在你生命里
            [00:21.00]Lord, touch me with lightning
            """.trimIndent(),
        )!!
        assertEquals("(One, two, three)", l.lines[0].text)
        assertEquals("(一二三)", l.lines[0].translation)
        assertEquals("My blue bucket of gold", l.lines[1].text)
        assertEquals("我的蓝色金桶", l.lines[1].translation)
        assertEquals("Friend, why don't you love me?", l.lines[2].text)
        assertEquals("告诉我 你渴望我在你生命里", l.lines[3].translation)
        assertEquals("Lord, touch me with lightning", l.lines[4].text)
        assertNull(l.lines[4].translation)
    }

    @Test
    fun duplicateTimestampsInSeparateBlocks() {
        val l = LrcParser.parse(
            "[00:01.00]Hello darkness\n[00:04.00]My old friend\n[00:01.00]你好 黑暗\n[00:04.00]我的老朋友",
        )!!
        assertEquals(2, l.lines.size)
        assertEquals("Hello darkness", l.lines[0].text)
        assertEquals("你好 黑暗", l.lines[0].translation)
        assertEquals("我的老朋友", l.lines[1].translation)
    }

    @Test
    fun separators() {
        val l = LrcParser.parse(
            "[00:01.00]I see the light / 我看见了光\n[00:02.00]Love | 爱\n[00:03.00]Day\t白天\n[00:04.00]Black / white\n[00:05.00]Ready ｜ 准备好了",
        )!!
        assertEquals("I see the light", l.lines[0].text)
        assertEquals("我看见了光", l.lines[0].translation)
        assertEquals("爱", l.lines[1].translation)
        assertEquals("白天", l.lines[2].translation)
        assertEquals("Black / white", l.lines[3].text)
        assertNull(l.lines[3].translation)
        assertEquals("准备好了", l.lines[4].translation)
    }

    @Test
    fun japaneseWithChineseTranslation() {
        val l = LrcParser.parse("[00:01.00]君の名は / 你的名字")!!
        assertEquals("君の名は", l.lines[0].text)
        assertEquals("你的名字", l.lines[0].translation)
    }

    @Test
    fun chineseSongWithOccasionalEnglishIsNotSplit() {
        val l = LrcParser.parse(
            """
            [00:01.00]Baby 我想你
            [00:02.00]窗外的麻雀
            [00:03.00]在电线杆上多嘴
            [00:04.00]你说这一句
            [00:05.00]很有夏天的感觉
            [00:06.00]Oh my love 别走
            """.trimIndent(),
        )!!
        assertEquals("Baby 我想你", l.lines[0].text)
        assertNull(l.lines[0].translation)
        assertEquals("Oh my love 别走", l.lines[5].text)
    }

    @Test
    fun plainBilingualLyrics() {
        val l = LrcParser.parse("Hello from the other side 来自彼岸的问候\nI must have called 我一定打过\nA thousand times 上千次")!!
        assertEquals("Hello from the other side", l.lines[0].text)
        assertEquals("上千次", l.lines[2].translation)
    }

    @Test
    fun repeatedIdenticalLineIsNotATranslation() {
        val l = LrcParser.parse("[00:01.00]La la la\n[00:01.00]La la la\n[00:02.00]Na na")!!
        assertNull(l.lines[0].translation)
    }
}
