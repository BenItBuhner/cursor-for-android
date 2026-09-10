package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

/**
 * The wording of a tool call, checked against what Cursor's desktop build says for the same call
 * (`tool-action-labels.js` / `tool-call-view-model-description.js`), for the names and payload shapes the public
 * stream, the desktop and the SDK each use.
 */
class ToolCallMapperTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun call(name: String, args: String? = null, result: String? = null, status: String = "completed") =
        ToolCallMapper.from(SseToolCallDto(callId = "c", name = name, status = status, args = args?.let(::json), result = result?.let(::json)))

    private fun line(name: String, args: String? = null, result: String? = null, status: String = "completed"): String {
        val c = call(name, args, result, status)
        return listOf(c.action, c.details).filter { it.isNotEmpty() }.joinToString(" ")
    }

    @Test
    fun `reads name the file and its lines`() {
        assertThat(line("read_file", """{"target_file":"/workspace/app/src/main/Timeline.kt"}""")).isEqualTo("Read Timeline.kt")
        assertThat(line("read_file", """{"path":"README.md"}""", status = "running")).isEqualTo("Reading README.md")
        assertThat(line("read_file", """{"path":"a/b/Main.kt","start_line_one_indexed":10,"end_line_one_indexed_inclusive":40}""")).isEqualTo("Read Main.kt L10-40")
        assertThat(line("read_file", """{"path":"a/b/Main.kt","offset":100,"limit":50}""")).isEqualTo("Read Main.kt L100-149")
        assertThat(line("read_file_v2", """{"relativeWorkspacePath":"x/y.ts"}""")).isEqualTo("Read y.ts")
        assertThat(call("read_file", """{"path":"a/b/Main.kt"}""").detail).isEqualTo("a/b/Main.kt")
        // Without arguments the verb still stands.
        assertThat(line("read_file")).isEqualTo("Read")
    }

    @Test
    fun `the desktop's special reads keep their names`() {
        assertThat(line("read_file", """{"path":"/home/u/.cursor/skills-cursor/env-setup/SKILL.md"}""")).isEqualTo("Used env-setup")
        assertThat(line("read_file", """{"path":"/home/u/.cursor/plugins/cache/x/skills/figma-use/SKILL.md"}""", status = "running")).isEqualTo("Using figma-use")
        assertThat(line("read_file", """{"path":"/home/u/.cursor/projects/workspace/terminals/3.txt"}""")).isEqualTo("Read terminal")
        assertThat(line("read_file", """{"path":"/home/u/.cursor/projects/workspace/agent-tools/abc.txt"}""")).isEqualTo("Read tool output")
        assertThat(line("read_file", """{"path":"/home/u/.cursor/projects/workspace/agent-transcripts/bc-1/transcript.json"}""")).isEqualTo("Read agent transcript")
    }

    @Test
    fun `searches read as the desktop words them`() {
        assertThat(line("grep", """{"pattern":"ModelSheet","path":"app/src/ui"}""")).isEqualTo("Grepped ModelSheet in ui")
        assertThat(line("grep", """{"pattern":"TODO"}""")).isEqualTo("Grepped TODO")
        assertThat(line("grep", status = "running")).isEqualTo("Grepping")
        assertThat(line("ripgrep_raw_search", """{"pattern":"x"}""")).isEqualTo("Grepped x")
        assertThat(line("glob_file_search", """{"glob_pattern":"*.kt","target_directory":"/workspace/app"}""")).isEqualTo("Searched files *.kt in app")
        assertThat(line("glob", """{"globPattern":"**/*.ts"}""", status = "running")).isEqualTo("Searching files **/*.ts")
        assertThat(line("codebase_search", """{"query":"how are tool calls rendered in the conversation view"}""")).isEqualTo("Searched how are tool calls rendered in the co...")
        assertThat(line("semantic_search_full", """{"query":"auth"}""")).isEqualTo("Searched auth")
        assertThat(line("web_search", """{"search_term":"cursor cloud agents api"}""")).isEqualTo("Searched web cursor cloud agents api")
        assertThat(line("web_fetch", """{"url":"https://cursor.com/docs"}""")).isEqualTo("Fetched page https://cursor.com/docs")
        assertThat(line("fetch", """{"url":"https://x.y"}""", status = "running")).isEqualTo("Fetching https://x.y")
        assertThat(line("list_dir", """{"target_directory":"app/src/main"}""")).isEqualTo("Listed main")
        assertThat(line("list_dir", """{"path":"."}""")).isEqualTo("Listed .")
    }

    @Test
    fun `edits name the file, take their line counts from the result and tell a new file apart`() {
        val edited = call("search_replace", """{"path":"app/Timeline.kt"}""", """{"success":{"linesAdded":12,"linesRemoved":3}}""")
        assertThat(edited.kind).isEqualTo(ToolKind.Edit)
        assertThat("${edited.action} ${edited.details}").isEqualTo("Edited Timeline.kt")
        assertThat(edited.lineStats).isEqualTo("+12 -3")
        assertThat(line("edit_file", """{"target_file":"x/y.kt"}""", status = "running")).isEqualTo("Editing y.kt")
        assertThat(line("write", """{"path":"a/New.kt","fileText":"..."}""", """{"success":{"path":"a/New.kt","linesCreated":40,"fileSize":900}}""")).isEqualTo("Created New.kt")
        assertThat(call("write", """{"path":"a/New.kt"}""", """{"success":{"linesCreated":40}}""").lineStats).isEqualTo("+40")
        assertThat(line("edit_file", """{"path":"a/New.kt","is_new_file":true}""")).isEqualTo("Created New.kt")
        assertThat(line("delete_file", """{"path":"a/Old.kt"}""")).isEqualTo("Deleted Old.kt")
        assertThat(line("edit_notebook", """{"notebook_path":"n.ipynb"}""")).isEqualTo("Edited n.ipynb")
        assertThat(call("edit_file", """{"path":"a.kt"}""").lineStats).isNull()
    }

    @Test
    fun `a command is described by the agent's description, else by the command itself`() {
        val bare = call("run_terminal_cmd", """{"command":"git status --short\n&& git log","is_background":false}""")
        assertThat(bare.action).isEqualTo("Ran")
        assertThat(bare.summary).isEqualTo("git status --short")
        assertThat(bare.detail).isEqualTo("git status --short\n&& git log")
        val described = call("run_terminal_cmd", """{"command":"git status","description":"run the git status check"}""", status = "running")
        assertThat(described.action).isEqualTo("Running")
        assertThat(described.summary).isEqualTo("The git status check")
        assertThat(line("shell", """{"command":"ls"}""")).isEqualTo("Ran ls")
        assertThat(line("run_terminal_command_v2", """{"command":"ls"}""", status = "running")).isEqualTo("Running ls")
    }

    @Test
    fun `an MCP call names its tool and server however the stream spells them`() {
        val sdk = call("mcp", """{"providerIdentifier":"user-Github","toolName":"list_pull_requests","args":{"owner":"o"}}""")
        assertThat(sdk.kind).isEqualTo(ToolKind.Mcp)
        assertThat(sdk.action).isEqualTo("Ran")
        assertThat(sdk.summary).isEqualTo("list_pull_requests")
        assertThat(sdk.server).isEqualTo("Github")
        assertThat(sdk.detail).isEqualTo("""{"owner":"o"}""")

        val demo = call("mcp", """{"server":"linear","tool":"list_issues"}""", status = "running")
        assertThat(demo.action).isEqualTo("Running")
        assertThat(demo.summary).isEqualTo("list_issues")
        assertThat(demo.server).isEqualTo("linear")

        // Only a raw `Server-tool_name`: split at the last dash until the result says which tool ran.
        val raw = call("mcp", """{"name":"Github-list_pull_requests"}""")
        assertThat(raw.summary).isEqualTo("list_pull_requests")
        assertThat(raw.server).isEqualTo("Github")
        val hyphenated = call("mcp", """{"name":"cursor-cloud-run-info"}""", """{"resultType":"mcpResult","value":{"selectedTool":"run-info","result":"..."}}""")
        assertThat(hyphenated.summary).isEqualTo("run-info")
        assertThat(hyphenated.server).isEqualTo("cursor-cloud")
        val lone = call("mcp", """{"name":"run_info"}""")
        assertThat(lone.summary).isEqualTo("run_info")
        assertThat(lone.server).isNull()

        // The cloud stream's `mcp` with nothing but a result names the tool from it.
        val fromResult = call("mcp", result = """{"resultType":"mcpResult","value":{"selectedTool":"run-info","result":"ok"}}""")
        assertThat(fromResult.summary).isEqualTo("run-info")
        assertThat(call("mcp").summary).isEmpty()
        assertThat(call("mcp").action).isEqualTo("Ran")
    }

    @Test
    fun `looking up the MCP tools is exploring the available tools`() {
        assertThat(line("get_mcp_tools")).isEqualTo("Explored available tools")
        assertThat(line("get_mcp_tools", status = "running")).isEqualTo("Exploring available tools")
        assertThat(call("get_mcp_tools").kind).isEqualTo(ToolKind.McpTools)
    }

    @Test
    fun `a subagent is a task described by its description`() {
        val task = call("task_v2", """{"description":"Map tool-call rendering code","prompt":"Thoroughness: very thorough. Find the files.","subagent_type":"explore"}""", status = "running")
        assertThat(task.kind).isEqualTo(ToolKind.Task)
        assertThat(task.action).isEqualTo("Working on task")
        assertThat(task.summary).isEqualTo("Map tool-call rendering code")
        assertThat(task.detail).isEqualTo("Thoroughness: very thorough. Find the files.")
        assertThat(line("task", """{"prompt":"x"}""")).isEqualTo("Completed task subagent")
    }

    @Test
    fun `a failed call reads as attempted`() {
        val failed = call("edit_file", """{"path":"a/b.kt"}""", """{"error":"old_string not found"}""")
        assertThat(failed.isError).isTrue()
        assertThat("${failed.action} ${failed.details}").isEqualTo("Edit attempted")
        assertThat(line("read_file", """{"path":"x"}""", """{"status":"error","error":{"message":"missing"}}""")).isEqualTo("Read attempted")
        assertThat(line("mcp", """{"toolName":"t","providerIdentifier":"s"}""", """{"value":{"isError":true}}""")).isEqualTo("Run attempted")
        assertThat(line("grep", """{"pattern":"x"}""", status = "error")).isEqualTo("Grep attempted")
        // A running call is never "attempted", and a result without an error is not one.
        assertThat(call("edit_file", """{"path":"a.kt"}""", status = "running").isError).isFalse()
        assertThat(call("read_file", """{"path":"a.kt"}""", """{"success":{"content":"","totalLines":0,"fileSize":0}}""").isError).isFalse()
    }

    /** The payloads are tool-specific and unstable: a client that always sends these fields still succeeded. */
    @Test
    fun `a rejection is what the field says, not that it is there`() {
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"rejected":true}""").isError).isTrue()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"permissionDenied":"true"}""").isError).isTrue()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"rejected":false}""").isError).isFalse()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"permissionDenied":null}""").isError).isFalse()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"rejected":{}}""").isError).isFalse()
        // An error field the same: reported when it says something.
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"error":""}""").isError).isFalse()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"error":false}""").isError).isFalse()
        assertThat(call("edit_file", """{"path":"a.kt"}""", """{"error":null}""").isError).isFalse()
    }

    @Test
    fun `a to-do update tells what changed against the previous list`() {
        val first = """{"todos":[{"id":"1","content":"Add tests","status":"pending"},{"id":"2","content":"Ship","status":"pending"}]}"""
        val prev = ToolCallMapper.todos(SseToolCallDto("t1", "todo_write", "completed", json(first)))
        assertThat(line("todo_write", first)).isEqualTo("Added 2 to-dos")
        assertThat(line("todo_write", first, status = "running")).isEqualTo("Updating to-do list")

        fun after(text: String) = ToolCallMapper.from(SseToolCallDto("t2", "todo_write", "completed", json(text)), prev).let { "${it.action} ${it.details}".trim() }
        assertThat(after("""{"todos":[{"id":"1","content":"Add tests","status":"in_progress"},{"id":"2","content":"Ship","status":"pending"}]}""")).isEqualTo("Started to-do Add tests")
        assertThat(after("""{"todos":[{"id":"1","content":"Add tests","status":"completed"},{"id":"2","content":"Ship","status":"pending"}]}""")).isEqualTo("Completed 1 of 2 Add tests")
        assertThat(after("""{"todos":[{"id":"1","content":"Add tests","status":"completed"},{"id":"2","content":"Ship","status":"completed"}]}""")).isEqualTo("Completed 2 of 2 to-dos")
        assertThat(after("""{"todos":[{"id":"1","content":"Add tests","status":"pending"},{"id":"2","content":"Ship","status":"pending"},{"id":"3","content":"Docs","status":"pending"}]}""")).isEqualTo("Added to-do Docs")
        assertThat(after("""{"todos":[{"id":"1","content":"Add tests","status":"cancelled"},{"id":"2","content":"Ship","status":"pending"}]}""")).isEqualTo("Cancelled to-do Add tests")
        assertThat(after(first)).isEqualTo("Checked to-do list")
        assertThat(after("""{"todos":[]}""")).isEqualTo("Cleared to-do list")
    }

    @Test
    fun `the other tools keep their desktop verbs or read as their names`() {
        assertThat(line("read_lints", """{"paths":["a"]}""")).isEqualTo("Read lints")
        assertThat(line("ask_question", """{"questions":[{"q":"a"},{"q":"b"}]}""")).isEqualTo("Asked 2 questions")
        assertThat(line("generate_image", """{"prompt":"A red bicycle"}""", status = "running")).isEqualTo("Generating image A red bicycle")
        assertThat(line("create_plan")).isEqualTo("Wrote plan")
        assertThat(line("switch_mode", """{"target_mode_id":"agent"}""")).isEqualTo("Switched mode")
        assertThat(line("fetch_rules")).isEqualTo("Fetched rules")
        assertThat(line("record_screen", status = "running")).isEqualTo("Recording screen")
        assertThat(line("create_goal")).isEqualTo("Created goal")
        assertThat(line("frobnicate_widget")).isEqualTo("Frobnicate widget")
        assertThat(line("switchModeToolCall")).isEqualTo("Switch mode")
        assertThat(ToolNames.humanize("")).isEqualTo("Tool")
    }

    @Test
    fun `every name the streams use lands on a kind`() {
        val expected = mapOf(
            "read_file" to ToolKind.Read, "read_file_v2" to ToolKind.Read, "read" to ToolKind.Read,
            "list_dir" to ToolKind.List, "list_dir_v2" to ToolKind.List, "ls" to ToolKind.List,
            "codebase_search" to ToolKind.Search, "semantic_search_full" to ToolKind.Search, "semSearch" to ToolKind.Search,
            "grep" to ToolKind.Grep, "ripgrep_raw_search" to ToolKind.Grep,
            "glob_file_search" to ToolKind.Glob, "glob" to ToolKind.Glob, "file_search" to ToolKind.Glob,
            "edit_file" to ToolKind.Edit, "edit_file_v2" to ToolKind.Edit, "search_replace" to ToolKind.Edit, "multi_str_replace" to ToolKind.Edit, "apply_patch" to ToolKind.Edit,
            "write" to ToolKind.Create, "delete_file" to ToolKind.Delete,
            "run_terminal_cmd" to ToolKind.Shell, "run_terminal_command_v2" to ToolKind.Shell, "shell" to ToolKind.Shell,
            "web_search" to ToolKind.WebSearch, "web_fetch" to ToolKind.WebFetch, "fetch" to ToolKind.WebFetch,
            "task" to ToolKind.Task, "task_v2" to ToolKind.Task,
            "mcp" to ToolKind.Mcp, "get_mcp_tools" to ToolKind.McpTools, "getMcpToolsToolCall" to ToolKind.McpTools,
            "todo_write" to ToolKind.Todo, "read_lints" to ToolKind.Lints, "ask_question" to ToolKind.Question,
            "generate_image" to ToolKind.Image, "create_plan" to ToolKind.Plan,
            "switch_mode" to ToolKind.Other, "never_seen_before" to ToolKind.Other,
        )
        expected.forEach { (name, kind) -> assertThat(name to ToolNames.kindOf(name)).isEqualTo(name to kind) }
    }
}
