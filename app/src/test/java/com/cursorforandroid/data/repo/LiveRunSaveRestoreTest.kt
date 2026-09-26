package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A turn under way that leaves the live-run table is parked as its accumulator's saved state and resumed from the
 * stream position it had reached (see `LiveRunHub.park`). Cut anywhere — mid-thought, mid-reply, between a call's
 * start and its end, after a to-do list — the saved state, through disk and back, plus the events after the cut is the
 * same story as the whole run applied without a break.
 */
class LiveRunSaveRestoreTest {

    private var clock = 1_800_000_000_000L

    private fun live() = TimelineBuilder.LiveRun("run-park", timed = true, startedAtMillis = 1_800_000_000_000L, nowProvider = { clock })

    private fun call(id: String, name: String, status: String, args: Map<String, String> = emptyMap(), output: String? = null) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = id,
            name = name,
            status = status,
            args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            result = output?.let { buildJsonObject { put("output", it); put("exitCode", 0) } },
        ),
    )

    private fun todos(id: String, status: String, vararg items: Pair<String, String>) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = id,
            name = "todo_write",
            status = status,
            args = buildJsonObject {
                put("todos", JsonArray(items.map { (todo, state) -> buildJsonObject { put("id", todo); put("content", "Do $todo"); put("status", state) } }))
            },
        ),
    )

    private val events: List<RunStreamEvent> = listOf(
        RunStreamEvent.Status("run-park", RunStatus.RUNNING),
        RunStreamEvent.Thinking("Looking at "),
        RunStreamEvent.Thinking("the build first."),
        todos("t1", "completed", "a" to "pending", "b" to "pending"),
        call("c1", "shell", "running", mapOf("command" to "./gradlew test")),
        RunStreamEvent.Thinking("While that runs, "),
        call("c1", "shell", "completed", mapOf("command" to "./gradlew test"), output = "BUILD SUCCESSFUL"),
        RunStreamEvent.Assistant("The tests "),
        RunStreamEvent.Assistant("pass. "),
        RunStreamEvent.Assistant("Now the docs."),
        todos("t2", "completed", "a" to "completed", "b" to "in_progress"),
        call("c2", "read", "running", mapOf("path" to "README.md")),
        RunStreamEvent.Error("stream_unavailable", "The machine went to sleep.", resumeFrom = "e12"),
        call("c2", "read", "completed", mapOf("path" to "README.md")),
        RunStreamEvent.Thinking("The README is out of date."),
        RunStreamEvent.Assistant("Updated the README."),
        RunStreamEvent.Result("run-park", RunStatus.FINISHED, "The tests pass. Now the docs.Updated the README.", durationMs = 61_000L, git = null),
    )

    private fun applyTimed(live: TimelineBuilder.LiveRun, events: List<RunStreamEvent>) = events.forEach { clock += 1_500L; live.apply(it) }

    @Test
    fun `a turn saved at any event and resumed after it tells the same story as one never interrupted`() {
        clock = 1_800_000_000_000L
        val whole = live().also { applyTimed(it, events) }
        val expected = whole.snapshot()
        for (cut in 0..events.size) {
            clock = 1_800_000_000_000L
            val before = live().also { applyTimed(it, events.take(cut)) }
            val onDisk = CursorJson.encodeToString(TimelineBuilder.LiveRun.Saved.serializer(), before.save())
            val resumed = live().also { it.restore(CursorJson.decodeFromString(TimelineBuilder.LiveRun.Saved.serializer(), onDisk)) }
            assertThat(resumed.applied).isEqualTo(before.applied)
            applyTimed(resumed, events.drop(cut))
            assertWithMessage("cut after event $cut").that(resumed.snapshot()).isEqualTo(expected)
            assertThat(resumed.status).isEqualTo(whole.status)
            assertThat(resumed.finished).isTrue()
        }
    }

    @Test
    fun `restoring into an accumulator already in use is refused`() {
        val used = live().also { it.apply(RunStreamEvent.Assistant("Hello")) }
        val saved = live().also { it.apply(RunStreamEvent.Assistant("Other")) }.save()
        val failure = runCatching { used.restore(saved) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }
}
