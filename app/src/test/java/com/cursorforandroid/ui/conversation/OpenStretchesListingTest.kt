package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.snapshots.Snapshot
import com.cursorforandroid.domain.StretchSteps
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.TranscriptRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The screen's listing of open stretches ([OpenStretches.listed]): with nothing toggled open and no lone thought being
 * written it lists the rows without asking a stretch, and in every case it lists what asking each one would.
 */
class OpenStretchesListingTest {

    private fun call(key: String) = TranscriptRow.Entry.Call(ToolCall(key, "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "$key.kt"), key)
    private fun thought(key: String, streaming: Boolean) = TranscriptRow.Entry.Thought(ThinkingBlock("Weighing $key.", isStreaming = streaming), key)
    private fun stretch(n: Int) = TranscriptRow.Stretch(listOf(thought("t$n", streaming = false), call("a$n"), call("b$n")))

    private fun transcript(turns: Int, live: TranscriptRow.Stretch? = null): List<TranscriptRow> =
        (0 until turns).map(::stretch) + listOfNotNull(live)

    @Test
    fun `nothing open lists the rows without asking a stretch`() {
        val rows = transcript(2_000)
        val open = OpenStretches()
        var reads = 0
        val listed = Snapshot.observe(readObserver = { reads++ }) { open.listed(rows, emptyMap()) }
        assertThat(listed.rows).isSameInstanceAs(rows)
        assertThat(listed.steps).isEmpty()
        assertThat(reads).isLessThan(10)
    }

    @Test
    fun `a stretch closed again lists nothing`() {
        val rows = transcript(50)
        val open = OpenStretches(mapOf(rows[3].key to false))
        assertThat(open.listed(rows, emptyMap()).rows).isSameInstanceAs(rows)
    }

    @Test
    fun `an open stretch lists its steps`() {
        val rows = transcript(50)
        val open = OpenStretches(mapOf(rows[3].key to true))
        val listed = open.listed(rows, emptyMap())
        assertThat(listed.rows.filterIsInstance<TranscriptRow.Step>().map { it.stretchKey }.distinct()).containsExactly(rows[3].key)
        assertThat(listed.rows.map { it.key }).isEqualTo(StretchSteps.list(rows, open::of).rows.map { it.key })
    }

    @Test
    fun `a lone thought being written lists its text with nothing toggled`() {
        val live = TranscriptRow.Stretch(listOf(thought("live", streaming = true)), live = true)
        val rows = transcript(50, live)
        val listed = OpenStretches().listed(rows, emptyMap())
        assertThat(listed.rows.last()).isInstanceOf(TranscriptRow.Step::class.java)
        assertThat((listed.rows.last() as TranscriptRow.Step).text).isEqualTo("Weighing live.")
    }

    @Test
    fun `a lone thought being written and closed by the reader lists nothing`() {
        val live = TranscriptRow.Stretch(listOf(thought("live", streaming = true)), live = true)
        val rows = transcript(50, live)
        val closed = OpenStretches.Saver.restore(listOf("0thought:${live.key}"))!!
        assertThat(closed.listed(rows, emptyMap()).rows).isSameInstanceAs(rows)
    }

    @Test
    fun `every mix of toggles lists what asking each stretch would`() {
        val live = TranscriptRow.Stretch(listOf(thought("live", streaming = true)), live = true)
        val lone = TranscriptRow.Stretch(listOf(thought("lone", streaming = false)))
        val rows = transcript(20) + lone + listOf(live)
        val toggles = listOf(
            emptyList(),
            listOf("1${rows[2].key}"),
            listOf("0${rows[2].key}", "1${rows[5].key}"),
            listOf("0thought:${live.key}"),
            listOf("1thought:${lone.key}"),
            listOf("1thought:${lone.key}", "0thought:${live.key}", "1${rows[0].key}"),
        )
        for (saved in toggles) {
            val open = OpenStretches.Saver.restore(saved)!!
            val fast = open.listed(rows, emptyMap())
            val asked = StretchSteps.list(rows, open::of, emptyMap())
            assertThat(fast.rows).isEqualTo(asked.rows)
            assertThat(fast.steps).isEqualTo(asked.steps)
        }
    }
}
