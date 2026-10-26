package com.novcloud.echo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class LibrarySnapshotTest {
    private fun song(id: Long, title: String = "Song $id", genre: String = "Pop", doc: String = "") = Song(
        id, title, "歌手 $id", "专辑", id / 10, "", genre, 200_000 + id, (id % 12).toInt(), 1, 2020,
        id * 1000, "/storage/emulated/0/Music/$id.flac", "/storage/emulated/0/Music", "audio/flac", 4_000_000 + id, doc,
    )

    private val store = (1L..40L).map { song(it) }
    private val tree = listOf(
        song(SafPaths.stableId("/storage/1a2b-3c4d/x/夜曲.ape"), "夜曲", "", "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3Ax/document/1A2B-3C4D%3Ax%2F%E5%A4%9C.ape"),
    )

    private fun bytes(s: LibrarySnapshot) = ByteArrayOutputStream().also { s.write(it) }.toByteArray()

    @Test
    fun roundTripKeepsEverySongAndScanState() {
        val snap = LibrarySnapshot(store + tree, tree, mapOf("content://tree/a" to 42L), mapOf("/m/a.wma|10" to 0L, "/m/b.mp3|20" to 31_000L))
        val back = LibrarySnapshot.read(bytes(snap).inputStream())!!
        assertEquals(snap.songs, back.songs)
        assertEquals(tree, back.treeSongs)
        assertEquals(snap.trees, back.trees)
        assertEquals(snap.probed, back.probed)
    }

    @Test
    fun storeSavesAtomicallyAndLoads() {
        val dir = Files.createTempDirectory("snap").toFile()
        val s = SnapshotStore(dir)
        assertNull(s.load())
        s.save(LibrarySnapshot(store))
        s.save(LibrarySnapshot(store.take(3)))
        assertEquals(store.take(3), s.load()!!.songs)
        assertEquals(listOf("library.snap"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test
    fun foreignTruncatedOrOtherSchemaFilesAreIgnored() {
        val good = bytes(LibrarySnapshot(store))
        assertNull(LibrarySnapshot.read(good.copyOf(good.size - 7).inputStream()))
        assertNull(LibrarySnapshot.read(ByteArray(0).inputStream()))
        assertNull(LibrarySnapshot.read("[{\"u\":1}]".toByteArray().inputStream()))
        val otherSchema = good.copyOf().also { it[7] = (it[7] + 1).toByte() }
        assertNull(LibrarySnapshot.read(otherSchema.inputStream()))
    }

    @Test
    fun unchangedScanDoesNotRepublish() {
        val current = Library.build(store + tree)
        assertFalse(LibrarySnapshot.changed(current, (tree + store).shuffled(java.util.Random(3))))
        assertTrue(LibrarySnapshot.changed(Library(), store + tree))
        assertTrue(LibrarySnapshot.changed(current, store))
        assertTrue(LibrarySnapshot.changed(current, store + tree + song(99)))
        assertTrue(LibrarySnapshot.changed(current, store.map { if (it.id == 7L) it.copy(genre = "Jazz") else it } + tree))
        val restored = Library.build(LibrarySnapshot.read(bytes(LibrarySnapshot(current.songs)).inputStream())!!.songs)
        assertFalse(LibrarySnapshot.changed(restored, store + tree))
    }
}
