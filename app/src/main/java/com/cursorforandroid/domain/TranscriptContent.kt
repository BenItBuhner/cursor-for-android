package com.cursorforandroid.domain

/**
 * What a conversation's tool calls add up to, read off the rendered timeline: the files the agent touched, the
 * diffs of its edits by file, the images and recordings it produced, the subagents it delegated to and the question
 * it is waiting on. Everything here comes from the documented stream (see [ToolPayload]); it is what the panel's
 * Changes, Files and Images sections show without any endpoint beyond the ones the transcript already used.
 */
data class TranscriptContent(
    /** Every file the agent read, wrote, edited or deleted, in order of first touch, with how it was touched. */
    val touched: List<TouchedFile>,
    /** The edits by file, in order of first edit; a file edited several times carries every diff. */
    val changes: List<FileChange>,
    /** Images the agent generated and recordings it made, in the order they were produced. */
    val media: List<MediaItem>,
    /** The subagents delegated to, in order. */
    val subagents: List<ToolPayload.Subagent>,
    /** The question the agent is waiting on, when a run is paused on one. */
    val pendingQuestion: ToolPayload.Question?,
) {
    val isEmpty: Boolean get() = touched.isEmpty() && changes.isEmpty() && media.isEmpty() && subagents.isEmpty() && pendingQuestion == null

    data class TouchedFile(val path: String, val kinds: Set<Touch>) {
        val name: String get() = ToolNames.basename(path)
        val wasChanged: Boolean get() = Touch.Edited in kinds || Touch.Created in kinds || Touch.Deleted in kinds
    }

    enum class Touch { Read, Created, Edited, Deleted }

    /** The edits of one file: each diff the stream carried, and the counts across them when every edit reported some. */
    data class FileChange(
        val path: String,
        val diffs: List<ToolPayload.FileDiff>,
        /** The file was created, edited or deleted here; a deletion has no diff. */
        val touch: Touch,
        /** The file's text after the last write, when a `write` result carried it. */
        val contentAfter: ToolPayload.FileContent? = null,
    ) {
        val name: String get() = ToolNames.basename(path)
        val linesAdded: Int? get() = diffs.map { it.linesAdded }.takeIf { it.isNotEmpty() && it.all { n -> n != null } }?.sumOf { it!! }
        val linesRemoved: Int? get() = diffs.map { it.linesRemoved }.takeIf { it.isNotEmpty() && it.all { n -> n != null } }?.sumOf { it!! }
        val lineStats: String? get() = lineStatsOf(linesAdded, linesRemoved)
        /** One unified diff of the whole change: the edits in order, separated so each hunk header is its own. */
        val unifiedDiff: String get() = diffs.joinToString("\n") { it.diff.trimEnd() }
    }

    sealed interface MediaItem {
        val callId: String
        data class Image(override val callId: String, val image: ToolPayload.GeneratedImage) : MediaItem
        data class Recording(override val callId: String, val recording: ToolPayload.Recording) : MediaItem
    }

    companion object {
        val EMPTY = TranscriptContent(emptyList(), emptyList(), emptyList(), emptyList(), pendingQuestion = null)

        fun of(items: List<TimelineItem>): TranscriptContent {
            val calls = items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }.toList()
            if (calls.isEmpty()) return EMPTY
            val touched = LinkedHashMap<String, MutableSet<Touch>>()
            val changes = LinkedHashMap<String, MutableList<ToolPayload.FileDiff>>()
            val changeTouch = LinkedHashMap<String, Touch>()
            val contentAfter = HashMap<String, ToolPayload.FileContent>()
            val media = ArrayList<MediaItem>()
            val subagents = ArrayList<ToolPayload.Subagent>()
            var pending: ToolPayload.Question? = null
            for (call in calls) {
                val path = call.touchedPath?.takeIf { it.isNotBlank() }
                val touch = when {
                    call.kind == ToolKind.Read -> Touch.Read
                    call.kind == ToolKind.Create -> Touch.Created
                    call.kind == ToolKind.Edit -> Touch.Edited
                    call.kind == ToolKind.Delete -> Touch.Deleted
                    else -> null
                }
                if (path != null && touch != null && !call.isError) {
                    touched.getOrPut(path) { LinkedHashSet() } += touch
                    if (touch != Touch.Read) {
                        changes.getOrPut(path) { ArrayList() }
                        // The strongest word for what happened to the file: created beats edited; a deletion is final.
                        changeTouch[path] = when {
                            touch == Touch.Deleted -> Touch.Deleted
                            changeTouch[path] == Touch.Created -> Touch.Created
                            else -> touch
                        }
                    }
                }
                when (val payload = call.payload) {
                    is ToolPayload.FileDiff -> changes.getOrPut(payload.path) { ArrayList() } += payload
                    is ToolPayload.FileContent -> if (payload.kind == ToolPayload.FileContent.Kind.Written) contentAfter[payload.path] = payload
                    is ToolPayload.GeneratedImage -> if (payload.src != null) media += MediaItem.Image(call.callId, payload)
                    is ToolPayload.Recording -> media += MediaItem.Recording(call.callId, payload)
                    is ToolPayload.Subagent -> subagents += payload
                    is ToolPayload.Question -> if (call.pendingQuestion != null) pending = payload
                    null -> Unit
                }
            }
            return TranscriptContent(
                touched = touched.map { (path, kinds) -> TouchedFile(path, kinds) },
                changes = changes.map { (path, diffs) -> FileChange(path, diffs, changeTouch[path] ?: Touch.Edited, contentAfter[path]) },
                media = media,
                subagents = subagents,
                pendingQuestion = pending,
            )
        }
    }
}
