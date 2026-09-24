package com.cursorforandroid.domain

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.RecordFixtures
import com.cursorforandroid.ui.components.MarkdownCache
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

/**
 * The coordinator's `SendMessage` through the whole presentation, event by event: the live stream's shape in which
 * the call is announced before its arguments, its arguments then streamed in growing fragments, its result arriving
 * on its own without the arguments, and the turn going on after it (`coordinator_send_fragments.sse`, Bennett's
 * v0.3.35 chat). The message is a row of its own from the first fragment on, whole once the arguments are, and stays
 * one after the result, after the turn's remaining steps and its end, and when the same items are presented again
 * from the presenter's cache and the markdown cache already holds its text.
 */
class CoordinatorMessagePresentationTest {

    private val events: List<RunStreamEvent> by lazy {
        val source = Buffer().writeUtf8(CoordinatorFixtures.text("coordinator_send_fragments.sse"))
        val out = ArrayList<RunStreamEvent>()
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event?.let { out += it }
        }
        out
    }

    private val whole = "All six paused workers are resumed where they stopped (pitch pipeline, backplate camera fix and composites, phones pass 12 and app finals, realism coordinator, clip gate, week-1 campaigns); hands round 15 never stopped. Next results due: the week-1 posting batches for the three winners and the ten pitch packages."

    private fun messages(rows: List<TranscriptRow>): List<ToolPayload.CoordinatorMessage> =
        rows.filterIsInstance<TranscriptRow.Message>().map { it.call.payload as ToolPayload.CoordinatorMessage }

    @Test
    fun `the message is a row from its first fragment, whole once the arguments are, and stays one to the end of the turn`() {
        val live = TimelineBuilder.LiveRun("run-coord-frag", timed = false)
        val presenter = TranscriptPresenter()
        val prompt = UserMessage("u1", "Fable 5.1 temporarily hit limits, but it is back so you can keep on working now. Thank you.", 1_789_819_000_000L)
        fun present(active: Boolean = true) = presenter.present(listOf(prompt) + live.snapshot(), coordinatorMode = true, runActive = active)

        // Announced before its arguments: the row is the message's, waiting for its text, not a bare step.
        events.take(5).forEach(live::apply)
        var rows = present().rows
        assertThat(rows.filterIsInstance<TranscriptRow.Message>()).hasSize(1)
        assertThat(messages(rows).single().message).isEmpty()
        assertThat(rows.filterIsInstance<TranscriptRow.Message>().single().call.isRunning).isTrue()

        // The first fragment: the row shows what there is, streaming.
        live.apply(events[5])
        rows = present().rows
        assertThat(messages(rows).single().message).isEqualTo("All six paused workers are resumed where they stopped")
        assertThat(messages(rows).single().missing).isFalse()

        // Whole: the row has the whole text.
        live.apply(events[6])
        rows = present().rows
        assertThat(messages(rows).single().message).isEqualTo(whole)

        // The result comes on its own, without the arguments: the text stays, the call is done.
        live.apply(events[7])
        rows = present().rows
        val done = rows.filterIsInstance<TranscriptRow.Message>().single()
        assertThat((done.call.payload as ToolPayload.CoordinatorMessage).message).isEqualTo(whole)
        assertThat(done.call.isRunning).isFalse()
        assertThat(done.call.isError).isFalse()

        // The turn goes on: the remark is a note in the stretch after the message, the edit a step; the message stands,
        // and so does the row for the worker the coordinator messaged before it.
        events.drop(8).forEach(live::apply)
        rows = present(active = false).rows
        assertThat(messages(rows).map { it.message }).containsExactly(whole)
        val kinds = rows.map { row -> when (row) { is TranscriptRow.Item -> "item:${row.item::class.simpleName}"; is TranscriptRow.Message -> "message"; is TranscriptRow.Stretch -> "stretch:${row.summary.text}"; else -> row::class.simpleName!! } }
        assertThat(kinds).containsExactly("item:UserMessage", "stretch:1 note", "Subagent", "message", "stretch:Worked 4m 13s · 1 edit · 1 note").inOrder()
        assertThat(rows.map { it.key }).containsNoDuplicates()

        // Presented again from the same items: the presenter answers from its segments, the rows the same instances.
        val again = presenter.present(listOf(prompt) + live.snapshot(), coordinatorMode = true, runActive = false)
        assertThat(again.segmentsBuilt).isEqualTo(0)
        assertThat(messages(again.rows).map { it.message }).containsExactly(whole)

        // The markdown cache holding the text changes nothing about what is drawn: the same blocks, the message's own.
        MarkdownCache.prime(whole)
        assertThat(MarkdownCache.cached(whole)).isNotNull()
        assertThat(MarkdownCache.parse(whole)).isEqualTo(MarkdownCache.cached(whole))
        assertThat(MarkdownCache.parse(whole).isNotEmpty()).isTrue()
    }

    /**
     * The record's shapes (`record_coordinator_pages.json`: the message recorded whole, in `is_streaming` pieces,
     * streamed back) through the same presentation: each a row of its own, and the same rows again from the cache.
     */
    @Test
    fun `the record's copies of the message present to rows, and again from the presenter's cache`() {
        val items = RecordFixtures.items("record_coordinator_pages.json", firstAt = 1_789_600_000_000L)
        val presenter = TranscriptPresenter()
        val first = presenter.present(items, coordinatorMode = true, runActive = false)
        val texts = messages(first.rows).map { it.message }
        assertThat(texts).hasSize(3)
        assertThat(texts[0]).startsWith("Shards are queued")
        assertThat(texts[1]).startsWith("All four shards rendered")
        assertThat(texts[2]).startsWith("The phone worker has the Fold8")
        assertThat(messages(first.rows).none { it.missing || it.recovered }).isTrue()
        texts.forEach(MarkdownCache::prime)
        val again = presenter.present(items, coordinatorMode = true, runActive = false)
        assertThat(again.segmentsBuilt).isEqualTo(0)
        assertThat(messages(again.rows).map { it.message }).isEqualTo(texts)
        assertThat(again.rows.filterIsInstance<TranscriptRow.Message>().map { it.key }).isEqualTo(first.rows.filterIsInstance<TranscriptRow.Message>().map { it.key })
    }

    /** The live turn cut off: the stream's last word was the result; a footer read off the record closes it. Still the message. */
    @Test
    fun `a turn closed from the record after the stream broke keeps the message`() {
        val live = TimelineBuilder.LiveRun("run-coord-frag", timed = false)
        events.take(8).forEach(live::apply)
        live.apply(RunStreamEvent.Result("run-coord-frag", RunStatus.FINISHED, null, 253_000, null, fromRecord = true))
        val rows = TranscriptPresenter().present(live.snapshot(), coordinatorMode = true, runActive = false).rows
        assertThat(messages(rows).map { it.message }).containsExactly(whole)
    }
}
