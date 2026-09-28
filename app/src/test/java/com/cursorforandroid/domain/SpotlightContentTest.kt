package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.repo.TimelineBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** What the Spotlight notification says about a chat's run and about a Project. */
class SpotlightContentTest {

    private fun tool(id: String, name: String, status: String, vararg args: Pair<String, String>, result: JsonElement? = null) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, result = result),
    )

    private fun diff(added: Int, removed: Int) = buildJsonObject {
        put("success", buildJsonObject { put("linesAdded", JsonPrimitive(added)); put("linesRemoved", JsonPrimitive(removed)) })
    }

    private fun run(vararg events: RunStreamEvent): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        events.forEach { live.apply(it) }
        return live.snapshot()
    }

    private fun task(id: String, status: String, description: String) = tool(id, "task", status, "subagent_type" to "explore", "description" to description)

    @Test
    fun `a run that has not said anything yet is starting, with nothing under it`() {
        val view = SpotlightContent.ofRun("a1", "Fix login", emptyList(), startedAtMillis = 5L)
        assertThat(view.title).isEqualTo("Fix login")
        assertThat(view.step).isEqualTo("Starting\u2026")
        assertThat(view.lines).isEmpty()
        assertThat(view.tally).isNull()
        assertThat(view.body).isNull()
        assertThat(view.startedAtMillis).isEqualTo(5L)
    }

    @Test
    fun `the step is the tool call in progress, and the tally counts what the run has done`() {
        val items = run(
            tool("r1", "read_file", "completed", "path" to "app/Login.kt"),
            tool("e1", "edit_file", "completed", "path" to "app/Login.kt", result = diff(80, 12)),
            tool("e2", "edit_file", "running", "path" to "app/Session.kt"),
        )
        val view = SpotlightContent.ofRun("a1", "Fix login", items, startedAtMillis = 0L)
        assertThat(view.step).isEqualTo("Editing Session.kt")
        assertThat(view.lines).isEmpty()
        assertThat(view.tally).isEqualTo("3 tool calls \u00B7 +80 \u221212 \u00B7 2 Files")
        assertThat(view.body).isEqualTo("Editing Session.kt\n3 tool calls \u00B7 +80 \u221212 \u00B7 2 Files")
    }

    @Test
    fun `running subagents are listed under the step, finished ones drop off, and past three the rest are counted`() {
        val items = run(
            task("s1", "running", "Survey cloud sync layer"),
            task("s2", "running", "Survey first-party agent and inference"),
            task("s3", "running", "Survey distribution"),
            task("s4", "running", "Audit the settings screen"),
            task("s5", "running", "Done already"),
            task("s5", "completed", "Done already"),
        )
        val view = SpotlightContent.ofRun("a1", "Map the codebase", items, startedAtMillis = 0L)
        assertThat(view.lines).containsExactly(
            "\u2022 Survey cloud sync layer",
            "\u2022 Survey first-party agent and inference",
            "\u2022 Survey distribution",
            "\u2022 +1 more",
        ).inOrder()
        assertThat(view.tally).isEqualTo("5 tool calls")
    }

    @Test
    fun `a dropped stream says so instead of a stale step`() {
        val items = run(tool("e1", "edit_file", "running", "path" to "app/Login.kt"))
        assertThat(SpotlightContent.ofRun("a1", "Fix login", items, 0L, reconnecting = true).step).isEqualTo(SpotlightContent.RECONNECTING)
    }

    @Test
    fun `a Project leads with its coordinator's step and gives each running chat a line`() {
        val coordinator = SpotlightContent.Member("p1", "Launch", run(task("s1", "running", "Delegate")), startedAtMillis = 100L)
        val workers = listOf(
            SpotlightContent.Member("w1", "Onboarding flow", run(tool("e1", "edit_file", "running", "path" to "ui/Welcome.kt")), startedAtMillis = 50L),
            SpotlightContent.Member("w2", "Pricing page", null, startedAtMillis = null),
        )
        val view = SpotlightContent.ofProject("p1", "Launch", coordinator, workers, runningCount = 2, chatCount = 5, fallbackStartedAtMillis = 999L)
        assertThat(view.isProject).isTrue()
        assertThat(view.lines).containsExactly(
            "\u2022 Onboarding flow \u00B7 Editing Welcome.kt",
            "\u2022 Pricing page \u00B7 Starting\u2026",
        ).inOrder()
        assertThat(view.tally).isEqualTo("2 of 5 chats running")
        // The chronometer counts from the earliest run it covers.
        assertThat(view.startedAtMillis).isEqualTo(50L)
    }

    @Test
    fun `a Project whose coordinator is idle counts its running chats in the headline`() {
        val workers = (1..5).map { SpotlightContent.Member("w$it", "Worker $it", null, null) }
        val view = SpotlightContent.ofProject("p1", "Launch", coordinator = null, running = workers.take(3), runningCount = 5, chatCount = 6, fallbackStartedAtMillis = 999L)
        assertThat(view.step).isEqualTo("5 chats running")
        assertThat(view.lines.last()).isEqualTo("\u2022 +2 more")
        assertThat(view.tally).isNull()
        assertThat(view.startedAtMillis).isEqualTo(999L)
    }

    @Test
    fun `a Project with nothing running is waiting`() {
        val view = SpotlightContent.ofProject("p1", "Launch", null, emptyList(), runningCount = 0, chatCount = 3, fallbackStartedAtMillis = 1L)
        assertThat(view.step).isEqualTo(SpotlightContent.WAITING)
        assertThat(view.body).isNull()
    }
}
