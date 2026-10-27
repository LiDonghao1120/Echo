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

    @Test
    fun japaneseAndMixedJapaneseEnglishWithChineseInSameLine() {
        val l = LrcParser.parse(
            """
            [00:01.00]Discord-disco - 十明
            [00:05.00]They couldn't feel the love because of discord. 就因不够和谐他们无法感受爱意
            [00:09.00]やけに気取ってもうてるgentle 如此装腔作势的温柔
            [00:13.00]なんで、なんで、なんでなんでって 为何 为何 请告诉我为何
            [00:16.00]♪
            [00:20.00]物語のあの子は可憐さ 故事里的那个孩子真可怜
            [00:24.00]dirtyはnothingだ　歪んだbrain 脑子不值一提 但他太脑子就变形
            [00:28.00]Oh oh oh
            """.trimIndent(),
        )!!
        val byText = l.lines.associateBy { it.text }
        assertEquals("就因不够和谐他们无法感受爱意", byText["They couldn't feel the love because of discord."]!!.translation)
        assertEquals("如此装腔作势的温柔", byText["やけに気取ってもうてるgentle"]!!.translation)
        assertEquals("为何 为何 请告诉我为何", byText["なんで、なんで、なんでなんでって"]!!.translation)
        assertEquals("故事里的那个孩子真可怜", byText["物語のあの子は可憐さ"]!!.translation)
        assertEquals("脑子不值一提 但他太脑子就变形", byText["dirtyはnothingだ　歪んだbrain"]!!.translation)
        assertNull(byText["Oh oh oh"]!!.translation)
        assertNull(byText["♪"]!!.translation)
    }

    @Test
    fun koreanGermanFrenchWithChinese() {
        val l = LrcParser.parse(
            """
            [00:01.00]사랑해 너를 我爱你
            [00:02.00]Ich liebe dich so sehr 我如此爱你
            [00:03.00]Je ne regrette rien 我无怨无悔
            [00:04.00]Всё будет хорошо 一切都会好的
            """.trimIndent(),
        )!!
        assertEquals("사랑해 너를", l.lines[0].text)
        assertEquals("我爱你", l.lines[0].translation)
        assertEquals("Ich liebe dich so sehr", l.lines[1].text)
        assertEquals("我无怨无悔", l.lines[2].translation)
        assertEquals("Всё будет хорошо", l.lines[3].text)
        assertEquals("一切都会好的", l.lines[3].translation)
    }

    @Test
    fun japaneseSongWithoutTranslationStaysWhole() {
        val l = LrcParser.parse(
            """
            [00:01.00]夢を見た 未来
            [00:02.00]君と歩いた道
            [00:03.00]忘れないで
            [00:04.00]空を見上げて 明日
            [00:05.00]さよなら
            [00:06.00]光の中で
            """.trimIndent(),
        )!!
        assertEquals("夢を見た 未来", l.lines[0].text)
        assertNull(l.lines[0].translation)
        assertEquals("空を見上げて 明日", l.lines[3].text)
    }

    @Test
    fun englishWithJapaneseTranslation() {
        val l = LrcParser.parse("[00:01.00]I love you 愛してる\n[00:02.00]Stay with me 一緒にいて\n[00:03.00]Forever and ever ずっと")!!
        assertEquals("I love you", l.lines[0].text)
        assertEquals("愛してる", l.lines[0].translation)
        assertEquals("ずっと", l.lines[2].translation)
    }

    @Test
    fun titleDashArtistLineIsNotATranslation() {
        assertEquals(-1, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("Lemon - 米津玄師"))
        assertEquals(-1, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("春よ、来い - 松任谷由実"))
        assertEquals(-1, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("Paper Satellites — 温屿"))
        assertEquals(-1, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("Paper Satellites － 温屿"))
        assertEquals(-1, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("Lemon -米津玄師"))
        assertEquals("I love you ".length, com.novcloud.echo.data.lyrics.Bilingual.inlineIndex("I love you 我爱你"))
    }

    @Test
    fun creditLineStaysWholeInsideABilingualSong() {
        val l = LrcParser.parse(
            """
            [00:00.00]Paper Satellites - 温屿
            [00:01.00]词：温屿
            [00:10.00]Paper satellites over the bay 纸做的卫星飞过海湾
            [00:12.00]We folded every letter into wings 我们把每封信折成翅膀
            [00:15.00]Hold the signal, hold it close 抓住信号 紧紧抓住
            [00:18.00]Paper satellites, come home 纸卫星啊 回家吧
            """.trimIndent(),
        )!!
        assertEquals("Paper Satellites - 温屿", l.lines[0].text)
        assertNull(l.lines[0].translation)
        assertEquals("词：温屿", l.lines[1].text)
        assertEquals("Paper satellites over the bay", l.lines[2].text)
        assertEquals("纸做的卫星飞过海湾", l.lines[2].translation)
        assertEquals("纸卫星啊 回家吧", l.lines[5].translation)
    }

    @Test
    fun japaneseCreditLineWithChineseArtistStaysWhole() {
        val l = LrcParser.parse(
            """
            [00:00.00]夜明けのうた - 温屿
            [00:10.00]夜明けまで待ってるよ 我会等到天亮
            [00:12.00]君の声が聞こえる 听见你的声音
            [00:15.00]遠くへ 遠くへ 去远方 去远方
            """.trimIndent(),
        )!!
        assertEquals("夜明けのうた - 温屿", l.lines[0].text)
        assertNull(l.lines[0].translation)
        assertEquals("我会等到天亮", l.lines[1].translation)
    }
}
