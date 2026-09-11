package com.cursorforandroid.data.local

import android.content.Context
import android.util.Log
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.PromptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The unsent draft in the New Chat composer, kept on disk so a process death does not lose typed work.
 *
 * The navigation stack survives being killed for memory; the composer's view model does not, so what was written
 * there is written here too. The text and the choices around it are one small JSON file; each attached image is a
 * file beside it, referenced by name — bytes belong on disk, not in a saved-state Bundle. The launch nonce is saved
 * with them, so a draft that comes back after a restart and is sent again keeps the chat id the first attempt would
 * have used and adopts whatever that attempt created.
 */
class DraftStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "draft")
    private val file = File(dir, "composer.json")

    /** One writer at a time, so [clear] never runs half-way through a save and leaves its files behind. */
    private val mutex = Mutex()
    /**
     * Bumped by [clear] before it takes the lock. A save that was debounced when the user signed out is not
     * cancelled by the sign-out — the composer's own serialization is a different lock, and the store is cleared
     * from outside it — so what stops it recreating the draft is having started under a generation that is gone.
     */
    private val generation = AtomicInteger()

    @Serializable
    data class Draft(
        val prompt: String = "",
        val images: List<Image> = emptyList(),
        val repoUrl: String? = null,
        val noRepo: Boolean = false,
        val ref: String = "",
        val modelId: String? = null,
        val modelParams: Map<String, String> = emptyMap(),
        /** False until something has settled the model choice, so "Default" can be told apart from "never asked". */
        val modelChosen: Boolean = false,
        val autoCreatePr: Boolean = false,
        val planMode: Boolean = false,
        val nonce: String = "",
    )

    /** One attached image: the name of its file in the draft directory, and what it was picked as. */
    @Serializable
    data class Image(val file: String, val mimeType: String)

    suspend fun read(): Draft? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { CursorJson.decodeFromString(Draft.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Draft could not be read; starting empty", it) }
            .getOrNull()
    }

    /** Writes [image] into the draft directory and returns what to reference it by, or null if it could not be written. */
    suspend fun writeImage(image: PromptImage): Image? {
        val startedIn = generation.get()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (generation.get() != startedIn) return@withLock null
                runCatching {
                    dir.mkdirs()
                    val name = UUID.randomUUID().toString()
                    File(dir, name).writeBytes(image.bytes)
                    Image(name, image.mimeType)
                }.onFailure { Log.w(TAG, "Draft image could not be written", it) }.getOrNull()
            }
        }
    }

    suspend fun readImage(image: Image): PromptImage? = withContext(Dispatchers.IO) {
        runCatching { PromptImage(File(dir, image.file).readBytes(), image.mimeType) }.getOrNull()
    }

    /** Saves [draft] and deletes the image files it no longer references. Written whole, so a kill cannot halve it. */
    suspend fun write(draft: Draft) {
        val startedIn = generation.get()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // The draft this would save is the signed-out account's; the directory has been cleared for the next.
                if (generation.get() != startedIn) return@withLock
                runCatching {
                    dir.mkdirs()
                    val scratch = File(dir, "composer.json.tmp")
                    scratch.writeText(CursorJson.encodeToString(Draft.serializer(), draft))
                    if (!scratch.renameTo(file)) scratch.delete()
                    prune(draft.images.mapTo(mutableSetOf()) { it.file })
                }.onFailure { Log.w(TAG, "Draft could not be written", it) }
            }
        }
    }

    /** Removes the draft and every image staged with it. False when something is still there afterwards. */
    suspend fun clear(): Boolean {
        generation.incrementAndGet()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    file.delete()
                    prune(emptySet())
                }
                val left = dir.listFiles()?.size ?: 0
                if (left > 0) Log.w(TAG, "Draft directory still holds $left files after being cleared")
                left == 0
            }
        }
    }

    private fun prune(keep: Set<String>) {
        dir.listFiles()?.forEach { if (it.name != file.name && it.name !in keep) it.delete() }
    }

    private companion object {
        const val TAG = "DraftStore"
    }
}
