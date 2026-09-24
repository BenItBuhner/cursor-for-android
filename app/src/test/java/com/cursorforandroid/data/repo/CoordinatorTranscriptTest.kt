package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorLineage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * A Project coordinator's transcript as the stream carries it, shaped on the coordinator of this very Project: the
 * coordinator's plain replies between tool calls are its working notes ("The release worker shipped v0.3.3 …"), and
 * what it says to the user goes through its `SendMessage` tool — `send_to_user` in the proto (`SendToUserArgs
 * {message}`, `ClientSideToolV2.SEND_TO_USER`), `SendMessage` by name on today's stream — which until 0.3.4 read as a
 * bare "Send message" row with nothing under it, while the notes read as the messages. The tool's message is the
 * coordinator's message, whichever name the stream gives the tool; the rest of the coordinator's tools keep their
 * cards and rows; and a chat whose transcript carries these tools is a coordinator's.
 */
class CoordinatorTranscriptTest {

    private fun call(id: String, name: String, args: String, result: String? = null) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = id,
            name = name,
            status = "completed",
            args = Json.parseToJsonElement(args) as JsonObject,
            result = result?.let { Json.parseToJsonElement(it) as JsonObject },
        ),
    )

    /** The turn of the reference screenshot, event for event. */
    private fun bennettsTurn(): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun("run-coord", timed = false)
        live.apply(RunStreamEvent.Assistant("The release worker shipped v0.3.3 and bumped main to 0.3.4; Bennett has the link with the root causes, and the board is rotated. The keyboard worker is the only one still building."))
        live.apply(RunStreamEvent.Thinking("The keyboard worker is done; route the release and tell Bennett."))
        live.apply(
            call(
                "c1", "send_to_agent",
                """{"agentId":"bc-release","message":"Land merge train and prep release: merge #113 and cut v0.3.4."}""",
                """{"success":{"status":"Delivered as queue"}}""",
            ),
        )
        live.apply(
            call(
                "c2", "SendMessage",
                """{"message":"The keyboard worker merged #113 with four root causes fixed against the Gboard reference; **v0.3.4** is being cut from it, Bennett has the explanation plus the note that his recording was the reference app, and the board is updated."}""",
                """{"success":{}}""",
            ),
        )
        live.apply(call("c3", "edit", """{"path":"notes.md"}""", """{"success":{"linesAdded":2,"linesRemoved":1}}"""))
        live.apply(RunStreamEvent.Assistant("The missing-Projects regression is routed as urgent to the Projects worker while 0.3.4 finishes cutting; Bennett has both tracks, and the board reflects them. 0.3.5 follows the fix."))
        live.apply(RunStreamEvent.Result("run-coord", RunStatus.FINISHED, null, 81_000, null))
        return live.snapshot()
    }

    @Test
    fun `SendMessage is the coordinator's tool to the user under either of its names`() {
        listOf("SendMessage", "sendMessage", "send_message", "SendMessageToolCall", "send_to_user", "sendToUser", "SendToUserToolCall").forEach { name ->
            assertThat(ToolNames.kindOf(name)).isEqualTo(ToolKind.Coordinator)
            assertThat(ToolNames.coordinatorTool(name)).isEqualTo("send_to_user")
            assertThat(CoordinatorLineage.isUserMessageTool(name)).isTrue()
        }
        // Names no worker: it is no lineage signal, and no worker id is read off it.
        assertThat(CoordinatorLineage.isCoordinatorTool("SendMessage")).isFalse()
        assertThat(ToolNames.coordinatorTool("send_to_agent")).isEqualTo("send_to_agent")
        assertThat(CoordinatorLineage.isUserMessageTool("send_to_agent")).isFalse()
        assertThat(ToolNames.coordinatorTool("read_file")).isNull()
    }

    @Test
    fun `the tool's message is read off its arguments, whichever field carries it`() {
        val message = "Both tracks are **moving**."
        listOf("message", "text", "content", "body", "markdown").forEach { field ->
            val payload = ToolPayloads.from("SendMessage", Json.parseToJsonElement("""{"$field":"$message"}"""), Json.parseToJsonElement("""{"success":{}}"""), null, callId = "c")
            assertThat(payload).isEqualTo(ToolPayload.CoordinatorMessage(message))
        }
        // Nothing said: nothing to render as the message.
        assertThat(ToolPayloads.from("SendMessage", Json.parseToJsonElement("{}"), null, null, callId = "c")).isNull()
    }

    /**
     * A silent turn's log replays the previous turn whole, not its message alone (Bennett's 2026-09-20 export: the
     * eleven-step block with ids …7gwWXN … w2dwQQ twice in a row, each with its own `assistant chars=239` and footer,
     * then a third `assistant chars=239` under a 4 s footer). Every call an earlier run drew is a replay, the text
     * that came with them too; the silent runs keep their footers and nothing else. A run with a call of its own
     * keeps its text although the words were said before.
     */
    @Test
    fun `a silent run's log that replays the previous turn's whole activity contributes only its footer`() {
        fun turn(runId: String, calls: List<Pair<String, String>>, text: String?, durationMs: Long): List<TimelineItem> {
            val live = TimelineBuilder.LiveRun(runId, timed = false)
            live.apply(RunStreamEvent.Thinking("Reading the report."))
            calls.forEach { (id, name) ->
                val args = when (name) { "send_message" -> """{"text":{"content":"Done: the pricing table is filed."}}"""; "send_to_agent" -> """{"agentId":"bc-w","message":"Next."}"""; else -> """{"path":"notes.md"}""" }
                live.apply(call(id, name, args, """{"success":{}}"""))
            }
            text?.let { live.apply(RunStreamEvent.Assistant(it)) }
            live.apply(RunStreamEvent.Result(runId, RunStatus.FINISHED, null, durationMs, null))
            return live.snapshot()
        }
        val original = listOf("7gwWXN" to "run_terminal_cmd", "xVzzwn" to "get_mcp_tools", "AGGrGj" to "read_file", "9dBRYJ" to "send_to_agent", "W5TUR6" to "send_message", "RCHLg4" to "edit_file", "w2dwQQ" to "edit_file")
        val note = "The research worker's table is filed; the primary is told what comes next."
        val items = turn("run-A", original, note, 98_586) +
            turn("run-B", original, note, 115_315) +
            turn("run-C", emptyList(), note, 4_245) +
            // A later run of its own that says the same words again: not a replay.
            turn("run-D", listOf("mzA4RA" to "edit_file"), note, 54_429)

        val leftOut = CoordinatorTranscript.replayedActivity(items)
        val presented = CoordinatorTranscript.present(items, coordinatorMode = true)
        // Run A whole; run B its footer alone; run C its footer alone; run D its call, its text and its footer.
        val calls = presented.filterIsInstance<ActivityGroup>().flatMap { it.calls }.map { it.callId }
        assertThat(calls).containsExactlyElementsIn(original.map { it.first } + "mzA4RA").inOrder()
        assertThat(presented.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly(note, note).inOrder()
        assertThat(presented.filterIsInstance<RunFooter>().map { it.runId }).containsExactly("run-A", "run-B", "run-C", "run-D").inOrder()
        assertThat(leftOut.values.toSet()).containsExactly("run:run-A")
        // The rows: run B and C fold into the stretch after run A's message, whose time is the three runs' together.
        val rows = TranscriptRows.of(presented, coordinatorMode = true)
        val messages = rows.filterIsInstance<TranscriptRow.Message>()
        assertThat(messages).hasSize(1)
        val after = rows.subList(rows.indexOf(messages.single()) + 1, rows.size).filterIsInstance<TranscriptRow.Stretch>()
        assertThat(after.first().entries.filterIsInstance<TranscriptRow.Entry.Footer>().map { it.footer.runId }).containsExactly("run-A", "run-B", "run-C", "run-D").inOrder()
        assertThat(after.first().summary.action).isEqualTo("Worked 4m 32s")
    }

    @Test
    fun `the coordinator's turn keeps its notes and its message apart, each as its own item`() {
        val items = bennettsTurn()
        // The two plain replies are assistant messages still — the transcript decides how to show them.
        val notes = items.filterIsInstance<AssistantMessage>()
        assertThat(notes.map { it.markdown }).containsExactly(
            "The release worker shipped v0.3.3 and bumped main to 0.3.4; Bennett has the link with the root causes, and the board is rotated. The keyboard worker is the only one still building.",
            "The missing-Projects regression is routed as urgent to the Projects worker while 0.3.4 finishes cutting; Bennett has both tracks, and the board reflects them. 0.3.5 follows the fix.",
        ).inOrder()

        // The tool calls sit in one group that is not folded: the coordinator's steps read one by one.
        val group = items.filterIsInstance<ActivityGroup>().single()
        assertThat(group.isCoordination).isTrue()
        assertThat(group.isWorkGrouped).isFalse()
        val calls = group.calls.associateBy { it.callId }

        // SendMessage carries its message as the payload the transcript renders as the coordinator's update.
        val update = calls.getValue("c2")
        assertThat(update.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(update.payload).isEqualTo(
            ToolPayload.CoordinatorMessage("The keyboard worker merged #113 with four root causes fixed against the Gboard reference; **v0.3.4** is being cut from it, Bennett has the explanation plus the note that his recording was the reference app, and the board is updated."),
        )
        assertThat(update.linkedAgentIds).isEmpty()

        // The message to the worker is the compact row it was, with its worker linked.
        val toWorker = calls.getValue("c1")
        assertThat(toWorker.kind).isEqualTo(ToolKind.Coordinator)
        assertThat((toWorker.payload as ToolPayload.WorkerAction).kind).isEqualTo(ToolPayload.WorkerAction.Kind.Messaged)
        assertThat(toWorker.linkedAgentIds).containsExactly("bc-release")

        // The coordinator's own edit is an edit like any other's.
        val edit = calls.getValue("c3")
        assertThat(edit.kind).isEqualTo(ToolKind.Edit)
        assertThat(edit.linesAdded).isEqualTo(2)
        assertThat(edit.linesRemoved).isEqualTo(1)
        assertThat(items.filterIsInstance<UserMessage>()).isEmpty()
    }

    @Test
    fun `a transcript carrying the coordinator's tools is a coordinator's, SendMessage alone included`() {
        assertThat(CoordinatorLineage.isCoordinator(bennettsTurn())).isTrue()
        // Its worker mentions are read for the lineage as before; the message to the user names none.
        assertThat(CoordinatorLineage.workerIds(bennettsTurn())).containsExactly("bc-release")
        assertThat(CoordinatorLineage.createdWorkerIds(bennettsTurn())).isEmpty()

        val onlyUpdates = TimelineBuilder.LiveRun("run-2", timed = false).apply {
            apply(RunStreamEvent.Assistant("Checking on the board."))
            apply(call("u1", "SendMessage", """{"message":"All three workers are done."}"""))
        }.snapshot()
        assertThat(CoordinatorLineage.isCoordinator(onlyUpdates)).isTrue()
        assertThat(CoordinatorLineage.workerIds(onlyUpdates)).isEmpty()

        // An agent's chat with the same shape and no coordinator's tool is not.
        val plain = TimelineBuilder.LiveRun("run-3", timed = false).apply {
            apply(RunStreamEvent.Assistant("Looking at the failing test."))
            apply(call("p1", "edit", """{"path":"notes.md"}""", """{"success":{"linesAdded":2,"linesRemoved":1}}"""))
        }.snapshot()
        assertThat(CoordinatorLineage.isCoordinator(plain)).isFalse()
    }

    @Test
    fun `an older stream's send_to_user reads the same as SendMessage`() {
        val live = TimelineBuilder.LiveRun("run-old", timed = false)
        live.apply(call("o1", "send_to_user", """{"message":"PR #215 is merged."}""", """{"success":{}}"""))
        val call = live.snapshot().filterIsInstance<ActivityGroup>().single().calls.single()
        assertThat(call.payload).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged."))
        assertThat(call.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(call.summary).isEqualTo("PR #215 is merged.")
    }
}
