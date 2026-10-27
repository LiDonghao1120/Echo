package com.novcloud.echo.data.db

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "play_event", indices = [Index("startedAt"), Index("songId"), Index("songKey")])
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
    @ColumnInfo(defaultValue = "") val songKey: String = "",
)

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_entry", primaryKeys = ["playlistId", "songKey"], indices = [Index("playlistId")])
data class PlaylistEntry(
    val playlistId: Long,
    val songKey: String,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "favorite")
data class Favorite(@PrimaryKey val songKey: String, val addedAt: Long)

/** Last known tags of every referenced song key, so a moved file can be found again by its metadata. */
@Entity(tableName = "song_ref")
data class SongRef(
    @PrimaryKey val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Where a key went when its file moved, for references outside the database (saved queue, older backups). */
@Entity(tableName = "song_alias")
data class SongAlias(@PrimaryKey val oldKey: String, val newKey: String)

/** Per-song lyric settings by song key: a timing offset (ms, positive shows lines earlier) and a lyric file the user picked, stored as text. */
@Entity(tableName = "lyric_override")
data class LyricOverride(
    @PrimaryKey val songKey: String,
    val offsetMs: Long = 0,
    val fileName: String? = null,
    val text: String? = null,
    val updatedAt: Long = 0,
)

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

data class PlaylistWithCount(val id: Long, val name: String, val createdAt: Long, val count: Int, val firstSongKey: String?)

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEvent): Long

    @Update
    suspend fun update(e: PlayEvent)

    @Query("SELECT * FROM play_event WHERE startedAt >= :from ORDER BY startedAt ASC")
    fun since(from: Long): Flow<List<PlayEvent>>

    @Query("SELECT * FROM play_event ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PlayEvent>>

    @Query("SELECT MIN(startedAt) FROM play_event")
    fun firstEventAt(): Flow<Long?>

    @Query("DELETE FROM play_event")
    suspend fun clear()

    @Query("SELECT * FROM play_event ORDER BY startedAt ASC")
    suspend fun all(): List<PlayEvent>

    @Insert
    suspend fun insertAll(e: List<PlayEvent>)
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.createdAt, COUNT(e.songKey) AS count,
           (SELECT songKey FROM playlist_entry WHERE playlistId = p.id ORDER BY position LIMIT 1) AS firstSongKey
           FROM playlist p LEFT JOIN playlist_entry e ON e.playlistId = p.id
           GROUP BY p.id ORDER BY p.createdAt DESC"""
    )
    fun all(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun byId(id: Long): Flow<Playlist?>

    @Query("SELECT songKey FROM playlist_entry WHERE playlistId = :id ORDER BY position")
    fun songKeys(id: Long): Flow<List<String>>

    @Query("SELECT * FROM playlist ORDER BY createdAt ASC")
    suspend fun allPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlist_entry ORDER BY playlistId, position")
    suspend fun allEntries(): List<PlaylistEntry>

    @Query("DELETE FROM playlist_entry")
    suspend fun clearAllEntries()

    @Query("DELETE FROM playlist")
    suspend fun clearAllPlaylists()

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

    @Query("DELETE FROM playlist_entry WHERE playlistId = :id AND songKey = :key")
    suspend fun removeEntry(id: Long, key: String)

    @Transaction
    suspend fun delete(id: Long) {
        clearEntries(id)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun add(id: Long, keys: List<String>) {
        var pos = maxPosition(id) + 1
        val now = System.currentTimeMillis()
        insertEntries(keys.map { PlaylistEntry(id, it, pos++, now) })
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT songKey FROM favorite ORDER BY addedAt DESC")
    fun keys(): Flow<List<String>>

    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    suspend fun all(): List<Favorite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(f: Favorite)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAll(f: List<Favorite>)

    @Query("DELETE FROM favorite WHERE songKey = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM favorite")
    suspend fun clear()
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM song_ref")
    suspend fun refs(): List<SongRef>

    @Query("SELECT songKey FROM favorite UNION SELECT songKey FROM playlist_entry UNION SELECT songKey FROM lyric_override UNION SELECT songKey FROM play_event WHERE songKey != ''")
    suspend fun referencedKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRefs(refs: List<SongRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRefs(refs: List<SongRef>)

    @Query("DELETE FROM song_ref")
    suspend fun clearRefs()

    @Query("SELECT * FROM song_alias")
    suspend fun aliases(): List<SongAlias>

    @Query("INSERT OR IGNORE INTO favorite (songKey, addedAt) SELECT :new, addedAt FROM favorite WHERE songKey = :old")
    suspend fun copyFavorite(old: String, new: String)

    @Query("DELETE FROM favorite WHERE songKey = :old")
    suspend fun dropFavorite(old: String)

    @Query("INSERT OR IGNORE INTO playlist_entry (playlistId, songKey, position, addedAt) SELECT playlistId, :new, position, addedAt FROM playlist_entry WHERE songKey = :old")
    suspend fun copyEntries(old: String, new: String)

    @Query("DELETE FROM playlist_entry WHERE songKey = :old")
    suspend fun dropEntries(old: String)

    @Query("INSERT OR IGNORE INTO lyric_override (songKey, offsetMs, fileName, text, updatedAt) SELECT :new, offsetMs, fileName, text, updatedAt FROM lyric_override WHERE songKey = :old")
    suspend fun copyLyrics(old: String, new: String)

    @Query("DELETE FROM lyric_override WHERE songKey = :old")
    suspend fun dropLyrics(old: String)

    @Query("UPDATE play_event SET songKey = :new, songId = :newId WHERE songKey = :old")
    suspend fun moveEvents(old: String, new: String, newId: Long)

    @Query("DELETE FROM song_ref WHERE `key` = :old")
    suspend fun dropRef(old: String)

    @Query("UPDATE song_alias SET newKey = :new WHERE newKey = :old")
    suspend fun forwardAliases(old: String, new: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlias(a: SongAlias)

    /** Points everything saved under [old] at [new]; rows already saved under [new] win. */
    @Transaction
    suspend fun move(old: String, new: String, newId: Long) {
        copyFavorite(old, new)
        dropFavorite(old)
        copyEntries(old, new)
        dropEntries(old)
        copyLyrics(old, new)
        dropLyrics(old)
        moveEvents(old, new, newId)
        dropRef(old)
        forwardAliases(old, new)
        putAlias(SongAlias(old, new))
    }
}

@Dao
interface LyricOverrideDao {
    @Query("SELECT * FROM lyric_override WHERE songKey = :key")
    fun observe(key: String): Flow<LyricOverride?>

    @Query("SELECT * FROM lyric_override WHERE songKey = :key")
    suspend fun get(key: String): LyricOverride?

    @Query("SELECT * FROM lyric_override")
    suspend fun all(): List<LyricOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(o: LyricOverride)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(o: List<LyricOverride>)

    @Query("DELETE FROM lyric_override WHERE songKey = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM lyric_override")
    suspend fun clear()

    @Transaction
    suspend fun setOffset(key: String, offsetMs: Long) {
        val o = (get(key) ?: LyricOverride(key)).copy(offsetMs = offsetMs, updatedAt = System.currentTimeMillis())
        if (o.offsetMs == 0L && o.text == null) remove(key) else put(o)
    }

    @Transaction
    suspend fun setFile(key: String, name: String?, text: String?) {
        val o = (get(key) ?: LyricOverride(key)).copy(fileName = name, text = text, updatedAt = System.currentTimeMillis())
        if (o.offsetMs == 0L && o.text == null) remove(key) else put(o)
    }
}

@Dao
interface ArtistImageDao {
    @Query("SELECT * FROM artist_image")
    fun all(): Flow<List<ArtistImage>>

    @Query("SELECT * FROM artist_image")
    suspend fun allNow(): List<ArtistImage>

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

    @Query("SELECT * FROM artist_logo")
    suspend fun allNow(): List<ArtistLogo>

    @Query("SELECT * FROM artist_logo WHERE artist = :artist")
    suspend fun get(artist: String): ArtistLogo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(a: ArtistLogo)

    @Query("DELETE FROM artist_logo WHERE artist = :artist")
    suspend fun remove(artist: String)
}

@Database(
    entities = [PlayEvent::class, Playlist::class, PlaylistEntry::class, Favorite::class, ArtistImage::class, ArtistLogo::class, SongRef::class, SongAlias::class, LyricOverride::class],
    version = 4,
    exportSchema = true,
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun events(): PlayEventDao
    abstract fun playlists(): PlaylistDao
    abstract fun favorites(): FavoriteDao
    abstract fun artistImages(): ArtistImageDao
    abstract fun artistLogos(): ArtistLogoDao
    abstract fun identity(): IdentityDao
    abstract fun lyricOverrides(): LyricOverrideDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_logo` (`artist` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`aspect` REAL NOT NULL, `luma` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`artist`))",
                )
            }
        }

        /**
         * Favorites and playlist entries were keyed by MediaStore _ID, which changes when a file is moved or renamed
         * or the media database is rebuilt. Existing rows become legacy "id:<old id>" keys; the first library scan
         * maps each to the file that still has that id (Rematch), and nothing that fails to match is dropped.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_new` (`songKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`songKey`))")
                db.execSQL("INSERT OR IGNORE INTO `favorite_new` (songKey, addedAt) SELECT 'id:' || songId, addedAt FROM favorite")
                db.execSQL("DROP TABLE `favorite`")
                db.execSQL("ALTER TABLE `favorite_new` RENAME TO `favorite`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_entry_new` (`playlistId` INTEGER NOT NULL, `songKey` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songKey`))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `playlist_entry_new` (playlistId, songKey, position, addedAt) " +
                        "SELECT playlistId, 'id:' || songId, position, addedAt FROM playlist_entry",
                )
                db.execSQL("DROP TABLE `playlist_entry`")
                db.execSQL("ALTER TABLE `playlist_entry_new` RENAME TO `playlist_entry`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entry_playlistId` ON `playlist_entry` (`playlistId`)")

                db.execSQL("ALTER TABLE `play_event` ADD COLUMN `songKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `play_event` SET songKey = 'id:' || songId")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_event_songKey` ON `play_event` (`songKey`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_ref` (`key` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `song_ref` (`key`, title, artist, album, durationMs, updatedAt) " +
                        "SELECT songKey, title, artist, album, songDurationMs, startedAt FROM play_event ORDER BY startedAt",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `song_ref` (`key`, title, artist, album, durationMs, updatedAt) " +
                        "SELECT songKey, '', '', '', 0, 0 FROM favorite UNION SELECT songKey, '', '', '', 0, 0 FROM playlist_entry",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `song_alias` (`oldKey` TEXT NOT NULL, `newKey` TEXT NOT NULL, PRIMARY KEY(`oldKey`))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyric_override` (`songKey` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL, " +
                        "`fileName` TEXT, `text` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`songKey`))",
                )
            }
        }

        fun create(context: Context, name: String = "echo.db") =
            Room.databaseBuilder(context, EchoDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
