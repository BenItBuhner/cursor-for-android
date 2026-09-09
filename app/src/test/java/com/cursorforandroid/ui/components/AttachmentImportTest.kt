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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Picker and clipboard pastes share [importAttachments] / [importPayloads]. A screenshot from Android's clipboard
 * often arrives as a wildcard image type or with no type, so magic bytes have to stand in for the resolver.
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

    @Test
    fun `payloads become attachments the composer can send`() {
        val imported = importPayloads(listOf(ImagePayload("paste@1", png(), "image/*")), currentCount = 0)
        assertThat(imported.error).isNull()
        assertThat(imported.attachments).hasSize(1)
        assertThat(PromptImage.isSupported(imported.attachments.single().image.mimeType)).isTrue()
    }

    @Test
    fun `a sixth pasted image is refused with the same limit as the picker`() {
        val payloads = List(2) { i -> ImagePayload("paste@$i", png(), "image/png") }
        val imported = importPayloads(payloads, currentCount = PromptImage.MAX_COUNT)
        assertThat(imported.attachments).isEmpty()
        assertThat(imported.error).isEqualTo(attachmentLimitMessage())
    }

    @Test
    fun `overflow keeps the ones that fit and names the cap`() {
        val payloads = List(3) { i -> ImagePayload("paste@$i", png(), "image/png") }
        val imported = importPayloads(payloads, currentCount = PromptImage.MAX_COUNT - 1)
        assertThat(imported.attachments).hasSize(1)
        assertThat(imported.error).isEqualTo(attachmentLimitMessage())
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
