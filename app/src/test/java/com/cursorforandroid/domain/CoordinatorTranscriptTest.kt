package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.local.CachedTrace
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A coordinator's chat read from what its transcript carries: the cached shape an earlier build wrote, the decision
 * made without the account's word, and the folding of a brief remark under the injected turn it answers.
 */
class CoordinatorTranscriptTest {

    /**
     * A finished run's trace as 0.3.4 wrote it to disk (`TraceCache`, one file per run, `CursorJson`): the
     * coordinator's `SendMessage` call filed under `Other` — its name was not known — with nothing read off its
     * arguments, the arguments themselves dropped as every call's are (`ToolCall.output`), and the coordinator's
     * note as the reply. Fields at their defaults are not written, which is why the call is these six keys.
     */
    private val staleTraceJson = """
        {"runId":"run-old","createdAtMillis":1789341000000,"items":[
          {"type":"assistant","id":"asst-run-old-0","markdown":"The release worker shipped v0.3.3 and bumped main to 0.3.4."},
          {"type":"activity","id":"activity-run-old-1","steps":[
            {"type":"thinking","text":"Route the release and tell Bennett."},
            {"type":"tool","callId":"c1","name":"sendToAgent","kind":"Other","status":"completed","summary":""},
            {"type":"tool","callId":"c2","name":"SendMessage","kind":"Other","status":"completed","summary":""}
          ]},
          {"type":"footer","id":"run-run-old-2","runId":"run-old","status":"FINISHED","durationMs":81000,"branches":[]}
        ]}
    """.trimIndent()

    private fun staleTrace(): CachedTrace = CursorJson.decodeFromString(CachedTrace.serializer(), staleTraceJson)

    private fun calls(items: List<TimelineItem>) = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }

    @Test
    fun `the stale shape is what an unknown tool serialised to, and it decodes`() {
        // The call as 0.3.4's mapper built it for a name it did not know: kind Other, nothing described, no payload.
        val asWritten = ToolCall(callId = "c2", name = "SendMessage", kind = ToolKind.Other, status = "completed", summary = "")
        val encoded = CursorJson.encodeToString(ActivityStep.serializer(), asWritten)
        assertThat(encoded).isEqualTo("""{"type":"tool","callId":"c2","name":"SendMessage","kind":"Other","status":"completed","summary":""}""")
        val trace = staleTrace()
        assertThat(trace.items).hasSize(3)
        assertThat(calls(trace.items).getValue("c2").kind).isEqualTo(ToolKind.Other)
        assertThat(calls(trace.items).getValue("c2").payload).isNull()
    }

    @Test
    fun `a cached call is re-read on its way to the screen, whatever kind an earlier build filed it under`() {
        val items = staleTrace().items
        // As stored: not a coordinator's group, its message a bare row. By content it is a coordinator's all the same.
        assertThat(items.filterIsInstance<ActivityGroup>().single().isCoordination).isFalse()
        assertThat(CoordinatorTranscript.hasCoordinatorContent(items)).isTrue()
        assertThat(CoordinatorTranscript.evidence(items)).containsExactly("sendToAgent", "SendMessage").inOrder()
        assertThat(CoordinatorTranscript.needsRefresh(items)).isTrue()

        val shown = CoordinatorTranscript.present(items, coordinatorMode = true)
        val group = shown.filterIsInstance<ActivityGroup>().single()
        assertThat(group.isCoordination).isTrue()
        assertThat(group.isWorkGrouped).isFalse()
        val message = group.calls.first { it.callId == "c2" }
        assertThat(message.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(message.payload).isEqualTo(ToolPayload.CoordinatorMessage("", missing = true))
        assertThat(message.action).isEqualTo("Sent message")
        val messaged = group.calls.first { it.callId == "c1" }
        assertThat(messaged.kind).isEqualTo(ToolKind.Coordinator)
        assertThat(messaged.payload).isNull()
        assertThat(messaged.action).isEqualTo("Messaged agent")
        // The other items are untouched, and a group with nothing to re-read is the same instance.
        assertThat(shown.filterIsInstance<AssistantMessage>()).isEqualTo(items.filterIsInstance<AssistantMessage>())
        val plain = ActivityGroup("g", listOf(ToolCall("r", "read_file", ToolKind.Read, "completed", "A.kt")))
        assertThat(CoordinatorTranscript.present(listOf(plain), coordinatorMode = false).single()).isSameInstanceAs(plain)
    }

    @Test
    fun `a message kept with its body by an earlier build keeps it, and a message the stream truncated is not a refresh`() {
        val kept = ToolCall("k", "send_to_user", ToolKind.Other, "completed", "PR #215 is merged.", detail = "PR #215 is merged.")
        assertThat(CoordinatorTranscript.reinterpret(kept).payload).isEqualTo(ToolPayload.CoordinatorMessage("PR #215 is merged."))
        assertThat(CoordinatorTranscript.needsRefresh(listOf(ActivityGroup("g", listOf(kept))))).isFalse()
        val truncated = ToolCall("t", "sendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("", missing = true), truncated = ToolTruncation(args = true))
        assertThat(CoordinatorTranscript.needsRefresh(listOf(ActivityGroup("g", listOf(truncated))))).isFalse()
        val failed = ToolCall("f", "SendMessage", ToolKind.Other, "completed", "", isError = true)
        assertThat(CoordinatorTranscript.reinterpret(failed).payload).isNull()
        assertThat(CoordinatorTranscript.reinterpret(failed).kind).isEqualTo(ToolKind.Coordinator)
    }

    @Test
    fun `a chat is a coordinator's by content when the list never classified it`() {
        val items = staleTrace().items
        val decision = TranscriptDiagnostics.decide(agent = null, items = items, recordProjectMode = false)
        assertThat(decision.coordinatorMode).isTrue()
        assertThat(decision.listProject).isFalse()
        assertThat(decision.recordProjectMode).isFalse()
        assertThat(decision.content).isTrue()
        assertThat(decision.evidence).containsExactly("sendToAgent", "SendMessage").inOrder()
        // An agent's chat with no such tool is not, whatever the list does not say.
        val plain = listOf(ActivityGroup("g", listOf(ToolCall("r", "read_file", ToolKind.Read, "completed", "A.kt"))), AssistantMessage("a", "Done."))
        assertThat(TranscriptDiagnostics.decide(agent = null, items = plain, recordProjectMode = false).coordinatorMode).isFalse()
        // The record's word alone is enough too.
        assertThat(TranscriptDiagnostics.decide(agent = null, items = plain, recordProjectMode = true).coordinatorMode).isTrue()
    }

    // --- Folding a brief remark under the injected turn it answers.

    private fun notice(id: String, title: String = "Subagent completed", summary: String = "Land merge train and prep release", body: String? = null) =
        SystemNotification(id, SystemNotification.Kind.Subagent, title, summary, body, NoticeTone.Success, raw = "<system_notification>…</system_notification>")

    private fun footer(id: String) = RunFooter(id, "run-$id", RunStatus.FINISHED, 45_000, emptyList())

    private fun sendMessage(id: String) = ActivityGroup("g-$id", listOf(ToolCall(id, "sendMessage", ToolKind.Coordinator, "completed", "Done", payload = ToolPayload.CoordinatorMessage("All three workers are done."))))

    @Test
    fun `a brief remark after an injected turn folds under the turn's row, and only in a coordinator's chat`() {
        val items = listOf(
            notice("n1"),
            ActivityGroup("g1", listOf(ThinkingBlock("Nothing to do here."))),
            AssistantMessage("a1", "Noted; the release worker is done and nothing else needs doing."),
            footer("f1"),
            notice("n2", title = "GitHub notification", summary = "#59 · synchronize · cursor[bot]"),
            AssistantMessage("a2", "Acknowledged."),
            footer("f2"),
        )
        val shown = CoordinatorTranscript.present(items, coordinatorMode = true)
        assertThat(shown.map { it.id }).containsExactly("n1", "g1", "f1", "n2", "f2").inOrder()
        assertThat((shown[0] as SystemNotification).narration).isEqualTo("Noted; the release worker is done and nothing else needs doing.")
        assertThat((shown[0] as SystemNotification).hasDetails).isTrue()
        assertThat((shown[3] as SystemNotification).narration).isEqualTo("Acknowledged.")
        // An agent's chat shows the same items as they are.
        assertThat(CoordinatorTranscript.present(items, coordinatorMode = false)).isEqualTo(items)
    }

    @Test
    fun `a turn that spoke to the user, wrote more than a remark, or is still writing keeps its rows`() {
        val long = "x".repeat(CoordinatorTranscript.REMARK_MAX_CHARS + 1)
        val items = listOf(
            notice("n1"), sendMessage("s1"), AssistantMessage("a1", "Noted."), footer("f1"),
            notice("n2"), AssistantMessage("a2", long), footer("f2"),
            notice("n3"), AssistantMessage("a3", "Still going", isStreaming = true),
            notice("n4"), AssistantMessage("a4", "One.\nTwo.\nThree.\nFour.\nFive."), footer("f4"),
        )
        val shown = CoordinatorTranscript.present(items, coordinatorMode = true)
        assertThat(shown.map { it.id }).isEqualTo(items.map { it.id })
        assertThat(shown.filterIsInstance<SystemNotification>().all { it.narration == null }).isTrue()
    }

    @Test
    fun `the user's own words after a notification start a turn of their own, and several notices fold under the last`() {
        val items = listOf(
            notice("n1"), notice("n1b", title = "Worker completed", summary = "Build Projects under Extended mode"),
            AssistantMessage("a1", "Both are done."), footer("f1"),
            notice("n2"), UserMessage("n2-prompt", "Hold the release until I've checked the keyboard fix on my phone."),
            AssistantMessage("a2", "Holding."), footer("f2"),
        )
        val shown = CoordinatorTranscript.present(items, coordinatorMode = true)
        assertThat(shown.map { it.id }).containsExactly("n1", "n1b", "f1", "n2", "n2-prompt", "a2", "f2").inOrder()
        assertThat((shown[0] as SystemNotification).narration).isNull()
        assertThat((shown[1] as SystemNotification).narration).isEqualTo("Both are done.")
        // The notice before the user's words has nothing folded: the reply after them answers the user.
        assertThat((shown[3] as SystemNotification).narration).isNull()
    }
}
