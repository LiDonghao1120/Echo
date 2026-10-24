package com.novcloud.echo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.novcloud.echo.data.ArtFetcher
import com.novcloud.echo.data.ArtKeyer
import com.novcloud.echo.data.LibraryRepository
import com.novcloud.echo.data.Prefs
import com.novcloud.echo.data.db.EchoDatabase
import com.novcloud.echo.data.lyrics.LyricsLoader
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = EchoDatabase.create(app)
    val prefs = Prefs(app)
    val library = LibraryRepository(app, db, prefs, appScope)
    val lyrics = LyricsLoader(app)
    val player = PlayerConnection(app, library.library, appScope)

    val allEvents = db.events().since(0).stateIn(appScope, SharingStarted.Eagerly, emptyList())

    private val today = MutableStateFlow(LocalDate.now())

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val home: StateFlow<HomeData> = combine(library.library, allEvents.debounce(800), today) { lib, ev, _ -> lib to ev }
        .map { (lib, ev) -> Recommender.build(lib, ev) }
        .flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.Eagerly, HomeData())

    init {
        appScope.launch {
            while (isActive) {
                delay(60_000)
                val d = LocalDate.now()
                if (d != today.value) today.value = d
            }
        }
        appScope.launch { library.library.collect { player.refresh() } }
    }
}

class EchoApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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
