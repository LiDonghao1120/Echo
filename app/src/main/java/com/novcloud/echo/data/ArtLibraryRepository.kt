package com.novcloud.echo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.novcloud.echo.data.db.ArtAsset
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.diag.StartupSteps
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Copies of picked images in app storage, so the originals on the phone can be deleted. */
object ArtLibraryStore {
    const val MAX_SIDE = 2400
    private val losslessTypes = setOf("png", "webp", "gif", "bmp")

    class Saved(val file: File, val width: Int, val height: Int, val luma: Float)

    fun dir(context: Context) = File(context.filesDir, "artist_library").apply { mkdirs() }

    fun hash(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True for formats that hold logos without loss (and usually with transparency). */
    fun lossless(mime: String?, fileName: String): Boolean =
        mime?.substringAfter('/')?.lowercase()?.let { t -> losslessTypes.any { t.contains(it) } } == true ||
            fileName.substringAfterLast('.', "").lowercase() in losslessTypes

    /**
     * Decodes [uri] (longest side at most [MAX_SIDE]), trims transparent edges and writes it as PNG when it has any
     * transparency or came from a lossless format, otherwise as JPEG. A [logo] is cleaned up first
     * ([ArtistArtStore.cleanLogo]: faint matte, specks, flat white or black background); a poster keeps its alpha as is.
     */
    fun save(context: Context, uri: Uri, lossless: Boolean, logo: Boolean = false): Saved? {
        val decoded = ArtistArtStore.decode(context, uri, MAX_SIDE, MAX_SIDE) ?: return null
        val bmp = if (logo) ArtistArtStore.cleanLogo(decoded) else ArtistArtStore.trim(decoded)
        if (bmp !== decoded) decoded.recycle()
        return write(context, bmp, lossless).also { bmp.recycle() }
    }

    internal fun write(context: Context, bmp: Bitmap, lossless: Boolean): Saved? {
        val png = lossless || hasTransparency(bmp)
        val file = File(dir(context), "a_${System.nanoTime().toString(36)}.${if (png) "png" else "jpg"}")
        val ok = runCatching {
            file.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it) }
        }.getOrDefault(false)
        val saved = Saved(file, bmp.width, bmp.height, ArtistArtStore.luma(bmp))
        if (!ok) file.delete()
        return saved.takeIf { ok }
    }

    /** Copies a file restored from a backup as it is (it was written by [save]). */
    fun adopt(context: Context, source: File): File? {
        val ext = source.extension.lowercase().ifEmpty { "png" }
        val file = File(dir(context), "a_${System.nanoTime().toString(36)}.$ext")
        return runCatching { source.copyTo(file, overwrite = true) }.getOrNull()
    }

    /** Longest side images are decoded at for [fingerprint]. */
    const val PRINT_SIDE = 1024

    /**
     * [ArtFingerprint] of the image at [uri], or null when it cannot be read. A [logo] is fingerprinted the way it is
     * stored, after [LogoCleanup], so the original file and the cleaned library copy still come out the same.
     */
    fun fingerprint(context: Context, uri: Uri, logo: Boolean = false): ArtFingerprint? {
        val bmp = ArtistArtStore.decode(context, uri, PRINT_SIDE, PRINT_SIDE) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        if (logo) {
            val r = runCatching { LogoCleanup.clean(px, w, h) }.getOrNull()
            if (r != null) return ArtFingerprint.of(r.argb, r.width, r.height)
        }
        return ArtFingerprint.of(px, w, h)
    }

    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return max(0, o.outWidth) to max(0, o.outHeight)
    }

    fun hasTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (c in row) if ((c ushr 24) != 0xFF) return true
        }
        return false
    }
}

data class LibraryAddReport(val added: Int, val duplicates: Int, val failed: Int)

/** What happened to an image set on an artist page when it was offered to the library. */
enum class Mirror { Added, Duplicate, Off, Failed }

/**
 * Fingerprints of library files by file name, kept in one text file so a library of hundreds of images is decoded
 * once, not on every comparison. Lines are appended; stale ones are dropped when the file is rewritten.
 */
internal class ArtPrints(private val file: File) {
    private var map: HashMap<String, String>? = null

    private fun load(): HashMap<String, String> = map ?: HashMap<String, String>().also { m ->
        runCatching {
            if (file.exists()) file.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        map = m
    }

    @Synchronized fun get(name: String): ArtFingerprint? = ArtFingerprint.parse(load()[name])

    @Synchronized fun put(name: String, print: ArtFingerprint) {
        val v = print.encode()
        if (load().put(name, v) == v) return
        runCatching { file.appendText("$name\t$v\n") }
    }

    /** Forgets files no longer in the library, rewriting the cache when it holds more stale lines than live ones. */
    @Synchronized fun retain(names: Set<String>) {
        val m = load()
        val stale = m.keys.filter { it !in names }
        if (stale.isEmpty()) return
        stale.forEach(m::remove)
        if (stale.size >= m.size) runCatching { file.writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" }) }
    }

    @Synchronized fun clear() {
        map = HashMap()
        file.delete()
    }
}

/**
 * The artist artwork library: logos and posters the user keeps for artists, picked from the phone and copied into
 * app storage. Images are put on artists as their own copies (see [LibraryRepository.setArtistArt]), so deleting a
 * library image never touches an artist page. With auto-apply on, an artist without a logo or poster gets the
 * newest library image with its name whenever the library or the artist list changes.
 */
@OptIn(FlowPreview::class)
class ArtLibraryRepository(
    private val context: Context,
    private val db: EchoDatabase,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val dao = db.artAssets()
    private val lock = Mutex()
    private val steps = StartupSteps.of(context)
    private val guard = context.getSharedPreferences("startup", Context.MODE_PRIVATE)

    val assets: StateFlow<List<ArtAsset>> = dao.all()
        .map { l -> l.filter { File(it.path).exists() } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val autoApply: StateFlow<Boolean> = prefs.artAutoApply.stateIn(scope, SharingStarted.Eagerly, true)

    /** Logos and posters set on an artist page from the phone are also kept in the library. */
    val syncFromArtist: StateFlow<Boolean> = prefs.artSyncFromArtist.stateIn(scope, SharingStarted.Eagerly, true)

    private val _mirrored = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Artists whose newly set logo or poster was just added to the library, for a short confirmation. */
    val mirrored: SharedFlow<String> = _mirrored.asSharedFlow()

    private val prints = ArtPrints(File(context.filesDir, "art_prints.txt"))

    private val _adding = MutableStateFlow<ScanProgress?>(null)
    val adding: StateFlow<ScanProgress?> = _adding.asStateFlow()

    init {
        scope.launch {
            combine(
                library.library.map { lib -> if (lib.loaded) lib.artists.map { it.name } else null },
                assets, autoApply, library.artistImages, library.artistLogos,
            ) { artists, _, on, _, _ -> artists != null && on }
                .debounce(700)
                .conflate()
                .collect { ready -> if (ready) runAuto() }
        }
        library.onUserArt = { artist, kind, source, single ->
            if (mirror(artist, kind, source) == Mirror.Added && single) _mirrored.tryEmit(artist)
        }
        scope.launch {
            library.library.first { it.loaded }
            steps.once(NAME_REPAIR_STEP) { repairNames() }
        }
        scope.launch {
            library.library.first { it.loaded }
            delay(LOGO_CLEANUP_DELAY_MS)
            runLogoCleanup()
            delay(BACKFILL_DELAY_MS - LOGO_CLEANUP_DELAY_MS)
            syncFromArtist.first { it }
            runBackfill()
        }
    }

    /**
     * One-time pass (v0.11.3) over logos already in the library: [LogoCleanup] takes off faint mattes, specks and
     * flat backgrounds that showed as a box behind the logo. Guarded like startup work; each file on its own.
     */
    internal suspend fun runLogoCleanup() {
        steps.once(LOGO_CLEANUP_STEP) { cleanLogosNow() }
    }

    /** Re-cleans every library logo; a changed one gets a new file (so no image cache shows the old one). Returns how many. */
    internal suspend fun cleanLogosNow(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val started = System.currentTimeMillis()
            var cleaned = 0
            var failed = 0
            val logos = dao.allNow().filter { ArtLibraryMatch.kindOf(it) == ArtKind.Logo && File(it.path).exists() }
            for (a in logos) {
                val ok = runCatching {
                    val old = File(a.path)
                    val bmp = ArtistArtStore.decodeFile(old) ?: return@runCatching false
                    val out = ArtistArtStore.cleanLogo(bmp)
                    if (out === bmp) { bmp.recycle(); return@runCatching true }
                    bmp.recycle()
                    val saved = ArtLibraryStore.write(context, out, lossless = true)
                    out.recycle()
                    saved ?: return@runCatching false
                    dao.replaceFile(a.id, saved.file.absolutePath, saved.width, saved.height, saved.luma)
                    old.delete()
                    cleaned++
                    true
                }.onFailure { DiagLog.w(TAG, "could not clean up a library logo", it) }.getOrDefault(false)
                if (!ok) failed++
            }
            if (cleaned > 0) prints.retain(dao.allNow().mapTo(HashSet()) { File(it.path).name })
            DiagLog.i(TAG, "logo cleanup: ${logos.size} library logos, cleaned $cleaned, unreadable $failed (${System.currentTimeMillis() - started} ms)")
            cleaned
        }
    }

    private fun displayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').ifEmpty { "image" }
    }

    /** Copies each picked image into the library; images whose file content is already there are skipped. */
    suspend fun add(uris: List<Uri>): LibraryAddReport = withContext(Dispatchers.IO) {
        lock.withLock {
            var added = 0
            var dup = 0
            var failed = 0
            val hashes = dao.allNow().mapTo(HashSet()) { it.hash }
            _adding.value = ScanProgress(0, uris.size)
            try {
                uris.forEachIndexed { i, uri ->
                    val r: Boolean? = runCatching<Boolean?> {
                        val name = displayName(uri)
                        val hash = context.contentResolver.openInputStream(uri)?.use(ArtLibraryStore::hash) ?: return@runCatching false
                        if (!hashes.add(hash)) return@runCatching null
                        val lossless = ArtLibraryStore.lossless(runCatching { context.contentResolver.getType(uri) }.getOrNull(), name)
                        val parsed = ArtLibraryNames.parse(name)
                        val logo = parsed.kind == ArtKind.Logo
                        val saved = ArtLibraryStore.save(context, uri, lossless, logo) ?: run { hashes.remove(hash); return@runCatching false }
                        ArtLibraryStore.fingerprint(context, Uri.fromFile(saved.file), logo)?.let { prints.put(saved.file.name, it) }
                        val now = System.currentTimeMillis()
                        dao.insert(
                            ArtAsset(
                                name = parsed.name, kind = parsed.kind.name, path = saved.file.absolutePath, width = saved.width, height = saved.height,
                                luma = saved.luma, hash = hash, addedAt = now, updatedAt = now,
                            ),
                        )
                        true
                    }.onFailure { DiagLog.w(TAG, "could not add an image to the artwork library", it) }.getOrDefault(false)
                    when (r) {
                        true -> added++
                        null -> dup++
                        false -> failed++
                    }
                    _adding.value = ScanProgress(i + 1, uris.size)
                }
            } finally {
                _adding.value = null
            }
            DiagLog.i(TAG, "artwork library: added $added, already there $dup, unreadable $failed")
            LibraryAddReport(added, dup, failed)
        }
    }

    suspend fun edit(id: Long, name: String, kind: ArtKind) = withContext(Dispatchers.IO) {
        val clean = Names.clean(name).ifEmpty { return@withContext }
        dao.edit(id, clean, kind.name, System.currentTimeMillis())
    }

    /** Removes the image and its file. Artists that use it keep their own copy. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val a = dao.get(id) ?: return@withLock
            dao.delete(id)
            File(a.path).delete()
        }
    }

    suspend fun setSyncFromArtist(on: Boolean) = prefs.setArtSyncFromArtist(on)

    /** Fingerprint of a library image, from the cache or worked out from its file once. */
    private fun printOf(a: ArtAsset): ArtFingerprint? {
        val f = File(a.path)
        prints.get(f.name)?.let { return it }
        val p = runCatching { ArtLibraryStore.fingerprint(context, Uri.fromFile(f), ArtLibraryMatch.kindOf(a) == ArtKind.Logo) }.getOrNull() ?: return null
        prints.put(f.name, p)
        return p
    }

    /**
     * The library already holds this image when a file has the same bytes (SHA-256 of what was picked) or looks the
     * same once decoded ([ArtFingerprint]): the library keeps re-encoded, trimmed copies, so bytes alone miss those.
     */
    internal fun findDuplicate(items: List<ArtAsset>, hash: String?, print: ArtFingerprint?): ArtAsset? {
        if (hash != null) items.firstOrNull { it.hash == hash }?.let { return it }
        if (print == null) return null
        return items.firstOrNull { printOf(it)?.same(print) == true }
    }

    /**
     * Keeps a logo or poster just set on [artist]'s page in the library too, named after the artist, unless the
     * library already has it or syncing is off. Only adds: what is on the artist stays as it is, and the artist
     * already has art of this kind, so auto-apply has nothing to put back.
     */
    suspend fun mirror(artist: String, kind: ArtKind, source: Uri): Mirror = withContext(Dispatchers.IO) {
        if (!prefs.artSyncFromArtist.first()) return@withContext Mirror.Off
        val r = runCatching { lock.withLock { mirrorLocked(artist, kind, source) } }
            .onFailure { DiagLog.w(TAG, "could not keep an artist image in the library", it) }
            .getOrDefault(Mirror.Failed)
        if (r == Mirror.Added) DiagLog.i(TAG, "kept a ${kind.name.lowercase()} set on an artist page in the library")
        r
    }

    private suspend fun mirrorLocked(artist: String, kind: ArtKind, source: Uri): Mirror {
        val name = Names.clean(artist).ifEmpty { return Mirror.Failed }
        val hash = context.contentResolver.openInputStream(source)?.use(ArtLibraryStore::hash) ?: return Mirror.Failed
        val items = dao.allNow().filter { File(it.path).exists() }
        if (items.any { it.hash == hash }) return Mirror.Duplicate
        val logo = kind == ArtKind.Logo
        val print = ArtLibraryStore.fingerprint(context, source, logo) ?: return Mirror.Failed
        if (findDuplicate(items, null, print) != null) return Mirror.Duplicate
        val type = runCatching { context.contentResolver.getType(source) }.getOrNull()
        val saved = ArtLibraryStore.save(context, source, ArtLibraryStore.lossless(type, source.lastPathSegment.orEmpty()), logo) ?: return Mirror.Failed
        prints.put(saved.file.name, print)
        val now = System.currentTimeMillis()
        dao.insert(
            ArtAsset(
                name = name, kind = kind.name, path = saved.file.absolutePath, width = saved.width, height = saved.height,
                luma = saved.luma, hash = hash, addedAt = now, updatedAt = now,
            ),
        )
        return Mirror.Added
    }

    /** One-time pass that keeps logos and posters set before syncing existed in the library; guarded like startup work. */
    internal suspend fun runBackfill() {
        steps.once(BACKFILL_STEP) { backfillNow() }
    }

    /** Offers every artist's current logo and poster to the library; returns how many were added. */
    internal suspend fun backfillNow(): Int = withContext(Dispatchers.IO) {
        if (!prefs.artSyncFromArtist.first()) return@withContext 0
        val started = System.currentTimeMillis()
        val lib = library.library.value
        fun shown(artist: String) = lib.artist(artist)?.name ?: Names.clean(artist)
        val logos = ArtistArtRows.latestByKey(db.artistLogos().allNow().filter { File(it.path).exists() }, { it.artist }, { it.updatedAt }).values
            .sortedBy { it.updatedAt }.map { Triple(shown(it.artist), ArtKind.Logo, File(it.path)) }
        val posters = ArtistArtRows.latestByKey(db.artistImages().allNow().filter { File(it.path).exists() }, { it.artist }, { it.updatedAt }).values
            .sortedBy { it.updatedAt }.map { Triple(shown(it.artist), ArtKind.Poster, File(it.path)) }
        var added = 0
        var dup = 0
        var failed = 0
        for ((artist, kind, file) in logos + posters) {
            when (mirror(artist, kind, Uri.fromFile(file))) {
                Mirror.Added -> added++
                Mirror.Duplicate -> dup++
                Mirror.Off -> break
                Mirror.Failed -> failed++
            }
        }
        prints.retain(dao.allNow().mapTo(HashSet()) { File(it.path).name })
        DiagLog.i(
            TAG,
            "backfill: ${logos.size} artist logos, ${posters.size} posters; added $added, already in library $dup, " +
                "unreadable $failed (${System.currentTimeMillis() - started} ms)",
        )
        added
    }

    /**
     * One-time pass (v0.13.2) after charset misreadings in tag text were fixed ("MAR虯 VELL" is "MARÍA VELL"): play history
     * is rewritten to the repaired names; a logo or poster stored under a misread name that provably came from
     * auto-apply (a library image whose name matched that misread spelling, added before it, and looking the same) is
     * removed so auto-apply runs again on the correct name; the rest move onto the correct name, one per artist.
     */
    internal suspend fun repairNames() {
        val started = System.currentTimeMillis()
        val events = library.repairEventText()
        var revoked = 0
        val assets = withContext(Dispatchers.IO) { dao.allNow().filter { File(it.path).exists() } }
        for (kind in ArtKind.entries) {
            val rows = withContext(Dispatchers.IO) {
                if (kind == ArtKind.Logo) db.artistLogos().allNow().map { Triple(it.artist, it.path, it.updatedAt to it.auto) }
                else db.artistImages().allNow().map { Triple(it.artist, it.path, it.updatedAt to it.auto) }
            }
            for ((artist, path, meta) in rows) {
                val (updatedAt, auto) = meta
                if (auto.isNotEmpty() || !ArtistArtRows.misread(artist)) continue
                if (provablyAuto(kind, artist, File(path), updatedAt, assets)) {
                    library.dropArtRow(kind, artist)
                    revoked++
                }
            }
        }
        val (dropped, renamed) = library.settleArtRows()
        DiagLog.i(
            TAG,
            "name repair: $events misread history values rewritten, $revoked auto-applied images on misread names removed, " +
                "$renamed images moved to the repaired name, $dropped duplicates dropped (${System.currentTimeMillis() - started} ms)",
        )
    }

    /** Auto-apply before v0.13.2 matched on [ArtLibraryMatch.legacyKey]; the image must also be that library image. */
    private fun provablyAuto(kind: ArtKind, artist: String, file: File, updatedAt: Long, assets: List<ArtAsset>): Boolean {
        val k = ArtLibraryMatch.legacyKey(artist)
        val candidates = assets.filter { ArtLibraryMatch.kindOf(it) == kind && it.addedAt <= updatedAt && ArtLibraryMatch.legacyKey(it.name) == k }
        if (candidates.isEmpty() || !file.exists()) return false
        val print = runCatching { ArtLibraryStore.fingerprint(context, Uri.fromFile(file), kind == ArtKind.Logo) }.getOrNull() ?: return false
        return candidates.any { printOf(it)?.same(print) == true }
    }

    /** Puts a copy of [asset] on [artist] as its [kind]. */
    fun use(asset: ArtAsset, artist: String, kind: ArtKind) = library.setArtistArt(artist, kind, File(asset.path))

    /**
     * The user removed [artist]'s [kind]: remember the library images with that name so auto-apply does not put one
     * straight back (images added later still can), then remove it.
     */
    suspend fun clearArt(artist: String, kind: ArtKind) {
        val k = ArtLibraryMatch.key(artist)
        val tokens = assets.value.filter { ArtLibraryMatch.key(it.name) == k && ArtLibraryMatch.kindOf(it) == kind }
            .map { ArtLibraryMatch.declined(kind, artist, it.hash) }
        if (tokens.isNotEmpty()) prefs.addArtDeclined(tokens)
        if (kind == ArtKind.Logo) library.clearArtistLogo(artist).join() else library.clearArtistImage(artist).join()
    }

    suspend fun setAutoApply(on: Boolean) {
        if (on) guard.edit().remove(AUTO_OFF).apply()
        prefs.setArtAutoApply(on)
    }

    private suspend fun runAuto() {
        if (guard.getBoolean(AUTO_OFF, false)) return
        steps.every(AUTO_STEP, recover = {
            guard.edit().putBoolean(AUTO_OFF, true).commit()
            DiagLog.w(TAG, "auto-apply did not finish last launch; paused until it is switched on again")
        }) {
            if (!guard.getBoolean(AUTO_OFF, false)) autoApplyNow()
        }
    }

    /** One auto-apply pass; returns how many images were put on artists. */
    internal suspend fun autoApplyNow(): Int = withContext(Dispatchers.IO) {
        val lib = library.library.value
        if (!lib.loaded || !prefs.artAutoApply.first()) return@withContext 0
        library.dropStaleAutoArt()
        val items = dao.allNow().filter { File(it.path).exists() }
        if (items.isEmpty()) return@withContext 0
        val posters = db.artistImages().allNow().filter { File(it.path).exists() }.mapTo(HashSet()) { Names.key(it.artist) }
        val logos = db.artistLogos().allNow().filter { File(it.path).exists() }.mapTo(HashSet()) { Names.key(it.artist) }
        val plan = ArtLibraryMatch.plan(lib.artists.map { it.name }, posters, logos, items, prefs.artDeclined.first())
        var n = 0
        for (a in plan) if (library.fillArt(a.artist, a.kind, File(a.asset.path), a.key)) n++
        if (n > 0) DiagLog.i(TAG, "auto-applied $n of ${plan.size} artwork library images to artists without their own")
        n
    }

    /** For a backup: every image with the file it is stored in. */
    suspend fun backupItems(): List<Pair<BackupAsset, File>> = withContext(Dispatchers.IO) {
        dao.allNow().filter { File(it.path).exists() }.mapIndexed { i, a ->
            val f = File(a.path)
            BackupAsset(a.name, a.kind, "lib_$i.${f.extension.ifEmpty { "png" }}", a.width, a.height, a.luma, a.hash, a.addedAt, a.updatedAt) to f
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            dao.allNow().forEach { File(it.path).delete() }
            dao.clear()
            prints.clear()
        }
    }

    /** Adds backup images whose files were extracted into [dir]; returns how many were added. */
    suspend fun restore(items: List<BackupAsset>, dir: File): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            var n = 0
            for (b in items) {
                val src = File(dir, b.file).takeIf { it.exists() } ?: continue
                val logo = b.kind != ArtKind.Poster.name
                val cleaned = if (logo) runCatching { cleanedCopy(src) }.getOrNull() else null
                val f = cleaned?.file ?: ArtLibraryStore.adopt(context, src) ?: continue
                val (w, h) = when {
                    cleaned != null -> cleaned.width to cleaned.height
                    b.width > 0 && b.height > 0 -> b.width to b.height
                    else -> ArtLibraryStore.size(f)
                }
                val luma = cleaned?.luma ?: b.luma
                val hash = b.hash.ifEmpty { f.inputStream().use(ArtLibraryStore::hash) }
                val now = System.currentTimeMillis()
                dao.insert(
                    ArtAsset(
                        name = Names.clean(b.name).ifEmpty { ArtLibraryNames.parse(b.file).name },
                        kind = if (b.kind == ArtKind.Poster.name) ArtKind.Poster.name else ArtKind.Logo.name,
                        path = f.absolutePath, width = w, height = h, luma = luma, hash = hash,
                        addedAt = b.addedAt.takeIf { it > 0 } ?: now, updatedAt = b.updatedAt.takeIf { it > 0 } ?: now,
                    ),
                )
                n++
            }
            n
        }
    }

    /** A cleaned copy of a backed-up logo from before v0.11.3, or null when it was already clean. */
    private fun cleanedCopy(src: File): ArtLibraryStore.Saved? {
        val bmp = ArtistArtStore.decodeFile(src) ?: return null
        val out = ArtistArtStore.cleanLogo(bmp)
        if (out === bmp) { bmp.recycle(); return null }
        bmp.recycle()
        return ArtLibraryStore.write(context, out, lossless = true).also { out.recycle() }
    }

    companion object {
        const val TAG = "EchoArtLibrary"
        private const val AUTO_STEP = "art_library_auto"
        private const val AUTO_OFF = "art_library_auto_off"
        private const val BACKFILL_STEP = "art_library_backfill_v1"
        private const val BACKFILL_DELAY_MS = 6_000L
        private const val LOGO_CLEANUP_STEP = "art_library_logo_cleanup_v1"
        private const val NAME_REPAIR_STEP = "repair_misread_names_v1"
        private const val LOGO_CLEANUP_DELAY_MS = 3_000L
    }
}
