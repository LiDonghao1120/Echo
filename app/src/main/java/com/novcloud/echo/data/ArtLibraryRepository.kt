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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.math.max

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
     * transparency or came from a lossless format, otherwise as JPEG. Alpha is kept as is.
     */
    fun save(context: Context, uri: Uri, lossless: Boolean): Saved? {
        val decoded = ArtistArtStore.decode(context, uri, MAX_SIDE, MAX_SIDE) ?: return null
        val bmp = ArtistArtStore.trim(decoded)
        if (bmp !== decoded) decoded.recycle()
        val png = lossless || hasTransparency(bmp)
        val file = File(dir(context), "a_${System.nanoTime().toString(36)}.${if (png) "png" else "jpg"}")
        val ok = runCatching {
            file.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it) }
        }.getOrDefault(false)
        val saved = Saved(file, bmp.width, bmp.height, ArtistArtStore.luma(bmp))
        bmp.recycle()
        if (!ok) file.delete()
        return saved.takeIf { ok }
    }

    /** Copies a file restored from a backup as it is (it was written by [save]). */
    fun adopt(context: Context, source: File): File? {
        val ext = source.extension.lowercase().ifEmpty { "png" }
        val file = File(dir(context), "a_${System.nanoTime().toString(36)}.$ext")
        return runCatching { source.copyTo(file, overwrite = true) }.getOrNull()
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
                        val saved = ArtLibraryStore.save(context, uri, lossless) ?: run { hashes.remove(hash); return@runCatching false }
                        val parsed = ArtLibraryNames.parse(name)
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
        val items = dao.allNow().filter { File(it.path).exists() }
        if (items.isEmpty()) return@withContext 0
        val posters = db.artistImages().allNow().filter { File(it.path).exists() }.mapTo(HashSet()) { Names.key(it.artist) }
        val logos = db.artistLogos().allNow().filter { File(it.path).exists() }.mapTo(HashSet()) { Names.key(it.artist) }
        val plan = ArtLibraryMatch.plan(lib.artists.map { it.name }, posters, logos, items, prefs.artDeclined.first())
        var n = 0
        for (a in plan) if (library.fillArt(a.artist, a.kind, File(a.asset.path))) n++
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
        }
    }

    /** Adds backup images whose files were extracted into [dir]; returns how many were added. */
    suspend fun restore(items: List<BackupAsset>, dir: File): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            var n = 0
            for (b in items) {
                val src = File(dir, b.file).takeIf { it.exists() } ?: continue
                val f = ArtLibraryStore.adopt(context, src) ?: continue
                val (w, h) = if (b.width > 0 && b.height > 0) b.width to b.height else ArtLibraryStore.size(f)
                val hash = b.hash.ifEmpty { f.inputStream().use(ArtLibraryStore::hash) }
                val now = System.currentTimeMillis()
                dao.insert(
                    ArtAsset(
                        name = Names.clean(b.name).ifEmpty { ArtLibraryNames.parse(b.file).name },
                        kind = if (b.kind == ArtKind.Poster.name) ArtKind.Poster.name else ArtKind.Logo.name,
                        path = f.absolutePath, width = w, height = h, luma = b.luma, hash = hash,
                        addedAt = b.addedAt.takeIf { it > 0 } ?: now, updatedAt = b.updatedAt.takeIf { it > 0 } ?: now,
                    ),
                )
                n++
            }
            n
        }
    }

    companion object {
        const val TAG = "EchoArtLibrary"
        private const val AUTO_STEP = "art_library_auto"
        private const val AUTO_OFF = "art_library_auto_off"
    }
}
