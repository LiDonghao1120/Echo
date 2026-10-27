package com.novcloud.echo.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.EchoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MigrationTest {
    private val v1 = listOf(
            "CREATE TABLE IF NOT EXISTS `play_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumId` INTEGER NOT NULL, `genre` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `songDurationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_play_event_startedAt` ON `play_event` (`startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_play_event_songId` ON `play_event` (`songId`)",
            "CREATE TABLE IF NOT EXISTS `playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `playlist_entry` (`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`))",
            "CREATE INDEX IF NOT EXISTS `index_playlist_entry_playlistId` ON `playlist_entry` (`playlistId`)",
            "CREATE TABLE IF NOT EXISTS `favorite` (`songId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
            "CREATE TABLE IF NOT EXISTS `artist_image` (`artist` TEXT NOT NULL, `path` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0cb3ac7eee716d48db7452b480643496')",
    )

    private val v2 = listOf(
        "CREATE TABLE IF NOT EXISTS `play_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `songId` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumId` INTEGER NOT NULL, `genre` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `songDurationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_play_event_startedAt` ON `play_event` (`startedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_play_event_songId` ON `play_event` (`songId`)",
        "CREATE TABLE IF NOT EXISTS `playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `playlist_entry` (`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`))",
        "CREATE INDEX IF NOT EXISTS `index_playlist_entry_playlistId` ON `playlist_entry` (`playlistId`)",
        "CREATE TABLE IF NOT EXISTS `favorite` (`songId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
        "CREATE TABLE IF NOT EXISTS `artist_image` (`artist` TEXT NOT NULL, `path` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))",
        "CREATE TABLE IF NOT EXISTS `artist_logo` (`artist` TEXT NOT NULL, `path` TEXT NOT NULL, `aspect` REAL NOT NULL, `luma` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '8934c773ef41b6c00e6adea67514a56f')",
    )

    private fun song(path: String, title: String, mediaId: Long) = SongKeys.of(path).let { key ->
        Song(SongKeys.id(key), title, "Ivy Calder", "Paper Satellites", 1, "", "Pop", 201_000, 1, 1, 2024, 0, path, path.substringBeforeLast('/'), "", 1, key = key, mediaId = mediaId)
    }

    @Test
    fun v2ToV3ConvertsIdsAndTheFirstScanRematchesThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("migrate3.db")
        val file = context.getDatabasePath("migrate3.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v2.forEach { db.execSQL(it) }
            fun play(id: Long, title: String, at: Long) = db.execSQL(
                "INSERT INTO play_event (songId,title,artist,album,albumId,genre,startedAt,listenedMs,songDurationMs,completed) " +
                    "VALUES ($id,'$title','Ivy Calder','Paper Satellites',3,'Pop',$at,200000,201000,1)",
            )
            play(7, "Glasshouse", 1000)
            play(9, "Low Tide", 2000)
            play(9, "Low Tide", 3000)
            play(11, "Gone Song", 4000)
            db.execSQL("INSERT INTO playlist (id,name,createdAt) VALUES (1,'Mine',5)")
            db.execSQL("INSERT INTO playlist_entry (playlistId,songId,position,addedAt) VALUES (1,7,0,6)")
            db.execSQL("INSERT INTO playlist_entry (playlistId,songId,position,addedAt) VALUES (1,9,1,6)")
            db.execSQL("INSERT INTO favorite (songId,addedAt) VALUES (7,9)")
            db.execSQL("INSERT INTO favorite (songId,addedAt) VALUES (9,10)")
            db.execSQL("INSERT INTO favorite (songId,addedAt) VALUES (11,11)")
            db.version = 2
        }
        val db = EchoDatabase.create(context, "migrate3.db")
        assertEquals(setOf("id:7", "id:9", "id:11"), db.favorites().keys().first().toSet())
        assertEquals(listOf("id:7", "id:9"), db.playlists().songKeys(1).first())
        assertEquals(4, db.events().all().size)
        assertEquals("Low Tide", db.identity().refs().single { it.key == "id:9" }.title)

        // id 7 still exists; the file behind id 9 was moved and reindexed under a new _ID; id 11 is gone.
        val kept = song("/storage/emulated/0/Music/Glasshouse.flac", "Glasshouse", 7)
        val moved = song("/storage/emulated/0/Music/Ivy/Low Tide.flac", "Low Tide", 300)
        val lib = Library.build(listOf(kept, moved))
        val dao = db.identity()
        val plan = Rematch.plan(dao.referencedKeys(), dao.refs().associateBy { it.key }, lib)
        plan.moves.forEach { (old, new) -> dao.move(old, new, SongKeys.id(new)) }
        dao.putRefs(plan.refs)

        assertEquals(setOf(kept.key, moved.key, "id:11"), db.favorites().keys().first().toSet())
        assertEquals(listOf(kept.key, moved.key), db.playlists().songKeys(1).first())
        val events = db.events().all()
        assertEquals(listOf(kept.id, moved.id, moved.id, 11L), events.map { it.songId })
        assertEquals(listOf(kept.key, moved.key, moved.key, "id:11"), events.map { it.songKey })
        assertEquals(mapOf("id:7" to kept.key, "id:9" to moved.key), dao.aliases().associate { it.oldKey to it.newKey })
        assertTrue(dao.refs().any { it.key == "id:11" })

        val again = Rematch.plan(dao.referencedKeys(), dao.refs().associateBy { it.key }, lib)
        assertTrue(again.moves.isEmpty())
        db.close()
    }

    @Test
    fun v1ToV2KeepsEverything() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("migrate.db")
        val file = context.getDatabasePath("migrate.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v1.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO play_event (songId,title,artist,album,albumId,genre,startedAt,listenedMs,songDurationMs,completed) VALUES (7,'t','a','al',3,'Pop',1000,200000,210000,1)")
            db.execSQL("INSERT INTO playlist (id,name,createdAt) VALUES (1,'Mine',5)")
            db.execSQL("INSERT INTO playlist_entry (playlistId,songId,position,addedAt) VALUES (1,7,0,6)")
            db.execSQL("INSERT INTO favorite (songId,addedAt) VALUES (7,9)")
            db.execSQL("INSERT INTO artist_image (artist,path,updatedAt) VALUES ('a','/x.jpg',1)")
            db.version = 1
        }
        val db = EchoDatabase.create(context, "migrate.db")
        assertEquals(1, db.events().since(0).first().size)
        assertEquals(listOf("id:7"), db.playlists().songKeys(1).first())
        assertEquals(listOf("id:7"), db.favorites().keys().first())
        assertEquals("/x.jpg", db.artistImages().all().first().single().path)
        db.artistLogos().put(ArtistLogo("a", "/l.png", 3.2f, 0.9f, 2))
        assertEquals(3.2f, db.artistLogos().all().first().single().aspect, 0.001f)
        db.close()
    }
}
