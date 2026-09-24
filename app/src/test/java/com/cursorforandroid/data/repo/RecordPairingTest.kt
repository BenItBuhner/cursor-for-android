package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/**
 * [RecordPairing]: the record's turns and the run list are two reads that each catch up with a new turn in their own
 * time. A turn pairs with the run the evidence names, and a run one read has and the other has not yet stays out of
 * every other turn's way — Bennett's frames of 2026-09-23, where one silent run the list had and the record did not
 * moved every reply one turn up.
 */
class RecordPairingTest {

    private val base = 1_800_000_000_000L

    private fun at(seconds: Int) = base + seconds * 1_000L
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun run(id: String, startedAt: Int, endedAt: Int?, status: String = "FINISHED") = RunDto(
        id = id,
        agentId = "bc-coordinator",
        status = status,
        createdAt = iso(at(startedAt)),
        updatedAt = endedAt?.let { iso(at(it) + 400) } ?: iso(at(startedAt)),
        durationMs = endedAt?.let { (it - startedAt) * 1_000L },
    )

    private fun turn(startedAt: Int, endedAt: Int, echo: String? = null) =
        RecordPairing.Turn(at(endedAt), (endedAt - startedAt) * 1_000L, echo)

    private fun untimed(echo: String? = null) = RecordPairing.Turn(null, null, echo)

    private fun RecordPairing.Pairing.ids(size: Int) = (0 until size).map { runAt(it)?.id }

    @Test
    fun `a silent run the list has and the record has not stays loose and every turn keeps its own run - the frame of 2026-09-23`() {
        // Turns: an old one, P ("Another regression…", answered "Yep, I see it…") and L (answered "this has been a
        // recurring issue…"). The list also has the three-second run a worker's report started after L, which the
        // record has not caught up with.
        val turns = listOf(turn(60, 100), turn(170, 200), turn(275, 300))
        val runs = listOf(run("r-old", 59, 100), run("r-p", 169, 200), run("r-l", 274, 300), run("r-silent", 302, 305))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-old", "r-p", "r-l").inOrder()
        assertThat(pairing.loose.map { it.id }).containsExactly("r-silent")
        assertThat((0 until 3).map { pairing.evidenceAt(it) }).containsExactly(RecordPairing.Evidence.TIME, RecordPairing.Evidence.TIME, RecordPairing.Evidence.TIME)
        assertThat(pairing.runless).isEqualTo(0)
    }

    @Test
    fun `a silent run still running when the list is read stays loose too`() {
        val turns = listOf(turn(170, 200), turn(275, 300))
        val runs = listOf(run("r-p", 169, 200), run("r-l", 274, 300), run("r-silent", 302, null, status = "RUNNING"))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(2)).containsExactly("r-p", "r-l").inOrder()
        assertThat(pairing.loose.map { it.id }).containsExactly("r-silent")
    }

    @Test
    fun `a turn the record has and the list has not leaves the newest turn without a run instead of moving the rest down`() {
        // The record caught up with N (sent from the web) before the run list did; its timing is not in yet.
        val turns = listOf(turn(60, 100), turn(170, 200), turn(275, 300), untimed())
        val runs = listOf(run("r-old", 59, 100), run("r-p", 169, 200), run("r-l", 274, 300))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(4)).containsExactly("r-old", "r-p", "r-l", null).inOrder()
        assertThat(pairing.loose).isEmpty()
        assertThat(pairing.runless).isEqualTo(1)
    }

    @Test
    fun `a newest turn whose timing is in but whose run is not pairs with nothing`() {
        val turns = listOf(turn(170, 200), turn(275, 300), turn(380, 400))
        val runs = listOf(run("r-p", 169, 200), run("r-l", 274, 300))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-p", "r-l", null).inOrder()
    }

    @Test
    fun `a prompt sent from here names its run even with no timings, and the silent run past it stays loose`() {
        val turns = listOf(untimed(), untimed(echo = "r-l"))
        val runs = listOf(run("r-p", 169, 200), run("r-l", 274, 300), run("r-silent", 302, 305))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(2)).containsExactly("r-p", "r-l").inOrder()
        assertThat(pairing.evidenceAt(1)).isEqualTo(RecordPairing.Evidence.ECHO)
        assertThat(pairing.evidenceAt(0)).isEqualTo(RecordPairing.Evidence.POSITION)
        assertThat(pairing.loose.map { it.id }).containsExactly("r-silent")
    }

    @Test
    fun `a prompt whose run the list has not got anchors nothing and the turns before it keep theirs`() {
        val turns = listOf(turn(170, 200), turn(275, 300), untimed(echo = "r-new"))
        val runs = listOf(run("r-p", 169, 200), run("r-l", 274, 300))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-p", "r-l", null).inOrder()
    }

    @Test
    fun `timings that tie settle nothing and the turns pair from the newest end as before`() {
        // A frozen clock: every run and turn at the same instant.
        val turns = listOf(turn(0, 0), turn(0, 0), turn(0, 0))
        val runs = listOf(run("r-1", 0, 0), run("r-2", 0, 0), run("r-3", 0, 0))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-1", "r-2", "r-3").inOrder()
        assertThat(pairing.count(RecordPairing.Evidence.TIME)).isEqualTo(0)
        assertThat(pairing.count(RecordPairing.Evidence.POSITION)).isEqualTo(3)
    }

    @Test
    fun `with no timings and no prompts from here the turns pair from the newest end`() {
        val turns = listOf(untimed(), untimed())
        val runs = listOf(run("r-0", 10, 20), run("r-1", 30, 40), run("r-2", 50, 60))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(2)).containsExactly("r-1", "r-2").inOrder()
        assertThat(pairing.loose).isEmpty()
    }

    @Test
    fun `turns older than every run in hand wait for the older page instead of borrowing a newer turn's run`() {
        val turns = listOf(turn(10, 20), turn(30, 40), turn(170, 200), turn(275, 300))
        val runs = listOf(run("r-p", 169, 200), run("r-l", 274, 300))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(4)).containsExactly(null, null, "r-p", "r-l").inOrder()
    }

    @Test
    fun `an untimed turn between two timed ones takes the run between their runs`() {
        val turns = listOf(turn(60, 100), untimed(), turn(275, 300))
        val runs = listOf(run("r-old", 59, 100), run("r-p", 169, 200), run("r-l", 274, 300), run("r-silent", 302, 305))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-old", "r-p", "r-l").inOrder()
        assertThat(pairing.evidenceAt(1)).isEqualTo(RecordPairing.Evidence.POSITION)
        assertThat(pairing.loose.map { it.id }).containsExactly("r-silent")
    }

    @Test
    fun `an untimed newest turn after a silent run the timings pass over takes the run after it`() {
        // The record caught up with the worker's report turn and with the user's next one; the latter's timing is not in.
        val turns = listOf(turn(275, 300), turn(302, 305), untimed())
        val runs = listOf(run("r-l", 274, 300), run("r-silent", 302, 305), run("r-next", 320, null, status = "RUNNING"))

        val pairing = RecordPairing.pair(turns, runs)

        assertThat(pairing.ids(3)).containsExactly("r-l", "r-silent", "r-next").inOrder()
        assertThat(pairing.loose).isEmpty()
    }
}
