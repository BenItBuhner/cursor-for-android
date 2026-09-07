package com.cursorforandroid.domain

/** An image attached to a prompt. The API accepts at most 5 images of up to 15 MB each (png / jpeg / gif / webp). */
class PromptImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    val sizeBytes: Int get() = bytes.size

    companion object {
        const val MAX_COUNT = 5
        const val MAX_BYTES = 15L * 1024 * 1024
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        fun isSupported(mimeType: String?): Boolean = mimeType != null && mimeType.lowercase() in SUPPORTED_MIME_TYPES
    }
}
