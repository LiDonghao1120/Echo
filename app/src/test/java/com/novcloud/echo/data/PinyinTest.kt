package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PinyinTest {
    @Before
    fun table() {
        Pinyin.install { File("src/main/assets/pinyin.bin").inputStream() }
    }

    private fun finds(text: String, q: String) = SearchText.of(text).match(q) >= 0

    @Test
    fun readingsComeFromTheTable() {
        assertEquals(listOf("zhou"), Pinyin.readings('周'))
        assertTrue("zeng" in Pinyin.readings('曾') && "ceng" in Pinyin.readings('曾'))
        assertEquals(listOf("lv"), Pinyin.readings('绿').take(1))
        assertEquals(emptyList<String>(), Pinyin.readings('a'))
    }

    @Test
    fun fullInitialsAndMixed() {
        listOf("zjl", "zhoujielun", "zhoujl", "zjielun", "周jl", "周杰l", "jielun", "jl", "ZJL", "zhou jie lun").forEach {
            assertTrue(it, finds("周杰伦", it))
        }
        assertTrue(finds("温屿", "wy"))
        assertTrue(finds("温屿", "wenyu"))
        assertTrue(finds("晚风邮局", "wfyj"))
        assertTrue(finds("晚风邮局", "fengyou"))
        assertTrue(finds("北岸 (Live)", "bal"))
        assertTrue(finds("Jay Chou 周杰伦", "jaychou"))
    }

    @Test
    fun rejectsWhatDoesNotSpellIt() {
        assertTrue(!finds("周杰伦", "zjm"))
        assertTrue(!finds("周杰伦", "jz"))
        assertTrue(!finds("周杰伦", "zhoux"))
        assertTrue(!finds("温屿", "yw"))
        assertTrue(!finds("Neon Orchard", "zjl"))
    }

    @Test
    fun polyphonesMatchEitherReading() {
        assertTrue(finds("曾经的你", "zengjing"))
        assertTrue(finds("曾经的你", "cengjing"))
        assertTrue(finds("长安", "changan"))
        assertTrue(finds("长安", "zhangan"))
        assertTrue(finds("绿色", "lvse"))
        assertTrue(finds("略", "lue"))
    }

    @Test
    fun matchPositionRanksStartHigher() {
        assertEquals(0, SearchText.of("小满").match("xm"))
        assertEquals(2, SearchText.of("我的小满").match("xm"))
    }

    @Test
    fun sortsHanAmongLatinByPinyin() {
        val names = listOf("Zoe", "周杰伦", "陈奕迅", "Adele", "温屿", "曾轶可", "123", "Beyond", "王菲", "♪Intro")
        val sorted = names.sortedBy { Pinyin.sortKey(it, name = true) }
        assertEquals(listOf("Adele", "Beyond", "陈奕迅", "王菲", "温屿", "曾轶可", "周杰伦", "Zoe", "123", "♪Intro"), sorted)
        assertEquals('Z', Pinyin.section(Pinyin.sortKey("曾轶可", name = true)))
        assertEquals('C', Pinyin.section(Pinyin.sortKey("曾经", name = false)))
        assertEquals('#', Pinyin.section(Pinyin.sortKey("123")))
        assertEquals('#', Pinyin.section(Pinyin.sortKey("あいみょん")))
        assertEquals('W', Pinyin.section(Pinyin.sortKey("  温屿")))
    }

    @Test
    fun librarySearchOrdersLiteralBeforePinyin() {
        fun s(id: Long, title: String, artist: String) = Song(
            id, title, artist, "A", 1, "", "", 200_000, 1, 1, 2020, 0, "/m/$id.mp3", "/m", "", 1,
        )
        val lib = Library.build(listOf(s(1, "WY Theme", "Otis Fenwick"), s(2, "灯塔", "温屿"), s(3, "Snowy", "Neon Orchard")))
        val r = lib.search.query("wy")
        assertEquals(listOf("WY Theme", "Snowy", "灯塔"), r.songs.map { it.title })
        assertEquals(listOf("温屿"), r.artists.map { it.name })
    }
}
