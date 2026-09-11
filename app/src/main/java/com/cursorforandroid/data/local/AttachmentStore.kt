package com.cursorforandroid.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptImage
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
 * Device-local copies of the images attached to prompts.
 *
 * `GET /v0/agents/{id}/conversation` returns a `user_message` as text only: the `prompt.images` that went up with a
 * prompt never come back down, so a screenshot the user attached would vanish the moment the optimistic bubble is
 * replaced by history. Sending a prompt therefore writes each image here — as sent when it is already display-sized,
 * downscaled otherwise — filed by agent and run (`files/attachments/<agent>/<run>/`), and `TimelineBuilder` pairs them
 * with the user message that began that run.
 *
 * A prompt is [stage]d before the request goes out so the optimistic bubble already shows its images; on success it is
 * [commit]ted under the run the API returned, on failure [discard]ed.
 */
class AttachmentStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "attachments")
    private val staging = File(root, ".staging")

    @Serializable
    private data class Meta(val runId: String, val images: List<ImageMeta>)

    @Serializable
    private data class ImageMeta(val file: String, val width: Int, val height: Int)

    /** Writes previews of [images] into a scratch directory. Images that cannot be decoded are skipped. */
    suspend fun stage(images: List<PromptImage>): StagedAttachments = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext StagedAttachments.EMPTY
        val dir = File(staging, UUID.randomUUID().toString())
        if (!dir.mkdirs() && !dir.isDirectory) return@withContext StagedAttachments.EMPTY
        val written = images.mapIndexedNotNull { index, image -> runCatching { writePreview(image, dir, index) }.getOrNull() }
        if (written.isEmpty()) {
            dir.deleteRecursively()
            return@withContext StagedAttachments.EMPTY
        }
        StagedAttachments(dir, written)
    }

    /** Files [staged] under the run that carried it and returns the attachments at their final paths. */
    suspend fun commit(agentId: String, runId: String, staged: StagedAttachments): List<MessageAttachment> = withContext(Dispatchers.IO) {
        val from = staged.dir ?: return@withContext emptyList()
        val to = runDir(agentId, runId)
        val moved = staged.attachments.map { it.copy(path = File(to, File(it.path).name).path) }
        // The metadata goes in before the move so the run directory is complete the instant it appears.
        writeMeta(from, runId, moved)
        to.parentFile?.mkdirs()
        if (to.exists()) to.deleteRecursively()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
        moved
    }

    suspend fun discard(staged: StagedAttachments) = withContext(Dispatchers.IO) {
        staged.dir?.deleteRecursively()
        Unit
    }

    /** Stages and commits in one step, for prompts without an optimistic bubble (launching a new agent). */
    suspend fun save(agentId: String, runId: String, images: List<PromptImage>): List<MessageAttachment> = commit(agentId, runId, stage(images))

    /** Every attachment kept for [agentId], keyed by the run whose prompt carried it. */
    suspend fun forAgent(agentId: String): Map<String, List<MessageAttachment>> = withContext(Dispatchers.IO) {
        val dirs = agentDir(agentId).listFiles { file -> file.isDirectory } ?: return@withContext emptyMap()
        dirs.mapNotNull { dir ->
            val meta = readMeta(dir) ?: return@mapNotNull null
            val images = meta.images.map { MessageAttachment(File(dir, it.file).path, it.width, it.height) }.filter { File(it.path).isFile }
            if (images.isEmpty()) null else meta.runId to images
        }.toMap()
    }

    suspend fun delete(agentId: String) = withContext(Dispatchers.IO) {
        agentDir(agentId).deleteRecursively()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        Unit
    }

    private fun agentDir(agentId: String) = File(root, safeName(agentId))

    private fun runDir(agentId: String, runId: String) = File(agentDir(agentId), safeName(runId))

    private fun writeMeta(dir: File, runId: String, attachments: List<MessageAttachment>) {
        val meta = Meta(runId, attachments.map { ImageMeta(File(it.path).name, it.width, it.height) })
        File(dir, META_FILE).writeText(CursorJson.encodeToString(Meta.serializer(), meta))
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
        /** Just above the composer's upload ceiling (1568px), so what the API received is what gets kept. */
        const val MAX_EDGE = 1600
        /** Only a GIF can arrive both within [MAX_EDGE] and this heavy; its first frame is re-encoded instead. */
        const val PASSTHROUGH_MAX_BYTES = 2 * 1024 * 1024
        const val JPEG_QUALITY = 85
        /** Pixels held at once while looking for transparency: a quarter of a megabyte, whatever the image's shape. */
        const val PIXEL_BAND_PX = 64 * 1024

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
