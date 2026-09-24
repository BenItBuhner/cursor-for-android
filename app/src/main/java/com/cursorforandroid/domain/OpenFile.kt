package com.cursorforandroid.domain

/**
 * A file the reader asked to see from a tool call: the path as the call names it, the call it was tapped on (so a
 * read shows what the agent saw, an edit highlights what it changed), and a line to bring into view (a search hit).
 */
data class FileOpenRequest(val path: String, val callId: String? = null, val line: Int? = null)

/**
 * What the transcript already carries of a file, for the full-file viewer, read off the documented stream's
 * payloads (`read.result.content`, `write.result.fileContentAfterWrite`, `edit.result.diffString`) with no request.
 */
data class CarriedFile(
    val path: String,
    /** The payload to show: the tapped call's when it carried the file's text, else the latest one that did. */
    val content: ToolPayload.FileContent?,
    /** The edits of the file the transcript saw, oldest first; [tappedDiff] is the one the reader tapped, if an edit. */
    val diffs: List<ToolPayload.FileDiff>,
    val tappedDiff: ToolPayload.FileDiff?,
    /** The tapped call was an edit, a write or a read: what the lines to highlight are measured against. */
    val tappedKind: ToolKind?,
) {
    /** [content] is the file whole — a write, or a read from the top to the end — so no read is needed to show it. */
    val isWhole: Boolean get() = content?.isWhole == true

    companion object {
        /**
         * The file at [path] as [items] carry it, for the call [callId] was tapped on. A read the reader tapped is
         * what they are shown, whole or not; an edit or a search hit falls back to the newest text any call left.
         */
        fun of(items: List<TimelineItem>, path: String, callId: String?): CarriedFile {
            val calls = items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }.toList()
            val same = calls.filter { call -> call.payload?.path?.let { samePath(it, path) } == true || call.detail?.let { samePath(it, path) } == true }
            val tapped = callId?.let { id -> calls.firstOrNull { it.callId == id } }
            val tappedContent = tapped?.payload as? ToolPayload.FileContent
            val latestContent = same.mapNotNull { it.payload as? ToolPayload.FileContent }.lastOrNull()
            val diffs = same.mapNotNull { it.payload as? ToolPayload.FileDiff }
            return CarriedFile(
                path = path,
                content = tappedContent ?: latestContent,
                diffs = diffs,
                tappedDiff = tapped?.payload as? ToolPayload.FileDiff,
                tappedKind = tapped?.kind,
            )
        }

        /** Two spellings of one file: equal, or one the other's absolute form (`/workspace/a/b.kt` and `a/b.kt`). */
        fun samePath(a: String, b: String): Boolean {
            val x = a.trim().removePrefix("./")
            val y = b.trim().removePrefix("./")
            if (x == y) return true
            return (x.startsWith("/") && x.endsWith("/$y")) || (y.startsWith("/") && y.endsWith("/$x"))
        }
    }
}

/** Line arithmetic over unified diffs, for the lines an edit left in the file. */
object DiffLines {
    private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")

    /**
     * The lines of the file after [diff] that the edit added or changed, 1-based: every `+` line counted from its
     * hunk's `+start`. A diff without hunk headers (a bare list of `+`/`-` lines) has no line numbers to give.
     */
    fun changedLines(diff: String): Set<Int> {
        val out = LinkedHashSet<Int>()
        var line = -1
        for (raw in diff.replace("\r\n", "\n").lineSequence()) {
            val hunk = HUNK.find(raw)
            if (hunk != null) {
                line = hunk.groupValues[1].toInt()
                continue
            }
            if (line < 0 || raw.startsWith("+++") || raw.startsWith("---")) continue
            when {
                raw.startsWith("+") -> out += line++
                raw.startsWith("-") -> Unit
                raw.startsWith("\\") -> Unit
                else -> line++
            }
        }
        return out
    }
}
