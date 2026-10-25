package com.novcloud.echo.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "play_event", indices = [Index("startedAt"), Index("songId")])
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String,
    val startedAt: Long,
    val listenedMs: Long,
    val songDurationMs: Long,
    val completed: Boolean,
)

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_entry", primaryKeys = ["playlistId", "songId"], indices = [Index("playlistId")])
data class PlaylistEntry(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "favorite")
data class Favorite(@PrimaryKey val songId: Long, val addedAt: Long)

@Entity(tableName = "artist_image")
data class ArtistImage(@PrimaryKey val artist: String, val path: String, val updatedAt: Long)

@Entity(tableName = "artist_logo")
data class ArtistLogo(
    @PrimaryKey val artist: String,
    val path: String,
    val aspect: Float,
    val luma: Float,
    val updatedAt: Long,
)

data class PlaylistWithCount(val id: Long, val name: String, val createdAt: Long, val count: Int, val firstSongId: Long?)

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEvent)

    @Query("SELECT * FROM play_event WHERE startedAt >= :from ORDER BY startedAt ASC")
    fun since(from: Long): Flow<List<PlayEvent>>

    @Query("SELECT * FROM play_event ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlayEvent>>

    @Query("SELECT MIN(startedAt) FROM play_event")
    fun firstEventAt(): Flow<Long?>

    @Query("DELETE FROM play_event")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.createdAt, COUNT(e.songId) AS count,
           (SELECT songId FROM playlist_entry WHERE playlistId = p.id ORDER BY position LIMIT 1) AS firstSongId
           FROM playlist p LEFT JOIN playlist_entry e ON e.playlistId = p.id
           GROUP BY p.id ORDER BY p.createdAt DESC"""
    )
    fun all(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun byId(id: Long): Flow<Playlist?>

    @Query("SELECT songId FROM playlist_entry WHERE playlistId = :id ORDER BY position")
    fun songIds(id: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(p: Playlist): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entry WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entry WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(e: List<PlaylistEntry>)

    @Query("DELETE FROM playlist_entry WHERE playlistId = :id AND songId = :songId")
    suspend fun removeEntry(id: Long, songId: Long)

    @Transaction
    suspend fun delete(id: Long) {
        clearEntries(id)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun add(id: Long, songIds: List<Long>) {
        var pos = maxPosition(id) + 1
        val now = System.currentTimeMillis()
        insertEntries(songIds.map { PlaylistEntry(id, it, pos++, now) })
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT songId FROM favorite ORDER BY addedAt DESC")
    fun ids(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: Favorite)

    @Query("DELETE FROM favorite WHERE songId = :id")
    suspend fun remove(id: Long)
}

@Dao
interface ArtistImageDao {
    @Query("SELECT * FROM artist_image")
    fun all(): Flow<List<ArtistImage>>

    @Query("SELECT * FROM artist_image WHERE artist = :artist")
    suspend fun get(artist: String): ArtistImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(a: ArtistImage)

    @Query("DELETE FROM artist_image WHERE artist = :artist")
    suspend fun remove(artist: String)
}

@Dao
interface ArtistLogoDao {
    @Query("SELECT * FROM artist_logo")
    fun all(): Flow<List<ArtistLogo>>

    @Query("SELECT * FROM artist_logo WHERE artist = :artist")
    suspend fun get(artist: String): ArtistLogo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(a: ArtistLogo)

    @Query("DELETE FROM artist_logo WHERE artist = :artist")
    suspend fun remove(artist: String)
}

@Database(
    entities = [PlayEvent::class, Playlist::class, PlaylistEntry::class, Favorite::class, ArtistImage::class, ArtistLogo::class],
    version = 2,
    exportSchema = true,
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun events(): PlayEventDao
    abstract fun playlists(): PlaylistDao
    abstract fun favorites(): FavoriteDao
    abstract fun artistImages(): ArtistImageDao
    abstract fun artistLogos(): ArtistLogoDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_logo` (`artist` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`aspect` REAL NOT NULL, `luma` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))",
                )
            }
        }

        fun create(context: Context, name: String = "echo.db") =
            Room.databaseBuilder(context, EchoDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}
