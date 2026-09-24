package com.cursorforandroid.domain

import java.util.Base64
import java.util.Locale

/** What a file is for the app's purposes: something the media viewer shows or plays, text a file viewer shows, or neither. */
enum class MediaKind { Image, Video, Audio, Text, Other }

/**
 * A file format the app can tell by its bytes (magic numbers) or, failing that, by its name. [label] is how a row
 * names it ("HEIC image", "PDF document"), [mimeType] what another app is told, [kind] where it opens.
 */
enum class FileFormat(val label: String, val mimeType: String, val kind: MediaKind) {
    PNG("PNG image", "image/png", MediaKind.Image),
    JPEG("JPEG image", "image/jpeg", MediaKind.Image),
    GIF("GIF image", "image/gif", MediaKind.Image),
    WEBP("WebP image", "image/webp", MediaKind.Image),
    BMP("BMP image", "image/bmp", MediaKind.Image),
    ICO("Icon", "image/x-icon", MediaKind.Image),
    HEIC("HEIC image", "image/heic", MediaKind.Image),
    AVIF("AVIF image", "image/avif", MediaKind.Image),
    SVG("SVG image", "image/svg+xml", MediaKind.Image),
    TIFF("TIFF image", "image/tiff", MediaKind.Image),
    MP4("MP4 video", "video/mp4", MediaKind.Video),
    MOV("QuickTime video", "video/quicktime", MediaKind.Video),
    WEBM("WebM video", "video/webm", MediaKind.Video),
    MKV("Matroska video", "video/x-matroska", MediaKind.Video),
    AVI("AVI video", "video/x-msvideo", MediaKind.Video),
    THREE_GP("3GP video", "video/3gpp", MediaKind.Video),
    MP3("MP3 audio", "audio/mpeg", MediaKind.Audio),
    M4A("M4A audio", "audio/mp4", MediaKind.Audio),
    AAC("AAC audio", "audio/aac", MediaKind.Audio),
    WAV("WAV audio", "audio/wav", MediaKind.Audio),
    OGG("Ogg audio", "audio/ogg", MediaKind.Audio),
    OPUS("Opus audio", "audio/opus", MediaKind.Audio),
    FLAC("FLAC audio", "audio/flac", MediaKind.Audio),
    PDF("PDF document", "application/pdf", MediaKind.Other),
    ZIP("ZIP archive", "application/zip", MediaKind.Other),
    GZIP("Gzip archive", "application/gzip", MediaKind.Other),
    HTML("Web page", "text/html", MediaKind.Text),
    ;

    val isImage: Boolean get() = kind == MediaKind.Image
    val isVideo: Boolean get() = kind == MediaKind.Video
    val isAudio: Boolean get() = kind == MediaKind.Audio
    val isMedia: Boolean get() = kind == MediaKind.Image || kind == MediaKind.Video || kind == MediaKind.Audio

    companion object {
        /** The format [name]'s extension says, when it says one this app knows. */
        fun ofName(name: String): FileFormat? = BY_EXTENSION[extensionOf(name)]

        fun extensionOf(name: String): String =
            name.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

        /** The format by the bytes themselves, whatever the name says; null for text, or a format this app does not know. */
        fun sniff(bytes: ByteArray): FileFormat? {
            val b = bytes
            fun at(i: Int, vararg values: Int): Boolean = b.size >= i + values.size && values.indices.all { b[i + it] == values[it].toByte() }
            fun ascii(i: Int, text: String): Boolean = b.size >= i + text.length && text.indices.all { b[i + it] == text[it].code.toByte() }
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(0, "RIFF") && ascii(8, "WAVE") -> WAV
                ascii(0, "RIFF") && ascii(8, "AVI ") -> AVI
                ascii(0, "BM") && at(6, 0, 0, 0, 0) && b.size >= 18 && (b[14].toInt() and 0xFF) in BMP_HEADER_SIZES && at(15, 0, 0, 0) -> BMP
                at(0, 0x00, 0x00, 0x01, 0x00) && b.size >= 22 -> ICO
                at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A) -> TIFF
                ascii(4, "ftyp") -> isoBrand(b)
                at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (containsAscii(b, "webm", 64)) WEBM else MKV
                ascii(0, "ID3") -> MP3
                b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xF6) == 0xF0 -> AAC
                b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> MP3
                ascii(0, "OggS") -> if (containsAscii(b, "OpusHead", 64)) OPUS else OGG
                ascii(0, "fLaC") -> FLAC
                ascii(0, "%PDF") -> PDF
                at(0, 0x50, 0x4B, 0x03, 0x04) -> ZIP
                at(0, 0x1F, 0x8B) -> GZIP
                else -> textFormat(b)
            }
        }

        /** An ISO base media file (`….ftyp<brand>`): a video, an M4A, a HEIC or an AVIF, by the major brand and the compatible ones. */
        private fun isoBrand(b: ByteArray): FileFormat {
            val boxSize = (0..3).fold(0L) { acc, i -> (acc shl 8) or (b[i].toLong() and 0xFF) }
            val end = boxSize.coerceIn(12, minOf(b.size, 64).toLong().coerceAtLeast(12)).toInt()
            // The major brand at 8, the minor version at 12 (not a brand), the compatible brands from 16.
            val brands = (listOf(8) + (16 until end step 4)).mapNotNull { i -> if (i + 4 <= b.size) String(b, i, 4, Charsets.ISO_8859_1) else null }
            if (brands.isEmpty()) return MP4
            return when {
                brands.any { it == "avif" || it == "avis" } -> AVIF
                brands.any { it in HEIF_BRANDS } -> HEIC
                brands.first() == "qt  " -> MOV
                brands.any { it == "M4A " || it == "M4B " || it == "M4P " } -> M4A
                brands.first().startsWith("3g") -> THREE_GP
                else -> MP4
            }
        }

        /** SVG and HTML are text: told apart by their opening once a byte-order mark, an XML prolog, comments and blanks are skipped. */
        private fun textFormat(b: ByteArray): FileFormat? {
            val head = String(b, 0, minOf(b.size, 1024), Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            val lower = head.lowercase(Locale.ROOT)
            if (lower.startsWith("<svg") || (lower.startsWith("<?xml") || lower.startsWith("<!--") || lower.startsWith("<!doctype svg")) && lower.contains("<svg")) return SVG
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.startsWith("<head") || lower.startsWith("<body")) return HTML
            return null
        }

        private fun containsAscii(b: ByteArray, text: String, within: Int): Boolean =
            String(b, 0, minOf(b.size, within), Charsets.ISO_8859_1).contains(text)

        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
        /** The DIB header sizes a real BMP carries at byte 14, so a text file that begins "BM" is not taken for one. */
        private val BMP_HEADER_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)

        private val BY_EXTENSION: Map<String, FileFormat> = mapOf(
            "png" to PNG, "jpg" to JPEG, "jpeg" to JPEG, "jpe" to JPEG, "gif" to GIF, "webp" to WEBP, "bmp" to BMP, "ico" to ICO,
            "heic" to HEIC, "heif" to HEIC, "avif" to AVIF, "svg" to SVG, "tif" to TIFF, "tiff" to TIFF,
            "mp4" to MP4, "m4v" to MP4, "mov" to MOV, "webm" to WEBM, "mkv" to MKV, "avi" to AVI, "3gp" to THREE_GP,
            "mp3" to MP3, "m4a" to M4A, "aac" to AAC, "wav" to WAV, "ogg" to OGG, "oga" to OGG, "opus" to OPUS, "flac" to FLAC,
            "pdf" to PDF, "zip" to ZIP, "gz" to GZIP, "tgz" to GZIP, "html" to HTML, "htm" to HTML,
        )
    }
}

/**
 * The bytes a file's read actually carried, once whatever they came wrapped in is taken off: the file itself, base64
 * text of it, a `data:` URI, a JSON object carrying it under a familiar key — or a Git LFS pointer standing in for a
 * file the repository keeps elsewhere. [format] is what the unwrapped bytes are, by their magic numbers, else by
 * the name; null for plain text or a format this app does not know.
 */
sealed interface FileBytes {
    data class Plain(val bytes: ByteArray, val format: FileFormat?, val unwrapped: Wrapper? = null) : FileBytes {
        override fun equals(other: Any?): Boolean = other is Plain && other.format == format && other.unwrapped == unwrapped && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + (format?.hashCode() ?: 0)
    }

    /** A Git LFS pointer (`version https://git-lfs.github.com/spec/v1`, `oid sha256:…`, `size N`): the file is not here. */
    data class LfsPointer(val oid: String?, val sizeBytes: Long?) : FileBytes

    /** What the bytes came wrapped in, when they did. */
    enum class Wrapper { Base64, DataUri, Json }

    companion object {
        /**
         * Reads [bytes] as the file named [name]. A payload that does not sniff as binary is looked at as text: a
         * `data:` URI, a JSON object with the file under `content` / `data` / `bytes` / `imageData` / `blobData`, or
         * one base64 run, each decoded when what comes out sniffs as a known format — never text into text, so a
         * source file that happens to be valid base64 stays a source file.
         */
        fun of(bytes: ByteArray, name: String = ""): FileBytes {
            val sniffed = FileFormat.sniff(bytes)
            if (sniffed != null && sniffed != FileFormat.HTML) return Plain(bytes, sniffed)
            if (bytes.size in 1..MAX_WRAPPED_BYTES && looksLikeText(bytes)) {
                val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
                lfsPointer(text)?.let { return it }
                unwrapText(text)?.let { return it }
            }
            return Plain(bytes, sniffed ?: FileFormat.ofName(name)?.takeUnless { it.isMedia && bytes.isNotEmpty() && looksLikeText(bytes) && it != FileFormat.SVG })
        }

        private fun unwrapText(text: String): Plain? {
            if (text.startsWith("data:", ignoreCase = true)) {
                DATA_URI.find(text)?.let { m ->
                    val payload = m.groupValues[3]
                    val decoded = if (m.groupValues[2].contains("base64", ignoreCase = true)) decodeBase64(payload)
                    else runCatching { java.net.URLDecoder.decode(payload, "UTF-8").toByteArray() }.getOrNull()
                    if (decoded != null) FileFormat.sniff(decoded)?.let { return Plain(decoded, it, Wrapper.DataUri) }
                }
            }
            if (text.startsWith("{")) {
                for (key in JSON_KEYS) {
                    val value = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
                    val inner = unwrapText(value.trim())
                    if (inner != null) return inner.copy(unwrapped = Wrapper.Json)
                    val decoded = decodeBase64(value) ?: continue
                    FileFormat.sniff(decoded)?.let { return Plain(decoded, it, Wrapper.Json) }
                }
            }
            if (text.length >= MIN_BASE64_CHARS && BASE64_TEXT.matches(text)) {
                val decoded = decodeBase64(text) ?: return null
                val format = FileFormat.sniff(decoded)?.takeUnless { it == FileFormat.HTML } ?: return null
                return Plain(decoded, format, Wrapper.Base64)
            }
            return null
        }

        private fun lfsPointer(text: String): LfsPointer? {
            if (!text.startsWith("version https://git-lfs.github.com/spec/")) return null
            val oid = Regex("""(?m)^oid (\S+)""").find(text)?.groupValues?.get(1)
            val size = Regex("""(?m)^size (\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            return LfsPointer(oid, size)
        }

        private fun decodeBase64(text: String): ByteArray? {
            val clean = text.filterNot { it.isWhitespace() }
            if (clean.isEmpty()) return null
            return runCatching { Base64.getDecoder().decode(clean) }
                .recoverCatching { Base64.getUrlDecoder().decode(clean) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }

        /** No NUL and mostly printable in the first kilobyte: what git and every editor call text. */
        fun looksLikeText(bytes: ByteArray): Boolean {
            val probe = minOf(bytes.size, 1024)
            if (probe == 0) return true
            var control = 0
            for (i in 0 until probe) {
                val c = bytes[i].toInt() and 0xFF
                if (c == 0) return false
                if (c < 0x09 || (c in 0x0E..0x1F)) control++
            }
            return control * 20 < probe
        }

        private val DATA_URI = Regex("""^data:([^;,]*)(;[^,]*)?,(.*)$""", RegexOption.DOT_MATCHES_ALL)
        private val BASE64_TEXT = Regex("""^[A-Za-z0-9+/_\-\s]+={0,2}\s*$""")
        private val JSON_KEYS = listOf("content", "data", "bytes", "imageData", "image_data", "blobData", "blob_data", "base64")
        private const val MIN_BASE64_CHARS = 16
        /** A wrapper is looked into up to this size; a larger text is what it says it is. */
        private const val MAX_WRAPPED_BYTES = 48 * 1024 * 1024
    }
}
