package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessTurn
import com.cursorforandroid.data.api.HeadlessTurnPage
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.TurnPlan
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TurnShape

/**
 * The account's own record of a chat (`FetchBackgroundComposer`, see [ConversationRecordApi]) as the conversation
 * holds it in Extended mode: a window of its newest turns, read from the record's end a page at a time and widened
 * towards its start as the reader scrolls up — the way Cursor's own Agents Window opens a long conversation on its
 * newest turns and lazily brings the rest. The record is indexed by step (a prompt, a thought, a tool call, its
 * result, a stretch of reply); a turn is the steps from one prompt to the next.
 *
 * The record outlives the documented run log: a turn whose live stream expired months ago is here whole, tool calls
 * and payloads included, which is why it is the transcript's source of truth in Extended mode rather than the
 * `/v0` text and the `/v1` replays.
 */
class RecordTurn(
    /** The record index of the turn's first step: its prompt, or its first step for a turn without one. */
    val stepIndex: Int,
    /** How many steps the turn had when it was read; a live turn grows. */
    val stepCount: Int,
    val prompt: String?,
    /** The prompt was sent in Project mode: the chat is a coordinator's (see `ConversationState.isProjectConversation`). */
    val projectMode: Boolean,
    /** The turn's trace — thoughts, tool calls with their payloads, the reply — without a footer, which the run or the timing gives. */
    val items: List<TimelineItem>,
    /**
     * The turn's steps and calls as they came, keys and value types only, for the transcript diagnostics: kept for
     * the record's newest turns as read this session (see [RecordTranscript.SHAPE_TURNS]); null for the rest and for
     * a turn restored from disk.
     */
    val shape: TurnShape? = null,
    /**
     * The last error the record logged for the turn (`error.message`, see `HeadlessTranscript.errorMessage`): the
     * server's account of what went wrong, which is the footer's reason when — and only when — the run's own record
     * says the run failed. A turn that logged one and went on is not a failure for it.
     */
    val errorMessage: String? = null,
    /**
     * The turn's reply came from the documented `/v0` transcript rather than the record: the record gave the turn
     * without its text and the run's log was gone (see `ConversationRepository.fillTextFromTranscript`).
     */
    val textFromTranscript: Boolean = false,
    /**
     * With [textFromTranscript]: whether the record gave the turn's steps (its calls) and only the reply was wanting
     * — the turn is whole — or nothing but the prompt, so the activity is gone with the log and the turn says so.
     */
    val stepsFromRecord: Boolean = true,
    /**
     * The turn is of the blob-backed record, where [stepIndex] is the turn's whole-chat index (see
     * [RecordWindow.turnIndexed]): its items are filed under a key of their own on disk, apart from the step-indexed
     * record's, whose indices are steps and would otherwise name another turn's file.
     */
    val turnIndexed: Boolean = false,
    /**
     * The turn's own blob id (blob-backed record): content-addressed, so a state naming the same id at the same index
     * names this turn unchanged, and it is not read again (see `RecordPager.Raw.reused`). Null for the step-indexed record.
     */
    val blobId: String? = null,
    /** Every step of the turn was read; false while a [TurnPlan.MESSAGES] read's other steps are still to come. */
    val complete: Boolean = true,
    /**
     * What the turn's structure lists (blob-backed record): its steps, and how many of them are the coordinator's
     * messages to the user (`send_message_step_indices`). Null when unknown — the step-indexed record, a structure
     * that could not be read.
     */
    val stepTotal: Int? = null,
    val messageSteps: Int? = null,
) {
    /** The key the turn's items are filed under on disk (see `TraceCache`); stable while the record is append-only. */
    val traceKey: String get() = traceKey(stepIndex, turnIndexed)

    /** The record gave the agent's words for the turn: a reply, or a coordinator's message with its body. */
    val hasText: Boolean by lazy { items.any { it is AssistantMessage && it.markdown.isNotBlank() } || hasUserMessage }

    /**
     * The record's structure of the turn was read and names what it holds: whether the turn sent the user a message
     * is the record's word then, not a guess from its steps — a turn without message steps sent none, and a turn the
     * structure lists no steps for did nothing but take its prompt.
     */
    val structureKnown: Boolean get() = turnIndexed && messageSteps != null && stepTotal != null

    /** This turn with [replies] — the `/v0` transcript's — as its text, marked as such (see [textFromTranscript]). */
    fun withTranscriptText(replies: List<TimelineItem>): RecordTurn =
        RecordTurn(stepIndex, stepCount, prompt, projectMode, items + replies, shape, errorMessage, textFromTranscript = true, stepsFromRecord = hasBody, turnIndexed = turnIndexed, blobId = blobId, complete = complete, stepTotal = stepTotal, messageSteps = messageSteps)

    /** The turn's steps are not to be had: the record gave none and the reply is the transcript's (see [withTranscriptText]). */
    val activityMissing: Boolean get() = textFromTranscript && !stepsFromRecord

    /**
     * The record gave the turn's steps, not the prompt alone: a thought, a tool call or a reply is among the items.
     * A record that keeps the prompts and files the bodies elsewhere leaves this false, and the run's log is read
     * for the turn instead (see `ConversationRepository.recordTurnsNeedingReplay`).
     */
    val hasBody: Boolean get() = items.any { it is ActivityGroup || it is AssistantMessage }

    /**
     * The coordinator's word to the user, with its body, is among the turn's calls. A coordinator's turn the record
     * holds without one — its narration there, its `SendMessage` in a shape the record did not give whole — is read
     * from the run's log too, like a turn without a body (see `ConversationRepository.recordTurnsNeedingReplay`).
     */
    val hasUserMessage: Boolean by lazy { CoordinatorTranscript.hasUserMessage(items) }

    /**
     * The coordinator's word to the user is among the turn's calls with a body read leniently out of arguments the
     * record did not give whole (see `MessageRecovery`): shown as recovered, and read from the run's log too while
     * that lasts, since the log has the message as it was sent.
     */
    val hasRecoveredMessage: Boolean by lazy { CoordinatorTranscript.hasRecoveredMessage(items) }

    /**
     * The record shows the coordinator calling its message tool in this turn at all — with the body, without it, in
     * pieces. A turn without the call sent nothing to the user: a message a run's log then carries for it is an
     * earlier turn's said again, not this turn's (see `ConversationRepository.recordItems`).
     */
    val hasMessageCall: Boolean by lazy { CoordinatorTranscript.hasMessageCall(items) }

    /** The record holds tool calls for the turn: it recorded what the agent did, a message among them when the coordinator sent one. */
    val hasCalls: Boolean by lazy { items.any { it is ActivityGroup && it.calls.isNotEmpty() } }

    /** The turn is the user's own prompt, not one Cursor injected (a worker's report, a subscribed pull request's change). */
    val isUserTurn: Boolean get() = prompt != null && !SystemNotifications.isInjected(prompt)

    /**
     * In a coordinator's chat, whether the run's log is worth asking for the coordinator's word this turn's record
     * does not give whole: a message read leniently out of pieces (the log has it as sent); no message call at all
     * in the user's own turn (the record has carried narration and no call, #202); an injected turn the record
     * holds without any call — the calls may have been dropped with the message. An injected turn whose calls the
     * record does hold, with no message among them, sent none: the coordinator filed a worker's report and routed
     * the next step, and the log would only say the same — or carry the last message sent again, ahead of its own
     * events (#228). Not asked: a Project's silent turns are most of its turns, and asking every one replayed a log
     * per turn to find the message that was not there (Bennett, 2026-09-20: "Loading the activity of 240 turns").
     */
    val wantsLogForMessage: Boolean get() = when {
        // The structure says the turn sent nothing: the log would only carry an earlier turn's message again (#228).
        structureKnown && messageSteps == 0 -> false
        // The structure names the message and the record gave it whole: nothing the log would add.
        structureKnown && hasUserMessage -> hasRecoveredMessage
        else -> hasRecoveredMessage || (!hasUserMessage && (isUserTurn || !hasCalls))
    }

    /**
     * The turn has nothing to read from its run's log: its steps are still being read (see [complete]), or the
     * structure lists none — a turn that ended at its prompt is whole as it is.
     */
    val bodyIsTheRecords: Boolean get() = !complete || (structureKnown && stepTotal == 0)

    companion object {
        const val TRACE_KEY_PREFIX = TraceCache.RECORD_KEY_PREFIX
        const val TURN_KEY_PREFIX = TraceCache.RECORD_TURN_KEY_PREFIX

        fun traceKey(stepIndex: Int, turnIndexed: Boolean = false): String = if (turnIndexed) "$TURN_KEY_PREFIX$stepIndex" else "$TRACE_KEY_PREFIX$stepIndex"
    }
}

/** The loaded window of a chat's record: its newest [turns], the record's size, and where the loaded steps begin. */
class RecordWindow(
    /** `total_responses` at the last read: the record's size in steps. */
    val total: Int,
    /** The record index the loaded steps begin at; 0 when the record's start is loaded. */
    val firstStep: Int,
    /** The loaded turns, oldest first. */
    val turns: List<RecordTurn>,
    /**
     * Steps at [firstStep] that belong to a turn whose prompt is before the window: the tail of that turn, kept so
     * the page that brings its prompt can complete it. Not persisted; a restart re-reads them with that page.
     */
    val leading: List<HeadlessStep> = emptyList(),
    /** The account's word on the whole chat, when read (see [RecordState]): the turn count and every turn's timing. */
    val state: RecordState? = null,
    val readAtMillis: Long = 0L,
    /**
     * The newest turn's steps as they were read, prompt included: what a read of the steps appended since — the
     * record's delta — is joined onto to rebuild that turn and cut the turns after it (see [RecordTranscript.append]).
     * Null for a window restored from disk, whose turns' steps are not kept: its next read is a tail read.
     */
    val newestTurn: HeadlessTranscript.Turn? = null,
    /**
     * The window is of the blob-backed record (see [ConversationRecordApi.turns]): [total] is the chat's turn count,
     * [firstStep] the whole-chat index of the first loaded turn, and every [RecordTurn.stepIndex] a turn index. The
     * step-indexed record (`FetchBackgroundComposer`) counts steps in all three.
     */
    val turnIndexed: Boolean = false,
) {
    /** Turns before the window: the record has steps before the loaded ones. */
    val hasOlder: Boolean get() = firstStep > 0

    /** Whether the record can be read on from [total] alone: the newest turn's steps are in hand to join the delta onto. */
    val canAppend: Boolean get() = newestTurn != null && turns.isNotEmpty()

    /** How many turns the chat has, counting the ones before the window: the state's count when known, else at least the loaded ones. */
    val turnCount: Int get() = maxOf(state?.turnCount ?: 0, turns.size)

    /** The whole-chat index of the [i]th loaded turn (0 is the chat's first turn), counted back from the newest. */
    fun turnIndex(i: Int): Int = turnCount - turns.size + i

    /** The timing of the [i]th loaded turn, when the state has one for it. */
    fun timing(i: Int): TurnTiming? = state?.timings?.getOrNull(turnIndex(i))

    fun withState(state: RecordState): RecordWindow = RecordWindow(total, firstStep, turns, leading, state, readAtMillis, newestTurn, turnIndexed)

    /** The blob-backed window's turns held whole, by whole-chat index to their blob id: what a read need not read again. */
    val held: Map<Int, String> get() = if (!turnIndexed) emptyMap() else turns.filter { it.complete && it.blobId != null }.associate { it.stepIndex to it.blobId!! }

    /** The blob-backed window's turns whose steps are still to be read (see [RecordTurn.complete]), newest first. */
    val incomplete: List<Int> get() = turns.filter { !it.complete }.map { it.stepIndex }.asReversed()

    /**
     * What the reader came for, counted: the user's own prompts and the coordinator's messages to the user the
     * window holds — by the structure's count where it was read, else by what the steps gave.
     */
    val words: Int get() = turns.sumOf { turn -> (if (turn.isUserTurn) 1 else 0) + (turn.messageSteps ?: if (turn.hasUserMessage) 1 else 0) }

    /** This window with [replacements] standing for the turns of the same step index. */
    fun withTurns(replacements: Map<Int, RecordTurn>): RecordWindow =
        if (replacements.isEmpty()) this else RecordWindow(total, firstStep, turns.map { replacements[it.stepIndex] ?: it }, leading, state, readAtMillis, newestTurn, turnIndexed)
}

/**
 * Reads the record a page at a time: the newest turns first ([tail]), the turns before them on request ([before]),
 * and what was appended since a read ([since]). Every read returns the raw steps with their place in the record;
 * [RecordTranscript.window] cuts them into turns and builds their items.
 */
object RecordPager {

    /**
     * Steps [firstStep] until [firstStep] + `steps.size`, out of a record of [total] steps — or, for the blob-backed
     * record ([turnIndexed]), the steps of the turns [firstStep] until [firstStep] + the turns read, out of [total]
     * turns, each step carrying its turn's index (see [HeadlessStep.turnIndex]).
     */
    class Raw(
        val steps: List<HeadlessStep>,
        val firstStep: Int,
        val total: Int,
        val turnIndexed: Boolean = false,
        /**
         * A turn-indexed page's turns, oldest first: each read to its plan, or [HeadlessTurn.reused] — held already
         * under the same blob id, not read, its steps not among [steps]. Empty for the step-indexed record.
         */
        val turns: List<HeadlessTurn> = emptyList(),
    ) {
        /** The whole-chat indices of the page's turns the reader held already. */
        val reused: Set<Int> by lazy { turns.filter { it.reused }.mapTo(HashSet()) { it.index } }

        /** The page's turns by whole-chat index. */
        val byIndex: Map<Int, HeadlessTurn> by lazy { turns.associateBy { it.index } }

        /**
         * The page's blobs answered as missing or unreadable for most of what it asked — [DRIFT_MIN_MISSING] at
         * least, and half or more: not a blob or two the account let go, but a server that names turns whose blobs it
         * will not give, or gives in a shape this build does not read. The chat's documented endpoints are its account
         * then, and the reason says what was asked and what came back (see `ConversationRepository.loadFromRecord`).
         */
        val drift: ConnectRpcException? get() {
            val asked = turns.sumOf { it.asked }
            val missing = turns.sumOf { it.missing }
            if (missing < DRIFT_MIN_MISSING || missing * 2 < asked) return null
            val last = turns.mapNotNull { it.lastMissing }.lastOrNull()
            val said = last?.message?.takeIf { it.isNotBlank() }?.let { ": \"$it\"" } ?: ""
            return ConnectRpcException(last?.httpCode ?: 200, last?.code ?: ConnectRpcException.UNREADABLE_ANSWER, "$missing of $asked blobs of the chat's turns were missing or unreadable$said", path = last?.path, cause = last)
        }
    }

    /** Blobs answered as missing in one page before the page is read as the record's drift rather than a gap (see [Raw.drift]). */
    const val DRIFT_MIN_MISSING = 3

    /**
     * The blob-backed record's newest [wantTurns] turns: the conversation state for the turn list (one round trip),
     * then the turns' blobs side by side, to [plan], the turns [held] names by their blob id left unread. Null when the
     * chat has no turns. [state] when the caller read it a moment ago.
     */
    suspend fun tailTurns(api: ConversationRecordApi, agentId: String, wantTurns: Int, state: RecordState? = null, plan: TurnPlan = TurnPlan.FULL, held: Map<Int, String> = emptyMap()): Raw? {
        val known = state ?: api.countedState(agentId)
        val total = known.turnCount
        if (total <= 0) return null
        val from = (total - wantTurns).coerceAtLeast(0)
        val page = api.countedTurns(agentId, from, total - from, known, plan, held) ?: return null
        return page.raw(from)
    }

    /**
     * The blob-backed record's delta since the chat had [knownTotal] turns: the newest known turn again (it may have
     * grown — a turn under way, or one that ended since) and every turn after it. One round trip for the state when
     * nothing changed but the newest turn's blob. Null when the chat has fewer turns than it had (rewound).
     */
    suspend fun sinceTurns(api: ConversationRecordApi, agentId: String, knownTotal: Int, state: RecordState? = null, held: Map<Int, String> = emptyMap()): Raw? {
        val known = state ?: api.countedState(agentId)
        val total = known.turnCount
        if (total < knownTotal) return null
        val from = (knownTotal - 1).coerceAtLeast(0)
        val page = api.countedTurns(agentId, from, total - from, known, TurnPlan.FULL, held) ?: return null
        return page.raw(from)
    }

    /** The blob-backed record's [wantTurns] turns before turn [firstTurn], to [plan]. */
    suspend fun beforeTurns(api: ConversationRecordApi, agentId: String, firstTurn: Int, wantTurns: Int, plan: TurnPlan = TurnPlan.FULL, state: RecordState? = null): Raw {
        val from = (firstTurn - wantTurns).coerceAtLeast(0)
        val page = api.countedTurns(agentId, from, firstTurn - from, state, plan, emptyMap()) ?: return Raw(emptyList(), firstTurn, 0, turnIndexed = true)
        return page.raw(from)
    }

    /**
     * Turns [indices] of the blob-backed record read again whole — the ones a [TurnPlan.MESSAGES] read left steps of
     * for later — in one read over their span, the turns of the span [held] names left unread.
     */
    suspend fun completeTurns(api: ConversationRecordApi, agentId: String, indices: Collection<Int>, state: RecordState, held: Map<Int, String>): Raw? {
        if (indices.isEmpty()) return null
        val from = indices.min()
        val page = api.countedTurns(agentId, from, indices.max() - from + 1, state, TurnPlan.FULL, held.filterKeys { it !in indices }) ?: return null
        return page.raw(from)
    }

    private fun HeadlessTurnPage.raw(from: Int): Raw = Raw(turns.flatMap { it.steps }, from, turnCount, turnIndexed = true, turns = turns)

    private suspend fun ConversationRecordApi.countedState(agentId: String): RecordState {
        TranscriptPerf.session(agentId).network("state")
        return state(agentId)
    }

    private suspend fun ConversationRecordApi.countedTurns(agentId: String, from: Int, limit: Int, state: RecordState?, plan: TurnPlan, held: Map<Int, String>): HeadlessTurnPage? {
        TranscriptPerf.session(agentId).network("record")
        return readTurns(agentId, from, limit, state, plan, held)
    }

    /**
     * The record's last [wantTurns] turns (whole ones: read back until [wantTurns] + 1 prompts are in hand, or the
     * record's start), out of [knownTotal] steps when a previous read left that behind (saving the probe), else
     * after one small probe for the size. Null when the record is empty.
     */
    suspend fun tail(api: ConversationRecordApi, agentId: String, wantTurns: Int, knownTotal: Int? = null, pageSize: Int = PAGE_SIZE, firstPageSize: Int = FIRST_PAGE_SIZE): Raw? {
        if (api.readsTurns) return tailTurns(api, agentId, wantTurns)
        var total: Int = knownTotal ?: 0
        var steps = ArrayList<HeadlessStep>()
        var end: Int
        if (total > 0) {
            // Read the newest page against the known size; the answer says whether the record grew.
            val from = (total - firstPageSize).coerceAtLeast(0)
            val page = api.counted(agentId, startIndex = from, limit = firstPageSize)
            total = page.totalResponses
            if (total <= 0) return null
            steps.addAll(page.steps)
            end = from
            if (from + page.steps.size < total) {
                // Grew since: the rest of the record, up to its new end.
                var next = from + page.steps.size
                var guard = 0
                while (next < total && guard++ < MAX_PAGES) {
                    val more = api.counted(agentId, startIndex = next, limit = pageSize)
                    if (more.steps.isEmpty()) break
                    steps.addAll(more.steps)
                    next += more.steps.size
                    total = more.totalResponses
                }
            }
        } else {
            val probe = api.counted(agentId, startIndex = 0, limit = 1)
            total = probe.totalResponses
            if (total <= 0) return null
            end = total
        }
        var pages = 0
        while (end > 0 && pages < MAX_PAGES && prompts(steps) <= wantTurns) {
            // The first page is the smaller: the newest turns of a coordinator's record carry payloads of tens of
            // thousands of characters a step, and the first paint should not wait on megabytes of them; the pages
            // behind it, read only when the newest did not hold the window's turns, are the usual size.
            val size = if (pages == 0 && steps.isEmpty()) firstPageSize else pageSize
            val from = (end - size).coerceAtLeast(0)
            val page = api.counted(agentId, startIndex = from, limit = end - from)
            if (page.steps.isEmpty() && from > 0) break
            steps = ArrayList<HeadlessStep>(page.steps.size + steps.size).apply { addAll(page.steps); addAll(steps) }
            end = from
            pages++
        }
        return Raw(steps, end, total)
    }

    /**
     * Whether the record has grown past [knownTotal] steps: one small read at the known end, which answers with the
     * record's size (and the first of the new steps, which [tail] then reads in full). Cheap while nothing changes.
     */
    suspend fun grownPast(api: ConversationRecordApi, agentId: String, knownTotal: Int): Boolean {
        if (api.readsTurns) return api.countedState(agentId).turnCount > knownTotal
        val page = api.counted(agentId, startIndex = knownTotal.coerceAtLeast(0), limit = 1)
        return page.totalResponses > knownTotal || page.steps.isNotEmpty()
    }

    /**
     * The steps appended since the record had [knownTotal] of them — its delta — read from the known end on, a page
     * at a time until the end: nothing of what was read before is read again. One small round trip when nothing
     * changed (an empty page carrying the size). Null when the record is shorter than it was — rewound, or another
     * chat's — and only a tail read can say what it holds now.
     */
    suspend fun since(api: ConversationRecordApi, agentId: String, knownTotal: Int, pageSize: Int = PAGE_SIZE): Raw? {
        if (api.readsTurns) return sinceTurns(api, agentId, knownTotal)
        val steps = ArrayList<HeadlessStep>()
        var next = knownTotal.coerceAtLeast(0)
        var total: Int
        var pages = 0
        do {
            val page = api.counted(agentId, startIndex = next, limit = pageSize)
            total = page.totalResponses
            if (total < knownTotal) return null
            steps.addAll(page.steps)
            next += page.steps.size
            pages++
        } while (page.steps.isNotEmpty() && next < total && pages < MAX_PAGES)
        return Raw(steps, knownTotal, maxOf(total, knownTotal))
    }

    /** The steps before [firstStep], back until [wantTurns] + 1 more prompts are in hand or the record's start is reached. */
    suspend fun before(api: ConversationRecordApi, agentId: String, firstStep: Int, wantTurns: Int, pageSize: Int = PAGE_SIZE): Raw {
        if (api.readsTurns) return beforeTurns(api, agentId, firstStep, wantTurns)
        var steps = ArrayList<HeadlessStep>()
        var end = firstStep
        var total = 0
        var pages = 0
        while (end > 0 && pages < MAX_PAGES && prompts(steps) <= wantTurns) {
            val from = (end - pageSize).coerceAtLeast(0)
            val page = api.counted(agentId, startIndex = from, limit = end - from)
            total = page.totalResponses
            if (page.steps.isEmpty() && from > 0) break
            steps = ArrayList<HeadlessStep>(page.steps.size + steps.size).apply { addAll(page.steps); addAll(steps) }
            end = from
            pages++
        }
        return Raw(steps, end, total)
    }

    private fun prompts(steps: List<HeadlessStep>): Int = steps.count { it.userMessage != null }

    /** One page read, counted for the chat's diagnostics (see [TranscriptPerf]). */
    private suspend fun ConversationRecordApi.counted(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
        TranscriptPerf.session(agentId).network("record")
        return fetch(agentId, startIndex = startIndex, limit = limit)
    }

    /** Steps per request: a turn is rarely more than a page or two. */
    const val PAGE_SIZE = 200
    /** Steps in the first page of a tail read: about the newest window's worth of a coordinator's turns (see [tail]). */
    const val FIRST_PAGE_SIZE = 100
    /** Pages read for one window at most. */
    const val MAX_PAGES = 12
}

object RecordTranscript {

    /**
     * The window [raw] makes: its steps cut into turns at their prompts, each turn's items built ([body]); the steps
     * before the first prompt kept as [RecordWindow.leading] when the record's start is not in the window (they are
     * the tail of a turn whose prompt is a page further back), and as a turn of their own when it is.
     * [previous] lends the items of turns it already built with the same steps, so a re-read costs nothing for them.
     */
    fun window(raw: RecordPager.Raw, previous: RecordWindow?, state: RecordState?, now: Long, wantTurns: Int = Int.MAX_VALUE, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        if (raw.turnIndexed && raw.turns.isNotEmpty()) return turnWindow(raw, previous, state, now, wantTurns, build)
        val cut = cut(raw.steps, raw.firstStep)
        // A turn-indexed read brings whole turns: a first turn without a prompt is a turn whose message could not be read, not another turn's tail.
        var leading = if (!raw.turnIndexed && raw.firstStep > 0 && cut.isNotEmpty() && cut.first().first.prompt == null) cut.first().first.steps else emptyList()
        var kept = if (leading.isNotEmpty()) cut.drop(1) else cut
        var firstStep = raw.firstStep
        if (kept.size > wantTurns) {
            // A page brought more whole turns than the window wants: the newest [wantTurns] are the window, and the
            // steps before them are read again with the page that widens it.
            kept = kept.takeLast(wantTurns)
            leading = emptyList()
            firstStep = kept.first().second
        }
        // A window of the other record's kind lends nothing: its indices name other turns.
        val reuse = previous?.takeIf { it.turnIndexed == raw.turnIndexed }?.turns?.associateBy { it.stepIndex }
        val turns = kept.mapIndexed { i, cutTurn ->
            val turn = cutTurn.first
            val stepIndex = cutTurn.second
            val count = turn.steps.size + (if (turn.prompt != null) 1 else 0)
            val old = reuse?.get(stepIndex)
            val reused = old != null && old.stepCount == count && old.prompt == turn.prompt
            val items = if (reused) old!!.items else build(turn, RecordTurn.traceKey(stepIndex, raw.turnIndexed))
            // The newest turns' shapes, from the steps as they were just read — reused items or not: the shapes are
            // the diagnostics' account of the record as this read found it.
            val shape = if (i >= kept.size - SHAPE_TURNS) HeadlessTranscript.shape(turn, stepIndex) else null
            RecordTurn(stepIndex, count, turn.prompt, turn.projectMode, items, shape, errorMessage = HeadlessTranscript.errorMessage(turn), turnIndexed = raw.turnIndexed)
        }
        return RecordWindow(raw.total, firstStep, turns, leading, state ?: previous?.state, now, newestTurn = kept.lastOrNull()?.first, turnIndexed = raw.turnIndexed)
    }

    /**
     * [window] with the record's [delta] — the steps appended since the window's [RecordWindow.total] — joined on:
     * the window's newest turn rebuilt from its own steps and the delta's (a turn under way grows; a finished one is
     * unchanged and keeps its items), and every turn the delta starts cut after it. Nothing else is touched: a
     * reopen, a growth read while the chat runs, costs the record's delta and not its newest page again. The window
     * stays the newest [wantTurns] turns, as a tail read leaves it: a turn the delta pushes past them goes (the page
     * before the window has it for the reader's scroll up). [window] itself when the delta is empty; requires
     * [RecordWindow.canAppend].
     */
    fun append(window: RecordWindow, delta: RecordPager.Raw, now: Long, wantTurns: Int = Int.MAX_VALUE, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        val newest = requireNotNull(window.newestTurn) { "a window restored from disk has no steps to append to" }
        require(delta.turnIndexed == window.turnIndexed) { "a delta of the other record's kind" }
        // A turn-indexed delta brings the newest known turn again, whole (it may have grown), and the turns after it.
        val rereadsNewest = delta.turnIndexed && delta.firstStep == window.total - 1
        require(delta.firstStep == window.total || rereadsNewest) { "the delta starts at ${delta.firstStep}, the window ends at ${window.total}" }
        if (delta.steps.isEmpty()) return if (delta.total == window.total) window else RecordWindow(delta.total, window.firstStep, window.turns, window.leading, window.state, now, newest, window.turnIndexed)
        val last = window.turns.last()
        // The newest turn's steps, its prompt first, then everything appended: cut at the prompts into the turn as it
        // is now and the turns that follow it. A turn-indexed delta carries the newest turn whole already.
        val promptStep = newest.prompt?.let { HeadlessStep(userMessage = it, projectMode = newest.projectMode, shape = newest.promptShape) }
        val joined = if (rereadsNewest) delta.steps else listOfNotNull(promptStep) + newest.steps + delta.steps
        val cut = cut(joined, last.stepIndex)
        val rebuilt = cut.mapIndexed { i, cutTurn ->
            val turn = cutTurn.first
            val stepIndex = cutTurn.second
            val count = turn.steps.size + (if (turn.prompt != null) 1 else 0)
            // The turn as it stood: its items stand when nothing was appended to it (the delta went to later turns).
            val items = if (i == 0 && stepIndex == last.stepIndex && count == last.stepCount && turn.prompt == last.prompt) last.items else build(turn, RecordTurn.traceKey(stepIndex, window.turnIndexed))
            val shape = if (i >= cut.size - SHAPE_TURNS) HeadlessTranscript.shape(turn, stepIndex) else null
            RecordTurn(stepIndex, count, turn.prompt, turn.projectMode, items, shape, errorMessage = HeadlessTranscript.errorMessage(turn), turnIndexed = window.turnIndexed)
        }
        // The newest turn read again as it was, and nothing after it: the window stands, nothing to republish.
        if (rereadsNewest && rebuilt.size == 1 && rebuilt[0].items === last.items && delta.total == window.total) return window
        val turns = window.turns.dropLast(1) + rebuilt
        if (turns.size <= wantTurns) return RecordWindow(delta.total, window.firstStep, turns, window.leading, window.state, now, newestTurn = cut.last().first, turnIndexed = window.turnIndexed)
        val kept = turns.takeLast(wantTurns)
        return RecordWindow(delta.total, kept.first().stepIndex, kept, emptyList(), window.state, now, newestTurn = cut.last().first, turnIndexed = window.turnIndexed)
    }

    /** How many of the record's newest turns keep their shapes for the transcript diagnostics. */
    const val SHAPE_TURNS = 6

    /**
     * The blob-backed record's window from a page read turn by turn ([RecordPager.Raw.turns]): each turn the page
     * read is built from its steps — or keeps [previous]'s items when it holds the turn under the same blob id and as
     * whole — and each turn the page reused is [previous]'s as it stands. The newest [wantTurns] of them are the
     * window, with [state] (the turns' timings) in place from the first frame.
     */
    private fun turnWindow(raw: RecordPager.Raw, previous: RecordWindow?, state: RecordState?, now: Long, wantTurns: Int, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        val (turns, cut) = turnTurns(raw, previous, build)
        val kept = if (turns.size > wantTurns) turns.takeLast(wantTurns) else turns
        val newestIndex = kept.lastOrNull()?.stepIndex
        val newest = newestIndex?.let { cut[it] } ?: previous?.newestTurn?.takeIf { previous.turns.lastOrNull()?.stepIndex == newestIndex }
        return RecordWindow(raw.total, kept.firstOrNull()?.stepIndex ?: raw.firstStep, kept, emptyList(), state ?: previous?.state, now, newestTurn = newest, turnIndexed = true)
    }

    /** The turns of a turn-indexed page as [RecordTurn]s (see [turnWindow]), and the read ones' steps as turns by index. */
    private fun turnTurns(raw: RecordPager.Raw, previous: RecordWindow?, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): Pair<List<RecordTurn>, Map<Int, HeadlessTranscript.Turn>> {
        val cut = cut(raw.steps, raw.firstStep).associate { it.second to it.first }
        val old = previous?.takeIf { it.turnIndexed }?.turns?.associateBy { it.stepIndex }
        val shapesFrom = raw.turns.size - SHAPE_TURNS
        val turns = raw.turns.mapIndexedNotNull { i, read ->
            val held = old?.get(read.index)
            if (read.reused) return@mapIndexedNotNull held
            val turn = cut[read.index] ?: return@mapIndexedNotNull null
            val count = turn.steps.size + (if (turn.prompt != null) 1 else 0)
            val same = held != null && held.blobId == read.blobId && held.complete == read.complete && held.stepCount == count && held.prompt == turn.prompt
            val items = if (same) held!!.items else build(turn, RecordTurn.traceKey(read.index, true))
            val shape = if (i >= shapesFrom) HeadlessTranscript.shape(turn, read.index) else held?.shape
            RecordTurn(read.index, count, turn.prompt, turn.projectMode, items, shape, errorMessage = HeadlessTranscript.errorMessage(turn), turnIndexed = true, blobId = read.blobId, complete = read.complete, stepTotal = read.stepTotal, messageSteps = read.messageSteps)
        }
        return turns to cut
    }

    /** [window] with the turns [raw] read again (see `RecordPager.completeTurns`) standing for its own of the same index. */
    fun completed(window: RecordWindow, raw: RecordPager.Raw, now: Long, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        // Only onto the turns the page was read for: a turn the window holds under another blob id changed since, and its own read stands.
        val held = window.turns.associateBy { it.stepIndex }
        val read = RecordPager.Raw(raw.steps, raw.firstStep, raw.total, turnIndexed = true, turns = raw.turns.filter { !it.reused && held[it.index]?.blobId == it.blobId })
        val (turns, cut) = turnTurns(read, window, build)
        if (turns.isEmpty()) return window
        val replaced = window.withTurns(turns.associateBy { it.stepIndex })
        val newestIndex = window.turns.last().stepIndex
        val newest = cut[newestIndex] ?: window.newestTurn
        return RecordWindow(replaced.total, replaced.firstStep, replaced.turns, emptyList(), window.state, now, newest, turnIndexed = true)
    }

    /**
     * [older] (the steps before [window]) joined onto it: the window's leading steps complete the last turn of the
     * older page, and the older turns go before the window's.
     */
    fun prepend(window: RecordWindow, older: RecordPager.Raw, now: Long, wantTurns: Int = Int.MAX_VALUE, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        if (window.turnIndexed && older.turnIndexed && older.turns.isNotEmpty()) {
            val first = window.turns.firstOrNull()?.stepIndex ?: window.firstStep
            // [wantTurns] bounds the page, as it does below: the window's own turns all stay.
            val olderTurns = turnTurns(older, null, build).first.filter { it.stepIndex < first }.let { if (it.size > wantTurns) it.takeLast(wantTurns) else it }
            if (olderTurns.isEmpty()) return window
            return RecordWindow(window.total, olderTurns.first().stepIndex, olderTurns + window.turns, emptyList(), window.state, now, window.newestTurn, turnIndexed = true)
        }
        val joined = RecordPager.Raw(older.steps + window.leading, older.firstStep, older.total.takeIf { it > 0 } ?: window.total, window.turnIndexed)
        val cut = cut(joined.steps, joined.firstStep)
        var leading = if (!joined.turnIndexed && joined.firstStep > 0 && cut.isNotEmpty() && cut.first().first.prompt == null) cut.first().first.steps else emptyList()
        var kept = if (leading.isNotEmpty()) cut.drop(1) else cut
        var firstStep = joined.firstStep
        if (kept.size > wantTurns) {
            kept = kept.takeLast(wantTurns)
            leading = emptyList()
            firstStep = kept.first().second
        }
        val olderTurns = kept.map { cutTurn ->
            val turn = cutTurn.first
            val stepIndex = cutTurn.second
            RecordTurn(stepIndex, turn.steps.size + (if (turn.prompt != null) 1 else 0), turn.prompt, turn.projectMode, build(turn, RecordTurn.traceKey(stepIndex, window.turnIndexed)), errorMessage = HeadlessTranscript.errorMessage(turn), turnIndexed = window.turnIndexed)
        }
        return RecordWindow(window.total, firstStep, olderTurns + window.turns, leading, window.state, now, window.newestTurn, window.turnIndexed)
    }

    /** Each turn of [steps] with the record index of its first step. */
    private fun cut(steps: List<HeadlessStep>, firstStep: Int): List<Pair<HeadlessTranscript.Turn, Int>> {
        val out = ArrayList<Pair<HeadlessTranscript.Turn, Int>>()
        var index = firstStep
        for (turn in HeadlessTranscript.split(steps)) {
            // The blob-backed record's steps carry their turn's index; the step-indexed record's are counted.
            val own = turn.turnIndex
            out += turn to (own ?: index)
            index = if (own != null) own + 1 else index + turn.steps.size + (if (turn.prompt != null) 1 else 0)
        }
        return out
    }
}
