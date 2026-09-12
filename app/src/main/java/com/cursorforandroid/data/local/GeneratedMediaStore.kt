package com.cursorforandroid.data.local

import android.content.Context
import java.io.File

/**
 * Device-local copies of the images an agent generates. The stream delivers a generated image's pixels once, as
 * base64 inside the `generateImage` result, and nothing serves them again afterwards unless the agent also published
 * the file as an artifact. So the bytes are written here the moment they arrive — `files/generated/<agent>/<call>.png`
 * — and the trace keeps only the `file://` URI, which is what keeps an image out of the trace file and lets the
 * transcript and the panel show it after a restart.
 */
class GeneratedMediaStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "generated")

    /** Writes [bytes] for the call and returns the `file://` URI to read them back from; null when the write failed. */
    fun save(agentId: String, callId: String, bytes: ByteArray, mimeType: String?): String? {
        if (bytes.isEmpty()) return null
        val dir = File(root, safeName(agentId))
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val file = File(dir, "${safeName(callId)}.${extensionFor(mimeType)}")
        return runCatching {
            file.writeBytes(bytes)
            "file://${file.absolutePath}"
        }.getOrNull()
    }

    /** Every image kept for [agentId], newest write last. */
    fun forAgent(agentId: String): List<File> =
        File(root, safeName(agentId)).listFiles { file -> file.isFile }?.sortedBy { it.lastModified() }.orEmpty()

    fun delete(agentId: String) {
        File(root, safeName(agentId)).deleteRecursively()
    }

    fun clear() {
        root.deleteRecursively()
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "png"
    }

    private companion object {
        /** Ids are `bc-…` / `call-…` today, but nothing an API returns is trusted as a path segment. */
        fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (cleaned.isEmpty() || cleaned.startsWith(".")) "_$cleaned" else cleaned
        }
    }
}
