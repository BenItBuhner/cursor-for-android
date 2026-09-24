package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UploadRef
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
 * The New Chat composer's drafts on disk, one directory each. The composer debounces its saves and the sign-out
 * clears the store from outside the composer's own serialization, so a save that started before the sign-out must not
 * put a draft back; and the single draft 0.3.61 kept is brought in, never dropped.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DraftStoreTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var legacy: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, DraftStore.ROOT).apply { deleteRecursively() }
        legacy = File(context.filesDir, DraftStore.LEGACY_ROOT).apply { deleteRecursively() }
    }

    private fun draft(id: String, prompt: String, images: List<DraftStore.Image> = emptyList(), updatedAt: Long = 1_000L) =
        DraftStore.Record(id = id, prompt = prompt, images = images, nonce = "nonce-$id", createdAtMillis = updatedAt, updatedAtMillis = updatedAt)

    private suspend fun files(id: String) = withContext(Dispatchers.IO) { File(root, id).listFiles()?.map { it.name }.orEmpty() }

    @Test
    fun `several drafts come back as they were written, each with its own images and choices`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val image = store.writeImage("d-1", PromptImage(byteArrayOf(1, 2, 3), "image/png"))!!
        store.write(
            draft("d-1", "Ship it", listOf(image)).copy(
                repoUrl = "https://github.com/acme/app",
                ref = "cursor/fix-login",
                device = DeviceTarget.machine("studio-mac"),
                modelId = "claude-fable-5.1-thinking",
                modelParams = listOf(ModelParam("context", "300k"), ModelParam("effort", "low")),
                modelLabel = "Claude Fable 5.1",
                modelChosen = true,
                planMode = true,
                mode = "DEBUG",
                autoCreatePr = true,
            ),
        )
        store.write(draft("d-2", "And another", updatedAt = 2_000L))

        val read = DraftStore(context).list().associateBy { it.id }
        assertThat(read.keys).containsExactly("d-1", "d-2")
        val first = read.getValue("d-1")
        assertThat(first.schema).isEqualTo(DraftStore.SCHEMA)
        assertThat(first.prompt).isEqualTo("Ship it")
        assertThat(first.nonce).isEqualTo("nonce-d-1")
        assertThat(first.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(first.ref).isEqualTo("cursor/fix-login")
        assertThat(first.device).isEqualTo(DeviceTarget.machine("studio-mac"))
        assertThat(first.modelParams).containsExactly(ModelParam("context", "300k"), ModelParam("effort", "low")).inOrder()
        assertThat(first.modelChosen).isTrue()
        assertThat(first.planMode).isTrue()
        assertThat(first.mode).isEqualTo("DEBUG")
        assertThat(store.readImage("d-1", first.images.single())!!.bytes).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(read.getValue("d-2").images).isEmpty()
        assertThat(read.getValue("d-2").mode).isNull()
    }

    @Test
    fun `a draft's files come back with their names and types, and are pruned with the draft`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val stored = store.writeFile("d-1", PromptFile(byteArrayOf(9, 9), "trace.zip", "application/zip"))!!
        store.write(draft("d-1", "Look").copy(files = listOf(stored)))

        val read = DraftStore(context).list().single()
        val file = store.readFile("d-1", read.files.single())!!
        assertThat(file.name).isEqualTo("trace.zip")
        assertThat(file.mimeType).isEqualTo("application/zip")
        assertThat(file.bytes).isEqualTo(byteArrayOf(9, 9))
        assertThat(files("d-1")).containsExactly("draft.json", stored.file)

        store.write(draft("d-1", "Look"))
        assertThat(files("d-1")).containsExactly("draft.json")
    }

    /**
     * A file whose upload completed before the app was closed comes back with its reference: the composer sends it by
     * that reference after a restart instead of uploading the bytes again. A file kept without one comes back without.
     */
    @Test
    fun `a completed upload's reference is kept with the draft's file across a restart`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val ref = UploadRef("upl_01", "s3-multipart-77", "8d1e5c6a-uuid")
        val stored = store.writeFile("d-1", PromptFile(byteArrayOf(1, 2, 3), "spec.pdf", "application/pdf"))!!
        val pending = store.writeFile("d-1", PromptFile(byteArrayOf(4), "notes.txt", "text/plain"))!!
        assertThat(stored.ref).isNull()
        store.write(draft("d-1", "Look").copy(files = listOf(stored.withRef(ref), pending)))

        val read = DraftStore(context).list().single()
        val (spec, notes) = read.files
        assertThat(spec.ref).isEqualTo(ref)
        assertThat(store.readFile("d-1", spec)!!.upload).isEqualTo(ref)
        assertThat(notes.ref).isNull()
        assertThat(store.readFile("d-1", notes)!!.upload).isNull()
    }

    @Test
    fun `deleting a draft removes it and everything filed with it, and leaves the others`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val image = store.writeImage("d-1", PromptImage(byteArrayOf(1), "image/png"))!!
        store.write(draft("d-1", "Gone", listOf(image)))
        store.write(draft("d-2", "Stays"))

        store.delete("d-1")

        assertThat(File(root, "d-1").exists()).isFalse()
        assertThat(DraftStore(context).list().map { it.id }).containsExactly("d-2")
    }

    /**
     * An update installed over 0.3.61: its one draft (`files/draft/composer.json` and the image beside it) is brought
     * in as a draft of its own, every field kept, and the old directory goes only once the new one is written. Run
     * again — a migration cut short by the process ending — it writes the same draft, never a second one.
     */
    @Test
    fun `0_3_61's single draft is brought in whole, once, and never dropped`() = runBlocking<Unit> {
        legacy.mkdirs()
        File(legacy, "img-1").writeBytes(byteArrayOf(4, 2))
        val json = """{"prompt":"Half a thought","images":[{"file":"img-1","mimeType":"image/png"}],"repoUrl":"https://github.com/acme/app",""" +
            """"ref":"cursor/cli-exploration-9c1d","modelId":"composer-2.5","modelParams":{"fast":"false"},"modelChosen":true,""" +
            """"autoCreatePr":true,"planMode":true,"nonce":"n-0361"}"""
        File(legacy, "composer.json").writeText(json)
        // A copy of the old directory, as a migration cut short between writing the new draft and removing the old would leave it.
        val copy = File(context.filesDir, "draft-copy").apply { deleteRecursively() }
        legacy.copyRecursively(copy)

        val first = DraftStore(context).list().single()

        assertThat(legacy.exists()).isFalse()
        assertThat(first.id).startsWith("legacy-")
        assertThat(first.prompt).isEqualTo("Half a thought")
        assertThat(first.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(first.ref).isEqualTo("cursor/cli-exploration-9c1d")
        assertThat(first.modelId).isEqualTo("composer-2.5")
        assertThat(first.modelParams).containsExactly(ModelParam("fast", "false"))
        assertThat(first.modelChosen).isTrue()
        assertThat(first.autoCreatePr).isTrue()
        assertThat(first.planMode).isTrue()
        assertThat(first.nonce).isEqualTo("n-0361")
        // 0.3.61 never recorded a device: the draft opens on the last launch's.
        assertThat(first.device).isNull()
        assertThat(DraftStore(context).readImage(first.id, first.images.single())!!.bytes).isEqualTo(byteArrayOf(4, 2))

        copy.renameTo(legacy)
        val again = DraftStore(context).list()
        assertThat(again.map { it.id }).containsExactly(first.id)
        assertThat(legacy.exists()).isFalse()
    }

    @Test
    fun `a record this build cannot read is set aside with its files, not dropped and not listed`() = runBlocking<Unit> {
        val store = DraftStore(context)
        store.write(draft("d-ok", "Fine"))
        File(root, "d-bad").mkdirs()
        File(root, "d-bad/draft.json").writeText("""{"id":"d-bad","prompt":"half""")
        File(root, "d-bad/photo").writeBytes(byteArrayOf(7))
        legacy.mkdirs()
        File(legacy, "composer.json").writeText("not json at all")

        val listed = store.list()

        assertThat(listed.map { it.id }).containsExactly("d-ok")
        val shelf = File(root, DraftFiles.UNREADABLE_DIR).listFiles()!!.map { it.name }
        assertThat(shelf.count { it.startsWith("d-bad-") }).isEqualTo(1)
        assertThat(shelf.count { it.startsWith("draft-") }).isEqualTo(1)
        val kept = File(root, DraftFiles.UNREADABLE_DIR).listFiles()!!.single { it.name.startsWith("d-bad-") }
        assertThat(File(kept, "photo").readBytes()).isEqualTo(byteArrayOf(7))
    }

    @Test
    fun `a save that started before the sign-out does not recreate the draft it cleared`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val image = store.writeImage("d-1", PromptImage(byteArrayOf(1, 2, 3), "image/png"))!!
        store.write(draft("d-1", "Previous account", listOf(image)))
        assertThat(store.list()).isNotEmpty()

        // Started undispatched, so the save has taken its generation and is waiting on the store before the
        // sign-out clears it — the debounced save the composer had queued when the user signed out.
        val save = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.write(draft("d-1", "Previous account, still typing", listOf(image)))
        }
        assertThat(store.clear()).isTrue()
        save.await()

        assertThat(store.list()).isEmpty()
        assertThat(root.exists()).isFalse()
    }

    @Test
    fun `an image staged before the sign-out is not left behind for the next one`() = runBlocking<Unit> {
        val store = DraftStore(context)
        val staged = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.writeImage("d-1", PromptImage(byteArrayOf(9), "image/png"))
        }
        assertThat(store.clear()).isTrue()
        staged.await()

        assertThat(root.exists()).isFalse()
    }
}
