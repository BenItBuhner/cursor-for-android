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
    fun `a vague, missing or wrong clipboard type is replaced by the one the bytes are`() {
        val bytes = png()
        assertThat(loadAttachment(bytes, "image/*", "a").getOrThrow().image.mimeType).isEqualTo("image/png")
        assertThat(loadAttachment(bytes, null, "b").getOrThrow().image.mimeType).isEqualTo("image/png")
        assertThat(loadAttachment(bytes, "image/png; charset=binary", "c").getOrThrow().image.mimeType).isEqualTo("image/png")
        // A keyboard sticker declared `image/png` over JPEG bytes: the model refuses a type that is not the bytes'.
        val jpeg = ByteArrayOutputStream().also { Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        assertThat(loadAttachment(jpeg, "image/png", "d").getOrThrow().image.mimeType).isEqualTo("image/jpeg")
    }

    /** A paste that hands over the picture's encoding instead of the picture would otherwise reach the agent as text to decode. */
    @Test
    fun `a paste carrying base64 or a data URI goes out as the picture itself`() {
        val bytes = png()
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        for (payload in listOf(base64, "data:image/png;base64,$base64")) {
            val image = loadAttachment(payload.toByteArray(), "image/png", "p").getOrThrow().image
            assertThat(image.bytes).isEqualTo(bytes)
            assertThat(image.mimeType).isEqualTo("image/png")
            assertThat(image.base64).isEqualTo(base64)
        }
    }

    @Test
    fun `an image that will not decode is refused inline instead of being sent as it is`() {
        val result = loadAttachment(png().copyOf(30), "image/png", "x")
        assertThat(result.exceptionOrNull()?.message).isEqualTo("Couldn't read this PNG image. Re-save it as PNG or JPEG and attach it again.")
        assertThat(loadAttachment("BM".toByteArray(), "image/bmp", "y").exceptionOrNull()?.message).isEqualTo("Unsupported image type (image/bmp). Use PNG, JPEG, GIF or WebP.")
    }

    /** Before, a pasted BMP was refused as an unsupported type; it now attaches as the JPEG the agent can read. */
    @Test
    fun `a pasted BMP attaches as an image the API takes`() {
        val bmp = java.nio.ByteBuffer.allocate(54 + 16 * 4 * 3).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + 16 * 4 * 3).putInt(0).putInt(54)
            putInt(40).putInt(16).putInt(4).putShort(1).putShort(24).putInt(0).putInt(16 * 4 * 3).putInt(2835).putInt(2835).putInt(0).putInt(0)
            repeat(16 * 4) { put(0x20).put(0x80.toByte()).put(0xF0.toByte()) }
        }.array()
        val attached = loadAttachment(bmp, "image/bmp", "z").getOrThrow()
        assertThat(attached.image.mimeType).isEqualTo("image/jpeg")
        assertThat(attached.thumbnail).isNotNull()
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
