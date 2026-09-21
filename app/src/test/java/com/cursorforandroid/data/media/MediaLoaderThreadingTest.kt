package com.cursorforandroid.data.media

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.media.ViewerFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The loader's callers are composition coroutines, and under the Compose test harness those run unconfined: a
 * suspension that another thread completes resumes them on that thread. The loader confines its whole call to the
 * main thread, so whatever thread the artifact URL, the fetch or the decode finish on, every continuation of the
 * call — the loader's own and the caller's — runs on the main thread, and the harness never performs a frame or
 * sends a snapshot's changes from a worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaLoaderThreadingTest {

    /** An artifact endpoint that answers when the test says, from the thread the test says, and notes where it resumed. */
    private class GatedApi : FakeCursorApi() {
        val gate = CompletableDeferred<String>()
        var resumedOn: Thread? = null
        override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto {
            val url = gate.await()
            resumedOn = Thread.currentThread()
            return DownloadArtifactResponseDto(url = url)
        }
    }

    private val mainThread: Thread = Looper.getMainLooper().thread

    /** Runs the main looper's queue until [done], the way the harness's waitForIdle does between checks. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "the loader never answered" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `the loader's continuations run on the main thread however its work finishes`() {
        val file = ViewerFixtures.png("threading.png", 64, 64, 0xFF203040.toInt())
        val api = GatedApi()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { api }))
        val ref = MediaRef.parse(ArtifactPaths.VM_ROOT + "threading.png", "bc-threading") as MediaRef.Artifact
        var answeredOn: Thread? = null
        var width = 0
        // The harness's own arrangement: an unconfined dispatcher, so a continuation runs wherever it is resumed.
        CoroutineScope(UnconfinedTestDispatcher()).launch {
            val bitmap = loader.image(ref, 64, 64)
            answeredOn = Thread.currentThread()
            width = bitmap.width
        }
        assertThat(api.resumedOn).isNull()

        // The URL arrives from a thread of its own; the fetch and the decode then run on Coil's workers.
        Thread { api.gate.complete(file) }.apply { name = "url-arrives"; start(); join() }
        pumpUntil { answeredOn != null }

        // Neither the loader's continuation after the URL nor the caller's after the decode left the main thread.
        assertThat(api.resumedOn).isSameInstanceAs(mainThread)
        assertThat(answeredOn).isSameInstanceAs(mainThread)
        assertThat(width).isEqualTo(64)
    }
}
