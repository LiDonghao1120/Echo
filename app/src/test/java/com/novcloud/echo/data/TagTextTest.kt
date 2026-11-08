package com.novcloud.echo.data

import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.tags.ArraySource
import com.novcloud.echo.data.tags.OwnText
import com.novcloud.echo.data.tags.TagText
import com.novcloud.echo.data.lyrics.TextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Tag text misread as GBK (and the reverse), with fictional names only. */
class TagTextTest {
    private val gbk = Charset.forName("GB18030")
    private val latin = listOf("MARÍA VELL", "Zoë Arden", "Renée Solvay", "Joaquín Mar", "Søren Hollis", "Ísold Brae", "Mötley Wren", "Ana Conceição", "Grüße Nordlicht")
    private val chinese = listOf("林雾", "白小舟", "云笙（白小舟）", "Kai林雾", "林雾 & Nova Lark", "雾港乐团 - 第二章")

    /** What a phone set to Chinese shows for Latin-1 tag bytes. */
    private fun misread(s: String) = String(s.toByteArray(Charsets.ISO_8859_1), gbk)

    @Test
    fun latinOneFramesStayLatinAndGbkFramesStayChinese() {
        latin.forEach { assertEquals(it, TagText.latin1(it.toByteArray(Charsets.ISO_8859_1))) }
        chinese.forEach { assertEquals(it, TagText.latin1(it.toByteArray(gbk))) }
        latin.forEach { assertEquals(it, TagText.latin1(it.toByteArray(Charsets.UTF_8))) }
        assertEquals("Ana’s Tide", TagText.latin1(byteArrayOf(0x41, 0x6E, 0x61, 0x92.toByte(), 0x73, 0x20, 0x54, 0x69, 0x64, 0x65)))
        assertEquals("Plain Name", TagText.latin1("Plain Name".toByteArray()))
        assertEquals("MARÍA VELL", TextDecoder.latinOrGbk("MARÍA VELL".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("林雾", TextDecoder.latinOrGbk("林雾".toByteArray(gbk)))
    }

    @Test
    fun theReportedBytePathIsCd41() {
        val bytes = "MARÍA VELL".toByteArray(Charsets.ISO_8859_1)
        assertEquals("4d4152cd412056454c4c", bytes.joinToString("") { "%02x".format(it) })
        assertEquals("MAR\u866F VELL", String(bytes, gbk))
        assertFalse(TagText.plausibleGbk(bytes))
        assertTrue(TagText.plausibleGbk("林雾".toByteArray(gbk)))
        assertTrue(TagText.plausibleGbk("Kai林雾".toByteArray(gbk)))
    }

    @Test
    fun storeTextMisreadAsGbkIsRepaired() {
        for (name in latin) {
            val shown = misread(name)
            if (shown == name || '\uFFFD' in shown) continue
            assertEquals(name, TagText.repair(shown))
            assertEquals(Names.key(name), Names.key(shown))
            assertEquals(name, Names.clean(shown))
        }
        assertEquals("Renée Solvay", TagText.repair(String("Renée Solvay".toByteArray(Charsets.UTF_8), gbk)))
        assertEquals("Renée Solvay", TagText.repair(String("Renée Solvay".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
        assertEquals("Joaquín Mar", Names.clean(String("Joaquín Mar".toByteArray(Charsets.UTF_8), Charset.forName("windows-1252"))))
    }

    @Test
    fun chineseAndCleanTextIsLeftAlone() {
        (chinese + latin + listOf("Nova Lark", "Ünal", "ÀÉÎ", "ナギ", "백설", "Ã")).forEach { assertNull(it, TagText.repair(it)) }
        chinese.forEach { assertEquals(it, Names.clean(it)); assertFalse(it, TagText.unexplained(it)) }
        assertTrue(TagText.unexplained("Zo\uFFFD Arden"))
        assertTrue(TagText.cjkInsideWord("Gr\u554Aner"))
        assertFalse(TagText.cjkInsideWord("Kai林雾"))
    }

    /** Japanese titles mix kana and kanji with Latin words; that is how they are written, not a misreading. */
    @Test
    fun japaneseNextToLatinIsNotSuspicious() {
        val names = listOf("夜明けのMOMO〜STREET COVERS〜", "雨音のKAI", "東京NIGHT DRIVE", "MOMOの歌", "ミナトBLUE", "森川遥 feat. Aria Moss")
        names.forEach { assertFalse(it, TagText.unexplained(it)); assertFalse(it, TagText.cjkInsideWord(it)) }
        val raw = names.mapIndexed { i, n -> song(i + 1, "Ms.MOMO", album = n) }
        val found = NameAudit.find(raw, raw.map { it.cleaned() })
        assertTrue(found.toString(), found.none { it.kind == NameAudit.Kind.Suspicious })
        assertTrue(TagText.loneHanInLatinWord("Gr\u554Aner"))
        assertTrue(TagText.loneHanInLatinWord("MAR\u866F"))
        assertFalse(TagText.loneHanInLatinWord("Jay周景澜"))
        assertFalse(TagText.loneHanInLatinWord("夜明けのMOMO"))
        assertTrue(TagText.unexplained("Ren\u00C3\u00A9e Sol\u00C3"))
    }

    private fun song(n: Int, artist: String, album: String = "Lumen Fields", title: String = "Track $n", albumArtist: String = "") =
        Song(n.toLong(), title, artist, album, 7, albumArtist, "Pop", 200_000, n, 1, 2025, 0, "/m/lumen/$n.mp3", "/m/lumen", "audio/mpeg", 1000L + n)

    @Test
    fun misreadAndCorrectSpellingsBecomeOneArtist() {
        val raw = listOf(
            song(1, misread("MARÍA VELL"), albumArtist = misread("MARÍA VELL")),
            song(2, misread("MARÍA VELL")),
            song(3, "MARÍA VELL"),
            song(4, "MARÍA VELL, Dov Kessel"),
            song(5, "林雾", album = "雾港"),
            song(6, "Kai林雾", album = "雾港"),
        )
        val lib = Library.build(raw.map { it.cleaned() })
        val names = lib.artists.map { it.name }
        assertEquals(1, names.count { Names.key(it) == Names.key("MARÍA VELL") })
        assertEquals("MARÍA VELL", lib.artist(misread("MARÍA VELL"))!!.name)
        assertTrue(names.none { '\u866F' in it })
        assertEquals(1, lib.albums.count { it.title == "Lumen Fields" })
        assertTrue("林雾" in names && "Kai林雾" in names)
        assertEquals(listOf("MARÍA VELL"), artistsOf(misread("MARÍA VELL")))

        val found = NameAudit.find(raw, raw.map { it.cleaned() })
        assertEquals(1, found.count { it.kind == NameAudit.Kind.Merged })
        val repaired = found.filter { it.kind == NameAudit.Kind.Repaired }
        assertEquals(2, repaired.size)
        assertEquals(3, repaired.sumOf { it.songs })
        assertTrue(found.none { it.kind == NameAudit.Kind.Suspicious })
        assertTrue(NameAudit.summary(found).startsWith("audit: repaired 2 distinct field values"))
    }

    @Test
    fun auditReportsWhatTextAloneCannotFix() {
        val raw = listOf(song(1, "Zo\uFFFD Arden"), song(2, "Ana", title = "Gr\u554Aner Weg"))
        val found = NameAudit.find(raw, raw.map { it.cleaned() })
        assertEquals(setOf("Zo\uFFFD Arden", "Gr\u554Aner Weg"), found.filter { it.kind == NameAudit.Kind.Suspicious }.map { it.text }.toSet())
        assertTrue(NameAudit.describe(found.first()).startsWith("suspicious"))
    }

    private fun frame(id: String, enc: Int, body: ByteArray): ByteArray {
        val size = body.size + 1
        return id.toByteArray() + byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(), 0, 0, enc.toByte()) + body
    }

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        val n = body.size
        val head = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, (n ushr 21 and 0x7F).toByte(), (n ushr 14 and 0x7F).toByte(), (n ushr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
        return head + body + ByteArray(64)
    }

    private fun id3v1(title: String, artist: String, album: String, cs: Charset = Charsets.ISO_8859_1): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(s: String, len: Int) = out.write(s.toByteArray(cs).copyOf(len))
        out.write("TAG".toByteArray()); field(title, 30); field(artist, 30); field(album, 30); out.write(ByteArray(35))
        return out.toByteArray()
    }

    @Test
    fun ownTagsAreReadWithTheSameRules() {
        val v2 = id3v23(
            frame("TIT2", 0, "Canción Lenta".toByteArray(Charsets.ISO_8859_1)),
            frame("TPE1", 0, "MARÍA VELL".toByteArray(Charsets.ISO_8859_1)),
            frame("TALB", 3, "Ísold Brae Live".toByteArray(Charsets.UTF_8)),
            frame("TPE2", 1, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Zoë Arden".toByteArray(Charsets.UTF_16LE)),
            frame("TCON", 0, "林雾".toByteArray(gbk)),
        )
        val t = OwnText.read(ArraySource(v2 + ByteArray(400)))!!
        assertEquals(OwnText("Canción Lenta", "MARÍA VELL", "Ísold Brae Live", "Zoë Arden", "林雾"), t)

        val v1 = OwnText.read(ArraySource(ByteArray(400) + id3v1("Rio", "Zoë Arden", "Joaquín Mar")))!!
        assertEquals(OwnText("Rio", "Zoë Arden", "Joaquín Mar"), v1)
        assertEquals("林雾", OwnText.read(ArraySource(ByteArray(10) + id3v1("x", "林雾", "y", gbk)))!!.artist)
        assertNull(OwnText.read(ArraySource(ByteArray(400))))
    }

    @Test
    fun ownTagsReplaceOnlyMisreadStoreText() {
        val store = song(1, "Zo\uFFFD Arden", title = "Rio", albumArtist = "林雾")
        assertTrue(OwnText.needsCheck(store))
        val fixed = OwnText.merge(store, OwnText("Rio", "Zoë Arden", "Lumen Fields", "Lin Wu"))
        assertEquals("Zoë Arden", fixed.artist)
        assertEquals("林雾", fixed.albumArtist)
        assertEquals("Rio", fixed.title)
        assertFalse(OwnText.needsCheck(song(2, misread("MARÍA VELL"))))
        assertFalse(OwnText.needsCheck(song(3, "Kai林雾")))
    }

    private fun asset(name: String, id: Long = 1, kind: ArtKind = ArtKind.Logo) =
        ArtAsset(id = id, name = name, kind = kind.name, path = "/a$id.png", width = 10, height = 4, luma = 0.5f, hash = "h$id", addedAt = id, updatedAt = id)

    @Test
    fun autoApplyMatchesWholeNamesOnly() {
        val assets = listOf(asset("CARA LUNE", 1), asset("Cara", 2), asset("MARÍA VELL", 3), asset("Lune Cara", 4))
        val plan = ArtLibraryMatch.plan(listOf("Cara Lune Trio", "MARÍA VELL", "Car", "Mar", "Zo\uFFFD Arden"), emptySet(), emptySet(), assets)
        assertEquals(listOf("MARÍA VELL" to 3L), plan.map { it.artist to it.asset.id })
        assertEquals(ArtLibraryMatch.key("MARÍA VELL"), plan.single().key)
        assertTrue(ArtLibraryMatch.plan(listOf("Zo\uFFFD Arden"), emptySet(), emptySet(), listOf(asset("Zo\uFFFD Arden"))).isEmpty())
        assertTrue(ArtLibraryMatch.plan(listOf("MARÍA VELL"), emptySet(), emptySet(), listOf(asset(misread("MARÍA VELL")))).isNotEmpty())
        assertTrue(ArtLibraryMatch.legacyKey(misread("MARÍA VELL")) != ArtLibraryMatch.key("MARÍA VELL"))
        assertEquals(ArtLibraryMatch.key(misread("MARÍA VELL")), ArtLibraryMatch.key("MARÍA VELL"))
    }

    private data class Row(val artist: String, val updatedAt: Long, val auto: String = "")

    @Test
    fun artRowsOfMisreadAndCorrectSpellingsSettleOnOne() {
        val bad = misread("MARÍA VELL")
        fun shown(s: String) = Names.clean(s)
        val manualBad = Row(bad, 5)
        val autoGood = Row("MARÍA VELL", 9, auto = "maríavell")
        val a = ArtistArtRows.settle(listOf(manualBad, autoGood), { it.artist }, { it.updatedAt }, { it.auto }, ::shown)
        assertEquals(listOf(autoGood), a.drop)
        assertEquals(listOf(manualBad to "MARÍA VELL"), a.rename)

        val older = Row("MARÍA VELL", 1)
        val b = ArtistArtRows.settle(listOf(older, Row(bad, 4)), { it.artist }, { it.updatedAt }, { it.auto }, ::shown)
        assertEquals(listOf(older), b.drop)
        assertEquals("MARÍA VELL", b.rename.single().second)

        val c = ArtistArtRows.settle(listOf(Row("Nova Lark", 1), Row("林雾", 2)), { it.artist }, { it.updatedAt }, { it.auto }, ::shown)
        assertTrue(c.drop.isEmpty() && c.rename.isEmpty())
        assertTrue(ArtistArtRows.misread(bad))
        assertFalse(ArtistArtRows.misread("林雾"))
    }

    @Test
    fun syntheticLibraryRepairCount() {
        val names = latin + chinese + listOf("Nova Lark", "Dov Kessel", "Wren Hollow")
        val raw = names.flatMapIndexed { i, n ->
            val shown = if (n in latin) misread(n) else n
            listOf(song(i * 3 + 1, shown, album = "$n Sessions"), song(i * 3 + 2, shown, album = "$n Sessions"), song(i * 3 + 3, n, album = "$n Sessions"))
        }
        val found = NameAudit.find(raw, raw.map { it.cleaned() })
        val repaired = found.filter { it.kind == NameAudit.Kind.Repaired }
        val changed = raw.zip(raw.map { it.cleaned() }).sumOf { (r, c) ->
            listOf(r.title to c.title, r.artist to c.artist, r.album to c.album, r.albumArtist to c.albumArtist, r.genre to c.genre).count { it.first != it.second }
        }
        val lib = Library.build(raw.map { it.cleaned() })
        val unrecoverable = latin.count { '\uFFFD' in misread(it) }
        assertEquals(names.flatMap(::splitArtists).map(Names::key).distinct().size + unrecoverable, lib.artists.size)
        println("synthetic library: ${raw.size} songs, ${names.size} artists; ${repaired.size} distinct repaired values, $changed song fields changed, ${found.count { it.kind == NameAudit.Kind.Merged }} merged, ${found.count { it.kind == NameAudit.Kind.Suspicious }} suspicious")
        assertEquals(unrecoverable, found.count { it.kind == NameAudit.Kind.Suspicious })
    }
}
