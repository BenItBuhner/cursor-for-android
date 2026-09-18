package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Random

class SendGateTest {

    private fun inputs(
        rowLoaded: Boolean = false,
        rowRunning: Boolean = false,
        rowUpdatedAt: Long = 0L,
        chat: RunStatus? = null,
        streaming: Boolean = false,
        reconnecting: Boolean = false,
        accountScanned: Boolean = false,
        accountRunning: Boolean = false,
        accountAt: Long = 0L,
    ) = SendGate.Inputs(rowLoaded, rowRunning, rowUpdatedAt, chat, streaming, reconnecting, accountScanned, accountRunning, accountAt)

    @Test
    fun `an idle chat is idle whatever order its sources arrived in, across 200 randomized orderings`() {
        // Every source that can speak for an idle agent — the row, the account's running set, the run record, a
        // stream that only tries to reconnect — arrives in a random order and a random subset; none says busy. The
        // decision must be "send now" every time, and name a source that actually spoke (never a made-up one).
        val random = Random(42)
        repeat(200) { round ->
            var i = inputs()
            val now = 1_000_000L + random.nextInt(100_000)
            val arrivals = mutableListOf<() -> Unit>(
                { i = i.copy(rowLoaded = true, rowRunning = false, rowUpdatedAtMillis = now - random.nextInt(60_000)) },
                { i = i.copy(accountScanned = true, accountRunning = false, accountAtMillis = now - random.nextInt(60_000)) },
                { i = i.copy(chatRunStatus = listOf(RunStatus.FINISHED, RunStatus.ERROR, RunStatus.CANCELLED, RunStatus.EXPIRED)[random.nextInt(4)]) },
                { i = i.copy(chatStreaming = true, chatReconnecting = random.nextBoolean()) },
            )
            arrivals.shuffle(random)
            arrivals.take(random.nextInt(arrivals.size + 1)).forEach { it() }
            val decision = SendGate.decide(i)
            assertWithMessage("round $round: $i").that(decision.busy).isFalse()
            when (decision.source) {
                SendGate.Source.Stream -> throw AssertionError("a reconnecting stream must not decide: $i")
                SendGate.Source.Account -> assertThat(i.accountScanned).isTrue()
                SendGate.Source.Row -> assertThat(i.rowLoaded).isTrue()
                SendGate.Source.Record -> assertThat(i.chatRunStatus).isNotNull()
                SendGate.Source.None -> assertThat(i.rowLoaded || i.accountScanned || i.chatRunStatus != null).isFalse()
            }
        }
    }

    @Test
    fun `a stream delivering an active run's events is busy whatever the row and the account say`() {
        val d = SendGate.decide(inputs(rowLoaded = true, rowRunning = false, chat = RunStatus.RUNNING, streaming = true, accountScanned = true, accountRunning = false, accountAt = 10))
        assertThat(d.busy).isTrue()
        assertThat(d.source).isEqualTo(SendGate.Source.Stream)
    }

    @Test
    fun `a stream still open on a run the chat has seen end says nothing`() {
        // Cancelled for a steer: the connection outlives the turn; the row, idle, decides.
        val d = SendGate.decide(inputs(rowLoaded = true, rowRunning = false, chat = RunStatus.CANCELLED, streaming = true))
        assertThat(d.busy).isFalse()
        assertThat(d.source).isEqualTo(SendGate.Source.Row)
    }

    @Test
    fun `a stream that is only reconnecting says nothing, and a stale RUNNING record does not hold the chat busy`() {
        // The run record says RUNNING and the stream has lost it; the row, read since, says idle: the row decides.
        val d = SendGate.decide(inputs(rowLoaded = true, rowRunning = false, rowUpdatedAt = 50, chat = RunStatus.RUNNING, streaming = true, reconnecting = true))
        assertThat(d.busy).isFalse()
        assertThat(d.source).isEqualTo(SendGate.Source.Row)
    }

    @Test
    fun `the account's running set speaks for the row when it was read since the row last moved`() {
        val fresh = SendGate.decide(inputs(rowLoaded = true, rowRunning = true, rowUpdatedAt = 100_000, accountScanned = true, accountRunning = false, accountAt = 100_000 + 1_000))
        assertThat(fresh.busy).isFalse()
        assertThat(fresh.source).isEqualTo(SendGate.Source.Account)
        // Read a poll before the row's stamp, within the slack: still the account's word.
        val nearly = SendGate.decide(inputs(rowLoaded = true, rowRunning = true, rowUpdatedAt = 100_000, accountScanned = true, accountRunning = false, accountAt = 100_000 - SendGate.ACCOUNT_SLACK_MS))
        assertThat(nearly.source).isEqualTo(SendGate.Source.Account)
    }

    @Test
    fun `a row that moved since the account was read — a send from here accepted — outranks the account's stale idle`() {
        val d = SendGate.decide(inputs(rowLoaded = true, rowRunning = true, rowUpdatedAt = 200_000, accountScanned = true, accountRunning = false, accountAt = 100_000))
        assertThat(d.busy).isTrue()
        assertThat(d.source).isEqualTo(SendGate.Source.Row)
    }

    @Test
    fun `without a row the record decides, and without either nothing holds the message`() {
        assertThat(SendGate.decide(inputs(chat = RunStatus.RUNNING)).let { it.busy to it.source }).isEqualTo(true to SendGate.Source.Record)
        assertThat(SendGate.decide(inputs(chat = RunStatus.FINISHED)).let { it.busy to it.source }).isEqualTo(false to SendGate.Source.Record)
        assertThat(SendGate.decide(inputs()).let { it.busy to it.source }).isEqualTo(false to SendGate.Source.None)
    }
}
