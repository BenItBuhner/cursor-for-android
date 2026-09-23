package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto

/**
 * Which run each of the documented transcript's prompts started — settled by evidence, and by position only where
 * nothing else tells, with every prompt kept in the transcript's own order whatever the pairing says.
 *
 * `/v0/agents/{id}/conversation` carries the prompts and the replies with no run ids and no timestamps, and the run
 * list carries the runs with no prompts: the two were laid over each other by position, the `r`th run under the
 * `r`th prompt. A chat with runs that no prompt started — a Project coordinator's turns opened by its workers'
 * reports, a turn resumed after a usage limit — has more runs than prompts, and by position every prompt after the
 * first such run sits under a run from before its own: Bennett's Polymarket Project on 2026-09-21 (298 prompts, 324
 * runs) drew its four newest replies with no prompt between them, the prompts that started those turns standing
 * twenty-six turns up the transcript, and the copy-suppression that kept this device's own prompts in place hid
 * the only copy of the ones it lost track of.
 *
 * Here the evidence comes first, each anchor kept only where it agrees with the order of the ones before it (a prompt
 * later in the transcript started a later run):
 *  1. a prompt that carries its time — the `<timestamp>` Cursor writes into the turns it injects (a worker's
 *     report, a goal continuing) — started the run created at that time;
 *  2. this device's own echoes — a prompt sent from here knows the run the server answered with — anchor the
 *     transcript's newest copy of their words;
 *  3. a run's `result` is the transcript's reply to the prompt that started it — the same words, or one cut short
 *     of the other — matched in order within each gap the anchors leave, newest first;
 *  4. the chat's newest prompt, if nothing settled it, and its newest run, while that run is under way, are each
 *     other's — but where the runs carry no results, only while the gap they close holds no more runs than
 *     prompts: once the run is over, position alone pairs that gap, and the turn under way is not drawn another
 *     way while it lasts;
 *  5. what is left pairs by position within the gaps — a gap's prompts with its first runs, the runs past them
 *     prompt-less; a gap with more prompts than runs leaves the newest prompts without a run (their run not listed
 *     yet), except the chat's oldest gap when the list is not complete, where the oldest prompts are the ones
 *     whose runs are not fetched.
 * A prompt is never left out: without a run it renders with its replies and no activity.
 */
object TurnPairing {

    /** What settled a turn's pairing (see the class note); for the diagnostics and the tests. */
    enum class Evidence { TIMESTAMP, ECHO, LIVE, RESULT, POSITION, NONE }

    /**
     * One turn of the chat, oldest first: a prompt with the run it started, a prompt without a run in hand, or a
     * run the transcript has no prompt for.
     */
    data class Turn(
        /** The transcript's prompt, or null for a run no prompt started. */
        val prompt: V0ConversationMessageDto?,
        /** Where [prompt] stands among the transcript's prompts, 0 the oldest; -1 for a turn without one. */
        val promptIndex: Int,
        /** The run, or null for a prompt whose run is not in hand. */
        val run: RunDto?,
        /** The transcript's replies to [prompt]: the assistant messages before the next prompt. Empty for a run without a prompt. */
        val replies: List<V0ConversationMessageDto>,
        val evidence: Evidence,
    )

    class Pairing(
        /** Every turn, oldest first. */
        val turns: List<Turn>,
        /** How many of the transcript's prompts there are. */
        val promptCount: Int,
    ) {
        /** The run each of the transcript's prompts started, by the prompt's index, for the prompts whose run is known. */
        val runOf: Map<Int, RunDto> = turns.asSequence().filter { it.promptIndex >= 0 && it.run != null }.associate { it.promptIndex to it.run!! }
        /** The transcript's prompt each run was started by, by run id, for the runs one of the transcript's prompts started (a run shown under a prompt sent from here is not among them). */
        val promptOf: Map<String, Int> = turns.asSequence().filter { it.promptIndex >= 0 && it.run != null }.associate { it.run!!.id to it.promptIndex }
        /** What settled each run's turn, by run id. */
        val evidenceOf: Map<String, Evidence> = turns.asSequence().filter { it.run != null }.associate { it.run!!.id to it.evidence }
        /** Runs no prompt started. */
        val promptless: Int = turns.count { it.prompt == null && it.run != null }
        /** Prompts whose run is not in hand. */
        val runless: Int = turns.count { it.prompt != null && it.run == null }
        fun count(evidence: Evidence): Int = turns.count { it.run != null && it.prompt != null && it.evidence == evidence }
    }

    /** A prompt with its replies, in the transcript's order. */
    private class Group(val index: Int, val prompt: V0ConversationMessageDto, val replies: List<V0ConversationMessageDto>)

    /**
     * Pairs [messages]' prompts with [runs] (oldest first, every run known — the server's and the ones only this
     * device knows). [echoes] are this device's own prompts by run id, as normalized text (see [normalize]);
     * [runsComplete] says whether [runs] is the whole chat or its newest part. [now] is unused for now: the
     * transcript carries no times to compare against.
     */
    fun pair(
        messages: List<V0ConversationMessageDto>,
        runs: List<RunDto>,
        echoes: Map<String, String> = emptyMap(),
        runsComplete: Boolean = true,
        /**
         * Runs of [echoes] that keep their echo as their prompt while the transcript has no copy of its words: no
         * prompt of the transcript's is theirs by any weaker evidence. The runs still under way, and the ones the
         * server has not listed yet — a prompt just sent, whose run and copy the two endpoints report in their own
         * time; a run the list has and that is over has a copy by now, and the words settle it like any other.
         */
        reserved: Set<String> = emptySet(),
    ): Pairing {
        val groups = ArrayList<Group>()
        val leading = ArrayList<V0ConversationMessageDto>()
        run {
            var prompt: V0ConversationMessageDto? = null
            var replies = ArrayList<V0ConversationMessageDto>()
            for (message in messages) {
                if (message.type == USER_MESSAGE) {
                    prompt?.let { groups += Group(groups.size, it, replies) }
                    prompt = message
                    replies = ArrayList()
                } else if (prompt == null) leading += message else replies += message
            }
            prompt?.let { groups += Group(groups.size, it, replies) }
        }
        val p = groups.size
        val r = runs.size
        val promptWords = groups.map { normalize(it.prompt.text) }
        val replyWords = groups.map { g -> g.replies.map { normalize(it.text) }.filter { it.isNotEmpty() } }
        // The anchors, prompt index → run index and back, kept in order: a prompt later in the transcript started a later run.
        val anchors = java.util.TreeMap<Int, Int>()
        val byRun = java.util.TreeMap<Int, Int>()
        val evidence = HashMap<Int, Evidence>() // by run index
        /** The prompts [run] may pair with without breaking the order: past the anchor before it, short of the anchor after it. */
        fun promptsFor(run: Int): IntRange = ((byRun.lowerEntry(run)?.value ?: -1) + 1) until (byRun.higherEntry(run)?.value ?: p)
        fun fits(prompt: Int, run: Int): Boolean = prompt !in anchors && run !in byRun && prompt in promptsFor(run)
        fun anchor(prompt: Int, run: Int, how: Evidence) {
            anchors[prompt] = run
            byRun[run] = prompt
            evidence[run] = how
        }

        val startedAt = runs.map { parseIsoMillis(it.createdAt) }
        // 1. A prompt that carries its time started the run created then: newest first, the nearest run within reason.
        for (prompt in p - 1 downTo 0) {
            val at = stampedAt(groups[prompt].prompt.text) ?: continue
            val run = ((r - 1) downTo 0).filter { it !in byRun && startedAt[it] > 0 && kotlin.math.abs(startedAt[it] - at) <= TIMESTAMP_TOLERANCE_MS }
                .minByOrNull { kotlin.math.abs(startedAt[it] - at) } ?: continue
            if (fits(prompt, run)) anchor(prompt, run, Evidence.TIMESTAMP)
        }
        // 2. This device's own prompts: the newest run first, the transcript's newest copy of its words. A run whose
        //    words the transcript has no copy of yet keeps its echo (see [reserved]): no prompt is its by position.
        val kept = HashSet<Int>()
        if (echoes.isNotEmpty()) {
            for (run in r - 1 downTo 0) {
                if (run in byRun) continue
                val wanted = echoes[runs[run].id]?.takeIf { it.isNotEmpty() } ?: continue
                val prompt = promptsFor(run).reversed().firstOrNull { i -> i !in anchors && promptWords[i] == wanted }
                if (prompt != null) anchor(prompt, run, Evidence.ECHO) else if (runs[run].id in reserved) kept += run
            }
        }
        // 3. A run's result is the reply to its prompt: within each gap the anchors leave, the newest prompt first
        //    takes the newest run before it whose result says its reply, and so on down — the same words said
        //    twice pair in order, and a run no prompt started (its result the transcript never carried) is passed over.
        val resultWords = runs.map { normalize(it.result ?: "") }
        run {
            var upperPrompt = p
            var upperRun = r
            val bounds = anchors.descendingMap().entries.map { it.key to it.value } + listOf(-1 to -1)
            for ((lowerPrompt, lowerRun) in bounds) {
                var runCursor = upperRun - 1
                for (prompt in upperPrompt - 1 downTo lowerPrompt + 1) {
                    if (replyWords[prompt].isEmpty()) continue
                    val run = (runCursor downTo lowerRun + 1).firstOrNull { it !in byRun && it !in kept && resultWords[it].isNotEmpty() && replyWords[prompt].any { words -> sameWords(words, resultWords[it]) } } ?: continue
                    anchor(prompt, run, Evidence.RESULT)
                    runCursor = run - 1
                }
                upperPrompt = lowerPrompt
                upperRun = lowerRun
            }
        }
        // 4. The turn under way: the chat's newest run, still running, on its newest prompt — after the results, so a
        //    newest prompt whose own run is over and says its reply keeps it: the run under way is then one no prompt
        //    of the transcript's started yet, a worker's report the coordinator is answering. Anchored, the newest
        //    prompt drew the report's run and left its own, reply and all, under the prompt before it. Runs without
        //    results say nothing either way; there a gap with more runs than prompts is left to position, as it will
        //    be once the run is over, and the run under way stands alone until its prompt lands.
        if (p > 0 && r > 0 && runs[r - 1].statusEnum().isActive && (r - 1) !in kept && fits(p - 1, r - 1)) {
            val below = byRun.lowerEntry(r - 1)
            val gapPrompts = p - 1 - (below?.value ?: -1)
            val gapRuns = ((below?.key ?: -1) + 1 until r - 1).count { it !in kept }
            if (resultWords.any { it.isNotEmpty() } || gapRuns < gapPrompts) anchor(p - 1, r - 1, Evidence.LIVE)
        }

        // 5. The gaps between the anchors, by position.
        val turns = ArrayList<Turn>(p + r)
        if (leading.isNotEmpty()) turns += Turn(null, -1, null, leading, Evidence.NONE)
        fun turnOf(prompt: Int, run: Int, how: Evidence) = Turn(groups[prompt].prompt, prompt, runs[run], groups[prompt].replies, how)
        fun runOnly(run: Int) = Turn(null, -1, runs[run], emptyList(), Evidence.NONE)
        fun promptOnly(prompt: Int) = Turn(groups[prompt].prompt, prompt, null, groups[prompt].replies, Evidence.NONE)
        var lastPrompt = -1
        var lastRun = -1
        val bounds = anchors.entries.map { it.key to it.value } + listOf(p to r)
        for ((nextPrompt, nextRun) in bounds) {
            val prompts = ((lastPrompt + 1) until nextPrompt).toList()
            // The gap's runs in order, less the ones that keep their echo: those take no prompt of the transcript's.
            val gapRuns = ((lastRun + 1) until nextRun).toList()
            val free = gapRuns.filter { it !in kept }
            val k = prompts.size
            val m = free.size
            // Which prompt each free run gets, if any; the prompts left over stand without a run.
            val promptOfRun = HashMap<Int, Int>()
            val runless = ArrayList<Int>()
            val runlessFirst: Boolean
            when {
                k <= m -> {
                    // Each prompt with the gap's next free run; the runs past the prompts stand on their own.
                    for (i in 0 until k) promptOfRun[free[i]] = prompts[i]
                    runlessFirst = false
                }
                lastPrompt == -1 && !runsComplete -> {
                    // The chat's oldest prompts, their runs not fetched: the newest of them take the runs in hand.
                    for (i in 0 until k - m) runless += prompts[i]
                    for (i in 0 until m) promptOfRun[free[i]] = prompts[k - m + i]
                    runlessFirst = true
                }
                else -> {
                    // More prompts than runs: the newest prompts' runs are not listed yet.
                    for (i in 0 until m) promptOfRun[free[i]] = prompts[i]
                    for (i in m until k) runless += prompts[i]
                    runlessFirst = false
                }
            }
            if (runlessFirst) runless.forEach { turns += promptOnly(it) }
            for (run in gapRuns) {
                val prompt = promptOfRun[run]
                turns += when {
                    prompt != null -> turnOf(prompt, run, Evidence.POSITION)
                    run in kept -> Turn(null, -1, runs[run], emptyList(), Evidence.ECHO)
                    else -> runOnly(run)
                }
            }
            if (!runlessFirst) runless.forEach { turns += promptOnly(it) }
            if (nextPrompt < p && nextRun < r) turns += turnOf(nextPrompt, nextRun, evidence[nextRun] ?: Evidence.POSITION)
            lastPrompt = nextPrompt
            lastRun = nextRun
        }
        return Pairing(turns, p)
    }

    /**
     * The pairing by position alone, as the transcript was always laid over the runs: the `i`th prompt with the
     * `i - firstRunAt`th of [runs] (oldest first), the prompts before that without a run, the runs past the prompts
     * on their own. For the callers that hand in a transcript and its runs already matched (a record turn's own
     * prompt and run, a test's fixture).
     */
    fun positional(messages: List<V0ConversationMessageDto>, runs: List<RunDto>, firstRunAt: Int = 0): List<Turn> {
        val turns = ArrayList<Turn>()
        var prompt: V0ConversationMessageDto? = null
        var index = -1
        var replies = ArrayList<V0ConversationMessageDto>()
        fun close() {
            val current = prompt
            if (current == null) { if (replies.isNotEmpty()) turns += Turn(null, -1, null, replies, Evidence.NONE); return }
            turns += Turn(current, index, runs.getOrNull(index - firstRunAt), replies, Evidence.POSITION)
        }
        for (message in messages) {
            if (message.type == USER_MESSAGE) {
                close()
                prompt = message
                index++
                replies = ArrayList()
            } else replies += message
        }
        close()
        for (i in (index + 1 - firstRunAt).coerceAtLeast(0) until runs.size) turns += Turn(null, -1, runs[i], emptyList(), Evidence.NONE)
        return turns
    }

    /** The words of a prompt or a reply as compared: trimmed, runs of whitespace as one space. */
    fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    /** The time a prompt carries — `<timestamp>2026-09-21T09:11:00.000Z</timestamp>`, as Cursor writes into the turns it injects — as epoch millis, or null. */
    fun stampedAt(text: String): Long? {
        val stamp = TIMESTAMP.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return parseIsoMillis(stamp).takeIf { it > 0 }
    }

    /** The same words, or one the other cut short — a result the server trimmed, a reply the transcript truncated — once there is enough of them to tell by. */
    private fun sameWords(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= PREFIX_MIN_CHARS && (a.startsWith(b) || b.startsWith(a)))

    private val WHITESPACE = Regex("\\s+")
    private val TIMESTAMP = Regex("""<timestamp\b[^>]*>(.*?)</timestamp\s*>""", RegexOption.DOT_MATCHES_ALL)
    private const val PREFIX_MIN_CHARS = 24
    /** How far a run's start may be from the time its prompt carries: the account starts the run as it injects the turn, within seconds. */
    private const val TIMESTAMP_TOLERANCE_MS = 30 * 60_000L
    private const val USER_MESSAGE = "user_message"
}
