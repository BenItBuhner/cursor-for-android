package com.cursorforandroid.domain

import kotlinx.serialization.json.JsonElement

/** One rendered entry in a conversation. */
sealed interface TimelineItem {
    val id: String
}

data class DateHeader(override val id: String, val label: String) : TimelineItem

data class UserMessage(
    override val id: String,
    val text: String,
    val timestampMillis: Long? = null,
) : TimelineItem

data class AssistantMessage(
    override val id: String,
    val markdown: String,
    val isStreaming: Boolean = false,
) : TimelineItem

data class ThinkingBlock(
    override val id: String,
    val text: String,
    val durationSeconds: Long? = null,
    val isStreaming: Boolean = false,
) : TimelineItem

/** Compact key/value row such as "Worked 3m 5s" or "Explored 6 files, 7 searches". */
data class SummaryRow(
    override val id: String,
    val label: String,
    val value: String,
) : TimelineItem

enum class ToolKind { Read, List, Search, Edit, Shell, Web, Task, Mcp, Other }

data class ToolCall(
    val callId: String,
    val name: String,
    val kind: ToolKind,
    val status: String,
    val summary: String,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
) {
    val isRunning: Boolean get() = status == "running"
}

/** A batch of consecutive tool calls, rendered as an "Explored N files, M searches" row that expands to a card. */
data class ToolActivity(
    override val id: String,
    val calls: List<ToolCall>,
) : TimelineItem {
    val fileCount: Int get() = calls.count { it.kind == ToolKind.Read || it.kind == ToolKind.List || it.kind == ToolKind.Edit }
    val searchCount: Int get() = calls.count { it.kind == ToolKind.Search || it.kind == ToolKind.Web }
    val commandCount: Int get() = calls.count { it.kind == ToolKind.Shell }
    val isRunning: Boolean get() = calls.any { it.isRunning }

    val headline: String
        get() {
            val parts = buildList {
                if (fileCount > 0) add("$fileCount ${if (fileCount == 1) "file" else "files"}")
                if (searchCount > 0) add("$searchCount ${if (searchCount == 1) "search" else "searches"}")
                if (commandCount > 0) add("$commandCount ${if (commandCount == 1) "command" else "commands"}")
            }
            return if (parts.isEmpty()) "${calls.size} ${if (calls.size == 1) "tool call" else "tool calls"}" else parts.joinToString(", ")
        }
    val verb: String get() = if (isRunning) "Exploring" else "Explored"
}

data class Subagent(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val detail: String? = null,
)

data class SubagentsCard(
    override val id: String,
    val subagents: List<Subagent>,
) : TimelineItem

data class NoticeCard(
    override val id: String,
    val title: String,
    val subtitle: String? = null,
    val tone: NoticeTone = NoticeTone.Neutral,
) : TimelineItem

enum class NoticeTone { Neutral, Success, Warning, Error }

/** Terminal marker for a run: status, duration and pushed branches. */
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
            n.contains("grep") || n.contains("search") || n.contains("rg") -> if (n.contains("web")) ToolKind.Web else ToolKind.Search
            n.contains("edit") || n.contains("write") || n.contains("delete") || n.contains("replace") || n.contains("patch") -> ToolKind.Edit
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
