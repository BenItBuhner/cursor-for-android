package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.local.CachedTrace
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the panel reads off a transcript, and that every payload survives the trace file. */
class TranscriptContentTest {

    private fun call(id: String, name: String, kind: ToolKind, detail: String? = null, payload: ToolPayload? = null, status: String = ToolCall.STATUS_COMPLETED) =
        ToolCall(callId = id, name = name, kind = kind, status = status, summary = detail?.let(ToolNames::basename).orEmpty(), detail = detail, payload = payload)

    private val diff1 = ToolPayload.FileDiff("app/Main.kt", "@@ -1 +1 @@\n-a\n+b", linesAdded = 1, linesRemoved = 1)
    private val diff2 = ToolPayload.FileDiff("app/Main.kt", "@@ -5 +5,2 @@\n+c\n+d", linesAdded = 2, linesRemoved = 0)
    private val written = ToolPayload.FileContent("notes.md", "# Notes", ToolPayload.FileContent.Kind.Written, totalLines = 1)
    private val read = ToolPayload.FileContent("README.md", "# Project", ToolPayload.FileContent.Kind.Read, totalLines = 1)
    private val image = ToolPayload.GeneratedImage("/workspace/cat.png", "a cat", src = "file:///data/generated/bc-1/i1.png")
    private val recording = ToolPayload.Recording("/opt/cursor/artifacts/demo.mp4", 4_000)
    private val subagent = ToolPayload.Subagent("Audit the tests", agentId = "bc-sub", transcriptPath = "/t/x.json")
    private val question = ToolPayload.Question(questions = listOf(ToolPayload.Question.Item("q1", "Ship it?", listOf(ToolPayload.Question.Option("y", "Yes")))))

    private val items: List<TimelineItem> = listOf(
        UserMessage("u1", "Do the thing"),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Looking around."),
                call("r1", "read_file", ToolKind.Read, "README.md", read),
                call("e1", "edit_file", ToolKind.Edit, "app/Main.kt", diff1),
                call("e2", "edit_file", ToolKind.Edit, "app/Main.kt", diff2),
                call("w1", "write", ToolKind.Create, "notes.md", written),
                call("d1", "delete_file", ToolKind.Delete, "old.txt"),
                call("i1", "generate_image", ToolKind.Image, payload = image),
                call("v1", "record_screen", ToolKind.Other, payload = recording),
                call("t1", "task", ToolKind.Task, payload = subagent),
                // A failed edit touched nothing.
                call("e3", "edit_file", ToolKind.Edit, "broken.kt").copy(isError = true),
            ),
        ),
        AssistantMessage("a1", "Partway."),
        ActivityGroup("g2", listOf(call("q1", "ask_question", ToolKind.Question, payload = question, status = ToolCall.STATUS_RUNNING))),
    )

    @Test
    fun `files, changes, media, subagents and the pending question are read off the timeline`() {
        val content = TranscriptContent.of(items)

        assertThat(content.touched.map { it.path }).containsExactly("README.md", "app/Main.kt", "notes.md", "old.txt").inOrder()
        assertThat(content.touched.first { it.path == "README.md" }.kinds).containsExactly(TranscriptContent.Touch.Read)
        assertThat(content.touched.first { it.path == "app/Main.kt" }.wasChanged).isTrue()

        val changes = content.changes
        assertThat(changes.map { it.path }).containsExactly("app/Main.kt", "notes.md", "old.txt").inOrder()
        val main = changes[0]
        assertThat(main.diffs).containsExactly(diff1, diff2).inOrder()
        assertThat(main.lineStats).isEqualTo("+3 -1")
        assertThat(main.touch).isEqualTo(TranscriptContent.Touch.Edited)
        assertThat(main.unifiedDiff).isEqualTo("@@ -1 +1 @@\n-a\n+b\n@@ -5 +5,2 @@\n+c\n+d")
        assertThat(changes[1].touch).isEqualTo(TranscriptContent.Touch.Created)
        assertThat(changes[1].contentAfter).isEqualTo(written)
        assertThat(changes[2].touch).isEqualTo(TranscriptContent.Touch.Deleted)
        assertThat(changes[2].diffs).isEmpty()

        assertThat(content.media).containsExactly(TranscriptContent.MediaItem.Image("i1", image), TranscriptContent.MediaItem.Recording("v1", recording)).inOrder()
        assertThat(content.subagents).containsExactly(subagent)
        assertThat(content.pendingQuestion).isEqualTo(question)
        assertThat(content.isEmpty).isFalse()
    }

    @Test
    fun `a transcript without tool calls has nothing`() {
        assertThat(TranscriptContent.of(listOf(UserMessage("u", "hi"), AssistantMessage("a", "hello")))).isEqualTo(TranscriptContent.EMPTY)
        assertThat(TranscriptContent.EMPTY.isEmpty).isTrue()
    }

    @Test
    fun `a diff does not count as a change of a file whose edit failed`() {
        val content = TranscriptContent.of(listOf(ActivityGroup("g", listOf(call("e", "edit_file", ToolKind.Edit, "x.kt").copy(isError = true)))))
        assertThat(content.changes).isEmpty()
        assertThat(content.touched).isEmpty()
    }

    @Test
    fun `every payload round-trips through the trace file's format`() {
        val trace = CachedTrace("run-1", 1_000L, items)
        val text = CursorJson.encodeToString(CachedTrace.serializer(), trace)
        val back = CursorJson.decodeFromString(CachedTrace.serializer(), text)
        assertThat(back).isEqualTo(trace)
        assertThat(TranscriptContent.of(back.items)).isEqualTo(TranscriptContent.of(items))
    }

    @Test
    fun `a trace written before payloads existed still reads`() {
        val legacy = """{"runId":"run-1","createdAtMillis":1,"items":[{"type":"activity","id":"g","steps":[{"type":"tool","callId":"c","name":"read_file","kind":"Read","status":"completed","summary":"README.md","detail":"README.md"}]}]}"""
        val back = CursorJson.decodeFromString(CachedTrace.serializer(), legacy)
        val call = (back.items.single() as ActivityGroup).calls.single()
        assertThat(call.payload).isNull()
        assertThat(call.truncated).isNull()
        assertThat(TranscriptContent.of(back.items).touched.single().path).isEqualTo("README.md")
    }

    @Test
    fun `a local file is a media reference of its own`() {
        val local = MediaRef.parse("file:///data/user/0/app/files/generated/bc-1/i1.png", agentId = "bc-1")
        assertThat(local).isEqualTo(MediaRef.Local("/data/user/0/app/files/generated/bc-1/i1.png"))
        assertThat(local.label).isEqualTo("i1.png")
        // `java.io.File.toURI()` spells it with one slash.
        assertThat(MediaRef.parse("file:/tmp/generated/i2.png", agentId = null)).isEqualTo(MediaRef.Local("/tmp/generated/i2.png"))
        assertThat(MediaRef.parse("file://", agentId = null)).isInstanceOf(MediaRef.Unavailable::class.java)
    }
}
