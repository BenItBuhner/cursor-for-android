package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorLineage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptDiagnostics
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

/**
 * A Project coordinator's turn as the documented stream carries it, frame for frame, from the fixtures generated
 * off Cursor's own protos (see [CoordinatorFixtures]). The bug this pins: the `SendMessage` tool's arguments are
 * `SendMessageArgs {text {content}}`, so its text is `args.text.content`, two levels down — a reader looking for a
 * top-level `message` string found nothing, and the coordinator's updates rendered as bare "Sent message" rows.
 */
class CoordinatorWireShapesTest {

    private val update = "The keyboard worker merged #113 with four root causes fixed against the Gboard reference; **v0.3.4** is being cut from it, Bennett has the explanation plus the note that his recording was the reference app, and the board is updated."
    private val second = "The missing-Projects regression is routed as urgent to the Projects worker while 0.3.4 finishes cutting; Bennett has both tracks, and the board reflects them. 0.3.5 follows the fix."

    /** Every frame of the fixture, parsed as the streamer parses them. */
    private fun events(): List<RunStreamEvent> {
        val source = Buffer().writeUtf8(CoordinatorFixtures.text("coordinator_run.sse"))
        val events = ArrayList<RunStreamEvent>()
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.let { events += it.event }
        }
        return events
    }

    private fun replay(): List<TimelineItem> {
        val live = TimelineBuilder.LiveRun("run-coord-001", timed = false)
        events().forEach(live::apply)
        return live.snapshot()
    }

    private fun calls(items: List<TimelineItem>): Map<String, ToolCall> = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }

    @Test
    fun `the fixture is a whole run, every tool_call frame decoded`() {
        val events = events()
        assertThat(events.count { it is RunStreamEvent.ToolCall }).isEqualTo(12)
        assertThat(events.last()).isEqualTo(RunStreamEvent.Done)
        assertThat(events.filterIsInstance<RunStreamEvent.ToolCall>().map { it.call.name }.distinct())
            .containsExactly("sendToAgent", "sendMessage", "edit", "SendMessage", "send_to_user", "create_agent", "getAgentStatus")
    }

    @Test
    fun `SendMessage carries its text under text-content, and that is the coordinator's message`() {
        val calls = calls(replay())
        // The SDK's public name, proto3 JSON.
        val sdkName = calls.getValue("c2")
        assertThat(sdkName.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(sdkName.payload).isEqualTo(ToolPayload.CoordinatorMessage(update))
        assertThat(sdkName.summary).isEqualTo(update.take(45) + "...")
        assertThat(sdkName.detail).isEqualTo(update)
        assertThat(sdkName.argKeys).containsExactly("text")
        assertThat(sdkName.action).isEqualTo("Sent message")
        assertThat(sdkName.isError).isFalse()
        // The desktop's model-facing name, the desktop's own serialisation: the same message.
        val desktopName = calls.getValue("c4")
        assertThat(desktopName.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(desktopName.payload).isEqualTo(ToolPayload.CoordinatorMessage(second))
        assertThat(desktopName.argKeys).containsExactly("text")
    }

    @Test
    fun `an attachment sent instead of text is the markdown for it`() {
        val call = calls(replay()).getValue("c5")
        assertThat(call.payload).isEqualTo(ToolPayload.CoordinatorMessage("![The board after the merge](https://cursor.com/agents/bc-demo/artifacts/board.png)"))
    }

    @Test
    fun `arguments the stream left out for size still make a message row, marked as missing its body`() {
        val call = calls(replay()).getValue("c6")
        assertThat(call.truncated?.args).isTrue()
        assertThat(call.payload).isEqualTo(ToolPayload.CoordinatorMessage("", missing = true))
        assertThat(call.argKeys).isEmpty()
        // The stream itself left it out: a replay would bring the same frame, so this is not a trace to refresh.
        assertThat(CoordinatorTranscript.needsRefresh(replay())).isFalse()
    }

    @Test
    fun `a failed send is an error row and no message, as Cursor's client hides it`() {
        val call = calls(replay()).getValue("c7")
        assertThat(call.isError).isTrue()
        assertThat(call.payload).isNull()
        assertThat(call.action).isEqualTo("Send message")
        assertThat(call.details).isEqualTo("attempted")
    }

    @Test
    fun `the older send_to_user reads the same as SendMessage`() {
        val call = calls(replay()).getValue("c8")
        assertThat(call.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(call.payload).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged."))
        assertThat(call.argKeys).containsExactly("message")
    }

    @Test
    fun `the rest of the coordinator's tools keep their cards and rows under either spelling`() {
        val calls = calls(replay())
        val messaged = calls.getValue("c1").payload as ToolPayload.WorkerAction
        assertThat(messaged.kind).isEqualTo(ToolPayload.WorkerAction.Kind.Messaged)
        assertThat(messaged.worker?.agentId).isEqualTo("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")
        assertThat(messaged.title).isEqualTo("Land merge train and prep release")
        assertThat(messaged.note).isEqualTo("Delivered as queue")
        assertThat(calls.getValue("c1").argKeys).containsExactly("toolCallId", "agentId", "message", "delivery", "title").inOrder()

        val created = calls.getValue("c9").payload as ToolPayload.WorkerAction
        assertThat(created.kind).isEqualTo(ToolPayload.WorkerAction.Kind.Created)
        assertThat(created.reported).isTrue()
        assertThat(created.worker?.agentId).isEqualTo("bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724")
        assertThat(created.title).isEqualTo("Build Projects under Extended mode")
        assertThat(calls.getValue("c9").linkedAgentIds).containsExactly("bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724")

        val status = calls.getValue("c10").payload as ToolPayload.WorkerAction
        assertThat(status.kind).isEqualTo(ToolPayload.WorkerAction.Kind.Status)
        assertThat(status.reported).isTrue()
        assertThat(status.workers.map { it.name }).containsExactly("Land merge train and prep release", "Build Projects under Extended mode").inOrder()
        // proto3 JSON leaves a false `turn_in_flight` out: absent reads as not in flight, and the last turn's status decides.
        assertThat(status.workers.map { it.statusLabel }).containsExactly("Finished", "Working").inOrder()
        assertThat(status.workers.map { it.prUrl }).containsExactly("https://github.com/BenItBuhner/cursor-for-android/pull/117", null).inOrder()
    }

    @Test
    fun `the turn is a coordinator's by its content alone, and the notes stay apart from the messages`() {
        val items = replay()
        assertThat(CoordinatorTranscript.hasCoordinatorContent(items)).isTrue()
        assertThat(CoordinatorTranscript.evidence(items)).containsExactly("sendToAgent", "sendMessage", "SendMessage", "send_to_user", "create_agent", "getAgentStatus").inOrder()
        assertThat(CoordinatorLineage.isCoordinator(items)).isTrue()
        val notes = items.filterIsInstance<AssistantMessage>().map { it.markdown }
        assertThat(notes).containsExactly(
            "The release worker shipped v0.3.3 and bumped main to 0.3.4; Bennett has the link with the root causes, and the board is rotated. The keyboard worker is the only one still building.",
            second,
        ).inOrder()
        // A chat the list never classified: the screen's decision is made from the content.
        val decision = TranscriptDiagnostics.decide(agent = null, items = items, recordProjectMode = false)
        assertThat(decision.coordinatorMode).isTrue()
        assertThat(decision.listProject).isFalse()
        assertThat(decision.content).isTrue()
        // The coordinator's group is never folded behind a summary.
        assertThat(items.filterIsInstance<ActivityGroup>().all { it.isCoordination && !it.isWorkGrouped }).isTrue()
    }

    @Test
    fun `a tool_call frame wrapped in a data member is read as the SDK reads it`() {
        val args = CoordinatorFixtures.shape("sendMessage", "text", "toJson")
        val wrapped = com.cursorforandroid.data.api.SseFrame("tool_call", "evt-1", """{"data":{"callId":"w1","name":"sendMessage","status":"completed","args":$args}}""")
        val event = SseParser.toEvent(wrapped) as RunStreamEvent.ToolCall
        assertThat(event.call.callId).isEqualTo("w1")
        assertThat(ToolCallMapper.from(event.call).payload).isEqualTo(ToolPayload.CoordinatorMessage(update))
        // A call whose own arguments have a `data` key is not unwrapped.
        val own = com.cursorforandroid.data.api.SseFrame("tool_call", "evt-2", """{"callId":"w2","name":"mcp","status":"completed","data":{"callId":"nested"}}""")
        assertThat((SseParser.toEvent(own) as RunStreamEvent.ToolCall).call.callId).isEqualTo("w2")
    }

    @Test
    fun `the bodies alone are read under every serialisation Cursor's TypeScript produces`() {
        for ((form, expected) in listOf("toJson" to update, "stringify" to update, "snake" to update)) {
            val args = CoordinatorFixtures.shape("sendMessage", "text", form)
            assertThat(ToolPayloads.coordinatorMessage(args)).isEqualTo(expected)
        }
        assertThat(ToolPayloads.coordinatorMessage(CoordinatorFixtures.shape("sendMessage", "attachment", "toJson"))).isEqualTo("![The board after the merge](https://cursor.com/agents/bc-demo/artifacts/board.png)")
        assertThat(ToolPayloads.coordinatorMessage(CoordinatorFixtures.shape("sendToUser", "args", "toJson"))).isEqualTo("PR #215 is merged.")
        // A success result carries no text: nothing is read off it as the message.
        val success = CoordinatorFixtures.shape("sendMessage", "success", "toJson")
        assertThat(ToolPayloads.from("sendMessage", null, success, null, "c")).isNull()
        // The error result's shape is an error: the call reads as failed under either name.
        val failed = ToolCallMapper.from(com.cursorforandroid.data.api.dto.SseToolCallDto("c", "sendMessage", "completed", CoordinatorFixtures.shape("sendMessage", "text", "toJson"), CoordinatorFixtures.shape("sendMessage", "error", "toJson")))
        assertThat(failed.isError).isTrue()
        assertThat(failed.payload).isNull()
    }
}
