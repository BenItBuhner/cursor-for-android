package com.cursorforandroid.domain

import java.util.Base64

/** An image attached to a prompt. The API accepts at most 5 images of up to 15 MB each (png / jpeg / gif / webp). */
class PromptImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    val sizeBytes: Int get() = bytes.size

    /**
     * The bytes as `prompt.images[].data` / `SelectedImage.data` carry them. Encoded once and kept; the composer
     * computes it the moment the image is picked (off the main thread, see `PendingAttachment`), so the request that
     * goes out on send has nothing left to encode.
     */
    val base64: String by lazy { Base64.getEncoder().encodeToString(bytes) }

    companion object {
        const val MAX_COUNT = 5
        const val MAX_BYTES = 15L * 1024 * 1024
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        fun isSupported(mimeType: String?): Boolean = mimeType != null && mimeType.lowercase() in SUPPORTED_MIME_TYPES
    }
}
