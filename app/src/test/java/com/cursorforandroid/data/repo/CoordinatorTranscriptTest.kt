package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorLineage
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
