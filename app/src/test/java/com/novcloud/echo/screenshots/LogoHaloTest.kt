package com.novcloud.echo.screenshots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.compose.AsyncImage
import com.github.takahirom.roborazzi.captureRoboImage
import com.novcloud.echo.data.ArtistArtStore
import com.novcloud.echo.data.ArtistLogoInfo
import com.novcloud.echo.data.SyntheticLogos
import com.novcloud.echo.ui.components.ArtistNameMark
import com.novcloud.echo.ui.theme.EchoTheme
import com.novcloud.echo.ui.theme.Ink
import com.novcloud.echo.ui.theme.Paper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * A logo still carrying a faint matte (stored before v0.11.3, never cleaned) drawn the way the artist page draws it,
 * halo included, on a flat page: between the letters, where the matte lies, the page shows through untouched. The
 * same file drawn plainly beside it does show the box, so the check can see one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "zh-rCN-w400dp-h880dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class LogoHaloTest : ScreenshotBase() {
    private val boxW = SyntheticLogos.box[2] - SyntheticLogos.box[0]
    private val boxH = SyntheticLogos.box[3] - SyntheticLogos.box[1]

    /** The generated wordmark with a dark matte of alpha 9..11, cut to the letters by the old >12 trim. */
    private fun matteFile(): File {
        val raw = SyntheticLogos.matte(0xFFFFFF, 10, matteRgb = 0x000000, noise = 1)
        val px = IntArray(boxW * boxH) { raw[(it / boxW + SyntheticLogos.box[1]) * SyntheticLogos.W + it % boxW + SyntheticLogos.box[0]] }
        val f = File(ArtistArtStore.dir(ctx), "l_matte_${System.nanoTime()}.png")
        f.outputStream().use { Bitmap.createBitmap(px, boxW, boxH, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun check(dark: Boolean) {
        compose.mainClock.autoAdvance = false
        Samples.installImageLoader(ctx)
        val f = matteFile()
        val info = ArtistLogoInfo(f, boxW.toFloat() / boxH, 1f)
        val page = if (dark) Ink else Paper
        compose.setContent {
            EchoTheme(dark, motion = false) {
                Column(Modifier.fillMaxWidth().height(520.dp).background(page), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(380.dp, 140.dp).background(Color(0xFF7A8794)), contentAlignment = Alignment.Center) {
                        ArtistNameMark("Wren Hollow", info, 340.dp, 110.dp, Modifier.testTag("mark_photo"))
                    }
                    ArtistNameMark("Wren Hollow", info, 340.dp, 110.dp, Modifier.testTag("mark"))
                    Box(Modifier.size(340.dp, 87.dp).testTag("plain")) { AsyncImage(f, null, Modifier.size(340.dp, 87.dp), contentScale = ContentScale.Fit) }
                }
            }
        }
        settle()
        val name = "logo_matte_halo_${if (dark) "dark" else "light"}"
        val out = File("build/outputs/roborazzi/$name.png")
        compose.onRoot().captureRoboImage(out.path)
        org.junit.Assume.assumeTrue("pixels are only written in Roborazzi record mode", out.exists())
        val bmp = android.graphics.BitmapFactory.decodeFile(out.path)
        val density = ctx.resources.displayMetrics.density

        /** Largest channel difference from [want] over the gap between the ring and the block of the node [tag]. */
        fun gap(tag: String, want: Int): Int {
            val b = compose.onNodeWithTag(tag).getBoundsInRoot()
            val left = b.left.value * density
            val top = b.top.value * density
            val scale = (b.right - b.left).value * density / boxW
            // the ring ends at x 175 of the letters, the block starts at 330; stay 22dp clear of both for the halo
            val margin = 22 * density / scale
            val x0 = (left + (175 + margin) * scale).toInt()
            val x1 = (left + (330 - margin) * scale).toInt()
            val y0 = (top + 2 * scale).toInt()
            val y1 = (top + (boxH - 2) * scale).toInt()
            assertTrue("$tag: gap too narrow to check ($x0..$x1)", x1 - x0 > 20)
            var worst = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val c = bmp.getPixel(x, y)
                worst = maxOf(
                    worst,
                    abs(android.graphics.Color.red(c) - android.graphics.Color.red(want)),
                    abs(android.graphics.Color.green(c) - android.graphics.Color.green(want)),
                    abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(want)),
                )
            }
            return worst
        }
        val onPage = gap("mark", page.toArgb())
        val onPhoto = gap("mark_photo", Color(0xFF7A8794).toArgb())
        val plain = gap("plain", page.toArgb())
        assertTrue("logo on the page shows a box between its letters (off by $onPage)", onPage <= 2)
        assertTrue("logo on grey shows a box between its letters (off by $onPhoto)", onPhoto <= 2)
        if (!dark) assertTrue("the plain drawing should show the matte box this test looks for (off by $plain)", plain >= 4)
        if (!dark) {
            // Above the block, whose top edge is inside the logo, the halo fades smoothly across the logo's top
            // edge: no step where its layer would end (it used to be cut there, a straight line along the box).
            val b = compose.onNodeWithTag("mark").getBoundsInRoot()
            val scale = (b.right - b.left).value * density / boxW
            val x = (b.left.value * density + (330 + (boxW - 330) / 2) * scale).toInt()
            val top = (b.top.value * density).toInt()
            val inside = android.graphics.Color.red(bmp.getPixel(x, top + 2))
            val outside = android.graphics.Color.red(bmp.getPixel(x, top - 2))
            assertTrue("halo is cut along the logo's top edge ($outside outside, $inside inside)", abs(inside - outside) <= 6)
        }
    }

    @Test fun matteLogoLight() = check(dark = false)
    @Test fun matteLogoDark() = check(dark = true)
}
