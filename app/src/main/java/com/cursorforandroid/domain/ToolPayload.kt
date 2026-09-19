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

    /**
     * A Project coordinator's word to a worker or about it (`agent/v1/coordinator_tools`): the worker it created
     * (`create_agent`, the prompt in [text], the worker's name in [title]), messaged (`send_to_agent`, the message
     * in [text]), asked about (`get_agent_status`, one [WorkerStatus] per worker), stopped, or read the transcript of
     * (an excerpt in [text]). [workers] names every worker the call touched; a status check carries their state as
     * the coordinator saw it, which the list's live row supersedes when the worker is loaded here.
     */
    @Serializable
    @SerialName("worker_action")
    data class WorkerAction(
        val kind: Kind,
        val workers: List<WorkerStatus> = emptyList(),
        val text: String? = null,
        val title: String? = null,
        /** The result's one-line message ("Created agent…", "Delivered as queued"). */
        val note: String? = null,
        val truncated: Boolean = false,
        /**
         * [workers] came from the tool's result — the worker `create_agent` made, the workers `get_agent_status`
         * listed as the coordinator's — rather than from its arguments: the coordinator's own word that they are its.
         */
        val reported: Boolean = false,
    ) : ToolPayload {
        enum class Kind { Created, Messaged, Status, Stopped, ReadTranscript }

        /** The worker the call was about, for the calls about one. */
        val worker: WorkerStatus? get() = workers.firstOrNull()
    }

    /**
     * The coordinator's message to the user, as the markdown it wrote: the `SendMessage` tool's `text.content`
     * (`agent.v1.SendMessageArgs`, the tool Cursor's client shows as a Project chat's message), or the older
     * `send_to_user`'s `message`. An attachment sent instead of text is the markdown for its link.
     *
     * [missing] marks a message whose body this copy of the turn does not have: the stream left the arguments out
     * for size (`tool_call.truncated.args`), or the turn was kept by a build that did not read the tool and dropped
     * the arguments with the rest (see `CoordinatorTranscript.reinterpret`). The row then says so rather than
     * standing bare, and the turn is asked for again where it can be. [recovered] marks a body read leniently out of
     * arguments that did not parse — the pieces of a streamed call the record never completed (see
     * `MessageRecovery`) — which the row shows with a word that it may not be the whole message. [messageId] is the
     * server's id for the message once it was delivered (`SendMessageResult.success.messageId`): the one name two
     * copies of the same message share whatever call carried them (see `CoordinatorTranscript.repeatedMessages`).
     */
    @Serializable
    @SerialName("coordinator_message")
    data class CoordinatorMessage(val message: String, val missing: Boolean = false, val recovered: Boolean = false, val messageId: String? = null) : ToolPayload

    /**
     * The agent filing or moving the chat's goal (`agent.v1.CreateGoalToolCall` / `UpdateGoalToolCall`; see [Goal]).
     * [Action.Set] is `CreateGoal`, whose `CreateGoalArgs {objective}` carry the objective verbatim and whose success
     * is empty; [Action.Update] is `UpdateGoal`, whose `UpdateGoalArgs {status}` name the status the goal moves to
     * (paused, active again, complete, cleared) and whose success repeats it. Either result's error case is
     * `GoalError {error}`, kept in [error] so the row can say why the goal was refused.
     *
     * [missing] marks a `CreateGoal` whose objective this copy of the turn does not have: the stream left the
     * arguments out for size, or the turn was kept by a build that did not read the tool (see `GoalTranscript`).
     */
    @Serializable
    @SerialName("goal")
    data class GoalChange(
        val action: Action,
        val objective: String? = null,
        val status: GoalStatus? = null,
        val error: String? = null,
        val missing: Boolean = false,
    ) : ToolPayload {
        enum class Action { Set, Update }

        /** The status the goal is in once this call has succeeded: active for a goal just set, else what the update asked. */
        val resultingStatus: GoalStatus? get() = if (action == Action.Set) GoalStatus.ACTIVE else status
    }
}

/**
 * One worker as a coordinator's tool named it: the id (a cloud agent's, `bc-…`, when it is one), the name the
 * coordinator gave or saw, and — from `get_agent_status` — where it stood: `lifecycle`, whether a turn was in
 * flight, how its last turn ended, its pull request, when it was last active.
 */
@Serializable
data class WorkerStatus(
    val agentId: String? = null,
    val name: String? = null,
    val lifecycle: String? = null,
    val turnInFlight: Boolean? = null,
    val lastTurnStatus: String? = null,
    val prUrl: String? = null,
    val lastActivityAtMillis: Long? = null,
) {
    val isCloudAgent: Boolean get() = agentId?.startsWith("bc-") == true

    /** "Working", "Finished", "Failed", "Cancelled", "Archived", or the raw word when it is one this build does not know; null for nothing said. */
    val statusLabel: String?
        get() = when {
            turnInFlight == true -> "Working"
            lastTurnStatus != null -> when (lastTurnStatus.trim().uppercase().removePrefix("RUN_STATUS_").removePrefix("TURN_STATUS_")) {
                "FINISHED", "SUCCEEDED", "COMPLETED", "SUCCESS" -> "Finished"
                "ERROR", "FAILED", "FAILURE" -> "Failed"
                "CANCELLED", "CANCELED", "STOPPED" -> "Cancelled"
                "EXPIRED", "TIMED_OUT", "TIMEOUT" -> "Expired"
                "RUNNING", "CREATING" -> "Working"
                else -> lastTurnStatus.trim().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            lifecycle != null -> lifecycle.trim().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            else -> null
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
