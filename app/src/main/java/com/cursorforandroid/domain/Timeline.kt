package com.cursorforandroid.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One rendered entry in a conversation. Serializable so the trace of a finished run — which the API only retains
 * for a while — can be kept on disk once it has been seen; the serial names are the on-disk contract.
 */
@Serializable
sealed interface TimelineItem {
    val id: String
}

@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String,
    val text: String,
    val timestampMillis: Long? = null,
    val attachments: List<MessageAttachment> = emptyList(),
) : TimelineItem

/**
 * An image that was attached to a prompt, as kept on this device. The transcript endpoint only returns the text of a
 * `user_message`, so the copy written when the prompt was sent is the only one there is; [path] points at it.
 */
@Serializable
data class MessageAttachment(
    val path: String,
    val width: Int,
    val height: Int,
) {
    val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    override val id: String,
    val markdown: String,
    val isStreaming: Boolean = false,
) : TimelineItem

/** Compact key/value row such as "Worked 3m 5s" or "Explored 6 files, 7 searches". */
@Serializable
@SerialName("summary")
data class SummaryRow(
    override val id: String,
    val label: String,
    val value: String,
) : TimelineItem

/** One step of an [ActivityGroup]: a stretch of reasoning or a tool call, in the order the agent took them. */
@Serializable
sealed interface ActivityStep

@Serializable
@SerialName("thinking")
data class ThinkingBlock(
    val text: String,
    val durationSeconds: Long? = null,
    val isStreaming: Boolean = false,
) : ActivityStep

enum class ToolKind { Read, List, Search, Edit, Shell, Web, Task, Mcp, Other }

@Serializable
@SerialName("tool")
data class ToolCall(
    val callId: String,
    val name: String,
    val kind: ToolKind,
    val status: String,
    val summary: String,
    /**
     * The call's raw JSON. Read once while the call is built — for [summary] and the line counts below — and not
     * kept: a run's tool calls carry whole file contents and command output, which would hold the run's entire
     * payload in memory for as long as the chat is open, and on disk for as long as its trace is.
     */
    val args: JsonElement? = null,
    val result: JsonElement? = null,
    /** Lines an edit reported adding and removing, taken off its result when the call was built (see [DiffStats]). */
    val additions: Int? = null,
    val deletions: Int? = null,
) : ActivityStep {
    val isRunning: Boolean get() = status == STATUS_RUNNING

    companion object {
        /** The two statuses the stream reports. */
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        /** Ours: the run ended (error, cancel) while the call was still reported as running. */
        const val STATUS_INTERRUPTED = "interrupted"
    }
}

/**
 * Everything the agent did between two messages — its reasoning and tool calls, interleaved as they happened — behind
 * one "Explored 6 files, 1 search · thought for 7s" row that expands to the trace. A run's work between two replies
 * is one thing to skim or dig into, not a stack of "Thought for 1s" / "Explored 2 files" rows.
 */
@Serializable
@SerialName("activity")
data class ActivityGroup(
    override val id: String,
    val steps: List<ActivityStep>,
) : TimelineItem {
    val thoughts: List<ThinkingBlock> get() = steps.filterIsInstance<ThinkingBlock>()
    val calls: List<ToolCall> get() = steps.filterIsInstance<ToolCall>()
    val fileCount: Int get() = calls.count { it.kind == ToolKind.Read || it.kind == ToolKind.List || it.kind == ToolKind.Edit }
    val searchCount: Int get() = calls.count { it.kind == ToolKind.Search || it.kind == ToolKind.Web }
    val commandCount: Int get() = calls.count { it.kind == ToolKind.Shell }

    /** The tool call in progress: the latest one still reported as running, or null. */
    val runningCall: ToolCall? get() = calls.lastOrNull { it.isRunning }
    val isRunning: Boolean get() = runningCall != null

    /** Whether the newest step is a thought still being written. */
    val isThinking: Boolean get() = (steps.lastOrNull() as? ThinkingBlock)?.isStreaming == true
    val isBusy: Boolean get() = isRunning || isThinking

    /** Seconds spent thinking across the group, or null when its thoughts were not timed (a replayed stream). */
    val thoughtSeconds: Long? get() = thoughts.mapNotNull { it.durationSeconds }.takeIf { it.isNotEmpty() }?.sum()

    /** "6 files, 1 search" — the tool calls by kind; null when the group is nothing but thinking. */
    val headline: String?
        get() {
            if (calls.isEmpty()) return null
            val parts = buildList {
                if (fileCount > 0) add("$fileCount ${if (fileCount == 1) "file" else "files"}")
                if (searchCount > 0) add("$searchCount ${if (searchCount == 1) "search" else "searches"}")
                if (commandCount > 0) add("$commandCount ${if (commandCount == 1) "command" else "commands"}")
            }
            return if (parts.isEmpty()) "${calls.size} ${if (calls.size == 1) "tool call" else "tool calls"}" else parts.joinToString(", ")
        }

    /** The row's verb: what the agent is doing while the group is busy, what it did once it has gone quiet. */
    val verb: String
        get() = when {
            calls.isNotEmpty() -> if (isBusy) "Exploring" else "Explored"
            else -> if (isBusy) "Thinking" else "Thought"
        }

    /**
     * What follows the verb: "6 files, 1 search · thought for 7s", "for 7s", or null when there is nothing to add.
     * The thinking time joins only once the group is quiet, so the counts hold still while the agent works.
     */
    val detail: String?
        get() {
            val thought = thoughtSeconds?.let { "for ${it}s" }
            return when {
                calls.isEmpty() -> if (isBusy) null else thought
                isBusy || thought == null -> headline
                else -> "$headline · thought $thought"
            }
        }
}

@Serializable
data class Subagent(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val detail: String? = null,
)

@Serializable
@SerialName("subagents")
data class SubagentsCard(
    override val id: String,
    val subagents: List<Subagent>,
) : TimelineItem

@Serializable
@SerialName("notice")
data class NoticeCard(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val tone: NoticeTone = NoticeTone.Neutral,
) : TimelineItem

enum class NoticeTone { Neutral, Success, Warning, Error }

/**
 * A turn Cursor injected on the user's behalf — a goal picked up again, a subagent's finished report — which the
 * transcript carries as a `user_message` full of the `<system_notification>` markup written for the model. It is
 * not something the user said, so it is shown as one compact row ("Subagent completed · Contacts and clipping") that
 * opens onto the part worth reading, rather than as a prompt bubble of markup. See [SystemNotifications].
 */
@Serializable
@SerialName("system_notification")
data class SystemNotification(
    override val id: String,
    val kind: Kind,
    /** The row's label: "Goal continued", "Subagent completed", "Subagent failed". */
    val title: String,
    /** What follows the label, one line of it: the subagent's title, the objective's first line. */
    val summary: String? = null,
    /**
     * The content itself, as markdown: the subagent's report, the goal's objective. The row opens onto it when it
     * has more than the [summary] shows; null when the notification had nothing beyond its label.
     */
    val body: String? = null,
    val tone: NoticeTone = NoticeTone.Neutral,
    /** The notification as injected, markup included, for "Copy message". */
    val raw: String,
    val timestampMillis: Long? = null,
) : TimelineItem {
    enum class Kind { Goal, Subagent, Task, Other }
}

/** Terminal marker for a run: status, duration and pushed branches. */
@Serializable
@SerialName("footer")
data class RunFooter(
    override val id: String,
    val runId: String,
    val status: RunStatus,
    val durationMs: Long?,
    val branches: List<GitBranch>,
) : TimelineItem

object ToolNames {
    fun kindOf(name: String): ToolKind {
        val n = name.lowercase()
        return when {
            n.contains("read") || n == "cat" -> ToolKind.Read
            n.contains("list") || n.contains("glob") || n == "ls" -> ToolKind.List
            // Before the search check: Cursor's edit tool is literally `search_replace`.
            n.contains("edit") || n.contains("write") || n.contains("delete") || n.contains("replace") || n.contains("patch") -> ToolKind.Edit
            n.contains("grep") || n.contains("search") || n.contains("rg") -> if (n.contains("web")) ToolKind.Web else ToolKind.Search
            n.contains("terminal") || n.contains("shell") || n.contains("bash") || n.contains("cmd") -> ToolKind.Shell
            n.contains("fetch") || n.contains("web") -> ToolKind.Web
            n == "task" || n.contains("subagent") -> ToolKind.Task
            n == "mcp" || n.startsWith("mcp") -> ToolKind.Mcp
            else -> ToolKind.Other
        }
    }

    fun verb(kind: ToolKind, status: String): String {
        val running = status == "running"
        return when (kind) {
            ToolKind.Read -> if (running) "Reading" else "Read"
            ToolKind.List -> if (running) "Listing" else "Listed"
            ToolKind.Search -> if (running) "Searching" else "Searched"
            ToolKind.Edit -> if (running) "Editing" else "Edited"
            ToolKind.Shell -> if (running) "Running" else "Ran"
            ToolKind.Web -> if (running) "Fetching" else "Fetched"
            ToolKind.Task -> if (running) "Delegating" else "Delegated"
            ToolKind.Mcp -> if (running) "Calling" else "Called"
            ToolKind.Other -> if (running) "Using" else "Used"
        }
    }
}
