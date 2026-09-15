package com.cursorforandroid.domain

import java.net.URLDecoder

/**
 * A path into an Agent Store as agents write them into their replies: `/cursor/stores/<mount>/<relative path>`,
 * the directory the store is mounted at inside a cloud agent's VM. The mount names the store — a cloud agent's by
 * its id (`bc-…`, a Project's coordinator and its shared context), the agent's own as `self`, the user's or the
 * team's as `user` / `team` — and the rest is the path inside it. Cursor's own client resolves the same paths
 * against the Project's Context store; here they are read through the account's store reads in Extended mode and
 * pointed at the Project on cursor.com without it.
 */
data class StorePath(val mount: String, val relativePath: String) {
    val fileName: String get() = relativePath.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()

    /** The cloud agent whose store the mount names, when it names one. */
    val agentId: String? get() = mount.takeIf { it.startsWith("bc-") }

    /** The agent's own store: whose depends on which chat the path is read in. */
    val isSelf: Boolean get() = mount == SELF

    val isImage: Boolean get() = extension in IMAGE_EXTENSIONS
    val isVideo: Boolean get() = extension in VIDEO_EXTENSIONS
    val isMarkdown: Boolean get() = extension in MARKDOWN_EXTENSIONS

    /** The store the path is in, once `self` is read against the chat of [chatAgentId]: the owner's id, or null when the mount names no agent. */
    fun ownerId(chatAgentId: String?): String? = agentId ?: chatAgentId?.takeIf { isSelf }

    /** The path as written, for captions and copies. */
    val text: String get() = "$ROOT$mount/$relativePath"

    companion object {
        const val ROOT = "/cursor/stores/"
        const val SELF = "self"

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv")
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx")
        /** Where a bare path in prose ends: at whitespace, a closing bracket or quote, or a sentence mark before one of those. */
        private val bare = Regex("""/cursor/stores/[^\s<>()\[\]"'`]+""")

        /**
         * The store path [src] names, or null when it is not one. Accepts the path as written, without its leading
         * slash, behind a `file:` scheme, and percent-encoded (`%20` for a space); a query or fragment is dropped.
         * A path that climbs out of its store (`..`) is not a store path.
         */
        fun parse(src: String): StorePath? {
            var text = src.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("file:", ignoreCase = true)) {
                text = text.substring(5).trimStart('/').let { "/$it" }
            }
            if (text.startsWith("cursor/stores/")) text = "/$text"
            if (!text.startsWith(ROOT)) return null
            text = text.substringBefore('#').substringBefore('?')
            val decoded = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
            val segments = decoded.removePrefix(ROOT).split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.size < 2 || segments.any { it == ".." }) return null
            return StorePath(segments.first(), segments.drop(1).joinToString("/"))
        }

        /** The bare store path starting at [index] of [text], as prose carries one, trailing sentence marks dropped. */
        fun findBare(text: String, index: Int): String? {
            val match = bare.matchAt(text, index) ?: return null
            var end = match.value.length
            while (end > 0 && match.value[end - 1] in ".,;:!?") end--
            val candidate = match.value.substring(0, end)
            return candidate.takeIf { parse(it) != null }
        }

        /** Where the Project behind a store is on the web: its coordinator's chat. */
        fun webUrl(ownerId: String): String = "https://cursor.com/agents/$ownerId"
    }
}
