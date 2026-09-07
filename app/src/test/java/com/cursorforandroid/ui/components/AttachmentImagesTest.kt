package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/** Real encoding and decoding through Robolectric's native renderer, the same one the screenshot tests use. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttachmentImagesTest {

    /**
     * A screenshot-like fixture: dark UI with light text, plus per-pixel noise over the top-left [noisyFraction] of
     * the image so PNG has real work to do (a flat synthetic image compresses to almost nothing, unlike a screenshot
     * of a real UI with imagery and anti-aliasing). With [alpha] the background stays transparent outside the noise.
     */
    private fun bitmap(width: Int, height: Int, alpha: Boolean = false, noisyFraction: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (!alpha) canvas.drawColor(Color.rgb(30, 30, 30))
        val paint = Paint().apply { color = Color.rgb(240, 240, 240); textSize = height / 12f }
        for (y in 0 until height step max(1, height / 12)) canvas.drawText("merge and chat states often fail to sync", 8f, y.toFloat(), paint)
        val nw = (width * noisyFraction).toInt().coerceIn(1, width)
        val nh = (height * noisyFraction).toInt().coerceIn(1, height)
        val region = IntArray(nw * nh)
        bmp.getPixels(region, 0, nw, 0, 0, nw, nh)
        val random = Random(42)
        for (i in region.indices) {
            val p = region[i]
            val d = random.nextInt(-14, 15)
            region[i] = Color.argb(255, (Color.red(p) + d).coerceIn(0, 255), (Color.green(p) + d).coerceIn(0, 255), (Color.blue(p) + d).coerceIn(0, 255))
        }
        bmp.setPixels(region, 0, nw, 0, 0, nw, nh)
        return bmp
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int = 100): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun `a phone screenshot is scaled to the model's ceiling and re-encoded as a much smaller JPEG`() {
        val original = bitmap(1440, 3200).encode(Bitmap.CompressFormat.PNG)
        assertThat(original.size).isGreaterThan(AttachmentImages.PASSTHROUGH_MAX_BYTES)

        val prepared = AttachmentImages.prepare(original, "image/png")

        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val (w, h) = dimensions(prepared.bytes)
        assertThat(h).isEqualTo(AttachmentImages.MAX_EDGE_PX)
        assertThat(w).isEqualTo(706) // 1440 * 1568 / 3200, aspect ratio kept
        assertThat(prepared.sizeBytes).isLessThan(original.size / 4)
    }

    @Test
    fun `a photo wider than the ceiling is downscaled and keeps its aspect ratio`() {
        val original = bitmap(3200, 2400).encode(Bitmap.CompressFormat.JPEG, 92)
        val prepared = AttachmentImages.prepare(original, "image/jpeg")
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val (w, h) = dimensions(prepared.bytes)
        assertThat(w).isEqualTo(AttachmentImages.MAX_EDGE_PX)
        assertThat(h).isEqualTo(1176)
        assertThat(prepared.sizeBytes).isLessThan(original.size)
    }

    @Test
    fun `small images and GIFs pass through untouched`() {
        val small = bitmap(640, 360, noisyFraction = 0.25f).encode(Bitmap.CompressFormat.PNG)
        assertThat(small.size).isAtMost(AttachmentImages.PASSTHROUGH_MAX_BYTES)
        val prepared = AttachmentImages.prepare(small, "image/png")
        assertThat(prepared.bytes).isEqualTo(small)
        assertThat(prepared.mimeType).isEqualTo("image/png")

        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) + ByteArray(2 * 1024 * 1024)
        val animated = AttachmentImages.prepare(gif, "image/GIF")
        assertThat(animated.bytes).isSameInstanceAs(gif)
        assertThat(animated.mimeType).isEqualTo("image/gif")
    }

    @Test
    fun `transparency is flattened onto white rather than black`() {
        val original = bitmap(2400, 2400, alpha = true, noisyFraction = 0.5f).encode(Bitmap.CompressFormat.PNG)
        val prepared = AttachmentImages.prepare(original, "image/png")
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.sizeBytes)
        val corner = decoded.getPixel(decoded.width - 1, decoded.height - 1)
        assertThat(Color.red(corner)).isAtLeast(250)
        assertThat(Color.green(corner)).isAtLeast(250)
        assertThat(Color.blue(corner)).isAtLeast(250)
    }

    @Test
    fun `EXIF orientation is baked into the pixels of a re-encoded photo`() {
        val file = File.createTempFile("attachment", ".jpg")
        try {
            file.writeBytes(bitmap(3000, 2000).encode(Bitmap.CompressFormat.JPEG, 90))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = file.readBytes()
            assertThat(ExifInterface(original.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)

            val prepared = AttachmentImages.prepare(original, "image/jpeg")

            // Landscape 3:2 rotated by 90° comes out portrait 2:3, so the long edge is now the height.
            val (w, h) = dimensions(prepared.bytes)
            assertThat(h).isEqualTo(AttachmentImages.MAX_EDGE_PX)
            assertThat(w).isEqualTo(1045)
        } finally {
            file.delete()
        }
    }
}
