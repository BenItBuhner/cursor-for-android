package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** The account's record of a chat cut into turns, paged from its end, and rendered like a replayed run. */
class HeadlessTranscriptTest {

    /** A record of [turns] turns, each a prompt, a thought, one read with its result and a reply: five steps a turn. */
    private class FakeRecord(turns: Int) : ConversationRecordApi {
        val steps: List<HeadlessStep> = (1..turns).flatMap { t ->
            listOf(
                HeadlessStep(userMessage = "Prompt $t"),
                HeadlessStep(thinking = "Thinking about $t."),
                HeadlessStep(toolCall = HeadlessToolCall("call-$t", "read_file", buildJsonObject { put("path", JsonPrimitive("src/File$t.kt")) })),
                HeadlessStep(toolResult = HeadlessToolResult("call-$t", buildJsonObject { put("content", JsonPrimitive("text of $t")); put("totalLines", JsonPrimitive(3)) })),
                HeadlessStep(text = "Reply $t"),
            )
        }
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun state(agentId: String): RecordState = RecordState(steps.count { it.userMessage != null }, emptyList(), pendingToolCalls = 0, isRootProject = false, numPriorInteractionUpdates = 0L, rewindEpoch = 0L)

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            requests += startIndex to limit
            return HeadlessPage(steps.drop(startIndex).take(limit), startIndex, steps.size)
        }
    }

    @Test
    fun `the record is cut into turns at its prompts`() {
        val turns = HeadlessTranscript.split(FakeRecord(3).steps)
        assertThat(turns.map { it.prompt }).containsExactly("Prompt 1", "Prompt 2", "Prompt 3").inOrder()
        assertThat(turns.all { it.steps.size == 4 }).isTrue()
    }

    @Test
    fun `the last turns are read from the record's end, a page at a time, no further back than needed`() = runBlocking<Unit> {
        val record = FakeRecord(40)

        val turns = HeadlessTranscript.tailTurns(record, "bc-1", count = 3, pageSize = 12)!!

        assertThat(turns.map { it.prompt }).containsExactly("Prompt 38", "Prompt 39", "Prompt 40").inOrder()
        // A probe for the length, then pages back from the end until four prompts bound three whole turns.
        assertThat(record.requests.first()).isEqualTo(0 to 1)
        assertThat(record.requests.drop(1).map { it.first }).containsExactly(188, 176).inOrder()
        assertThat(record.requests.drop(1).all { it.second == 12 }).isTrue()
    }

    @Test
    fun `a turn renders like a replayed run, with the run's own footer`() {
        val turn = HeadlessTranscript.split(FakeRecord(1).steps).single()
        val run = RunDto("run-1", "bc-1", "FINISHED", "2026-04-13T18:30:00.000Z", "2026-04-13T18:31:00.000Z", durationMs = 60_000, result = "Reply 1")

        val items = HeadlessTranscript.trace(turn, run)

        assertThat(items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        val group = items[0] as ActivityGroup
        assertThat((group.steps[0] as ThinkingBlock).text).isEqualTo("Thinking about 1.")
        val call = group.calls.single()
        assertThat(call.callId).isEqualTo("call-1")
        assertThat(call.summary).isEqualTo("File1.kt")
        assertThat(call.isRunning).isFalse()
        assertThat((call.payload as ToolPayload.FileContent).content).isEqualTo("text of 1")
        assertThat((items[1] as AssistantMessage).markdown).isEqualTo("Reply 1")
        val footer = items[2] as RunFooter
        assertThat(footer.runId).isEqualTo("run-1")
        assertThat(footer.status).isEqualTo(RunStatus.FINISHED)
        assertThat(footer.durationMs).isEqualTo(60_000)
    }
}
