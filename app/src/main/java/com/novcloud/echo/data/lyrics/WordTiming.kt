package com.novcloud.echo.data.lyrics

import kotlin.math.min

/** One sung span of a line: characters [start, end) fill from [t0] to [t1]. */
data class Segment(val start: Int, val end: Int, val t0: Long, val t1: Long)

class LineTiming(val length: Int, val segments: List<Segment>) {
    /**
     * Filled extent at [t] as a fractional character index into the line. Characters between spans (spaces,
     * punctuation) fill at once when the next span starts; the tail fills when the last span ends.
     */
    fun position(t: Long): Float {
        if (segments.isEmpty()) return 0f
        val first = segments.first()
        if (t < first.t0) return 0f
        for (i in segments.indices) {
            val s = segments[i]
            if (t < s.t0) return segments[i - 1].end.toFloat()
            if (t < s.t1) {
                val f = (t - s.t0).toFloat() / (s.t1 - s.t0).coerceAtLeast(1)
                return s.start + (s.end - s.start) * f
            }
        }
        return length.toFloat()
    }

    /** Index of the span being sung at [t], or -1 between spans. */
    fun active(t: Long): Int {
        for (i in segments.indices) {
            val s = segments[i]
            if (t < s.t0) return -1
            if (t < s.t1) return i
        }
        return -1
    }
}

object WordTiming {
    /** Upper bound for an estimated line, so a long instrumental break after a line does not stretch its fill. */
    internal const val MS_PER_UNIT = 650L
    internal const val LINE_PAD_MS = 400L
    private const val TAIL_MS_PER_UNIT = 450L

    /** Timing for [line] (index [index] in [lines]); real word stamps when present, otherwise an estimate. */
    fun of(lines: List<LyricLine>, index: Int, songEndMs: Long = -1): LineTiming? {
        val line = lines.getOrNull(index) ?: return null
        if (line.timeMs < 0 || line.text.isBlank()) return null
        val next = lines.drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs
            ?: songEndMs.takeIf { it > line.timeMs }
        val words = line.words
        return if (words != null) stamped(line, words, next) else estimate(line.text, line.timeMs, line.endMs.takeIf { it > line.timeMs } ?: next)
    }

    private fun stamped(line: LyricLine, words: List<LyricWord>, next: Long?): LineTiming {
        val sorted = words.sortedBy { it.timeMs }
        val segs = sorted.mapIndexed { i, w ->
            val following = sorted.getOrNull(i + 1)?.timeMs
            val fallback = following ?: line.endMs.takeIf { it > w.timeMs }
                ?: min(next ?: Long.MAX_VALUE, w.timeMs + TAIL_MS_PER_UNIT * units(line.text, w.start, w.end).coerceAtLeast(1.0).toLong())
            val end = if (w.endMs > w.timeMs) w.endMs else fallback
            Segment(w.start.coerceIn(0, line.text.length), w.end.coerceIn(0, line.text.length), w.timeMs, end.coerceAtLeast(w.timeMs + 1))
        }
        return LineTiming(line.text.length, segs)
    }

    /**
     * Spreads the line over its sung time by syllable weight: a CJK character, kana or hangul block counts one
     * (small kana and the long-vowel mark half), a Latin word one per vowel group, a number by its digits.
     * Spaces and punctuation get no time.
     */
    fun estimate(text: String, start: Long, nextStart: Long?): LineTiming {
        val units = tokenize(text)
        val total = units.sumOf { it.third }
        if (units.isEmpty() || total <= 0.0) return LineTiming(text.length, emptyList())
        val cap = (total * MS_PER_UNIT).toLong() + LINE_PAD_MS
        val span = min(nextStart?.let { it - start } ?: cap, cap).coerceAtLeast(300)
        var acc = 0.0
        val segs = units.map { (a, b, w) ->
            val t0 = start + (span * (acc / total)).toLong()
            acc += w
            val t1 = start + (span * (acc / total)).toLong()
            Segment(a, b, t0, t1.coerceAtLeast(t0 + 1))
        }
        return LineTiming(text.length, segs)
    }

    internal fun units(text: String, from: Int, to: Int): Double = tokenize(text.substring(from, to)).sumOf { it.third }

    internal fun tokenize(text: String): List<Triple<Int, Int, Double>> {
        val out = ArrayList<Triple<Int, Int, Double>>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < text.length -> {
                    val cp = text.codePointAt(i)
                    if (Character.isIdeographic(cp)) out += Triple(i, i + 2, 1.0)
                    i += 2
                }
                isSyllabic(c) -> { out += Triple(i, i + 1, if (c in SMALL_KANA || c == 'ー') 0.5 else 1.0); i++ }
                c.isLetterOrDigit() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() && !isSyllabic(text[j]) || (text[j] in "'’-" && j + 1 < text.length && text[j + 1].isLetter() && !isSyllabic(text[j + 1])))) j++
                    out += Triple(i, j, latinWeight(text.substring(i, j)))
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"
    private const val VOWELS = "aeiouyàáâãäåèéêëìíîïòóôõöùúûüýÿæœаеёиоуыэюяαεηιουω"

    private fun isSyllabic(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF' ||
        c in '\u3040'..'\u30FF' || c in '\uAC00'..'\uD7AF' || c == '\u3005'

    private fun latinWeight(word: String): Double {
        if (word.all { it.isDigit() }) return (word.length * 0.6).coerceAtLeast(1.0)
        var groups = 0
        var inVowel = false
        word.lowercase().forEach { ch ->
            val v = ch in VOWELS
            if (v && !inVowel) groups++
            inVowel = v
        }
        val stem = word.lowercase().removeSuffix("s")
        if (groups > 1 && stem.length > 3 && stem.endsWith("e") && stem[stem.length - 2] !in VOWELS && !stem.endsWith("le")) groups--
        return groups.coerceAtLeast(1).toDouble()
    }
}

/**
 * Smooth playback position for per-frame drawing. The player is polled a few times a second; between polls the
 * position advances with the frame clock (at most [MAX_EXTRAPOLATE_MS] past the last poll), small backward
 * corrections from poll jitter are absorbed instead of rewinding the fill, and real seeks jump.
 */
class SmoothClock {
    private var anchorPos = 0L
    private var anchorFrame = -1L
    private var shown = 0L
    private var lastPolled = Long.MIN_VALUE

    fun frame(frameMs: Long, polled: Long, playing: Boolean): Long {
        if (polled != lastPolled || anchorFrame < 0) {
            lastPolled = polled
            anchorPos = polled
            anchorFrame = frameMs
        }
        val target = if (playing) anchorPos + (frameMs - anchorFrame).coerceIn(0, MAX_EXTRAPOLATE_MS) else anchorPos
        val back = shown - target
        shown = if (back in 1..JITTER_MS) shown else target
        return shown
    }

    companion object {
        const val MAX_EXTRAPOLATE_MS = 600L
        const val JITTER_MS = 250L
    }
}
