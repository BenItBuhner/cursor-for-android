package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.DiffStats
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolLabels
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolOutput
import com.cursorforandroid.domain.ToolTruncation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Turns a `tool_call` event into the [ToolCall] the conversation shows, worded the way Cursor's client words the
 * same call (`tool-call-view-model-description.js` in the desktop build): the file's name for a read or an edit,
 * "pattern in dir" for a grep, the tool and its server for an MCP call, "available tools" for a look at the MCP
 * tools. The API says the payloads are tool-specific and unstable, so every key is looked for under the names the
 * public stream, the desktop build and the SDK each use, and a call that carries none of them still gets its verb.
 */
object ToolCallMapper {

    /** One to-do as the agent's to-do list carries it; [previousTodos] is the list as of the last update. */
    data class Todo(val id: String, val content: String, val status: String)

    /**
     * The [ToolCall] for a `tool_call` event. [images] keeps the bytes of a generated image on the device (see
     * [ToolPayloads.from]); without one a small image stays inline and a large one is noted but not kept.
     */
    fun from(dto: SseToolCallDto, previousTodos: List<Todo>? = null, images: GeneratedImageSink? = null): ToolCall {
        val kind = ToolNames.kindOf(dto.name)
        val args = dto.args as? JsonObject
        val result = dto.result
        val running = dto.status == ToolCall.STATUS_RUNNING
        val isError = dto.status == "error" || (!running && isErrorResult(result))
        val stats = if (kind == ToolKind.Edit || kind == ToolKind.Create) editStats(result) else null
        val described = describe(kind, dto.name, args, result, running, previousTodos)
        // Everything the row needs is read here; the payload itself is not kept (see [ToolCall.output]).
        val output = ToolOutput.from(described.kind, described.detail, result)
        // What the call produced, clipped like the output: the simplified event carries it when it is small enough.
        val payload = if (isError) null else ToolPayloads.from(dto.name, dto.args, result, images, dto.callId)
        return ToolCall(
            callId = dto.callId,
            name = dto.name,
            kind = described.kind,
            status = dto.status,
            summary = described.summary,
            server = described.server,
            detail = described.detail,
            linesAdded = stats?.additions,
            linesRemoved = stats?.deletions,
            isError = isError,
            labels = described.labels,
            output = output.output,
            exitCode = output.exitCode,
            payload = payload,
            truncated = dto.truncated?.takeIf { it.args || it.result }?.let { ToolTruncation(it.args, it.result) },
        )
    }

    /** The to-do list an update carries, for the next update to be described against. */
    fun todos(dto: SseToolCallDto): List<Todo>? {
        if (ToolNames.kindOf(dto.name) != ToolKind.Todo) return null
        return todosOf(dto.args as? JsonObject)
    }

    private class Description(
        val summary: String,
        val kind: ToolKind,
        val server: String? = null,
        detail: String? = null,
        val labels: ToolLabels? = null,
    ) {
        /** Bounded here rather than at each call it is read from: a command or an MCP argument has no size limit. */
        val detail: String? = ToolOutput.detail(detail)
    }

    private fun describe(kind: ToolKind, name: String, args: JsonObject?, result: JsonElement?, running: Boolean, previousTodos: List<Todo>?): Description = when (kind) {
        ToolKind.Read -> {
            val path = args.string(PATH_KEYS)
            when {
                path == null -> Description("", kind)
                else -> {
                    val special = specialRead(path)
                    if (special != null) Description(special.second, kind, detail = path, labels = special.first) else Description(ToolNames.basename(path) + readRange(args), kind, detail = path)
                }
            }
        }
        ToolKind.List -> args.string(PATH_KEYS + DIRECTORY_KEYS).let { path -> Description(path?.let(ToolNames::basename).orEmpty(), kind, detail = path) }
        ToolKind.Grep -> {
            val pattern = args.string(PATTERN_KEYS).orEmpty()
            val path = args.string(PATH_KEYS + DIRECTORY_KEYS)
            Description(if (path != null && pattern.isNotEmpty()) "$pattern in ${ToolNames.basename(path)}" else pattern, kind, detail = listOfNotNull(pattern.ifBlank { null }, path?.let { "in $it" }).joinToString(" ").ifBlank { null })
        }
        ToolKind.Glob -> {
            val pattern = args.string(PATTERN_KEYS).orEmpty()
            val dir = args.string(DIRECTORY_KEYS + PATH_KEYS)
            Description(if (dir != null && pattern.isNotEmpty()) "$pattern in ${ToolNames.basename(dir)}" else pattern, kind, detail = listOfNotNull(pattern.ifBlank { null }, dir?.let { "in $it" }).joinToString(" ").ifBlank { null })
        }
        ToolKind.Search -> args.string(QUERY_KEYS).orEmpty().let { Description(truncate(it, QUERY_MAX), kind, detail = it.ifBlank { null }) }
        ToolKind.Edit, ToolKind.Create, ToolKind.Delete -> {
            val path = args.string(PATH_KEYS)
            val created = kind == ToolKind.Create || args.bool("is_new_file", "isNewFile") == true || result.obj()?.deep("linesCreated") != null
            Description(path?.let(ToolNames::basename).orEmpty(), if (kind == ToolKind.Edit && created) ToolKind.Create else kind, detail = path)
        }
        ToolKind.Shell -> {
            val command = args.string(COMMAND_KEYS)
            val description = args.string(DESCRIPTION_KEYS)?.let(::shellDescription)
            Description(description ?: command?.lineSequence()?.firstOrNull()?.trim().orEmpty(), kind, detail = command)
        }
        ToolKind.WebSearch -> args.string(QUERY_KEYS).orEmpty().let { Description(it, kind, detail = it.ifBlank { null }) }
        ToolKind.WebFetch -> args.string(URL_KEYS).orEmpty().let { Description(it, kind, detail = it.ifBlank { null }) }
        ToolKind.Task -> {
            val description = args.string(DESCRIPTION_KEYS)
            Description(description ?: "subagent", kind, detail = args.string(listOf("prompt"))?.lineSequence()?.firstOrNull()?.take(PROMPT_MAX))
        }
        ToolKind.Mcp -> mcp(args, result)
        ToolKind.McpTools -> Description("available tools", kind)
        ToolKind.Todo -> todoDescription(previousTodos, todosOf(args), running)
        ToolKind.Lints -> Description("", kind)
        ToolKind.Question -> {
            // "Asking questions" while they are open, "Asked 2 questions" once answered.
            val count = (args?.get("questions") as? JsonArray)?.size ?: 0
            Description(if (running) "" else if (count == 1) "1 question" else "$count questions", kind)
        }
        ToolKind.Image -> args.string(listOf("prompt")).orEmpty().let { Description(truncate(it, PROMPT_MAX), kind, detail = it.ifBlank { null }) }
        ToolKind.Plan -> Description("", kind)
        ToolKind.Other -> Description("", kind)
    }

    /**
     * An MCP call names its tool and server under `toolName` / `providerIdentifier` (the desktop and the SDK), or
     * `server` / `tool`, or only a raw `name` of the form `Server-tool_name`; a finished call's result names the tool
     * it ran as `selectedTool`. A provider identifier is a display name once its `user-` / `team-` scope is dropped.
     */
    private fun mcp(args: JsonObject?, result: JsonElement?): Description {
        val resultObj = result.obj()
        val selected = resultObj?.deep("selectedTool")?.string()
        var tool = args.string(listOf("toolName", "tool_name", "tool")) ?: selected
        var server = args.string(listOf("providerIdentifier", "provider_identifier", "serverName", "server_name", "server", "namespace"))
        val raw = args.string(listOf("name", "rawName", "raw_name"))
        if (raw != null) {
            if (tool != null && raw.endsWith("-$tool") && raw.length > tool.length + 1) {
                server = server ?: raw.removeSuffix("-$tool")
            } else if (tool == null) {
                val split = raw.lastIndexOf('-')
                if (split > 0 && split < raw.lastIndex && server == null) {
                    server = raw.substring(0, split)
                    tool = raw.substring(split + 1)
                } else {
                    tool = raw
                }
            }
        }
        val displayServer = server?.replace(PROVIDER_SCOPE, "")?.ifBlank { null }
        val inner = (args?.get("args") ?: args?.get("arguments"))?.toString()
        return Description(tool.orEmpty(), ToolKind.Mcp, server = displayServer, detail = inner?.takeIf { it != "{}" && it != "null" })
    }

    /**
     * The one-line story of a to-do update, as the desktop tells it: what changed against the previous list — a to-do
     * started, some completed ("Completed 2 of 5"), added or cancelled — or that the list was checked.
     */
    private fun todoDescription(previous: List<Todo>?, updated: List<Todo>?, running: Boolean): Description {
        val error = "Update todos"
        if (running) return Description("to-do list", ToolKind.Todo, labels = ToolLabels("Updating", "Updated", error))
        fun labels(completed: String) = ToolLabels("Updating", completed, error)
        val before = previous.orEmpty()
        val after = updated.orEmpty()
        if (after.isEmpty()) return Description("to-do list", ToolKind.Todo, labels = labels(if (before.isNotEmpty()) "Cleared" else "Checked"))
        val was = before.associate { it.id to it.status }
        val started = after.filter { it.status == "inProgress" && was[it.id] != "inProgress" }
        val completed = after.filter { it.status == "completed" && was[it.id] != "completed" }
        val added = after.filter { it.status == "pending" && it.id !in was }
        val cancelled = after.filter { it.status == "cancelled" && was[it.id] != "cancelled" }
        return when {
            started.size == 1 -> Description(started.single().content, ToolKind.Todo, labels = labels("Started to-do"))
            started.size > 1 -> Description("", ToolKind.Todo, labels = labels("Started ${started.size} to-dos"))
            completed.isNotEmpty() -> {
                val done = after.count { it.status == "completed" }
                if (completed.size == 1) Description(completed.single().content, ToolKind.Todo, labels = labels("Completed $done of ${after.size}"))
                else Description("", ToolKind.Todo, labels = labels("Completed $done of ${after.size} to-dos"))
            }
            added.size == 1 -> Description(added.single().content, ToolKind.Todo, labels = labels("Added to-do"))
            added.size > 1 -> Description("", ToolKind.Todo, labels = labels("Added ${added.size} to-dos"))
            cancelled.size == 1 -> Description(cancelled.single().content, ToolKind.Todo, labels = labels("Cancelled to-do"))
            cancelled.size > 1 -> Description("", ToolKind.Todo, labels = labels("Cancelled ${cancelled.size} to-dos"))
            else -> Description("to-do list", ToolKind.Todo, labels = labels("Checked"))
        }
    }

    private fun todosOf(args: JsonObject?): List<Todo>? {
        val list = args?.get("todos") as? JsonArray ?: return null
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            Todo(
                id = obj.string(listOf("id")) ?: return@mapNotNull null,
                content = obj.string(listOf("content", "text", "title")).orEmpty(),
                status = normalizeTodoStatus(obj.string(listOf("status")).orEmpty()),
            )
        }
    }

    private fun normalizeTodoStatus(status: String) = when (status.lowercase().replace("_", "").replace("-", "")) {
        "inprogress", "started", "active" -> "inProgress"
        "completed", "done" -> "completed"
        "cancelled", "canceled" -> "cancelled"
        else -> "pending"
    }

    /**
     * Reads the desktop treats specially: a skill file is "Used skill-name", a terminal transcript "Read terminal", a
     * saved tool output "Read tool output", an agent's transcript "Read agent transcript".
     */
    private fun specialRead(path: String): Pair<ToolLabels, String>? {
        val p = path.replace('\\', '/')
        val skill = skillName(p)
        if (skill != null) return ToolLabels("Using", "Used", "Use") to skill
        val read = ToolLabels("Reading", "Read", "Read")
        val segments = p.split('/').filter { it.isNotEmpty() }
        val projectsAt = segments.indexOf(".cursor").takeIf { it >= 0 && segments.getOrNull(it + 1) == "projects" }
        if (projectsAt != null) {
            val folder = segments.getOrNull(projectsAt + 3)
            val rest = segments.drop(projectsAt + 4)
            when (folder) {
                "terminals" -> if (rest.size == 1 && TERMINAL_FILE.matches(rest[0])) return read to "terminal"
                "agent-tools" -> if (rest.size == 1 && rest[0].endsWith(".txt")) return read to "tool output"
                "agent-transcripts" -> return read to "agent transcript"
            }
        }
        return null
    }

    private fun skillName(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (path.endsWith("/SKILL.md") || path == "SKILL.md") {
            if (SKILL_ROOTS.none { path.contains(it) }) return null
            return segments.getOrNull(segments.size - 2)
        }
        if (path.endsWith(".md") && path.contains(".cursor/cloud-skills/")) return segments.last().removeSuffix(".md")
        return null
    }

    /** "L12-40" when the read was of a range; the public stream names the lines in a few ways. */
    private fun readRange(args: JsonObject?): String {
        val start = args.int("start_line_one_indexed", "startLine", "start_line") ?: args.int("offset")
        val end = args.int("end_line_one_indexed_inclusive", "endLine", "end_line")
            ?: args.int("limit")?.let { limit -> start?.let { it + limit - 1 } }
        return if (start != null && end != null && end >= start) " L$start-$end" else ""
    }

    /** The agent's description of a command, sentence-cased and without a leading "run": "Check the git status". */
    private fun shellDescription(description: String): String? {
        val text = description.trim().replace(LEADING_RUN, "")
        return text.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    }

    private fun editStats(result: JsonElement?): DiffStats? {
        DiffStats.fromToolResult(result)?.let { return it }
        val created = result.obj()?.deep("linesCreated")?.intOrNull() ?: return null
        return DiffStats(created, 0)
    }

    /**
     * Whether a finished call's result says it failed: an `error` key, an error `status`, the MCP result's `isError`,
     * or a rejection. A result that is not an object says nothing.
     */
    private fun isErrorResult(result: JsonElement?): Boolean {
        val obj = result.obj() ?: return false
        if (obj["error"].isReported()) return true
        if (obj["rejected"].isTrue() || obj["permissionDenied"].isTrue()) return true
        if (obj.string(listOf("status"))?.lowercase() == "error") return true
        if (obj.string(listOf("resultType"))?.lowercase()?.contains("error") == true) return true
        val value = obj["value"] as? JsonObject
        return (value?.get("isError") as? JsonPrimitive)?.booleanOrNull == true || (obj["isError"] as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun truncate(text: String, max: Int) = if (text.length > max) text.take(max - 3) + "..." else text

    /** A reason that says something. A field that is absent, null, empty or false reports no failure. */
    private fun JsonElement?.isReported(): Boolean = when (this) {
        null, JsonNull -> false
        is JsonPrimitive -> content.isNotBlank() && booleanOrNull != false
        else -> true
    }

    /** A flag the payload sets, as a JSON boolean or as its string form. Merely naming it says nothing. */
    private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.string(keys: List<String>): String? {
        if (this == null) return null
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun JsonObject?.int(vararg keys: String): Int? {
        if (this == null) return null
        for (key in keys) (this[key] as? JsonPrimitive)?.intOrNull?.let { return it }
        return null
    }

    private fun JsonObject?.bool(vararg keys: String): Boolean? {
        if (this == null) return null
        for (key in keys) (this[key] as? JsonPrimitive)?.booleanOrNull?.let { return it }
        return null
    }

    /** The first value under [key] within two levels of nesting: results wrap their fields in `success` / `value`. */
    private fun JsonObject.deep(key: String, depth: Int = 0): JsonElement? {
        this[key]?.let { return it }
        if (depth >= 2) return null
        for (value in values) if (value is JsonObject) value.deep(key, depth + 1)?.let { return it }
        return null
    }

    private val PATH_KEYS = listOf("path", "target_file", "targetFile", "file_path", "filePath", "relative_workspace_path", "relativeWorkspacePath", "file", "notebook_path", "absolutePath")
    private val DIRECTORY_KEYS = listOf("target_directory", "targetDirectory", "directory_path", "directoryPath", "cwd", "directory")
    private val PATTERN_KEYS = listOf("pattern", "glob_pattern", "globPattern", "query")
    private val QUERY_KEYS = listOf("query", "search_term", "searchTerm", "term", "pattern")
    private val COMMAND_KEYS = listOf("command", "cmd")
    private val DESCRIPTION_KEYS = listOf("description")
    private val URL_KEYS = listOf("url", "uri")
    /** Compiled once: [describe] runs for every tool event on the stream. */
    private val PROVIDER_SCOPE = Regex("^(user|team|project)-")
    private val TERMINAL_FILE = Regex("^(ext-)?\\d+\\.txt$")
    private val LEADING_RUN = Regex("^run(?=\\s|$)\\s*", RegexOption.IGNORE_CASE)
    private val SKILL_ROOTS = listOf(".cursor/skills/", ".cursor/skills-cursor/", ".cursor/cloud-skills/", ".cursor/plugins/", ".claude/skills/", ".claude/plugins/", ".codex/skills/", ".grok/skills/", ".agents/skills/")
    private const val QUERY_MAX = 40
    private const val PROMPT_MAX = 48
}
