package com.novcloud.echo.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.novcloud.echo.BuildConfig
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.data.db.Playlist
import com.novcloud.echo.data.db.PlaylistEntry
import com.novcloud.echo.diag.DiagLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A backup read from a file and waiting for the user to pick merge or replace. */
class PendingImport(val data: BackupData, internal val artDir: File) {
    val favorites get() = data.favorites.size
    val playlists get() = data.playlists.size
    val events get() = data.events.size
    val artists get() = data.artists.size
}

data class ImportResult(val favorites: Int, val playlists: Int, val events: Int, val artists: Int, val lyrics: Int = 0)

class BackupManager(
    private val context: Context,
    private val db: EchoDatabase,
    private val prefs: Prefs,
    private val repo: LibraryRepository,
) {
    private val work get() = File(context.cacheDir, "backup")

    suspend fun current(): BackupData = withContext(Dispatchers.IO) {
        val entries = db.playlists().allEntries().groupBy { it.playlistId }
        BackupData(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            favorites = db.favorites().all(),
            playlists = db.playlists().allPlaylists().map { p ->
                BackupPlaylist(p.name, p.createdAt, entries[p.id].orEmpty().map { BackupEntry(it.songKey, it.position, it.addedAt) })
            },
            events = db.events().all(),
            refs = db.identity().refs(),
            aliases = db.identity().aliases(),
            settings = prefs.backupSettings(),
            lyrics = db.lyricOverrides().all(),
        )
    }

    suspend fun export(target: Uri): Result<BackupData> = withContext(Dispatchers.IO) {
        runCatching {
            repo.reconcile()
            val base = current()
            val art = LinkedHashMap<String, File>()
            val posters = db.artistImages().allNow().associate { it.artist to File(it.path) }.filterValues { it.exists() }
            val logos = db.artistLogos().allNow().associate { it.artist to File(it.path) }.filterValues { it.exists() }
            val artists = (posters.keys + logos.keys).sorted().mapIndexed { i, name ->
                val p = posters[name]?.let { f -> "poster_$i.${f.extension.ifEmpty { "jpg" }}".also { art[it] = f } }
                val l = logos[name]?.let { f -> "logo_$i.${f.extension.ifEmpty { "png" }}".also { art[it] = f } }
                BackupArtist(name, p, l)
            }
            val data = base.copy(artists = artists)
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("cannot open $target")
            out.use { BackupCodec.write(data, art, it) }
            DiagLog.i(TAG, "exported backup: ${data.favorites.size} favorites, ${data.playlists.size} playlists, ${data.events.size} plays, ${artists.size} artists, ${art.size} images")
            data
        }.onFailure { DiagLog.e(TAG, "backup export failed", it) }
    }

    suspend fun read(source: Uri): Result<PendingImport> = withContext(Dispatchers.IO) {
        runCatching {
            work.deleteRecursively()
            val dir = File(work, "art")
            val input = context.contentResolver.openInputStream(source) ?: throw BackupException(BackupException.Reason.Unopenable)
            val data = input.use { BackupCodec.read(it, dir) }
            PendingImport(data, dir)
        }.onFailure { DiagLog.w(TAG, "backup read failed", it) }
    }

    suspend fun apply(pending: PendingImport, mode: ImportMode): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val before = prefs.backupSettings()
            val cur = current()
            val final = BackupMerge.combine(cur, pending.data, mode)
            db.withTransaction {
                db.favorites().clear()
                db.favorites().addAll(final.favorites)
                db.playlists().clearAllEntries()
                db.playlists().clearAllPlaylists()
                for (p in final.playlists) {
                    val id = db.playlists().insert(Playlist(name = p.name, createdAt = p.createdAt))
                    db.playlists().insertEntries(p.entries.map { PlaylistEntry(id, it.key, it.position, it.addedAt) })
                }
                db.events().clear()
                db.events().insertAll(final.events.map { it.copy(id = 0, songId = if (it.songKey.isEmpty()) it.songId else SongKeys.id(it.songKey)) })
                db.identity().clearRefs()
                db.identity().putRefs(final.refs)
                final.aliases.forEach { db.identity().putAlias(it) }
                db.lyricOverrides().clear()
                db.lyricOverrides().putAll(final.lyrics)
            }
            val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
            prefs.restoreSettings(final.settings) { it in granted }
            val artists = restoreArt(pending, mode)
            repo.reconcile()
            val after = prefs.backupSettings()
            if (after.scanIncluded != before.scanIncluded || after.scanExcluded != before.scanExcluded ||
                after.scanTrees != before.scanTrees || after.minDurationSec != before.minDurationSec
            ) repo.refresh()
            val base = if (mode == ImportMode.Merge) cur else BackupData()
            val result = ImportResult(
                final.favorites.size - base.favorites.size,
                final.playlists.size - base.playlists.size,
                final.events.size - base.events.size,
                artists,
                final.lyrics.size - base.lyrics.size,
            )
            DiagLog.i(TAG, "imported backup ($mode): $result")
            work.deleteRecursively()
            result
        }.onFailure { DiagLog.e(TAG, "backup import failed", it) }
    }

    private suspend fun restoreArt(pending: PendingImport, mode: ImportMode): Int {
        val images = db.artistImages()
        val logos = db.artistLogos()
        if (mode == ImportMode.Replace) {
            images.allNow().forEach { File(it.path).delete(); images.remove(it.artist) }
            logos.allNow().forEach { File(it.path).delete(); logos.remove(it.artist) }
        }
        var count = 0
        val now = System.currentTimeMillis()
        for (a in pending.data.artists) {
            var done = false
            a.poster?.let { File(pending.artDir, it) }?.takeIf { it.exists() && images.get(a.artist) == null }?.let { f ->
                ArtistArtStore.savePoster(context, a.artist, Uri.fromFile(f))?.let {
                    images.put(ArtistImage(a.artist, it.absolutePath, now))
                    done = true
                }
            }
            a.logo?.let { File(pending.artDir, it) }?.takeIf { it.exists() && logos.get(a.artist) == null }?.let { f ->
                ArtistArtStore.saveLogo(context, a.artist, Uri.fromFile(f))?.let {
                    logos.put(ArtistLogo(a.artist, it.file.absolutePath, it.aspect, it.luma, now))
                    done = true
                }
            }
            if (done) count++
        }
        return count
    }

    fun discard() {
        work.deleteRecursively()
    }

    private companion object {
        const val TAG = "EchoBackup"
    }
}
