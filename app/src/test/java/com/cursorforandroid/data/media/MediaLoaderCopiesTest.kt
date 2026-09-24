package com.cursorforandroid.data.media

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.repo.ArtifactRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** The copies the loader makes for other apps and the viewer are held to a budget, and a day's leftovers go at start. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaLoaderCopiesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val hour = 60 * 60 * 1000L
    private val mib = 1L shl 20

    @Before
    fun clean() {
        File(context.cacheDir, MediaLoader.MEDIA_DIR).deleteRecursively()
    }

    /** Sparse: as long as a real copy to the bounds, without writing the bytes. */
    private fun File.copy(name: String, bytes: Long, modifiedAt: Long): File = File(apply { mkdirs() }, name).apply {
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        setLastModified(modifiedAt)
    }

    private fun File.names(): List<String> = listFiles()!!.filter { it.isFile }.map { it.name }

    /** The loader confines its calls to the main thread, so the main looper is pumped while one runs. */
    private fun <T> onMain(block: suspend () -> T): T {
        var result: Result<T>? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { result = runCatching { block() } }
        val deadline = System.currentTimeMillis() + 20_000
        while (result == null) {
            check(System.currentTimeMillis() < deadline) { "the loader never answered" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result!!.getOrThrow()
    }

    @Test
    fun `a new copy for the viewer pushes out the least recently used, never one still in use`() {
        val opened = File(context.cacheDir, "${MediaLoader.MEDIA_DIR}/opened")
        opened.copy("oldest.mp4", 20 * mib, now - 3 * hour)
        opened.copy("older.png", 5 * mib, now - 2 * hour)
        opened.copy("on-screen.mp4", 20 * mib, now - 5 * 60_000L)
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }))

        val kept = File(onMain { loader.keep(ByteArray(1_000) { it.toByte() }, "new.png") }.removePrefix("file://"))

        assertThat(kept.isFile).isTrue()
        assertThat(opened.names()).containsExactly("older.png", "on-screen.mp4", kept.name)
    }

    @Test
    fun `the startup sweep drops the copies nothing asked for in a day and holds the rest to the bound`() {
        val cache = folder.newFolder("cache")
        val media = File(cache, MediaLoader.MEDIA_DIR)
        media.copy("two-days.mp4", mib, now - 48 * hour)
        media.copy("stale.mp4.part", mib, now - 48 * hour)
        media.copy("live.mp4.part", 100 * mib, now - 60_000L)
        media.copy("big-a.mp4", 40 * mib, now - 5 * hour)
        media.copy("big-b.mp4", 40 * mib, now - 4 * hour)
        media.copy("shared.mp4", 40 * mib, now - 10 * 60_000L)
        val opened = File(media, "opened")
        opened.copy("viewed-yesterday.png", mib, now - 30 * hour)
        opened.copy("viewed.png", mib, now - 2 * hour)

        MediaLoader.sweepCopies(cache, now)

        assertThat(media.names()).containsExactly("live.mp4.part", "shared.mp4")
        assertThat(opened.names()).containsExactly("viewed.png")
    }
}
