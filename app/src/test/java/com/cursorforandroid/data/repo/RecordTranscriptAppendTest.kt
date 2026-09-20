package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.domain.AssistantMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The record read on from its known end (see [RecordPager.since], [RecordTranscript.append]): a reopen or a growth
 * read costs the steps appended since — one small page when nothing changed — and rebuilds the newest turn alone,
 * the turns before it keeping their items; a record shorter than it was is read from its end again.
 */
class RecordTranscriptAppendTest {

    private val build: (HeadlessTranscript.Turn, String) -> List<com.cursorforandroid.domain.TimelineItem> = { turn, key -> HeadlessTranscript.body(turn, key) }

    private fun prompt(text: String) = HeadlessStep(userMessage = text, projectMode = true)
    private fun text(text: String) = HeadlessStep(text = text)
    private val done = HeadlessStep()

    private class Served(var steps: List<HeadlessStep>) : ConversationRecordApi {
        val reads = ArrayList<Pair<Int, Int>>()
        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            reads += startIndex to limit
            return HeadlessPage(steps.drop(startIndex).take(limit), startIndex, steps.size)
        }
        override suspend fun state(agentId: String): RecordState = RecordState(0, emptyList(), 0, true, 0L, 0L)
    }

    private fun record(vararg turns: List<HeadlessStep>): List<HeadlessStep> = turns.flatMap { it }

    @Test
    fun `nothing appended - one read at the known end, the window unchanged by identity`() = runBlocking<Unit> {
        val served = Served(record(listOf(prompt("A"), text("a1"), done), listOf(prompt("B"), text("b1"))))
        val window = RecordTranscript.window(RecordPager.tail(served, "bc", wantTurns = 10)!!, null, null, now = 1L, build = build)
        assertThat(window.canAppend).isTrue()
        served.reads.clear()
        val delta = RecordPager.since(served, "bc", window.total)!!
        assertThat(served.reads).containsExactly(window.total to RecordPager.PAGE_SIZE)
        assertThat(delta.steps).isEmpty()
        assertThat(RecordTranscript.append(window, delta, now = 2L, build = build)).isSameInstanceAs(window)
    }

    @Test
    fun `the newest turn grows and a turn follows - the newest turn is rebuilt, the rest keep their items`() = runBlocking<Unit> {
        val served = Served(record(listOf(prompt("A"), text("a1"), done), listOf(prompt("B"), text("b1"))))
        val window = RecordTranscript.window(RecordPager.tail(served, "bc", wantTurns = 10)!!, null, null, now = 1L, build = build)
        val turnA = window.turns[0]
        // The live turn B ends and C starts.
        served.steps = served.steps + listOf(text("b2"), done, prompt("C"), text("c1"))
        val delta = RecordPager.since(served, "bc", window.total)!!
        assertThat(delta.firstStep).isEqualTo(window.total)
        assertThat(delta.steps).hasSize(4)
        val grown = RecordTranscript.append(window, delta, now = 2L, build = build)
        assertThat(grown.total).isEqualTo(served.steps.size)
        assertThat(grown.turns.map { it.prompt }).containsExactly("A", "B", "C").inOrder()
        assertThat(grown.turns[0].items).isSameInstanceAs(turnA.items)
        val b = grown.turns[1]
        assertThat(b.stepIndex).isEqualTo(window.turns[1].stepIndex)
        assertThat(b.stepCount).isEqualTo(4)
        assertThat(b.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("b1b2")
        assertThat(grown.turns[2].items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("c1")
        assertThat(grown.newestTurn!!.prompt).isEqualTo("C")
        // Read on again: the next delta starts where this one ended.
        served.steps = served.steps + listOf(text("c2"))
        val next = RecordPager.since(served, "bc", grown.total)!!
        assertThat(next.firstStep).isEqualTo(grown.total)
        assertThat(RecordTranscript.append(grown, next, now = 3L, build = build).turns.last().items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("c1c2")
    }

    @Test
    fun `a delta that ends a finished turn without adding to it keeps that turn's items`() = runBlocking<Unit> {
        val served = Served(record(listOf(prompt("A"), text("a1"), done)))
        val window = RecordTranscript.window(RecordPager.tail(served, "bc", wantTurns = 10)!!, null, null, now = 1L, build = build)
        served.steps = served.steps + listOf(prompt("B"), text("b1"))
        val grown = RecordTranscript.append(window, RecordPager.since(served, "bc", window.total)!!, now = 2L, build = build)
        assertThat(grown.turns[0].items).isSameInstanceAs(window.turns[0].items)
        assertThat(grown.turns.map { it.prompt }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `a record shorter than it was is not a delta`() = runBlocking<Unit> {
        val served = Served(record(listOf(prompt("A"), text("a1"), done), listOf(prompt("B"), text("b1"))))
        val window = RecordTranscript.window(RecordPager.tail(served, "bc", wantTurns = 10)!!, null, null, now = 1L, build = build)
        served.steps = served.steps.take(3)
        assertThat(RecordPager.since(served, "bc", window.total)).isNull()
    }

    @Test
    fun `a window restored from disk has no steps to append to`() {
        val restored = RecordWindow(total = 5, firstStep = 0, turns = listOf(RecordTurn(0, 3, "A", true, emptyList())), newestTurn = null)
        assertThat(restored.canAppend).isFalse()
    }
}
