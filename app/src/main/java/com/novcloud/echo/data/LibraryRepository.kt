package com.novcloud.echo.data

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
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
    private val treeScanner = TreeScanner(context)
    private val genres = GenreResolver(context)
    private var genreJob: Job? = null
    private val _genreProbing = MutableStateFlow(false)
    val genreProbing: StateFlow<Boolean> = _genreProbing.asStateFlow()
    private var treeSongs: List<Song> = emptyList()
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()
    private val _artBusy = MutableStateFlow<Set<String>>(emptySet())
    val artBusy: StateFlow<Set<String>> = _artBusy.asStateFlow()
    private val _artFailed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val artFailed: SharedFlow<String> = _artFailed.asSharedFlow()
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
        .map { l -> l.filter { File(it.path).exists() }.associate { it.artist to it.path } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val artistLogos: StateFlow<Map<String, ArtistLogoInfo>> = db.artistLogos().all()
        .map { l -> l.filter { File(it.path).exists() }.associate { it.artist to ArtistLogoInfo(File(it.path), it.aspect, it.luma) } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    internal fun publish(lib: Library) {
        _library.value = lib
    }

    fun refresh(full: Boolean = true) {
        scope.launch { rescan(full) }
    }

    suspend fun rescan(full: Boolean = true) = mutex.withLock {
        _scanning.value = true
        genreJob?.cancel()
        try {
            val min = prefs.minDurationSec.first() * 1000L
            val rules = FolderRules(prefs.scanIncluded.first(), prefs.scanExcluded.first())
            val trees = prefs.scanTrees.first().filter(::hasPermission)
            val songs = withContext(Dispatchers.IO) {
                val store = runCatching { scanner.scan(rules, min) }.getOrNull()
                    ?: MediaScanner.Result(emptyList(), emptySet())
                if (full || treeSongs.isEmpty() && trees.isNotEmpty()) {
                    treeSongs = runCatching {
                        treeScanner.scan(trees, rules, store.knownPaths) { _progress.value = it }
                    }.getOrDefault(treeSongs)
                }
                val extra = treeSongs.filter { it.durationMs >= min && rules.accepts(it.folder, true) && it.path.lowercase() !in store.knownPaths }
                genres.apply(Dedupe.merge(store.songs, extra))
            }
            _library.value = withContext(Dispatchers.Default) { Library.build(songs) }
            fillGenres(songs)
        } finally {
            _scanning.value = false
            _progress.value = null
        }
        registerObserver()
    }

    private fun fillGenres(songs: List<Song>) {
        genreJob = scope.launch(Dispatchers.IO) {
            val todo = genres.unresolved(songs)
            if (todo.isEmpty()) return@launch
            _genreProbing.value = true
            try {
                var found = 0
                for ((i, s) in todo.withIndex()) {
                    if (!isActive) break
                    if (genres.probe(s).isNotEmpty()) found++
                    if ((i + 1) % 200 == 0 || i == todo.lastIndex) {
                        genres.save()
                        if (found > 0) {
                            found = 0
                            val lib = Library.build(genres.apply(_library.value.songs))
                            if (isActive) _library.value = lib
                        }
                    }
                }
            } finally {
                genres.save()
                _genreProbing.value = false
            }
        }
    }

    private fun hasPermission(tree: String) = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }

    suspend fun folders(): List<AudioFolder> = withContext(Dispatchers.IO) { scanner.folders() }

    suspend fun addTree(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.addTree(tree.toString(), SafPaths.treePath(tree))
        rescan(true)
    }

    suspend fun removeTree(tree: String) {
        val uri = Uri.parse(tree)
        prefs.removeTree(tree, SafPaths.treePath(uri))
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        withContext(Dispatchers.IO) { treeScanner.forget(tree) }
        rescan(true)
    }

    suspend fun setFolder(path: String, on: Boolean) {
        prefs.setFolder(path, on)
        rescan(prefs.scanTrees.first().isNotEmpty())
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
                    rescan(false)
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

    fun setArtistImage(artist: String, source: Uri) = scope.launch { withArt(artist) { putPoster(artist, source) } }

    fun clearArtistImage(artist: String) = scope.launch(Dispatchers.IO) {
        artistImages.value[artist]?.let { File(it).delete() }
        db.artistImages().remove(artist)
    }

    fun setArtistLogo(artist: String, source: Uri) = scope.launch { withArt(artist) { putLogo(artist, source) } }

    fun clearArtistLogo(artist: String) = scope.launch(Dispatchers.IO) {
        artistLogos.value[artist]?.file?.delete()
        db.artistLogos().remove(artist)
    }

    private suspend fun withArt(artist: String, block: suspend () -> Boolean) {
        _artBusy.value = _artBusy.value + artist
        try {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(false) }
            if (!ok) _artFailed.tryEmit(artist)
        } finally {
            _artBusy.value = _artBusy.value - artist
        }
    }

    private suspend fun putPoster(artist: String, source: Uri): Boolean {
        val file = runCatching { ArtistArtStore.savePoster(context, artist, source) }.getOrNull() ?: return false
        val old = db.artistImages().get(artist)?.path
        db.artistImages().put(ArtistImage(artist, file.absolutePath, System.currentTimeMillis()))
        if (old != null && old != file.absolutePath) File(old).delete()
        return true
    }

    private suspend fun putLogo(artist: String, source: Uri): Boolean {
        val info = runCatching { ArtistArtStore.saveLogo(context, artist, source) }.getOrNull() ?: return false
        val old = db.artistLogos().get(artist)?.path
        db.artistLogos().put(ArtistLogo(artist, info.file.absolutePath, info.aspect, info.luma, System.currentTimeMillis()))
        if (old != null && old != info.file.absolutePath) File(old).delete()
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
