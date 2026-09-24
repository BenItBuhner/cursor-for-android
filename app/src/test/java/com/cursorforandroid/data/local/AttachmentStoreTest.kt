package com.cursorforandroid.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Native graphics so [BitmapFactory] and [Bitmap.compress] really decode and encode. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttachmentStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AttachmentStore(context)
    private val root = File(context.filesDir, "attachments")

    @After
    fun cleanUp() = runBlocking { store.clear() }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, mimeType: String): PromptImage {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, 90, out))
        return PromptImage(out.toByteArray(), mimeType)
    }

    private fun png(width: Int, height: Int, color: Int = Color.RED): PromptImage =
        encode(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, Bitmap.CompressFormat.PNG, "image/png")

    private fun jpeg(width: Int, height: Int): PromptImage =
        encode(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }, Bitmap.CompressFormat.JPEG, "image/jpeg")

    /** Random opaque pixels compress to nothing, so a modest bitmap becomes a multi-megabyte PNG. */
    private fun noisyPng(width: Int, height: Int, tweak: (IntArray, Int, Int) -> Unit = { _, _, _ -> }): PromptImage {
        val random = Random(7)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        tweak(pixels, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        return encode(bitmap, Bitmap.CompressFormat.PNG, "image/png")
    }

    private fun dimensions(path: String): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun `a staged prompt is moved under its run on commit and comes back keyed by run`() = runBlocking {
        val staged = store.stage(listOf(png(400, 800), png(300, 300)))
        assertThat(staged.attachments).hasSize(2)
        staged.attachments.forEach { assertThat(File(it.path).isFile).isTrue() }
        assertThat(staged.attachments[0].width to staged.attachments[0].height).isEqualTo(400 to 800)
        assertThat(staged.attachments[0].aspectRatio).isEqualTo(0.5f)

        val kept = store.commit("bc-1", "run-7", staged)
        assertThat(kept).hasSize(2)
        kept.forEach { assertThat(File(it.path).isFile).isTrue() }
        staged.attachments.forEach { assertThat(File(it.path).exists()).isFalse() }
        assertThat(File(root, ".staging").listFiles().orEmpty()).isEmpty()

        assertThat(store.forAgent("bc-1")).isEqualTo(mapOf("run-7" to kept))
        assertThat(store.forAgent("bc-2")).isEmpty()
    }

    @Test
    fun `files of any type are staged byte for byte under their own names and come back after the images, with name, type and size`() = runBlocking {
        val pdf = PromptFile(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D), "Q3 report (final).pdf", "application/pdf")
        val zip = PromptFile(ByteArray(3) { 1 }, "bundle.zip", "application/zip")
        val staged = store.stage(listOf(png(64, 64)), listOf(pdf, zip))
        assertThat(staged.attachments).hasSize(3)
        val files = staged.attachments.filter { it.isFile }
        assertThat(files.map { it.name }).containsExactly("Q3 report (final).pdf", "bundle.zip").inOrder()
        assertThat(files.map { it.mimeType }).containsExactly("application/pdf", "application/zip").inOrder()
        assertThat(files.map { it.sizeBytes }).containsExactly(5L, 3L).inOrder()
        // Kept as picked, under a name the filesystem takes, so the viewer reads the type off the extension.
        assertThat(File(files[0].path).name).isEqualTo("f0-Q3_report__final_.pdf")
        assertThat(File(files[0].path).readBytes()).isEqualTo(pdf.bytes)

        val kept = store.commit("bc-1", "run-8", staged)
        assertThat(kept.filter { it.isFile }.map { it.name }).containsExactly("Q3 report (final).pdf", "bundle.zip").inOrder()
        kept.forEach { assertThat(File(it.path).isFile).isTrue() }
        val read = store.forAgent("bc-1").getValue("run-8")
        assertThat(read.filterNot { it.isFile }).hasSize(1)
        assertThat(read.filter { it.isFile }.map { it.name to it.sizeBytes }).containsExactly("Q3 report (final).pdf" to 5L, "bundle.zip" to 3L).inOrder()
        assertThat(read.filter { it.isFile }.map { it.kind }).containsExactly(PromptFileKind.Pdf, PromptFileKind.Archive).inOrder()
        assertThat(read.indexOfFirst { it.isFile }).isEqualTo(1)
    }

    @Test
    fun `a picture attached as a file is kept as a preview so the transcript shows it inline, and a video stays a file`() = runBlocking {
        val photo = PromptFile(png(64, 48).bytes, "IMG_20260917_074100.png", "image/png")
        val clip = PromptFile(ByteArray(6) { 3 }, "VID_20260917_074200.mp4", "video/mp4")
        val junk = PromptFile(byteArrayOf(1, 2, 3), "broken.png", "image/png")

        val kept = store.save("bc-4", "run-2", emptyList(), listOf(photo, clip, junk))

        assertThat(kept).hasSize(3)
        // The photo: an image attachment with its dimensions, no name, decodable by the bubble like a pasted one.
        assertThat(kept[0].isFile).isFalse()
        assertThat(kept[0].width to kept[0].height).isEqualTo(64 to 48)
        // The clip: a card with its name, type and size, opened by the system viewer.
        assertThat(kept[1].isFile).isTrue()
        assertThat(kept[1].name).isEqualTo("VID_20260917_074200.mp4")
        assertThat(kept[1].kind).isEqualTo(PromptFileKind.Video)
        // Bytes that call themselves an image but will not decode are kept as the file they are.
        assertThat(kept[2].isFile).isTrue()
        assertThat(kept[2].name).isEqualTo("broken.png")
        assertThat(store.forAgent("bc-4").getValue("run-2").map { it.isFile }).containsExactly(false, true, true).inOrder()
    }

    @Test
    fun `a prompt of files alone stages, and a save files them under the run in one step`() = runBlocking {
        val kept = store.save("bc-3", "run-1", emptyList(), listOf(PromptFile(byteArrayOf(7), "notes.txt", "text/plain")))
        assertThat(kept.single().isFile).isTrue()
        assertThat(store.forAgent("bc-3").getValue("run-1").single().name).isEqualTo("notes.txt")
    }

    @Test
    fun `discarding a staged prompt removes its files`() = runBlocking {
        val staged = store.stage(listOf(png(64, 64)))
        val path = staged.attachments.single().path
        store.discard(staged)
        assertThat(File(path).exists()).isFalse()
        assertThat(File(path).parentFile?.exists()).isFalse()
    }

    @Test
    fun `a staged prompt a dead process never committed or discarded is swept after a week, a recent one is not`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val orphan = store.stage(listOf(png(64, 64)))
        val orphanDir = File(orphan.attachments.single().path).parentFile!!
        orphanDir.setLastModified(now - 8 * 24 * 60 * 60 * 1000L)
        val waiting = store.stage(listOf(png(32, 32)))
        store.commit("bc-1", "run-1", store.stage(listOf(png(16, 16))))
        File(root, "bc-1/run-1").setLastModified(now - 30 * 24 * 60 * 60 * 1000L)

        store.sweepStaging(now)

        assertThat(orphanDir.exists()).isFalse()
        assertThat(store.staged(waiting.attachments).attachments).isEqualTo(waiting.attachments)
        assertThat(store.forAgent("bc-1").keys).containsExactly("run-1")
    }

    @Test
    fun `images already within bounds are kept byte for byte`() = runBlocking {
        val png = png(400, 800)
        val jpeg = jpeg(300, 200)
        val gif = PromptImage(TINY_GIF, "image/gif")
        val kept = store.save("bc-1", "run-1", listOf(png, jpeg, gif))
        assertThat(kept.map { File(it.path).extension }).containsExactly("png", "jpg", "gif").inOrder()
        assertThat(File(kept[0].path).readBytes()).isEqualTo(png.bytes)
        assertThat(File(kept[1].path).readBytes()).isEqualTo(jpeg.bytes)
        assertThat(File(kept[2].path).readBytes()).isEqualTo(TINY_GIF)
        assertThat(kept[0].width to kept[0].height).isEqualTo(400 to 800)
        assertThat(kept[1].width to kept[1].height).isEqualTo(300 to 200)
        assertThat(kept[2].width to kept[2].height).isEqualTo(1 to 1)
    }

    @Test
    fun `oversized images are shrunk to 1600px and re-encoded, as png when they have transparent pixels`() = runBlocking {
        val kept = store.save("bc-1", "run-1", listOf(png(3200, 800), png(3200, 200, Color.TRANSPARENT)))
        assertThat(kept[0].width to kept[0].height).isEqualTo(1600 to 400)
        assertThat(dimensions(kept[0].path)).isEqualTo(1600 to 400)
        assertThat(kept[0].path).endsWith(".jpg")
        assertThat(kept[1].width to kept[1].height).isEqualTo(1600 to 100)
        assertThat(kept[1].path).endsWith(".png")
    }

    @Test
    fun `an image within bounds but heavier than 2 MB is re-encoded rather than copied`() = runBlocking {
        val noisy = noisyPng(1200, 1200)
        assertThat(noisy.sizeBytes).isGreaterThan(2 * 1024 * 1024)
        val kept = store.save("bc-1", "run-1", listOf(noisy)).single()
        assertThat(kept.path).endsWith(".jpg")
        assertThat(kept.width to kept.height).isEqualTo(1200 to 1200)
        assertThat(File(kept.path).length()).isLessThan(noisy.sizeBytes.toLong())
    }

    @Test
    fun `transparency is found wherever it is, including the last pixel`() = runBlocking {
        // Read in bands, the check has to reach the far corner of an otherwise opaque image to call it transparent.
        val corner = noisyPng(1200, 1200) { pixels, width, height -> pixels[width * height - 1] = Color.TRANSPARENT }
        val opaque = noisyPng(1200, 1200)
        assertThat(corner.sizeBytes).isGreaterThan(2 * 1024 * 1024)

        val kept = store.save("bc-1", "run-1", listOf(corner, opaque))

        assertThat(kept[0].path).endsWith(".png")
        assertThat(kept[1].path).endsWith(".jpg")
        assertThat(dimensions(kept[0].path)).isEqualTo(1200 to 1200)
    }

    @Test
    fun `undecodable bytes are skipped and a prompt of only junk stages nothing`() = runBlocking {
        val junk = PromptImage("not an image".toByteArray(), "image/png")
        val none = store.stage(listOf(junk))
        assertThat(none.attachments).isEmpty()
        assertThat(File(root, ".staging").listFiles().orEmpty()).isEmpty()
        assertThat(store.commit("bc-1", "run-1", none)).isEmpty()
        assertThat(store.forAgent("bc-1")).isEmpty()

        val mixed = store.stage(listOf(junk, png(20, 20)))
        assertThat(mixed.attachments).hasSize(1)
        store.discard(mixed)
    }

    @Test
    fun `nothing is staged for a prompt without images`() = runBlocking {
        val staged = store.stage(emptyList())
        assertThat(staged.attachments).isEmpty()
        assertThat(store.commit("bc-1", "run-1", staged)).isEmpty()
        assertThat(root.exists()).isFalse()
    }

    @Test
    fun `deleting an agent removes only its attachments`() = runBlocking {
        store.save("bc-1", "run-1", listOf(png(10, 10)))
        store.save("bc-2", "run-2", listOf(png(10, 10)))
        store.delete("bc-1")
        assertThat(store.forAgent("bc-1")).isEmpty()
        assertThat(store.forAgent("bc-2")).hasSize(1)
    }

    private companion object {
        /** The classic 1x1 transparent GIF89a. */
        val TINY_GIF: ByteArray = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
    }

    @Test
    fun `ids never escape the attachments directory`() = runBlocking {
        val kept = store.save("..", "../..", listOf(png(10, 10)))
        kept.forEach { assertThat(File(it.path).canonicalPath).startsWith(root.canonicalPath + File.separator) }
        assertThat(store.forAgent("..").keys).containsExactly("../..")
        store.delete("..")
        assertThat(store.forAgent("..")).isEmpty()
        assertThat(root.isDirectory).isTrue()
        assertThat(context.filesDir.isDirectory).isTrue()
    }
}
