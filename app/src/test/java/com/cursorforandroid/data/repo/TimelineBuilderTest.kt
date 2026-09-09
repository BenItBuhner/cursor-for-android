package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolOutput
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkHeader
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

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
        val items = TimelineBuilder.fromHistory(messages, runs)
        val types = items.map { it::class.simpleName }
        assertThat(types).containsExactly(
            "UserMessage", "AssistantMessage", "AssistantMessage", "RunFooter",
            "UserMessage", "AssistantMessage",
        ).inOrder()
        val footer = items.filterIsInstance<RunFooter>().single()
        assertThat(footer.runId).isEqualTo("run-1")
        assertThat(footer.durationMs).isEqualTo(185_000L)
        assertThat(footer.branches.single().branch).isEqualTo("cursor/readme")
        assertThat((items[0] as UserMessage).text).isEqualTo("Add a README")
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
        val items = TimelineBuilder.fromHistory(messages, runs, attachments = mapOf("run-2" to shots))
        val prompts = items.filterIsInstance<UserMessage>()
        assertThat(prompts.map { it.text }).containsExactly("Add a README", "Fix the layout in these screenshots").inOrder()
        assertThat(prompts[0].attachments).isEmpty()
        assertThat(prompts[1].attachments).isEqualTo(shots)
    }

    @Test
    fun `turns Cursor injected are notification rows that still begin their run`() {
        val report = "<timestamp>Monday, Sep 7, 2026, 9:35 PM (UTC)</timestamp>\n<system_notification>\nThe following task has finished.\n\n<task>\nkind: subagent\nstatus: success\ntitle: Contacts and clipping\ndetail: This is the last output of the subagent:\n\nThe clipping is gone.\n</task>\n</system_notification>\n<user_query>The beginning of the above subagent result is already visible to the user. Perform any follow-up actions (if needed).</user_query>"
        val continuation = "<system_notification source=\"goal\">\nContinue working toward the active thread goal.\n\n<objective>\nBuild procedural hands.\n</objective>\n</system_notification>"
        val messages = listOf(
            V0ConversationMessageDto("m1", "user_message", "Build procedural hands."),
            V0ConversationMessageDto("m2", "assistant_message", "Delegated the contact pass."),
            V0ConversationMessageDto("m3", "user_message", report),
            V0ConversationMessageDto("m4", "assistant_message", "Merged it."),
            V0ConversationMessageDto("m5", "user_message", continuation),
            V0ConversationMessageDto("m6", "assistant_message", "Picking the goal back up."),
        )
        val runs = listOf(
            run("run-1", "2026-09-07T20:30:00.000Z"),
            run("run-2", "2026-09-07T21:35:00.000Z", branch = "cursor/contact-clipping"),
            run("run-3", "2026-09-07T21:50:00.000Z", status = "RUNNING", duration = null),
        )
        val items = TimelineBuilder.fromHistory(messages, runs)
        assertThat(items.map { it::class.simpleName }).containsExactly(
            "UserMessage", "AssistantMessage", "RunFooter",
            "SystemNotification", "AssistantMessage", "RunFooter",
            "SystemNotification", "AssistantMessage",
        ).inOrder()
        val done = items[3] as SystemNotification
        assertThat(done.id).isEqualTo("m3")
        assertThat(done.title).isEqualTo("Subagent completed")
        assertThat(done.summary).isEqualTo("Contacts and clipping")
        assertThat(done.body).isEqualTo("The clipping is gone.")
        // Paired with its run like any prompt: stamped with the run's start, and the run's footer follows its reply.
        assertThat(done.timestampMillis).isEqualTo(parseIsoMillis("2026-09-07T21:35:00.000Z"))
        assertThat((items[5] as RunFooter).runId).isEqualTo("run-2")
        val goal = items[6] as SystemNotification
        assertThat(goal.title).isEqualTo("Goal continued")
        assertThat(goal.summary).isEqualTo("Build procedural hands.")
        assertThat(items.none { it is UserMessage && it.text.contains("<") }).isTrue()
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
        val items = TimelineBuilder.fromHistory(messages, runs)
        assertThat(items.map { it.id }).containsNoDuplicates()
        assertThat(items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Working on it.", "Done.").inOrder()
        assertThat(items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Add a README", "Again").inOrder()
    }

    @Test
    fun `history without transcript falls back to run results`() {
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z", result = "Final answer"))
        val items = TimelineBuilder.fromHistory(emptyList(), runs)
        assertThat(items.map { it::class.simpleName }).containsExactly("AssistantMessage", "RunFooter").inOrder()
        assertThat((items[0] as AssistantMessage).markdown).isEqualTo("Final answer")
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
        val items = TimelineBuilder.fromHistory(messages, runs, traces)
        assertThat(items.map { it::class.simpleName }).containsExactly(
            "UserMessage", "ActivityGroup", "AssistantMessage", "RunFooter",
            "UserMessage", "AssistantMessage", "RunFooter",
            "UserMessage", "ActivityGroup", "AssistantMessage",
        ).inOrder()
        // The transcript's copies of run-1's replies are gone; the trace's own text and footer took their place.
        assertThat(items.none { it.id == "m2" || it.id == "m3" || it.id == "run-run-1" }).isTrue()
        assertThat((items[2] as AssistantMessage).markdown).isEqualTo("Done, README added.")
        assertThat((items[3] as RunFooter).runId).isEqualTo("run-1")
        assertThat((items[3] as RunFooter).durationMs).isEqualTo(42_000L)
        // The expired run keeps the transcript text and a footer built from the run record.
        assertThat(items[5].id).isEqualTo("m5")
        assertThat((items[6] as RunFooter).durationMs).isEqualTo(185_000L)
        // The live run has no footer yet, and its work sits right after its prompt rather than after the older runs.
        assertThat((items[7] as UserMessage).text).isEqualTo("And a FAQ")
        assertThat((items[8] as ActivityGroup).calls.map { it.callId }).containsExactly("run-3-c1", "run-3-c2").inOrder()
    }

    @Test
    fun `a trace also replaces the result fallback of runs missing from the transcript`() {
        val runs = listOf(run("run-1", "2026-04-13T18:30:00.000Z", result = "Final answer"))
        val traces = mapOf("run-1" to trace("run-1", "Final answer"))
        val items = TimelineBuilder.fromHistory(emptyList(), runs, traces)
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
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
        // With no time to report, the thought's row is the bare verb; the read that follows is a line of its own.
        assertThat(work.thoughtAction).isEqualTo("Thought")
        assertThat(work.thoughtDetails).isNull()
        assertThat(work.isWorkGrouped).isFalse()
        assertThat(work.header).isEqualTo(WorkHeader("Explored", "README.md"))
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
        // The thought before the first tool call is its own row; the work behind it is summed up the desktop's way,
        // the edit first, then what was explored, the second thought among the steps.
        assertThat(first.leadingThoughts.map { it.text }).containsExactly("Where does the picker live?")
        assertThat(first.thoughtAction).isEqualTo("Thought")
        assertThat(first.thoughtDetails).isEqualTo("2s")
        assertThat(first.work.map { it::class.simpleName }).containsExactly("ToolCall", "ToolCall", "ThinkingBlock", "ToolCall", "ToolCall").inOrder()
        assertThat(first.isWorkGrouped).isTrue()
        assertThat(first.header).isEqualTo(WorkHeader("Edited", "HomeScreen.kt, explored 2 files, 1 search"))

        val second = items[2] as ActivityGroup
        assertThat(second.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall").inOrder()
        assertThat(second.thoughtDetails).isEqualTo("1s")
        assertThat(second.header).isEqualTo(WorkHeader("Explored", "1 search"))
    }

    @Test
    fun `the row narrates the step in progress and settles into a summary once the stretch is over`() {
        var clock = 1_000L
        val live = TimelineBuilder.LiveRun("run-1") { clock }

        live.apply(RunStreamEvent.Thinking("Let me see."))
        with(live.group()) {
            assertThat(thoughtAction).isEqualTo("Thinking")
            assertThat(thoughtDetails).isNull()
            assertThat(isLeadingThoughtStreaming).isTrue()
            assertThat(isThinking).isTrue()
            assertThat(isBusy).isTrue()
            assertThat(work).isEmpty()
        }

        clock += 3_000
        live.apply(tool("c1", "read_file", "running", "path" to "README.md"))
        with(live.group()) {
            // The thought closed when the tool started; the read is a line of its own, still going.
            assertThat(thoughtAction).isEqualTo("Thought")
            assertThat(thoughtDetails).isEqualTo("3s")
            assertThat(isWorkGrouped).isFalse()
            assertThat(isWorkBusy).isTrue()
            assertThat(header).isEqualTo(WorkHeader("Exploring", "README.md"))
            assertThat(runningCall?.callId).isEqualTo("c1")
            assertThat(runningCall?.action).isEqualTo("Reading")
        }

        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        assertThat(live.group().isBusy).isFalse()
        assertThat(live.group().calls.single().action).isEqualTo("Read")

        live.apply(RunStreamEvent.Thinking("And the build file."))
        with(live.group()) {
            // A thought after a tool call joins the work, which is now worth a row of its own.
            assertThat(isWorkGrouped).isTrue()
            assertThat(header).isEqualTo(WorkHeader("Exploring", "README.md"))
            assertThat(isThinking).isTrue()
            assertThat(isLeadingThoughtStreaming).isFalse()
            assertThat(isRunning).isFalse()
        }

        clock += 2_000
        live.apply(RunStreamEvent.Assistant("Both read."))
        with(live.group()) {
            assertThat(isBusy).isFalse()
            assertThat(header).isEqualTo(WorkHeader("Explored", "README.md"))
            // The leading thought's time is its own; the later thought is read among the steps.
            assertThat(thoughtDetails).isEqualTo("3s")
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
            assertThat(thoughtAction).isEqualTo("Thought")
            assertThat(thoughtDetails).isEqualTo("4s")
            assertThat(work).isEmpty()
            assertThat(isWorkGrouped).isFalse()
        }

        val replayed = TimelineBuilder.LiveRun("run-2", timed = false)
        replayed.apply(RunStreamEvent.Thinking("Replayed."))
        replayed.apply(RunStreamEvent.Assistant("Reply."))
        assertThat(replayed.group().thoughtAction).isEqualTo("Thought")
        assertThat(replayed.group().thoughtDetails).isNull()
    }

    @Test
    fun `delegating to subagents is a tool call among the others, as it is on the desktop`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Thinking("Split the survey."))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(tool("s1", "task_v2", "running", "description" to "Survey the cloud layer", "prompt" to "Map the cloud layer."))
        live.apply(tool("c2", "grep", "completed", "pattern" to "TODO"))
        live.apply(RunStreamEvent.Thinking("Meanwhile, the build."))
        live.apply(tool("c3", "run_terminal_cmd", "completed", "command" to "./gradlew build"))
        live.apply(tool("s1", "task_v2", "completed", "description" to "Survey the cloud layer", "prompt" to "Map the cloud layer."))
        live.apply(tool("s2", "task_v2", "running", "description" to "Survey the API", "prompt" to "Map the API."))
        live.apply(RunStreamEvent.Assistant("Here is what I found."))

        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage").inOrder()
        val work = items[0] as ActivityGroup
        assertThat(work.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall", "ToolCall", "ToolCall", "ThinkingBlock", "ToolCall", "ToolCall").inOrder()
        assertThat(work.header).isEqualTo(WorkHeader("Exploring", "README.md, 1 search, ran 1 command, 2 agents"))
        val tasks = work.calls.filter { it.kind == ToolKind.Task }
        assertThat(tasks.map { it.action to it.summary }).containsExactly("Completed task" to "Survey the cloud layer", "Working on task" to "Survey the API").inOrder()
        assertThat(tasks.map { it.detail }).containsExactly("Map the cloud layer.", "Map the API.").inOrder()
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
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()

        val work = items.filterIsInstance<ActivityGroup>().single()
        assertThat(work.steps.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolCall", "ToolCall", "ToolCall").inOrder()
        val thinking = work.thoughts.single()
        assertThat(thinking.text).isEqualTo("Let me look at the repo.")
        assertThat(thinking.isStreaming).isFalse()
        assertThat(thinking.durationSeconds).isEqualTo(3L)
        assertThat(work.thoughtDetails).isEqualTo("3s")

        assertThat(work.calls.map { it.callId }).containsExactly("c1", "c2", "s1").inOrder()
        assertThat(work.calls.first().status).isEqualTo("completed")
        assertThat(work.summary.files).containsExactly("README.md")
        assertThat(work.summary.searches).isEqualTo(1)
        assertThat(work.summary.taskCalls).isEqualTo(1)
        assertThat(work.header).isEqualTo(WorkHeader("Explored", "README.md, 1 search, 1 agent"))

        // The subagent's `completed` arrived after the reply had begun, and still landed on its call.
        val task = work.calls.last()
        assertThat(task.kind).isEqualTo(ToolKind.Task)
        assertThat(task.action).isEqualTo("Completed task")
        assertThat(task.summary).isEqualTo("Survey cloud layer")

        val assistant = items.filterIsInstance<AssistantMessage>().single()
        assertThat(assistant.markdown).isEqualTo("Hello world.")
        assertThat(assistant.isStreaming).isFalse()

        val footer = items.last() as RunFooter
        assertThat(footer.status).isEqualTo(RunStatus.FINISHED)
        assertThat(footer.durationMs).isEqualTo(42_000L)
        assertThat(live.finished).isTrue()
    }

    /**
     * A tool call's JSON is whole files and whole command outputs. Everything the trace needs is read off it while
     * the call is built; keeping the rest would hold the run's entire payload for as long as the chat is open.
     */
    @Test
    fun `a tool call keeps its line counts and drops the json they came from`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        live.apply(
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = "e1",
                    name = "edit_file",
                    status = "completed",
                    args = buildJsonObject { put("path", JsonPrimitive("a/One.kt")) },
                    result = buildJsonObject { put("linesAdded", JsonPrimitive(12)); put("linesRemoved", JsonPrimitive(3)) },
                ),
            ),
        )
        live.apply(tool("r1", "read_file", "completed", "path" to "b/Two.kt"))

        val calls = live.snapshot().filterIsInstance<ActivityGroup>().single().calls
        val edit = calls.single { it.callId == "e1" }
        // The row reads "Edited One.kt", as Cursor's own does, and opens onto the path it was given.
        assertThat(edit.summary).isEqualTo("One.kt")
        assertThat(edit.detail).isEqualTo("a/One.kt")
        assertThat(edit.linesAdded).isEqualTo(12)
        assertThat(edit.linesRemoved).isEqualTo(3)
        // A read is not an edit, so nothing was walked for it.
        assertThat(calls.single { it.callId == "r1" }.linesAdded).isNull()
    }

    /** A command's output is the other payload a trace would otherwise carry whole: it is clipped as the call is built. */
    @Test
    fun `a command keeps the clipped output its row opens onto`() {
        val live = TimelineBuilder.LiveRun("run-1") { 0L }
        live.apply(
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = "s1",
                    name = "run_terminal_cmd",
                    status = "completed",
                    args = buildJsonObject { put("command", JsonPrimitive("ls")) },
                    result = buildJsonObject { put("stdout", JsonPrimitive((1..60).joinToString("\n") { "line $it" })); put("exitCode", JsonPrimitive(0)) },
                ),
            ),
        )

        val shell = live.snapshot().filterIsInstance<ActivityGroup>().single().calls.single()
        assertThat(shell.exitCode).isEqualTo(0)
        assertThat(shell.output!!.lines()).hasSize(ToolOutput.MAX_OUTPUT_LINES + 1)
        assertThat(shell.output).endsWith("… 20 more lines")
    }

    /**
     * Text is only written into its item when something is about to read it, so every reader — a snapshot between
     * two deltas, a tool call closing a thought, the result — has to see everything that arrived before it.
     */
    @Test
    fun `a snapshot between deltas shows the text so far and loses nothing appended after it`() {
        val live = TimelineBuilder.LiveRun("run-7") { 1_000L }
        live.apply(RunStreamEvent.Thinking("Look"))
        assertThat(live.snapshot().filterIsInstance<ActivityGroup>().single().thoughts.single().text).isEqualTo("Look")
        live.apply(RunStreamEvent.Thinking("ing around."))
        live.apply(RunStreamEvent.Assistant("Half "))
        assertThat(live.snapshot().filterIsInstance<ActivityGroup>().single().thoughts.single().text).isEqualTo("Looking around.")
        assertThat(live.snapshot().filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Half ")
        live.apply(RunStreamEvent.Assistant("way."))
        live.apply(RunStreamEvent.Thinking("More"))
        live.apply(RunStreamEvent.Thinking(" thought."))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(RunStreamEvent.Assistant("Done."))

        val items = live.snapshot()
        assertThat(items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Half way.", "Done.").inOrder()
        assertThat(items.filterIsInstance<ActivityGroup>().flatMap { it.thoughts }.map { it.text })
            .containsExactly("Looking around.", "More thought.").inOrder()
        // Taking it twice with nothing in between says exactly the same thing.
        assertThat(live.snapshot()).isEqualTo(items)
    }

    /**
     * A reader pays for the text once per change, not once per read: the reply is only written into its item when
     * a delta has actually arrived since the last time anyone looked.
     */
    @Test
    fun `reading the same trace twice does not build its text again`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        repeat(200) { live.apply(RunStreamEvent.Assistant("token $it ")) }
        val first = live.snapshot()
        val second = live.snapshot()
        first.indices.forEach { i -> assertThat(second[i]).isSameInstanceAs(first[i]) }

        // One more delta rewrites that item and nothing else.
        live.apply(RunStreamEvent.Thinking("Hmm."))
        live.apply(RunStreamEvent.Assistant("and more."))
        val third = live.snapshot()
        assertThat(third.first()).isSameInstanceAs(first.first())
        assertThat(third.filterIsInstance<AssistantMessage>().last().markdown).isEqualTo("and more.")
    }

    /** The live notification times a run whose outcome carries no duration; the footer used to say nothing at all. */
    @Test
    fun `a finished run without a reported duration still says how long it worked`() {
        var clock = 60_000L
        val live = TimelineBuilder.LiveRun("run-1", startedAtMillis = 5_000L, nowProvider = { clock })
        clock = 95_000L
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", null, null))
        assertThat((live.snapshot().last() as RunFooter).durationMs).isEqualTo(90_000L)

        // A replay is not happening now, so its clock says nothing about the run and no duration is invented.
        val replayed = TimelineBuilder.LiveRun("run-2", timed = false, startedAtMillis = 5_000L, nowProvider = { clock })
        replayed.apply(RunStreamEvent.Result("run-2", RunStatus.FINISHED, "Done.", null, null))
        assertThat((replayed.snapshot().last() as RunFooter).durationMs).isNull()
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
        assertThat(live.snapshot().map { it::class.simpleName }).containsExactly("ActivityGroup")
        assertThat(live.group().isThinking).isTrue()
        assertThat(live.applied).isEqualTo(1)

        clock += 5_000
        live.apply(RunStreamEvent.Thinking(" first."))
        live.apply(RunStreamEvent.Assistant("Done."))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", 5_000, null))
        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        // One thought, continued across the drop rather than split in two.
        assertThat((items[0] as ActivityGroup).thoughts.single().text).isEqualTo("Reading the code first.")
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
        val tools = live.group()
        assertThat(tools.isBusy).isFalse()
        assertThat(tools.calls.map { it.status }).containsExactly("completed", "completed", "completed").inOrder()
        assertThat(tools.header.action).isEqualTo("Explored")
        assertThat(tools.calls.last().action).isEqualTo("Completed task")

        // A run that did not finish interrupted whatever was still running.
        val cut = TimelineBuilder.LiveRun("run-2", timed = false)
        cut.apply(tool("c1", "run_terminal_cmd", "running", "command" to "sleep 100"))
        cut.apply(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey"))
        cut.apply(RunStreamEvent.Result("run-2", RunStatus.CANCELLED, null, 3_000, null))
        val interrupted = cut.group().calls
        assertThat(interrupted.none { it.isRunning }).isTrue()
        assertThat(interrupted.map { it.status }).containsExactly(ToolCall.STATUS_INTERRUPTED, ToolCall.STATUS_INTERRUPTED)
        assertThat(interrupted.map { it.action }).containsExactly("Ran", "Completed task").inOrder()
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

    @Test
    fun `the final reply is appended when the stream only carried remarks between tool calls`() {
        // The shape of the API's own example: a remark, a tool call, then the reply arrives with the result alone.
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Assistant("I'll update the README now."))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Added README.md with installation instructions.", 12_357, null))
        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("AssistantMessage", "ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(items.filterIsInstance<AssistantMessage>().map { it.markdown })
            .containsExactly("I'll update the README now.", "Added README.md with installation instructions.").inOrder()
    }

    @Test
    fun `a final reply the stream already delivered is not repeated by the result`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Assistant("Looking first."))
        live.apply(tool("c1", "read_file", "completed", "path" to "README.md"))
        live.apply(RunStreamEvent.Assistant("Added the\nREADME."))
        // Same words, different whitespace: still the same reply.
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Added the README.\n", 1_000, null))
        val items = live.snapshot()
        assertThat(items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Looking first.", "Added the\nREADME.").inOrder()
        assertThat(items.last()).isInstanceOf(RunFooter::class.java)
    }

    @Test
    fun `a reply the stream cut off is completed in place by the result`() {
        // The connection dropped mid-reply and the outcome was read from the run record.
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(tool("c1", "edit_file", "completed", "path" to "README.md"))
        live.apply(RunStreamEvent.Assistant("Added the RE"))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Added the README and a troubleshooting section.", 1_000, null))
        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        val reply = items.filterIsInstance<AssistantMessage>().single()
        assertThat(reply.markdown).isEqualTo("Added the README and a troubleshooting section.")
        assertThat(reply.isStreaming).isFalse()
    }

    @Test
    fun `a failed run still gets its final reply, ahead of the notice`() {
        val live = TimelineBuilder.LiveRun("run-1", timed = false)
        live.apply(RunStreamEvent.Assistant("Trying the build."))
        live.apply(tool("c1", "run_terminal_cmd", "completed", "command" to "./gradlew build"))
        live.apply(RunStreamEvent.Result("run-1", RunStatus.ERROR, "The build failed on a missing dependency.", 9_000, null))
        val items = live.snapshot()
        assertThat(items.map { it::class.simpleName }).containsExactly("AssistantMessage", "ActivityGroup", "AssistantMessage", "NoticeCard", "RunFooter").inOrder()
        assertThat((items[2] as AssistantMessage).markdown).isEqualTo("The build failed on a missing dependency.")
        assertThat((items.last() as RunFooter).status).isEqualTo(RunStatus.ERROR)
    }
}
