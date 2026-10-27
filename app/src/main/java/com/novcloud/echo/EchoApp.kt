package com.novcloud.echo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.novcloud.echo.data.ArtFetcher
import com.novcloud.echo.data.ArtKeyer
import com.novcloud.echo.data.BackupManager
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.Pinyin
import com.novcloud.echo.data.Prefs
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.data.lyrics.LyricsLoader
import com.novcloud.echo.domain.DailyPin
import com.novcloud.echo.domain.HomeData
import com.novcloud.echo.domain.Recommender
import com.novcloud.echo.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = EchoDatabase.create(app)
    val prefs = Prefs(app)
    val library = LibraryRepository(app, db, prefs, appScope)
    val lyrics = LyricsLoader(app, db)
    val backup = BackupManager(app, db, prefs, library)
    val player = PlayerConnection(app, library.library, appScope)
    val lyricsTree: StateFlow<String?> = prefs.lyricsTree.stateIn(appScope, SharingStarted.Eagerly, null)
    val audioFx = com.novcloud.echo.playback.audio.AudioFx(app, prefs::audioFx, prefs::setAudioFx, appScope)

    private val events = db.events().since(0).shareIn(appScope, SharingStarted.Eagerly, replay = 1)
    val allEvents = events.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private val today = MutableStateFlow(LocalDate.now())
    private val dailyPin = DailyPin(File(app.filesDir, "daily_pin"))

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val home: StateFlow<HomeData> = combine(library.library, merge(events.take(1), events.drop(1).debounce(800)), today) { lib, ev, _ -> lib to ev }
        .map { (lib, ev) ->
            val data = Recommender.build(lib, ev, pinned = dailyPin.read(LocalDate.now()))
            if (lib.songs.isNotEmpty()) data.daily?.let { dailyPin.write(data.day, it.songs) }
            data
        }
        .flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.Eagerly, HomeData())

    /** Home has content to show: the library (saved or scanned) and its recommendations are both in. */
    fun homeReady(): Boolean = library.library.value.loaded && home.value.ready

    init {
        appScope.launch {
            while (isActive) {
                delay(60_000)
                val d = LocalDate.now()
                if (d != today.value) today.value = d
            }
        }
        appScope.launch { library.library.collect { player.refresh() } }
        appScope.launch {
            combine(player.state.map { it.song }.distinctUntilChanged { a, b -> a?.id == b?.id }, lyricsTree) { s, t -> s to t }
                .collectLatest { (song, tree) ->
                    lyrics.warmTree(tree)
                    if (song != null) runCatching { lyrics.load(song, tree) }
                }
        }
    }
}

class EchoApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        Pinyin.install { assets.open("pinyin.bin") }
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ArtKeyer())
            add(ArtFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(64L * 1024 * 1024).build() }
        .crossfade(220)
        .respectCacheHeaders(false)
        .build()
}
