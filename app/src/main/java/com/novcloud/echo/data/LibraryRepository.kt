package com.novcloud.echo.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.novcloud.echo.data.db.ArtistImage
import com.novcloud.echo.data.db.ArtistLogo
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.data.db.Favorite
import com.novcloud.echo.data.db.Playlist
import com.novcloud.echo.data.db.SongRef
import com.novcloud.echo.diag.DiagLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val artLock = Mutex()
    @Volatile private var aliases: Map<String, String> = emptyMap()
    private var observerRegistered = false
    private var pending: Job? = null
    private val autoScan = AutoScanPolicy()
    private val autoPrefs by lazy { context.getSharedPreferences("autoscan", Context.MODE_PRIVATE) }
    @Volatile private var foreground = false
    @Volatile private var dirtyWhileAway = false
    @Volatile private var lastTreeCheck = 0L
    private val _changes = MutableSharedFlow<LibraryDiff>(extraBufferCapacity = 8)
    /** What each scan that changed the library added, removed or found edited (for caches keyed by song). */
    val changes: SharedFlow<LibraryDiff> = _changes.asSharedFlow()

    private val favoriteKeys = db.favorites().keys().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val favoriteOrder: StateFlow<List<Long>> = combine(favoriteKeys, _library) { keys, lib -> keys.mapNotNull { lib.songByKey[it]?.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val favorites: StateFlow<Set<Long>> = favoriteOrder.map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    val playlists = db.playlists().all().stateIn(scope, SharingStarted.Eagerly, emptyList())
    /** Custom posters by [Names.key] of the artist; look up with [forName]. */
    val artistImages: StateFlow<Map<String, String>> = db.artistImages().all()
        .map { l -> ArtistArtRows.latestByKey(l.filter { File(it.path).exists() }, { it.artist }, { it.updatedAt }).mapValues { it.value.path } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Name logos by [Names.key] of the artist; look up with [forName]. */
    val artistLogos: StateFlow<Map<String, ArtistLogoInfo>> = db.artistLogos().all()
        .map { l -> ArtistArtRows.latestByKey(l.filter { File(it.path).exists() }, { it.artist }, { it.updatedAt }).mapValues { ArtistLogoInfo(File(it.value.path), it.value.aspect, it.value.luma) } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    internal fun publish(lib: Library) {
        _library.value = lib
    }

    private val snapshots = SnapshotStore(context.filesDir)
    private var treeStamps: Map<String, Long> = emptyMap()
    private val probed = HashMap<String, Long>()
    private val steps = com.novcloud.echo.diag.StartupSteps.of(context)
    private val restored: Deferred<Unit> = scope.async(Dispatchers.IO) {
        steps.once("merge_artist_art_v1") { mergeArtistArt() }
        steps.every("restore_snapshot", recover = { snapshots.discard() }) { restoreSnapshot() }
    }

    private suspend fun restoreSnapshot() {
        val snap = runCatching { snapshots.load() }.onFailure { DiagLog.e(TAG, "snapshot unreadable", it) }.getOrNull() ?: return
        val songs = snap.songs.map { it.cleaned() }
        val tree = snap.treeSongs.map { it.cleaned() }
        reportVariants(snap.songs)
        val lib = Library.build(songs)
        treeSongs = tree
        treeStamps = snap.trees
        probed.putAll(snap.probed)
        if (!_library.value.loaded) {
            _library.value = lib
            reconcile(lib)
            if (songs.indices.any { songs[it] !== snap.songs[it] } || tree.indices.any { tree[it] !== snap.treeSongs[it] }) {
                DiagLog.i(TAG, "saved library had untidy tag text; normalized in place")
                runCatching { saveSnapshot() }.onFailure { DiagLog.e(TAG, "snapshot save failed", it) }
            }
        }
    }
    private val reportedVariants = HashSet<String>()

    /** Logs, once per process, names whose spellings differ but fold to one artist, album or genre (see [NameVariants]). */
    private fun reportVariants(raw: List<Song>) {
        val found = runCatching { NameVariants.find(raw) }.getOrDefault(emptyList())
        synchronized(reportedVariants) {
            found.filter { reportedVariants.add(it.kind + "|" + it.key + "|" + it.variants.joinToString("\u0001")) }
        }.take(50).forEach { DiagLog.i(TAG, NameVariants.describe(it)) }
    }

    /** One poster and one logo per artist: rows saved under spellings that now fold together keep the latest. */
    internal suspend fun mergeArtistArt() {
        val images = db.artistImages()
        val imageRows = images.allNow()
        val dropImages = ArtistArtRows.superseded(imageRows, { it.artist }, { it.updatedAt })
        dropImages.forEach { r ->
            images.remove(r.artist)
            if (imageRows.none { it !in dropImages && it.path == r.path }) File(r.path).delete()
        }
        val logos = db.artistLogos()
        val logoRows = logos.allNow()
        val dropLogos = ArtistArtRows.superseded(logoRows, { it.artist }, { it.updatedAt })
        dropLogos.forEach { r ->
            logos.remove(r.artist)
            if (logoRows.none { it !in dropLogos && it.path == r.path }) File(r.path).delete()
        }
        if (dropImages.isNotEmpty() || dropLogos.isNotEmpty()) {
            DiagLog.i(TAG, "merged artist art across spellings: dropped ${dropImages.size} older posters, ${dropLogos.size} older logos")
        }
    }
    private val started = AtomicBoolean(false)
    private var missingJob: Job? = null

    enum class Walk(val mode: AutoScanPolicy.Mode) {
        Never(AutoScanPolicy.Mode.Never),
        IfChanged(AutoScanPolicy.Mode.IfChanged),
        Always(AutoScanPolicy.Mode.Always),
    }

    /** Startup scan, once per process. Needs the audio permission; returns false without it. */
    fun start(): Boolean {
        if (!hasAudioPermission()) return false
        if (started.compareAndSet(false, true)) {
            lastTreeCheck = System.currentTimeMillis()
            scope.launch { rescan(Walk.IfChanged) }
        }
        return true
    }

    /**
     * The app came to the foreground: run a MediaStore scan held back while it was away, and check SAF folders for
     * new or edited files when the last check is old enough (see [AutoScanPolicy]). Never blocks the caller.
     */
    fun onForeground() {
        foreground = true
        if (!started.get()) return
        if (dirtyWhileAway) {
            dirtyWhileAway = false
            DiagLog.i(TAG, "auto-scan: media store changed while in the background")
            scheduleStoreScan()
        }
        val now = System.currentTimeMillis()
        if (!autoScan.checkDue(now, lastTreeCheck)) return
        lastTreeCheck = now
        scope.launch {
            if (prefs.scanTrees.first().isEmpty()) return@launch
            rescan(Walk.IfChanged)
        }
    }

    fun onBackground() {
        foreground = false
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
                    val now = System.currentTimeMillis()
                    val lastDeep = autoPrefs.getLong(KEY_DEEP_WALK, 0)
                    if (stamps != treeStamps || autoScan.walkTrees(walk.mode, false, now, lastDeep)) {
                        val t1 = SystemClock.elapsedRealtime()
                        runCatching { treeScanner.scan(trees, rules, store.knownPaths) { _progress.value = it } }
                            .onSuccess {
                                treeSongs = it
                                treeStamps = stamps
                                autoPrefs.edit().putLong(KEY_DEEP_WALK, now).apply()
                            }
                            .onFailure { DiagLog.e(TAG, "folder walk failed", it) }
                        tTrees = SystemClock.elapsedRealtime() - t1
                    }
                }
                val extra = treeSongs.filter { it.durationMs >= min && rules.accepts(it.folder, true) && it.path.lowercase() !in store.knownPaths }
                val raw = genres.apply(SongKeys.unique(Dedupe.merge(store.songs, extra)))
                reportVariants(raw)
                raw.map { it.cleaned() }
            } ?: return
            val changed = LibrarySnapshot.changed(_library.value, songs)
            if (changed && _library.value.loaded) {
                val diff = withContext(Dispatchers.Default) { LibraryDiff.of(_library.value.songs, songs) }
                if (!diff.isEmpty) {
                    DiagLog.i(TAG, "library $walk: ${diff.describe()}")
                    _changes.tryEmit(diff)
                }
            }
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

    init {
        scope.launch(Dispatchers.IO) {
            restored.await()
            delay(LOGO_CLEANUP_DELAY_MS)
            steps.once(LOGO_CLEANUP_STEP) { cleanStoredLogos() }
        }
    }

    /**
     * One-time pass (v0.11.3) over artist logos saved before [ArtistArtStore.cleanLogo] existed: a faint matte across
     * the canvas showed as a box behind the logo on the artist page. A changed logo is written to a new file (so no
     * image cache keeps the old one) with its aspect and brightness worked out again; its date stays. Returns how many.
     */
    internal suspend fun cleanStoredLogos(): Int = withContext(Dispatchers.IO) {
        artLock.withLock {
            val started = System.currentTimeMillis()
            val rows = db.artistLogos().allNow()
            val replaced = HashMap<String, ArtistLogoInfo?>()
            var cleaned = 0
            var failed = 0
            for (row in rows) {
                val old = File(row.path)
                if (!old.exists()) continue
                val info = if (replaced.containsKey(row.path)) replaced[row.path] else runCatching {
                    val bmp = ArtistArtStore.decodeFile(old) ?: return@runCatching null
                    val out = ArtistArtStore.cleanLogo(bmp)
                    if (out === bmp) { bmp.recycle(); return@runCatching null }
                    bmp.recycle()
                    ArtistArtStore.writeLogo(context, row.artist, out).also { out.recycle() } ?: run { failed++; null }
                }.onFailure { failed++; DiagLog.w(TAG, "could not clean up an artist logo", it) }.getOrNull().also { replaced[row.path] = it }
                if (info == null) continue
                db.artistLogos().put(row.copy(path = info.file.absolutePath, aspect = info.aspect, luma = info.luma))
                cleaned++
            }
            replaced.forEach { (path, info) -> if (info != null) File(path).delete() }
            DiagLog.i(TAG, "logo cleanup: ${rows.size} artist logos, cleaned $cleaned, unreadable $failed (${System.currentTimeMillis() - started} ms)")
            cleaned
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

    /** MediaStore changes: debounced while in the foreground, deferred to the next foreground while away. */
    private fun registerObserver() {
        if (observerRegistered) return
        observerRegistered = true
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!foreground) {
                    dirtyWhileAway = true
                    return
                }
                scheduleStoreScan()
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
    }

    private fun scheduleStoreScan() {
        val wait = autoScan.onChange(SystemClock.elapsedRealtime())
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            autoScan.onScan()
            rescan(Walk.Never)
        }
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
        private const val LOGO_CLEANUP_STEP = "artist_logo_cleanup_v1"
        private const val LOGO_CLEANUP_DELAY_MS = 2_500L
        private const val KEY_DEEP_WALK = "deep_walk_at"
    }

    /**
     * Called after a logo or poster from the phone (not from the artwork library) was put on an artist; [single] is
     * false for folder imports. The artwork library uses it to keep a copy.
     */
    @Volatile var onUserArt: (suspend (artist: String, kind: ArtKind, source: Uri, single: Boolean) -> Unit)? = null

    private suspend fun userArt(artist: String, kind: ArtKind, source: Uri, single: Boolean) {
        val hook = onUserArt ?: return
        runCatching { hook(artist, kind, source, single) }.onFailure { DiagLog.w(TAG, "artist image hook failed", it) }
    }

    /** Puts a picked image on [artist] as its [kind]; returns false when it could not be read. */
    suspend fun setArtFromPhone(artist: String, kind: ArtKind, source: Uri): Boolean {
        val ok = withArt(artist) { if (kind == ArtKind.Logo) putLogo(artist, source) else putPoster(artist, source) }
        if (ok) userArt(artist, kind, source, single = true)
        return ok
    }

    fun setArtistImage(artist: String, source: Uri) = scope.launch { setArtFromPhone(artist, ArtKind.Poster, source) }

    fun clearArtistImage(artist: String) = scope.launch(Dispatchers.IO) {
        val key = Names.key(artist)
        db.artistImages().allNow().filter { Names.key(it.artist) == key }.forEach {
            File(it.path).delete()
            db.artistImages().remove(it.artist)
        }
    }

    fun setArtistLogo(artist: String, source: Uri) = scope.launch { setArtFromPhone(artist, ArtKind.Logo, source) }

    fun clearArtistLogo(artist: String) = scope.launch(Dispatchers.IO) {
        val key = Names.key(artist)
        db.artistLogos().allNow().filter { Names.key(it.artist) == key }.forEach {
            File(it.path).delete()
            db.artistLogos().remove(it.artist)
        }
    }

    /** Puts a copy of [file] (an artwork library image) on [artist]; the library image can be deleted afterwards. */
    fun setArtistArt(artist: String, kind: ArtKind, file: File) = scope.launch {
        withArt(artist) { if (kind == ArtKind.Logo) putLogo(artist, Uri.fromFile(file)) else putPoster(artist, Uri.fromFile(file)) }
    }

    private suspend fun hasArt(artist: String, kind: ArtKind): Boolean {
        val key = Names.key(artist)
        return if (kind == ArtKind.Logo) db.artistLogos().allNow().any { Names.key(it.artist) == key && File(it.path).exists() }
        else db.artistImages().allNow().any { Names.key(it.artist) == key && File(it.path).exists() }
    }

    /** Like [setArtistArt] but only while [artist] has no [kind] of its own, checked again after the copy is made. */
    internal suspend fun fillArt(artist: String, kind: ArtKind, file: File): Boolean = withContext(Dispatchers.IO) {
        artLock.withLock {
            if (hasArt(artist, kind)) return@withLock false
            val source = Uri.fromFile(file)
            when (kind) {
                ArtKind.Logo -> {
                    val info = runCatching { ArtistArtStore.saveLogo(context, artist, source) }.getOrNull() ?: return@withLock false
                    if (hasArt(artist, kind)) { info.file.delete(); return@withLock false }
                    db.artistLogos().put(ArtistLogo(artist, info.file.absolutePath, info.aspect, info.luma, System.currentTimeMillis()))
                }
                ArtKind.Poster -> {
                    val out = runCatching { ArtistArtStore.savePoster(context, artist, source) }.getOrNull() ?: return@withLock false
                    if (hasArt(artist, kind)) { out.delete(); return@withLock false }
                    db.artistImages().put(ArtistImage(artist, out.absolutePath, System.currentTimeMillis()))
                }
            }
            true
        }
    }

    private suspend fun withArt(artist: String, block: suspend () -> Boolean): Boolean {
        _artBusy.value = _artBusy.value + artist
        try {
            val ok = withContext(Dispatchers.IO) { artLock.withLock { runCatching { block() }.getOrDefault(false) } }
            if (!ok) _artFailed.tryEmit(artist)
            return ok
        } finally {
            _artBusy.value = _artBusy.value - artist
        }
    }

    private suspend fun putPoster(artist: String, source: Uri): Boolean {
        val file = runCatching { ArtistArtStore.savePoster(context, artist, source) }.getOrNull() ?: return false
        val key = Names.key(artist)
        val old = db.artistImages().allNow().filter { Names.key(it.artist) == key }
        old.filter { it.artist != artist }.forEach { db.artistImages().remove(it.artist) }
        db.artistImages().put(ArtistImage(artist, file.absolutePath, System.currentTimeMillis()))
        old.filter { it.path != file.absolutePath }.forEach { File(it.path).delete() }
        return true
    }

    private suspend fun putLogo(artist: String, source: Uri): Boolean {
        val info = runCatching { ArtistArtStore.saveLogo(context, artist, source) }.getOrNull() ?: return false
        val key = Names.key(artist)
        val old = db.artistLogos().allNow().filter { Names.key(it.artist) == key }
        old.filter { it.artist != artist }.forEach { db.artistLogos().remove(it.artist) }
        db.artistLogos().put(ArtistLogo(artist, info.file.absolutePath, info.aspect, info.luma, System.currentTimeMillis()))
        old.filter { it.path != info.file.absolutePath }.forEach { File(it.path).delete() }
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
            val ok = when (m.kind) {
                ArtKind.Logo -> putLogo(m.artist, f.uri).also { if (it) logos++ }
                ArtKind.Poster -> putPoster(m.artist, f.uri).also { if (it) posters++ }
            }
            if (ok) userArt(m.artist, m.kind, f.uri, single = false)
        }
        ImportReport(logos, posters, unmatched)
    }
}
