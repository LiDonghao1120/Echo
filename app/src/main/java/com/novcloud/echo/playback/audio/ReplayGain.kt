package com.novcloud.echo.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

enum class GainMode { Off, Track, Album }

/** ReplayGain values in dB / linear peak, as tagged. */
data class GainTags(val trackGain: Float? = null, val trackPeak: Float? = null, val albumGain: Float? = null, val albumPeak: Float? = null) {
    val isEmpty get() = trackGain == null && albumGain == null
}

object ReplayGain {
    /**
     * Reads REPLAYGAIN_* from any key/value tag source (ID3 TXXX descriptions, Vorbis comments, MP4 freeform
     * names, APEv2 keys), plus Opus R128_*_GAIN (Q7.8 dB relative to -23 LUFS, so +5 dB to reach ReplayGain's -18).
     */
    fun parse(tags: Map<String, String>): GainTags {
        val t = tags.mapKeys { it.key.trim().uppercase() }
        fun db(k: String) = t[k]?.let(::number)
        fun r128(k: String) = t[k]?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
        return GainTags(
            trackGain = db("REPLAYGAIN_TRACK_GAIN") ?: r128("R128_TRACK_GAIN"),
            trackPeak = db("REPLAYGAIN_TRACK_PEAK")?.takeIf { it > 0f },
            albumGain = db("REPLAYGAIN_ALBUM_GAIN") ?: r128("R128_ALBUM_GAIN"),
            albumPeak = db("REPLAYGAIN_ALBUM_PEAK")?.takeIf { it > 0f },
        )
    }

    private val numberRe = Regex("[-+]?\\d+(?:[.,]\\d+)?")
    private fun number(s: String): Float? = numberRe.find(s)?.value?.replace(',', '.')?.toFloatOrNull()

    fun fromMetadata(metadata: Metadata?): GainTags {
        if (metadata == null) return GainTags()
        val map = HashMap<String, String>()
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is TextInformationFrame -> if (e.id == "TXXX" && e.description != null) map.putIfAbsent(e.description!!, e.values.firstOrNull().orEmpty())
                is InternalFrame -> map.putIfAbsent(e.description, e.text)
                is VorbisComment -> map.putIfAbsent(e.key, e.value)
            }
        }
        return parse(map)
    }

    /**
     * Linear gain for one track. Album mode falls back to the track values; an untagged track is left alone.
     * With [clipPrevention] the gain is capped so the tagged peak stays at or below full scale; without a peak
     * tag, the cap is unity (no boost) because the headroom is unknown.
     */
    fun linear(tags: GainTags, mode: GainMode, preampDb: Float, clipPrevention: Boolean): Float {
        if (mode == GainMode.Off) return 1f
        val (gain, peak) = when {
            mode == GainMode.Album && tags.albumGain != null -> tags.albumGain to (tags.albumPeak ?: tags.trackPeak)
            tags.trackGain != null -> tags.trackGain to tags.trackPeak
            tags.albumGain != null -> tags.albumGain to tags.albumPeak
            else -> return 1f
        }
        var g = 10f.pow((gain + preampDb) / 20f)
        if (clipPrevention) g = minOf(g, if (peak != null) 1f / peak else 1f)
        return g.coerceIn(0.01f, 8f)
    }
}

/** Multiplies 16-bit or float PCM by [gain]; 16-bit output is saturated rather than wrapped. Other encodings pass through. */
class GainProcessor : BaseAudioProcessor() {
    @Volatile var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val g = gain
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            if (g == 1f) out.put(src) else while (src.remaining() >= 2) {
                val v = (src.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(v.toShort())
            }
        } else {
            if (g == 1f) out.put(src) else while (src.remaining() >= 4) out.putFloat((src.float * g).coerceIn(-1f, 1f))
        }
        if (src.hasRemaining()) out.put(src)
        out.flip()
    }
}

/**
 * Sets the ReplayGain for each stream as the renderer configures the sink for it. MediaCodecAudioRenderer passes
 * the stream's metadata (ID3/Vorbis/MP4 tags) with the output format, and by then every buffer of the previous
 * stream has been queued through [processor] at the old gain, so gapless transitions switch exactly at the boundary.
 */
class ReplayGainSink(sink: AudioSink, private val processor: GainProcessor) : ForwardingAudioSink(sink) {
    @Volatile private var tags = GainTags()
    @Volatile private var mode = GainMode.Off
    @Volatile private var preamp = 0f
    @Volatile private var clip = true

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        tags = ReplayGain.fromMetadata(inputFormat.metadata)
        update()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    fun setSettings(mode: GainMode, preampDb: Float, clipPrevention: Boolean) {
        this.mode = mode
        preamp = preampDb
        clip = clipPrevention
        update()
    }

    private fun update() {
        processor.gain = ReplayGain.linear(tags, mode, preamp, clip)
    }
}
