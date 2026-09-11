package com.cursorforandroid.domain

import java.net.URLDecoder
import java.util.Base64

/**
 * A piece of message text once media markup has been separated out: agent replies embed screenshots and
 * recordings as raw HTML (`<img src=… />`, `<video src=…></video>`) or markdown images (`![alt](src)`), and the
 * chat renderer needs to draw those instead of showing the tags.
 */
sealed interface MediaSegment {
    data class Text(val text: String) : MediaSegment
    data class Image(val src: String, val alt: String?) : MediaSegment
    data class Video(val src: String, val poster: String?) : MediaSegment
}

/** Finds `<img>`, `<video>` and `![alt](src)` inside message text. Pure string processing, no rendering. */
object MediaMarkup {
    private val candidate = Regex("""<img\b|<video\b|!\[""", RegexOption.IGNORE_CASE)
    private val imgTag = Regex("""<img\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val videoOpen = Regex("""<video\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val videoClose = Regex("""</video\s*>""", RegexOption.IGNORE_CASE)
    private val sourceTag = Regex("""<source\b([^>]*)>""", RegexOption.IGNORE_CASE)
    /** `![alt](src "title")`, with the optional `<…>` around the destination CommonMark allows. */
    private val markdownImage = Regex("""!\[([^\]]*)]\(\s*<?([^\s)>]+)>?(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)""")
    private val attribute = Regex("""([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    // Link wrappers around an image: `<a href><img></a>` and `[![alt](img)](href)`. The image is what matters.
    private val anchorOpenBefore = Regex("""<a\b[^>]*>\s*$""", RegexOption.IGNORE_CASE)
    private val anchorCloseAfter = Regex("""\s*</a\s*>""", RegexOption.IGNORE_CASE)
    private val bracketBefore = Regex("""\[\s*$""")
    private val linkCloseAfter = Regex("""\s*]\([^)\s]*\)""")
    // Image-wrapped <a>…</a> is consumed by [linkWrapper]. Standalone <a href> stays in the text so the inline
    // renderer can turn it into a link; leftover video/source tags are never meaningful as prose.
    private val strayTags = Regex("""</video\s*>|<source\b[^>]*>""", RegexOption.IGNORE_CASE)
    /**
     * An opening tag or markdown image that has not been closed yet — what a streaming reply looks like mid-token.
     * A `<video>` without `</video>` only counts for a short stretch so a missing close tag cannot hide paragraphs.
     */
    private val partialTail = Regex(
        """(?:<img\b[^>]*|<video\b[^>]*|<video\b[^>]*>(?:(?!</video>).){0,160}|!\[[^\]]*(?:]\([^)]*)?)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun containsMedia(text: String): Boolean = split(text).any { it !is MediaSegment.Text }

    /** Splits [text] into text and media segments, in order. Whitespace-only text between media is dropped. */
    fun split(text: String): List<MediaSegment> {
        val out = mutableListOf<MediaSegment>()
        val pending = StringBuilder()
        fun flushText() {
            val cleaned = strayTags.replace(pending, "").trim()
            if (cleaned.isNotEmpty()) out += MediaSegment.Text(cleaned)
            pending.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val m = candidate.find(text, i)
            if (m == null) {
                pending.append(text, i, text.length)
                break
            }
            pending.append(text, i, m.range.first)
            val parsed = when (m.value.lowercase()) {
                "<img" -> parseImg(text, m.range.first)
                "<video" -> parseVideo(text, m.range.first)
                else -> parseMarkdownImage(text, m.range.first)
            }
            if (parsed == null) {
                // Not real markup (e.g. a bare `![`): keep the characters and move on.
                pending.append(m.value)
                i = m.range.last + 1
                continue
            }
            val (segment, tagEnd) = parsed
            if (segment == null) {
                // A tag without a source is dropped; the text around it stays one run.
                i = tagEnd
                if (pending.isNotEmpty() && pending.last().isWhitespace()) while (i < text.length && text[i] == ' ') i++
                continue
            }
            var end = tagEnd
            if (segment is MediaSegment.Image) {
                val wrapper = linkWrapper(pending, text, end)
                if (wrapper != null) {
                    pending.setLength(wrapper.first)
                    end = wrapper.second
                }
            }
            flushText()
            out += segment
            i = end
        }
        flushText()
        return out
    }

    /** Media replaced by their alt text (or nothing): for one-line previews and notifications. */
    fun stripped(text: String): String = split(text).joinToString(" ") {
        when (it) {
            is MediaSegment.Text -> it.text
            is MediaSegment.Image -> it.alt.orEmpty()
            is MediaSegment.Video -> ""
        }
    }.replace(Regex("[ \\t]+"), " ").trim()

    /** Drops an unfinished tag at the end of streaming text so the raw markup never flashes before it completes. */
    fun trimPartialTail(text: String): String {
        val m = partialTail.find(text) ?: return text
        return text.substring(0, m.range.first).trimEnd()
    }

    /**
     * When the image at [imageEnd] is wrapped in a link, returns the length [before] should be cut to and the
     * index after the closing link markup.
     */
    private fun linkWrapper(before: CharSequence, text: String, imageEnd: Int): Pair<Int, Int>? {
        anchorOpenBefore.find(before)?.let { open ->
            anchorCloseAfter.matchAt(text, imageEnd)?.let { close -> return open.range.first to close.range.last + 1 }
        }
        bracketBefore.find(before)?.let { open ->
            linkCloseAfter.matchAt(text, imageEnd)?.let { close -> return open.range.first to close.range.last + 1 }
        }
        return null
    }

    private fun parseImg(text: String, start: Int): Pair<MediaSegment?, Int>? {
        val m = imgTag.matchAt(text, start) ?: return null
        val attrs = attributes(m.groupValues[1])
        val src = attrs["src"]?.takeIf { it.isNotBlank() } ?: return null to m.range.last + 1
        return MediaSegment.Image(src, attrs["alt"]?.takeIf { it.isNotBlank() }) to m.range.last + 1
    }

    private fun parseVideo(text: String, start: Int): Pair<MediaSegment?, Int>? {
        val open = videoOpen.matchAt(text, start) ?: return null
        val attrs = attributes(open.groupValues[1])
        val close = videoClose.find(text, open.range.last + 1)
        val inner = if (close != null) text.substring(open.range.last + 1, close.range.first) else ""
        val end = close?.range?.last?.plus(1) ?: (open.range.last + 1)
        val src = attrs["src"]?.takeIf { it.isNotBlank() }
            ?: sourceTag.findAll(inner).mapNotNull { attributes(it.groupValues[1])["src"] }.firstOrNull { it.isNotBlank() }
            ?: return null to end
        return MediaSegment.Video(src, attrs["poster"]?.takeIf { it.isNotBlank() }) to end
    }

    private fun parseMarkdownImage(text: String, start: Int): Pair<MediaSegment?, Int>? {
        val m = markdownImage.matchAt(text, start) ?: return null
        return MediaSegment.Image(decodeEntities(m.groupValues[2]), m.groupValues[1].trim().ifEmpty { null }) to m.range.last + 1
    }

    private fun attributes(raw: String): Map<String, String> = attribute.findAll(raw).associate { m ->
        m.groupValues[1].lowercase() to decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

/**
 * Cloud agents write artifacts to `/opt/cursor/artifacts/…` inside the VM and reference that absolute path in
 * their replies. The API serves the same files as `artifacts/<relative path>` through
 * `GET /v1/agents/{id}/artifacts/download?path=…`, which only accepts the relative form.
 */
object ArtifactPaths {
    const val VM_ROOT = "/opt/cursor/artifacts/"
    private const val API_ROOT = "artifacts/"

    /** The `path` query value for [src], or null when [src] does not point into the artifacts directory. */
    fun apiPath(src: String): String? {
        val trimmed = src.trim()
        if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.startsWith("data:", ignoreCase = true)) return null
        val relative = when {
            trimmed.startsWith(VM_ROOT) -> trimmed.removePrefix(VM_ROOT)
            trimmed.startsWith(VM_ROOT.removePrefix("/")) -> trimmed.removePrefix(VM_ROOT.removePrefix("/"))
            trimmed.removePrefix("./").removePrefix("/").startsWith(API_ROOT) -> trimmed.removePrefix("./").removePrefix("/").removePrefix(API_ROOT)
            // Other absolute spellings such as /workspace/artifacts/x.png: keep everything after the artifacts segment.
            trimmed.startsWith("/") && trimmed.contains("/$API_ROOT") -> trimmed.substringAfter("/$API_ROOT")
            else -> return null
        }
        val normalized = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (normalized.isEmpty() || normalized.any { it == ".." }) return null
        return API_ROOT + normalized.joinToString("/")
    }

    /** `artifacts/screenshots/demo.png` → `demo.png`. */
    fun fileName(path: String): String = path.trimEnd('/').substringBefore('?').substringBefore('#').substringAfterLast('/').ifEmpty { path }
}

/** Where the bytes of a media element come from once the `src` string has been interpreted. */
sealed interface MediaRef {
    /** Stable identity for caches; the same artifact keeps the same key while its presigned URL rotates. */
    val cacheKey: String
    /** Short name for captions and error rows. */
    val label: String

    data class Remote(val url: String) : MediaRef {
        override val cacheKey: String get() = "url:$url"
        override val label: String get() = ArtifactPaths.fileName(url)
    }

    /** An artifact of [agentId]; [path] is the `artifacts/…` form the download endpoint expects. */
    data class Artifact(val agentId: String, val path: String) : MediaRef {
        override val cacheKey: String get() = "artifact:$agentId:$path"
        override val label: String get() = ArtifactPaths.fileName(path)
    }

    /** A `data:` URI decoded up front. */
    class Inline(val bytes: ByteArray, val mimeType: String?) : MediaRef {
        override val cacheKey: String get() = "inline:${bytes.contentHashCode()}:${bytes.size}"
        override val label: String get() = "Embedded image"
    }

    /** A file on this device (`file://…`): an image the agent generated, kept the moment the stream delivered it. */
    data class Local(val path: String) : MediaRef {
        override val cacheKey: String get() = "file:$path"
        override val label: String get() = ArtifactPaths.fileName(path)
    }

    /** A `src` we cannot fetch: a repository-relative path, a VM path with no agent to ask, an unknown scheme. */
    data class Unavailable(val src: String) : MediaRef {
        override val cacheKey: String get() = "unavailable:$src"
        override val label: String get() = ArtifactPaths.fileName(src)
    }

    companion object {
        private val dataUri = Regex("""^data:([^;,]+)?(;[^,]*)?,(.*)$""", RegexOption.DOT_MATCHES_ALL)

        /** Interprets a `src` attribute for media shown inside [agentId]'s conversation (null outside one). */
        fun parse(src: String, agentId: String?): MediaRef {
            val trimmed = src.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return Remote(trimmed)
            // Both spellings `java.io.File.toURI()` (`file:/x`) and `Uri.fromFile` (`file:///x`) produce.
            if (trimmed.startsWith("file:", ignoreCase = true)) {
                val path = runCatching { java.net.URI(trimmed).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: return Unavailable(trimmed)
                return Local(path)
            }
            dataUri.find(trimmed)?.let { m ->
                val isBase64 = m.groupValues[2].contains("base64", ignoreCase = true)
                val bytes = runCatching {
                    if (isBase64) Base64.getMimeDecoder().decode(m.groupValues[3].trim()) else URLDecoder.decode(m.groupValues[3], "UTF-8").toByteArray()
                }.getOrNull()
                return if (bytes == null || bytes.isEmpty()) Unavailable("data:") else Inline(bytes, m.groupValues[1].ifBlank { null })
            }
            val path = ArtifactPaths.apiPath(trimmed)
            return if (path != null && agentId != null) Artifact(agentId, path) else Unavailable(trimmed)
        }
    }
}
