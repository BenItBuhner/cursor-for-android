package com.cursorforandroid.ui.conversation

import com.cursorforandroid.ui.conversation.TranscriptPrefetchStrategy.Companion.rowsAhead
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which rows the transcript asks for ahead of time: the two past the last visible one, asked for once, let go when
 * they come into view or stop being next, and never past the end of the list.
 */
class TranscriptPrefetchStrategyTest {

    private val released = mutableListOf<String>()
    private val acquired = mutableListOf<Int>()
    private val rows = AheadRows<String> { released += it }

    private fun update(wanted: List<Int>) = rows.update(wanted) { index -> acquired += index; "row-$index" }

    @Test
    fun `the rows past the last visible one, within the list`() {
        assertThat(rowsAhead(lastVisible = 5, total = 60, count = 2)).containsExactly(6, 7).inOrder()
        assertThat(rowsAhead(lastVisible = 58, total = 60, count = 2)).containsExactly(59)
        assertThat(rowsAhead(lastVisible = 59, total = 60, count = 2)).isEmpty()
        assertThat(rowsAhead(lastVisible = null, total = 60, count = 2)).isEmpty()
        assertThat(rowsAhead(lastVisible = 0, total = 1, count = 2)).isEmpty()
    }

    @Test
    fun `rows are acquired once and kept while they stay next in line`() {
        update(listOf(6, 7))
        update(listOf(6, 7))
        assertThat(acquired).containsExactly(6, 7).inOrder()
        assertThat(released).isEmpty()
        assertThat(rows.indices).containsExactly(6, 7)
    }

    @Test
    fun `a row that comes into view is released and the next one acquired`() {
        update(listOf(6, 7))
        update(listOf(7, 8))
        assertThat(released).containsExactly("row-6")
        assertThat(acquired).containsExactly(6, 7, 8).inOrder()
        assertThat(rows.indices).containsExactly(7, 8)
    }

    @Test
    fun `rows that are no longer next in line are all released`() {
        update(listOf(6, 7))
        update(listOf(16, 17))
        assertThat(released).containsExactly("row-6", "row-7")
        assertThat(rows.indices).containsExactly(16, 17)
        update(emptyList())
        assertThat(released).containsExactly("row-6", "row-7", "row-16", "row-17")
        assertThat(rows.indices).isEmpty()
    }
}
