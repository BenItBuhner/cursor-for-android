package com.cursorforandroid.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The document picker's import: a selection of any type becomes [PendingFile]s with the provider's name and type,
 * images among them become image attachments, and the 15 MB cap and the count caps are applied before a byte is uploaded.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FileImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun temp(name: String, bytes: ByteArray): Uri {
        val dir = File.createTempFile("picked", "").let { it.delete(); it.mkdirs(); it }
        return Uri.fromFile(File(dir, name).also { it.writeBytes(bytes) })
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `a document keeps its display name, its bytes and a type read off the name when the provider has none`() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31)
        val imported = importFiles(context, listOf(temp("Q3 report.pdf", pdf), temp("trace.har", "{}".toByteArray())), currentFileCount = 0, currentImageCount = 0)

        assertThat(imported.error).isNull()
        assertThat(imported.images).isEmpty()
        assertThat(imported.files.map { it.file.name }).containsExactly("Q3 report.pdf", "trace.har").inOrder()
        assertThat(imported.files[0].file.mimeType).isEqualTo("application/pdf")
        assertThat(imported.files[0].file.bytes).isEqualTo(pdf)
        assertThat(imported.files[0].file.kind).isEqualTo(PromptFileKind.Pdf)
        assertThat(imported.files[1].file.mimeType).isEqualTo("application/json")
        assertThat(imported.files[1].file.kind).isEqualTo(PromptFileKind.Code)
        assertThat(imported.files.map { it.id }.toSet()).hasSize(2)
    }

    @Test
    fun `an image picked as a file joins the image strip, so it renders inline as it always has`() {
        val imported = importFiles(context, listOf(temp("shot.png", png()), temp("notes.txt", "hi".toByteArray())), currentFileCount = 0, currentImageCount = 0)

        assertThat(imported.error).isNull()
        assertThat(imported.images).hasSize(1)
        assertThat(imported.images.single().image.mimeType).isEqualTo("image/png")
        assertThat(imported.files.map { it.file.name }).containsExactly("notes.txt")
    }

    @Test
    fun `a file over 15 MB is refused before it is in memory, with the guard's words, and the rest still load`() {
        val huge = File.createTempFile("huge", ".bin")
        huge.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(15) { out.write(chunk) }
            out.write(1)
        }
        val imported = importFiles(context, listOf(Uri.fromFile(huge), temp("small.txt", "ok".toByteArray())), currentFileCount = 0, currentImageCount = 0)

        assertThat(imported.error).isEqualTo(PromptFile.TOO_LARGE_MESSAGE)
        assertThat(imported.files.map { it.file.name }).containsExactly("small.txt")
        huge.delete()
    }

    @Test
    fun `exactly 15 MB is within the cap`() {
        val atCap = File.createTempFile("atcap", ".bin")
        atCap.outputStream().use { out -> repeat(15) { out.write(ByteArray(1024 * 1024)) } }
        val imported = importFiles(context, listOf(Uri.fromFile(atCap)), currentFileCount = 0, currentImageCount = 0)
        assertThat(imported.error).isNull()
        assertThat(imported.files.single().file.sizeBytes).isEqualTo(15 * 1024 * 1024)
        atCap.delete()
    }

    @Test
    fun `a sixth file is refused with the count's message, and the image slots are counted apart`() {
        val files = List(3) { temp("f$it.txt", "x".toByteArray()) }
        val imported = importFiles(context, files, currentFileCount = PromptFile.MAX_COUNT - 1, currentImageCount = 0)
        assertThat(imported.files).hasSize(1)
        assertThat(imported.error).isEqualTo(fileLimitMessage())

        val full = importFiles(context, listOf(temp("shot.png", png())), currentFileCount = 0, currentImageCount = PromptImage.MAX_COUNT)
        assertThat(full.images).isEmpty()
        assertThat(full.error).isEqualTo(attachmentLimitMessage())
    }

    @Test
    fun `a selection that cannot be opened says so and the others still come through`() {
        val gone = Uri.fromFile(File(context.cacheDir, "nowhere/missing.pdf"))
        val imported = importFiles(context, listOf(gone, temp("here.txt", "x".toByteArray())), currentFileCount = 0, currentImageCount = 0)
        assertThat(imported.files.map { it.file.name }).containsExactly("here.txt")
        assertThat(imported.error).isNotNull()
    }

    @Test
    fun `every kind has a glyph of its own`() {
        val icons = PromptFileKind.entries.map { it.icon() }
        assertThat(icons).hasSize(PromptFileKind.entries.size)
        assertThat(PromptFileKind.Audio.icon()).isEqualTo(CursorIcons.Music)
        assertThat(PromptFileKind.Archive.icon()).isEqualTo(CursorIcons.FileArchive)
        assertThat(PromptFileKind.Pdf.icon()).isEqualTo(CursorIcons.FileText)
        assertThat(PromptFileKind.Other.icon()).isEqualTo(CursorIcons.File)
    }
}
