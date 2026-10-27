package com.novcloud.echo.playback.audio

import kotlin.math.abs
import kotlin.math.log10
import org.json.JSONArray
import org.json.JSONObject

/** A band of the custom curve: centre frequency in Hz and level in millibels. */
data class Band(val hz: Int, val mb: Int)

data class AudioSettings(
    val eqEnabled: Boolean = false,
    val preset: String = EqPresets.FLAT,
    val custom: List<Band> = emptyList(),
    val bassStrength: Int = 0,
    val gainMode: GainMode = GainMode.Off,
    val preampDb: Float = 0f,
    val clipPrevention: Boolean = true,
    val fade: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("eq", eqEnabled).put("preset", preset)
        .put("custom", JSONArray().apply { custom.forEach { put(JSONArray().put(it.hz).put(it.mb)) } })
        .put("bass", bassStrength).put("rg", gainMode.name).put("preamp", preampDb.toDouble()).put("clip", clipPrevention).put("fade", fade)
        .toString()

    /** Device band levels (mB) for these settings, given the device's band centres and level range. */
    fun levels(centersHz: List<Int>, minMb: Int, maxMb: Int): List<Int> {
        val curve: List<Band> = if (preset == EqPresets.CUSTOM) custom else EqPresets.curve(preset)
        return centersHz.map { EqPresets.interpolate(curve, it).coerceIn(minMb, maxMb) }
    }

    companion object {
        fun fromJson(s: String?): AudioSettings {
            if (s.isNullOrBlank()) return AudioSettings()
            return runCatching {
                val o = JSONObject(s)
                val arr = o.optJSONArray("custom") ?: JSONArray()
                AudioSettings(
                    eqEnabled = o.optBoolean("eq"),
                    preset = o.optString("preset", EqPresets.FLAT).takeIf { it == EqPresets.CUSTOM || EqPresets.ids.contains(it) } ?: EqPresets.FLAT,
                    custom = (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { Band(it.optInt(0), it.optInt(1)) } }.filter { it.hz > 0 },
                    bassStrength = o.optInt("bass").coerceIn(0, 1000),
                    gainMode = GainMode.entries.firstOrNull { it.name == o.optString("rg") } ?: GainMode.Off,
                    preampDb = o.optDouble("preamp", 0.0).toFloat().coerceIn(-12f, 12f),
                    clipPrevention = o.optBoolean("clip", true),
                    fade = o.optBoolean("fade", true),
                )
            }.getOrDefault(AudioSettings())
        }
    }
}

/**
 * Presets are curves on the ten ISO octave bands, so they mean the same on a 5-band and a 10-band device:
 * each device band takes the curve's level at its centre frequency, interpolated on a log-frequency axis.
 */
object EqPresets {
    const val FLAT = "flat"
    const val CUSTOM = "custom"
    private val iso = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** id, dB per ISO band. */
    val all: List<Pair<String, List<Int>>> = listOf(
        FLAT to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "bass" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        "vocal" to listOf(-2, -2, -1, 1, 3, 4, 3, 1, 0, -1),
        "pop" to listOf(-1, 1, 3, 4, 3, 0, -1, -1, 1, 2),
        "rock" to listOf(5, 4, 2, -1, -2, -1, 2, 3, 4, 4),
        "electronic" to listOf(5, 4, 1, 0, -2, 1, 0, 1, 4, 5),
        "jazz" to listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        "classical" to listOf(4, 3, 2, 1, -1, -1, 0, 2, 3, 4),
        "acoustic" to listOf(4, 3, 2, 1, 1, 1, 2, 3, 2, 1),
        "treble" to listOf(0, 0, 0, 0, 0, 1, 3, 5, 6, 6),
    )
    val ids = all.map { it.first }.toSet()

    fun curve(id: String): List<Band> {
        val db = all.firstOrNull { it.first == id }?.second ?: all[0].second
        return iso.zip(db) { hz, d -> Band(hz, d * 100) }
    }

    fun interpolate(curve: List<Band>, hz: Int): Int {
        val pts = curve.filter { it.hz > 0 }.sortedBy { it.hz }
        if (pts.isEmpty()) return 0
        if (hz <= pts.first().hz) return pts.first().mb
        if (hz >= pts.last().hz) return pts.last().mb
        val x = log10(hz.toDouble())
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (hz <= b.hz) {
                val xa = log10(a.hz.toDouble())
                val xb = log10(b.hz.toDouble())
                val t = if (abs(xb - xa) < 1e-9) 0.0 else (x - xa) / (xb - xa)
                return Math.round(a.mb + (b.mb - a.mb) * t).toInt()
            }
        }
        return pts.last().mb
    }
}
