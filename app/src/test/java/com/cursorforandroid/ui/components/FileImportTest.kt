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
 * The two pickers' imports. The Files picker (Extended mode) turns a selection of any type into [PendingFile]s with
 * the provider's name and type — an image with its thumbnail, counted against the image slots. The photo picker's
 * import is the image-only inline path in the default mode and the same real-file path in Extended mode, videos
 * included. The 15 MB cap and the count caps are applied before a byte is uploaded, in the words of what was picked.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FileImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val none = AttachmentCounts(images = 0, files = 0)

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
        val imported = importFiles(context, listOf(temp("Q3 report.pdf", pdf), temp("trace.har", "{}".toByteArray())), none)

        assertThat(imported.error).isNull()
        assertThat(imported.images).isEmpty()
        assertThat(imported.files.map { it.file.name }).containsExactly("Q3 report.pdf", "trace.har").inOrder()
        assertThat(imported.files[0].file.mimeType).isEqualTo("application/pdf")
        assertThat(imported.files[0].file.bytes).isEqualTo(pdf)
        assertThat(imported.files[0].file.kind).isEqualTo(PromptFileKind.Pdf)
        assertThat(imported.files[0].thumbnail).isNull()
        assertThat(imported.files[1].file.mimeType).isEqualTo("application/json")
        assertThat(imported.files[1].file.kind).isEqualTo(PromptFileKind.Code)
        assertThat(imported.files.map { it.id }.toSet()).hasSize(2)
    }

    @Test
    fun `an image picked through Files is a real file with a thumbnail, named as an image on the prompt`() {
        val imported = importFiles(context, listOf(temp("shot.png", png()), temp("notes.txt", "hi".toByteArray())), none)

        assertThat(imported.error).isNull()
        assertThat(imported.images).isEmpty()
        assertThat(imported.files.map { it.file.name }).containsExactly("shot.png", "notes.txt").inOrder()
        val shot = imported.files[0]
        assertThat(shot.isImage).isTrue()
        assertThat(shot.file.mimeType).isEqualTo("image/png")
        assertThat(shot.thumbnail).isNotNull()
        assertThat(imported.files[1].isImage).isFalse()
    }

    @Test
    fun `the photo picker's import is the inline image path in the default mode and the real-file path in Extended mode`() {
        val uris = listOf(temp("IMG_0001.png", png()), temp("VID_0002.mp4", ByteArray(64) { 0x1F }))

        val default = importMedia(context, uris, extended = false, counts = none)
        assertThat(default.images).hasSize(1)
        assertThat(default.images.single().image.mimeType).isEqualTo("image/png")
        assertThat(default.files).isEmpty()
        // The video the image-only picker would never return is refused as not an image, as a paste of one is.
        assertThat(default.error).contains("Unsupported image type")

        val extended = importMedia(context, uris, extended = true, counts = none)
        assertThat(extended.error).isNull()
        assertThat(extended.images).isEmpty()
        assertThat(extended.files.map { it.file.name }).containsExactly("IMG_0001.png", "VID_0002.mp4").inOrder()
        assertThat(extended.files[0].isImage).isTrue()
        assertThat(extended.files[0].thumbnail).isNotNull()
        assertThat(extended.files[1].isImage).isFalse()
        assertThat(extended.files[1].file.mimeType).isEqualTo("video/mp4")
        assertThat(extended.files[1].file.kind).isEqualTo(PromptFileKind.Video)
    }

    @Test
    fun `a file over 15 MB is refused before it is in memory, in the words of its kind, and the rest still load`() {
        fun huge(name: String): File = File.createTempFile("huge", name).also { file ->
            file.outputStream().use { out ->
                val chunk = ByteArray(1024 * 1024)
                repeat(15) { out.write(chunk) }
                out.write(1)
            }
        }
        val video = huge(".mp4")
        val archive = huge(".zip")

        val clip = importFiles(context, listOf(Uri.fromFile(video), temp("small.txt", "ok".toByteArray())), none)
        assertThat(clip.error).isEqualTo("Videos must be 15 MB or smaller.")
        assertThat(clip.files.map { it.file.name }).containsExactly("small.txt")

        val bundle = importFiles(context, listOf(Uri.fromFile(archive)), none)
        assertThat(bundle.error).isEqualTo(PromptFile.TOO_LARGE_MESSAGE)
        assertThat(bundle.files).isEmpty()
        video.delete()
        archive.delete()
    }

    @Test
    fun `exactly 15 MB is within the cap`() {
        val atCap = File.createTempFile("atcap", ".bin")
        atCap.outputStream().use { out -> repeat(15) { out.write(ByteArray(1024 * 1024)) } }
        val imported = importFiles(context, listOf(Uri.fromFile(atCap)), none)
        assertThat(imported.error).isNull()
        assertThat(imported.files.single().file.sizeBytes).isEqualTo(15 * 1024 * 1024)
        atCap.delete()
    }

    @Test
    fun `a sixth file is refused with the count's message, and the image slots are counted apart`() {
        val files = List(3) { temp("f$it.txt", "x".toByteArray()) }
        val imported = importFiles(context, files, AttachmentCounts(images = 0, files = PromptFile.MAX_COUNT - 1))
        assertThat(imported.files).hasSize(1)
        assertThat(imported.error).isEqualTo(fileLimitMessage())

        val full = importFiles(context, listOf(temp("shot.png", png())), AttachmentCounts(images = PromptImage.MAX_COUNT, files = 0))
        assertThat(full.files).isEmpty()
        assertThat(full.error).isEqualTo(attachmentLimitMessage())

        // Five images already attached leave room for a video, which is a file, not an image.
        val video = importFiles(context, listOf(temp("clip.mp4", ByteArray(8))), AttachmentCounts(images = PromptImage.MAX_COUNT, files = 0))
        assertThat(video.files).hasSize(1)
        assertThat(video.error).isNull()
    }

    @Test
    fun `the slots cut a list of files the same way, images and other files apart`() {
        val image = { n: Int -> PendingFile("i$n", PromptFile(ByteArray(1), "i$n.png", "image/png")) }
        val doc = { n: Int -> PendingFile("d$n", PromptFile(ByteArray(1), "d$n.pdf", "application/pdf")) }
        val mixed = (1..7).flatMap { listOf(image(it), doc(it)) }
        val kept = mixed.withinSlots(imagesElsewhere = 2)
        assertThat(kept.filter { it.isImage }.map { it.id }).containsExactly("i1", "i2", "i3").inOrder()
        assertThat(kept.filterNot { it.isImage }.map { it.id }).containsExactly("d1", "d2", "d3", "d4", "d5").inOrder()
    }

    @Test
    fun `a selection that cannot be opened says so and the others still come through`() {
        val gone = Uri.fromFile(File(context.cacheDir, "nowhere/missing.pdf"))
        val imported = importFiles(context, listOf(gone, temp("here.txt", "x".toByteArray())), none)
        assertThat(imported.files.map { it.file.name }).containsExactly("here.txt")
        assertThat(imported.error).isNotNull()
    }

    @Test
    fun `a pick the provider leaves nameless is named the way a gallery names an export`() {
        assertThat(generatedName("image/jpeg", nowMillis = 0L)).matches("IMG_\\d{8}_\\d{6}\\.jpg")
        assertThat(generatedName("video/quicktime", nowMillis = 0L)).matches("VID_\\d{8}_\\d{6}\\.mov")
        assertThat(generatedName("video/mp4", nowMillis = 0L)).endsWith(".mp4")
        assertThat(generatedName("audio/mpeg", nowMillis = 0L)).startsWith("AUD_")
        assertThat(generatedName(null, nowMillis = 0L)).matches("FILE_\\d{8}_\\d{6}\\.bin")
        assertThat(generatedName("image/*", nowMillis = 0L)).endsWith(".bin")
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
