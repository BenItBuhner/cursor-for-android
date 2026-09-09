package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * The summary row of a stretch of work, worded the way Cursor's `step-group-display` words a step group, and the rule
 * for when the steps collapse behind it at all.
 */
class WorkHeaderTest {

    private fun call(kind: ToolKind, summary: String, status: String = "completed", detail: String? = null, added: Int? = null, removed: Int? = null) =
        ToolCall("c-${summary}-${kind}", kind.name.lowercase(), kind, status, summary, detail = detail ?: summary, linesAdded = added, linesRemoved = removed)

    private fun group(vararg steps: ActivityStep) = ActivityGroup("g", steps.toList())

    @Test
    fun `exploring names a lone file or directory and counts the rest`() {
        assertThat(group(call(ToolKind.Read, "README.md"), call(ToolKind.Grep, "TODO")).header).isEqualTo(WorkHeader("Explored", "README.md, 1 search"))
        assertThat(group(call(ToolKind.List, "src"), call(ToolKind.Read, "A.kt"), call(ToolKind.Read, "B.kt")).header).isEqualTo(WorkHeader("Explored", "src, 2 files"))
        assertThat(group(call(ToolKind.List, "src"), call(ToolKind.List, "test"), call(ToolKind.Read, "A.kt")).header).isEqualTo(WorkHeader("Explored", "2 directories, 1 file"))
        // Two reads of one file count twice, as they do on the desktop.
        assertThat(group(call(ToolKind.Read, "A.kt"), call(ToolKind.Read, "A.kt"), call(ToolKind.Read, "B.kt")).header).isEqualTo(WorkHeader("Explored", "3 files"))
        assertThat(
            group(
                call(ToolKind.Read, "A.kt"), call(ToolKind.Search, "auth"), call(ToolKind.Glob, "*.kt"), call(ToolKind.WebSearch, "x"), call(ToolKind.McpTools, "available tools"),
                call(ToolKind.WebFetch, "https://x"), call(ToolKind.Lints, ""), call(ToolKind.Mcp, "list_issues"), call(ToolKind.Mcp, "get_issue"),
                call(ToolKind.Image, "a cat"), call(ToolKind.Shell, "ls"), call(ToolKind.Shell, "pwd"), call(ToolKind.Task, "Survey"),
            ).header,
        ).isEqualTo(WorkHeader("Explored", "A.kt, 4 searches, 1 fetch, lints, 2 tools, 1 image, ran 2 commands, 1 agent"))
    }

    @Test
    fun `nothing but commands is Ran N commands, one described command by its description`() {
        assertThat(group(call(ToolKind.Shell, "ls", detail = "ls")).header).isEqualTo(WorkHeader("Ran", "1 command"))
        assertThat(group(call(ToolKind.Shell, "ls", detail = "ls"), call(ToolKind.Shell, "pwd", detail = "pwd")).header).isEqualTo(WorkHeader("Ran", "2 commands"))
        assertThat(group(call(ToolKind.Shell, "ls", status = "running", detail = "ls")).header).isEqualTo(WorkHeader("Running", "1 command"))
        assertThat(group(call(ToolKind.Shell, "Check the git status", detail = "git status")).header).isEqualTo(WorkHeader("Ran", "Check the git status"))
    }

    @Test
    fun `edits come first, then what was explored, then the line counts set apart`() {
        val edits = group(
            call(ToolKind.Read, "A.kt"), call(ToolKind.Read, "B.kt"), call(ToolKind.Grep, "x"),
            call(ToolKind.Edit, "A.kt", added = 12, removed = 3), call(ToolKind.Edit, "B.kt", added = 4, removed = 0), call(ToolKind.Shell, "./gradlew test"),
        )
        assertThat(edits.header).isEqualTo(WorkHeader("Edited", "2 files, explored 2 files, 1 search, ran 1 command", "+16 -3"))
        assertThat(group(call(ToolKind.Edit, "A.kt", added = 12, removed = 3), call(ToolKind.Edit, "A.kt", added = 1, removed = 1)).header)
            .isEqualTo(WorkHeader("Edited", "A.kt", "+13 -4"))
        assertThat(group(call(ToolKind.Create, "New.kt", added = 40)).header).isEqualTo(WorkHeader("Created", "New.kt", "+40"))
        assertThat(group(call(ToolKind.Create, "New.kt"), call(ToolKind.Edit, "Old.kt")).header).isEqualTo(WorkHeader("Edited", "2 files"))
        assertThat(group(call(ToolKind.Delete, "Old.kt")).header).isEqualTo(WorkHeader("Deleted", "Old.kt"))
        assertThat(group(call(ToolKind.Edit, "A.kt", status = "running")).header).isEqualTo(WorkHeader("Editing", "A.kt"))
        // One edit without counts and the counts are not shown at all, as on the desktop.
        assertThat(group(call(ToolKind.Edit, "A.kt", added = 12, removed = 3), call(ToolKind.Edit, "B.kt")).header).isEqualTo(WorkHeader("Edited", "2 files"))
    }

    @Test
    fun `nothing but images is Generated N images`() {
        assertThat(group(call(ToolKind.Image, "a cat"), call(ToolKind.Image, "a dog")).header).isEqualTo(WorkHeader("Generated", "2 images"))
    }

    @Test
    fun `one or two bare reads stay as lines, anything else collapses`() {
        assertThat(group(call(ToolKind.Read, "A.kt")).isWorkGrouped).isFalse()
        assertThat(group(call(ToolKind.Read, "A.kt"), call(ToolKind.List, "src")).isWorkGrouped).isFalse()
        assertThat(group(call(ToolKind.Read, "A.kt"), call(ToolKind.Read, "B.kt"), call(ToolKind.Read, "C.kt")).isWorkGrouped).isTrue()
        assertThat(group(call(ToolKind.Grep, "x")).isWorkGrouped).isTrue()
        assertThat(group(call(ToolKind.Shell, "ls")).isWorkGrouped).isTrue()
        assertThat(group(call(ToolKind.Read, "A.kt"), ThinkingBlock("Hm.")).isWorkGrouped).isTrue()
        // A thought before the reads is its own row and does not make a group of them.
        assertThat(group(ThinkingBlock("Hm."), call(ToolKind.Read, "A.kt")).isWorkGrouped).isFalse()
        assertThat(group(ThinkingBlock("Hm.")).isWorkGrouped).isFalse()
    }

    @Test
    fun `the thought row says how long, or briefly, or nothing when untimed`() {
        assertThat(group(ThinkingBlock("Hm.", durationSeconds = 3)).let { it.thoughtAction to it.thoughtDetails }).isEqualTo("Thought" to "3s")
        assertThat(group(ThinkingBlock("Hm.", durationSeconds = 0)).thoughtDetails).isEqualTo("briefly")
        assertThat(group(ThinkingBlock("Hm.")).thoughtDetails).isNull()
        assertThat(group(ThinkingBlock("Hm.", isStreaming = true)).let { it.thoughtAction to it.thoughtDetails }).isEqualTo("Thinking" to null)
        val streaming = group(ThinkingBlock("Hm.", isStreaming = true))
        assertThat(streaming.isLeadingThoughtStreaming).isTrue()
        assertThat(streaming.isWorkBusy).isFalse()
        val later = group(call(ToolKind.Read, "A.kt"), ThinkingBlock("Then.", isStreaming = true))
        assertThat(later.isLeadingThoughtStreaming).isFalse()
        assertThat(later.isWorkBusy).isTrue()
    }

    @Test
    fun `a tool call's line stats and error wording`() {
        assertThat(call(ToolKind.Edit, "A.kt", added = 3, removed = 0).lineStats).isEqualTo("+3")
        assertThat(call(ToolKind.Edit, "A.kt", added = 0, removed = 2).lineStats).isEqualTo("-2")
        assertThat(call(ToolKind.Edit, "A.kt").lineStats).isNull()
        val failed = ToolCall("c", "edit_file", ToolKind.Edit, "completed", "A.kt", isError = true)
        assertThat(failed.action).isEqualTo("Edit")
        assertThat(failed.details).isEqualTo("attempted")
        val overridden = ToolCall("c", "todo_write", ToolKind.Todo, "completed", "Add tests", labels = ToolLabels("Updating", "Started to-do", "Update todos"))
        assertThat(overridden.action).isEqualTo("Started to-do")
    }

    @Test
    fun `tool output is read from the shapes the streams use`() {
        val shell = ToolCall(
            "c", "run_terminal_cmd", ToolKind.Shell, "completed", "ls", detail = "ls -la",
            result = Json.parseToJsonElement("""{"success":{"exitCode":2,"stdout":"total 8\nREADME.md","stderr":"warn","executionTime":3}}"""),
        )
        val out = ToolOutput.of(shell)
        assertThat(out.input).isEqualTo("ls -la")
        assertThat(out.output).isEqualTo("total 8\nREADME.md\nwarn")
        assertThat(out.exitCode).isEqualTo(2)

        val mcp = ToolCall(
            "c", "mcp", ToolKind.Mcp, "completed", "run-info", server = "cursor-cloud", detail = """{"a":1}""",
            result = Json.parseToJsonElement("""{"resultType":"mcpResult","value":{"selectedTool":"run-info","result":"Cursor Cloud MCP identity"}}"""),
        )
        assertThat(ToolOutput.of(mcp)).isEqualTo(ToolOutput("""{"a":1}""", "Cursor Cloud MCP identity"))
        val sdkMcp = ToolCall("c", "mcp", ToolKind.Mcp, "completed", "t", result = Json.parseToJsonElement("""{"content":[{"text":{"text":"one"}},{"text":{"text":"two"}}]}"""))
        assertThat(ToolOutput.of(sdkMcp).output).isEqualTo("one\ntwo")

        val read = ToolCall("c", "read_file", ToolKind.Read, "completed", "A.kt", detail = "app/A.kt", result = buildJsonObject { })
        assertThat(ToolOutput.of(read)).isEqualTo(ToolOutput("app/A.kt", null))
        assertThat(ToolOutput.of(ToolCall("c", "grep", ToolKind.Grep, "completed", "")).isEmpty).isTrue()

        val long = (1..60).joinToString("\n") { "line $it" }
        val clipped = ToolOutput.of(ToolCall("c", "shell", ToolKind.Shell, "completed", "x", result = Json.parseToJsonElement("""{"stdout":${Json.encodeToString(kotlinx.serialization.serializer<String>(), long)}}""")))
        assertThat(clipped.output!!.lines()).hasSize(ToolOutput.MAX_OUTPUT_LINES + 1)
        assertThat(clipped.output).endsWith("… 20 more lines")
    }
}
