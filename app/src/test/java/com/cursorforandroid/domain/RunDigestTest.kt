package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.repo.TimelineBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class RunDigestTest {

    private fun tool(id: String, name: String, status: String, path: String, result: JsonElement? = null) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }, result = result),
    )

    private fun diff(added: Int, removed: Int) = buildJsonObject {
        put("success", buildJsonObject { put("linesAdded", JsonPrimitive(added)); put("linesRemoved", JsonPrimitive(removed)) })
    }

    @Test
    fun `activity follows the latest timeline item`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        assertThat(RunDigest.from(live.snapshot()).activity).isEqualTo(RunDigest.Activity.Starting)

        live.apply(RunStreamEvent.Thinking("Let me look"))
        assertThat(RunDigest.from(live.snapshot()).activity).isEqualTo(RunDigest.Activity.Thinking)

        live.apply(tool("t1", "read_file", "running", "app/src/main/Composer.kt"))
        assertThat(RunDigest.from(live.snapshot()).activity.label).isEqualTo("Reading Composer.kt")

        live.apply(tool("t1", "read_file", "completed", "app/src/main/Composer.kt"))
        assertThat(RunDigest.from(live.snapshot()).activity).isEqualTo(RunDigest.Activity.Working)

        live.apply(tool("t2", "edit_file", "running", "app/src/main/Composer.kt"))
        assertThat(RunDigest.from(live.snapshot()).activity.label).isEqualTo("Editing Composer.kt")

        live.apply(RunStreamEvent.Assistant("Done"))
        assertThat(RunDigest.from(live.snapshot()).activity).isEqualTo(RunDigest.Activity.Writing)

        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done", 5_000, null))
        assertThat(RunDigest.from(live.snapshot()).activity).isEqualTo(RunDigest.Activity.Finishing)
    }

    @Test
    fun `counts distinct files and sums line stats reported by edit tools`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        live.apply(tool("e1", "edit_file", "completed", "a/One.kt", diff(50, 200)))
        live.apply(tool("e2", "edit_file", "completed", "a/One.kt", diff(10, 20)))
        live.apply(tool("e3", "search_replace", "completed", "b/Two.kt", diff(20, 10)))
        live.apply(tool("e4", "write", "completed", "c/Three.kt"))
        live.apply(tool("r1", "read_file", "completed", "a/One.kt"))
        live.apply(tool("s1", "grep", "completed", "pattern"))
        live.apply(tool("c1", "run_terminal_cmd", "completed", "ls"))

        val digest = RunDigest.from(live.snapshot())
        assertThat(ToolNames.kindOf("search_replace")).isEqualTo(ToolKind.Edit)
        assertThat(digest.filesEdited).isEqualTo(3)
        assertThat(digest.filesRead).isEqualTo(1)
        assertThat(digest.searches).isEqualTo(1)
        assertThat(digest.commands).isEqualTo(1)
        assertThat(digest.additions).isEqualTo(80)
        assertThat(digest.deletions).isEqualTo(230)
        assertThat(digest.lineStats()).isEqualTo("+80 \u2212230")
        assertThat(digest.statsLine(185_000)).isEqualTo("+80 \u2212230 \u00B7 3 Files")
    }

    @Test
    fun `stats line falls back to files and duration when no tool reported line counts`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        live.apply(tool("e1", "edit_file", "completed", "a/One.kt"))
        val digest = RunDigest.from(live.snapshot())
        assertThat(digest.hasLineStats).isFalse()
        assertThat(digest.statsLine(185_000)).isEqualTo("1 File \u00B7 Worked 3m 5s")
        assertThat(RunDigest().statsLine(185_000)).isEqualTo("Worked 3m 5s")
        assertThat(RunDigest().statsLine(null)).isNull()
    }

    @Test
    fun `diff stats accept common key spellings and ignore unrelated numbers`() {
        assertThat(DiffStats.fromToolResult(buildJsonObject { put("additions", JsonPrimitive(3)); put("deletions", JsonPrimitive(1)) })).isEqualTo(DiffStats(3, 1))
        assertThat(DiffStats.fromToolResult(buildJsonObject { put("insertions", JsonPrimitive(7)) })).isEqualTo(DiffStats(7, 0))
        assertThat(DiffStats.fromToolResult(buildJsonObject { put("totalLines", JsonPrimitive(99)) })).isNull()
        assertThat(DiffStats.fromToolResult(null)).isNull()
        assertThat(DiffStats.fromToolResult(JsonPrimitive("text"))).isNull()
    }

    @Test
    fun `subagent cards describe delegation while any child is running`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        val sub = { id: String, status: String ->
            RunStreamEvent.ToolCall(SseToolCallDto(id, "task", status, buildJsonObject { put("subagent_type", JsonPrimitive("explore")); put("description", JsonPrimitive("Survey")) }))
        }
        live.apply(sub("s1", "running"))
        live.apply(sub("s2", "running"))
        assertThat(RunDigest.from(live.snapshot()).activity.label).isEqualTo("Delegating to 2 subagents")
        live.apply(sub("s1", "completed"))
        live.apply(sub("s2", "completed"))
        val digest = RunDigest.from(live.snapshot())
        assertThat(digest.activity).isEqualTo(RunDigest.Activity.Working)
        assertThat(digest.subagents).isEqualTo(2)
    }
}
