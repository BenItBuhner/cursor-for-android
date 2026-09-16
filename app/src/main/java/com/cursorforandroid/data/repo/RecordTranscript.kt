package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.TimelineItem

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
) {
    /** The key the turn's items are filed under on disk (see `TraceCache`); stable while the record is append-only. */
    val traceKey: String get() = traceKey(stepIndex)

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

    companion object {
        const val TRACE_KEY_PREFIX = TraceCache.RECORD_KEY_PREFIX

        fun traceKey(stepIndex: Int): String = "$TRACE_KEY_PREFIX$stepIndex"
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
) {
    /** Turns before the window: the record has steps before the loaded ones. */
    val hasOlder: Boolean get() = firstStep > 0

    /** How many turns the chat has, counting the ones before the window: the state's count when known, else at least the loaded ones. */
    val turnCount: Int get() = maxOf(state?.turnCount ?: 0, turns.size)

    /** The whole-chat index of the [i]th loaded turn (0 is the chat's first turn), counted back from the newest. */
    fun turnIndex(i: Int): Int = turnCount - turns.size + i

    /** The timing of the [i]th loaded turn, when the state has one for it. */
    fun timing(i: Int): TurnTiming? = state?.timings?.getOrNull(turnIndex(i))

    fun withState(state: RecordState): RecordWindow = RecordWindow(total, firstStep, turns, leading, state, readAtMillis)
}

/**
 * Reads the record a page at a time: the newest turns first ([tail]), the turns before them on request ([before]),
 * and what was appended since a read ([since]). Every read returns the raw steps with their place in the record;
 * [RecordTranscript.window] cuts them into turns and builds their items.
 */
object RecordPager {

    /** Steps [firstStep] until [firstStep] + `steps.size`, out of a record of [total] steps. */
    class Raw(val steps: List<HeadlessStep>, val firstStep: Int, val total: Int)

    /**
     * The record's last [wantTurns] turns (whole ones: read back until [wantTurns] + 1 prompts are in hand, or the
     * record's start), out of [knownTotal] steps when a previous read left that behind (saving the probe), else
     * after one small probe for the size. Null when the record is empty.
     */
    suspend fun tail(api: ConversationRecordApi, agentId: String, wantTurns: Int, knownTotal: Int? = null, pageSize: Int = PAGE_SIZE): Raw? {
        var total: Int = knownTotal ?: 0
        var steps = ArrayList<HeadlessStep>()
        var end: Int
        if (total > 0) {
            // Read the newest page against the known size; the answer says whether the record grew.
            val from = (total - pageSize).coerceAtLeast(0)
            val page = api.fetch(agentId, startIndex = from, limit = pageSize)
            total = page.totalResponses
            if (total <= 0) return null
            steps.addAll(page.steps)
            end = from
            if (from + page.steps.size < total) {
                // Grew since: the rest of the record, up to its new end.
                var next = from + page.steps.size
                var guard = 0
                while (next < total && guard++ < MAX_PAGES) {
                    val more = api.fetch(agentId, startIndex = next, limit = pageSize)
                    if (more.steps.isEmpty()) break
                    steps.addAll(more.steps)
                    next += more.steps.size
                    total = more.totalResponses
                }
            }
        } else {
            val probe = api.fetch(agentId, startIndex = 0, limit = 1)
            total = probe.totalResponses
            if (total <= 0) return null
            end = total
        }
        var pages = 0
        while (end > 0 && pages < MAX_PAGES && prompts(steps) <= wantTurns) {
            val from = (end - pageSize).coerceAtLeast(0)
            val page = api.fetch(agentId, startIndex = from, limit = end - from)
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
        val page = api.fetch(agentId, startIndex = knownTotal.coerceAtLeast(0), limit = 1)
        return page.totalResponses > knownTotal || page.steps.isNotEmpty()
    }

    /** The steps before [firstStep], back until [wantTurns] + 1 more prompts are in hand or the record's start is reached. */
    suspend fun before(api: ConversationRecordApi, agentId: String, firstStep: Int, wantTurns: Int, pageSize: Int = PAGE_SIZE): Raw {
        var steps = ArrayList<HeadlessStep>()
        var end = firstStep
        var total = 0
        var pages = 0
        while (end > 0 && pages < MAX_PAGES && prompts(steps) <= wantTurns) {
            val from = (end - pageSize).coerceAtLeast(0)
            val page = api.fetch(agentId, startIndex = from, limit = end - from)
            total = page.totalResponses
            if (page.steps.isEmpty() && from > 0) break
            steps = ArrayList<HeadlessStep>(page.steps.size + steps.size).apply { addAll(page.steps); addAll(steps) }
            end = from
            pages++
        }
        return Raw(steps, end, total)
    }

    private fun prompts(steps: List<HeadlessStep>): Int = steps.count { it.userMessage != null }

    /** Steps per request: a turn is rarely more than a page or two. */
    const val PAGE_SIZE = 200
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
        val cut = cut(raw.steps, raw.firstStep)
        var leading = if (raw.firstStep > 0 && cut.isNotEmpty() && cut.first().first.prompt == null) cut.first().first.steps else emptyList()
        var kept = if (leading.isNotEmpty()) cut.drop(1) else cut
        var firstStep = raw.firstStep
        if (kept.size > wantTurns) {
            // A page brought more whole turns than the window wants: the newest [wantTurns] are the window, and the
            // steps before them are read again with the page that widens it.
            kept = kept.takeLast(wantTurns)
            leading = emptyList()
            firstStep = kept.first().second
        }
        val reuse = previous?.turns?.associateBy { it.stepIndex }
        val turns = kept.map { cutTurn ->
            val turn = cutTurn.first
            val stepIndex = cutTurn.second
            val count = turn.steps.size + (if (turn.prompt != null) 1 else 0)
            val old = reuse?.get(stepIndex)
            val items = if (old != null && old.stepCount == count && old.prompt == turn.prompt) old.items else build(turn, RecordTurn.traceKey(stepIndex))
            RecordTurn(stepIndex, count, turn.prompt, turn.projectMode, items)
        }
        return RecordWindow(raw.total, firstStep, turns, leading, state ?: previous?.state, now)
    }

    /**
     * [older] (the steps before [window]) joined onto it: the window's leading steps complete the last turn of the
     * older page, and the older turns go before the window's.
     */
    fun prepend(window: RecordWindow, older: RecordPager.Raw, now: Long, wantTurns: Int = Int.MAX_VALUE, build: (HeadlessTranscript.Turn, String) -> List<TimelineItem>): RecordWindow {
        val joined = RecordPager.Raw(older.steps + window.leading, older.firstStep, older.total.takeIf { it > 0 } ?: window.total)
        val cut = cut(joined.steps, joined.firstStep)
        var leading = if (joined.firstStep > 0 && cut.isNotEmpty() && cut.first().first.prompt == null) cut.first().first.steps else emptyList()
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
            RecordTurn(stepIndex, turn.steps.size + (if (turn.prompt != null) 1 else 0), turn.prompt, turn.projectMode, build(turn, RecordTurn.traceKey(stepIndex)))
        }
        return RecordWindow(window.total, firstStep, olderTurns + window.turns, leading, window.state, now)
    }

    /** Each turn of [steps] with the record index of its first step. */
    private fun cut(steps: List<HeadlessStep>, firstStep: Int): List<Pair<HeadlessTranscript.Turn, Int>> {
        val out = ArrayList<Pair<HeadlessTranscript.Turn, Int>>()
        var index = firstStep
        for (turn in HeadlessTranscript.split(steps)) {
            out += turn to index
            index += turn.steps.size + (if (turn.prompt != null) 1 else 0)
        }
        return out
    }
}
