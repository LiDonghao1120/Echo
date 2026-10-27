package com.novcloud.echo.playback.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.novcloud.echo.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the device's effect engine offers. [available] false when no equalizer can be created at all. */
data class EqCaps(
    val available: Boolean,
    val centersHz: List<Int> = emptyList(),
    val minMb: Int = -1500,
    val maxMb: Int = 1500,
    val bassBoost: Boolean = false,
)

/**
 * Audio effect settings shared by the settings page and the playback service (same process). Edits apply at
 * once through [settings] and are written to DataStore shortly after, so dragging a band is heard live without
 * a write per frame; the service reads the stored value when it starts.
 */
class AudioFx(
    private val context: Context,
    private val load: suspend () -> String?,
    private val store: suspend (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    private val _caps = MutableStateFlow<EqCaps?>(null)
    val caps: StateFlow<EqCaps?> = _caps.asStateFlow()
    private var saveJob: Job? = null

    init {
        scope.launch {
            val stored = AudioSettings.fromJson(load())
            if (!_loaded.value) _settings.value = stored
            _loaded.value = true
        }
    }

    suspend fun awaitLoaded(): AudioSettings {
        _loaded.first { it }
        return _settings.value
    }

    fun update(change: (AudioSettings) -> AudioSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        _loaded.value = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            store(next.toJson())
        }
    }

    fun publishCaps(c: EqCaps) {
        _caps.value = c
    }

    /** Reads the device's equalizer on a private session when the service has not reported it yet. */
    suspend fun probe() {
        if (_caps.value != null) return
        val c = withContext(Dispatchers.IO) {
            val am = context.getSystemService(AudioManager::class.java)
            val session = am?.generateAudioSessionId() ?: AudioManager.ERROR
            if (session == AudioManager.ERROR) return@withContext EqCaps(false)
            EffectChain.describe(session)
        }
        if (_caps.value == null) _caps.value = c
    }
}

/** The equalizer and bass boost attached to one audio session. Every call is guarded: effect engines fail in many ways. */
class EffectChain(val session: Int) {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    var caps: EqCaps = EqCaps(false)
        private set

    init {
        eq = runCatching { Equalizer(0, session) }.onFailure { DiagLog.w(TAG, "equalizer unavailable on session $session", it) }.getOrNull()
        bass = runCatching { BassBoost(0, session).takeIf { it.strengthSupported } }.getOrNull()
        caps = eq?.let { describe(it, bass != null) } ?: EqCaps(false, bassBoost = bass != null)
    }

    fun apply(s: AudioSettings) {
        val e = eq
        if (e != null && caps.available) runCatching {
            if (s.eqEnabled) {
                s.levels(caps.centersHz, caps.minMb, caps.maxMb).forEachIndexed { i, mb -> e.setBandLevel(i.toShort(), mb.toShort()) }
            }
            e.enabled = s.eqEnabled
        }.onFailure { DiagLog.w(TAG, "equalizer apply failed", it) }
        bass?.let { b ->
            runCatching {
                if (s.bassStrength > 0) b.setStrength(s.bassStrength.toShort())
                b.enabled = s.bassStrength > 0
            }.onFailure { DiagLog.w(TAG, "bass boost apply failed", it) }
        }
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
    }

    companion object {
        private const val TAG = "EchoFx"

        private fun describe(e: Equalizer, bass: Boolean): EqCaps = runCatching {
            val range = e.bandLevelRange
            val n = e.numberOfBands.toInt()
            EqCaps(true, (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }, range[0].toInt(), range[1].toInt(), bass)
        }.getOrElse { EqCaps(false, bassBoost = bass) }

        fun describe(session: Int): EqCaps {
            val chain = EffectChain(session)
            val c = chain.caps
            chain.release()
            return c
        }
    }
}
