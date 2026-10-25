package com.novcloud.echo.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.data.db.Favorite
import com.novcloud.echo.data.db.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LibraryRepository(
    private val context: Context,
    private val db: EchoDatabase,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val scanner = MediaScanner(context)
    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val mutex = Mutex()
    private var observerRegistered = false
    private var pending: Job? = null

    val favorites: StateFlow<Set<Long>> = db.favorites().ids().map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    val favoriteOrder: StateFlow<List<Long>> = db.favorites().ids()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val playlists = db.playlists().all().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val artistImages: StateFlow<Map<String, String>> = db.artistImages().all()
        .map { l -> l.associate { it.artist to it.path } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val artistLogos: StateFlow<Map<String, ArtistLogoInfo>> = db.artistLogos().all()
        .map { l -> l.associate { it.artist to ArtistLogoInfo(File(it.path), it.aspect, it.luma) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    internal fun publish(lib: Library) {
        _library.value = lib
    }

    fun refresh() {
        scope.launch { rescan() }
    }

    suspend fun rescan() = mutex.withLock {
        _scanning.value = true
        try {
            val min = prefs.minDurationSec.first() * 1000L
            val songs = withContext(Dispatchers.IO) { runCatching { scanner.scan(min) }.getOrDefault(emptyList()) }
            _library.value = withContext(Dispatchers.Default) { Library.build(songs) }
        } finally {
            _scanning.value = false
        }
        registerObserver()
    }

    suspend fun awaitLoaded(): Library {
        if (!_library.value.loaded) rescan()
        return _library.first { it.loaded }
    }

    private fun registerObserver() {
        if (observerRegistered) return
        observerRegistered = true
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pending?.cancel()
                pending = scope.launch {
                    delay(1500)
                    rescan()
                }
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
    }

    fun toggleFavorite(id: Long) {
        scope.launch {
            if (id in favorites.value) db.favorites().remove(id) else db.favorites().add(Favorite(id, System.currentTimeMillis()))
        }
    }

    fun playlistSongs(id: Long) = db.playlists().songIds(id)
    fun playlist(id: Long) = db.playlists().byId(id)

    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long {
        val id = db.playlists().insert(Playlist(name = name, createdAt = System.currentTimeMillis()))
        if (songIds.isNotEmpty()) db.playlists().add(id, songIds)
        return id
    }

    fun addToPlaylist(id: Long, songIds: List<Long>) = scope.launch { db.playlists().add(id, songIds) }
    fun removeFromPlaylist(id: Long, songId: Long) = scope.launch { db.playlists().removeEntry(id, songId) }
    fun renamePlaylist(id: Long, name: String) = scope.launch { db.playlists().rename(id, name) }
    fun deletePlaylist(id: Long) = scope.launch { db.playlists().delete(id) }

    fun setArtistImage(artist: String, source: Uri) = scope.launch(Dispatchers.IO) { putPoster(artist, source) }

    fun clearArtistImage(artist: String) = scope.launch(Dispatchers.IO) {
        artistImages.value[artist]?.let { File(it).delete() }
        db.artistImages().remove(artist)
    }

    fun setArtistLogo(artist: String, source: Uri) = scope.launch(Dispatchers.IO) { putLogo(artist, source) }

    fun clearArtistLogo(artist: String) = scope.launch(Dispatchers.IO) {
        artistLogos.value[artist]?.file?.delete()
        db.artistLogos().remove(artist)
    }

    private suspend fun putPoster(artist: String, source: Uri): Boolean {
        val file = runCatching { ArtistArtStore.savePoster(context, artist, source) }.getOrNull() ?: return false
        artistImages.value[artist]?.let { if (it != file.absolutePath) File(it).delete() }
        db.artistImages().put(ArtistImage(artist, file.absolutePath, System.currentTimeMillis()))
        return true
    }

    private suspend fun putLogo(artist: String, source: Uri): Boolean {
        val info = runCatching { ArtistArtStore.saveLogo(context, artist, source) }.getOrNull() ?: return false
        artistLogos.value[artist]?.file?.let { if (it != info.file) it.delete() }
        db.artistLogos().put(ArtistLogo(artist, info.file.absolutePath, info.aspect, info.luma, System.currentTimeMillis()))
        return true
    }

    suspend fun importFolder(tree: Uri): ImportReport = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext ImportReport(0, 0, emptyList())
        val index = ArtistFileMatcher.index(_library.value.artists.map { it.name })
        var logos = 0
        var posters = 0
        val unmatched = ArrayList<String>()
        root.listFiles().filter { it.isFile }.forEach { f ->
            val name = f.name ?: return@forEach
            val m = ArtistFileMatcher.match(name, index)
            if (m == null) {
                if (name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")) unmatched += name
                return@forEach
            }
            when (m.kind) {
                ArtKind.Logo -> if (putLogo(m.artist, f.uri)) logos++
                ArtKind.Poster -> if (putPoster(m.artist, f.uri)) posters++
            }
        }
        ImportReport(logos, posters, unmatched)
    }
}
