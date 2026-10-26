package com.novcloud.echo.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Last scan result, so a cold start can publish the library before any MediaStore or SAF query runs.
 * [treeSongs] are the raw SAF tree results (before rules/duration filtering), [trees] the shallow
 * listing signature of each tree at walk time, [probed] MediaStore durations read with MMR keyed by path|size.
 */
class LibrarySnapshot(
    val songs: List<Song>,
    val treeSongs: List<Song> = emptyList(),
    val trees: Map<String, Long> = emptyMap(),
    val probed: Map<String, Long> = emptyMap(),
) {
    fun write(out: OutputStream) {
        val d = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        d.writeInt(MAGIC)
        d.writeInt(SCHEMA)
        writeSongs(d, songs)
        writeSongs(d, treeSongs)
        d.writeInt(trees.size)
        trees.forEach { (k, v) -> d.str(k); d.writeLong(v) }
        d.writeInt(probed.size)
        probed.forEach { (k, v) -> d.str(k); d.writeLong(v) }
        d.writeInt(MAGIC)
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x45434831
        private const val FORMAT = 1

        /** Changes whenever [Song]'s fields change, so an older file is discarded instead of misread. */
        val SCHEMA: Int = FORMAT * 31 + Song::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { "${it.name}:${it.type.name}" }.sorted().joinToString(",").hashCode()

        fun read(input: InputStream): LibrarySnapshot? = runCatching {
            val d = DataInputStream(BufferedInputStream(input, 1 shl 16))
            if (d.readInt() != MAGIC || d.readInt() != SCHEMA) return null
            val songs = readSongs(d)
            val tree = readSongs(d)
            val trees = HashMap<String, Long>().apply { repeat(d.readInt()) { put(d.str(), d.readLong()) } }
            val probed = HashMap<String, Long>().apply { repeat(d.readInt()) { put(d.str(), d.readLong()) } }
            if (d.readInt() != MAGIC) return null
            LibrarySnapshot(songs, tree, trees, probed)
        }.getOrNull()

        fun sameSongs(a: List<Song>, b: List<Song>): Boolean = a.size == b.size && HashSet(a) == HashSet(b)

        /** Whether a scan result has to be published over [current]; an identical song set keeps the existing library. */
        fun changed(current: Library, songs: List<Song>): Boolean = !current.loaded || !sameSongs(current.songs, songs)

        private fun writeSongs(d: DataOutputStream, list: List<Song>) {
            d.writeInt(list.size)
            for (s in list) {
                d.writeLong(s.id); d.str(s.title); d.str(s.artist); d.str(s.album); d.writeLong(s.albumId)
                d.str(s.albumArtist); d.str(s.genre); d.writeLong(s.durationMs); d.writeInt(s.track); d.writeInt(s.disc)
                d.writeInt(s.year); d.writeLong(s.dateAdded); d.str(s.path); d.str(s.folder); d.str(s.mimeType)
                d.writeLong(s.size); d.str(s.docUri)
            }
        }

        private fun readSongs(d: DataInputStream): List<Song> {
            val n = d.readInt()
            require(n in 0..1_000_000)
            return ArrayList<Song>(n).apply {
                repeat(n) {
                    add(
                        Song(
                            id = d.readLong(), title = d.str(), artist = d.str(), album = d.str(), albumId = d.readLong(),
                            albumArtist = d.str(), genre = d.str(), durationMs = d.readLong(), track = d.readInt(), disc = d.readInt(),
                            year = d.readInt(), dateAdded = d.readLong(), path = d.str(), folder = d.str(), mimeType = d.str(),
                            size = d.readLong(), docUri = d.str(),
                        ),
                    )
                }
            }
        }

        private fun DataOutputStream.str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeInt(b.size)
            write(b)
        }

        private fun DataInputStream.str(): String {
            val n = readInt()
            require(n in 0..1_000_000)
            return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
        }
    }
}

class SnapshotStore(dir: File) {
    private val file = File(dir, "library.snap")

    fun load(): LibrarySnapshot? = if (file.exists()) file.inputStream().use { LibrarySnapshot.read(it) } else null

    @Synchronized
    fun save(s: LibrarySnapshot) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.outputStream().use { s.write(it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { tmp.delete() }
    }
}
