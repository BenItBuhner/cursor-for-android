package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import java.util.TreeMap
import kotlin.math.abs

/**
 * Which run each turn of the account's record started (Extended mode, the Beta engine): settled by evidence, and by
 * position only within the gaps the evidence leaves — never by counting both lists back from their newest ends.
 *
 * The record and the run list are two reads, and each catches up with a new turn in its own time. A turn's run
 * stands in for the turn's body whenever it has a trace or a story to show (see `ConversationRepository.recordItems`),
 * so a turn given another turn's run draws that turn's work in place of its own. Until 0.3.86 the window's turns were
 * laid over the runs from the newest end, the record's last turn on the list's last run. One run the list had and
 * the record did not yet — a worker's report the coordinator spent three seconds on, heard of from the run list
 * while the record's read failed or was skipped — moved every turn one run on: the reply to the newest question
 * drawn under the question before it, the newest question with nothing but the silent run's "Worked 3s", and the
 * reply that belonged to the question before gone (Bennett's frames of 2026-09-23). A record ahead of the list moved
 * them the other way. Every version did so for the seconds between the two reads; 0.3.85's state reads — failing as
 * "offline", or skipped as current, while the run page still landed — left it so until the next good read.
 *
 * Here, each anchor kept only where it agrees with the order of the ones before it (a later turn started a later run):
 *  1. a prompt sent from here names its run — the turn the record caught it up in, by its words ([Turn.echo]);
 *  2. the account's timing of a turn — its end, and its start by its duration — names the run whose life matches it
 *     clearly best: better than every other run by [AMBIGUITY_MS], and no other turn matching that run as well;
 *  3. the gaps the anchors leave pair in order, walking from an anchor and passing over a turn or a run the timings
 *     say the other side does not have: forward from the newest anchor, where the runs no turn takes are the turns
 *     the record has not caught up with ([Pairing.loose]); back from the oldest, where the turns past the runs in
 *     hand are the ones whose runs the list has not paged in; a gap between two anchors pairs in order when its two
 *     sides count the same, and walks back from its newer anchor when they do not. With no anchor at all, back from
 *     the newest end, as before — the timings still passing over what one side lacks.
 * A turn without a run renders from the record alone; nothing is ever taken from a run the evidence gives another turn.
 */
object RecordPairing {

    /** What settled a turn's run; for the diagnostics and the tests. */
    enum class Evidence { ECHO, TIME, POSITION, NONE }

    /**
     * One turn of the window as the pairing reads it: the account's timing of it — when it ended, and how long it
     * took — when read, and the run of the prompt sent from here that the turn is ([echo]), when one is.
     */
    class Turn(val endedAt: Long?, val durationMs: Long?, val echo: String? = null) {
        val startedAt: Long? get() = endedAt?.let { end -> durationMs?.takeIf { it >= 0 }?.let { end - it } }
    }

    class Pairing(
        private val runs: Array<RunDto?>,
        private val evidence: Array<Evidence>,
        /** Runs newer than every run a turn took: turns the record has not caught up with yet, oldest first. */
        val loose: List<RunDto>,
        /** How many runs there were to pair with. */
        val candidates: Int,
    ) {
        /** The run of the window's [i]th turn, or null when its run is not in hand. */
        fun runAt(i: Int): RunDto? = runs.getOrNull(i)
        fun evidenceAt(i: Int): Evidence = evidence.getOrElse(i) { Evidence.NONE }
        /** The runs some turn took, by id. */
        val placed: Set<String> = runs.mapNotNullTo(HashSet()) { it?.id }
        fun count(of: Evidence): Int = evidence.indices.count { runs[it] != null && evidence[it] == of }
        /** Turns without a run. */
        val runless: Int get() = runs.count { it == null }
    }

    /**
     * Pairs [turns] (the window's, oldest first) with [runs] (oldest first by creation: every run known that no prompt
     * sent from here and still ahead of the record holds — see `ConversationRepository.trailingRunIds`).
     */
    fun pair(turns: List<Turn>, runs: List<RunDto>): Pairing {
        val t = turns.size
        val r = runs.size
        val created = LongArray(r) { parseIsoMillis(runs[it].createdAt) }
        val active = BooleanArray(r) { runs[it].statusEnum().isActive }
        // When each run ended: its record's last write, else its start and duration; never for a run still going.
        val ended = LongArray(r) { j ->
            if (active[j]) Long.MAX_VALUE else parseIsoMillis(runs[j].updatedAt).takeIf { it > 0 }
                ?: runs[j].durationMs?.let { created[j] + it } ?: created[j]
        }
        val runOf = arrayOfNulls<RunDto>(t)
        val evidence = Array(t) { Evidence.NONE }
        val anchors = TreeMap<Int, Int>()
        val byRun = HashSet<Int>()

        /** [run] may be [turn]'s without breaking the order of the anchors: after the run of the anchor before it, before the run of the one after. */
        fun fits(turn: Int, run: Int): Boolean = turn !in anchors && run !in byRun &&
            (anchors.lowerEntry(turn)?.value ?: -1) < run && run < (anchors.higherEntry(turn)?.value ?: r)
        fun anchor(turn: Int, run: Int, how: Evidence) {
            anchors[turn] = run
            byRun += run
            runOf[turn] = runs[run]
            evidence[turn] = how
        }

        // 1. The prompts sent from here, newest first.
        if (turns.any { it.echo != null }) {
            val index = HashMap<String, Int>(r * 2)
            runs.forEachIndexed { j, run -> index[run.id] = j }
            for (i in t - 1 downTo 0) {
                val run = turns[i].echo?.let { index[it] } ?: continue
                if (fits(i, run)) anchor(i, run, Evidence.ECHO)
            }
        }

        // 2. The account's timings: each turn's clearly best run, kept when no other turn matches that run as well.
        /** How far [run]'s life is from turn [i]'s timing, or null when the timing rules the run out. */
        fun cost(i: Int, run: Int): Long? {
            val end = turns[i].endedAt ?: return null
            val start = created[run].takeIf { it > 0 } ?: return null
            if (end < start - SLACK_MS) return null
            val runEnd = if (active[run]) maxOf(end, start) else ended[run]
            if (end > runEnd + SLACK_MS) return null
            return abs(end - runEnd) + (turns[i].startedAt?.let { abs(it - start) } ?: 0L)
        }
        if (turns.any { it.endedAt != null } && r > 0) {
            val best = IntArray(t) { -1 }
            val bestCost = LongArray(t) { Long.MAX_VALUE }
            for (i in 0 until t) {
                if (i in anchors) continue
                val end = turns[i].endedAt ?: continue
                // Runs are in order of creation: none created after the turn ended (and the slack) can be its run.
                var j = upperBound(created, end + SLACK_MS) - 1
                var first = -1
                var firstCost = Long.MAX_VALUE
                var secondCost = Long.MAX_VALUE
                while (j >= 0) {
                    if (created[j] in 1 until end - LOOKBACK_MS) break
                    if (j !in byRun) {
                        val c = cost(i, j)
                        if (c != null) {
                            if (c < firstCost) { secondCost = firstCost; firstCost = c; first = j } else if (c < secondCost) secondCost = c
                        }
                    }
                    j--
                }
                if (first >= 0 && (secondCost == Long.MAX_VALUE || secondCost - firstCost >= AMBIGUITY_MS)) {
                    best[i] = first
                    bestCost[i] = firstCost
                }
            }
            val claims = HashMap<Int, MutableList<Int>>()
            for (i in 0 until t) if (best[i] >= 0) claims.getOrPut(best[i]) { ArrayList(1) } += i
            val chosen = ArrayList<Int>()
            for (claimants in claims.values) {
                val sorted = claimants.sortedBy { bestCost[it] }
                if (sorted.size == 1 || bestCost[sorted[1]] - bestCost[sorted[0]] >= AMBIGUITY_MS) chosen += sorted[0]
            }
            chosen.sortedBy { bestCost[it] }.forEach { i -> if (fits(i, best[i])) anchor(i, best[i], Evidence.TIME) }
        }

        // 3. The gaps.
        /** Where turn [i] stands against [run] by the timings: 0 when it may be the run's (or nothing tells), below 0 when older, above 0 when newer. */
        fun order(i: Int, run: Int): Int {
            val end = turns[i].endedAt ?: return 0
            val start = created[run].takeIf { it > 0 } ?: return 0
            if (end < start - SLACK_MS) return -1
            if (!active[run] && end > ended[run] + SLACK_MS) return 1
            return 0
        }
        fun gap(i: Int, run: Int) {
            runOf[i] = runs[run]
            evidence[i] = Evidence.POSITION
        }
        /** From turn [i] and [run] towards the newer ends, short of [toTurn] and [toRun]. */
        fun forward(from: Int, fromRun: Int, toTurn: Int, toRun: Int) {
            var i = from
            var j = fromRun
            while (i < toTurn && j < toRun) {
                when (order(i, j).coerceIn(-1, 1)) {
                    0 -> { gap(i, j); i++; j++ }
                    // The turn ended before the run began: the list has not got the turn's run.
                    -1 -> i++
                    // The run ended before the turn: the record has no turn for it.
                    else -> j++
                }
            }
        }
        /** From turn [i] and [run] towards the older ends, down to (not including) [downToTurn] and [downToRun]. */
        fun backward(from: Int, fromRun: Int, downToTurn: Int, downToRun: Int) {
            var i = from
            var j = fromRun
            while (i > downToTurn && j > downToRun) {
                when (order(i, j).coerceIn(-1, 1)) {
                    0 -> { gap(i, j); i--; j-- }
                    // The turn is newer than the run: the list has not got the turn's run.
                    1 -> i--
                    // The run is newer than the turn: the record has no turn for it.
                    else -> j--
                }
            }
        }
        if (anchors.isEmpty()) {
            backward(t - 1, r - 1, -1, -1)
        } else {
            val bounds = ArrayList<Pair<Int, Int>>(anchors.size + 2)
            bounds += -1 to -1
            anchors.forEach { (turn, run) -> bounds += turn to run }
            bounds += t to r
            for (k in 0 until bounds.size - 1) {
                val (lowTurn, lowRun) = bounds[k]
                val (highTurn, highRun) = bounds[k + 1]
                val turnsIn = highTurn - lowTurn - 1
                val runsIn = highRun - lowRun - 1
                if (turnsIn <= 0 || runsIn <= 0) continue
                when {
                    highTurn == t -> forward(lowTurn + 1, lowRun + 1, t, r)
                    lowTurn == -1 -> backward(highTurn - 1, highRun - 1, -1, -1)
                    turnsIn == runsIn -> for (n in 0 until turnsIn) gap(lowTurn + 1 + n, lowRun + 1 + n)
                    else -> backward(highTurn - 1, highRun - 1, lowTurn, lowRun)
                }
            }
        }

        var newest = -1
        val index = HashMap<String, Int>(r * 2)
        runs.forEachIndexed { j, run -> index[run.id] = j }
        runOf.forEach { run -> run?.let { newest = maxOf(newest, index.getValue(it.id)) } }
        val loose = if (newest + 1 >= r) emptyList() else runs.subList(newest + 1, r).toList()
        return Pairing(runOf, evidence, loose, r)
    }

    /** The first index of [sorted] whose value is above [value]. */
    private fun upperBound(sorted: LongArray, value: Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** How far the account's timing of a turn and its run's record may disagree: the run is created a moment before the turn starts, and written a moment after it ends. */
    const val SLACK_MS = 10_000L
    /** How much better one run must match a turn's timing than the next best to be taken as its run: ties settle nothing. */
    const val AMBIGUITY_MS = 2_000L
    /** How long before a turn ended its run may have been created, at most: runs created earlier are not looked at. */
    private const val LOOKBACK_MS = 12 * 3_600_000L
}
