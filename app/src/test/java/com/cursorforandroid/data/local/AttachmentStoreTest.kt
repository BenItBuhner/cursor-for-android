package com.cursorforandroid.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private fun noisyPng(width: Int, height: Int): PromptImage {
        val random = Random(7)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
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
    fun `discarding a staged prompt removes its files`() = runBlocking {
        val staged = store.stage(listOf(png(64, 64)))
        val path = staged.attachments.single().path
        store.discard(staged)
        assertThat(File(path).exists()).isFalse()
        assertThat(File(path).parentFile?.exists()).isFalse()
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
