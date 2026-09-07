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

    private fun png(width: Int, height: Int, color: Int = Color.RED): PromptImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        return PromptImage(out.toByteArray(), "image/png")
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
    fun `previews are shrunk to 1600px and written as jpeg unless they have transparent pixels`() = runBlocking {
        val kept = store.save("bc-1", "run-1", listOf(png(3200, 800), png(100, 50, Color.TRANSPARENT), png(50, 50)))
        assertThat(kept[0].width to kept[0].height).isEqualTo(1600 to 400)
        assertThat(dimensions(kept[0].path)).isEqualTo(1600 to 400)
        assertThat(kept[0].path).endsWith(".jpg")
        assertThat(kept[1].path).endsWith(".png")
        assertThat(kept[2].width to kept[2].height).isEqualTo(50 to 50)
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
