package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowUpStoreTest {

    private lateinit var context: Context
    private lateinit var store: FollowUpStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FollowUpStore(context)
    }

    @After
    fun tearDown() = runBlocking { store.clear() }

    private fun image(id: String, vararg bytes: Byte, mime: String = "image/png") = DraftImage(id, PromptImage(bytes, mime))

    private fun agentDir(agentId: String) = File(File(context.filesDir, "followups"), agentId)

    @Test
    fun `draft and queue round-trip with their images and options`() = runBlocking<Unit> {
        val draft = FollowUpDraft("Still typing", listOf(image("content://photos/1@42", 1, 2, 3)))
        val queued = QueuedFollowUp(
            id = "queued-1",
            text = "Then this",
            images = listOf(image("content://photos/2@43", 4, 5, mime = "image/jpeg")),
            queuedAtMillis = 1_800_000_000_000L,
            planMode = true,
            modelId = "composer-2.5",
            modelParams = listOf(ModelParam("speed", "fast")),
            modelDisplayName = "Composer 2.5",
            // Transient state never reaches the disk.
            isSending = true,
            error = "Whatever went wrong last time",
        )

        store.write("bc-1", draft, listOf(queued))
        val read = store.read("bc-1")!!

        assertThat(read.restored).isTrue()
        assertThat(read.draft.text).isEqualTo("Still typing")
        assertThat(read.draft.images.single().id).isEqualTo("content://photos/1@42")
        assertThat(read.draft.images.single().image.bytes.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
        assertThat(read.draft.images.single().image.mimeType).isEqualTo("image/png")
        val q = read.queue.single()
        assertThat(q.id).isEqualTo("queued-1")
        assertThat(q.text).isEqualTo("Then this")
        assertThat(q.images.single().image.bytes.toList()).isEqualTo(listOf<Byte>(4, 5))
        assertThat(q.images.single().image.mimeType).isEqualTo("image/jpeg")
        assertThat(q.queuedAtMillis).isEqualTo(1_800_000_000_000L)
        assertThat(q.planMode).isTrue()
        assertThat(q.modelId).isEqualTo("composer-2.5")
        assertThat(q.modelParams).containsExactly(ModelParam("speed", "fast"))
        assertThat(q.modelDisplayName).isEqualTo("Composer 2.5")
        assertThat(q.isSending).isFalse()
        assertThat(q.error).isNull()
    }

    @Test
    fun `an image is written once and dropped once nothing refers to it`() = runBlocking<Unit> {
        val shared = image("img-shared", 7, 7, 7)
        store.write("bc-1", FollowUpDraft("a", listOf(shared)), listOf(QueuedFollowUp("q", "b", listOf(shared), 1L)))
        val files = { agentDir("bc-1").listFiles()!!.map { it.name }.filter { it != "state.json" } }
        assertThat(files()).hasSize(1)

        // Still referred to by the queue after the draft drops it.
        store.write("bc-1", FollowUpDraft("a"), listOf(QueuedFollowUp("q", "b", listOf(shared), 1L)))
        assertThat(files()).hasSize(1)

        store.write("bc-1", FollowUpDraft("a"), emptyList())
        assertThat(files()).isEmpty()
        assertThat(store.read("bc-1")?.draft?.text).isEqualTo("a")
    }

    @Test
    fun `nothing to keep removes the chat's directory`() = runBlocking<Unit> {
        store.write("bc-1", FollowUpDraft("a", listOf(image("i", 1))), emptyList())
        assertThat(agentDir("bc-1").isDirectory).isTrue()

        store.write("bc-1", FollowUpDraft("   "), emptyList())

        assertThat(agentDir("bc-1").exists()).isFalse()
        assertThat(store.read("bc-1")).isNull()
    }

    @Test
    fun `a missing image file is left out rather than failing the read`() = runBlocking<Unit> {
        store.write("bc-1", FollowUpDraft("a", listOf(image("i", 1))), emptyList())
        agentDir("bc-1").listFiles()!!.first { it.name != "state.json" }.delete()

        val read = store.read("bc-1")!!
        assertThat(read.draft.text).isEqualTo("a")
        assertThat(read.draft.images).isEmpty()
    }

    @Test
    fun `an id that would escape the root is confined`() = runBlocking<Unit> {
        store.write("../../escape", FollowUpDraft("a"), emptyList())
        assertThat(File(context.filesDir, "followups").listFiles()!!.map { it.name }).containsExactly("_.._.._escape")
        assertThat(File(context.filesDir, "escape").exists()).isFalse()
    }
}
