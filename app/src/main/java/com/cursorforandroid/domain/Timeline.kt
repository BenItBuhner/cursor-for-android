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
    /** Sent from here and not yet acknowledged by the server; drawn faded until its run is filed. */
    val isPending: Boolean = false,
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

/**
 * What a tool call is, in the categories Cursor's own client sorts its tools into (the `toolCase`s of the desktop
 * build's `tool-action-labels.js`): each kind has its own verbs and its own place in a group's summary. [Search] is
 * the semantic codebase search; [Grep] and [Glob] are the exact and file-name searches. [Create] is an edit that
 * wrote a new file. [McpTools] is the agent looking up which MCP tools it has. [Other] covers everything else, which
 * is named after the tool itself ("Switched mode").
 */
enum class ToolKind {
    Read, List, Search, Grep, Glob, Edit, Create, Delete, Shell, WebSearch, WebFetch, Task, Mcp, McpTools, Todo, Lints, Question, Image, Plan, Other,
}

/** The three forms of a tool's verb: while it runs, once it is done, and when it failed ("Edit attempted"). */
@Serializable
data class ToolLabels(val loading: String, val completed: String, val error: String)

@Serializable
@SerialName("tool")
data class ToolCall(
    val callId: String,
    val name: String,
    val kind: ToolKind,
    val status: String,
    /**
     * The row's details, in Cursor's words: "Timeline.kt", "ModelSheet in ui", "list_pull_requests",
     * "available tools", or "" when the verb says it all ("Read lints").
     */
    val summary: String,
    /** MCP: the server the tool belongs to, read as "in Github". */
    val server: String? = null,
    /** What the row opens onto: the shell command, the full path, the URL, the whole query. */
    val detail: String? = null,
    /** Lines an edit added and removed, when its result reported them. */
    val linesAdded: Int? = null,
    val linesRemoved: Int? = null,
    /** The tool reported a failure; the row reads "Edit attempted". */
    val isError: Boolean = false,
    /** Verbs that override the kind's: a skill being read is "Used skill", a to-do update "Completed 2 of 5". */
    val labels: ToolLabels? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
) : ActivityStep {
    val isRunning: Boolean get() = status == STATUS_RUNNING

    /** "Reading", "Read" or "Read" (the attempted form) — the verb for the call's state. */
    val action: String
        get() {
            val l = labels ?: ToolNames.labels(kind, name)
            return when {
                isRunning -> l.loading
                isError -> l.error
                else -> l.completed
            }
        }

    /** What follows the verb: the summary, or "attempted" once the call failed. */
    val details: String get() = if (isError && !isRunning) "attempted" else summary

    /** "+12 -3", or null when the call reported no line counts. */
    val lineStats: String? get() = lineStatsOf(linesAdded, linesRemoved)

    val isFileChange: Boolean get() = kind == ToolKind.Edit || kind == ToolKind.Create || kind == ToolKind.Delete

    companion object {
        /** The two statuses the stream reports. */
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        /** Ours: the run ended (error, cancel) while the call was still reported as running. */
        const val STATUS_INTERRUPTED = "interrupted"
    }
}

/**
 * Everything the agent did between two messages — its reasoning and tool calls, interleaved as they happened. It is
 * shown the way Cursor's client shows a stretch of steps: a thought that came before any tool call is its own
 * "Thought 3s" row, and the tool calls with the thoughts between them collapse behind one summary — "Explored 6
 * files, 1 search, ran 2 commands", "Edited 2 files, explored 3 files +12 -3", "Ran 1 command" — unless they are one
 * or two bare reads, which stay as lines of their own.
 */
@Serializable
@SerialName("activity")
data class ActivityGroup(
    override val id: String,
    val steps: List<ActivityStep>,
) : TimelineItem {
    val thoughts: List<ThinkingBlock> get() = steps.filterIsInstance<ThinkingBlock>()
    val calls: List<ToolCall> get() = steps.filterIsInstance<ToolCall>()

    /** The thoughts before the first tool call: the "Thought 3s" row. */
    val leadingThoughts: List<ThinkingBlock> get() = steps.takeWhile { it is ThinkingBlock }.filterIsInstance<ThinkingBlock>()

    /** From the first tool call on: the tool calls and the thoughts between them, behind the summary row. */
    val work: List<ActivityStep> get() = steps.drop(leadingThoughts.size)

    /** The tool call in progress: the latest one still reported as running, or null. */
    val runningCall: ToolCall? get() = calls.lastOrNull { it.isRunning }
    val isRunning: Boolean get() = runningCall != null

    /** Whether the newest step is a thought still being written. */
    val isThinking: Boolean get() = (steps.lastOrNull() as? ThinkingBlock)?.isStreaming == true
    val isBusy: Boolean get() = isRunning || isThinking

    /** Whether the leading thought is the one being written. */
    val isLeadingThoughtStreaming: Boolean get() = work.isEmpty() && isThinking

    /** Whether the work — a tool running, or a thought after a tool call being written — is still going. */
    val isWorkBusy: Boolean get() = work.isNotEmpty() && isBusy

    /** Seconds spent on the leading thought, or null when it was not timed (a replayed stream) or there is none. */
    val thoughtSeconds: Long? get() = leadingThoughts.mapNotNull { it.durationSeconds }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * Whether the work collapses behind its summary row. Cursor groups tool calls from the first one, except that
     * reads and listings alone need three of them; anything with a thought among it is grouped.
     */
    val isWorkGrouped: Boolean
        get() {
            if (calls.isEmpty()) return false
            if (work.any { it is ThinkingBlock }) return true
            val onlyReads = calls.all { it.kind == ToolKind.Read || it.kind == ToolKind.List }
            return if (onlyReads) calls.size >= 3 else true
        }

    /** The leading thought's row: "Thinking" while it streams, then "Thought" with how long it took. */
    val thoughtAction: String get() = if (isLeadingThoughtStreaming) "Thinking" else "Thought"

    /** "3s", "briefly", or null while thinking or when the thought was not timed. */
    val thoughtDetails: String?
        get() {
            if (isLeadingThoughtStreaming) return null
            val seconds = thoughtSeconds ?: return null
            return if (seconds <= 0) "briefly" else "${seconds}s"
        }

    /** The summary row of the work, in Cursor's words. */
    val header: WorkHeader get() = WorkHeader.of(this)

    /** What the work counts up to; the numbers behind the summary row. */
    val summary: WorkSummary get() = WorkSummary.of(calls)
}

/** What a stretch of tool calls adds up to, counted the way Cursor's client counts a step group. */
data class WorkSummary(
    /** One entry per read, the file's name: two reads of one file are "2 files", as they are on the desktop. */
    val files: List<String>,
    val directories: List<String>,
    /** Exact, semantic, file-name and web searches, and looking up the MCP tools. */
    val searches: Int,
    val fetches: Int,
    val lints: Int,
    val commands: Int,
    val mcpToolCalls: Int,
    val taskCalls: Int,
    val images: Int,
    /** Distinct files edited or created, and distinct files deleted. */
    val edits: Int,
    val deletes: Int,
    /** Distinct names of the files changed. */
    val fileChangeFiles: List<String>,
    /** Lines added and removed across the edits; null when no edit reported counts or one of them is missing them. */
    val additions: Int?,
    val deletions: Int?,
) {
    val hasFileChanges: Boolean get() = edits > 0 || deletes > 0

    /** "+12 -3", or null. */
    val lineStats: String? get() = lineStatsOf(additions, deletions)

    companion object {
        fun of(calls: List<ToolCall>): WorkSummary {
            val changes = calls.filter { it.isFileChange }
            val editPaths = changes.filter { it.kind != ToolKind.Delete }.map { it.detail ?: it.summary }.distinct()
            val deletePaths = changes.filter { it.kind == ToolKind.Delete }.map { it.detail ?: it.summary }.distinct()
            val reported = changes.filter { it.kind != ToolKind.Delete && !it.isRunning }
            val statsMissing = reported.any { it.linesAdded == null && it.linesRemoved == null }
            val additions = reported.sumOf { it.linesAdded ?: 0 }
            val deletions = reported.sumOf { it.linesRemoved ?: 0 }
            val hasStats = !statsMissing && reported.isNotEmpty() && (additions > 0 || deletions > 0)
            return WorkSummary(
                files = calls.filter { it.kind == ToolKind.Read }.map { it.summary },
                directories = calls.filter { it.kind == ToolKind.List }.map { it.summary },
                searches = calls.count { it.kind == ToolKind.Search || it.kind == ToolKind.Grep || it.kind == ToolKind.Glob || it.kind == ToolKind.WebSearch || it.kind == ToolKind.McpTools },
                fetches = calls.count { it.kind == ToolKind.WebFetch },
                lints = calls.count { it.kind == ToolKind.Lints },
                commands = calls.count { it.kind == ToolKind.Shell },
                mcpToolCalls = calls.count { it.kind == ToolKind.Mcp },
                taskCalls = calls.count { it.kind == ToolKind.Task },
                images = calls.count { it.kind == ToolKind.Image },
                edits = editPaths.size,
                deletes = deletePaths.size,
                fileChangeFiles = changes.map { it.summary }.filter { it.isNotBlank() }.distinct(),
                additions = if (hasStats) additions else null,
                deletions = if (hasStats) deletions else null,
            )
        }
    }
}

/** "+12 -3" as the desktop writes line counts: each side only when it is above zero, null when neither is. */
internal fun lineStatsOf(added: Int?, removed: Int?): String? {
    val parts = listOfNotNull((added ?: 0).takeIf { it > 0 }?.let { "+$it" }, (removed ?: 0).takeIf { it > 0 }?.let { "-$it" })
    return parts.joinToString(" ").ifEmpty { null }
}

/**
 * The summary row of a stretch of work — "Explored 3 files, 2 searches, ran 1 command", "Edited Timeline.kt, explored
 * 2 files +12 -3", "Ran 1 command" — built the way Cursor's `step-group-display` builds it: a verb for the kind of
 * work, the counts joined by commas, and the line counts of the edits set apart so they can be coloured.
 */
data class WorkHeader(
    val action: String,
    /** The counts, or null when there is nothing to add to the verb. */
    val details: String?,
    /** "+12 -3", or null. */
    val lineStats: String? = null,
) {
    companion object {
        fun of(group: ActivityGroup): WorkHeader {
            val calls = group.calls
            val summary = group.summary
            val busy = group.isWorkBusy
            if (calls.isEmpty()) return WorkHeader(if (busy) "Exploring" else "Explored", null)
            if (summary.commands > 0 && summary.commands == calls.size) {
                // One command described by the agent is summarised by that description; otherwise by the count.
                val description = calls.singleOrNull()?.takeIf { it.summary != it.detail?.lineSequence()?.first() }?.summary
                return WorkHeader(if (busy) "Running" else "Ran", description ?: plural(summary.commands, "command"))
            }
            if (summary.images > 0 && summary.images == calls.size) {
                return WorkHeader(if (busy) "Generating" else "Generated", plural(summary.images, "image"))
            }
            val parts = mutableListOf<String>()
            val action: String
            if (summary.hasFileChanges) {
                val changes = calls.filter { it.isFileChange }
                val labels = when {
                    summary.edits > 0 -> if (changes.size == 1 && changes.single().kind == ToolKind.Create) ToolNames.labels(ToolKind.Create, "") else ToolNames.labels(ToolKind.Edit, "")
                    else -> ToolNames.labels(ToolKind.Delete, "")
                }
                action = if (busy) labels.loading else labels.completed
                val fileCount = if (summary.edits > 0) summary.edits + summary.deletes else summary.deletes
                parts += summary.fileChangeFiles.singleOrNull() ?: plural(fileCount, "file")
                parts += explored(summary, prefix = true, first = false)
            } else {
                action = if (busy) "Exploring" else "Explored"
                parts += explored(summary, prefix = false, first = true)
            }
            if (summary.images > 0) parts += plural(summary.images, "image")
            if (summary.commands > 0) parts += "ran ${plural(summary.commands, "command")}"
            if (summary.taskCalls > 0) parts += plural(summary.taskCalls, "agent")
            return WorkHeader(action, parts.joinToString(", ").ifBlank { null }, summary.lineStats)
        }

        /**
         * The exploration counts: "3 files, 2 searches, 1 fetch, lints, 2 tools". A lone directory or file that
         * comes first is named instead of counted; after an edit the first part reads "explored 3 files".
         */
        private fun explored(summary: WorkSummary, prefix: Boolean, first: Boolean): List<String> {
            val parts = mutableListOf<String>()
            if (summary.directories.isNotEmpty()) {
                parts += if (first && summary.directories.size == 1) summary.directories.single() else plural(summary.directories.size, "directory", "directories")
            }
            if (summary.files.isNotEmpty()) {
                parts += if (first && parts.isEmpty() && summary.files.size == 1) summary.files.single() else plural(summary.files.size, "file")
            }
            if (summary.searches > 0) parts += plural(summary.searches, "search", "searches")
            if (summary.fetches > 0) parts += plural(summary.fetches, "fetch", "fetches")
            if (summary.lints > 0) parts += "lints"
            if (summary.mcpToolCalls > 0) parts += plural(summary.mcpToolCalls, "tool")
            if (prefix && parts.isNotEmpty()) parts[0] = "explored ${parts[0]}"
            return parts
        }

        private fun plural(count: Int, one: String, many: String = "${one}s") = "$count ${if (count == 1) one else many}"
    }
}

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

/**
 * The names the Cloud Agents API gives tool calls (`read_file`, `run_terminal_cmd`, `grep`, `mcp`, `task_v2`…), the
 * raw names of the desktop build (`read_file_v2`, `ripgrep_raw_search`…) and the SDK's (`read`, `shell`, `semSearch`…),
 * sorted into kinds, and the verbs the desktop build's `tool-action-labels.js` gives each kind.
 */
object ToolNames {
    private val KINDS: Map<String, ToolKind> = buildMap {
        fun put(kind: ToolKind, vararg names: String) = names.forEach { this[it] = kind }
        put(ToolKind.Read, "read_file", "read_file_v2", "read", "cat", "pi_read_tool_call", "read_mcp_resource", "fetch_mcp_resource")
        put(ToolKind.List, "list_dir", "list_dir_v2", "ls", "pi_ls_tool_call", "list_mcp_resources")
        put(ToolKind.Search, "codebase_search", "semantic_search_full", "semantic_search", "sem_search", "semsearch", "read_semsearch_files", "search_conversations")
        put(ToolKind.Grep, "grep", "grep_search", "ripgrep_search", "ripgrep_raw_search", "rg", "pi_grep_tool_call")
        put(ToolKind.Glob, "glob_file_search", "glob", "file_search", "pi_find_tool_call")
        put(ToolKind.Edit, "edit_file", "edit_file_v2", "edit", "search_replace", "str_replace", "multi_str_replace", "apply_patch", "reapply", "edit_notebook", "pi_edit_tool_call", "apply_agent_diff")
        put(ToolKind.Create, "write", "write_file", "create_file", "pi_write_tool_call")
        put(ToolKind.Delete, "delete_file", "delete")
        put(ToolKind.Shell, "run_terminal_cmd", "run_terminal_command", "run_terminal_command_v2", "shell", "bash", "terminal", "pi_bash_tool_call", "write_shell_stdin")
        put(ToolKind.WebSearch, "web_search", "websearch")
        put(ToolKind.WebFetch, "web_fetch", "webfetch", "fetch")
        put(ToolKind.Task, "task", "task_v2", "subagent")
        put(ToolKind.Mcp, "mcp", "call_mcp_tool", "call_dynamic_tool")
        put(ToolKind.McpTools, "get_mcp_tools", "getmcptools", "get_dynamic_tools")
        put(ToolKind.Todo, "todo_write", "update_todos", "updatetodos")
        put(ToolKind.Lints, "read_lints", "readlints")
        put(ToolKind.Question, "ask_question", "askquestion")
        put(ToolKind.Image, "generate_image", "generateimage")
        put(ToolKind.Plan, "create_plan", "createplan")
    }

    fun kindOf(name: String): ToolKind {
        val n = name.trim().lowercase().removeSuffix("toolcall").removeSuffix("_tool_call")
        KINDS[n]?.let { return it }
        if (n in OTHER) return ToolKind.Other
        // Names not seen before are placed by what they say; an unknown name is shown as itself.
        return when {
            n.startsWith("mcp_") || n.startsWith("mcp-") -> ToolKind.Mcp
            n.contains("subagent") -> ToolKind.Task
            n.contains("terminal") || n.contains("shell") -> ToolKind.Shell
            n.contains("delete") -> ToolKind.Delete
            n.contains("edit") || n.contains("replace") || n.contains("patch") -> ToolKind.Edit
            n.contains("write") -> ToolKind.Create
            n.contains("grep") -> ToolKind.Grep
            n.contains("glob") -> ToolKind.Glob
            n.contains("web") && n.contains("search") -> ToolKind.WebSearch
            n.contains("search") -> ToolKind.Search
            n.contains("fetch") -> ToolKind.WebFetch
            n.contains("read") -> ToolKind.Read
            n.contains("list") -> ToolKind.List
            else -> ToolKind.Other
        }
    }

    private val OTHER: Map<String, ToolLabels> = mapOf(
        "todo_read" to ToolLabels("Reading todos", "Read todos", "Read todos"),
        "fetch_rules" to ToolLabels("Fetching rules", "Fetched rules", "Fetch rules"),
        "switch_mode" to ToolLabels("Switching mode", "Switched mode", "Switch mode"),
        "record_screen" to ToolLabels("Recording screen", "Recorded screen", "Record screen"),
        "computer_use" to ToolLabels("Using computer", "Used computer", "Use computer"),
        "create_goal" to ToolLabels("Creating goal", "Created goal", "Create goal"),
        "update_goal" to ToolLabels("Updating goal", "Updated goal", "Update goal"),
        "await" to ToolLabels("Waiting", "Waited", "Wait"),
        "reflect" to ToolLabels("Reflecting", "Reflected", "Reflect"),
        "fetch_pull_request" to ToolLabels("Fetching PR", "Fetched PR", "Fetch PR"),
        "fetch_github_pr" to ToolLabels("Fetching PR", "Fetched PR", "Fetch PR"),
        "create_diagram" to ToolLabels("Creating diagram", "Created diagram", "Create diagram"),
        "pr_management" to ToolLabels("Managing PR", "Managed PR", "Manage PR"),
        "edit_pr_labels" to ToolLabels("Editing PR labels", "Edited PR labels", "Edit PR labels"),
        "fetch_cloud_agent_data" to ToolLabels("Fetching cloud agent data", "Fetched cloud agent data", "Fetch cloud agent data"),
        "mcp_auth" to ToolLabels("Authenticating MCP server", "Authenticated MCP server", "MCP authentication"),
        "connect_scm" to ToolLabels("Connecting GitHub", "Connected GitHub", "Connect GitHub"),
        "send_to_user" to ToolLabels("Sending message", "Sent message", "Send message"),
        "get_agent_status" to ToolLabels("Checking agents", "Checked agents", "Check agents"),
        "send_to_agent" to ToolLabels("Messaging agent", "Messaged agent", "Message agent"),
        "read_agent_transcript" to ToolLabels("Reading transcript", "Read transcript", "Read transcript"),
        "create_agent" to ToolLabels("Creating agent", "Created agent", "Create agent"),
        "stop_agent" to ToolLabels("Stopping agent", "Stopped agent", "Stop agent"),
        "setup_vm_environment" to ToolLabels("Setting up VM", "Set up VM", "Set up VM"),
        "truncated" to ToolLabels("Processing", "Processed", "Process"),
    )

    /** The verbs of a kind; for [ToolKind.Other] the tool's own, or its name read as words ("Switched mode"). */
    fun labels(kind: ToolKind, name: String): ToolLabels = when (kind) {
        ToolKind.Read -> ToolLabels("Reading", "Read", "Read")
        ToolKind.List -> ToolLabels("Listing", "Listed", "List")
        ToolKind.Search -> ToolLabels("Searching", "Searched", "Search")
        ToolKind.Grep -> ToolLabels("Grepping", "Grepped", "Grep")
        ToolKind.Glob -> ToolLabels("Searching files", "Searched files", "Search files")
        ToolKind.Edit -> ToolLabels("Editing", "Edited", "Edit")
        ToolKind.Create -> ToolLabels("Creating", "Created", "Create")
        ToolKind.Delete -> ToolLabels("Deleting", "Deleted", "Delete")
        ToolKind.Shell -> ToolLabels("Running", "Ran", "Run")
        ToolKind.WebSearch -> ToolLabels("Searching web", "Searched web", "Search web")
        // Cursor's plain `fetch` is "Fetched"; the web page fetch says what it fetched.
        ToolKind.WebFetch -> if (name.lowercase() == "fetch") ToolLabels("Fetching", "Fetched", "Fetch") else ToolLabels("Fetching page", "Fetched page", "Fetch page")
        ToolKind.Task -> ToolLabels("Working on task", "Completed task", "Work on task")
        ToolKind.Mcp -> ToolLabels("Running", "Ran", "Run")
        ToolKind.McpTools -> ToolLabels("Exploring", "Explored", "Explore tools")
        ToolKind.Todo -> ToolLabels("Updating", "Updated", "Update todos")
        ToolKind.Lints -> ToolLabels("Reading lints", "Read lints", "Read lints")
        ToolKind.Question -> ToolLabels("Asking questions", "Asked", "Ask question")
        ToolKind.Image -> ToolLabels("Generating image", "Generated image", "Generate image")
        ToolKind.Plan -> ToolLabels("Writing plan", "Wrote plan", "Write plan")
        ToolKind.Other -> OTHER[name.trim().lowercase()] ?: humanize(name).let { ToolLabels(it, it, it) }
    }

    /** `switch_mode` / `switchModeToolCall` → "Switch mode"; an empty name is "Tool", as it is on the desktop. */
    fun humanize(name: String): String {
        val words = name.trim()
            .removeSuffix("ToolCall")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("[_\\-]+"), " ")
            .trim()
            .lowercase()
        if (words.isEmpty()) return "Tool"
        return words.replaceFirstChar { it.uppercase() }
    }

    /** The last segment of a path: `app/src/Main.kt` → `Main.kt`; a bare name or `.` stays as it is. */
    fun basename(path: String): String {
        val trimmed = path.trim().trimEnd('/', '\\')
        if (trimmed.isEmpty()) return path.trim()
        return trimmed.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: trimmed
    }
}
