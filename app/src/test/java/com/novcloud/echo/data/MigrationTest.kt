package com.novcloud.echo.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.EchoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
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
        assertEquals(listOf(7L), db.playlists().songIds(1).first())
        assertEquals(listOf(7L), db.favorites().ids().first())
        assertEquals("/x.jpg", db.artistImages().all().first().single().path)
        db.artistLogos().put(ArtistLogo("a", "/l.png", 3.2f, 0.9f, 2))
        assertEquals(3.2f, db.artistLogos().all().first().single().aspect, 0.001f)
        db.close()
    }
}
