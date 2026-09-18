package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UploadRef
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
    fun `files of any type round-trip with their names and types, kept byte for byte and dropped like images`() = runBlocking<Unit> {
        val pdf = DraftFile("content://docs/9@1", PromptFile(byteArrayOf(0x25, 0x50, 0x44, 0x46), "Q3 report (final).pdf", "application/pdf"))
        val zip = DraftFile("content://docs/10@2", PromptFile(ByteArray(3) { 9 }, "bundle.zip", "application/zip"))
        store.write("bc-1", FollowUpDraft("Look at these", files = listOf(pdf)), listOf(QueuedFollowUp("q", "", files = listOf(zip), queuedAtMillis = 1L)))

        val read = store.read("bc-1")!!
        val draftFile = read.draft.files.single()
        assertThat(draftFile.id).isEqualTo("content://docs/9@1")
        assertThat(draftFile.file.name).isEqualTo("Q3 report (final).pdf")
        assertThat(draftFile.file.mimeType).isEqualTo("application/pdf")
        assertThat(draftFile.file.bytes.toList()).isEqualTo(listOf<Byte>(0x25, 0x50, 0x44, 0x46))
        val queuedFile = read.queue.single().files.single()
        assertThat(queuedFile.file.name).isEqualTo("bundle.zip")
        assertThat(queuedFile.file.bytes).hasLength(3)
        assertThat(read.queue.single().previewText).isEqualTo("See the attached file.")
        // On disk under safe names with their own extensions, apart from the images.
        val names = agentDir("bc-1").listFiles()!!.map { it.name }.filter { it != "state.json" }
        assertThat(names).hasSize(2)
        assertThat(names.count { it.startsWith("file-") && it.endsWith(".pdf") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("file-") && it.endsWith(".zip") }).isEqualTo(1)

        store.write("bc-1", FollowUpDraft("Look at these"), emptyList())
        assertThat(agentDir("bc-1").listFiles()!!.map { it.name }).containsExactly("state.json")
        assertThat(store.read("bc-1")!!.draft.files).isEmpty()
    }

    /**
     * A file whose upload completed before the restart — in the draft or in a queued message — comes back with its
     * reference, so it is sent by that reference rather than uploaded again; one still going up comes back without.
     */
    @Test
    fun `a completed upload's reference rides with the file, in the draft and in the queue, across a restart`() = runBlocking<Unit> {
        val ref = UploadRef("upl_9", "s3-abc", "uuid-9")
        val up = DraftFile("f1", PromptFile(byteArrayOf(1), "spec.pdf", "application/pdf", ref))
        val notYet = DraftFile("f2", PromptFile(byteArrayOf(2), "notes.txt", "text/plain"))
        val queuedRef = UploadRef("upl_10", "s3-def", "uuid-10")
        val queued = DraftFile("f3", PromptFile(byteArrayOf(3), "bundle.zip", "application/zip", queuedRef))
        store.write("bc-2", FollowUpDraft("Read", files = listOf(up, notYet)), listOf(QueuedFollowUp("q", "", files = listOf(queued), queuedAtMillis = 1L)))

        val read = FollowUpStore(context).read("bc-2")!!
        val (spec, notes) = read.draft.files
        assertThat(spec.file.upload).isEqualTo(ref)
        assertThat(notes.file.upload).isNull()
        assertThat(read.queue.single().files.single().file.upload).isEqualTo(queuedRef)
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
