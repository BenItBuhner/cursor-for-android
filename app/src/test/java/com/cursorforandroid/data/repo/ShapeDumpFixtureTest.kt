package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptDiagnostics
import com.cursorforandroid.domain.TranscriptLoadDiagnostics
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.ShapeFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * A record in the shapes a shape dump describes (`shape_dump_0325.txt`, the format the transcript diagnostics export
 * for the newest turns: see `RecordShapes`), read back through the app's own parser with the values a test needs put
 * in, and rendered: a `SendMessage` whose streamed pieces the record cut short is shown with the body read out of
 * them and marked recovered; one whose pieces the record filed under another id gets them from there; a turn the
 * record ends on an error carries the server's reason; and the dump the diagnostics write of it all is the fixture
 * itself, line for line — so a dump from a device this build misreads becomes a test of that record's shape.
 */
class ShapeDumpFixtureTest {

    private val dump = CoordinatorFixtures.text("shape_dump_0325.txt")

    private val prompt = "So where we at rn"
    private val narration = "Bennett wants the phone renders checked; the phone worker has the Fold8 pair on its list."
    private val pieces = listOf(
        """{"text":{"content":"The phone worker has the Fold8""",
        """ and Fold8 Ultra on its list and is rendering""",
        """ app-demo shots on every device; first frames in""",
    )
    private val github = "<system_notification source=\"github\" pr=\"https://github.com/BenItBuhner/revenue-scaling-pipeline/pull/171\" action=\"synchronize\" sender=\"cursor[bot]\">\nA subscribed pull request changed. Use the linked PR for details if needed.\n</system_notification>"
    private val remark = "Noted; nothing for Bennett in this one."
    private val prompt3 = "I'm in it for all products and renders, by the way. Nothing looks realistic enough yet. Keep on iterating until they are indiscernible from the human eye."
    private val strays = listOf("""{"text":{"content":"Rendering the""", """ Fold8 shots now."}}""")
    private val error = "Model provider returned an overloaded error (529). The turn was not completed."

    /** The values the dump's lengths stand for, by line and path; everything else is a placeholder of the dump's length. */
    private fun values(line: Int, path: String): JsonElement? {
        val index = ShapeFixtures.lines(dump)[line].index
        val value: String? = when (path) {
            "humanMessage.text" -> when (index) { 4021, 4040 -> prompt; 4029 -> github; 4033 -> prompt3; else -> null }
            "humanMessage.createdAt" -> "1789600000000"
            "text" -> when (index) { 4022 -> narration; 4030 -> remark; else -> null }
            "toolCall.toolCallId", "finalToolResult.toolCallId" -> if (index < 4029) "toolu_01SendPieces" else "toolu_01SendNamed0"
            "toolCall.modelCallId" -> "model_call_000000000001a"
            "streamedBackToolCall.modelCallId" -> "model_call_000000000001b"
            "toolCall.rawArgs" -> when (index) { 4023 -> pieces[0]; 4024 -> pieces[1]; 4025 -> pieces[2]; else -> null }
            "streamedBackToolCall.rawArgs" -> when (index) { 4035 -> strays[0]; 4036 -> strays[1]; else -> null }
            "status.message" -> "finished"
            "error.message" -> error
            else -> null
        }
        return value?.let { JsonPrimitive(it) }
    }

    private val steps = ShapeFixtures.steps(dump, ::values)
    private val turns = HeadlessTranscript.split(steps)

    private fun messages(turn: HeadlessTranscript.Turn, key: String) = HeadlessTranscript.body(turn, key)
        .filterIsInstance<ActivityGroup>().flatMap { it.calls }.map { CoordinatorTranscript.reinterpret(it) }
        .mapNotNull { call -> (call.payload as? ToolPayload.CoordinatorMessage)?.let { call to it } }

    @Test
    fun `the dump's lengths and values make the record the app reads, four turns of it`() {
        assertThat(steps.map { it.shape!!.index }).isEqualTo(ShapeFixtures.lines(dump).map { it.index })
        assertThat(turns).hasSize(4)
        assertThat(turns.map { it.prompt }).containsExactly(prompt, github, prompt3, prompt).inOrder()
        assertThat(turns.all { it.projectMode }).isTrue()
        // The placeholders read as the dump said they would: a prompt of its length, no value of anyone's.
        assertThat(turns[0].prompt).hasLength(17)
    }

    @Test
    fun `a streamed message the record cut short is shown from its pieces, marked recovered`() {
        val (call, payload) = messages(turns[0], "rec-4021").single()
        assertThat(call.callId).isEqualTo("toolu_01SendPieces")
        assertThat(payload.message).isEqualTo("The phone worker has the Fold8 and Fold8 Ultra on its list and is rendering app-demo shots on every device; first frames in")
        assertThat(payload.recovered).isTrue()
        assertThat(payload.missing).isFalse()
        // Its row is the message, and the turn says it has the coordinator's word — read leniently.
        val items = HeadlessTranscript.body(turns[0], "rec-4021")
        assertThat(CoordinatorTranscript.hasUserMessage(items)).isTrue()
        assertThat(CoordinatorTranscript.hasRecoveredMessage(items)).isTrue()
        val rows = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(1)
    }

    @Test
    fun `a message whose pieces the record filed under another id is read from those, which are then no call of their own`() {
        val (call, payload) = messages(turns[2], "rec-4033").single()
        assertThat(call.callId).isEqualTo("toolu_01SendNamed0")
        assertThat(payload).isEqualTo(ToolPayload.CoordinatorMessage("Rendering the Fold8 shots now.", recovered = true))
        val calls = HeadlessTranscript.body(turns[2], "rec-4033").filterIsInstance<ActivityGroup>().flatMap { it.calls }
        assertThat(calls.map { it.callId }).containsExactly("toolu_01SendNamed0")
    }

    @Test
    fun `a turn the record ends on an error keeps the server's reason for the run's footer, and is no failure on its own`() {
        // The record's error step is the server's account of what went wrong, not of how the run ended: the turn's
        // body says nothing of failure, and the message is kept for the footer of a run the server says failed.
        val failed = HeadlessTranscript.body(turns[3], "rec-4040")
        assertThat(failed.filterIsInstance<NoticeCard>()).isEmpty()
        assertThat(failed.filterIsInstance<com.cursorforandroid.domain.RunFooter>()).isEmpty()
        assertThat(HeadlessTranscript.errorMessage(turns[3])).isEqualTo(error)
        assertThat(HeadlessTranscript.errorMessage(turns[1])).isNull()
        val silent = HeadlessTranscript.body(turns[1], "rec-4029")
        assertThat(silent.filterIsInstance<NoticeCard>()).isEmpty()
        assertThat(silent.map { it::class.simpleName }).containsExactly("AssistantMessage")
        // The run's own record decides: a failed run's trace closes on its status, the record's error as the reason.
        val run = com.cursorforandroid.data.api.dto.RunDto("run-4040", "bc-revenue", status = "ERROR", createdAt = "2026-09-16T11:00:00Z", updatedAt = "2026-09-16T11:02:00Z", durationMs = 120_000)
        val traced = HeadlessTranscript.trace(turns[3], run)
        assertThat(traced.filterIsInstance<NoticeCard>()).isEmpty()
        val footer = traced.filterIsInstance<com.cursorforandroid.domain.RunFooter>().single()
        assertThat(footer.status).isEqualTo(RunStatus.ERROR)
        assertThat(footer.reason).isEqualTo(error)
        // The same turn under a run the server says finished: the error was on the way, not the end.
        val finished = HeadlessTranscript.trace(turns[3], run.copy(status = "FINISHED"))
        assertThat(finished.filterIsInstance<com.cursorforandroid.domain.RunFooter>().single().let { it.status to it.reason }).isEqualTo(RunStatus.FINISHED to null)
    }

    @Test
    fun `the diagnostics write the dump of these turns, and it is the fixture line for line`() {
        val shapes = turns.mapIndexed { i, turn -> HeadlessTranscript.shape(turn, turn.promptShape!!.index) }
        val load = TranscriptLoadDiagnostics(
            attached = 1, paused = false, fetched = true, fetchedAtIso = null, messages = 0, prompts = 0, runsLoaded = 4, runsComplete = true,
            hasOlderCursor = false, runOrder = null, latestFetchedById = false, window = 4, windowStart = 0, chatTurns = 4, runs = emptyList(),
            traceQueue = 0, traceInFlight = 0, traceWorkerRunning = false, expiredBeforeIso = null, expiredRuns = 0, failedTraces = 0,
            liveRunId = null, following = false, liveStream = null, lastError = null, transcriptError = null, transcriptUnavailable = false,
            source = "record", shapes = shapes,
        )
        val report = TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input("0.3.26", "2026-09-16T16:00:00Z", extendedMode = true, agentId = "bc-revenue", agent = null, state = TranscriptDiagnostics.State(listOf(UserMessage("u", prompt))), load = load),
        )
        val expected = dump.lines().filter { it.isNotBlank() }
        val actual = report.lines()
        for (line in expected) assertThat(actual).contains(line)
        // In the dump's order, as one block.
        val start = actual.indexOf(expected.first())
        assertThat(actual.subList(start, start + expected.size)).isEqualTo(expected)
        // Nothing anyone wrote is in the report.
        assertThat(report).doesNotContain("Fold8")
        assertThat(report).doesNotContain("Bennett")
        assertThat(report).doesNotContain("overloaded")
        assertThat(report).doesNotContain("toolu_01SendPieces")
    }
}
