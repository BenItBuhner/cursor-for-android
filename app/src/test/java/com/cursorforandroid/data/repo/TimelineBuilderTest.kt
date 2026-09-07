package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.ToolCall
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
    fun `history attaches on-device images to the user message that started their run`() {
        val messages = listOf(
            V0ConversationMessageDto("m1", "user_message", "Add a README"),
            V0ConversationMessageDto("m2", "assistant_message", "Done."),
            V0ConversationMessageDto("m3", "user_message", "Fix the layout in these screenshots"),
            V0ConversationMessageDto("m4", "assistant_message", "Looking at those screenshots."),
        )
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z"), run("run-2", "2026-04-13T18:50:00.000Z"))
        val shots = listOf(MessageAttachment("/data/attachments/bc-1/run-2/0.jpg", 720, 1600), MessageAttachment("/data/attachments/bc-1/run-2/1.jpg", 1600, 900))
        val items = TimelineBuilder.fromHistory(messages, runs, attachments = mapOf("run-2" to shots), nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        val prompts = items.filterIsInstance<UserMessage>()
        assertThat(prompts.map { it.text }).containsExactly("Add a README", "Fix the layout in these screenshots").inOrder()
        assertThat(prompts[0].attachments).isEmpty()
        assertThat(prompts[1].attachments).isEqualTo(shots)
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

    /** What a replayed (or live) stream of `runId` produces: thinking, one tool batch, the reply and the footer. */
    private fun trace(runId: String, reply: String, finished: Boolean = true): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun(runId, timed = false)
        live.apply(RunStreamEvent.Thinking("Looking around."))
        live.apply(tool("$runId-c1", "read_file", "completed", "path" to "README.md"))
        live.apply(tool("$runId-c2", "grep", "completed", "pattern" to "TODO"))
        live.apply(RunStreamEvent.Assistant(reply))
        if (finished) live.apply(RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 42_000, null))
        return live.snapshot()
    }

    @Test
    fun `a run's trace stands in for its transcript replies and footer`() {
        val messages = listOf(
            V0ConversationMessageDto("m1", "user_message", "Add a README"),
            V0ConversationMessageDto("m2", "assistant_message", "Working on it."),
            V0ConversationMessageDto("m3", "assistant_message", "Done, README added."),
            V0ConversationMessageDto("m4", "user_message", "Add troubleshooting"),
            V0ConversationMessageDto("m5", "assistant_message", "Added a troubleshooting section."),
            V0ConversationMessageDto("m6", "user_message", "And a FAQ"),
        )
        val runs = listOf(
            run("run-3", "2026-04-13T19:10:00.000Z", status = "RUNNING", duration = null),
            run("run-2", "2026-04-13T18:50:00.000Z"),
            run("run-1", "2026-04-13T18:30:00.000Z", branch = "cursor/readme"),
        )
        // run-1 replayed from its retained stream; run-2 expired (text only); run-3 is live and still streaming.
        val traces = mapOf(
            "run-1" to trace("run-1", "Done, README added."),
            "run-3" to trace("run-3", "Drafting the FAQ", finished = false),
        )
        val items = TimelineBuilder.fromHistory(messages, runs, traces, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        assertThat(items.map { it::class.simpleName }).containsExactly(
            "DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage",
        ).inOrder()
        // The transcript's copies of run-1's replies are gone; the trace's own text and footer took their place.
        assertThat(items.none { it.id == "m2" || it.id == "m3" || it.id == "run-run-1" }).isTrue()
        assertThat((items[4] as AssistantMessage).markdown).isEqualTo("Done, README added.")
        assertThat((items[5] as RunFooter).runId).isEqualTo("run-1")
        assertThat((items[5] as RunFooter).durationMs).isEqualTo(42_000L)
        // The expired run keeps the transcript text and a footer built from the run record.
        assertThat(items[8].id).isEqualTo("m5")
        assertThat((items[9] as RunFooter).durationMs).isEqualTo(185_000L)
        // The live run has no footer yet, and its tools sit right after its prompt rather than after the older runs.
        assertThat((items[11] as UserMessage).text).isEqualTo("And a FAQ")
        assertThat((items[13] as ToolActivity).calls.map { it.callId }).containsExactly("run-3-c1", "run-3-c2").inOrder()
    }

    @Test
    fun `a trace also replaces the result fallback of runs missing from the transcript`() {
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z", result = "Final answer"))
        val traces = mapOf("run-1" to trace("run-1", "Final answer"))
        val items = TimelineBuilder.fromHistory(emptyList(), runs, traces, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        assertThat(items.map { it::class.simpleName }).containsExactly("DateHeader", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat(items.none { it.id == "res-run-1" }).isTrue()
    }

    @Test
    fun `an untimed accumulator leaves thinking durations out`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1", timed = false) { clock }
        live.apply(RunStreamEvent.Thinking("Replayed thought."))
        clock += 30_000
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        val thinking = live.snapshot().filterIsInstance<ThinkingBlock>().single()
        assertThat(thinking.isStreaming).isFalse()
        assertThat(thinking.durationSeconds).isNull()
    }

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

    @Test
    fun `a stream error is not part of the run's story and the story continues after it`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1") { clock }
        live.apply(RunStreamEvent.Thinking("Reading the code"))
        live.apply(RunStreamEvent.Error("upstream_error", "Run stream failed", resumeFrom = "1-0"))
        // Nothing was added, and the thought is still open: the run is still thinking while the connection returns.
        assertThat(live.snapshot().map { it::class.simpleName }).containsExactly("ThinkingBlock")
        assertThat(live.snapshot().filterIsInstance<ThinkingBlock>().single().isStreaming).isTrue()
        assertThat(live.applied).isEqualTo(1)

        clock += 5_000
        live.apply(RunStreamEvent.Thinking(" first."))
        live.apply(RunStreamEvent.Assistant("Done."))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", 5_000, null))
        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ThinkingBlock", "AssistantMessage", "RunFooter").inOrder()
        assertThat((items[0] as ThinkingBlock).text).isEqualTo("Reading the code first.")
        assertThat(items.none { it is NoticeCard }).isTrue()
    }

    @Test
    fun `a terminal result settles tool calls and subagents the stream never closed`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(tool("c2", "run_terminal_cmd", "running", "command" to "./gradlew test"))
        live.apply(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey"))
        // The `completed` events were lost with the connection; the run record says the run finished.
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "All green.", 30_000, null))
        val tools = live.snapshot().filterIsInstance<ToolActivity>().single()
        assertThat(tools.isRunning).isFalse()
        assertThat(tools.calls.map { it.status }).containsExactly("completed", "completed").inOrder()
        assertThat(tools.verb).isEqualTo("Explored")
        assertThat(live.snapshot().filterIsInstance<SubagentsCard>().single().subagents.single().status).isEqualTo("Done")

        // A run that did not finish interrupted whatever was still running.
        val cut = TimelineBuilder.LiveRun("run-2", timed = false)
        cut.apply(tool("c1", "run_terminal_cmd", "running", "command" to "sleep 100"))
        cut.apply(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey"))
        cut.apply(RunStreamEvent.Result("run-2", RunStatus.CANCELLED, null, 3_000, null))
        val interrupted = cut.snapshot().filterIsInstance<ToolActivity>().single().calls.single()
        assertThat(interrupted.isRunning).isFalse()
        assertThat(interrupted.status).isEqualTo(ToolCall.STATUS_INTERRUPTED)
        assertThat(cut.snapshot().filterIsInstance<SubagentsCard>().single().subagents.single().status).isEqualTo("Stopped")
        assertThat(cut.snapshot().filterIsInstance<NoticeCard>().single().title).isEqualTo("Run cancelled")
    }

    @Test
    fun `a run that fails without a final text is explained by the stream's last error`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Assistant("Starting on it."))
        live.apply(RunStreamEvent.Error("upstream_error", "Worker disconnected", resumeFrom = "3-0"))
        // The record has no reason of its own (`result` is null for failed runs).
        live.apply(RunStreamEvent.Result("run-1", RunStatus.ERROR, null, 9_000, null))
        val notice = live.snapshot().filterIsInstance<NoticeCard>().single()
        assertThat(notice.title).isEqualTo("Run failed")
        assertThat(notice.subtitle).isEqualTo("Worker disconnected")
        assertThat((live.snapshot().last() as RunFooter).status).isEqualTo(RunStatus.ERROR)

        // An expired log says nothing about why the run failed.
        val expired = TimelineBuilder.LiveRun("run-2", timed = false)
        expired.apply(RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        expired.apply(RunStreamEvent.Result("run-2", RunStatus.ERROR, null, null, null))
        assertThat(expired.snapshot().filterIsInstance<NoticeCard>().single().subtitle).isNull()
    }
}
