package com.cursorforandroid.data.local

import android.content.Context
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.FollowUpComposerState
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UploadRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Device-local copy of what the follow-up composer holds for each chat and has not sent yet: the draft as typed and
 * the queue of follow-ups waiting for the agent's turn to end. Filed by agent (`files/followups/<agent>/`): one
 * `state.json` and the images, each written once under its id and dropped once nothing refers to it any more. A chat
 * with an empty composer and an empty queue has no directory.
 *
 * The images are kept byte for byte — they are the request's payload, not previews — so a restored draft sends
 * exactly what was attached.
 */
class FollowUpStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "followups")
    /** One writer per chat at a time: a save that outlives the one scheduled after it must not undo its files. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(agentId: String): Mutex = locks.getOrPut(agentId) { Mutex() }

    @Serializable
    private data class StoredImage(val id: String, val file: String, val mimeType: String)

    /**
     * A file of any type: its bytes under [file], the name and type the request carries, and — once its upload has
     * been completed — the reference the prompt names it by, so a restart sends what is already up rather than uploading again.
     */
    @Serializable
    private data class StoredFile(
        val id: String,
        val file: String,
        val name: String,
        val mimeType: String,
        val uploadId: String? = null,
        val s3UploadId: String? = null,
        val uploadUuid: String? = null,
    ) {
        val ref: UploadRef? get() = uploadId?.takeIf { it.isNotBlank() }?.let { UploadRef(it, s3UploadId.orEmpty(), uploadUuid ?: it) }
    }

    @Serializable
    private data class StoredDraft(val text: String = "", val images: List<StoredImage> = emptyList(), val files: List<StoredFile> = emptyList())

    @Serializable
    private data class StoredQueued(
        val id: String,
        val text: String,
        val images: List<StoredImage> = emptyList(),
        val files: List<StoredFile> = emptyList(),
        val queuedAtMillis: Long,
        val planMode: Boolean? = null,
        val modelId: String? = null,
        val modelParams: List<ModelParam> = emptyList(),
        val modelDisplayName: String? = null,
        /** Written before the request goes out, so a restore knows a send may have got through; see [QueuedFollowUp]. */
        val sendStartedAtMillis: Long? = null,
    )

    @Serializable
    private data class Stored(val draft: StoredDraft = StoredDraft(), val queue: List<StoredQueued> = emptyList())

    /** What the disk holds for [agentId]; null when nothing is filed. Images whose file has gone are left out. */
    suspend fun read(agentId: String): FollowUpComposerState? = lock(agentId).withLock { withContext(Dispatchers.IO) {
        val dir = agentDir(agentId)
        val file = File(dir, STATE_FILE).takeIf { it.isFile } ?: return@withContext null
        val stored = runCatching { CursorJson.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull() ?: return@withContext null
        FollowUpComposerState(
            draft = FollowUpDraft(stored.draft.text, stored.draft.images.mapNotNull { it.load(dir) }, stored.draft.files.mapNotNull { it.load(dir) }),
            queue = stored.queue.map { q ->
                QueuedFollowUp(
                    id = q.id,
                    text = q.text,
                    images = q.images.mapNotNull { it.load(dir) },
                    files = q.files.mapNotNull { it.load(dir) },
                    queuedAtMillis = q.queuedAtMillis,
                    planMode = q.planMode,
                    modelId = q.modelId,
                    modelParams = q.modelParams,
                    modelDisplayName = q.modelDisplayName,
                    sendStartedAtMillis = q.sendStartedAtMillis,
                )
            },
            restored = true,
        )
    } }

    /** Files [draft] and [queue] for [agentId], or removes the chat's directory when both are empty. */
    suspend fun write(agentId: String, draft: FollowUpDraft, queue: List<QueuedFollowUp>) = lock(agentId).withLock { withContext(Dispatchers.IO) {
        val dir = agentDir(agentId)
        if (draft.isEmpty && queue.isEmpty()) {
            dir.deleteRecursively()
            return@withContext
        }
        if (!dir.mkdirs() && !dir.isDirectory) return@withContext
        val referenced = HashSet<String>()
        fun store(image: DraftImage): StoredImage {
            val name = fileNameFor(image)
            referenced += name
            val file = File(dir, name)
            // Written once: the id names the same bytes for as long as the image is attached anywhere.
            if (!file.isFile) file.writeBytes(image.image.bytes)
            return StoredImage(image.id, name, image.image.mimeType)
        }
        fun storeFile(draftFile: DraftFile): StoredFile {
            val name = fileNameFor(draftFile)
            referenced += name
            val file = File(dir, name)
            if (!file.isFile) file.writeBytes(draftFile.file.bytes)
            val ref = draftFile.file.upload
            return StoredFile(draftFile.id, name, draftFile.file.name, draftFile.file.mimeType, uploadId = ref?.uploadId, s3UploadId = ref?.s3UploadId, uploadUuid = ref?.uuid)
        }
        val stored = Stored(
            draft = StoredDraft(draft.text, draft.images.map(::store), draft.files.map(::storeFile)),
            queue = queue.map { q ->
                StoredQueued(
                    id = q.id,
                    text = q.text,
                    images = q.images.map(::store),
                    files = q.files.map(::storeFile),
                    queuedAtMillis = q.queuedAtMillis,
                    planMode = q.planMode,
                    modelId = q.modelId,
                    modelParams = q.modelParams,
                    modelDisplayName = q.modelDisplayName,
                    sendStartedAtMillis = q.sendStartedAtMillis,
                )
            },
        )
        // The state is swapped in whole, so a crash mid-write leaves the previous copy rather than half a file.
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.writeText(CursorJson.encodeToString(Stored.serializer(), stored))
        if (!tmp.renameTo(File(dir, STATE_FILE))) {
            File(dir, STATE_FILE).writeText(tmp.readText())
            tmp.delete()
        }
        dir.listFiles()?.forEach { if (it.name != STATE_FILE && it.name !in referenced) it.delete() }
    } }

    suspend fun remove(agentId: String) = lock(agentId).withLock { withContext(Dispatchers.IO) {
        agentDir(agentId).deleteRecursively()
        Unit
    } }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        Unit
    }

    private fun StoredImage.load(dir: File): DraftImage? {
        val bytes = File(dir, file).takeIf { it.isFile }?.readBytes() ?: return null
        return DraftImage(id, PromptImage(bytes, mimeType))
    }

    private fun StoredFile.load(dir: File): DraftFile? {
        val bytes = File(dir, file).takeIf { it.isFile }?.readBytes() ?: return null
        return DraftFile(id, PromptFile(bytes, name, mimeType, ref))
    }

    /** Like [fileNameFor] for an image, with the file's own extension so what is on disk still reads as what it is. */
    private fun fileNameFor(draftFile: DraftFile): String {
        val extension = PromptFile.extensionOf(draftFile.file.name).takeIf { it.isNotEmpty() && it.length <= 12 } ?: "bin"
        return "file-" + safeName(draftFile.id).takeLast(32) + "-" + draftFile.id.hashCode().toUInt().toString(16) + "." + extension
    }

    private fun agentDir(agentId: String) = File(root, safeName(agentId))

    /**
     * The picker's ids carry a content URI, so the file takes a safe tail of it plus a hash of the whole: readable,
     * and two ids that clean to the same tail still land in different files.
     */
    private fun fileNameFor(image: DraftImage): String =
        safeName(image.id).takeLast(32) + "-" + image.id.hashCode().toUInt().toString(16) + "." + extensionFor(image.image.mimeType)

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val STATE_FILE = "state.json"

        /** Unsafe characters are replaced and a leading dot prefixed, so no id can escape [root] or hide as a dotfile. */
        fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (cleaned.isEmpty() || cleaned.startsWith(".")) "_$cleaned" else cleaned
        }
    }
}
