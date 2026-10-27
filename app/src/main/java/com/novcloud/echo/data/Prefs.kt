package com.novcloud.echo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("echo")

data class SavedQueue(val keys: List<String>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

enum class ThemeMode { System, Dark, Light }

class Prefs(private val context: Context) {
    private val ds get() = context.dataStore

    private object K {
        val queue = stringPreferencesKey("queue")
        val queueKeys = stringPreferencesKey("queue_keys")
        val index = intPreferencesKey("queue_index")
        val pos = longPreferencesKey("queue_pos")
        val shuffle = booleanPreferencesKey("shuffle")
        val repeat = intPreferencesKey("repeat")
        val minDuration = intPreferencesKey("min_duration_s")
        val lyricsTree = stringPreferencesKey("lyrics_tree")
        val theme = stringPreferencesKey("theme")
        val songSort = stringPreferencesKey("song_sort")
        val keepScreenLyrics = booleanPreferencesKey("lyrics_keep_screen")
        val scanIncluded = stringSetPreferencesKey("scan_included")
        val scanExcluded = stringSetPreferencesKey("scan_excluded")
        val scanTrees = stringSetPreferencesKey("scan_trees")
    }

    val minDurationSec: Flow<Int> = ds.data.map { it[K.minDuration] ?: 30 }
    val lyricsTree: Flow<String?> = ds.data.map { it[K.lyricsTree] }
    val theme: Flow<ThemeMode> = ds.data.map { p -> ThemeMode.entries.firstOrNull { it.name == p[K.theme] } ?: ThemeMode.System }
    val songSort: Flow<String> = ds.data.map { it[K.songSort] ?: "title" }
    val keepScreenOnLyrics: Flow<Boolean> = ds.data.map { it[K.keepScreenLyrics] ?: true }

    val scanIncluded: Flow<Set<String>> = ds.data.map { it[K.scanIncluded].orEmpty() }
    val scanExcluded: Flow<Set<String>> = ds.data.map { it[K.scanExcluded].orEmpty() }
    val scanTrees: Flow<Set<String>> = ds.data.map { it[K.scanTrees].orEmpty() }

    suspend fun setFolder(path: String, on: Boolean) = edit {
        val inc = it[K.scanIncluded].orEmpty().toMutableSet()
        val exc = it[K.scanExcluded].orEmpty().toMutableSet()
        if (on) { inc += path; exc -= path } else { exc += path; inc -= path }
        it[K.scanIncluded] = inc
        it[K.scanExcluded] = exc
    }

    suspend fun resetFolder(path: String) = edit {
        it[K.scanIncluded] = it[K.scanIncluded].orEmpty() - path
        it[K.scanExcluded] = it[K.scanExcluded].orEmpty() - path
    }

    suspend fun addTree(uri: String, path: String?) = edit {
        it[K.scanTrees] = it[K.scanTrees].orEmpty() + uri
        if (path != null) {
            it[K.scanIncluded] = it[K.scanIncluded].orEmpty() + path
            it[K.scanExcluded] = it[K.scanExcluded].orEmpty() - path
        }
    }

    suspend fun removeTree(uri: String, path: String?) = edit {
        it[K.scanTrees] = it[K.scanTrees].orEmpty() - uri
        if (path != null) it[K.scanIncluded] = it[K.scanIncluded].orEmpty() - path
    }

    suspend fun setMinDuration(sec: Int) = edit { it[K.minDuration] = sec }
    suspend fun setLyricsTree(uri: String?) = edit { if (uri == null) it.remove(K.lyricsTree) else it[K.lyricsTree] = uri }
    suspend fun setTheme(mode: ThemeMode) = edit { it[K.theme] = mode.name }
    suspend fun setSongSort(sort: String) = edit { it[K.songSort] = sort }
    suspend fun setKeepScreenOnLyrics(v: Boolean) = edit { it[K.keepScreenLyrics] = v }

    suspend fun backupSettings(): BackupSettings {
        val p = ds.data.first()
        return BackupSettings(
            theme = p[K.theme], minDurationSec = p[K.minDuration], songSort = p[K.songSort], keepScreenOnLyrics = p[K.keepScreenLyrics],
            scanIncluded = p[K.scanIncluded].orEmpty(), scanExcluded = p[K.scanExcluded].orEmpty(), scanTrees = p[K.scanTrees].orEmpty(),
            lyricsTree = p[K.lyricsTree],
        )
    }

    /** [usable] filters folder grants: a tree from another phone (or a revoked one) cannot be read here. */
    suspend fun restoreSettings(s: BackupSettings, usable: (String) -> Boolean) = edit {
        fun <T> put(k: androidx.datastore.preferences.core.Preferences.Key<T>, v: T?) { if (v == null) it.remove(k) else it[k] = v }
        put(K.theme, s.theme)
        put(K.minDuration, s.minDurationSec)
        put(K.songSort, s.songSort)
        put(K.keepScreenLyrics, s.keepScreenOnLyrics)
        it[K.scanIncluded] = s.scanIncluded
        it[K.scanExcluded] = s.scanExcluded
        it[K.scanTrees] = s.scanTrees.filter(usable).toSet()
        put(K.lyricsTree, s.lyricsTree?.takeIf(usable))
    }

    suspend fun saveQueue(q: SavedQueue) = edit {
        it.remove(K.queue)
        it[K.queueKeys] = q.keys.joinToString(SEP)
        it[K.index] = q.index
        it[K.pos] = q.positionMs
        it[K.shuffle] = q.shuffle
        it[K.repeat] = q.repeat
    }

    suspend fun loadQueue(): SavedQueue? {
        val p = ds.data.first()
        val keys = p[K.queueKeys]?.split(SEP)?.filter { it.isNotEmpty() }
            ?: p[K.queue]?.split(',')?.mapNotNull { it.toLongOrNull()?.let(SongKeys::legacyKey) }.orEmpty()
        if (keys.isEmpty()) return null
        return SavedQueue(keys, p[K.index] ?: 0, p[K.pos] ?: 0, p[K.shuffle] ?: false, p[K.repeat] ?: 0)
    }

    private companion object {
        const val SEP = "\u001F"
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit { block(it) }
    }
}

