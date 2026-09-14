package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The transcript diagnostics: every item and call accounted for by kind and shape, nothing of their text in it. */
class TranscriptDiagnosticsTest {

    private val secret = "Bennett has the explanation plus the note that his recording was the reference app"
    private val items = listOf(
        UserMessage("u1", "Start this Project, and keep the API key hunter2 out of it."),
        SystemNotification("n1", SystemNotification.Kind.Subagent, "Subagent completed", "Land merge train and prep release", "The merge train landed.", NoticeTone.Success, raw = "<system_notification>…</system_notification>", agentId = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671"),
        AssistantMessage("a1", "The release worker shipped v0.3.3."),
        ActivityGroup(
            "g1",
            listOf(
                ThinkingBlock("Route the release and tell Bennett."),
                ToolCall("c1", "sendToAgent", ToolKind.Coordinator, "completed", "bc-24e35e9f", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")), text = "Land it."), argKeys = listOf("toolCallId", "agentId", "message", "delivery", "title"), linkedAgentIds = listOf("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")),
                ToolCall("c2", "SendMessage", ToolKind.Other, "completed", ""),
                ToolCall("c3", "sendMessage", ToolKind.Coordinator, "completed", secret.take(45) + "...", detail = secret, payload = ToolPayload.CoordinatorMessage(secret), argKeys = listOf("text")),
                ToolCall("c4", "sendMessage", ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("", missing = true), truncated = ToolTruncation(args = true)),
                ToolCall("c5", "edit", ToolKind.Edit, "completed", "notes.md", detail = "notes.md", linesAdded = 2, linesRemoved = 1, argKeys = listOf("path")),
            ),
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 81_000, emptyList()),
    )

    private fun render(agent: Agent? = null, recordProjectMode: Boolean = false) = TranscriptDiagnostics.render(
        TranscriptDiagnostics.Input(
            appVersion = "0.3.7",
            nowIso = "2026-09-14T04:00:00Z",
            extendedMode = true,
            agentId = "bc-bae107cb-2562-40b2-b814-4f8eca874668",
            agent = agent,
            state = TranscriptDiagnostics.State(items, runStatus = RunStatus.FINISHED, hasOlder = true, recordProjectMode = recordProjectMode),
        ),
    )

    @Test
    fun `the report carries kinds, names, statuses, payload shapes and argument keys, and none of the text`() {
        val report = render()
        assertThat(report).contains("Cursor for Android 0.3.7 · transcript diagnostics · 2026-09-14T04:00:00Z")
        assertThat(report).contains("mode=extended")
        assertThat(report).contains("chat=…874668 row=not in list")
        assertThat(report).contains("classification: COORDINATOR listProject=false recordProjectMode=false content=true evidence=sendToAgent,SendMessage,sendMessage")
        assertThat(report).contains("presented: items=5 folded=0 staleMessages=1 rows=7 stretches=2 messages=3")
        assertThat(report).contains("  user chars=59 attachments=0")
        assertThat(report).contains("  notification kind=Subagent title=\"Subagent completed\" tone=Success summaryChars=33 bodyChars=23 agent=…74f671")
        assertThat(report).contains("  assistant chars=34")
        assertThat(report).contains("  activity steps=6 coordination=true grouped=false")
        assertThat(report).contains("    thinking chars=35")
        assertThat(report).contains("    tool sendToAgent · Coordinator · completed · worker_action(messaged,workers=1,reported=false) · args=[toolCallId,agentId,message,delivery,title] · linked=1 · truncated=-")
        assertThat(report).contains("    tool SendMessage · Other→Coordinator · completed · coordinator_message(missing) · args=[] · linked=0 · truncated=-")
        assertThat(report).contains("    tool sendMessage · Coordinator · completed · coordinator_message(${secret.length} chars) · args=[text] · linked=0 · truncated=-")
        assertThat(report).contains("    tool sendMessage · Coordinator · completed · coordinator_message(missing) · args=[] · linked=0 · truncated=args")
        assertThat(report).contains("    tool edit · Edit · completed · - · args=[path] · linked=0 · truncated=-")
        assertThat(report).contains("  footer status=FINISHED duration=81000 branches=0")
        // Nothing the user or the coordinator wrote, and no whole id.
        assertThat(report).doesNotContain("hunter2")
        assertThat(report).doesNotContain("Bennett")
        assertThat(report).doesNotContain("Land merge train")
        assertThat(report).doesNotContain("notes.md")
        assertThat(report).doesNotContain("bc-24e35e9f-1a2d")
        assertThat(report).doesNotContain("bc-bae107cb-2562")
    }

    @Test
    fun `the decision names each of its words, and a chat never opened says so`() {
        assertThat(render(recordProjectMode = true)).contains("classification: COORDINATOR listProject=false recordProjectMode=true content=true")
        val none = TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = false, agentId = null, agent = null, state = null))
        assertThat(none).contains("chat: none opened this session")
        assertThat(none).contains("mode=default")
        val unloaded = TranscriptDiagnostics.render(TranscriptDiagnostics.Input("0.3.7", "2026-09-14T04:00:00Z", extendedMode = false, agentId = "bc-1234567890", agent = null, state = null))
        assertThat(unloaded).contains("state: none (the chat has not been loaded)")
    }
}
