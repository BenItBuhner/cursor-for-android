package com.cursorforandroid.domain

import java.util.Locale

/**
 * A file of any type attached to a prompt in Extended mode — a PDF, a recording, an archive, a log, a picture or a
 * video straight from the gallery — carried the way the desktop Agents Window carries one: uploaded through the
 * account's `PresignPromptUpload` / `CompletePromptUpload` and referenced from the prompt by its upload id, as an
 * `agent.v1.SelectedImage` when it is an image and an `agent.v1.SelectedDocument {uuid, filename, mime_type,
 * prompt_upload_ref}` otherwise (Cursor 3.20.21 `cloudAgentPromptUpload.js`, `HKy` and `WKy`). A pasted or
 * shared-in image is not a file: it stays a [PromptImage] and travels inline, as `prompt.images[]` / `selected_images[]`.
 */
class PromptFile(
    val bytes: ByteArray,
    /** The name the picker reported, as the desktop sends the `File.name`; what the agent sees the file as. */
    val name: String,
    /** The provider's type, or one read off the extension; `application/octet-stream` when neither says. */
    val mimeType: String,
    /**
     * Where the bytes already are on the account, once the upload that started when the file was attached has been
     * completed: the prompt then carries this reference and no upload waits on the send. Null until then, and for a
     * file the account has no upload path for, which travels inline.
     */
    val upload: UploadRef? = null,
) {
    val sizeBytes: Int get() = bytes.size
    val kind: PromptFileKind get() = PromptFileKind.of(name, mimeType)

    /** The same file, carrying [ref] (or none). */
    fun withUpload(ref: UploadRef?): PromptFile = if (ref == upload) this else PromptFile(bytes, name, mimeType, ref)

    companion object {
        /** How many files one prompt may carry — the desktop's `cloudMaxDocumentAttachmentsPerRequest`. */
        const val MAX_COUNT = 5
        /**
         * 15 MB, the ceiling asked for here and the figure the desktop uses for a video's inline bytes
         * (`promptAttachmentUtil.js` `lCm`, `gemini_video_attachment_config.inlineMaxBytes`) and the documented API
         * for an image; its own cloud-document guard is stricter (`RWa`, 10 MB), which the request notes.
         */
        const val MAX_BYTES = 15L * 1024 * 1024
        const val OCTET_STREAM = "application/octet-stream"

        /** `Files must be 15 MB or smaller.`, in the words of the desktop's guard with this build's ceiling. */
        val TOO_LARGE_MESSAGE = "Files must be ${MAX_BYTES / (1024 * 1024)} MB or smaller."

        /** The size refusal in the words of what was picked: `Videos must be…` for a video, `Images must be…` for an image, [TOO_LARGE_MESSAGE] otherwise. */
        fun tooLargeMessage(mimeType: String?, name: String): String = when (PromptFileKind.of(name, mimeType.orEmpty())) {
            PromptFileKind.Video -> "Videos must be ${MAX_BYTES / (1024 * 1024)} MB or smaller."
            PromptFileKind.Image -> "Images must be ${MAX_BYTES / (1024 * 1024)} MB or smaller."
            else -> TOO_LARGE_MESSAGE
        }

        /** The provider's type when it names one, else what the extension says, else [OCTET_STREAM]. */
        fun resolveMimeType(declared: String?, name: String): String {
            val cleaned = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (!cleaned.isNullOrEmpty() && cleaned != OCTET_STREAM && cleaned != "*/*" && !cleaned.endsWith("/*") && '/' in cleaned) return cleaned
            return mimeTypeForExtension(extensionOf(name)) ?: cleaned?.takeIf { '/' in it && !it.endsWith("/*") } ?: OCTET_STREAM
        }

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
        }

        /** `12 KB`, `3.4 MB`: the size as a chip shows it. */
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)).replace(".0 MB", " MB")
        }

        /**
         * The desktop's `IWa`: a name safe as one path segment — anything outside `[A-Za-z0-9._-]` becomes `_`, and a
         * name that is empty, `.` or `..` becomes `attachment`. For the copies kept on this device; the wire carries [name] as it is.
         */
        fun safeFileName(name: String): String {
            val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "attachment" else cleaned
        }

        private fun mimeTypeForExtension(extension: String): String? = when (extension) {
            "pdf" -> "application/pdf"
            "txt", "log", "ini", "cfg", "conf" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json", "har" -> "application/json"
            "xml" -> "application/xml"
            "yaml", "yml" -> "application/x-yaml"
            "toml" -> "application/toml"
            "html", "htm" -> "text/html"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> null
        }
    }
}

/**
 * A completed prompt upload as the account knows it: the `PresignPromptUpload` id the prompt references
 * (`prompt_upload_ref {upload_id}`), the S3 upload id `AbortPromptUpload` takes to drop it again, and the `uuid` the
 * `SelectedImage` / `SelectedDocument` is minted with — kept with the draft so a restart sends what is already up.
 */
data class UploadRef(val uploadId: String, val s3UploadId: String, val uuid: String)

/**
 * What a file reads as, for its chip's glyph and card: the desktop's `xWa` buckets (image, document, video) widened
 * to the audio, archive and code files the picker here lets through.
 */
enum class PromptFileKind(val label: String) {
    Image("Image"),
    Video("Video"),
    Audio("Audio"),
    Pdf("PDF"),
    Text("Text"),
    Code("Code"),
    Archive("Archive"),
    Other("File");

    companion object {
        private val textExtensions = setOf("txt", "log", "md", "markdown", "csv", "ini", "cfg", "conf", "rtf")
        private val codeExtensions = setOf(
            "json", "har", "xml", "yaml", "yml", "toml", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
            "cs", "swift", "m", "sh", "bash", "zsh", "sql", "html", "htm", "css", "scss", "gradle", "properties", "env", "diff", "patch",
        )
        private val archiveExtensions = setOf("zip", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "jar", "apk", "aab")
        /** The desktop's `cCm`: what it calls a video by name when the provider names no type. */
        private val videoExtensions = setOf("mp4", "mov", "webm", "mkv", "avi", "wmv", "m4v", "mpeg", "mpg")
        private val audioExtensions = setOf("mp3", "m4a", "wav", "ogg", "oga", "flac", "aac", "opus")
        private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "svg", "avif")

        fun of(name: String, mimeType: String): PromptFileKind {
            val mime = mimeType.lowercase(Locale.ROOT)
            val extension = PromptFile.extensionOf(name)
            return when {
                mime.startsWith("image/") || (mime.isEmpty() || mime == PromptFile.OCTET_STREAM) && extension in imageExtensions -> Image
                mime.startsWith("video/") || (mime.isEmpty() || mime == PromptFile.OCTET_STREAM) && extension in videoExtensions -> Video
                mime.startsWith("audio/") || (mime.isEmpty() || mime == PromptFile.OCTET_STREAM) && extension in audioExtensions -> Audio
                mime == "application/pdf" || extension == "pdf" -> Pdf
                extension in archiveExtensions || mime in setOf("application/zip", "application/gzip", "application/x-tar", "application/x-7z-compressed", "application/vnd.rar") -> Archive
                extension in codeExtensions || mime in setOf("application/json", "application/xml", "text/xml", "application/x-yaml", "text/yaml", "text/html") -> Code
                mime.startsWith("text/") || extension in textExtensions -> Text
                else -> Other
            }
        }
    }
}
