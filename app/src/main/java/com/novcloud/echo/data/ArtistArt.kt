package com.novcloud.echo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class ArtistLogoInfo(val file: File, val aspect: Float, val luma: Float) {
    val dark: Boolean get() = luma < 0.38f
}

enum class ArtKind { Logo, Poster }

data class ArtMatch(val artist: String, val kind: ArtKind)

data class ImportReport(val logos: Int, val posters: Int, val unmatched: List<String>)

object ArtistFileMatcher {
    private val imageExt = setOf("png", "webp", "jpg", "jpeg")
    private val transparentExt = setOf("png", "webp")
    private val posterSuffix = Regex("""(?i)[\s_\-.]*(poster|background|bg|海报|背景)$""")
    private val logoSuffix = Regex("""(?i)[\s_\-.]*(logo|wordmark|name|标志)$""")
    private val hashSuffix = Regex("""-[0-9a-f]{8,}$""")

    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    fun index(artists: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        artists.forEach { a -> key(a).takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, a) } }
        return out
    }

    fun match(fileName: String, index: Map<String, String>): ArtMatch? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in imageExt) return null
        var stem = fileName.substringBeforeLast('.').trim()
        var kind: ArtKind? = null
        posterSuffix.find(stem)?.let { stem = stem.removeRange(it.range); kind = ArtKind.Poster }
        if (kind == null) logoSuffix.find(stem)?.let { stem = stem.removeRange(it.range); kind = ArtKind.Logo }
        val resolved = kind ?: if (ext in transparentExt) ArtKind.Logo else ArtKind.Poster
        val artist = index[key(stem)] ?: index[key(stem.replace(hashSuffix, ""))] ?: return null
        return ArtMatch(artist, resolved)
    }
}

object ArtFiles {
    fun name(kind: ArtKind, artist: String, version: Long): String {
        val prefix = if (kind == ArtKind.Poster) "p" else "l"
        val ext = if (kind == ArtKind.Poster) "jpg" else "png"
        return "${prefix}_${artist.hashCode().toUInt()}_${version.toString(36)}.$ext"
    }
}

object ArtistArtStore {
    private const val LOGO_MAX_W = 1600
    private const val LOGO_MAX_H = 900
    private const val POSTER_MAX = 2200

    fun dir(context: Context) = File(context.filesDir, "artists").apply { mkdirs() }

    private fun decode(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, maxW.toFloat() / w, maxH.toFloat() / h)
                    if (scale < 1f) d.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.isMutableRequired = false
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = minOf(1f, maxW.toFloat() / raw.width, maxH.toFloat() / raw.height)
        if (scale >= 0.999f) return raw
        val scaled = Bitmap.createScaledBitmap(raw, max(1, (raw.width * scale).roundToInt()), max(1, (raw.height * scale).roundToInt()), true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    fun savePoster(context: Context, artist: String, uri: Uri): File? {
        val bmp = decode(context, uri, POSTER_MAX, POSTER_MAX) ?: return null
        val file = File(dir(context), ArtFiles.name(ArtKind.Poster, artist, System.nanoTime()))
        val ok = runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrDefault(false)
        bmp.recycle()
        return file.takeIf { ok }
    }

    fun saveLogo(context: Context, artist: String, uri: Uri): ArtistLogoInfo? {
        val decoded = decode(context, uri, LOGO_MAX_W, LOGO_MAX_H) ?: return null
        val bmp = trim(decoded)
        if (bmp !== decoded) decoded.recycle()
        val luma = luma(bmp)
        val file = File(dir(context), ArtFiles.name(ArtKind.Logo, artist, System.nanoTime()))
        val ok = runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
        val aspect = bmp.width.toFloat() / bmp.height
        bmp.recycle()
        return if (ok) ArtistLogoInfo(file, aspect, luma) else null
    }

    private fun trim(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 12) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < l || b < t) return bmp
        if (l == 0 && t == 0 && r == w - 1 && b == h - 1) return bmp
        return Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1)
    }

    private fun luma(bmp: Bitmap): Float {
        val step = max(1, max(bmp.width, bmp.height) / 160)
        var sum = 0.0
        var weight = 0.0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val a = (c ushr 24) / 255.0
                if (a > 0.05) {
                    val r = (c shr 16 and 0xff) / 255.0
                    val g = (c shr 8 and 0xff) / 255.0
                    val bl = (c and 0xff) / 255.0
                    sum += (0.2126 * r + 0.7152 * g + 0.0722 * bl) * a
                    weight += a
                }
                x += step
            }
            y += step
        }
        return if (weight == 0.0) 1f else (sum / weight).toFloat()
    }
}
