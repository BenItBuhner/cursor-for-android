package com.cursorforandroid.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Picker and clipboard pastes share [importAttachments]. A screenshot from Android's clipboard often arrives as a
 * wildcard image type or with no type, so magic bytes have to stand in for the resolver.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttachmentImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun png(width: Int = 32, height: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(80, 160, 240))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `magic bytes recognise the types the API accepts`() {
        assertThat(sniffImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png")
        assertThat(sniffImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))).isEqualTo("image/jpeg")
        assertThat(sniffImageMime(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))).isEqualTo("image/gif")
        val webp = ByteArray(12)
        "RIFF".forEachIndexed { i, c -> webp[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> webp[8 + i] = c.code.toByte() }
        assertThat(sniffImageMime(webp)).isEqualTo("image/webp")
        assertThat(sniffImageMime("not an image".toByteArray())).isNull()
    }

    @Test
    fun `a vague or missing clipboard type is recovered from the bytes`() {
        val bytes = png()
        assertThat(resolveImageMime("image/*", bytes)).isEqualTo("image/png")
        assertThat(resolveImageMime(null, bytes)).isEqualTo("image/png")
        assertThat(resolveImageMime("image/png", bytes)).isEqualTo("image/png")
        assertThat(resolveImageMime("image/bmp", "BM".toByteArray())).isNull()
    }

    /** [count] temporary PNGs, as the clipboard or the picker hands them over: content the resolver can open. */
    private fun pngUris(count: Int): List<Uri> = List(count) {
        File.createTempFile("clipboard", ".png").also { file -> file.writeBytes(png()) }.let(Uri::fromFile)
    }

    @Test
    fun `a sixth pasted image is refused with the same limit as the picker`() {
        val imported = importAttachments(context, pngUris(2), currentCount = PromptImage.MAX_COUNT)
        assertThat(imported.attachments).isEmpty()
        assertThat(imported.error).isEqualTo(attachmentLimitMessage())
    }

    @Test
    fun `overflow keeps the ones that fit and names the cap`() {
        val imported = importAttachments(context, pngUris(3), currentCount = PromptImage.MAX_COUNT - 1)
        assertThat(imported.attachments).hasSize(1)
        assertThat(imported.error).isEqualTo(attachmentLimitMessage())
    }

    /**
     * The paste path reads through the picker's bounded reader, so a provider that streams a huge original without
     * declaring its size is refused at the cap rather than after all of it — which is what pasting a cloud-backed
     * image used to cost, on the main thread.
     */
    @Test
    fun `a pasted stream that declares no size is refused at the cap without being drained`() {
        val uri = Uri.parse("content://com.cursorforandroid.test/huge.png")
        val stream = CountingStream(100L * 1024 * 1024)
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)

        val imported = importAttachments(context, listOf(uri), currentCount = 0)

        assertThat(imported.attachments).isEmpty()
        assertThat(imported.error).isEqualTo("Images must be 15 MB or smaller.")
        assertThat(stream.consumed).isEqualTo(PromptImage.MAX_BYTES + 1)
    }

    /** A stream of [length] zero bytes that allocates nothing, counting what was actually taken from it. */
    private class CountingStream(private val length: Long) : InputStream() {
        var consumed = 0L
            private set

        override fun read(): Int {
            if (consumed >= length) return -1
            consumed++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= length) return -1
            val n = minOf(len.toLong(), length - consumed).toInt()
            consumed += n
            return n
        }
    }

    @Test
    fun `an image clip item is taken and a text clip is left alone`() {
        val file = File.createTempFile("clipboard", ".png")
        file.writeBytes(png())
        val uri = Uri.fromFile(file)
        val imageClip = ClipData(ClipDescription("pasted", arrayOf("image/png")), ClipData.Item(uri))
        assertThat(imageUrisFromClip(context.contentResolver, imageClip)).containsExactly(uri)

        val textClip = ClipData.newPlainText("text", "hello")
        assertThat(imageUrisFromClip(context.contentResolver, textClip)).isEmpty()
        file.delete()
    }

    @Test
    fun `a file URI from the clipboard loads the same way the picker does`() {
        val file = File.createTempFile("clipboard", ".png")
        try {
            val bytes = png()
            file.writeBytes(bytes)
            val uri = Uri.fromFile(file)
            val imported = importAttachments(context, listOf(uri), currentCount = 0)
            assertThat(imported.error).isNull()
            assertThat(imported.attachments).hasSize(1)
            assertThat(imported.attachments.single().image.mimeType).isEqualTo("image/png")
        } finally {
            file.delete()
        }
    }
}
