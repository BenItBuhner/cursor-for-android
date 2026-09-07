package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentsCard
import com.cursorforandroid.domain.TimelineItem
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
            "DateHeader", "UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "ActivityGroup", "AssistantMessage",
        ).inOrder()
        // The transcript's copies of run-1's replies are gone; the trace's own text and footer took their place.
        assertThat(items.none { it.id == "m2" || it.id == "m3" || it.id == "run-run-1" }).isTrue()
        assertThat((items[3] as AssistantMessage).markdown).isEqualTo("Done, README added.")
        assertThat((items[4] as RunFooter).runId).isEqualTo("run-1")
        assertThat((items[4] as RunFooter).durationMs).isEqualTo(42_000L)
        // The expired run keeps the transcript text and a footer built from the run record.
        assertThat(items[7].id).isEqualTo("m5")
        assertThat((items[8] as RunFooter).durationMs).isEqualTo(185_000L)
        // The live run has no footer yet, and its work sits right after its prompt rather than after the older runs.
        assertThat((items[10] as UserMessage).text).isEqualTo("And a FAQ")
        assertThat((items[11] as ActivityGroup).calls.map { it.callId }).containsExactly("run-3-c1", "run-3-c2").inOrder()
    }

    @Test
    fun `a trace also replaces the result fallback of runs missing from the transcript`() {
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z", result = "Final answer"))
        val traces = mapOf("run-1" to trace("run-1", "Final answer"))
        val items = TimelineBuilder.fromHistory(emptyList(), runs, traces, nowMillis = 1_776_200_000_000L, zone = ZoneOffset.UTC)
        assertThat(items.map { it::class.simpleName }).containsExactly("DateHeader", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(items.none { it.id == "res-run-1" }).isTrue()
    }

    @Test
    fun `an untimed accumulator leaves thinking durations out`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1", timed = false) { clock }
        live.apply(RunStreamEvent.Thinking("Replayed thought."))
        clock += 30_000
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        val work = live.snapshot().filterIsInstance<ActivityGroup>().single()
        val thinking = work.thoughts.single()
        assertThat(thinking.isStreaming).isFalse()
        assertThat(thinking.durationSeconds).isNull()
        // With no time to report, the row is the tool summary alone; the thought is there once the row is opened.
        assertThat(work.detail).isEqualTo("1 file")
        assertThat(work.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall").inOrder()
    }

    private fun TimelineBuilder.LiveRun.group(): ActivityGroup = snapshot().filterIsInstance<ActivityGroup>().single()

    @Test
    fun `reasoning and tool calls between two replies interleave in one group`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1") { clock }
        live.apply(RunStreamEvent.Thinking("Where does the picker live?"))
        clock += 2_000
        live.apply(tool("c1", "grep", "running", "pattern" to "ModelSheet"))
        live.apply(tool("c1", "grep", "completed", "pattern" to "ModelSheet"))
        live.apply(tool("c2", "read_file", "completed", "path" to "ModelSheet.kt"))
        live.apply(RunStreamEvent.Thinking("Found it; now the screen that hosts it."))
        clock += 5_000
        live.apply(tool("c3", "read_file", "completed", "path" to "HomeScreen.kt"))
        live.apply(tool("c4", "edit_file", "completed", "path" to "HomeScreen.kt"))
        live.apply(RunStreamEvent.Assistant("Wired the sheet into HomeScreen."))
        // Work after a reply is a new stretch, with its own row.
        live.apply(RunStreamEvent.Thinking("Any imports left behind?"))
        clock += 1_000
        live.apply(tool("c5", "grep", "completed", "pattern" to "import androidx.compose.material3.TextField"))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Wired the sheet into HomeScreen.", 9_000, null))

        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "ActivityGroup", "RunFooter").inOrder()
        assertThat(items.map { it.id }).containsNoDuplicates()

        val first = items[0] as ActivityGroup
        assertThat(first.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall", "ToolCall", "ThinkingBlock", "ToolCall", "ToolCall").inOrder()
        assertThat(first.thoughts.map { it.text }).containsExactly("Where does the picker live?", "Found it; now the screen that hosts it.").inOrder()
        assertThat(first.thoughts.map { it.durationSeconds }).containsExactly(2L, 5L).inOrder()
        assertThat(first.calls.map { it.callId }).containsExactly("c1", "c2", "c3", "c4").inOrder()
        assertThat(first.calls.map { it.status }).doesNotContain("running")
        assertThat(first.isBusy).isFalse()
        assertThat(first.verb).isEqualTo("Explored")
        assertThat(first.detail).isEqualTo("3 files, 1 search · thought for 7s")

        val second = items[2] as ActivityGroup
        assertThat(second.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall").inOrder()
        assertThat(second.detail).isEqualTo("1 search · thought for 1s")
    }

    @Test
    fun `the row narrates the step in progress and settles into a summary once the stretch is over`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1") { clock }

        live.apply(RunStreamEvent.Thinking("Let me see."))
        with(live.group()) {
            assertThat(verb).isEqualTo("Thinking")
            assertThat(detail).isNull()
            assertThat(isThinking).isTrue()
            assertThat(isBusy).isTrue()
        }

        clock += 3_000
        live.apply(tool("c1", "read_file", "running", "path" to "README.md"))
        with(live.group()) {
            assertThat(verb).isEqualTo("Exploring")
            // The thinking time waits for the row to go quiet, so the counts hold still while the agent works.
            assertThat(detail).isEqualTo("1 file")
            assertThat(runningCall?.callId).isEqualTo("c1")
        }

        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        assertThat(live.group().isBusy).isFalse()
        assertThat(live.group().detail).isEqualTo("1 file · thought for 3s")

        live.apply(RunStreamEvent.Thinking("And the build file."))
        with(live.group()) {
            assertThat(verb).isEqualTo("Exploring")
            assertThat(detail).isEqualTo("1 file")
            assertThat(isThinking).isTrue()
            assertThat(isRunning).isFalse()
        }

        clock += 2_000
        live.apply(RunStreamEvent.Assistant("Both read."))
        with(live.group()) {
            assertThat(isBusy).isFalse()
            assertThat(verb).isEqualTo("Explored")
            assertThat(detail).isEqualTo("1 file · thought for 5s")
        }
    }

    @Test
    fun `a stretch of nothing but thinking keeps its thought row`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1") { clock }
        live.apply(RunStreamEvent.Thinking("Just weighing the options."))
        clock += 4_000
        live.apply(RunStreamEvent.Assistant("Go with the sheet."))
        with(live.group()) {
            assertThat(verb).isEqualTo("Thought")
            assertThat(detail).isEqualTo("for 4s")
            assertThat(headline).isNull()
        }

        val replayed = TimelineBuilder.LiveRun("run-2", timed = false)
        replayed.apply(RunStreamEvent.Thinking("Replayed."))
        replayed.apply(RunStreamEvent.Assistant("Reply."))
        assertThat(replayed.group().verb).isEqualTo("Thought")
        assertThat(replayed.group().detail).isNull()
    }

    @Test
    fun `delegating to subagents does not split the stretch of work around it`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Thinking("Split the survey."))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey the cloud layer"))
        live.apply(tool("c2", "grep", "completed", "pattern" to "TODO"))
        live.apply(RunStreamEvent.Thinking("Meanwhile, the build."))
        live.apply(tool("c3", "run_terminal_cmd", "completed", "command" to "./gradlew build"))
        live.apply(tool("s1", "task", "completed", "subagent_type" to "explore", "description" to "Survey the cloud layer"))
        live.apply(tool("s2", "task", "running", "subagent_type" to "explore", "description" to "Survey the API"))
        live.apply(RunStreamEvent.Assistant("Here is what I found."))

        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "SubagentsCard", "AssistantMessage").inOrder()
        val work = items[0] as ActivityGroup
        assertThat(work.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall", "ToolCall", "ThinkingBlock", "ToolCall").inOrder()
        assertThat(work.detail).isEqualTo("1 file, 1 search, 1 command")
        val card = items[1] as SubagentsCard
        assertThat(card.subagents.map { it.title to it.status }).containsExactly("Survey the cloud layer" to "Done", "Survey the API" to "Running").inOrder()
    }

    @Test
    fun `a late status update lands on its call instead of opening a new group`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("c1", "run_terminal_cmd", "running", "command" to "./gradlew test"))
        live.apply(RunStreamEvent.Assistant("Tests are running; "))
        live.apply(tool("c1", "run_terminal_cmd", "completed", "command" to "./gradlew test"))
        live.apply(RunStreamEvent.Assistant("they pass."))

        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage").inOrder()
        assertThat((items[0] as ActivityGroup).calls.single().status).isEqualTo("completed")
        assertThat((items[1] as AssistantMessage).markdown).isEqualTo("Tests are running; they pass.")
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
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "SubagentsCard", "AssistantMessage", "RunFooter").inOrder()

        val work = items.filterIsInstance<ActivityGroup>().single()
        assertThat(work.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall", "ToolCall").inOrder()
        val thinking = work.thoughts.single()
        assertThat(thinking.text).isEqualTo("Let me look at the repo.")
        assertThat(thinking.isStreaming).isFalse()
        assertThat(thinking.durationSeconds).isEqualTo(3L)

        assertThat(work.calls.map { it.callId }).containsExactly("c1", "c2").inOrder()
        assertThat(work.calls.first().status).isEqualTo("completed")
        assertThat(work.fileCount).isEqualTo(1)
        assertThat(work.searchCount).isEqualTo(1)
        assertThat(work.headline).isEqualTo("1 file, 1 search")
        assertThat(work.verb).isEqualTo("Explored")
        assertThat(work.detail).isEqualTo("1 file, 1 search · thought for 3s")

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
