package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The list's work in flight, and the one rule for showing it: never a row without an item behind it. */
class PendingWorkTest {

    @Test
    fun `an item is registered for as long as its block runs, cancellation included`() = runTest {
        var clock = 1_000L
        val pending = PendingWork(now = { clock })
        val gate = CompletableDeferred<Unit>()
        val job = async { pending.track("list page 2") { gate.await() } }
        runCurrent()
        assertThat(pending.items.map { it.name }).containsExactly("list page 2")
        clock += 250
        assertThat(pending.describe(clock)).containsExactly("list page 2 (250 ms)")
        job.cancel()
        advanceUntilIdle()
        assertThat(pending.items).isEmpty()

        val failing = async { runCatching { pending.track("older page") { error("boom") } } }
        advanceUntilIdle()
        assertThat(failing.await().isFailure).isTrue()
        assertThat(pending.items).isEmpty()
    }

    @Test
    fun `the row is shown only for work in flight, and goes the moment nothing is`() = runTest {
        val pending = PendingWork(now = { 0L })
        // Nothing in flight: asking to show shows nothing — a request that is over is not a spinner.
        pending.show()
        assertThat(pending.state.value.shown).isFalse()

        val page = pending.begin("list page 2")
        pending.show()
        assertThat(pending.state.value.shown).isTrue()
        // A second item joins under the same row; the first ending does not lower it while the second runs.
        val records = pending.begin("account records by id (4)")
        pending.end(page)
        assertThat(pending.state.value.shown).isTrue()
        assertThat(pending.items.map { it.name }).containsExactly("account records by id (4)")
        // The last item ending lowers the row with it.
        pending.end(records)
        assertThat(pending.state.value.shown).isFalse()
        assertThat(pending.items).isEmpty()

        // Work that begins afterwards — a poll's round — is registered and not shown: nobody asked for it.
        val poll = pending.begin("list refresh (quick, silent)")
        assertThat(pending.state.value.shown).isFalse()
        assertThat(pending.items).hasSize(1)
        pending.end(poll)
    }

    @Test
    fun `shown is never true with nothing in flight, whatever the order of ends and shows`() {
        val pending = PendingWork(now = { 0L })
        val a = pending.begin("a")
        val b = pending.begin("b")
        pending.show()
        pending.end(b)
        pending.end(a)
        pending.show()
        assertThat(pending.state.value).isEqualTo(PendingWork.State())
    }
}
