package com.cursorforandroid.data.local

import android.content.Context
import android.util.Log
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UploadRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The new chats written in the New Chat composer and not sent — the drafts the sidebar lists above its groups — kept on
 * disk so nothing typed is lost to a process death, a restart, an update or a sign-out. One directory per draft
 * (`files/drafts/<id>/`): its `draft.json` ([Record]) and each attached image or file beside it under a name of its
 * own, referenced from the record — bytes belong on disk, not in a saved-state Bundle. The launch nonce is kept with
 * each, so a draft sent after a restart keeps the chat id the first attempt would have used and adopts whatever that
 * attempt created.
 *
 * Every record carries its [SCHEMA]. 0.3.61 and before kept a single draft (`files/draft/composer.json`); it is
 * brought in as a draft of its own the first time the drafts are listed ([list]), and removed only once it has been
 * written in the new form. A record this build cannot read is set aside with its files ([DraftFiles.setAside]), never
 * written over or dropped.
 */
class DraftStore(context: Context) {

    private val filesDir = context.applicationContext.filesDir
    private val root = File(filesDir, ROOT)
    private val legacyDir = File(filesDir, LEGACY_ROOT)

    /** One writer at a time, so [clear] never runs half-way through a save and leaves its files behind. */
    private val mutex = Mutex()
    /**
     * Bumped by [clear] and [whileStopped] before they take the lock. A save that was debounced when the user signed
     * out is not cancelled by the sign-out — the composer's own serialization is a different lock — so what stops it
     * recreating the draft is having started under a generation that is gone.
     */
    private val generation = AtomicInteger()

    /**
     * One draft: what the composer held and the choices around it — the repository and branch, the device, the model
     * with every parameter the picker set, the switches — and, once sent, the chat it went out as ([launchedAs]) until
     * the server has it. [device] is null for a draft 0.3.61 kept, which did not record one: it opens on the device the
     * last launch ran on, as it did then. [origin] names the composer that wrote it ([ORIGIN_COMPOSER], or the quick
     * composer's [ORIGIN_QUICK_COMPOSER]).
     */
    @Serializable
    data class Record(
        val id: String,
        val schema: Int = 0,
        val createdAtMillis: Long = 0L,
        val updatedAtMillis: Long = 0L,
        val origin: String = ORIGIN_COMPOSER,
        val prompt: String = "",
        val images: List<Image> = emptyList(),
        /** Files of any type attached in Extended mode, each a file beside the draft like an image. */
        val files: List<StoredFile> = emptyList(),
        val repoUrl: String? = null,
        val noRepo: Boolean = false,
        val ref: String = "",
        val device: DeviceTarget? = null,
        val modelId: String? = null,
        val modelParams: List<ModelParam> = emptyList(),
        /** The model's name as the chip showed it, for a draft listed before the catalogue has loaded. */
        val modelLabel: String? = null,
        /** False until something has settled the model choice, so "Default" can be told apart from "never asked". */
        val modelChosen: Boolean = false,
        val autoCreatePr: Boolean = false,
        val planMode: Boolean = false,
        val nonce: String = "",
        /** The chat this draft was sent as, while the server has yet to take it; the sidebar shows the chat, not the draft. */
        val launchedAs: String? = null,
        /** Why its launch did not go through, in the server's words, until the draft is written into again. */
        val error: String? = null,
    ) {
        /** Something typed or attached: a draft without it is not kept. */
        val hasContent: Boolean get() = prompt.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()

        /** Everything but when it was written: two records the same here are the same draft. */
        fun sameContentAs(other: Record?): Boolean = other != null && copy(schema = 0, createdAtMillis = 0, updatedAtMillis = 0) ==
            other.copy(schema = 0, createdAtMillis = 0, updatedAtMillis = 0)
    }

    /** One attached image: the name of its file in the draft's directory, and what it was picked as. */
    @Serializable
    data class Image(val file: String, val mimeType: String)

    /**
     * One attached file: its bytes' name in the draft's directory, the name and type the request carries, and — once
     * its upload has been completed — the reference the prompt names it by, so a restart sends what is already up.
     */
    @Serializable
    data class StoredFile(
        val file: String,
        val name: String,
        val mimeType: String,
        val uploadId: String? = null,
        val s3UploadId: String? = null,
        val uploadUuid: String? = null,
    ) {
        val ref: UploadRef? get() = uploadId?.takeIf { it.isNotBlank() }?.let { UploadRef(it, s3UploadId.orEmpty(), uploadUuid ?: it) }

        /** The same record naming [ref] — the bytes on disk are untouched, so a completed upload costs no rewrite. */
        fun withRef(ref: UploadRef?): StoredFile = copy(uploadId = ref?.uploadId, s3UploadId = ref?.s3UploadId, uploadUuid = ref?.uuid)
    }

    /** `files/draft/composer.json` as 0.3.61 and before wrote it: the one draft there was. */
    @Serializable
    private data class LegacyDraft(
        val prompt: String = "",
        val images: List<Image> = emptyList(),
        val files: List<StoredFile> = emptyList(),
        val repoUrl: String? = null,
        val noRepo: Boolean = false,
        val ref: String = "",
        val modelId: String? = null,
        val modelParams: Map<String, String> = emptyMap(),
        val modelChosen: Boolean = false,
        val autoCreatePr: Boolean = false,
        val planMode: Boolean = false,
        val nonce: String = "",
    )

    /**
     * Every draft on disk, 0.3.61's single one brought in first. A record is listed as written; [readImage] and
     * [readFile] answer null for a file of it that has gone.
     */
    suspend fun list(): List<Record> = withContext(Dispatchers.IO) {
        mutex.withLock {
            migrateLegacy()
            root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty().mapNotNull { dir -> readRecord(dir) }
        }
    }

    /** Writes [image] into draft [draftId]'s directory and returns what to reference it by, or null if it could not be written. */
    suspend fun writeImage(draftId: String, image: PromptImage): Image? {
        val startedIn = generation.get()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (generation.get() != startedIn) return@withLock null
                runCatching {
                    val name = UUID.randomUUID().toString()
                    File(dir(draftId).apply { mkdirs() }, name).writeBytes(image.bytes)
                    Image(name, image.mimeType)
                }.onFailure { Log.w(TAG, "Draft image could not be written", it) }.getOrNull()
            }
        }
    }

    suspend fun readImage(draftId: String, image: Image): PromptImage? = withContext(Dispatchers.IO) {
        runCatching { PromptImage(File(dir(draftId), image.file).readBytes(), image.mimeType) }.getOrNull()
    }

    /** Writes [file]'s bytes into draft [draftId]'s directory and returns what to reference it by, or null if it could not be written. */
    suspend fun writeFile(draftId: String, file: PromptFile): StoredFile? {
        val startedIn = generation.get()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (generation.get() != startedIn) return@withLock null
                runCatching {
                    val name = UUID.randomUUID().toString()
                    File(dir(draftId).apply { mkdirs() }, name).writeBytes(file.bytes)
                    StoredFile(name, file.name, file.mimeType).withRef(file.upload)
                }.onFailure { Log.w(TAG, "Draft file could not be written", it) }.getOrNull()
            }
        }
    }

    suspend fun readFile(draftId: String, stored: StoredFile): PromptFile? = withContext(Dispatchers.IO) {
        runCatching { PromptFile(File(dir(draftId), stored.file).readBytes(), stored.name, stored.mimeType, stored.ref) }.getOrNull()
    }

    /** Saves [record] whole and deletes the files of its directory it no longer references. False when nothing was written. */
    suspend fun write(record: Record): Boolean {
        val startedIn = generation.get()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                // The draft this would save is a signed-out account's; its directory has been parked or cleared.
                if (generation.get() != startedIn) return@withLock false
                runCatching {
                    val dir = dir(record.id).apply { mkdirs() }
                    DraftFiles.write(File(dir, RECORD_FILE), CursorJson.encodeToString(Record.serializer(), record.copy(schema = SCHEMA)))
                    val keep = record.images.mapTo(HashSet()) { it.file } + record.files.map { it.file }
                    dir.listFiles()?.forEach { if (!it.name.startsWith(RECORD_FILE) && it.name !in keep) it.delete() }
                    true
                }.onFailure { Log.w(TAG, "Draft could not be written", it) }.getOrDefault(false)
            }
        }
    }

    /** Removes draft [id] and everything filed with it. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { dir(id).deleteRecursively() }
        Unit
    }

    /** Removes every draft, 0.3.61's included. False when something is still there afterwards. */
    suspend fun clear(): Boolean {
        generation.incrementAndGet()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                root.deleteRecursively()
                legacyDir.deleteRecursively()
                val cleared = !root.exists() && !legacyDir.exists()
                if (!cleared) Log.w(TAG, "Draft directories still hold files after being cleared")
                cleared
            }
        }
    }

    /**
     * Runs [move] — the drafts' directories leaving for the signed-out account's parking place — with no write under
     * way, and makes any save that was scheduled before it a no-op, as [clear] does.
     */
    suspend fun whileStopped(move: suspend () -> Unit) {
        generation.incrementAndGet()
        mutex.withLock { move() }
    }

    private fun dir(id: String) = File(root, safeName(id))

    private fun readRecord(dir: File): Record? {
        val file = File(dir, RECORD_FILE)
        val text = runCatching { DraftFiles.read(file) }.getOrElse { failure ->
            DraftFiles.setAside(root, dir, failure)
            return null
        } ?: return null
        return runCatching { CursorJson.decodeFromString(Record.serializer(), text) }.getOrElse { failure ->
            DraftFiles.setAside(root, dir, failure)
            null
        }?.let { if (it.schema < SCHEMA) it.copy(schema = SCHEMA) else it }
    }

    /**
     * 0.3.61's single draft, brought in as a draft of its own: its files moved into the new directory, the record
     * written, and only then the old directory removed. Its id is derived from what it holds, so a migration that is
     * cut short and run again writes the same draft rather than a second one. A file that cannot be read is set aside
     * with its images.
     */
    private fun migrateLegacy() {
        val legacyFile = File(legacyDir, LEGACY_FILE)
        if (!legacyFile.isFile) {
            if (legacyDir.isDirectory && legacyDir.list()?.isEmpty() == true) legacyDir.delete()
            return
        }
        val text = runCatching { DraftFiles.read(legacyFile) }.getOrNull()
        val legacy = text?.let { runCatching { CursorJson.decodeFromString(LegacyDraft.serializer(), it) }.getOrNull() }
        if (legacy == null) {
            DraftFiles.setAside(root.apply { mkdirs() }, legacyDir, IllegalStateException("0.3.61 draft unreadable"))
            return
        }
        val id = LEGACY_ID_PREFIX + digest(text)
        val target = dir(id).apply { mkdirs() }
        (legacy.images.map { it.file } + legacy.files.map { it.file }).forEach { name ->
            val from = File(legacyDir, name)
            val to = File(target, name)
            if (from.isFile && !to.exists() && !from.renameTo(to)) from.copyTo(to)
        }
        val at = legacyFile.lastModified()
        val record = Record(
            id = id,
            schema = SCHEMA,
            createdAtMillis = at,
            updatedAtMillis = at,
            prompt = legacy.prompt,
            images = legacy.images,
            files = legacy.files,
            repoUrl = legacy.repoUrl,
            noRepo = legacy.noRepo,
            ref = legacy.ref,
            modelId = legacy.modelId,
            modelParams = legacy.modelParams.map { (paramId, value) -> ModelParam(paramId, value) },
            modelChosen = legacy.modelChosen,
            autoCreatePr = legacy.autoCreatePr,
            planMode = legacy.planMode,
            nonce = legacy.nonce,
        )
        val written = runCatching { DraftFiles.write(File(target, RECORD_FILE), CursorJson.encodeToString(Record.serializer(), record)) }
        if (written.isSuccess) legacyDir.deleteRecursively() else Log.w(TAG, "0.3.61 draft could not be brought in; left where it is", written.exceptionOrNull())
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        /** The drafts' directory under `files/`: one entry per draft (see [DraftFiles.Root]). */
        const val ROOT = "drafts"
        /** 0.3.61's single-draft directory, read once and brought in; parked whole if a sign-out finds it. */
        const val LEGACY_ROOT = "draft"
        /** The schema this build writes: 1 is the first with more than one draft (0.3.61's single draft was unversioned). */
        const val SCHEMA = 1
        const val ORIGIN_COMPOSER = "composer"
        /** The quick composer over the launcher (the new-chat widget's), filing a draft it was left with. */
        const val ORIGIN_QUICK_COMPOSER = "quick-composer"
        private const val RECORD_FILE = "draft.json"
        private const val LEGACY_FILE = "composer.json"
        private const val LEGACY_ID_PREFIX = "legacy-"
        private const val TAG = "DraftStore"

        /** Unsafe characters are replaced and a leading dot prefixed, so no id can escape the root or hide as a dotfile. */
        private fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (cleaned.isEmpty() || cleaned.startsWith(".")) "_$cleaned" else cleaned
        }
    }
}
