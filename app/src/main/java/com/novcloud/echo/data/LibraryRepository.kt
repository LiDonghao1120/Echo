package com.novcloud.echo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import com.novcloud.echo.data.db.SongRef
import com.novcloud.echo.diag.DiagLog
import android.os.SystemClock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val identityLock = Mutex()
    @Volatile private var aliases: Map<String, String> = emptyMap()
    private var observerRegistered = false
    private var pending: Job? = null

    private val favoriteKeys = db.favorites().keys().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val favoriteOrder: StateFlow<List<Long>> = combine(favoriteKeys, _library) { keys, lib -> keys.mapNotNull { lib.songByKey[it]?.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val favorites: StateFlow<Set<Long>> = favoriteOrder.map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
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

    private val snapshots = SnapshotStore(context.filesDir)
    private var treeStamps: Map<String, Long> = emptyMap()
    private val probed = HashMap<String, Long>()
    private val restored: Deferred<Unit> = scope.async(Dispatchers.IO) {
        val snap = runCatching { snapshots.load() }.getOrNull() ?: return@async
        treeSongs = snap.treeSongs
        treeStamps = snap.trees
        probed.putAll(snap.probed)
        val lib = Library.build(snap.songs)
        if (!_library.value.loaded) {
            _library.value = lib
            reconcile(lib)
        }
    }
    private val started = AtomicBoolean(false)
    private var missingJob: Job? = null

    enum class Walk { Never, IfChanged, Always }

    /** Startup scan, once per process. Needs the audio permission; returns false without it. */
    fun start(): Boolean {
        if (!hasAudioPermission()) return false
        if (started.compareAndSet(false, true)) scope.launch { rescan(Walk.IfChanged) }
        return true
    }

    fun refresh(full: Boolean = true) {
        scope.launch { rescan(if (full) Walk.Always else Walk.Never) }
    }

    /** A queued file could not be opened; rescan so it drops out. */
    fun onMissingFile() {
        missingJob?.cancel()
        missingJob = scope.launch {
            delay(2000)
            rescan(Walk.Always)
        }
    }

    suspend fun rescan(full: Boolean) = rescan(if (full) Walk.Always else Walk.Never)

    suspend fun rescan(walk: Walk) {
        restored.await()
        mutex.withLock { scan(walk) }
        registerObserver()
    }

    private suspend fun scan(walk: Walk) {
        _scanning.value = true
        genreJob?.cancel()
        val t0 = SystemClock.elapsedRealtime()
        var tStore = 0L
        var tTrees = -1L
        try {
            val min = prefs.minDurationSec.first() * 1000L
            val rules = FolderRules(prefs.scanIncluded.first(), prefs.scanExcluded.first())
            val trees = prefs.scanTrees.first().filter(::hasPermission)
            val loaded = _library.value.loaded
            val songs = withContext(Dispatchers.IO) {
                val store = runCatching { scanner.scan(rules, min, probed) }
                    .onFailure { DiagLog.e(TAG, "MediaStore scan failed", it) }.getOrNull()
                    ?: if (loaded) return@withContext null else MediaScanner.Result(emptyList(), emptySet())
                tStore = SystemClock.elapsedRealtime() - t0
                if (trees.isEmpty()) {
                    treeSongs = emptyList()
                    treeStamps = emptyMap()
                } else {
                    val stamps = if (walk == Walk.Never && treeStamps.keys == trees.toSet()) treeStamps
                    else trees.associateWith { treeScanner.signature(it) ?: 0L }
                    if (walk == Walk.Always || stamps != treeStamps) {
                        val t1 = SystemClock.elapsedRealtime()
                        runCatching { treeScanner.scan(trees, rules, store.knownPaths) { _progress.value = it } }
                            .onSuccess { treeSongs = it; treeStamps = stamps }
                            .onFailure { DiagLog.e(TAG, "folder walk failed", it) }
                        tTrees = SystemClock.elapsedRealtime() - t1
                    }
                }
                val extra = treeSongs.filter { it.durationMs >= min && rules.accepts(it.folder, true) && it.path.lowercase() !in store.knownPaths }
                genres.apply(SongKeys.unique(Dedupe.merge(store.songs, extra)))
            } ?: return
            val changed = LibrarySnapshot.changed(_library.value, songs)
            if (changed) {
                _library.value = withContext(Dispatchers.Default) { Library.build(songs) }
            }
            reconcile(_library.value)
            saveSnapshot()
            DiagLog.i(
                TAG,
                "scan $walk: ${songs.size} songs (${treeSongs.size} from ${trees.size} folders), store ${tStore}ms" +
                    (if (tTrees >= 0) ", folders ${tTrees}ms" else "") + ", total ${SystemClock.elapsedRealtime() - t0}ms" +
                    if (changed) "" else ", unchanged",
            )
            fillGenres(songs)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiagLog.e(TAG, "scan $walk failed", e)
        } finally {
            _scanning.value = false
            _progress.value = null
        }
    }

    private suspend fun saveSnapshot() {
        val snap = LibrarySnapshot(_library.value.songs, treeSongs, treeStamps, HashMap(probed))
        withContext(Dispatchers.IO) { snapshots.save(snap) }
    }

    private fun fillGenres(songs: List<Song>) {
        genreJob = scope.launch(Dispatchers.IO) {
            val todo = genres.unresolved(songs)
            if (todo.isEmpty()) return@launch
            _genreProbing.value = true
            var changed = false
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
                            if (isActive) {
                                _library.value = lib
                                changed = true
                            }
                        }
                    }
                }
            } finally {
                genres.save()
                _genreProbing.value = false
            }
            if (changed && isActive) saveSnapshot()
        }
    }

    private fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
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

    /** The saved library when there is one, otherwise the result of the startup scan. */
    suspend fun awaitLoaded(): Library {
        restored.await()
        if (_library.value.loaded || !start()) return _library.value
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
                    rescan(Walk.Never)
                }
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
    }

    /** Re-points saved references at the current files (see [Rematch]) and refreshes their stored tags. */
    suspend fun reconcile(lib: Library = _library.value) = withContext(Dispatchers.IO) {
        if (!lib.loaded) return@withContext
        identityLock.withLock {
            runCatching {
                val dao = db.identity()
                val referenced = dao.referencedKeys()
                val refs = dao.refs().associateBy { it.key }
                val plan = Rematch.plan(referenced, refs, lib)
                plan.moves.forEach { (old, new) -> dao.move(old, new, SongKeys.id(new)) }
                if (plan.refs.isNotEmpty()) dao.putRefs(plan.refs)
                aliases = dao.aliases().associate { it.oldKey to it.newKey }
                if (plan.moves.isNotEmpty()) {
                    val legacy = plan.moves.keys.count { it.startsWith("id:") }
                    val unmatched = referenced.count { it !in lib.songByKey && it !in plan.moves }
                    DiagLog.i(TAG, "rematched ${plan.moves.size} saved songs ($legacy from pre-0.7 ids), $unmatched still unmatched")
                }
            }.onFailure { DiagLog.e(TAG, "reconcile failed", it) }
        }
    }

    /** The library song a saved key refers to now, following moves. */
    fun resolve(key: String, lib: Library = _library.value): Song? =
        lib.songByKey[key] ?: aliases[key]?.let { lib.songByKey[it] }
            ?: key.takeIf { it.startsWith("id:") }?.removePrefix("id:")?.toLongOrNull()?.let { lib.songByLegacyId[it] }

    private fun songsFor(ids: List<Long>): List<Song> {
        val lib = _library.value
        return ids.mapNotNull { lib.songById[it] }
    }

    private suspend fun remember(songs: List<Song>) {
        val now = System.currentTimeMillis()
        db.identity().putRefs(songs.map { SongRef(it.key, it.title, it.artist, it.album, it.durationMs, now) })
    }

    fun toggleFavorite(id: Long) {
        val song = _library.value.songById[id] ?: return
        scope.launch {
            if (song.key in favoriteKeys.value) db.favorites().remove(song.key) else {
                remember(listOf(song))
                db.favorites().add(Favorite(song.key, System.currentTimeMillis()))
            }
        }
    }

    fun playlistSongs(id: Long): Flow<List<Long>> =
        combine(db.playlists().songKeys(id), _library) { keys, lib -> keys.mapNotNull { resolve(it, lib)?.id } }

    fun playlist(id: Long) = db.playlists().byId(id)

    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long {
        val id = db.playlists().insert(Playlist(name = name, createdAt = System.currentTimeMillis()))
        val songs = songsFor(songIds)
        if (songs.isNotEmpty()) {
            remember(songs)
            db.playlists().add(id, songs.map { it.key })
        }
        return id
    }

    fun addToPlaylist(id: Long, songIds: List<Long>) = scope.launch {
        val songs = songsFor(songIds)
        remember(songs)
        db.playlists().add(id, songs.map { it.key })
    }

    fun removeFromPlaylist(id: Long, songId: Long) = scope.launch {
        val song = _library.value.songById[songId] ?: return@launch
        db.playlists().removeEntry(id, song.key)
    }
    fun renamePlaylist(id: Long, name: String) = scope.launch { db.playlists().rename(id, name) }
    fun deletePlaylist(id: Long) = scope.launch { db.playlists().delete(id) }

    companion object {
        const val TAG = "EchoLibrary"
    }

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
