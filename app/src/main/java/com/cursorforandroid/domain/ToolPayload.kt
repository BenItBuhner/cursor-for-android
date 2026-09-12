package com.cursorforandroid.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a tool call produced beyond its one-line summary, kept when the stream carried it: the diff of an edit, the
 * text of a file the agent read or wrote, an image it generated, a recording it made, the subagent it delegated to,
 * or the question it is waiting on. The documented stream delivers these two ways — inside the simplified
 * `tool_call` event's `result` when it is small enough, and always on the SDK-shape `interaction_update` event —
 * and either way they are read once, clipped to what a screen can show, and kept on the [ToolCall] so the
 * transcript can open onto them and the panel can list them, on disk with the run's trace like the rest.
 */
@Serializable
sealed interface ToolPayload {
    /** The path the payload is about, for the Files and Changes lists; null for a question. */
    val path: String? get() = null

    /** A unified diff of one edit (`edit.result.diffString`), with the line counts when the result reported them. */
    @Serializable
    @SerialName("diff")
    data class FileDiff(
        override val path: String,
        val diff: String,
        val linesAdded: Int? = null,
        val linesRemoved: Int? = null,
        /** The diff was longer than [ToolPayloadLimits.MAX_TEXT_CHARS] and is kept to its head. */
        val truncated: Boolean = false,
    ) : ToolPayload {
        /** The diff's lines, for rendering; a Windows-style ending is read like a plain one. */
        val lines: List<String> get() = diff.replace("\r\n", "\n").lines()
    }

    /** The text of a file as the agent saw it (`read.result.content`) or left it (`write.result.fileContentAfterWrite`). */
    @Serializable
    @SerialName("file")
    data class FileContent(
        override val path: String,
        val content: String,
        val kind: Kind,
        val totalLines: Int? = null,
        val fileSize: Long? = null,
        val truncated: Boolean = false,
    ) : ToolPayload {
        enum class Kind { Read, Written }

        val lineCount: Int get() = totalLines ?: content.lineSequence().count()
    }

    /**
     * An image the agent generated (`generateImage.result.imageData`). [src] is where the pixels can be read from —
     * a `file://` URI on this device once a store kept them, else the `data:` URI itself — and null when the result
     * carried no image the app could keep (too large, undecodable).
     */
    @Serializable
    @SerialName("image")
    data class GeneratedImage(
        override val path: String?,
        val description: String? = null,
        val src: String? = null,
    ) : ToolPayload

    /** A screen recording the agent saved (`recordScreen.result.path`), which the artifact endpoint serves. */
    @Serializable
    @SerialName("recording")
    data class Recording(
        override val path: String,
        val durationMs: Long? = null,
    ) : ToolPayload

    /** A subagent the agent delegated to (`task`): its transcript's path on the VM, and its id when it ran in the cloud. */
    @Serializable
    @SerialName("subagent")
    data class Subagent(
        val description: String?,
        val agentId: String? = null,
        val transcriptPath: String? = null,
        val durationMs: Long? = null,
        val isBackground: Boolean = false,
        val subagentType: String? = null,
    ) : ToolPayload {
        override val path: String? get() = transcriptPath

        /** True when [agentId] names a cloud agent this app could open (`bc-…`), not a local subagent's handle. */
        val isCloudAgent: Boolean get() = agentId?.startsWith("bc-") == true
    }

    /**
     * The questions an `ask_question` call put to the user, and the answers once they came. Pending while the call is
     * still running: the run is paused on it, and only Cursor's own clients (or Extended mode, later) can answer.
     */
    @Serializable
    @SerialName("question")
    data class Question(
        val title: String? = null,
        val questions: List<Item> = emptyList(),
        val answers: List<Answer> = emptyList(),
    ) : ToolPayload {
        @Serializable
        data class Item(
            val id: String,
            val prompt: String,
            val options: List<Option> = emptyList(),
            val allowMultiple: Boolean = false,
        )

        @Serializable
        data class Option(val id: String, val label: String)

        @Serializable
        data class Answer(
            val questionId: String,
            val selectedOptionIds: List<String> = emptyList(),
            val freeformText: String? = null,
        )

        val isAnswered: Boolean get() = answers.isNotEmpty()

        /** What was picked for [item], in the options' words, or the free text; null while unanswered. */
        fun answerFor(item: Item): String? {
            val answer = answers.firstOrNull { it.questionId == item.id } ?: return null
            val picked = answer.selectedOptionIds.mapNotNull { id -> item.options.firstOrNull { it.id == id }?.label ?: id.takeIf { it.isNotBlank() } }
            val text = answer.freeformText?.trim()?.takeIf { it.isNotEmpty() }
            return (picked + listOfNotNull(text)).joinToString(", ").ifEmpty { null }
        }
    }
}

/** How much of a payload's text is kept: enough for any edit or file a screen can scroll, bounded for the trace file. */
object ToolPayloadLimits {
    const val MAX_TEXT_CHARS = 40_000

    /** An image larger than this as base64 is not kept inline in a trace; a store on the device keeps the bytes instead. */
    const val MAX_INLINE_IMAGE_CHARS = 256 * 1024

    /** [text] up to [max] characters, and whether anything was cut. Never splits a surrogate pair. */
    fun clip(text: String, max: Int = MAX_TEXT_CHARS): Pair<String, Boolean> {
        if (text.length <= max) return text to false
        var cutAt = max
        if (cutAt > 0 && Character.isHighSurrogate(text[cutAt - 1])) cutAt--
        return text.substring(0, cutAt) to true
    }
}

/** Whether a tool call reported that the stream left part of its payload out (`tool_call.truncated`). */
@Serializable
data class ToolTruncation(val args: Boolean = false, val result: Boolean = false) {
    val any: Boolean get() = args || result
}
