package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseInteractionToolCallDto
import com.cursorforandroid.data.api.dto.SseInteractionUpdateDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.SseToolCallTruncationDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

/**
 * How the SDK-shape `interaction_update` joins the simplified `tool_call` event for the same call: whichever comes
 * first, the transcript ends up with one call carrying the wording of the simplified event and the payload of the
 * typed one.
 */
class TimelineBuilderInteractionTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun tool(id: String, name: String, status: String, args: String? = null, result: String? = null, truncated: SseToolCallTruncationDto? = null) =
        RunStreamEvent.ToolCall(SseToolCallDto(callId = id, name = name, status = status, args = args?.let(::json), result = result?.let(::json), truncated = truncated))

    private fun interaction(id: String, type: String, completed: Boolean, args: String? = null, result: String? = null) = RunStreamEvent.Interaction(
        SseInteractionUpdateDto(
            type = if (completed) SseInteractionUpdateDto.TOOL_CALL_COMPLETED else SseInteractionUpdateDto.TOOL_CALL_STARTED,
            callId = id,
            toolCall = SseInteractionToolCallDto(type, args?.let(::json), result?.let(::json)),
        ),
    )

    private val editArgs = """{"path":"app/src/Main.kt"}"""
    private val editValue = """{"status":"success","value":{"linesAdded":2,"linesRemoved":1,"diffString":"@@ -1 +1,2 @@\n-a\n+b\n+c"}}"""

    private fun TimelineBuilder.LiveRun.calls(): List<ToolCall> = snapshot().filterIsInstance<ActivityGroup>().flatMap { it.calls }

    @Test
    fun `an update after the simplified event enriches the same call`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("c1", "edit_file", "running", editArgs))
        // The simplified result left the diff out; the typed update carries it.
        live.apply(tool("c1", "edit_file", "completed", editArgs, """{"success":{"linesAdded":2,"linesRemoved":1}}"""))
        live.apply(interaction("c1", "edit", completed = true, args = editArgs, result = editValue))

        val call = live.calls().single()
        assertThat(call.name).isEqualTo("edit_file")
        assertThat(call.action).isEqualTo("Edited")
        assertThat(call.summary).isEqualTo("Main.kt")
        assertThat(call.lineStats).isEqualTo("+2 -1")
        val diff = call.payload as ToolPayload.FileDiff
        assertThat(diff.diff).startsWith("@@ -1 +1,2 @@")
        assertThat(live.applied).isEqualTo(2)
    }

    @Test
    fun `an update before the simplified event stands in for it, then hands over`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(interaction("c1", "edit", completed = false, args = editArgs))
        // Until the simplified event arrives the typed one is the row, worded the same way.
        assertThat(live.calls().single().let { it.action to it.summary }).isEqualTo("Editing" to "Main.kt")

        live.apply(interaction("c1", "edit", completed = true, args = editArgs, result = editValue))
        val standing = live.calls().single()
        assertThat(standing.isRunning).isFalse()
        assertThat(standing.payload).isInstanceOf(ToolPayload.FileDiff::class.java)

        live.apply(tool("c1", "edit_file", "completed", editArgs, """{"success":{"linesAdded":2,"linesRemoved":1}}"""))
        val call = live.calls().single()
        assertThat(call.name).isEqualTo("edit_file")
        assertThat((call.payload as ToolPayload.FileDiff).linesAdded).isEqualTo(2)
        // Interactions are not content events: a reconnect catches up on the simplified ones alone.
        assertThat(live.applied).isEqualTo(1)
    }

    @Test
    fun `a read's text and a write's file come along, and the payload survives the status update`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("r1", "read_file", "running", """{"path":"README.md"}"""))
        live.apply(interaction("r1", "read", completed = true, args = """{"path":"README.md"}""", result = """{"status":"success","value":{"content":"# Project\nline 2","totalLines":2,"fileSize":16}}"""))
        live.apply(tool("r1", "read_file", "completed", """{"path":"README.md"}""", truncated = SseToolCallTruncationDto(result = true)))
        live.apply(tool("w1", "write", "running", """{"path":"notes.md","fileText":"# Draft"}"""))
        live.apply(tool("w1", "write", "completed", """{"path":"notes.md","fileText":"# Draft"}""", """{"success":{"linesCreated":1,"fileSize":7,"fileContentAfterWrite":"# Draft"}}"""))

        val (read, write) = live.calls()
        val readText = read.payload as ToolPayload.FileContent
        assertThat(readText.kind).isEqualTo(ToolPayload.FileContent.Kind.Read)
        assertThat(readText.content).isEqualTo("# Project\nline 2")
        assertThat(read.truncated!!.result).isTrue()
        assertThat(read.touchedPath).isEqualTo("README.md")
        val written = write.payload as ToolPayload.FileContent
        assertThat(written.kind).isEqualTo(ToolPayload.FileContent.Kind.Written)
        assertThat(write.kind).isEqualTo(ToolKind.Create)
        assertThat(write.lineStats).isEqualTo("+1")
    }

    @Test
    fun `a generated image and a recording show up as the group's media`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("i1", "generate_image", "completed", """{"prompt":"a cat"}""", """{"success":{"filePath":"/workspace/cat.png","imageData":"data:image/png;base64,AAAA"}}"""))
        live.apply(tool("v1", "record_screen", "completed", """{"mode":"SAVE_RECORDING"}""", """{"success":{"path":"/opt/cursor/artifacts/demo.mp4","recordingDurationMs":4000}}"""))
        live.apply(tool("s1", "run_terminal_cmd", "completed", """{"command":"ls"}""", """{"success":{"stdout":"a"}}"""))

        val group = live.snapshot().filterIsInstance<ActivityGroup>().single()
        assertThat(group.media.map { it.callId }).containsExactly("i1", "v1").inOrder()
        assertThat((group.media[0].payload as ToolPayload.GeneratedImage).src).isEqualTo("data:image/png;base64,AAAA")
        assertThat((group.media[1].payload as ToolPayload.Recording).path).isEqualTo("/opt/cursor/artifacts/demo.mp4")
        assertThat(group.header.action).isEqualTo("Explored")
    }

    @Test
    fun `a question is pending while its call runs and answered once it completes`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        val args = """{"questions":[{"id":"q1","prompt":"Ship it?","options":[{"id":"y","label":"Yes"},{"id":"n","label":"No"}]}]}"""
        live.apply(tool("q1", "ask_question", "running", args))
        val group = live.snapshot().filterIsInstance<ActivityGroup>().single()
        val pending = group.pendingQuestion!!
        assertThat(pending.pendingQuestion!!.questions.single().prompt).isEqualTo("Ship it?")
        assertThat(group.isRunning).isTrue()

        live.apply(tool("q1", "ask_question", "completed", args, """{"success":{"answers":[{"questionId":"q1","selectedOptionIds":["y"]}]}}"""))
        val done = live.snapshot().filterIsInstance<ActivityGroup>().single()
        assertThat(done.pendingQuestion).isNull()
        val question = done.calls.single().payload as ToolPayload.Question
        assertThat(question.answerFor(question.questions.single())).isEqualTo("Yes")
    }

    @Test
    fun `a run that ends leaves the payloads in place`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(interaction("c1", "edit", completed = true, args = editArgs, result = editValue))
        live.apply(RunStreamEvent.Assistant("Done."))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", 1_000, null))
        val call = live.calls().single()
        assertThat(call.status).isEqualTo(ToolCall.STATUS_COMPLETED)
        assertThat(call.payload).isInstanceOf(ToolPayload.FileDiff::class.java)
    }
}
