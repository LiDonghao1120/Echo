package com.novcloud.echo

import com.novcloud.echo.playback.audio.AudioSettings
import com.novcloud.echo.playback.audio.Band
import com.novcloud.echo.playback.audio.EqPresets
import com.novcloud.echo.playback.audio.GainMode
import com.novcloud.echo.playback.audio.GainTags
import com.novcloud.echo.playback.audio.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.pow

class AudioEffectsTest {
    @Test
    fun parsesReplayGainFromAnyTagSpelling() {
        val g = ReplayGain.parse(
            mapOf(
                "replaygain_track_gain" to "-7.25 dB", "REPLAYGAIN_TRACK_PEAK" to "0.988525",
                "ReplayGain_Album_Gain" to "+1,50 dB", "replaygain_album_peak" to "1.000000",
            ),
        )
        assertEquals(-7.25f, g.trackGain!!, 1e-4f)
        assertEquals(0.988525f, g.trackPeak!!, 1e-6f)
        assertEquals(1.5f, g.albumGain!!, 1e-4f)
        assertEquals(1f, g.albumPeak!!, 1e-6f)
    }

    @Test
    fun opusR128IsShiftedToReplayGainReference() {
        val g = ReplayGain.parse(mapOf("R128_TRACK_GAIN" to "-2560"))
        assertEquals(-5f, g.trackGain!!, 1e-4f)
        assertNull(g.albumGain)
    }

    @Test
    fun linearGainHonoursModePreampAndClipping() {
        val tags = GainTags(trackGain = -6f, trackPeak = 0.5f, albumGain = 4f, albumPeak = 0.9f)
        assertEquals(1f, ReplayGain.linear(tags, GainMode.Off, 0f, true), 0f)
        assertEquals(10f.pow(-6f / 20f), ReplayGain.linear(tags, GainMode.Track, 0f, true), 1e-5f)
        assertEquals(10f.pow(-3f / 20f), ReplayGain.linear(tags, GainMode.Track, 3f, true), 1e-5f)
        assertEquals(1f / 0.9f, ReplayGain.linear(tags, GainMode.Album, 0f, true), 1e-5f)
        assertEquals(10f.pow(4f / 20f), ReplayGain.linear(tags, GainMode.Album, 0f, false), 1e-5f)
    }

    @Test
    fun albumFallsBackToTrackAndUntaggedIsUntouched() {
        assertEquals(10f.pow(-2f / 20f), ReplayGain.linear(GainTags(trackGain = -2f), GainMode.Album, 0f, true), 1e-5f)
        assertEquals(1f, ReplayGain.linear(GainTags(), GainMode.Track, 6f, true), 0f)
        assertEquals(1f, ReplayGain.linear(GainTags(trackGain = 5f), GainMode.Track, 0f, true), 0f)
    }

    @Test
    fun presetsMapOntoDeviceBandsByFrequency() {
        val five = listOf(60, 230, 910, 3600, 14000)
        val rock = AudioSettings(preset = "rock").levels(five, -1500, 1500)
        assertEquals(5, rock.size)
        assertEquals(EqPresets.interpolate(EqPresets.curve("rock"), 60), rock[0])
        assertEquals(400, rock[4])
        val flat = AudioSettings().levels(five, -1500, 1500)
        assertEquals(listOf(0, 0, 0, 0, 0), flat)
        val clamped = AudioSettings(preset = EqPresets.CUSTOM, custom = listOf(Band(60, 3000), Band(14000, -3000))).levels(five, -1200, 1200)
        assertEquals(1200, clamped.first())
        assertEquals(-1200, clamped.last())
    }

    @Test
    fun interpolationIsLogFrequency() {
        val curve = listOf(Band(100, 0), Band(10_000, 1000))
        assertEquals(500, EqPresets.interpolate(curve, 1000))
        assertEquals(0, EqPresets.interpolate(curve, 20))
        assertEquals(1000, EqPresets.interpolate(curve, 20_000))
    }

    @Test
    fun settingsSurviveJsonAndRejectGarbage() {
        val s = AudioSettings(true, EqPresets.CUSTOM, listOf(Band(60, 300), Band(14000, -200)), 450, GainMode.Album, -3.5f, false, false)
        assertEquals(s, AudioSettings.fromJson(s.toJson()))
        assertEquals(AudioSettings(), AudioSettings.fromJson("{not json"))
        assertEquals(EqPresets.FLAT, AudioSettings.fromJson("""{"preset":"nope"}""").preset)
    }
}
