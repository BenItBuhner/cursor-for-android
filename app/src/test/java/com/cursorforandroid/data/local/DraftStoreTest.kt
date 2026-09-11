package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The unsent composer draft on disk. The composer debounces its saves and the sign-out clears the store from
 * outside the composer's own serialization, so a save that started before the sign-out must not put the draft back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DraftStoreTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.filesDir, "draft")
        dir.deleteRecursively()
    }

    private fun draft(prompt: String, images: List<DraftStore.Image> = emptyList()) =
        DraftStore.Draft(prompt = prompt, images = images, nonce = "nonce")

    private suspend fun files() = withContext(Dispatchers.IO) { dir.listFiles()?.map { it.name }.orEmpty() }

    @Test
    fun `a draft and its images come back as they were written`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val image = store.writeImage(PromptImage(byteArrayOf(1, 2, 3), "image/png"))!!
        store.write(draft("Ship it", listOf(image)))

        val read = DraftStore(context).read()!!
        assertThat(read.prompt).isEqualTo("Ship it")
        assertThat(read.nonce).isEqualTo("nonce")
        assertThat(store.readImage(read.images.single())!!.bytes).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a save that started before the sign-out does not recreate the draft it cleared`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val image = store.writeImage(PromptImage(byteArrayOf(1, 2, 3), "image/png"))!!
        store.write(draft("Previous account", listOf(image)))
        assertThat(store.read()).isNotNull()

        // Started undispatched, so the save has taken its generation and is waiting on the store before the
        // sign-out clears it — the debounced save the composer had queued when the user signed out.
        val save = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.write(draft("Previous account, still typing", listOf(image)))
        }
        assertThat(store.clear()).isTrue()
        save.await()

        assertThat(store.read()).isNull()
        assertThat(files()).isEmpty()
    }

    @Test
    fun `an image staged before the sign-out is not left behind for the next one`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val staged = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.writeImage(PromptImage(byteArrayOf(9), "image/png"))
        }
        assertThat(store.clear()).isTrue()
        staged.await()

        assertThat(files()).isEmpty()
    }
}
