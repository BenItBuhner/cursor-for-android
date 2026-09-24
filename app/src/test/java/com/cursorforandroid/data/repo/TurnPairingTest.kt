package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [TurnPairing]: every prompt of the transcript, in the transcript's order, exactly once, with the run the evidence
 * gives it — and by position only where the evidence runs out. Bennett's Polymarket Project (2026-09-21: 298
 * prompts, 324 runs, 26 of them no prompt started) is the fixture: laid over by position, its four newest replies
 * had no prompt between them, the prompts twenty-six turns up.
 */
class TurnPairingTest {

    private val firstAt = 1_800_000_000_000L - PromptlessFixture.TURNS * LongProject.TURN_SPACING_MS
    private val turns = PromptlessFixture.turns(firstAt)
    private val messages = LongProject.v0Transcript(turns)
    private val prompted = turns.filterNot { it.promptless }

    private fun runs(withResults: Boolean) = turns.map { t ->
        RunDto(t.runId, LongProject.AGENT_ID, t.status, LongProject.iso(t.startedAt), LongProject.iso(t.endedAt), t.durationMs, result = if (withResults) LongProject.result(t) else null)
    }

    @Test
    fun `the fixture is Bennett's shape`() {
        assertThat(turns).hasSize(324)
        assertThat(turns.count { it.promptless }).isEqualTo(26)
        assertThat(messages.count { it.type == "user_message" }).isEqualTo(298)
        assertThat(turns.last().isUser).isTrue()
        assertThat(turns.last().status).isEqualTo("RUNNING")
    }

    @Test
    fun `by position the newest prompts sit twenty-six turns up and the newest runs have none - the frame of 2026-09-21`() {
        val positional = TurnPairing.positional(messages, runs(withResults = false))
        // The newest 26 runs carry no prompt, and the newest prompt is drawn above a run from 26 turns before its own.
        assertThat(positional.takeLast(26).all { it.prompt == null && it.run != null }).isTrue()
        val newest = positional.last { it.prompt != null }
        assertThat(newest.prompt!!.id).isEqualTo("${turns.last().runId}-u")
        assertThat(newest.run!!.id).isNotEqualTo(turns.last().runId)
    }

    @Test
    fun `with the runs' results every prompt pairs with its own run, in the transcript's order, and the live prompt heads the live run`() {
        val pairing = TurnPairing.pair(messages, runs(withResults = true))
        assertEveryPromptOnceInOrder(pairing)
        // Each prompt started the run the fixture says it did — an injected turn by the time it carries, the user's by
        // the result being the reply — and the runs no prompt started stand on their own.
        prompted.forEachIndexed { i, turn -> assertThat(pairing.runOf[i]?.id).isEqualTo(turn.runId) }
        assertThat(pairing.count(TurnPairing.Evidence.TIMESTAMP)).isEqualTo(prompted.count { !it.isUser })
        assertThat(pairing.count(TurnPairing.Evidence.RESULT) + pairing.count(TurnPairing.Evidence.LIVE) + pairing.count(TurnPairing.Evidence.POSITION)).isEqualTo(prompted.count { it.isUser })
        assertThat(pairing.promptless).isEqualTo(26)
        assertThat(pairing.runless).isEqualTo(0)
        assertThat(pairing.evidenceOf.getValue(turns.last().runId)).isEqualTo(TurnPairing.Evidence.LIVE)
        // The runs no prompt started are the fixture's prompt-less ones, each between its neighbours.
        assertThat(pairing.turns.filter { it.prompt == null }.map { it.run!!.id }).containsExactlyElementsIn(turns.filter { it.promptless }.map { it.runId }).inOrder()
    }

    @Test
    fun `without results the prompts still all show in order, the live prompt above the live run, the rest by position`() {
        val pairing = TurnPairing.pair(messages, runs(withResults = false))
        assertEveryPromptOnceInOrder(pairing)
        assertThat(pairing.runless).isEqualTo(0)
        assertThat(pairing.promptless).isEqualTo(26)
        // The chat's newest prompt and its run under way: each other's.
        val last = pairing.turns.last { it.prompt != null }
        assertThat(last.run!!.id).isEqualTo(turns.last().runId)
        assertThat(last.evidence).isEqualTo(TurnPairing.Evidence.LIVE)
        assertThat(pairing.turns.last()).isSameInstanceAs(last)
    }

    @Test
    fun `this device's own prompts name their runs and pull the transcript's newest copy of their words to them`() {
        val runs = runs(withResults = false)
        // The newest three prompted turns, sent from here: their runs are known.
        val mine = prompted.takeLast(3)
        val echoes = mine.associate { it.runId to TurnPairing.normalize(it.prompt) }
        val pairing = TurnPairing.pair(messages, runs, echoes)
        assertEveryPromptOnceInOrder(pairing)
        mine.forEach { turn -> assertThat(pairing.evidenceOf.getValue(turn.runId)).isAnyOf(TurnPairing.Evidence.ECHO, TurnPairing.Evidence.LIVE, TurnPairing.Evidence.TIMESTAMP) }
        mine.forEach { turn -> assertThat(pairing.runOf.getValue(prompted.indexOf(turn)).id).isEqualTo(turn.runId) }
    }

    @Test
    fun `a prompt whose run is not in hand is shown with its replies and no run, and a run the transcript lacks a prompt for stands alone`() {
        val messages = listOf(
            V0ConversationMessageDto("u1", "user_message", "First"),
            V0ConversationMessageDto("a1", "assistant_message", "Done with the first."),
            V0ConversationMessageDto("u2", "user_message", "Second"),
            V0ConversationMessageDto("a2", "assistant_message", "Done with the second."),
            V0ConversationMessageDto("u3", "user_message", "Third, just sent"),
        )
        val runs = listOf(
            RunDto("r1", "a", "FINISHED", "2026-09-21T09:00:00Z", "2026-09-21T09:01:00Z", 60_000, result = "Done with the first."),
            RunDto("r-injected", "a", "FINISHED", "2026-09-21T09:02:00Z", "2026-09-21T09:03:00Z", 60_000, result = "Noted the worker's report."),
            RunDto("r2", "a", "FINISHED", "2026-09-21T09:04:00Z", "2026-09-21T09:05:00Z", 60_000, result = "Done with the second."),
        )
        val pairing = TurnPairing.pair(messages, runs)
        assertThat(pairing.turns.map { (it.prompt?.id ?: "-") to (it.run?.id ?: "-") }).containsExactly("u1" to "r1", "-" to "r-injected", "u2" to "r2", "u3" to "-").inOrder()
        assertThat(pairing.turns.last().replies).isEmpty()
        assertThat(pairing.runless).isEqualTo(1)
    }

    @Test
    fun `the same words said twice pair in order, and the oldest prompts go without a run while the list is not complete`() {
        val messages = (1..4).flatMap { i -> listOf(V0ConversationMessageDto("u$i", "user_message", "continue"), V0ConversationMessageDto("a$i", "assistant_message", "Continuing.")) }
        // Only the newest two runs fetched.
        val runs = listOf(
            RunDto("r3", "a", "FINISHED", "2026-09-21T09:04:00Z", "2026-09-21T09:05:00Z", 60_000, result = "Continuing."),
            RunDto("r4", "a", "FINISHED", "2026-09-21T09:06:00Z", "2026-09-21T09:07:00Z", 60_000, result = "Continuing."),
        )
        val pairing = TurnPairing.pair(messages, runs, runsComplete = false)
        assertThat(pairing.turns.map { (it.prompt?.id ?: "-") to (it.run?.id ?: "-") }).containsExactly("u1" to "-", "u2" to "-", "u3" to "r3", "u4" to "r4").inOrder()
    }

    @Test
    fun `an anchor never breaks the transcript's order`() {
        // A later run whose result happens to be an earlier prompt's reply cannot take that prompt once a run before it took a later one.
        val messages = listOf(
            V0ConversationMessageDto("u1", "user_message", "One"), V0ConversationMessageDto("a1", "assistant_message", "Same words here, long enough to match on."),
            V0ConversationMessageDto("u2", "user_message", "Two"), V0ConversationMessageDto("a2", "assistant_message", "Something else entirely for the second."),
        )
        val runs = listOf(
            RunDto("r1", "a", "FINISHED", "2026-09-21T09:00:00Z", "2026-09-21T09:01:00Z", 60_000, result = "Something else entirely for the second."),
            RunDto("r2", "a", "FINISHED", "2026-09-21T09:02:00Z", "2026-09-21T09:03:00Z", 60_000, result = "Same words here, long enough to match on."),
        )
        val pairing = TurnPairing.pair(messages, runs)
        // Whatever the words say, the order holds: every prompt once, in the transcript's order, the runs in theirs —
        // a prompt the crossed words leave without a run is shown all the same, and the run without a prompt too.
        assertThat(pairing.turns.mapNotNull { it.prompt?.id }).containsExactly("u1", "u2").inOrder()
        assertThat(pairing.turns.mapNotNull { it.run?.id }).containsExactly("r1", "r2").inOrder()
        assertThat(pairing.turns.map { (it.prompt?.id ?: "-") to (it.run?.id ?: "-") }).containsExactly("u1" to "-", "u2" to "r1", "-" to "r2").inOrder()
    }

    /** Bennett, 2026-09-23: a worker's report the coordinator is answering, its prompt not in the transcript yet. */
    private val reportUnderWay = listOf(
        V0ConversationMessageDto("u1", "user_message", "Where are we on the scanner?"),
        V0ConversationMessageDto("a1", "assistant_message", "Day two: forty markets read."),
        V0ConversationMessageDto("u2", "user_message", "The replies vanish again."),
        V0ConversationMessageDto("a2", "assistant_message", "A worker is on it."),
    )

    private fun reportRuns(results: Boolean) = listOf(
        RunDto("r1", "a", "FINISHED", "2026-09-23T09:00:00Z", "2026-09-23T09:01:00Z", 60_000, result = "Day two: forty markets read.".takeIf { results }),
        RunDto("r2", "a", "FINISHED", "2026-09-23T09:10:00Z", "2026-09-23T09:11:00Z", 60_000, result = "A worker is on it.".takeIf { results }),
        RunDto("r-report", "a", "RUNNING", "2026-09-23T09:11:02Z", "2026-09-23T09:11:02Z", null),
    )

    @Test
    fun `a worker's report under way that the transcript has no prompt for takes no prompt's run, results or not`() {
        for (results in listOf(false, true)) {
            val pairing = TurnPairing.pair(reportUnderWay, reportRuns(results))
            assertThat(pairing.turns.map { (it.prompt?.id ?: "-") to (it.run?.id ?: "-") }).containsExactly("u1" to "r1", "u2" to "r2", "-" to "r-report").inOrder()
            assertThat(pairing.evidenceOf.getValue("r2")).isEqualTo(if (results) TurnPairing.Evidence.RESULT else TurnPairing.Evidence.POSITION)
        }
    }

    @Test
    fun `past a run no prompt started, the newest prompt still heads its run under way where the runs say their replies`() {
        val messages = reportUnderWay + V0ConversationMessageDto("u3", "user_message", "Ship the fix.")
        val runs = listOf(
            RunDto("r1", "a", "FINISHED", "2026-09-23T09:00:00Z", "2026-09-23T09:01:00Z", 60_000, result = "Day two: forty markets read."),
            RunDto("r2", "a", "FINISHED", "2026-09-23T09:10:00Z", "2026-09-23T09:11:00Z", 60_000, result = "A worker is on it."),
            RunDto("r-silent", "a", "FINISHED", "2026-09-23T09:11:02Z", "2026-09-23T09:11:05Z", 3_000),
            RunDto("r3", "a", "RUNNING", "2026-09-23T09:20:00Z", "2026-09-23T09:20:00Z", null),
        )
        val pairing = TurnPairing.pair(messages, runs)
        assertThat(pairing.turns.map { (it.prompt?.id ?: "-") to (it.run?.id ?: "-") }).containsExactly("u1" to "r1", "u2" to "r2", "-" to "r-silent", "u3" to "r3").inOrder()
        assertThat(pairing.evidenceOf.getValue("r3")).isEqualTo(TurnPairing.Evidence.LIVE)
    }

    private fun assertEveryPromptOnceInOrder(pairing: TurnPairing.Pairing) {
        val expected = messages.filter { it.type == "user_message" }.map { it.id }
        assertThat(pairing.turns.mapNotNull { it.prompt?.id }).containsExactlyElementsIn(expected).inOrder()
        // And the runs, too, keep their order.
        val runIds = pairing.turns.mapNotNull { it.run?.id }
        assertThat(runIds).containsExactlyElementsIn(turns.map { it.runId }).inOrder()
    }
}

/** Bennett's Polymarket Project as the fixture: 324 turns, 26 of them with no `/v0` message, the newest the user's and under way. */
object PromptlessFixture {
    const val TURNS = 324
    /** Turn 7, 19, 31, … 307: 26 turns, none of them a user's ((i-1) % 16 == 0 never holds for i ≡ 7 mod 12 — 7, 19, 31, 43, 55, 67, 79, 91, 103, 115, 127, 139, 151, 163, 175, 187, 199, 211, 223, 235, 247, 259, 271, 283, 295, 307). */
    fun isPromptless(i: Int): Boolean = i % 12 == 7 && i <= 307
    fun turns(firstAt: Long): List<LongProject.Turn> = LongProject.turns(firstAt, turns = TURNS, promptless = ::isPromptless, lastIsUser = true)
}
