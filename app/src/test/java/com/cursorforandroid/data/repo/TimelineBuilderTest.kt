package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.time.ZoneOffset

class TimelineBuilderTest {

    private fun run(id: String, createdAt: String, status: String = "FINISHED", duration: Long? = 185_000, result: String? = null, branch: String? = null) = RunDto(
        id = id, agentId = "bc-1", status = status, createdAt = createdAt, updatedAt = createdAt, durationMs = duration, result = result,
        git = branch?.let { RunGitDto(listOf(RunGitBranchDto("github.com/o/r", it, null))) },
    )

    @Test
    fun `history interleaves transcript with run footers`() {
        val messages = listOf(
            V0ConversationMessageDto("m1", "user_message", "Add a README"),
            V0ConversationMessageDto("m2", "assistant_message", "Working on it."),
            V0ConversationMessageDto("m3", "assistant_message", "Done, README added."),
            V0ConversationMessageDto("m4", "user_message", "Add troubleshooting"),
            V0ConversationMessageDto("m5", "assistant_message", "Added a troubleshooting section."),
        )
        val runs = listOf(
            run("run-2", "2026-04-13T18:50:00.000Z", status = "RUNNING", duration = null),
            run("run-1", "2026-04-13T18:30:00.000Z", branch = "cursor/readme"),
        )
        val items = TimelineBuilder.fromHistory(messages, runs, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        val types = items.map { it::class.simpleName }
        assertThat(types).containsExactly(
            "DateHeader", "UserMessage", "AssistantMessage", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "AssistantMessage",
        ).inOrder()
        val footer = items.filterIsInstance<RunFooter>().single()
        assertThat(footer.runId).isEqualTo("run-1")
        assertThat(footer.durationMs).isEqualTo(185_000L)
        assertThat(footer.branches.single().branch).isEqualTo("cursor/readme")
        assertThat((items[1] as UserMessage).text).isEqualTo("Add a README")
        assertThat((items[0] as DateHeader).label).contains("at")
    }

    @Test
    fun `history never repeats an id even when the transcript does`() {
        val messages = listOf(
            V0ConversationMessageDto("m1", "user_message", "Add a README"),
            V0ConversationMessageDto("m2", "assistant_message", "Working on it."),
            V0ConversationMessageDto("m2", "assistant_message", "Done."),
            V0ConversationMessageDto("m1", "user_message", "Again"),
        )
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z"), run("run-1", "2026-04-13T18:50:00.000Z"))
        val items = TimelineBuilder.fromHistory(messages, runs, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        assertThat(items.map { it.id }).containsNoDuplicates()
        assertThat(items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Working on it.", "Done.").inOrder()
        assertThat(items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Add a README", "Again").inOrder()
    }

    @Test
    fun `history without transcript falls back to run results`() {
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z", result = "Final answer"))
        val items = TimelineBuilder.fromHistory(emptyList(), runs, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        assertThat(items.map { it::class.simpleName }).containsExactly("DateHeader", "AssistantMessage", "RunFooter").inOrder()
        assertThat((items[1] as AssistantMessage).markdown).isEqualTo("Final answer")
    }

    private fun tool(id: String, name: String, status: String, vararg args: Pair<String, String>) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }),
    )

    @Test
    fun `live run coalesces deltas, groups tools, tracks subagents and finishes with a footer`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-9") { clock }
        live.apply(RunStreamEvent.Status("run-9", RunStatus.RUNNING))
        live.apply(RunStreamEvent.Thinking("Let me look "))
        live.apply(RunStreamEvent.Thinking("at the repo."))
        clock += 3_000
        live.apply(tool("c1", "read_file", "running", "path" to "README.md"))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(tool("c2", "grep", "completed", "pattern" to "TODO"))
        live.apply(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey cloud layer"))
        live.apply(RunStreamEvent.Assistant("Hello "))
        live.apply(RunStreamEvent.Assistant("world."))
        live.apply(tool("s1", "task", "completed", "subagent_type" to "explore", "description" to "Survey cloud layer"))
        live.apply(RunStreamEvent.Result("run-9", RunStatus.FINISHED, "Hello world.", 42_000, RunGitDto(listOf(RunGitBranchDto("github.com/o/r", "cursor/x", null)))))

        val items = live.snapshot()
        val thinking = items.filterIsInstance<ThinkingBlock>().single()
        assertThat(thinking.text).isEqualTo("Let me look at the repo.")
        assertThat(thinking.isStreaming).isFalse()
        assertThat(thinking.durationSeconds).isEqualTo(3L)

        val tools = items.filterIsInstance<ToolActivity>().single()
        assertThat(tools.calls.map { it.callId }).containsExactly("c1", "c2").inOrder()
        assertThat(tools.calls.first().status).isEqualTo("completed")
        assertThat(tools.fileCount).isEqualTo(1)
        assertThat(tools.searchCount).isEqualTo(1)
        assertThat(tools.headline).isEqualTo("1 file, 1 search")

        val subs = items.filterIsInstance<SubagentsCard>().single()
        assertThat(subs.subagents.single().status).isEqualTo("Done")
        assertThat(subs.subagents.single().kind).isEqualTo("Explorer")

        val assistant = items.filterIsInstance<AssistantMessage>().single()
        assertThat(assistant.markdown).isEqualTo("Hello world.")
        assertThat(assistant.isStreaming).isFalse()

        val footer = items.last() as RunFooter
        assertThat(footer.status).isEqualTo(RunStatus.FINISHED)
        assertThat(footer.durationMs).isEqualTo(42_000L)
        assertThat(live.finished).isTrue()
    }

    @Test
    fun `result text is used when no assistant deltas arrived`() {
        val live = TimelineBuilder.LiveRun("run-1")
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Only the summary.", 1000, null))
        val items = live.snapshot()
        assertThat(items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Only the summary.")
        assertThat(items.last()).isInstanceOf(RunFooter::class.java)
    }
}
