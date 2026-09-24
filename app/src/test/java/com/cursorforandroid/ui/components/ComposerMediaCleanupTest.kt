package com.cursorforandroid.ui.components

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The composer's on-device copies of its media go once nothing shows them: removed, sent, or left over from a day ago. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposerMediaCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { folder.newFolder("composer-media") }

    private fun item(id: String) = ComposerMediaItem(id, ByteArray(1_000) { it.toByte() }, "image/png", name = null, thumbnail = null, isVideo = false, durationMs = null)

    private fun copies(): List<String> = dir.listFiles()!!.filter { it.isFile }.map { it.name }

    private fun copyOf(previews: ComposerMediaPreviews, id: String): File = File(android.net.Uri.parse(previews.src(id, "image/png")).path!!)

    /** The previews hop to the main thread and back, so the main looper is pumped while they run. */
    private fun <T> onMain(block: suspend () -> T): T {
        var result: Result<T>? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { result = runCatching { block() } }
        val deadline = System.currentTimeMillis() + 20_000
        while (result == null) {
            check(System.currentTimeMillis() < deadline) { "the previews never answered" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result!!.getOrThrow()
    }

    @Test
    fun `a copy goes as soon as its item is removed from the row`() {
        val previews = ComposerMediaPreviews(dir, releaseAfterMs = 0)
        onMain { previews.ensureAll(listOf(item("a"), item("b"))) }
        assertThat(copies()).hasSize(2)

        onMain { previews.ensureAll(listOf(item("a"))) }

        assertThat(copyOf(previews, "a").isFile).isTrue()
        assertThat(copyOf(previews, "b").exists()).isFalse()
    }

    @Test
    fun `the row's copies go once it is released, the message sent`() {
        val previews = ComposerMediaPreviews(dir, releaseAfterMs = 0)
        onMain { previews.ensureAll(listOf(item("a"), item("b"))) }
        onMain { previews.ensure("c", "image/png", item("c").bytes) }
        assertThat(copies()).hasSize(3)

        onMain { previews.release().join() }

        assertThat(copies()).isEmpty()
    }

    @Test
    fun `a copy another row still holds outlives this row's release`() {
        val chat = ComposerMediaPreviews(dir, releaseAfterMs = 0)
        val newChat = ComposerMediaPreviews(dir, releaseAfterMs = 0)
        onMain { chat.ensureAll(listOf(item("a"), item("b"))) }
        onMain { newChat.ensureAll(listOf(item("a"))) }

        onMain { chat.release().join() }
        assertThat(copyOf(chat, "a").isFile).isTrue()
        assertThat(copyOf(chat, "b").exists()).isFalse()

        onMain { newChat.release().join() }
        assertThat(copies()).isEmpty()
    }

    @Test
    fun `the startup sweep drops the copies nobody asked for in a day, and keeps the recent ones`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1000L
        File(dir, "m-old.png").apply { writeBytes(ByteArray(10)); setLastModified(twoDaysAgo) }
        File(dir, "m-old.mp4.part").apply { writeBytes(ByteArray(10)); setLastModified(twoDaysAgo) }
        File(dir, "m-fresh.png").apply { writeBytes(ByteArray(10)); setLastModified(now - 60_000) }

        ComposerMediaPreviews.sweep(dir, now)

        assertThat(copies()).containsExactly("m-fresh.png")
    }
}
