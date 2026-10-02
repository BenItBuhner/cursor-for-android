package com.cursorforandroid.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.RecordImage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Images written for one prompt that has not been tied to a run yet; see [AttachmentStore.stage]. */
class StagedAttachments internal constructor(internal val dir: File?, val attachments: List<MessageAttachment>) {
    companion object {
        val EMPTY = StagedAttachments(null, emptyList())
    }
}

/**
 * Where [AttachmentStore.commit] moved each set of copies, so a reader holding a path from before the move still finds
 * the file. A bubble is drawn from its staged paths, and the launch or send can settle and move the files in the
 * instant between the bubble taking a path and decoding it; the state that names the new paths comes a frame later,
 * and a decode that failed on the old one would show the image as gone. Held for this process only, and bounded:
 * a path is only ever stale for the moment it takes the new one to reach the screen.
 */
object AttachmentMoves {
    private val moves = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_MOVES
    }

    @Synchronized
    internal fun record(from: File, to: File) {
        moves[from.path] = to.path
    }

    /**
     * [path], or where the file it named went — through every move since, a set re-staged and filed again included.
     * [path] itself while it is still there, or when nothing that was moved is there instead.
     */
    fun resolve(path: String): String {
        var file = File(path)
        if (file.exists()) return path
        synchronized(this) {
            repeat(MAX_MOVES) {
                val dir = file.parentFile?.path ?: return path
                val to = moves[dir] ?: return path
                file = File(to, file.name)
                if (file.exists()) return file.path
            }
        }
        return path
    }

    @Synchronized
    internal fun clear() = moves.clear()

    private const val MAX_MOVES = 256
}

/**
 * What names a prompt's attachments besides the run it started (see [AttachmentStore.commit]): the id its sender
 * minted for the message, when the send had one (`agent.v1.UserMessage.message_id`, the account's queue), and its
 * words with the moment it was sent — for a message the record carries without a run of its own (one taken into
 * the turn under way), or whose run the record's turn was never paired with.
 */
data class PromptKey(val messageId: String? = null, val text: String? = null, val sentAtMs: Long? = null)

/**
 * How a prompt's bubble finds its attachments in the map [AttachmentStore.forAgent] gives: by the run that carried
 * the prompt, else by the message's id, else by its words and the moment it was sent — the closest sending of those
 * words to when the turn started.
 */
object PromptAttachments {
    private const val MESSAGE = "message:"
    private const val TEXT = "text:"
    /**
     * How far apart a prompt's sending and its turn's start may be and still be the same message, when words alone
     * name it: a message queued behind a long turn starts well after it was sent, and one taken into the turn under
     * way was sent well after that turn started.
     */
    private const val TEXT_WINDOW_MS = 12 * 60 * 60 * 1000L

    fun messageKey(messageId: String): String = MESSAGE + messageId

    fun textKey(text: String, sentAtMs: Long): String = TEXT + QueuePlacement.textKey(text) + "@" + sentAtMs

    /** [key] names a set by a message's id or its words (see [keys]), not by a run. */
    fun isNamed(key: String): Boolean = key.startsWith(MESSAGE) || key.startsWith(TEXT)

    /** The keys [key] names a set by, besides its run: the message's, and its words' with the moment it was sent. */
    fun keys(key: PromptKey): List<String> = listOfNotNull(
        key.messageId?.let { messageKey(it) },
        key.text?.takeIf { it.isNotBlank() }?.let { text -> key.sentAtMs?.let { textKey(text, it) } },
    )

    /** The attachments of a prompt: under [runId] when the turn has its run, else by [messageId], else by [text] sent nearest [startedAt]. Null for none. */
    fun find(attachments: Map<String, List<MessageAttachment>>, runId: String?, messageId: String?, text: String?, startedAt: Long?): List<MessageAttachment>? {
        if (attachments.isEmpty()) return null
        runId?.let { attachments[it] }?.let { return it }
        messageId?.let { attachments[MESSAGE + it] }?.let { return it }
        if (text.isNullOrBlank()) return null
        val prefix = TEXT + QueuePlacement.textKey(text) + "@"
        var best: List<MessageAttachment>? = null
        var bestGap = Long.MAX_VALUE
        var candidates = 0
        for ((key, value) in attachments) {
            if (!key.startsWith(prefix)) continue
            val sentAt = key.substring(prefix.length).toLongOrNull() ?: continue
            candidates++
            val gap = if (startedAt == null) 0L else kotlin.math.abs(sentAt - startedAt)
            if (gap <= TEXT_WINDOW_MS && gap < bestGap) { best = value; bestGap = gap }
        }
        // Words alone, with no moment to compare against, name a set only when they were sent with pictures once.
        return if (startedAt == null && candidates > 1) null else best
    }
}

/**
 * Device-local copies of the images attached to prompts.
 *
 * `GET /v0/agents/{id}/conversation` returns a `user_message` as text only: the `prompt.images` that went up with a
 * prompt never come back down, so a screenshot the user attached would vanish the moment the optimistic bubble is
 * replaced by history. Sending a prompt therefore writes each image here — as sent when it is already display-sized,
 * downscaled otherwise — filed by agent and run (`files/attachments/<agent>/<run>/`), and `TimelineBuilder` pairs them
 * with the user message that began that run; a set is also named by the message's id and its words (see [PromptKey]),
 * for a prompt the transcript shows without its run. The account's record carries a prompt's pictures too
 * (`selected_images`): a copy read from there is kept under the message's id (see [keepFromRecord]), so a message
 * sent elsewhere shows them, and a message sent here shows the account's copy of them.
 *
 * A prompt is [stage]d before the request goes out so the optimistic bubble already shows its images; on success it is
 * [commit]ted under the run the API returned, on failure [discard]ed.
 */
class AttachmentStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "attachments")
    private val staging = File(root, ".staging")

    @Serializable
    private data class Meta(
        val runId: String? = null,
        val images: List<ImageMeta>,
        val files: List<FileMeta> = emptyList(),
        val messageId: String? = null,
        val textKey: String? = null,
        val sentAtMs: Long? = null,
    )

    /** [key] names a picture kept from the record (see [keepFromRecord]) among its message's; null for one sent from here. */
    @Serializable
    private data class ImageMeta(val file: String, val width: Int, val height: Int, val key: String? = null)

    /** A file of any type (Extended mode), kept byte for byte so the system viewer opens what the agent was given. */
    @Serializable
    private data class FileMeta(val file: String, val name: String, val mimeType: String, val sizeBytes: Long)

    /**
     * Writes previews of [images] and copies of [files] into a scratch directory. Images that cannot be decoded are
     * skipped; a file is kept as it is, under its own name, so the card can hand it to the system viewer — except a
     * picture attached as a file (from the gallery, in Extended mode), which is kept as a preview like any image, so
     * the transcript shows it inline; one that will not decode is kept as a file after all.
     */
    suspend fun stage(images: List<PromptImage>, files: List<PromptFile> = emptyList()): StagedAttachments = withContext(Dispatchers.IO) {
        if (images.isEmpty() && files.isEmpty()) return@withContext StagedAttachments.EMPTY
        val dir = File(staging, UUID.randomUUID().toString())
        if (!dir.mkdirs() && !dir.isDirectory) return@withContext StagedAttachments.EMPTY
        val written = images.mapIndexedNotNull { index, image -> runCatching { writePreview(image, dir, index) }.getOrNull() } +
            files.mapIndexedNotNull { index, file ->
                val preview = if (PromptImage.isSupported(file.mimeType)) runCatching { writePreview(PromptImage(file.bytes, file.mimeType), dir, images.size + index) }.getOrNull() else null
                preview ?: runCatching { writeFile(file, dir, index) }.getOrNull()
            }
        if (written.isEmpty()) {
            DiskSweep.deleteTree(dir)
            return@withContext StagedAttachments.EMPTY
        }
        StagedAttachments(dir, written)
    }

    /**
     * Files [staged] under the run that carried it — and under [key]'s names for the prompt — and returns the
     * attachments at their final paths. A prompt with no run of its own ([runId] null: one the turn under way took,
     * which the record shows without a run) is filed by [key] alone — its message id, else the moment it was sent.
     */
    suspend fun commit(agentId: String, runId: String?, staged: StagedAttachments, key: PromptKey? = null): List<MessageAttachment> = withContext(Dispatchers.IO) {
        val from = staged.dir ?: return@withContext emptyList()
        val to = if (runId != null) runDir(agentId, runId) else sentDir(agentId, key ?: PromptKey())
        val moved = staged.attachments.map { it.copy(path = File(to, File(it.path).name).path) }
        // The metadata goes in before the move so the run directory is complete the instant it appears.
        writeMeta(from, runId, moved, key)
        to.parentFile?.mkdirs()
        if (to.exists()) DiskSweep.deleteTree(to)
        // Recorded before the move: a reader that finds the old path gone finds the new one already named.
        AttachmentMoves.record(from, to)
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            DiskSweep.deleteTree(from)
        }
        moved
    }

    /**
     * The attachments [commit]ted under [runId] as a staged set again, in place — for a message the transcript filed
     * under a run that turned out not to carry it, which goes back to waiting with its images (see
     * `ConversationRepository.noteAccountQueue`); the next [commit] moves them under the run the account does start.
     */
    fun committed(agentId: String, runId: String, attachments: List<MessageAttachment>): StagedAttachments =
        if (attachments.isEmpty()) StagedAttachments.EMPTY else StagedAttachments(runDir(agentId, runId), attachments)

    /**
     * The set [stage] wrote for [attachments], read back after a restart — for a message still waiting on the account's
     * queue, whose copies are to be filed under the run it starts; empty when they are gone. One put back on the card
     * from under a run that did not carry it ([committed]) is read back where it was filed.
     */
    fun staged(attachments: List<MessageAttachment>): StagedAttachments {
        val dir = attachments.firstOrNull()?.let { File(it.path).parentFile } ?: return StagedAttachments.EMPTY
        val kept = dir.parentFile == staging || dir.parentFile?.parentFile == root
        if (!kept || attachments.any { File(it.path).parentFile != dir || !File(it.path).isFile }) return StagedAttachments.EMPTY
        return StagedAttachments(dir, attachments)
    }

    suspend fun discard(staged: StagedAttachments) = withContext(Dispatchers.IO) {
        staged.dir?.let(DiskSweep::deleteTree)
        Unit
    }

    /**
     * At start: the staged sets last written more than [maxAgeMs] ago. A process that died between [stage] and
     * [commit] or [discard] left one nothing will ever reach again. A message still waiting on the account's queue
     * reads its set back by [staged], and a week is past any wait that still delivers.
     */
    suspend fun sweepStaging(nowMillis: Long = System.currentTimeMillis(), maxAgeMs: Long = STAGING_MAX_AGE_MS) = withContext(Dispatchers.IO) {
        staging.listFiles { f -> f.isDirectory }?.forEach { if (it.lastModified() < nowMillis - maxAgeMs) DiskSweep.deleteTree(it) }
        Unit
    }

    /** Stages and commits in one step, for prompts without an optimistic bubble (launching a new agent). */
    suspend fun save(agentId: String, runId: String, images: List<PromptImage>, files: List<PromptFile> = emptyList(), key: PromptKey? = null): List<MessageAttachment> =
        commit(agentId, runId, stage(images, files), key)

    /**
     * Names a set already [commit]ted under [runId] by [key] too — for a message whose id or words were learnt after
     * its images were filed. The set's own names stay; nothing happens for a run with no set.
     */
    suspend fun name(agentId: String, runId: String, key: PromptKey) = withContext(Dispatchers.IO) {
        val dir = runDir(agentId, runId)
        val meta = readMeta(dir) ?: return@withContext
        val named = meta.copy(
            messageId = key.messageId ?: meta.messageId,
            textKey = key.text?.let { QueuePlacement.textKey(it) } ?: meta.textKey,
            sentAtMs = key.sentAtMs ?: meta.sentAtMs,
        )
        if (named != meta) File(dir, META_FILE).writeText(CursorJson.encodeToString(Meta.serializer(), named))
    }

    /**
     * Every attachment kept for [agentId]: each set keyed by the run whose prompt carried it (its images first, then
     * its files), and again by the message's id and by its words with the moment it was sent, where the set has
     * them (see [PromptAttachments]). A set kept from the record (see [keepFromRecord]) is under the message's id
     * alone, and is the one a message id names when this device has a set of its own for it too.
     */
    suspend fun forAgent(agentId: String): Map<String, List<MessageAttachment>> = withContext(Dispatchers.IO) {
        val dirs = agentDir(agentId).listFiles { file -> file.isDirectory } ?: return@withContext emptyMap()
        val out = LinkedHashMap<String, List<MessageAttachment>>()
        // The device's own sets first, the record's after: the record's copy wins the message id.
        for (dir in dirs.sortedBy { it.name.startsWith(RECORD_PREFIX) }) {
            val meta = readMeta(dir) ?: continue
            val images = meta.images.map { MessageAttachment(File(dir, it.file).path, it.width, it.height) }.filter { File(it.path).isFile }
            val files = meta.files.map { MessageAttachment.file(File(dir, it.file).path, it.name, it.mimeType, it.sizeBytes) }.filter { File(it.path).isFile }
            val all = images + files
            if (all.isEmpty()) continue
            meta.runId?.let { out[it] = all }
            meta.messageId?.let { out[PromptAttachments.messageKey(it)] = all }
            if (meta.textKey != null && meta.sentAtMs != null) out.putIfAbsent(PromptAttachments.textKey(meta.textKey, meta.sentAtMs), all)
        }
        out
    }

    /**
     * The pictures the account's record carries for the message [messageId], kept on this device: [images] are the
     * record's own with their bytes, each named by [RecordImage.key]. Written once — a picture already kept is not
     * written again — into a set of the message's own (`record-<message id>`), under the message's id and, with
     * [text] and [sentAtMs], its words; returns the set as it then stands, the record's order. Blocking: for the
     * turn builders, which run off the main thread (see `ConversationRepository.turnBuilder`).
     */
    fun keepFromRecord(agentId: String, messageId: String, images: List<Pair<RecordImage, ByteArray>>, text: String? = null, sentAtMs: Long? = null): List<MessageAttachment> {
        val dir = recordDir(agentId, messageId)
        val meta = readMeta(dir)
        val kept = meta?.images.orEmpty().associateBy { it.key }.toMutableMap()
        var changed = meta == null
        var slot = meta?.images?.size ?: 0
        for ((image, bytes) in images) {
            val key = image.key ?: continue
            if (kept[key]?.let { File(dir, it.file).isFile } == true) continue
            if (!dir.isDirectory && !dir.mkdirs()) return emptyList()
            val written = runCatching { writePreview(PromptImage(bytes, image.mimeType ?: "image/png"), dir, slot++) }.getOrNull() ?: continue
            kept[key] = ImageMeta(File(written.path).name, written.width, written.height, key)
            changed = true
        }
        if (kept.isEmpty()) return emptyList()
        if (changed || (text != null && meta?.textKey == null)) {
            dir.mkdirs()
            val next = Meta(
                runId = null, images = kept.values.toList(), messageId = messageId,
                textKey = text?.let { QueuePlacement.textKey(it) } ?: meta?.textKey, sentAtMs = sentAtMs ?: meta?.sentAtMs,
            )
            File(dir, META_FILE).writeText(CursorJson.encodeToString(Meta.serializer(), next))
        }
        return kept.values.map { MessageAttachment(File(dir, it.file).path, it.width, it.height) }.filter { File(it.path).isFile }
    }

    /** The keys of the record's pictures already kept for [messageId] (see [keepFromRecord]); empty for a message without a set. */
    fun keptFromRecord(agentId: String, messageId: String): Set<String> {
        val dir = recordDir(agentId, messageId)
        return readMeta(dir)?.images.orEmpty().mapNotNullTo(HashSet()) { image -> image.key?.takeIf { File(dir, image.file).isFile } }
    }

    suspend fun delete(agentId: String) = withContext(Dispatchers.IO) {
        DiskSweep.deleteTree(agentDir(agentId))
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        DiskSweep.deleteTree(root)
        AttachmentMoves.clear()
        Unit
    }

    private fun agentDir(agentId: String) = File(root, safeName(agentId))

    private fun runDir(agentId: String, runId: String) = File(agentDir(agentId), safeName(runId))

    /** The set kept from the record for a message (see [keepFromRecord]): apart from the runs', which a run id names. */
    private fun recordDir(agentId: String, messageId: String) = File(agentDir(agentId), RECORD_PREFIX + safeName(messageId))

    /** The set of a prompt sent from here with no run of its own (see [commit]), by the message's id, else the moment it was sent. */
    private fun sentDir(agentId: String, key: PromptKey) = File(agentDir(agentId), SENT_PREFIX + safeName(key.messageId ?: key.sentAtMs?.toString() ?: UUID.randomUUID().toString()))

    private fun writeMeta(dir: File, runId: String?, attachments: List<MessageAttachment>, key: PromptKey?) {
        val meta = Meta(
            runId,
            images = attachments.filterNot { it.isFile }.map { ImageMeta(File(it.path).name, it.width, it.height) },
            files = attachments.filter { it.isFile }.map { FileMeta(File(it.path).name, it.name.orEmpty(), it.mimeType.orEmpty(), it.sizeBytes) },
            messageId = key?.messageId,
            textKey = key?.text?.let { QueuePlacement.textKey(it) },
            sentAtMs = key?.sentAtMs,
        )
        File(dir, META_FILE).writeText(CursorJson.encodeToString(Meta.serializer(), meta))
    }

    /**
     * Copies [file] as it is. Named after the file itself (made safe, prefixed by its slot so two picks of the same
     * name stay apart) rather than an index: the system viewer the card hands it to reads the type off the extension.
     */
    private fun writeFile(file: PromptFile, dir: File, index: Int): MessageAttachment {
        val target = File(dir, "f$index-" + PromptFile.safeFileName(file.name).takeLast(MAX_FILE_NAME))
        target.writeBytes(file.bytes)
        return MessageAttachment.file(target.path, file.name, file.mimeType, file.sizeBytes.toLong())
    }

    private fun readMeta(dir: File): Meta? {
        val file = File(dir, META_FILE).takeIf { it.isFile } ?: return null
        return runCatching { CursorJson.decodeFromString(Meta.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Writes [image] for display. An image that already fits [MAX_EDGE] on its long side and [PASSTHROUGH_MAX_BYTES]
     * is kept byte for byte — the composer downsizes before upload, so that is nearly everything, and copying keeps
     * a GIF's animation and avoids a second lossy encode. Anything larger is decoded, shrunk to [MAX_EDGE] and
     * written as JPEG, or PNG when it has transparent pixels. Returns null for bytes that are not a decodable image.
     */
    private fun writePreview(image: PromptImage, dir: File, index: Int): MessageAttachment? {
        val bytes = image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (maxOf(bounds.outWidth, bounds.outHeight) <= MAX_EDGE && bytes.size <= PASSTHROUGH_MAX_BYTES) {
            val file = File(dir, "$index.${extensionFor(image.mimeType)}")
            file.writeBytes(bytes)
            return MessageAttachment(file.path, bounds.outWidth, bounds.outHeight)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val bitmap = decoded.shrinkToEdge(MAX_EDGE)
        try {
            val transparent = bitmap.hasAlpha() && bitmap.hasTransparentPixels()
            val file = File(dir, "$index.${if (transparent) "png" else "jpg"}")
            file.outputStream().buffered().use { out ->
                val ok = if (transparent) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (!ok) return null
            }
            return MessageAttachment(file.path, bitmap.width, bitmap.height)
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun Bitmap.shrinkToEdge(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return this
        val factor = maxEdge.toFloat() / edge
        return scale(maxOf(1, (width * factor).toInt()), maxOf(1, (height * factor).toInt()))
    }

    /**
     * Read a band at a time rather than a row: an opaque PNG has to be scanned to the last pixel before it can be
     * called opaque, and a row at a time is one JNI round trip per row — over a thousand of them for a full-size
     * preview — for no more certainty than a band gives.
     */
    private fun Bitmap.hasTransparentPixels(): Boolean {
        val rows = (PIXEL_BAND_PX / width).coerceIn(1, height)
        val band = IntArray(width * rows)
        var y = 0
        while (y < height) {
            val take = minOf(rows, height - y)
            getPixels(band, 0, width, 0, y, width, take)
            for (i in 0 until width * take) if (band[i] ushr 24 != 0xFF) return true
            y += take
        }
        return false
    }

    /** Cosmetic: [BitmapFactory] sniffs the real format when reading. */
    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private companion object {
        const val META_FILE = "meta.json"
        /** A record set's directory name, ahead of the message id: a run id never begins so (`run-…`), and `safeName` keeps it from a run's. */
        const val RECORD_PREFIX = "record-"
        /** The directory of a set sent from here without a run (see [commit]); apart from the runs' and the record's alike. */
        const val SENT_PREFIX = "sent-"
        /** A file name's tail kept on disk: long enough for any real name, short enough for every filesystem. */
        const val MAX_FILE_NAME = 120
        /** Just above the composer's upload ceiling (1568px), so what the API received is what gets kept. */
        const val MAX_EDGE = 1600
        /** Only a GIF can arrive both within [MAX_EDGE] and this heavy; its first frame is re-encoded instead. */
        const val PASSTHROUGH_MAX_BYTES = 2 * 1024 * 1024
        const val JPEG_QUALITY = 85
        /** Pixels held at once while looking for transparency: a quarter of a megabyte, whatever the image's shape. */
        const val PIXEL_BAND_PX = 64 * 1024
        const val STAGING_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        /**
         * IDs are `bc-…` / `run-…` today, but nothing an API returns should be trusted as a path segment: unsafe
         * characters are replaced, and a leading dot is prefixed so `..` cannot escape [root] and nothing lands in
         * the staging directory.
         */
        fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (cleaned.isEmpty() || cleaned.startsWith(".")) "_$cleaned" else cleaned
        }
    }
}
