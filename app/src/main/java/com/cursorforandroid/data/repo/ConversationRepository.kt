package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.isLostReply
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.CachedLocalPrompt
import com.cursorforandroid.data.local.CachedRecordTurn
import com.cursorforandroid.data.local.CachedRecordWindow
import com.cursorforandroid.data.local.CachedTurnTiming
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.StagedAttachments
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.data.repo.TimelineBuilder.withUniqueIds
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorLineage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.PendingAttachment
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunOrder
import com.cursorforandroid.domain.TranscriptLoadDiagnostics
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ConversationState(
    val agentId: String,
    val items: List<TimelineItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val activeRunId: String? = null,
    val runStatus: RunStatus? = null,
    val isStreaming: Boolean = false,
    /**
     * The live connection dropped and is being re-established; the trace shown is complete up to the drop and the
     * run is still going. Only meaningful while [isStreaming].
     */
    val isReconnecting: Boolean = false,
    val transcriptUnavailable: Boolean = false,
    /**
     * The chat has turns older than the ones shown. [items] render the newest window of runs; the ones before it
     * are built (and their traces read or replayed) when the reader scrolls up to them (see [ConversationRepository.loadOlder]).
     */
    val hasOlder: Boolean = false,
    /** Older turns are being paged in right now: their run records fetched, or their traces read. */
    val isLoadingOlder: Boolean = false,
    /**
     * The account's own record says the chat runs in Project mode (`agent_mode = AGENT_MODE_PROJECT` on a prompt of
     * its transcript, Extended mode): a coordinator's chat, whatever the list calls it and whatever its tools show.
     */
    val isProjectConversation: Boolean = false,
    /**
     * The last fetch of `/v0/agents/{id}/conversation` failed (a timeout on a transcript of hundreds of turns, a
     * server error) while the run list answered: what is shown is the transcript last read, or none. Null once a
     * fetch has gone through. Shown on the screen rather than swallowed.
     */
    val transcriptError: String? = null,
    /** Where the traces of the turns shown stand: how many are on screen, still coming, gone for good, or failed. */
    val traceStatus: TraceStatus = TraceStatus(),
    /**
     * Extended mode: the account's record could not be read and the documented endpoints stand in for it — the run
     * activity and the `/v0` text, without the account's copy of the turns and, in a Project, without the
     * coordinator's messages the record alone carries whole. Said on the screen in the server's words, never
     * silently (see [RecordFallback]); null while the record serves the chat, or when it has nothing for it.
     */
    val recordFallback: RecordFallback? = null,
    /**
     * Where the messages the account took into its queue stand (see [QueuePlacement], [ConversationRepository.expectDelivery]):
     * the ones [items] shows under their run, which the card above the composer leaves out from the same frame on,
     * and the ones put back on the card. Carried here so the card and the transcript are read off one frame.
     */
    val queuePlacement: QueuePlacement = QueuePlacement.NONE,
)

/**
 * The account's record refused or failed and the documented path stands in (see [ConversationState.recordFallback]):
 * [reason] in the server's words, [sinceMillis] when, [readMillis] how long the read took before it failed, and
 * [retryAfterMillis] the pause the server asked for when it named one — until it has passed the record is not
 * asked again (see `ConversationRepository.RECORD_RETRY_MS` for a failure that named none).
 */
data class RecordFallback(val reason: String, val sinceMillis: Long, val readMillis: Long, val retryAfterMillis: Long?)

/**
 * Of the finished runs the window shows, how many have their trace (thoughts, tool calls, payloads) on screen and
 * why the others do not: [pending] are being read or replayed, [expired] have no log left on the server (and no
 * copy here), [failed] could not be read this time (the network) and will be asked for again on the next fetch.
 */
data class TraceStatus(
    val shown: Int = 0,
    val pending: Int = 0,
    val expired: Int = 0,
    val failed: Int = 0,
) {
    val missing: Int get() = expired + failed
}

/** The failure of a [ConversationRepository.launch] that was stopped from the chat before the server had answered. */
class LaunchCancelledException : RuntimeException("The chat was stopped before it started.")

/** A follow-up shown in the transcript ahead of its request; see [ConversationRepository.stageFollowUp]. */
class StagedFollowUp internal constructor(
    internal val localId: String,
    val text: String,
    internal val attachments: StagedAttachments,
    internal val stagedAt: Long,
    /** The bubble is on screen ahead of the request; false for a queued message, whose card is what the reader sees until the server has it. */
    internal val shown: Boolean = true,
    /** The message the echo stands for, kept so a bubble not shown ahead can be shown once the server accepts. */
    internal val message: V0ConversationMessageDto,
    internal val placeholder: RunDto,
) {
    /** The same message with its attachments where they are now (see `AttachmentStore.committed`). */
    internal fun withAttachments(attachments: StagedAttachments): StagedFollowUp = StagedFollowUp(localId, text, attachments, stagedAt, shown, message, placeholder)
}

/**
 * Owns the transcript for each open agent. History comes from `/v0/agents/{id}/conversation` and
 * `/v1/agents/{id}/runs`; anything happening right now arrives through the [LiveRunHub], which shares one SSE
 * stream per run with the live-notification monitor.
 *
 * The legacy transcript is text only. Every run's thinking, tool calls and subagents live in its event log, which
 * the hub replays for finished runs as long as the API retains it, so the same trace the live view showed is
 * there to dig into after the fact. A trace seen whole — followed live to its result, here or by the notification
 * monitor, or replayed — is kept on disk from then on, so it survives the retention window and a restart. Runs
 * whose log expired before it was ever seen keep their text. The images a prompt carried never come back from the
 * server at all; they are filled in from the on-device [AttachmentStore].
 *
 * Opening a chat is cache-first: the transcript and traces saved on disk (by an earlier visit or the background
 * prefetch) render immediately and the network only revalidates them. What is written back for the transcript is
 * its inputs in the shape the server will report them — never the rendered items, which are derived from them.
 *
 * A chat is opened on its newest turns. The first page of the run list — read as the chat's newest runs whichever
 * order the server lists them in, anchored on the agent's latest run (see [newestRuns]) — and the transcript's text
 * are enough to render the newest [WINDOW_RUNS] runs; the rest of the run records are paged in behind the cursor so
 * every prompt is paired with its own run, and the older turns — their items, and the traces behind them — are
 * built only when the reader scrolls up to them ([loadOlder]), a window at a time. The disk keeps the window that
 * was open, so the next start renders it before the network answers. What the load could not bring — a transcript
 * that timed out, turns whose logs are gone or could not be read — is said on the screen ([ConversationState.transcriptError],
 * [ConversationState.traceStatus]) and in the diagnostics export ([loadDiagnostics]), never swallowed.
 *
 * Prompts sent from this device are on screen before the server has answered — a new chat [launch]es around its
 * prompt, a follow-up appears the moment it is sent — and stay there while the server's transcript and run list
 * catch up with them, each at its own pace.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    private val hub: LiveRunHub,
    private val attachments: AttachmentStore,
    private val cache: ConversationCache? = null,
    private val traceCache: TraceCache? = null,
    /** Prefetching only runs while the app is visible; the default lets tests and the demo skip that question. */
    private val isForeground: () -> Boolean = { true },
    /**
     * The first screen to show this chat. The finished notification for it can go: the user is reading the reply
     * here. Default is nothing, so repository tests do not need a shade.
     */
    private val onOpened: (agentId: String) -> Unit = {},
    private val prefetchLimit: Int = PREFETCH_LIMIT,
    private val prefetchSpacingMs: Long = PREFETCH_SPACING_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Where one agent's own work runs; a seam for tests that need to hold it up and see what the caller did meanwhile. */
    private val entryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The account's own transcript of a chat (Extended mode), for the turns whose documented log has expired and
     * which the disk never held; null (or the capability off) leaves those turns to their text.
     */
    private val record: ConversationRecordApi? = null,
    private val capabilities: suspend () -> Capabilities = { Capabilities.DOCUMENTED },
    /** Where the images the account's transcript carries are kept on the device, like the stream's (see [LiveRunHub]). */
    private val images: GeneratedImageStore? = null,
    /**
     * For a chat on one of the user's machines (`env.type: machine`): whether the machine reports the chat as the
     * one it is busy with (`GET /v0/private-workers`, `activeBcId`) — the account's word on a Remote Control agent
     * when the run records and the stream say nothing. Null when it cannot be told.
     */
    private val machineBusy: suspend (Agent) -> Boolean? = { null },
) {
    /**
     * A prompt sent from this device — the one that launched the chat, or a follow-up: its message and its run, a
     * placeholder until the server has answered, and — once the run finished while we watched — the reply the
     * transcript will carry, for the disk copy.
     */
    private data class LocalPrompt(
        val message: V0ConversationMessageDto,
        val run: RunDto,
        val reply: V0ConversationMessageDto? = null,
        /**
         * The prompt was delivered into [run] while it was under way (the account's queue promoted it as a steer):
         * it is shown after this many of the run's items — the story streamed before it arrived — rather than
         * ahead of the run, where a prompt that started the run goes. Null for a prompt that started its run.
         */
        val steeredAfter: Int? = null,
        /**
         * The account's id for the message, when its send minted one (see [sendStagedVia]): by which the card above the
         * composer leaves the account's row for it out while the bubble stands (see [Entry.queuePlacement]).
         */
        val followupId: String? = null,
    ) {
        /** True once the server has answered with the real run; until then [run] is the placeholder named after the prompt. */
        val filed: Boolean get() = run.id != message.id
    }

    /**
     * A message the account holds in its queue for the chat, sent from here (see [expectDelivery]): the prompt as
     * staged (not shown), and the newest run known when the account took it — the run it waits behind; the run the
     * account starts on it is a newer one. [queuedOnAccount] says the account's queue still listed it the last time
     * the queue was read; false once a read of the queue no longer had it, at which point it has been delivered.
     */
    /**
     * A message the account took into its queue, on the card above the composer and nowhere else until the run the
     * account delivers it on is seen (see [expectDelivery]): [followupId] is the account's id for it, minted by the
     * send that filed it, by which the card's row and this copy are the same message; null for a message queued
     * without one, known by its words alone. [behindRunId] is the newest run known when it was queued (or last seen
     * still queued): a run newer than it is the one to look for the message in. [queuedOnAccount] is the last read of
     * the account's queue's word: whether the message was still listed.
     */
    private class Awaiting(
        val staged: StagedFollowUp,
        val behindRunId: String?,
        val queuedAt: Long,
        val queuedOnAccount: Boolean = true,
        val followupId: String? = null,
        /**
         * How many prompts with these very words the transcript showed when the message was queued: a frame showing
         * more holds the message — the server's copy, ahead of its adoption — and the card leaves it out from that
         * frame on (see [Entry.queuePlacement]). The same words sent twice are told apart by the count.
         */
        val priorCopies: Int = 0,
        /** How many prompts with these words the `/v0` transcript held when the message was queued: a copy beyond them is this message's (see [adoptDelivered]). */
        val priorTranscriptCopies: Int = 0,
    ) {
        fun copy(behindRunId: String? = this.behindRunId, queuedOnAccount: Boolean = this.queuedOnAccount, staged: StagedFollowUp = this.staged, queuedAt: Long = this.queuedAt) =
            Awaiting(staged, behindRunId, queuedAt, queuedOnAccount, followupId, priorCopies, priorTranscriptCopies)
    }

    /**
     * A message delivered from the account's queue and filed in the transcript under [runId] (see [adoptDelivered]),
     * until a read of the account's queue confirms the account no longer lists it. The card leaves it out meanwhile
     * ([ConversationState.queuePlacement]), whatever the last read said. Should the account still list it once the
     * run has ended — the run did not carry it after all — it goes back on the card, said so (see [noteAccountQueue]).
     * [localMessageId] names the echo in [Entry.local]; [images] are its attachments as filed under the run, to go
     * back with it; [endedSeenAt] is when the run was first seen over, by this device's clock.
     */
    private class Delivered(
        val staged: StagedFollowUp,
        val followupId: String?,
        val localMessageId: String,
        val runId: String,
        val steered: Boolean,
        val filedAt: Long,
        /** Its attachments as filed under the run (see [file]); the staged ones until the filing has moved them. */
        var images: List<MessageAttachment>,
        /** The transcript's copies of the words before this message's (see [Awaiting.priorTranscriptCopies]): the copy it was filed off is one beyond. */
        val priorTranscriptCopies: Int,
        /** The frame's copies of the words before this message's (see [Awaiting.priorCopies]): a frame showing no more does not show it. */
        val priorCopies: Int,
        var endedSeenAt: Long? = null,
    )

    /** What the stream of the run being followed has told so far. */
    private data class LiveTrace(val runId: String, val items: List<TimelineItem>)

    /** How much of a run's story [items] carry: its steps and its other rows, for telling a rebuilt fragment from the story it replaces. */
    private fun storySize(items: List<TimelineItem>): Int = items.sumOf { if (it is ActivityGroup) it.steps.size else 1 }

    /**
     * A built timeline with nothing yet from the run being followed, and the inputs it was built from. Every
     * mutation replaces the collection it changes, so identity is all it takes to tell whether the build still
     * stands — which it does for the whole of a run, however many events it streams.
     */
    private class Prefix(entry: Entry, private val liveRunId: String, val items: List<TimelineItem>) {
        private val messages = entry.messages
        private val runs = entry.runs
        private val local = entry.local
        private val traces = entry.traces
        private val partial = entry.partial
        private val promptImages = entry.promptImages
        private val window = entry.window
        private val runsComplete = entry.runsComplete
        val ids: Set<String> = items.mapTo(HashSet(items.size)) { it.id }

        fun matches(entry: Entry, runId: String): Boolean = liveRunId == runId &&
            messages === entry.messages && runs === entry.runs && local === entry.local &&
            traces === entry.traces && partial === entry.partial && promptImages === entry.promptImages &&
            window == entry.window && runsComplete == entry.runsComplete
    }

    /**
     * Everything one record turn's rendering is read from (see `Entry.recordItems`). Two turns render the same when
     * these agree: the turn's own steps and prompt (its items by identity — a re-read that reuses them is the same
     * turn, whatever object carries it), its run and how this device reads the run's status, its timing, the
     * prompt's images, the trace or the live story standing in for the body (by identity: either is replaced
     * whole when it changes), whether it is the live newest turn, the word on a missing body, and which of the
     * stand-in's message calls are an earlier turn's message said again ([repeats], see `recordItems`).
     */
    private class TurnInputs(
        val turn: RecordTurn,
        val run: RunDto?,
        val runStatus: RunStatus?,
        val timing: TurnTiming?,
        val attachments: List<MessageAttachment>?,
        val complete: List<TimelineItem>?,
        val liveItems: List<TimelineItem>?,
        /** The story the run's stream told before it stopped being followed, standing in until the whole trace lands (see `Entry.partial`). */
        val partial: List<TimelineItem>?,
        val liveNewest: Boolean,
        val notice: NoticeCard?,
        val repeats: Set<String> = emptySet(),
    ) {
        fun sameAs(other: TurnInputs): Boolean =
            turn.stepIndex == other.turn.stepIndex && turn.stepCount == other.turn.stepCount && turn.items === other.turn.items &&
                turn.projectMode == other.turn.projectMode && turn.prompt == other.turn.prompt &&
                run == other.run && runStatus == other.runStatus && timing == other.timing && attachments == other.attachments &&
                complete === other.complete && liveItems === other.liveItems && partial === other.partial && liveNewest == other.liveNewest && notice == other.notice &&
                repeats == other.repeats
    }

    /** A record turn's items as last rendered, with the inputs they were rendered from. */
    private class RenderedTurn(val inputs: TurnInputs, val items: List<TimelineItem>)

    /**
     * How the transcript's messages line up with the runs shown. `/v0/agents/{id}/conversation` pairs its user
     * messages with the runs by position, oldest first; the window renders the newest runs, so the messages shown
     * start at the prompt of the first of them.
     */
    private class Layout(
        /** The transcript's messages shown: from the prompt of the oldest run in the window on. */
        val messages: List<V0ConversationMessageDto>,
        /** The runs those messages pair with by position, oldest first. */
        val paired: List<RunDto>,
        /** The runs shown after them — runs the transcript has no prompt for — each with the prompt sent from here when there is one. */
        val standing: List<RunDto>,
        /** Runs (and their prompts) older than the window: what [ConversationRepository.loadOlder] would bring. */
        val olderCount: Int,
        /** How many turns the chat has, runs in hand or not (see `Entry.chatTotal`). */
        val total: Int,
        /** How many of the window's prompts come before the first run in hand: they render without a run. */
        val runOffset: Int = 0,
    )

    /**
     * Everything shown for one agent is derived from a few inputs, so a trace that lands, a follow-up that is sent
     * or a live snapshot that arrives all rebuild the same way. Mutations happen under the entry's monitor.
     */
    private inner class Entry(val agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + entryDispatcher)
        val state = MutableStateFlow(ConversationState(agentId))
        /** The legacy transcript as the server returned it (or as the disk remembered it). */
        var messages: List<V0ConversationMessageDto> = emptyList()
        /**
         * The v1 runs as the server returned them (or as the disk remembered them): the newest ones first of all, and
         * the older ones behind them as far as the list has been paged (see [runsComplete]).
         */
        var runs: List<RunDto> = emptyList()
        /** True once the run list has been read to its end (or as far as it is ever read): [runs] are all there are. */
        var runsComplete = true
        /** How the server listed the runs on the last fetch (see [newestRuns]); null before one. For the diagnostics. */
        var runOrder: RunOrder? = null
        /** The agent's latest run was missing from the list's first page and fetched by id on the last fetch. */
        var latestFetchedById = false
        /** The last `/v0` transcript fetch failed while the runs answered (see [ConversationState.transcriptError]). */
        var transcriptError: String? = null
        /** The reader's last scroll up met a page of run records that could not be read; the word under the transcript is that page's (see [loadOlderNow]). */
        var olderPageFailed = false
        /**
         * Finished runs whose log could not be read on the last pass — the network, not the log's age — and are worth
         * asking for again (see [ConversationRepository.retryTraces]); cleared as each is queued again.
         */
        val failedTraces = HashSet<String>()
        /** Where the next page of older run records starts; null when there is none to fetch. */
        var olderRunsCursor: String? = null
        /** How many of the newest runs are rendered; grows by [WINDOW_RUNS] each time the reader asks for older turns. */
        var window = WINDOW_RUNS
        /** Older turns are being paged in (see [ConversationRepository.loadOlder]). */
        var loadingOlder = false
        /** Pages the rest of the run records behind the first one, so every prompt pairs with its run. */
        var runPagingJob: Job? = null
        /** Prompts sent from here that the server has not reported in full yet (see [items]). */
        var local: List<LocalPrompt> = emptyList()
        /**
         * Per finished run: its complete trace — replayed from the retained log, followed live to its `result`, or
         * read back from disk. Nothing partial belongs here — the builder lets a trace stand in for the run's
         * transcript, and [loadTraces] never asks for a run again once it is present.
         */
        var traces: Map<String, List<TimelineItem>> = emptyMap()
        /**
         * Runs whose trace in [traces] came off the disk from a build that did not read the coordinator's message
         * tool or the goal tools, so its message calls have no body and its goal no objective (see
         * [CoordinatorTranscript.needsRefresh], [GoalTranscript.needsRefresh]). Shown as they are —
         * re-read on the way to the screen — and asked for again like a run without a trace: the replay, or the
         * account's record, brings the bodies and replaces the file. Cleared as each lands.
         */
        val staleTraces = HashSet<String>()
        /** The account's record said a prompt of this chat was sent in Project mode (see [ConversationState.isProjectConversation]). */
        var projectMode = false
        /**
         * The account's own record of the chat as far as it has been read (Extended mode; see [RecordWindow]). When
         * set, the transcript is built from it — its newest turns, tool calls and payloads included, whatever the
         * documented run log still has — and the `/v0` text and the `/v1` replays are not consulted; the `/v1` run
         * list still gives each turn its run (status, footer) and names the live one.
         */
        var recordWindow: RecordWindow? = null
        /** The record answered with nothing for this chat (not served for it, or nothing yet): the documented path stands. */
        var recordEmpty = false
        /** The last read of the record failed (the network); what stands is the copy last read, or the documented path. */
        var recordError: String? = null
        /**
         * Until when the record is not asked again after it refused or failed with nothing of it on screen: the pause
         * the server named, else [RECORD_RETRY_MS]. A load meanwhile is the documented path's at once, the refusal
         * standing on the screen (see [ConversationState.recordFallback]); the next load after it asks again.
         */
        var recordRefusedUntil = 0L
        /** The transcript is built from the account's record (see [recordWindow]). */
        val fromRecord: Boolean get() = recordWindow != null
        /**
         * The record's page before the window, read ahead of the reader's next scroll up once they have scrolled up
         * once (see [prefetchOlderRecord]): a page of two hundred steps with their payloads is megabytes over a phone's
         * connection, and read when asked for it was the seconds of "Loading older…" at the top. One page, the
         * screen's alone: it goes with the screen.
         */
        var prefetchedOlder: RecordPager.Raw? = null
        var prefetchJob: Job? = null
        /**
         * The story so far of the run [streamJob] is following, shown in place of the transcript it does not have
         * yet. The job owns it: it goes when following stops, and a snapshot from a job that is no longer the
         * follower is ignored. Left behind, it would keep standing in for a run that has since finished — hiding the
         * reply and the footer — and pass for a complete trace, so the run would never be replayed either.
         */
        var live: LiveTrace? = null
        /**
         * Per run no longer followed: the story its stream told before the follow ended without the whole — the
         * screen paused or left, the next run came, the stream broke and the run record ended the turn. It stands
         * in for the run's trace, tool calls and text as they were streamed, until the replay (or the record) brings
         * the whole trace, which replaces it (see [traces]; a run in both is shown from [traces]). Dropped with the
         * follow until 0.3.47: the previous turn's streamed calls and text vanished the moment the next run was
         * followed, and stayed gone until its replay landed — for good, when its log had expired (Bennett's
         * 2026-09-20 export: `run …883d2a FINISHED trace=pending items=0` under his prompt, the footer alone).
         */
        var partial: Map<String, List<TimelineItem>> = emptyMap()
        /**
         * Messages the account took into its queue from here, behind the turn under way, and has not delivered yet
         * as far as this device knows (see [ConversationRepository.expectDelivery]): each is filed under the run the
         * account starts on it the moment that run is seen, so it moves from the card into the transcript rather
         * than out of both.
         */
        var awaiting: List<Awaiting> = emptyList()
        /** Messages filed from the account's queue the account has not yet confirmed gone from it (see [Delivered]). */
        var delivered: List<Delivered> = emptyList()
        /** Messages put back on the card after the run they were filed under ended without them, by followup id, with the card's word. */
        var returned: Map<String, String> = emptyMap()

        /**
         * Where the queued messages stand, for the frame [items] being published (see [ConversationState.queuePlacement]):
         * the messages filed under their run ([delivered]), and the ones still waiting whose words the frame shows
         * already — the server's copy, from the transcript or the record, a frame ahead of the filing — so the card
         * never shows a message the transcript it is drawn beside shows, whichever source drew it first.
         */
        fun queuePlacement(items: List<TimelineItem>): QueuePlacement {
            val bubbles = local.filter { !it.filed }
            if (delivered.isEmpty() && returned.isEmpty() && awaiting.isEmpty() && bubbles.isEmpty()) return QueuePlacement.NONE
            val ids = HashSet<String>()
            val texts = HashSet<String>()
            val waiting = ArrayList<PendingFollowup>()
            val shown = HashMap<String, Int>()
            for (item in items) if (item is UserMessage) shown.merge(QueuePlacement.textKey(item.text), 1, Int::plus)
            // A message the composer shows as a bubble ahead of its request is off the card for as long as the bubble
            // stands: the account may list it — the send's reply still on its way back — before the bubble has come
            // down for the card to take it (see [sendStagedVia]). Its place is the bubble, whatever the list says.
            // Kept apart from the delivered sets: a bubble's coming is no reason to read the account's queue again.
            val shownIds = bubbles.mapNotNullTo(HashSet()) { it.followupId }
            val shownTexts = bubbles.mapTo(HashSet()) { QueuePlacement.textKey(it.message.text) }
            // A message filed under its run: off the card while the frame shows it — which is every frame, bar a
            // conversation rewound past it, when the account's list (which still names it) is the place it is.
            for (d in delivered) {
                val key = QueuePlacement.textKey(d.staged.text)
                if ((shown[key] ?: 0) > d.priorCopies) { if (d.followupId != null) ids += d.followupId else texts += key }
            }
            if (awaiting.isNotEmpty()) {
                for (a in awaiting) {
                    val key = QueuePlacement.textKey(a.staged.text)
                    if ((shown[key] ?: 0) > a.priorCopies) {
                        // The frame shows it — the server's copy ahead of the filing: off the card from this frame on.
                        if (a.followupId != null) ids += a.followupId else texts += key
                    } else {
                        // Not shown yet: on the card, from this device's own knowledge, until it is — with what it
                        // carries, as the account's own row would say it: its files by name and type, its pictures by count.
                        val carried = a.staged.attachments.attachments
                        waiting += PendingFollowup(
                            id = a.followupId ?: "local:${a.staged.localId}",
                            text = a.staged.text,
                            createdAtMillis = a.queuedAt,
                            files = carried.filter { it.isFile }.map { PendingAttachment(it.name ?: "Document", it.mimeType.orEmpty()) },
                            imageCount = carried.count { !it.isFile },
                            note = if (a.queuedOnAccount) null else QueuePlacement.DELIVERING_NOTE,
                        )
                    }
                }
            }
            if (ids.isEmpty() && texts.isEmpty() && returned.isEmpty() && waiting.isEmpty() && shownIds.isEmpty() && shownTexts.isEmpty()) return QueuePlacement.NONE
            return QueuePlacement(deliveredIds = ids, deliveredTexts = texts, returned = returned, waiting = waiting, shownIds = shownIds, shownTexts = shownTexts)
        }
        /**
         * Finished runs whose traces are wanted and not yet in memory, by run id, in the order they were asked for.
         * One worker ([traceJob]) drains it, disk first, then the retained logs; asking for more runs adds to it
         * rather than replacing the pass under way, so a replay of forty runs is never cut short by the forty-first.
         */
        val traceQueue = LinkedHashMap<String, RunDto>()
        /** The runs the worker has taken off the queue and not settled yet; back on it if the pass is cut short. */
        val traceInFlight = LinkedHashMap<String, RunDto>()
        /**
         * Runs whose retained log is gone (`410 stream_expired`) or older than one that is: never asked for again this
         * session. The retention window is time-based, so once one run's log has expired every older run's has too.
         */
        var expiredBefore = Long.MIN_VALUE
        val expiredRuns = HashSet<String>()
        /** Runs whose finish has been folded into the inputs and written back; the screen and the hub both report it. */
        val recordedFinishes = HashSet<String>()
        var transcriptUnavailable = false
        /** The agent row's `updatedAt` the inputs correspond to; the prefetch skips rows that have not moved. */
        var inputsUpdatedAt = 0L
        /** True once this session fetched the inputs from the network (as opposed to disk). */
        var fetched = false
        /** When the inputs were last fetched; a return to the foreground moments later does not fetch them again. */
        var fetchedAt = 0L
        /** Per run: the images its prompt carried. A prompt in flight is keyed by its placeholder run. */
        var promptImages: Map<String, List<MessageAttachment>> = emptyMap()
        var streamJob: Job? = null
        var traceJob: Job? = null
        var loadJob: Job? = null
        /** A forced revalidation asked for while [loadJob] was in flight: the chat is read again once it lands (see [revalidateNow]). */
        var rereadAfterLoad = false
        /** Cuts the window back a while after the last screen left (see [trimWindow]); cancelled by a screen coming back. */
        var trimJob: Job? = null
        /**
         * Keeps the chat followed while the account calls it running and no stream is open on a run of it: a turn the
         * record ended while the account ran on (a steer's next run on its way), a chat with no run record or stream
         * to be had (a machine agent's). Finds the next run and follows it, and meanwhile reads the record's tail
         * (Extended mode) so the steps still arrive (see [keepFollowing]).
         */
        var keepFollowingJob: Job? = null
        /** The request creating this chat, from the moment its prompt is shown until the server has answered (see [launch]). */
        var launch: Deferred<Result<Launched>>? = null
        @Volatile var launching = false
        var attached = 0
        /**
         * Nothing can see the chat although a screen is still attached (see [pause]). A load that was already in
         * flight when this went up finishes — its inputs are worth having — but must not open a stream or replay a
         * log behind a screen nobody is looking at.
         */
        @Volatile var paused = false
        /**
         * Bumped by every [pause] and [detach]. [resume] and [revalidate] hand their work to [scope] rather than
         * doing it under the entry's monitor, so the screen can have stopped again by the time it runs; the count
         * taken when they were called is what tells them the entry has moved on since.
         */
        @Volatile var stops = 0
        /**
         * The run this device's [cancelRun] stopped. A load that was in flight when the cancel went out read the
         * run's record before it, and starting to follow the run is no word from the run: neither may put the chat
         * back to running for it (see [statusOf]) — which is what held a steer sent the moment a chat opened until
         * the stream ended. The run's own events, and a terminal status from anywhere, still have the last word.
         */
        @Volatile var cancelledRunId: String? = null

        /**
         * The status a run record gives the chat: an active status for a run this device saw end reads as it ended —
         * the run it cancelled as cancelled, and the run the hub followed to its finish as the hub saw it finish
         * (`AgentRepository.endedStatus`, the list's memory of each chat's last ended run). A record that predates
         * the end — the run page a load read before the Stop, the record a next-run look reads a poll behind the
         * stream — is not the run's word on itself. Nor is an active record of a run that began before the one this
         * device saw end (`AgentRepository.endedBefore`): the agent ran that one first, so the older run is over,
         * how being what the next read of its record says — [RunStatus.UNKNOWN] until then, never running. The case
         * is a load whose run page was read before a steer's Stop of a turn the page did not hold yet, landing after
         * it: followed as the turn under way, that page's run put the spinner back on a turn long over and held the
         * steered message behind a stream that had nothing to say.
         */
        fun statusOf(run: RunDto): RunStatus {
            val status = run.statusEnum()
            if (!status.isActive) return status
            if (run.id == cancelledRunId) return RunStatus.CANCELLED
            agents.endedStatus(agentId, run.id)?.let { return it }
            return if (agents.endedBefore(agentId, run)) RunStatus.UNKNOWN else status
        }

        /**
         * [run]'s record as this device knows it (see [statusOf]): the run it cancelled reads cancelled, the run it
         * saw finish reads finished, whatever a record written before the end says. What the agent's row is patched
         * from (see `Agent.withLatestRun`): a load whose run page was read before the Stop, landing after it, put the
         * row back to running for the very run this device had stopped — after the run's own end had already settled
         * it, so nothing settled it again until the follow-up queue's busy recheck, twenty seconds on.
         */
        fun known(run: RunDto): RunDto {
            val status = statusOf(run)
            return if (status == run.statusEnum()) run else run.copy(status = status.name)
        }

        /**
         * Following a run still under way: the follower alive with the run's story so far in hand, the stream
         * shown as open, and the run not one this device saw end. Neither of the first two says so on its own: the
         * follower's job outlives the finished snapshot by the finish's own bookkeeping (the read marker, the write
         * to disk), and the story stands in for the trace after a finish read off the record — a load landing in
         * that window (a foreground return as the turn ends) read the two as "streaming" and called the chat
         * RUNNING over its footer, until the next-run looks gave up.
         */
        fun isFollowingLive(): Boolean {
            val current = live ?: return false
            return streamJob?.isActive == true && state.value.isStreaming && agents.endedStatus(agentId, current.runId) == null
        }

        /**
         * The chat's status as the screen shows it, the freshest word first. A run being streamed is running,
         * whatever any record says of it. Next the agent's row: when the account calls the chat running and its
         * word is at least as new as the latest run record's, the chat is running — a record that says cancelled
         * or finished is then an older turn's, or a stale copy (the cache, a `/v1` list that lags the account), and
         * a new run is on its way into the list. Only then the latest run's own status. A terminal state is never
         * shown while anything fresher calls the chat active.
         */
        fun chatStatus(latest: RunDto?, streaming: Boolean = isFollowingLive()): RunStatus? {
            if (streaming) return state.value.runStatus?.takeIf { it.isActive } ?: RunStatus.RUNNING
            val recorded = latest?.let { statusOf(it) }
            if (recorded?.isActive == true) return recorded
            val row = agents.agent(agentId) ?: return recorded
            if (row.latestRunId == cancelledRunId && cancelledRunId != null) return recorded
            // The account's word: the row's own status, or the account list's running set (Extended mode; it says
            // what runs without touching the row's run-level status). Not the legacy list's pass: its execution
            // status lags a finish, which is the stale spinner the run records are read to end.
            val accountRunning = row.isRunning || agents.runningScan.value.accountIds?.contains(agentId) == true
            if (!accountRunning) return recorded
            val recordAt = latest?.let { parseIsoMillis(it.updatedAt) } ?: 0L
            // Running, and with activity after the record was last written (or a run the record is not): the record
            // is an older turn's, or stale. A record as new as the row is the turn that just ended.
            val fresher = latest == null || row.latestRunId != latest.id || row.updatedAtMillis > recordAt + STALE_RUN_RECORD_SLACK_MS
            return if (fresher) RunStatus.RUNNING else recorded
        }

        /** True while the account or the run records call the chat active (see [chatStatus]). */
        fun isChatRunning(): Boolean = chatStatus(latestRun())?.isActive == true

        /** The account's word, without the run records: the row's status, or the account list's running set (Extended mode). */
        fun rowSaysRunning(): Boolean {
            val row = agents.agent(agentId)
            return (row?.isRunning == true || agents.runningScan.value.accountIds?.contains(agentId) == true) && (cancelledRunId == null || row?.latestRunId != cancelledRunId)
        }

        /** The row runs on a run other than [runId]: the account has moved past it (see [chatStatus]). */
        fun accountNamesNewerRun(runId: String): Boolean {
            val row = agents.agent(agentId) ?: return false
            val rowLatest = row.latestRunId ?: return false
            return row.isRunning && rowLatest != runId && !rowLatest.startsWith(LOCAL_RUN_PREFIX)
        }
        /** The workers last reported to the agent list from this transcript (see [coordinatorLineage]); null before any. */
        var reportedWorkers: Set<String>? = null
        /** The stamp of the last prompt staged from here, so two staged in one millisecond get distinct ids (see [stageFollowUp]). */
        var lastLocalStamp = 0L
        /** When the items were last rebuilt and published (monotonic millis), and the trailing publish a burst is waiting on (see [publishCoalesced]). */
        var lastPublishAtMs = 0L
        var pendingPublish: Job? = null
        var lastUsedAt = AppClock.now()
        private var builtPrefix: Prefix? = null
        private var orderedFromRuns: List<RunDto>? = null
        private var orderedFromLocal: List<LocalPrompt>? = null
        private var orderedRuns: List<RunDto> = emptyList()

        val hasInputs: Boolean get() = messages.isNotEmpty() || runs.isNotEmpty() || recordWindow != null
        val isIdle: Boolean get() = attached == 0 && streamJob == null && traceJob?.isActive != true && loadJob?.isActive != true && runPagingJob?.isActive != true && !launching

        /**
         * The server's view joined with the prompts sent from here. `/v0/agents/{id}/conversation` pairs its user
         * messages with the runs by position, oldest first, and the two endpoints catch up with a new run
         * independently: right after a launch the run list has the run while the transcript is still empty, and a
         * follow-up's prompt can be in the transcript before the run list has its run. So the transcript is laid
         * over as many runs as it has prompts for — the runs of prompts sent from here included, so a prompt the
         * transcript already lists pairs with the run only the local copy has — and every run past that is rendered
         * on its own, with the prompt sent from here when there is one, headed by the run alone when there is none
         * (an agent without a transcript, a run started elsewhere the transcript has not caught up with). Whichever
         * endpoint reports a prompt sent from here first, it shows once and never goes missing.
         *
         * Only the newest [window] runs are rendered, with the messages from the first of them on (see [layout]);
         * everything older waits for the reader to scroll up to it.
         *
         * Each segment has unique ids on its own; the join is made unique too, because a follow-up can briefly exist
         * on both sides (the server listed its run while the request was still in flight), and a repeated id aborts
         * the list that renders these.
         */
        fun items(): List<TimelineItem> {
            recordWindow?.let { return recordItems(it) }
            val current = live
            val layout = layout()
            // A followed run is always the newest one there is, so it sorts last and everything the transcript
            // renders before it is untouched by an event. That part is built once and kept until an input actually
            // changes; a streamed event only re-appends the run's own items.
            val tail = current?.takeIf { layout.standing.lastOrNull()?.id == it.runId && it.runId !in traces }
                ?: return build(shownTraces(), layout)
            val prefix = builtPrefix?.takeIf { it.matches(this, tail.runId) } ?: buildPrefix(tail.runId, layout)
            val steered = local.firstOrNull { it.run.id == tail.runId && it.steeredAfter != null }
            val story = if (steered == null) tail.items else tail.items.toMutableList().also { spliceSteered(it, layout.standing.last(), steered, mapOf(tail.runId to tail.items)) }
            return prefix.items + story.withUniqueIds(prefix.ids)
        }

        /**
         * The transcript from the account's record (Extended mode): each loaded turn's prompt — an injected turn as
         * its row — and its trace, paired with its run from the newest turn back (the record's last turn is the
         * chat's last run) for the footer and the live state. A turn whose run the list has not fetched gets its
         * footer from the turn's timing instead. The run being followed shows its story so far in place of the
         * record's copy of the turn, and a run followed to its end shows the complete trace the stream gave; prompts
         * sent from here that the record has not caught up with trail the record's turns, as pending.
         */
        private fun recordItems(window: RecordWindow): List<TimelineItem> {
            val ordered = allRuns()
            // The prompts sent from here the record has not caught up with. Each stands where its run's creation puts
            // it among the record's turns — before the first turn that started after it, when the account's timings
            // say so, else after them all: a reply is never shown above the prompt it answers, whichever source each
            // came from (see [turnOf], and the order this keeps, `LocalEchoOrderTest`).
            val trailing = local.filter { window.turnOf(it) == null }
            val trailingIds = trailing.mapTo(HashSet()) { it.run.id }
            val paired = ordered.filter { it.id !in trailingIds }
            val offset = paired.size - window.turns.size
            val current = live
            // Running by the freshest word there is (see [chatStatus]): the newest turn is under way, whatever a
            // stale run record says of it.
            val latest = latestRun()
            val chatRunning = chatStatus(latest)?.isActive == true
            val items = ArrayList<TimelineItem>(window.turns.size * 4 + 8)
            val shown = HashSet<Int>(window.turns.size * 2)
            val perf = TranscriptPerf.session(agentId)
            val pending = trailing.filterNot { it.filed }.mapTo(HashSet()) { it.run.id }
            fun echo(prompt: LocalPrompt): List<TimelineItem> =
                TimelineBuilder.fromHistory(listOfNotNull(prompt.message, prompt.reply), listOf(prompt.run), shownTraces(), promptImages, pending, partial = keptStories().keys)
            fun startOf(i: Int): Long? {
                val run = paired.getOrNull(offset + i)
                return run?.let { parseIsoMillis(it.createdAt).takeIf { ms -> ms > 0 } } ?: window.turnStartedAt(i)
            }
            // Where each trailing prompt goes: the index of the first turn that started after its run was created, or null for the end.
            val insertBefore: Map<LocalPrompt, Int?> = trailing.associateWith { prompt ->
                val sentAt = if (prompt.filed) parseIsoMillis(prompt.run.createdAt).takeIf { it > 0 } else null
                if (sentAt == null) null else window.turns.indices.firstOrNull { i -> (startOf(i) ?: Long.MIN_VALUE) > sentAt }
            }
            val appended = trailing.filter { insertBefore[it] == null }
            // The coordinator's messages drawn so far in this pass, as their text reads: what a later turn's stand-in
            // is compared with (see [repeats] below). Only the turns' own rendering adds to it, in window order.
            var messagesShown: MutableSet<String>? = null
            for ((i, turn) in window.turns.withIndex()) {
                trailing.forEach { prompt -> if (insertBefore[prompt] == i) items += echo(prompt) }
                val run = paired.getOrNull(offset + i)
                // The newest turn of the record is the live one while the chat runs and nothing sent from here trails it.
                val newest = i == window.turns.lastIndex && appended.isEmpty()
                val complete = run?.let { traces[it.id] }
                val liveItems = if (complete == null && run != null && current?.runId == run.id && current.items.isNotEmpty()) current.items else null
                val kept = if (complete == null && liveItems == null && run != null) partial[run.id]?.takeIf { it.isNotEmpty() } else null
                val liveNewest = newest && chatRunning
                // A run's log (or its stream) standing in for a turn the record shows without any call of the
                // coordinator's message tool: the turn sent nothing, so a message the log carries that reads as one
                // drawn earlier is that earlier turn's message said again — a coordinator's log can carry the last
                // message ahead of the run's own events (Bennett's 2026-09-19 frame: one reply per silent run, the
                // same words each time). Left out here, where the sources are known; a message the log carries that
                // no earlier turn showed is drawn, the record having kept the call in no shape this app reads (#202).
                // Never for the turn being streamed: the record's copy of it lags the stream, so its own message
                // would read as one it has not sent yet — and its own words are never a copy of anything.
                val standIn = complete ?: liveItems ?: kept
                val repeats = if (standIn != null && !turn.hasMessageCall && !(liveItems != null && liveNewest)) CoordinatorTranscript.messageCallsReading(standIn, messagesShown ?: emptySet()) else emptySet()
                val inputs = TurnInputs(
                    turn = turn,
                    run = run,
                    runStatus = run?.let { statusOf(it) },
                    timing = window.timing(i),
                    attachments = run?.let { promptImages[it.id] },
                    complete = complete,
                    liveItems = liveItems,
                    partial = kept,
                    liveNewest = liveNewest,
                    // A turn without its steps says so; so does one whose reply the transcript gave while its log is gone.
                    notice = if (complete == null && liveItems == null && kept == null && (!turn.hasBody || turn.activityMissing) && !liveNewest) turnBodyNotice(turn, run) else null,
                    repeats = repeats,
                )
                // A turn whose inputs have not moved is the items it was last rendered to — the same instances, so
                // everything downstream (the rows, the screen's rows) can tell it has not changed without reading it.
                val rendered = renderedTurns[turn.stepIndex]?.takeIf { it.inputs.sameAs(inputs) }?.also { perf.turnRenderReused() }
                    ?: RenderedTurn(inputs, renderTurn(inputs)).also { renderedTurns[turn.stepIndex] = it; perf.turnRendered() }
                items.addAll(rendered.items)
                shown += turn.stepIndex
                val texts = CoordinatorTranscript.messageTexts(rendered.items)
                if (texts.isNotEmpty()) (messagesShown ?: HashSet<String>().also { messagesShown = it }).addAll(texts)
            }
            // The cache holds the window's turns and nothing else: a turn paged out (see [trimWindow]) leaves it.
            if (renderedTurns.size > shown.size) renderedTurns.keys.retainAll(shown)
            appended.forEach { prompt -> items += echo(prompt) }
            return items.withUniqueIds()
        }

        /** The rendered items of the window's turns, by step index; see [recordItems]. Under the entry's monitor. */
        private val renderedTurns = HashMap<Int, RenderedTurn>()

        /** The items the turn at [stepIndex] was last rendered to (see [recordItems]), for the diagnostics; null before it was. */
        fun renderedItems(stepIndex: Int): List<TimelineItem>? = renderedTurns[stepIndex]?.items

        /**
         * The stand-in the turn at [stepIndex] was last rendered from (its run's log or stream) and the keys of the
         * message calls left out of it as an earlier turn's message said again (see [recordItems]), for the
         * diagnostics; nothing when the turn was rendered from the record.
         */
        fun renderedRepeats(stepIndex: Int): Pair<List<TimelineItem>, Set<String>> {
            val inputs = renderedTurns[stepIndex]?.inputs ?: return emptyList<TimelineItem>() to emptySet()
            return (inputs.complete ?: inputs.liveItems ?: inputs.partial ?: emptyList()) to inputs.repeats
        }

        /**
         * One turn of the record as the transcript shows it: its prompt (an injected turn as its rows), then its
         * trace — the stream's complete one, the story so far of the run being followed, the record's own body, or
         * the record's turn with the word on its missing body — and its footer where one belongs (see [renderTurn]).
         */
        private fun renderTurn(inputs: TurnInputs): List<TimelineItem> {
            val turn = inputs.turn
            val run = inputs.run
            val timing = inputs.timing
            val items = ArrayList<TimelineItem>((inputs.complete ?: inputs.liveItems ?: inputs.partial ?: turn.items).size + 4)
            val startedAt = run?.let { parseIsoMillis(it.createdAt).takeIf { ms -> ms > 0 } }
                ?: timing?.timestampMs?.let { end -> end - (timing.durationMs ?: 0L) }?.takeIf { it > 0 }
            turn.prompt?.let { text ->
                val promptId = "rec-prompt-${turn.stepIndex}"
                items += SystemNotifications.parse(promptId, text, startedAt)?.items
                    ?: listOf(UserMessage(promptId, text, startedAt, attachments = inputs.attachments ?: emptyList()))
            }
            when {
                // The trace the stream gave, whole: its own footer included — less an earlier turn's message it
                // carries again (see [recordItems]).
                inputs.complete != null -> { items += CoordinatorTranscript.withoutRepeats(inputs.complete, inputs.repeats); return items }
                // The story the stream tells so far — unless it has told nothing yet (a stream that will not
                // open, a machine agent's): then the record's copy of the turn, as far as it has been read.
                inputs.liveItems != null -> { items += CoordinatorTranscript.withoutRepeats(inputs.liveItems, inputs.repeats); return items }
                // The story the stream told before the follow ended, until the whole trace lands (see [Entry.partial]):
                // with its footer when the stream's end gave one, else the run's below.
                inputs.partial != null -> {
                    // With the record's own words for the turn, when the stream never delivered them.
                    items += TimelineBuilder.withReplies(CoordinatorTranscript.withoutRepeats(inputs.partial, inputs.repeats), turn.items.filterIsInstance<AssistantMessage>())
                    if (inputs.partial.any { it is RunFooter }) return items
                }
                // The record's own body — with the reply the transcript gave when the record lacked it and the log
                // was gone, in which case the turn still says its activity is not to be had (see [fillTextFromTranscript]).
                turn.hasBody -> {
                    items += turn.items
                    if (turn.activityMissing) inputs.notice?.let { items += it }
                }
                // The record has the turn without its steps: the run's log stands in when it has been replayed
                // (see [recordTurnsNeedingReplay]); until then, or when it is gone, the turn says so itself.
                else -> {
                    items += turn.items
                    inputs.notice?.let { items += it }
                }
            }
            // The footer: the run's when known and over, else the timing's for a turn that is over. Its id is the
            // turn's whichever gives it, so the row keeps its place when the run list lands. A run the server says
            // failed takes its reason from the record's own error when the run's record carries none; a run that
            // logged an error and finished is not a failure for it (see RecordTurn.errorMessage).
            when {
                inputs.liveNewest -> Unit
                run != null -> {
                    val status = inputs.runStatus
                    if (status != null && !status.isActive) {
                        val footer = TimelineBuilder.footer(run).copy(id = "rec-footer-${turn.stepIndex}", status = status)
                        items += if (footer.isFailure && footer.reason == null) footer.copy(reason = turn.errorMessage) else footer
                    }
                }
                timing?.durationMs != null ->
                    items += RunFooter("rec-footer-${turn.stepIndex}", "rec-${turn.stepIndex}", RunStatus.FINISHED, timing.durationMs, emptyList())
            }
            return items
        }

        /**
         * What a turn without a body says for itself, once its run's log has been asked for: the log is gone, or it
         * could not be read this time. Nothing while the replay is still on its way (the status line above the
         * transcript says so), and nothing when the run is not known yet.
         */
        private fun turnBodyNotice(turn: RecordTurn, run: RunDto?): NoticeCard? {
            val id = "rec-body-${turn.stepIndex}"
            val replyWord = if (turn.textFromTranscript) "the reply is the transcript's." else "the reply is shown when the record has it."
            return when {
                run == null -> null
                run.id in expiredRuns || parseIsoMillis(run.createdAt) < expiredBefore -> NoticeCard(id, "This turn's activity is no longer available", "Cursor no longer has its log; $replyWord", NoticeTone.Neutral)
                run.id in failedTraces -> NoticeCard(id, "This turn's activity couldn't be loaded", "Retry from the line above the transcript.", NoticeTone.Warning)
                else -> null
            }
        }

        /**
         * The runs whose turn the record holds without its steps — an account whose record keeps the prompts and
         * files the bodies elsewhere — or, in a coordinator's chat, without the coordinator's word to the user, and
         * whose log is the one place left to read them from: the finished runs paired with such turns, newest first,
         * that no replay has answered for yet.
         */
        fun recordTurnsNeedingReplay(): List<RunDto> {
            val window = recordWindow ?: return emptyList()
            val ordered = allRuns()
            val trailing = local.filter { window.turnOf(it) == null }.mapTo(HashSet()) { it.run.id }
            val paired = ordered.filter { it.id !in trailing }
            val offset = paired.size - window.turns.size
            return window.turns.withIndex().mapNotNull { (i, turn) ->
                val run = paired.getOrNull(offset + i) ?: return@mapNotNull null
                // Without a body; with a body and no reply in an ordinary chat — the record gave the calls and not the
                // agent's words, which the log has (and the transcript after it, see [fillTextFromTranscript]); or,
                // in a coordinator's chat, with a body the log may complete with the coordinator's word (see
                // RecordTurn.wantsLogForMessage): a message read leniently out of pieces, the user's turn without
                // one, an injected turn the record holds without any call. An injected turn whose calls the record
                // holds, none of them a message, sent none and is left as the record has it.
                val wanting = !turn.hasBody || (if (projectMode) turn.wantsLogForMessage else !turn.hasText)
                run.takeIf { wanting && statusOf(it).isTerminal && it.id !in traces }
            }.asReversed()
        }

        /**
         * The record's window holds more turns than the run list reaches, with older pages of the list still to
         * read. Turns pair with runs by position from the newest, so a turn behind more runs than the list has in
         * hand pairs with none: no footer, and — the coordinator's word to the user being in the run's log alone
         * when the record has it in no shape this app reads — no reply, however many times the record is read
         * (Bennett's v0.3.35 chat: a Project injecting dozens of turns between two of his, and the first page of the
         * list reaching twenty runs). The list is paged until it covers the window (see `loadFromRecord`,
         * `loadOlderFromRecord`), as the documented path pages it before widening.
         */
        fun recordNeedsRuns(): Boolean {
            val window = recordWindow ?: return false
            if (runsComplete || olderRunsCursor == null) return false
            // The runs of prompts sent from here that the record has not caught up with pair with no turn of the window.
            val trailing = local.count { window.turnOf(it) == null }
            return allRuns().size - trailing < window.turns.size
        }

        /**
         * The window's turn that is [prompt]'s — the record has caught up with the prompt sent from here — or null.
         *
         * Until 0.3.33 the record held a prompt when its text was among the window's newest `local.size + 1` turns.
         * A Project coordinator's workers report in as injected turns, dozens in a minute, so by the time the record
         * was read again the user's turn was far from its end: the echo was never matched, never pruned, and stood
         * trailing behind every turn the record had — the reply above the question, restart after restart (Bennett's
         * v0.3.32 frames). Now the whole window is searched for a turn of the user's (never an injected one) whose
         * prompt reads the same once whitespace is normalised — the server trims and reflows what the phone sent.
         * One such turn is the prompt's; among several (the same words sent twice) the one that started closest to
         * the run's creation, when the account's timings say, else the newest. The text is the whole criterion: a
         * turn that merely started around the time the prompt was sent is another question asked a moment before or
         * after, and taking it for this one would make that question disappear.
         */
        fun RecordWindow.turnOf(prompt: LocalPrompt): RecordTurn? {
            val text = normalizePrompt(prompt.message.text)
            if (text.isEmpty()) return null
            val sentAt = if (prompt.filed) parseIsoMillis(prompt.run.createdAt).takeIf { it > 0 } else null
            var match: RecordTurn? = null
            var matchDelta = Long.MAX_VALUE
            for (i in turns.indices.reversed()) {
                val turn = turns[i]
                val recorded = turn.prompt ?: continue
                if (SystemNotifications.isInjected(recorded) || normalizePrompt(recorded) != text) continue
                // Newest first: an older match replaces the newer only when the timings put it closer to the send.
                val startedAt = turnStartedAt(i)
                val delta = if (sentAt != null && startedAt != null) kotlin.math.abs(startedAt - sentAt) else null
                if (match == null || (delta != null && delta < matchDelta)) {
                    match = turn
                    matchDelta = delta ?: matchDelta
                }
            }
            return match
        }

        /** When the [i]th loaded turn started, by the account's timing of it (its end less its duration), when read. */
        private fun RecordWindow.turnStartedAt(i: Int): Long? {
            val timing = timing(i) ?: return null
            val end = timing.timestampMs ?: return null
            return (end - (timing.durationMs ?: 0L)).takeIf { it > 0 }
        }

        /**
         * Where the window sits over the chat. The chat has as many runs as the list holds once it is complete;
         * until then as many as the transcript has prompts for (every prompt starts a run), the runs not fetched yet
         * being its oldest. Run `r`, counted oldest first over the whole chat, pairs with the `r`th prompt; the runs
         * past the prompts stand on their own. The window is the newest [window] runs, and the messages shown start
         * at the prompt of its first run.
         */
        fun layout(): Layout {
            val ordered = allRuns()
            val prompts = shownMessages().count { it.type == USER_MESSAGE }
            val total = chatTotal(ordered, prompts)
            // The runs in hand are the chat's newest; the ones before them are not fetched yet (or not at all).
            val base = total - ordered.size
            // The window is the newest [window] turns of the chat, runs in hand or not: a chat whose runs are still
            // being fetched shows its newest prompts with what there is, never the whole transcript.
            val firstShown = (total - window).coerceAtLeast(0)
            val shown = ordered.drop((firstShown - base).coerceAtLeast(0))
            val pairedCount = (prompts - maxOf(firstShown, base)).coerceIn(0, shown.size)
            return Layout(
                messages = messagesFromPrompt(firstShown),
                paired = shown.take(pairedCount),
                standing = shown.drop(pairedCount),
                olderCount = firstShown,
                total = total,
                runOffset = (base - firstShown).coerceAtLeast(0),
            )
        }

        /**
         * How many runs the chat has, counting the ones the list has not fetched. Exact once the list is complete.
         * Until then every prompt started a run, plus the prompts sent from here that the transcript has not caught
         * up with — told from the transcript's newest prompts, since a prompt sent from here is always the chat's
         * newest — and never fewer than the runs in hand.
         */
        private fun chatTotal(ordered: List<RunDto>, prompts: Int, transcript: List<V0ConversationMessageDto> = shownMessages()): Int {
            if (runsComplete) return ordered.size
            val listed = runs.mapTo(HashSet()) { it.id }
            val unlisted = local.filter { it.run.id !in listed }
            var missing = 0
            if (unlisted.isNotEmpty()) {
                // The transcript's newest prompt: the newest of the prompts sent from here that it has caught up with.
                val newest = transcript.lastOrNull { it.type == USER_MESSAGE }?.text?.trim()
                for (prompt in unlisted.asReversed()) {
                    if (newest != null && newest == prompt.message.text.trim()) break
                    missing++
                }
            }
            return maxOf(prompts + missing, ordered.size)
        }

        /** The transcript from its [index]th user message on (0 is the whole of it; past the last prompt, nothing). */
        private fun messagesFromPrompt(index: Int): List<V0ConversationMessageDto> {
            val shown = shownMessages()
            if (index <= 0) return shown
            var seen = 0
            val start = shown.indexOfFirst { it.type == USER_MESSAGE && seen++ == index }
            return if (start < 0) emptyList() else shown.subList(start, shown.size)
        }

        private var shownMessagesFrom: Triple<List<V0ConversationMessageDto>, List<LocalPrompt>, List<RunDto>>? = null
        private var shownMessagesCache: List<V0ConversationMessageDto> = emptyList()

        /**
         * The transcript as laid over the runs: [messages] less the turns that are a prompt sent from here said
         * again where the transcript's position puts it, when the prompt already stands under the run this device
         * knows it started — or was steered into. The transcript pairs its prompts with the runs by position, and
         * a chat with runs the transcript has no prompt for (a turn resumed after a usage limit) puts every later
         * prompt under a run too early: Bennett's 2026-09-20 export paired 298 prompts with 324 runs, his own
         * messages drawn under turns from an hour before while their echoes stood under the right runs. The echo
         * knows its run; the transcript's newest copy of the same words is that echo said again, and is left out
         * with the replies under it (the run's own trace shows them). The same words sent twice hide one copy each.
         */
        fun shownMessages(): List<V0ConversationMessageDto> {
            shownMessagesFrom?.let { if (it.first === messages && it.second === local && it.third === runs) return shownMessagesCache }
            val shown = hideEchoedTurns()
            shownMessagesFrom = Triple(messages, local, runs)
            shownMessagesCache = shown
            return shown
        }

        private fun hideEchoedTurns(): List<V0ConversationMessageDto> {
            if (local.none { it.filed } || messages.none { it.type == USER_MESSAGE }) return messages
            val ordered = allRuns()
            val listed = runs.mapTo(HashSet()) { it.id }
            var hidden: Set<Int> = emptySet()
            // Hiding a turn lowers the count of prompts, which lowers how many runs they cover, which can leave one
            // more echo standing: a few passes reach the fixed point (there are only ever a handful of echoes).
            repeat(local.size + 1) {
                val reduced = if (hidden.isEmpty()) messages else messages.filterIndexed { i, _ -> i !in hidden }
                val prompts = reduced.count { it.type == USER_MESSAGE }
                val covered = coveredCount(ordered, prompts, reduced)
                val standing = local.filter { it.filed && it.run.id in listed && (it.steeredAfter != null || ordered.indexOfFirst { r -> r.id == it.run.id } >= covered) }
                val next = HashSet<Int>()
                for (echo in standing) {
                    val wanted = normalizePrompt(echo.message.text)
                    if (wanted.isEmpty()) continue
                    // The newest copy of the words not already taken by another echo, with the replies that follow it.
                    val at = messages.indices.reversed().firstOrNull { i -> i !in next && messages[i].type == USER_MESSAGE && normalizePrompt(messages[i].text) == wanted } ?: continue
                    var j = at
                    do { next += j; j++ } while (j < messages.size && messages[j].type != USER_MESSAGE)
                }
                if (next == hidden) return@repeat
                hidden = next
            }
            if (hidden.isEmpty()) return messages
            return messages.filterIndexed { i, _ -> i !in hidden }
        }

        /** How many of the chat's runs, oldest first, [prompts] of [transcript] cover (see [coveredCount]). */
        private fun coveredCount(ordered: List<RunDto>, prompts: Int, transcript: List<V0ConversationMessageDto>): Int {
            if (runsComplete) return prompts
            val unfetched = chatTotal(ordered, prompts, transcript) - ordered.size
            return (prompts - unfetched).coerceIn(0, ordered.size)
        }

        /** The timeline with [shown] standing in for the runs that have a trace. */
        private fun build(shown: Map<String, List<TimelineItem>>, layout: Layout, steersOf: (RunDto) -> Boolean = { true }): List<TimelineItem> {
            // Prompts the server has not answered for yet read as pending; their placeholder run is the key.
            val pending = local.filterNot { it.filed }.mapTo(HashSet()) { it.run.id }
            val stories = keptStories().keys
            // A run whose log the server let go, with nothing else to read its activity from: its row says so — under
            // every such turn of a coordinator's (its message to the reader was activity), under a turn shown bare otherwise.
            val gone = expiredTurns()
            val coordinator = gone.isNotEmpty() && (projectMode || state.value.isProjectConversation || shown.values.any { CoordinatorTranscript.hasCoordinatorContent(it) })
            val items = TimelineBuilder.fromHistory(layout.messages, layout.paired, shown, promptImages, pending, firstRunAt = layout.runOffset, partial = stories, expired = gone, expiredRowWithReplies = coordinator).toMutableList()
            // A prompt steered into a run the transcript pairs with its own prompt: after the story the run had told by then.
            layout.paired.forEach { run -> if (steersOf(run)) local.filter { it.run.id == run.id && it.steeredAfter != null }.forEach { steered -> spliceSteered(items, run, steered, shown) } }
            layout.standing.forEach { run ->
                // The prompt that started the run, sent from here, ahead of it; the ones steered into it among its rows.
                val prompt = local.firstOrNull { it.run.id == run.id && it.steeredAfter == null }
                val turn = TimelineBuilder.fromHistory(listOfNotNull(prompt?.message, prompt?.reply), listOf(run), shown, promptImages, pending, partial = stories, expired = gone, expiredRowWithReplies = coordinator).toMutableList()
                if (steersOf(run)) local.filter { it.run.id == run.id && it.steeredAfter != null }.forEach { steered -> spliceSteered(turn, run, steered, shown) }
                items += turn
            }
            return items.withUniqueIds()
        }

        /**
         * Puts [steered]'s prompt among [run]'s items in [items] — after the first [LocalPrompt.steeredAfter] of the
         * run's own rows, where the account delivered it — when the run's rows are there to place it among; else
         * after everything the run has, so the prompt is never missing.
         */
        private fun spliceSteered(items: MutableList<TimelineItem>, run: RunDto, steered: LocalPrompt, shown: Map<String, List<TimelineItem>>) {
            val bubble = UserMessage(steered.message.id, steered.message.text, parseIsoMillis(run.createdAt).takeIf { it > 0 }, attachments = promptImages[steered.message.id] ?: emptyList())
            val story = shown[run.id]
            val after = steered.steeredAfter ?: 0
            if (story == null || story.isEmpty()) {
                // The run's rows are the transcript's own text and footer: the prompt follows the last of them.
                val at = items.indexOfLast { it is RunFooter && it.runId == run.id }.takeIf { it >= 0 } ?: items.size
                items.add(at, bubble)
                return
            }
            val anchor = story.getOrNull((after - 1).coerceAtMost(story.lastIndex))
            val at = anchor?.let { a -> items.indexOfFirst { it === a } }?.takeIf { it >= 0 }?.plus(1)
                ?: items.indexOfFirst { it === story.first() }.takeIf { it >= 0 && after <= 0 }
                ?: items.indexOfLast { it === story.last() }.takeIf { it >= 0 }?.plus(1)
                ?: items.size
            items.add(at, bubble)
        }

        /** Everything but the followed run's own items: an empty trace makes the builder contribute nothing for it. */
        private fun buildPrefix(liveRunId: String, layout: Layout): Prefix {
            // The followed run's story is appended by [items], which splices the prompts steered into it among the
            // story's rows itself; the prefix holds the turn's head only, or a steer would be drawn twice.
            val items = build(traces + keptStories() + (liveRunId to emptyList()), layout, steersOf = { it.id != liveRunId })
            return Prefix(this, liveRunId, items).also { builtPrefix = it }
        }

        /**
         * The runs whose log is gone from the server — found expired by a replay, or older than the oldest log the
         * server still had ([expiredBefore]) — with no trace, no kept story, and no record turn standing in for them
         * (the record path draws such a turn's notice of its own, see [turnBodyNotice]).
         */
        private fun expiredTurns(): Set<String> {
            if (recordWindow != null) return emptySet()
            if (expiredRuns.isEmpty() && expiredBefore == Long.MIN_VALUE) return emptySet()
            return runs.asSequence()
                .filter { it.id !in traces && it.id !in partial && !it.statusEnum().isActive && (it.id in expiredRuns || parseIsoMillis(it.createdAt) < expiredBefore) }
                .mapTo(HashSet()) { it.id }
        }

        /** The stories of runs no longer followed that have no complete trace yet (see [partial]). */
        private fun keptStories(): Map<String, List<TimelineItem>> = if (partial.isEmpty()) partial else partial.filterKeys { it !in traces }

        /** The complete traces, the kept stories behind them, plus the story so far of the followed run unless it already has a complete one. */
        private fun shownTraces(): Map<String, List<TimelineItem>> {
            val kept = keptStories()
            val current = live?.takeUnless { it.runId in traces }
            if (kept.isEmpty() && current == null) return traces
            val out = if (kept.isEmpty()) traces else traces + kept
            return if (current == null) out else out + (current.runId to current.items)
        }

        /** Every run known, oldest first: the server's, then the runs of prompts sent from here that its list lacks — placeholders included. */
        fun allRuns(): List<RunDto> {
            if (orderedFromRuns === runs && orderedFromLocal === local) return orderedRuns
            val listed = runs.mapTo(HashSet()) { it.id }
            orderedRuns = (runs + local.map { it.run }.filter { it.id !in listed }).sortedBy { parseIsoMillis(it.createdAt) }
            orderedFromRuns = runs
            orderedFromLocal = local
            return orderedRuns
        }

        /**
         * How many of the chat's runs, oldest first, the server's transcript has a prompt for: its prompts, less the
         * runs older than what the list has fetched (whose prompts come first).
         */
        fun coveredCount(): Int {
            val shown = shownMessages()
            val prompts = shown.count { it.type == USER_MESSAGE }
            if (runsComplete) return prompts
            // The prompts of the runs not fetched yet come first; what is left pairs with the runs in hand, oldest first.
            return coveredCount(allRuns(), prompts, shown)
        }

        /** True when this run's message is shown from a prompt sent from here rather than from the server's transcript. */
        fun standsIn(runId: String): Boolean = local.any { it.run.id == runId } && allRuns().drop(coveredCount()).any { it.id == runId }

        /** The newest run there is, counting prompts sent from here that the server's list has not caught up with (never a placeholder: there is nothing to stream for it yet). */
        fun latestRun(): RunDto? = (runs + local.filter { it.filed }.map { it.run }).maxByOrNull { parseIsoMillis(it.createdAt) }



        /**
         * What the disk keeps: the server's inputs as reported, and the prompts sent from here — those the server has
         * answered with a run; one still awaiting its answer has nothing the disk could pair it with — so the next
         * start can go on standing them in for what the server has still not caught up with.
         */
        fun toCached(): CachedConversation = CachedConversation(
            agentId = agentId,
            messages = messages,
            runs = runs,
            transcriptUnavailable = transcriptUnavailable,
            agentUpdatedAtMillis = inputsUpdatedAt,
            local = local.filter { it.filed }.map { CachedLocalPrompt(it.message, it.run, it.reply, it.steeredAfter) },
            runsComplete = runsComplete,
            olderRunsCursor = olderRunsCursor,
            // The window the reader had open, within reason: the next start renders it from disk before the network answers.
            window = window.coerceAtMost(MAX_RESTORED_WINDOW),
            record = recordWindow?.let { w ->
                val kept = w.turns.takeLast(MAX_RESTORED_WINDOW)
                CachedRecordWindow(
                    total = w.total,
                    firstStep = kept.firstOrNull()?.stepIndex ?: w.firstStep,
                    turnCount = w.state?.turnCount ?: 0,
                    turns = kept.map { CachedRecordTurn(it.stepIndex, it.stepCount, it.prompt, it.projectMode, it.errorMessage) },
                    timings = w.state?.timings?.map { CachedTurnTiming(it.durationMs, it.timestampMs) } ?: emptyList(),
                    turnIndexed = w.turnIndexed,
                )
            },
        )

        /** Runs the placeholder of a prompt sent now must sort after, whatever the device clock says relative to the server's. */
        fun newestRunAt(): Long = (runs + local.map { it.run }).maxOfOrNull { parseIsoMillis(it.createdAt) } ?: 0L

        fun runById(runId: String): RunDto? = runs.firstOrNull { it.id == runId } ?: local.firstOrNull { it.run.id == runId }?.run
    }

    /**
     * True when [page] — the run list's newest page as just read — says nothing the entry did not know before the
     * read ([held], its runs by id then): every run on it is held with the same status, end and result, and no
     * prompt sent from here awaits its run ([noLocal]). Then no turn started or ended since the transcript was last
     * read, and the copy in hand is the transcript (see [load]).
     */
    private fun runsUnchanged(page: ListRunsResponseDto, held: Map<String, RunDto>, noLocal: Boolean): Boolean {
        // A prompt sent from here still standing in for the transcript's copy is a turn the transcript has to be read for.
        if (page.items.isEmpty() || !noLocal) return false
        return page.items.all { fresh ->
            val known = held[fresh.id] ?: return@all false
            // A run under way is written to as it works; its record moving is not a turn starting or ending, and
            // read as one it had the whole transcript fetched again on every load that landed during a turn.
            known.status == fresh.status && known.result == fresh.result && (fresh.statusEnum().isActive || known.updatedAt == fresh.updatedAt)
        }
    }

    private val entries = LinkedHashMap<String, Entry>()
    /** agentId -> the `agentUpdatedAtMillis` of its entry on disk ([ABSENT] when known to be missing). */
    private val diskIndex = ConcurrentHashMap<String, Long>()
    private var prefetchJob: Job? = null
    private var pendingPrefetch: List<Agent>? = null

    init {
        // A completed list fetch is the cue to warm the transcripts most likely to be opened next.
        scope.launch {
            agents.state
                .filter { it.hasLoaded && !it.isRefreshing && !it.isFromCache }
                .map { it.agents }
                .distinctUntilChanged()
                .collect { schedulePrefetch(it) }
        }
        // A run that finishes while no screen is streaming it — the notification monitor holds the stream, or the
        // screen left mid-run — still reaches the transcript and the disk, so the chat is whole when it is opened.
        scope.launch {
            hub.finishes.collect { snapshot -> runCatching { onRunFinished(snapshot) }.onFailure { if (it is CancellationException) throw it } }
        }
    }

    private fun entry(agentId: String): Entry = synchronized(entries) {
        entries[agentId]?.also { it.lastUsedAt = AppClock.now() } ?: run {
            evictIdleEntries()
            Entry(agentId).also { entries[agentId] = it }
        }
    }

    /** Keeps memory bounded: the least recently used transcripts nobody is looking at are dropped (the disk keeps them). */
    private fun evictIdleEntries() {
        if (entries.size < MAX_ENTRIES) return
        entries.values.filter { it.isIdle }.sortedBy { it.lastUsedAt }
            .take(entries.size - MAX_ENTRIES + 1)
            .forEach { victim ->
                entries.values.remove(victim)
                victim.scope.cancel()
            }
    }

    fun state(agentId: String): StateFlow<ConversationState> = entry(agentId).state.asStateFlow()

    private val lastOpened = MutableStateFlow<String?>(null)

    /** The chat a screen most recently attached to, or null before any: the one Settings' debug sheet diagnoses. */
    val lastOpenedAgentId: StateFlow<String?> = lastOpened.asStateFlow()

    /** True while at least one conversation screen shows this agent. */
    fun isAttached(agentId: String): Boolean = synchronized(entries) { (entries[agentId]?.attached ?: 0) > 0 }

    /**
     * Why the chat, or its newest run, reads as failed — for the diagnostics' `status:` line. The newest run's
     * footer among the items shown, when the server's status for that run is a failure: the run, the reason as
     * shown, where the word came from (the stream's own `result`, the run's record, the account's record for the
     * reason), and whether the conversation has moved past it (the chat's status is then not the failure's). Null
     * when the newest run did not fail. Under the entry's monitor.
     */
    private fun Entry.failureLine(latest: RunDto?, window: RecordWindow?): TranscriptLoadDiagnostics.FailureLine? {
        val items = state.value.items
        val footer = items.lastOrNull { it is RunFooter && it.isFailure } as? RunFooter ?: return null
        // The failure is the newest run's, or an older run's the conversation moved past.
        val newest = latest == null || footer.runId == latest.id
        val snapshot = hub.current(agentId, footer.runId)
        val turn = window?.turns?.lastOrNull { turn -> footer.id == "rec-footer-${turn.stepIndex}" }
        val reasonFromRecord = footer.reason != null && footer.reason == turn?.errorMessage
        val source = (if (snapshot?.finished == true && snapshot.streamed) "stream" else "run-record") + (if (reasonFromRecord) "+account-record" else "")
        val current = newest && state.value.runStatus?.isActive != true && items.lastOrNull() === footer
        return TranscriptLoadDiagnostics.FailureLine(ProjectDiagnostics.tail(footer.runId), source, footer.reason, current)
    }

    /** The load's account of [agentId] for the diagnostics export (see [TranscriptLoadDiagnostics]); null for a chat never opened. */
    fun loadDiagnostics(agentId: String): TranscriptLoadDiagnostics? {
        val e = synchronized(entries) { entries[agentId] } ?: return null
        return synchronized(e) {
            val layout = e.layout()
            val live = e.live
            val streaming = e.streamJob?.isActive == true
            val liveRun = e.latestRun()?.takeIf { it.statusEnum().isActive }?.id ?: live?.runId
            val snapshot = liveRun?.let { hub.current(agentId, it) }
            // `…(story)`: the run shows what its stream told before the follow ended, its whole trace still to come (see Entry.partial).
            fun traceOf(run: RunDto): String = when {
                run.statusEnum().isActive -> "live"
                run.id in e.traces -> if (run.id in e.staleTraces) "shown(stale)" else "shown"
                run.id in e.traceQueue || run.id in e.traceInFlight -> "pending"
                run.id in e.expiredRuns || parseIsoMillis(run.createdAt) < e.expiredBefore -> "expired"
                run.id in e.failedTraces -> "failed"
                else -> "none"
            } + (if (run.id in e.partial && run.id !in e.traces) "(story)" else "")
            val window = e.recordWindow
            val coordinator = e.projectMode || CoordinatorTranscript.hasCoordinatorContent(e.state.value.items)
            // The message calls the rows leave out as copies of an earlier message (see CoordinatorTranscript.repeatedMessages).
            val presenterRepeats = if (coordinator) CoordinatorTranscript.leftOut(e.state.value.items) else emptySet()
            /** The ids of the message calls of [items] keyed in [keys], as the shape dump names ids. */
            fun repeatIds(items: List<TimelineItem>, keys: Set<String>): List<String> = CoordinatorTranscript.messageCallIds(items, keys).map { ProjectDiagnostics.tail(it) }
            /** `rendered=yes|recovered|missing|none via=… [repeat=…]`: what is drawn of [shown], from where, and which calls were copies. */
            fun renderedWords(shown: List<TimelineItem>, via: String, copies: List<String>): String {
                val drawn = CoordinatorTranscript.withoutRepeats(shown, presenterRepeats)
                val stage = CoordinatorTranscript.messageStage(drawn).substringBefore(' ').let { if (it == "body") "yes" else it }
                return "rendered=$stage via=$via" + (if (copies.isEmpty()) "" else " repeat=${copies.distinct().joinToString(",")}")
            }
            val runLines = if (window != null) {
                // From the record: one line per loaded turn, with the run the turn pairs with when the list has it
                // (paired as [recordItems] pairs them), and in a coordinator's chat the message's stages: what the
                // record has of the coordinator's word, what reached the screen from which source, and which message
                // call a stand-in carried that was an earlier turn's message again.
                val trailing = with(e) { local.filter { window.turnOf(it) == null } }.mapTo(HashSet()) { it.run.id }
                val paired = e.allRuns().filter { it.id !in trailing }
                val offset = paired.size - window.turns.size
                window.turns.mapIndexed { i, turn ->
                    val run = paired.getOrNull(offset + i)
                    val complete = run?.let { e.traces[it.id] }
                    val liveItems = e.live?.takeIf { run != null && it.runId == run.id && complete == null }?.items
                    val kept = if (complete == null && liveItems == null && run != null) e.partial[run.id] else null
                    val trace = when {
                        run != null && run.statusEnum().isActive -> "live"
                        complete != null -> "shown(log)"
                        kept != null -> "shown(story)"
                        turn.items.isEmpty() -> "pending"
                        run == null -> "shown(unpaired)"
                        else -> "shown"
                    }
                    val message = if (!coordinator) null else {
                        val shown = e.renderedItems(turn.stepIndex) ?: complete ?: liveItems ?: kept ?: turn.items
                        val via = when {
                            complete != null -> "log"
                            liveItems != null -> "live"
                            kept != null -> "story"
                            turn.textFromTranscript -> "record+transcript"
                            else -> "record"
                        }
                        // The copies the rendering left out of the stand-in, and the ones the rows leave out of what was rendered.
                        val copies = e.renderedRepeats(turn.stepIndex).let { (standIn, keys) -> repeatIds(standIn, keys) } + repeatIds(shown, presenterRepeats)
                        "record=${CoordinatorTranscript.messageStage(turn.items)} " + renderedWords(shown, via, copies)
                    }
                    TranscriptLoadDiagnostics.RunLine("turn@${turn.stepIndex}" + (run?.let { "/" + ProjectDiagnostics.tail(it.id) } ?: ""), run?.status ?: "-", trace, turn.items.size, message)
                }
            } else {
                (layout.paired + layout.standing).map { run ->
                    // What stands in for the run on screen: its whole trace, the story being streamed, or the story kept from a follow that ended.
                    val shown = e.traces[run.id] ?: e.live?.takeIf { it.runId == run.id }?.items ?: e.partial[run.id]
                    val via = when {
                        e.traces[run.id] != null -> "log"
                        e.live?.runId == run.id -> "live"
                        else -> "story"
                    }
                    val message = if (!coordinator || shown == null) null else renderedWords(shown, via, repeatIds(shown, presenterRepeats))
                    TranscriptLoadDiagnostics.RunLine(ProjectDiagnostics.tail(run.id), run.status, traceOf(run), shown?.size ?: 0, message)
                }
            }
            TranscriptLoadDiagnostics(
                attached = e.attached,
                paused = e.paused,
                fetched = e.fetched,
                fetchedAtIso = e.fetchedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                messages = e.messages.size,
                prompts = e.messages.count { it.type == USER_MESSAGE },
                runsLoaded = e.runs.size,
                runsComplete = e.runsComplete,
                hasOlderCursor = e.olderRunsCursor != null,
                runOrder = e.runOrder,
                latestFetchedById = e.latestFetchedById,
                window = e.window,
                windowStart = if (window != null) window.turnCount - window.turns.size else layout.olderCount,
                chatTurns = window?.turnCount ?: layout.total,
                runs = runLines,
                source = if (window != null) "record" else "runs",
                record = if (window != null || e.recordEmpty || e.recordError != null) {
                    val fallback = e.state.value.recordFallback
                    TranscriptLoadDiagnostics.RecordLine(
                        window?.total ?: 0, window?.firstStep ?: 0, window?.turns?.size ?: 0, window?.state?.turnCount, window?.state != null, e.recordEmpty, e.recordError,
                        read = if (window?.turnIndexed ?: (record?.readsTurns == true)) "turns" else "steps",
                        fallback = fallback?.let { f ->
                            TranscriptLoadDiagnostics.FallbackLine(
                                sinceIso = Instant.ofEpochMilli(f.sinceMillis).toString(),
                                readMs = f.readMillis,
                                retryAfterMs = f.retryAfterMillis,
                                refusedUntilIso = e.recordRefusedUntil.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                            )
                        },
                    )
                } else null,
                // The newest turns' steps and calls as the record gave them this session, keys and value types only.
                shapes = window?.turns?.mapNotNull { it.shape }.orEmpty(),
                status = run {
                    val latest = e.latestRun()
                    val row = agents.agent(agentId)
                    TranscriptLoadDiagnostics.StatusLine(
                        shown = e.state.value.runStatus?.name ?: "-",
                        latestRun = latest?.let { e.statusOf(it).name } ?: "-",
                        streaming = streaming,
                        rowRunning = row?.isRunning == true,
                        // The account's word on this chat, the same one the send gate reads (RunningScan.accountWord): running or not, and when it said so.
                        accountRunning = agents.runningScan.value.accountWord[agentId]?.running == true,
                        rowNewerThanRecordMs = if (row != null && latest != null) row.updatedAtMillis - parseIsoMillis(latest.updatedAt) else null,
                        failure = e.failureLine(latest, window),
                        accountAtIso = agents.runningScan.value.accountWord[agentId]?.atMillis?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    )
                },
                traceQueue = e.traceQueue.size,
                traceInFlight = e.traceInFlight.size,
                traceWorkerRunning = e.traceJob?.isActive == true,
                expiredBeforeIso = e.expiredBefore.takeIf { it > Long.MIN_VALUE }?.let { Instant.ofEpochMilli(it).toString() },
                expiredRuns = e.expiredRuns.size,
                failedTraces = e.failedTraces.size,
                liveRunId = liveRun,
                following = streaming,
                liveStream = snapshot?.let { TranscriptLoadDiagnostics.LiveStreamLine(it.eventCount, it.status.name, it.reconnecting, it.expired, it.finished, it.items.size) },
                lastError = e.state.value.error,
                transcriptError = e.transcriptError,
                transcriptUnavailable = e.transcriptUnavailable,
            )
        }
    }

    /**
     * Attach a screen. Loads history on first attach and keeps streaming while at least one screen is attached. A
     * chat whose launch is still in flight has nothing on the server to load: its prompt is already on screen, and the
     * launch starts the stream when the server answers.
     */
    fun attach(agentId: String) {
        val e = entry(agentId)
        lastOpened.value = agentId
        val firstScreen = synchronized(e) {
            if (e.attached == 0) TranscriptPerf.opened(agentId)
            e.attached++
            // A screen attaching can see the chat, whatever the last one that left had done.
            e.paused = false
            e.trimJob?.cancel()
            e.trimJob = null
            if (e.attached == 1 && !e.launching) {
                e.loadJob?.cancel()
                // A chat read from the network moments ago is shown as it stands — the transcript in memory, whole —
                // and only its live run is picked up again: nothing is fetched for a reader who stepped out and
                // straight back in. Any longer away, and the chat is read again for what changed since (see [load]).
                val fresh = e.fetched && e.hasInputs && AppClock.now() - e.fetchedAt < REOPEN_FRESH_MS
                e.loadJob = e.scope.launch {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.listedAtMillis) }
                    if (fresh) reopen(e, agentId) else load(e, agentId)
                }
            }
            e.attached == 1
        }
        if (firstScreen) onOpened(agentId)
    }

    /**
     * A screen back on a chat read moments ago: the transcript stands as it is, and the chat's live turn is followed
     * again — its stream for a run under way, the account's record while the account calls the chat running with no
     * run to stream — with the traces the window still lacks asked for from the disk first, as ever. No read of the
     * record, the run list or the transcript: the last one is fresh (see [REOPEN_FRESH_MS]).
     */
    private suspend fun reopen(e: Entry, agentId: String) {
        val latest = synchronized(e) { e.latestRun() }
        val active = latest?.takeIf { e.statusOf(it).isActive }
        if (active != null) {
            if (!e.isFollowing(active.id)) startStreaming(e, agentId, active, unlessMovedOn = true)
        } else if (accountSaysRunning(e)) {
            keepFollowing(e, endedRunId = null)
        }
        loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
    }

    /**
     * Detach a screen. When the last one leaves, everything in flight for it stops: the load (whose tail would
     * otherwise start following a run nobody is looking at), the replays and the live stream. The next attach loads
     * afresh, and the hub still holds the story of a run that is followed again within its grace period.
     */
    fun detach(agentId: String) {
        val e = entry(agentId)
        synchronized(e) {
            e.attached = (e.attached - 1).coerceAtLeast(0)
            if (e.attached == 0) {
                e.stops++
                e.paused = false
                e.traceQueue.clear()
                e.traceInFlight.clear()
                e.loadJob?.cancel()
                e.loadJob = null
                e.traceJob?.cancel()
                e.traceJob = null
                e.runPagingJob?.cancel()
                e.runPagingJob = null
                e.keepFollowingJob?.cancel()
                e.keepFollowingJob = null
                e.prefetchJob?.cancel()
                e.prefetchJob = null
                e.prefetchedOlder = null
                e.loadingOlder = false
                e.stopFollowing()
                // The window the reader had open stays a while: a reader who comes straight back finds the chat as
                // they left it, not cut to the newest turns and paging them back in (see [trimWindow]).
                e.trimJob?.cancel()
                e.trimJob = e.scope.launch {
                    delay(TRIM_AFTER_DETACH_MS)
                    synchronized(e) { if (e.attached == 0) e.trimWindow() }
                }
            }
        }
    }

    /**
     * A while after the last screen left the chat, the window falls back to what the next open starts on and the
     * traces of the turns past it leave memory (the disk has them): however far a reader scrolled, a chat left behind
     * holds no more than [MAX_RESTORED_WINDOW] runs' worth of items and traces. Not on the way out itself: a reopen
     * within [TRIM_AFTER_DETACH_MS] shows the transcript as it was, whole, from memory.
     */
    private fun Entry.trimWindow() {
        if (window <= MAX_RESTORED_WINDOW) return
        publish(
            mutate = {
                window = MAX_RESTORED_WINDOW
                recordWindow?.let { w ->
                    if (w.turns.size > MAX_RESTORED_WINDOW) {
                        val kept = w.turns.takeLast(MAX_RESTORED_WINDOW)
                        recordWindow = RecordWindow(w.total, kept.first().stepIndex, kept, emptyList(), w.state, w.readAtMillis, w.newestTurn, w.turnIndexed)
                    }
                }
                val kept = layout().let { it.paired + it.standing }.mapTo(HashSet()) { it.id }
                traces = traces.filterKeys { it in kept }
                if (partial.isNotEmpty()) partial = partial.filterKeys { it in kept }
            },
        )
    }

    /**
     * Drops everything known about an agent, on disk too; used when it is deleted. A screen still showing it keeps
     * its entry, emptied in place: it captured that state flow once, so dropping the entry would leave it collecting
     * a flow nothing can ever publish to again — and a reload would silently build a second one.
     */
    fun forget(agentId: String) {
        val attached = synchronized(entries) {
            val e = entries[agentId] ?: return@synchronized null
            if (e.attached > 0) return@synchronized e
            entries.remove(agentId)
            e.scope.cancel()
            null
        }
        attached?.emptyInPlace()
        diskIndex[agentId] = ABSENT
        scope.launch {
            cache?.remove(agentId)
            traceCache?.remove(agentId)
        }
    }

    private fun Entry.emptyInPlace() {
        streamJob?.cancel()
        traceJob?.cancel()
        loadJob?.cancel()
        runPagingJob?.cancel()
        keepFollowingJob?.cancel()
        keepFollowingJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedOlder = null
        trimJob?.cancel()
        trimJob = null
        publish(
            mutate = {
                messages = emptyList()
                runs = emptyList()
                runsComplete = true
                olderRunsCursor = null
                window = WINDOW_RUNS
                loadingOlder = false
                local = emptyList()
                traces = emptyMap()
                partial = emptyMap()
                awaiting = emptyList()
                delivered = emptyList()
                returned = emptyMap()
                promptImages = emptyMap()
                live = null
                streamJob = null
                traceJob = null
                loadJob = null
                runPagingJob = null
                paused = false
                traceQueue.clear()
                traceInFlight.clear()
                staleTraces.clear()
                failedTraces.clear()
                runOrder = null
                latestFetchedById = false
                transcriptError = null
                projectMode = false
                recordWindow = null
                recordEmpty = false
                recordError = null
                recordRefusedUntil = 0L
                expiredRuns.clear()
                expiredBefore = Long.MIN_VALUE
                recordedFinishes.clear()
                transcriptUnavailable = false
                fetched = false
                fetchedAt = 0L
                inputsUpdatedAt = 0L
            },
            transform = { ConversationState(agentId, isLoading = false) },
        )
    }

    /**
     * The screen stopped without its ViewModel going with it — the app was backgrounded, or a destination that keeps
     * it alive came up. Nothing can see the chat, so the stream and the replays stop; the reference count is
     * untouched, so this is not a [detach] and the entry stays warm for [resume].
     *
     * The run itself carries on: the hub keeps the shared stream alive for the live-notification monitor and for its
     * release grace, so a quick app switch tears nothing down, and a longer absence is caught up by [resume].
     */
    fun pause(agentId: String) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        synchronized(e) {
            if (e.attached == 0) return
            e.stops++
            e.paused = true
            e.traceJob?.cancel()
            e.traceJob = null
            e.keepFollowingJob?.cancel()
            e.keepFollowingJob = null
            // The replays under way wait, with the ones not started yet, for the screen to come back.
            e.requeueInFlight()
            e.stopFollowing()
        }
    }

    /**
     * The screen is back. A run still going is followed again straight away rather than waiting on [revalidate],
     * which skips the fetch when the history was read moments ago — after a short absence that would otherwise
     * leave the chat paused with a run streaming behind it.
     *
     * The caller is the composition, so nothing of this is done on its thread: the entry's monitor is held by the
     * background load and the live stream while they rebuild the timeline, and waiting for it there is a stall on
     * the main thread. What the screen coming back means for the entry is settled on [Entry.scope] instead.
     */
    fun resume(agentId: String) = offload(agentId) { e -> resumeNow(e) }

    private fun resumeNow(e: Entry) {
        val run = synchronized(e) {
            if (e.attached == 0) return
            e.paused = false
            if (e.streamJob != null || e.launching) null else e.latestRun()?.takeIf { it.statusEnum().isActive }
        }
        if (run != null) startStreaming(e, e.agentId, run)
        // The replays the pause turned away, and the ones it cut short, pick up where they were.
        loadTraces(e, e.agentId, emptyList())
        revalidateNow(e)
    }

    /**
     * Runs [work] for the agent's entry on that entry's own scope. The count of stops taken here is checked again
     * before [work] runs: the screen can have stopped, or the last one left, while this was waiting to be dispatched,
     * and the entry must not be brought back up behind it.
     */
    private fun offload(agentId: String, work: suspend (Entry) -> Unit) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        val seen = e.stops
        e.scope.launch { if (e.stops == seen) work(e) }
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.stopFollowing()
        e.loadJob?.cancel()
        // The reader asked: everything is read again, the transcript included, whatever the run list says of it.
        e.loadJob = e.scope.launch { load(e, agentId, force = true) }
    }

    /**
     * Throws away everything kept for the chat — in memory and on disk: its transcript, its traces, the record's
     * window — and reads it again from the server, for a chat that shows less than it should because a copy an
     * earlier build wrote is standing in the way. The disk goes first, so what the new read writes is not swept away under it.
     */
    fun reloadTranscript(agentId: String) {
        val e = entry(agentId)
        e.emptyInPlace()
        diskIndex[agentId] = ABSENT
        e.loadJob = e.scope.launch {
            cache?.remove(agentId)
            traceCache?.remove(agentId)
            load(e, agentId, force = true)
        }
    }

    /**
     * Brings an open chat back up to date after the app returns to the foreground: while it was away the network
     * may have taken the stream down mid-run, or the run may have finished. Loads the history again, which restarts
     * the stream of a run still going and replays one that ended. A load already in flight, or one that completed
     * moments ago (the first open), is left alone — unless [force]: the caller knows the chat's copy is behind the
     * server (a Stop the server refused because the turn was over already), and a load in flight read its page
     * before that, so the chat is read again once it lands. Called from the composition, so it is settled off that
     * thread for the same reason [resume] is.
     */
    fun revalidate(agentId: String, force: Boolean = false) = offload(agentId) { e -> revalidateNow(e, force) }

    private fun revalidateNow(e: Entry, force: Boolean = false) {
        synchronized(e) {
            // A chat still being launched has nothing on the server to fetch; the launch settles it when the server answers.
            if (e.attached == 0 || e.launching) return
            val inFlight = e.loadJob?.takeIf { it.isActive }
            if (inFlight != null) {
                // The load under way read its run page before whatever prompted this; what it publishes is that
                // page's word. A forced revalidation is owed a read that postdates the cause: once, when the load lands.
                if (force && !e.rereadAfterLoad) {
                    e.rereadAfterLoad = true
                    inFlight.invokeOnCompletion {
                        synchronized(e) { e.rereadAfterLoad = false }
                        revalidateNow(e, force = true)
                    }
                }
                return
            }
            if (!force && AppClock.now() - e.fetchedAt < REVALIDATE_MIN_INTERVAL_MS) return
            e.loadJob = e.scope.launch { load(e, e.agentId) }
        }
    }

    /**
     * Rebuilds the visible items from the entry's inputs after [mutate] has changed them. What the rebuilt transcript
     * says about the chat's workers — the one word default mode has on a Project's lineage — goes to the agent list
     * once it has changed (see [Entry.coordinatorLineage]), outside the entry's monitor.
     */
    private inline fun Entry.publish(mutate: Entry.() -> Unit = {}, transform: ConversationState.() -> ConversationState = { this }) {
        val workers = synchronized(this) {
            mutate()
            lastPublishAtMs = monotonicMillis()
            val buildStartedAt = System.nanoTime()
            val items = items()
            val window = recordWindow
            val (older, status) = if (window != null) {
                (window.hasOlder) to recordTraceStatus(window)
            } else {
                layout().let { (it.olderCount > 0) to traceStatus(it) }
            }
            // The queued messages' placement goes out with the items it was decided with: one frame, one place each.
            state.update { it.copy(items = items, hasOlder = older, traceStatus = status, queuePlacement = queuePlacement(items)).transform() }
            // The newest page counts as whole once nothing shown is still being read or replayed.
            TranscriptPerf.session(agentId).publication(items.size, whole = status.pending == 0, buildNanos = System.nanoTime() - buildStartedAt)
            coordinatorLineage(items)
        }
        if (workers != null) {
            // Only what the coordinator's tools returned as workers it created is placed — the `managerAgentId` the
            // account's record carries for a created worker, which default mode cannot read. A chat the coordinator
            // merely messaged or read is placed by nothing (the desktop reads no transcript at all).
            val (created, _) = workers
            if (created.isNotEmpty()) agents.applyLineage(agentId, created.associateWith { AgentParentKind.PROJECT_WORKER }, LineageSignal.COORDINATOR_CREATED)
        }
    }

    /**
     * [mutate]s the entry and publishes — at once when the last publication is [PUBLISH_COALESCE_MS] or more ago,
     * else once, a little later, for the whole burst: a stream's deltas and a pass of replays landing one after
     * another otherwise rebuild the transcript and recompose the screen for every one of them, which reads as
     * churn on a long chat. What was mutated is in the entry either way; the trailing publication shows all of it.
     */
    private fun Entry.publishCoalesced(mutate: Entry.() -> Unit) {
        val now = synchronized(this) {
            mutate()
            val now = monotonicMillis()
            if (now - lastPublishAtMs >= PUBLISH_COALESCE_MS) return@synchronized true
            if (pendingPublish?.isActive != true) {
                pendingPublish = scope.launch {
                    delay(PUBLISH_COALESCE_MS)
                    publish()
                }
            }
            false
        }
        if (now) publish()
    }

    /**
     * Where the turns of the record's window stand (see [TraceStatus]): a turn with its body from the record, or
     * with its run's trace, is shown; one without a body whose run is queued for a replay (or not paired yet) is
     * pending; one whose log is gone is expired; one whose replay failed is failed. The live turn counts as shown.
     */
    private fun Entry.recordTraceStatus(window: RecordWindow): TraceStatus {
        val ordered = allRuns()
        val trailing = local.filter { window.turnOf(it) == null }.mapTo(HashSet()) { it.run.id }
        val paired = ordered.filter { it.id !in trailing }
        val offset = paired.size - window.turns.size
        var shown = 0
        var pending = 0
        var expired = 0
        var failed = 0
        window.turns.forEachIndexed { i, turn ->
            val run = paired.getOrNull(offset + i)
            when {
                // A turn whose reply the transcript gave and whose steps the record never had has its words, not its
                // activity: counted by its log's state below.
                (turn.hasBody && !turn.activityMissing) || (run != null && run.id in traces) || (run != null && live?.runId == run.id) -> shown++
                run == null -> if (i == window.turns.lastIndex && isChatRunning()) shown++ else pending++
                run.statusEnum().isActive -> shown++
                run.id in traceQueue || run.id in traceInFlight -> pending++
                run.id in expiredRuns || parseIsoMillis(run.createdAt) < expiredBefore -> expired++
                run.id in failedTraces -> failed++
                else -> if (fetched || state.value.isLoading) pending++ else failed++
            }
        }
        return TraceStatus(shown, pending, expired, failed)
    }

    /**
     * Where the traces of the window's finished runs stand (see [TraceStatus]). Under the entry's monitor. A run the
     * disk or the log answered for is shown; one on the queue or in flight is pending; one whose log is gone (and
     * that no record filled) is expired; one asked for and not answered is failed, until the next fetch asks again.
     */
    private fun Entry.traceStatus(layout: Layout): TraceStatus {
        var shown = 0
        var pending = 0
        var expired = 0
        var failed = 0
        for (run in layout.paired + layout.standing) {
            if (!run.statusEnum().isTerminal) continue
            when {
                run.id in traces -> shown++
                run.id in traceQueue || run.id in traceInFlight -> pending++
                run.id in expiredRuns || parseIsoMillis(run.createdAt) < expiredBefore -> expired++
                run.id in failedTraces -> failed++
                // Not asked yet: the load is still on its way to asking — unless it failed before it could, in which
                // case the run's trace is as unread as one whose replay failed, and Retry asks for it.
                else -> if (fetched || state.value.isLoading) pending++ else failed++
            }
        }
        return TraceStatus(shown, pending, expired, failed)
    }

    /**
     * The workers the chat's transcript names through the coordinator's tools (see [CoordinatorLineage]) — the ones
     * its tools returned as its own, and every one they addressed — when that has changed since it was last reported,
     * else null. A transcript with the tools but no ids yet still reports (empty sets): the chat is a coordinator,
     * and that alone places it.
     */
    private fun Entry.coordinatorLineage(items: List<TimelineItem>): Pair<Set<String>, Set<String>>? {
        if (!CoordinatorLineage.isCoordinator(items)) return null
        val workers = CoordinatorLineage.workerIds(items)
        if (workers == reportedWorkers) return null
        reportedWorkers = workers
        return CoordinatorLineage.createdWorkerIds(items) to workers
    }

    /** True while a stream is open on [runId] for this entry. */
    private fun Entry.isFollowing(runId: String): Boolean = synchronized(this) { streamJob?.isActive == true && state.value.activeRunId == runId }

    /**
     * Stops following the active run. Its story so far stays, standing in for the run until its whole trace lands
     * (see [Entry.partial], [Entry.keepStory]) — a follow ending does not blank what the screen showed of the turn.
     * With [over] — the run the follow is ended for, over by the server's account — the chat's status is settled from
     * that record in the same frame: the load that found the run over had kept the stream's word for the status ("a
     * stream open on the latest run keeps its word"), and stopping the stream alone left `isStreaming` false over a
     * `RUNNING` status that nothing corrected until the next load — the Stop button and "Working…" over the reply and
     * the footer, for a turn that ended while the connection was down. Without [over] the status is not this call's
     * to say: a screen leaving, or a reload about to read it afresh.
     */
    private fun Entry.stopFollowing(over: RunDto? = null) {
        streamJob?.cancel()
        publish(
            mutate = { streamJob = null; keepStory(); live = null },
            transform = { copy(isStreaming = false, isReconnecting = false, runStatus = if (over != null) this@stopFollowing.chatStatus(over, streaming = false) else runStatus) },
        )
    }

    /**
     * Inside a merge's `mutate`, under the entry's monitor: the run being followed is over by the run record just
     * merged. Its stream, whatever it still says, no longer stands for the turn: the follow ends here, its story
     * kept (see [keepStory]), so the very frame that first shows the run's footer from the record is the frame that
     * turns the stream off — the same frame, never two (#237/#241's rule read the other way round: a run is never
     * shown ended with its stream on; `ConversationRepositoryTest` caught the two-frame version once on main at
     * `adcd808`). Returns the record the follow ended over, for the transform's word; null when nothing ended.
     */
    private fun Entry.endFollowIfOver(): RunDto? {
        val followed = live?.runId ?: return null
        val record = runs.firstOrNull { it.id == followed } ?: return null
        if (record.statusEnum().isActive) return null
        streamJob?.cancel()
        streamJob = null
        keepStory()
        live = null
        return record
    }

    /**
     * Before a merge whose page shows the followed run over: the agent's row learns the run's end first, so the frame
     * the merge publishes — the footer under the run, the stream off (see [Entry.endFollowIfOver]) — is never drawn
     * beside a row still running (#237/#241: the row is patched before the frame that ends the stream). A run that
     * is no longer the row's latest changes nothing on the row (see `Agent.withLatestRun`).
     */
    private fun patchRowIfFollowedOver(e: Entry, agentId: String, page: List<RunDto>) {
        val record = synchronized(e) {
            val followed = e.live?.runId ?: return
            val over = page.firstOrNull { it.id == followed && !it.statusEnum().isActive } ?: return
            e.known(over)
        }
        agents.recordRun(agentId, record)
    }

    /**
     * The story the followed run's stream has told so far is kept for the run (see [partial]) when the run has no
     * complete trace: what the screen showed of the turn does not go blank because the follow ends — the screen
     * paused, the next run followed, the run over by its record with the stream broken. Under the entry's monitor.
     */
    private fun Entry.keepStory() {
        val current = live ?: return
        if (current.items.isEmpty() || current.runId in traces) return
        val held = partial[current.runId]
        if (held != null && storySize(held) >= storySize(current.items)) return
        partial = partial + (current.runId to current.items)
    }

    /**
     * Reads the chat: the account's record (Extended mode), else the documented transcript and runs. [force] reads
     * the documented transcript again even when the run list says nothing changed — the reader's own Reload.
     */
    private suspend fun load(e: Entry, agentId: String, force: Boolean = false) {
        val backend = session.current
        val tokens = cacheTokens()
        val api = backend.api
        if (!e.hasInputs) restoreFromCache(e, agentId)
        e.state.update { it.copy(isLoading = true, error = null) }
        // Extended mode: the account's own record is the transcript (see [loadFromRecord]); the documented path
        // below stands in only for a chat the record has nothing for, or when the record cannot be read before
        // anything is on screen.
        val recordEnabled = record != null && !backend.isDemo && capabilities().accountTranscript
        if (!recordEnabled && synchronized(e) { e.recordWindow != null }) {
            // The mode was switched off since the record's window was shown (or restored): the documented path renders.
            e.publish(mutate = { recordWindow = null; recordError = null })
        }
        val recordApi = record?.takeIf { recordEnabled && !synchronized(e) { e.recordEmpty } }
        // The run list's first page the record path read beside the record, handed on when the record was not served:
        // the documented path needs the same page, and the fallback should not cost the round trip twice.
        var handedRuns: Result<ListRunsResponseDto>? = null
        if (recordApi != null) {
            val served = try {
                loadFromRecord(e, agentId, recordApi, backend, tokens).also { handedRuns = it.runPage }.served
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                synchronized(e) { e.recordError = t.userMessage() }
                synchronized(e) { e.recordWindow != null }.also { standing -> if (standing) e.publish(transform = { copy(isLoading = false, transcriptError = t.userMessage()) }) }
            }
            if (served) return
        }
        try {
            coroutineScope {
                // The newest runs, one page: enough to render the window; the rest of the records follow behind the
                // cursor (see [pageOlderRuns]) rather than holding the first frame up.
                val runPage = async { handedRuns ?: run { net(agentId, "runs"); runCatching { api.listRuns(agentId, limit = FIRST_RUN_PAGE) } } }
                // The `/v0` transcript is the whole chat in one answer, megabytes for a long one. A chat read before
                // is asked for it again only when the newest runs say something changed — a run started or ended
                // since — every turn of the transcript being a run: with nothing new in the run list there is
                // nothing new in the transcript, and the copy in hand stands (see [runsUnchanged]).
                // Compared with the runs as held before this load: the run page landing first is merged into the
                // entry meanwhile (see [publishRunsFirst]), and compared with itself it would always read unchanged.
                val held = synchronized(e) { if (!force && e.fetched && e.messages.isNotEmpty() && e.recordWindow == null) e.runs.associateBy { it.id } to e.local.isEmpty() else null }
                val conversation = async {
                    if (held != null) {
                        val page = runPage.await().getOrNull()
                        if (page != null && runsUnchanged(page, held.first, noLocal = held.second)) return@async Result.success(V0ConversationResponseDto(agentId, synchronized(e) { e.messages }))
                    }
                    net(agentId, "transcript")
                    runCatching { api.conversationV0(agentId) }
                }
                val stored = async { runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap()) }
                // The runs and their traces render first: the `/v0` transcript is the whole chat in one answer,
                // megabytes for a long one, and a slow connection takes its time over it. Whichever lands first is
                // shown; when the runs come first the turns show with their traces and replies, and the prompts'
                // text joins when the transcript arrives (see [publishRunsFirst]).
                if (!conversation.isCompleted) {
                    select<Unit> {
                        conversation.onJoin { }
                        runPage.onJoin { }
                    }
                    if (!conversation.isCompleted && runPage.isCompleted) publishRunsFirst(e, agentId, api, runPage.await(), stored.await())
                }
                val convResult = conversation.await()
                val runResult = runPage.await()
                val onDevice = stored.await()
                // Each endpoint answers for itself: what one of them could not read is left as it was rather than
                // reported as absent, so a timeout on the run list does not strip the footers and traces off an
                // otherwise healthy transcript — nor write that shape to disk.
                val transcript = convResult.getOrNull()?.messages
                val firstPage = runResult.getOrNull()
                val transcriptFailed = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                // The run the agent's row names as its latest: the one anchor that says which runs are the chat's
                // newest, whatever order the list came in (see [newestRuns]).
                val latestId = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
                val (known, knownComplete) = synchronized(e) { e.runs to e.runsComplete }
                val newest = firstPage?.let { newestRuns(api, agentId, it, latestId, known, knownComplete) }
                val page = newest?.page
                val fetched = convResult.isSuccess || page?.items?.isNotEmpty() == true
                // The newest run once the inputs are merged: usually the server's, but a follow-up sent from here
                // that the list has not caught up with yet is newer, and a reload must keep following it rather
                // than declare the previous run the active one.
                var latest: RunDto? = null
                var merged: List<RunDto> = emptyList()
                var unavailable = false
                var pageOlder = false
                // What the load could not read, in the server's words: the transcript, or — the transcript answering
                // and the run list not — the runs, without which the turns show no status, footer or trace. Either is
                // said under the transcript with the way to ask again, never swallowed; the next fetch that reads it clears it.
                val runsFailed = runResult.isFailure && runResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val transcriptIssue = when {
                    transcriptFailed -> convResult.exceptionOrNull()?.userMessage() ?: "The transcript could not be read."
                    runsFailed -> runResult.exceptionOrNull()?.userMessage() ?: "The run list could not be read."
                    else -> null
                }
                if (fetched) {
                    // The row first, when the page shows the followed run over (see [patchRowIfFollowedOver]).
                    page?.let { patchRowIfFollowedOver(e, agentId, it.items) }
                    // The traces are kept: they are complete, and a finished run's log does not change. Local prompts
                    // hand over to the server once it reports them in full.
                    e.publish(
                        mutate = {
                            transcript?.let { messages = it }
                            newest?.let { mergeNewestPage(it.page, endKnown = it.endKnown); runOrder = if (it.ascending) RunOrder.OLDEST_FIRST else RunOrder.NEWEST_FIRST; latestFetchedById = it.latestFetched }
                            unavailable = transcriptFailed && messages.isEmpty()
                            this.transcriptUnavailable = unavailable
                            transcriptError = transcriptIssue
                            inputsUpdatedAt = agents.agent(agentId)?.updatedAtMillis ?: 0L
                            pruneLocal()
                            // The disk knows every filed prompt; only prompts still in flight exist solely in memory.
                            promptImages = onDevice + promptImages.filterKeys { key -> local.any { it.run.id == key } }
                            this.fetched = true
                            fetchedAt = AppClock.now()
                            latest = latestRun()
                            merged = runs
                            pageOlder = !runsComplete && olderRunsCursor != null
                            // The run followed is over by the list just merged: the follow ends in this frame, with the footer.
                            endFollowIfOver()
                        },
                        transform = {
                            // A stream already open on the latest run (the runs rendered ahead of the transcript) keeps its word.
                            val following = e.streamJob?.isActive == true && activeRunId == latest?.id
                            copy(
                                isLoading = false,
                                error = null,
                                activeRunId = latest?.id,
                                runStatus = if (following) runStatus else e.chatStatus(latest),
                                isStreaming = following && isStreaming,
                                isReconnecting = following && isReconnecting,
                                transcriptUnavailable = unavailable,
                                transcriptError = transcriptIssue,
                            )
                        },
                    )
                } else {
                    e.reportLoadFailure(convResult.exceptionOrNull() ?: runResult.exceptionOrNull())
                }
                // The latest run is the row's execution state (a turn that ended in an error is only visible here),
                // so the sidebar reflects it right away. The full agent record then enriches the row (repo, PR,
                // duration); it never holds up the transcript, and reuses this run, saving a round-trip. As this
                // device knows the run (see [Entry.known], and the list's own memory in `AgentRepository.recordRun`):
                // a record read before a Stop, or before the run's end on its stream, does not put the row back to running.
                val knownLatest = latest?.let { e.known(it) }
                knownLatest?.let { run -> agents.recordRun(agentId, run) }
                launch { agents.loadDetail(agentId, knownLatest) }
                if (fetched) {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.listedAtMillis) }
                    persist(e, backend, tokens)
                    val active = latest?.takeIf { it.statusEnum().isActive }
                    if (active != null) {
                        // Already followed when the runs rendered ahead of the transcript (see [publishRunsFirst]): the
                        // stream is not opened a second time for the same run. And only while it is still the chat's
                        // latest: a follow-up accepted since (the persist above is a suspension) is followed already.
                        if (!e.isFollowing(active.id)) startStreaming(e, agentId, active, unlessMovedOn = true)
                    } else {
                        // The run being followed is over by the server's account: what its stream told before the
                        // connection dropped, or the outcome read from the run record, must not stand in for it any
                        // longer — the transcript has the reply now, and the replay below brings the whole trace.
                        // The status goes with it, in the same frame (see [Entry.stopFollowing]).
                        val followed = synchronized(e) { e.live?.runId }
                        if (followed != null && merged.any { it.id == followed && !it.statusEnum().isActive }) e.stopFollowing(over = latest)
                        // No run to stream while the account calls the chat running: kept followed (see [keepFollowing]).
                        if (accountSaysRunning(e)) keepFollowing(e, endedRunId = null)
                    }
                    loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
                    if (pageOlder) pageOlderRuns(e, agentId)
                    adoptDelivered(e)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            e.reportLoadFailure(t)
        }
    }

    /**
     * The run list landed before the `/v0` transcript: the newest runs are shown now — with the traces the disk has
     * for them, their replies and footers — over whatever transcript stands (the disk's, or none), and the live run
     * is followed. The transcript's text lays over them when it arrives; a transcript that never does leaves this
     * standing, said as such (see [ConversationState.transcriptError]).
     */
    private suspend fun publishRunsFirst(e: Entry, agentId: String, api: CursorApi, runResult: Result<ListRunsResponseDto>, onDevice: Map<String, List<MessageAttachment>>) {
        val firstPage = runResult.getOrNull() ?: return
        val latestId = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
        val (known, knownComplete) = synchronized(e) { e.runs to e.runsComplete }
        val newest = newestRuns(api, agentId, firstPage, latestId, known, knownComplete)
        if (newest.page.items.isEmpty()) return
        patchRowIfFollowedOver(e, agentId, newest.page.items)
        var latest: RunDto? = null
        var over: RunDto? = null
        e.publish(
            mutate = {
                mergeNewestPage(newest.page, endKnown = newest.endKnown)
                runOrder = if (newest.ascending) RunOrder.OLDEST_FIRST else RunOrder.NEWEST_FIRST
                latestFetchedById = newest.latestFetched
                pruneLocal()
                promptImages = onDevice + promptImages.filterKeys { key -> local.any { it.run.id == key } }
                latest = latestRun()
                // The run followed is over by the page just merged: its footer and the stream's end in one frame.
                over = endFollowIfOver()
            },
            // Still loading: the transcript is on its way. The screen shows what there is meanwhile.
            transform = { copy(activeRunId = latest?.id, runStatus = e.chatStatus(latest), isStreaming = over == null && isStreaming, isReconnecting = over == null && isReconnecting) },
        )
        latest?.let { run -> agents.recordRun(agentId, e.known(run)) }
        val active = latest?.takeIf { it.statusEnum().isActive }
        // A follow already open on the run stands: restarting it blanked the run's streamed items for a frame on
        // every load that landed during the turn (a foreground return, a reload).
        if (active != null && !e.isFollowing(active.id)) startStreaming(e, agentId, active, unlessMovedOn = true)
        loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
        adoptDelivered(e)
    }

    /**
     * Extended mode's transcript: the newest turns of the account's record (`FetchBackgroundComposer`, paged from
     * the record's end), shown the moment they are read; the run list (`/v1`, one page plus the latest run by id)
     * for each turn's status, footer and the live run to follow; the account's latest conversation state
     * (`GetLatestAgentConversationState`) for the turn count and every turn's timing. Each answer is shown as it
     * lands. Returns false when the record has nothing for the chat — not served for it — so the documented path
     * takes over; a record that cannot be read leaves the copy last read standing, with the failure said, and also
     * returns false when there is no such copy, so the chat still opens on what the documented endpoints give.
     */
    /** What [loadFromRecord] came to: whether the record served the chat, and the run list's first page it read meanwhile (handed on when it did not). */
    private class RecordLoad(val served: Boolean, val runPage: Result<ListRunsResponseDto>? = null)

    private suspend fun loadFromRecord(e: Entry, agentId: String, api: ConversationRecordApi, backend: CursorBackend, tokens: CacheTokens): RecordLoad = coroutineScope {
        val cursorApi = backend.api
        val (known, wantTurns) = synchronized(e) { e.recordWindow to e.window }
        // The record refused a moment ago and nothing of it is on screen: the documented path, at once, until the
        // pause the refusal asked for has passed — the refusal stands on the screen meanwhile.
        if (known == null && AppClock.now() < synchronized(e) { e.recordRefusedUntil }) return@coroutineScope RecordLoad(served = false)
        val readStartedAt = System.nanoTime()
        // A window in hand with its newest turn's steps is read on from its end — the record's delta, one small
        // round trip when nothing changed — rather than its newest page again: a reopen, a return to the foreground,
        // cost the steps the chat added since and not the megabytes it already has (see [RecordTranscript.append]).
        // A record shorter than the window knew it (rewound) is read from its end again, like a first open.
        // The blob-backed record is read a turn's blobs at a time: a cold open paints its newest few turns first and
        // brings the rest of the window right behind them (see [FIRST_PAINT_TURNS], the second stage below), so the
        // first frame waits on a handful of round trips rather than the window's every step.
        val stagedPaint = api.readsTurns && (known == null || !known.canAppend) && wantTurns > FIRST_PAINT_TURNS
        val firstWant = if (stagedPaint) FIRST_PAINT_TURNS else wantTurns
        val stateRead = async { net(agentId, "state"); runCatching { api.state(agentId) } }
        val tail = async {
            runCatching {
                if (api.readsTurns) {
                    // The blob-backed record: the state read once names the turns; a refusal of it is the record's refusal.
                    val state = stateRead.await().getOrThrow()
                    if (known != null && known.canAppend) RecordPager.sinceTurns(api, agentId, known.total, state) ?: RecordPager.tailTurns(api, agentId, wantTurns, state)
                    else RecordPager.tailTurns(api, agentId, firstWant, state)
                } else if (known != null && known.canAppend) {
                    RecordPager.since(api, agentId, known.total) ?: RecordPager.tail(api, agentId, wantTurns, null)
                } else {
                    RecordPager.tail(api, agentId, firstWant, known?.total)
                }
            }
        }
        val runPage = async { net(agentId, "runs"); runCatching { cursorApi.listRuns(agentId, limit = FIRST_RUN_PAGE) } }
        val stored = async { runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap()) }
        val raw = tail.await()
        val onDevice = stored.await()
        val rawWindow = raw.getOrNull()
        if (raw.isSuccess && rawWindow == null) {
            // Nothing in the record for this chat: the documented endpoints are its only account — with the run page
            // read beside the record, so the fallback does not ask for it again.
            e.publish(mutate = { recordEmpty = true; recordError = null; recordRefusedUntil = 0L }, transform = { copy(recordFallback = null) })
            stateRead.cancel()
            return@coroutineScope RecordLoad(served = false, runPage = runPage.await())
        }
        if (rawWindow == null) {
            val failure = raw.exceptionOrNull()
            val message = failure?.userMessage() ?: "The account's record could not be read."
            synchronized(e) { e.recordError = message }
            if (known == null) {
                // Nothing on screen from the record and none to be had now: the documented path shows what it can —
                // and says so, in the server's words, with the pause the refusal asked for kept, so the record is not
                // asked again on every open meanwhile (see [ConversationState.recordFallback]).
                stateRead.cancel()
                val now = AppClock.now()
                val retryAfter = (failure as? ConnectRpcException)?.retryAfterMillis
                val fallback = RecordFallback(message, now, (System.nanoTime() - readStartedAt) / 1_000_000, retryAfter)
                // A removal is not a pause: the server has said the read is gone, and asking every half minute
                // costs a round trip on every open for nothing (see [RECORD_REMOVED_RETRY_MS]).
                val pause = when {
                    retryAfter != null -> retryAfter.coerceAtLeast(RECORD_RETRY_MIN_MS)
                    failure.isRecordRemoved() -> RECORD_REMOVED_RETRY_MS
                    else -> RECORD_RETRY_MS
                }
                e.publish(
                    mutate = { recordRefusedUntil = now + pause },
                    transform = { copy(recordFallback = fallback) },
                )
                return@coroutineScope RecordLoad(served = false, runPage = runPage.await())
            }
            e.publish(transform = { copy(isLoading = false, transcriptError = message) })
        } else {
            val sink = images?.forAgent(agentId)
            val now = AppClock.now()
            // A turn-indexed delta starts at the newest known turn, read again whole (see RecordPager.sinceTurns).
            val delta = known != null && known.canAppend && rawWindow.turnIndexed == known.turnIndexed &&
                (rawWindow.firstStep == known.total || (rawWindow.turnIndexed && rawWindow.firstStep == known.total - 1))
            var built = if (delta) RecordTranscript.append(known!!, rawWindow, now, wantTurns = wantTurns, build = turnBuilder(agentId, sink))
            else RecordTranscript.window(rawWindow, known, state = null, now, wantTurns = wantTurns, build = turnBuilder(agentId, sink))
            var project = false
            e.publish(
                mutate = {
                    recordWindow = built
                    recordEmpty = false
                    recordError = null
                    recordRefusedUntil = 0L
                    transcriptError = null
                    transcriptUnavailable = false
                    fetched = true
                    fetchedAt = now
                    inputsUpdatedAt = agents.agent(agentId)?.updatedAtMillis ?: 0L
                    if (built.turns.any { it.projectMode }) projectMode = true
                    project = projectMode
                    pruneLocal()
                    promptImages = onDevice + promptImages.filterKeys { key -> local.any { it.run.id == key } }
                },
                transform = { copy(isLoading = false, error = null, transcriptError = null, transcriptUnavailable = false, isProjectConversation = project, recordFallback = null) },
            )
            persistRecord(e, built, known, backend, tokens)
            // The second stage of a cold open on the blob-backed record: the rest of the window's turns, behind the ones on screen.
            if (stagedPaint && built.hasOlder && built.turns.size < wantTurns) {
                val older = runCatching { RecordPager.before(api, agentId, built.firstStep, wantTurns - built.turns.size) }.getOrElse { t -> if (t is CancellationException) throw t; null }
                if (older != null && older.steps.isNotEmpty()) {
                    val widened = RecordTranscript.prepend(built, older, AppClock.now(), wantTurns = wantTurns, build = turnBuilder(agentId, sink))
                    e.publish(mutate = { if (recordWindow === built) recordWindow = widened; pruneLocal() })
                    persistRecord(e, widened, built, backend, tokens)
                    built = widened
                }
            }
        }
        // The runs: each turn's status and footer, and the run to follow.
        val runResult = runPage.await()
        val firstPage = runResult.getOrNull()
        if (firstPage != null) {
            val latestId = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
            val (knownRuns, knownComplete) = synchronized(e) { e.runs to e.runsComplete }
            val newest = newestRuns(cursorApi, agentId, firstPage, latestId, knownRuns, knownComplete)
            patchRowIfFollowedOver(e, agentId, newest.page.items)
            var latest: RunDto? = null
            var merged: List<RunDto> = emptyList()
            e.publish(
                mutate = {
                    mergeNewestPage(newest.page, endKnown = newest.endKnown)
                    runOrder = if (newest.ascending) RunOrder.OLDEST_FIRST else RunOrder.NEWEST_FIRST
                    latestFetchedById = newest.latestFetched
                    pruneLocal()
                    latest = latestRun()
                    merged = runs
                    // The run followed is over by the list just merged: the follow ends in this frame, with the footer.
                    endFollowIfOver()
                },
                transform = {
                    // A stream already open on the latest run keeps its word (see [publishRunsFirst]).
                    val following = e.streamJob?.isActive == true && activeRunId == latest?.id
                    copy(activeRunId = latest?.id, runStatus = if (following) runStatus else e.chatStatus(latest), isStreaming = following && isStreaming, isReconnecting = following && isReconnecting)
                },
            )
            val knownLatest = latest?.let { e.known(it) }
            knownLatest?.let { run -> agents.recordRun(agentId, run) }
            launch { agents.loadDetail(agentId, knownLatest) }
            val active = latest?.takeIf { it.statusEnum().isActive }
            if (active != null) {
                if (!e.isFollowing(active.id)) startStreaming(e, agentId, active, unlessMovedOn = true)
            } else {
                val followed = synchronized(e) { e.live?.runId }
                if (followed != null && merged.any { it.id == followed && !it.statusEnum().isActive }) e.stopFollowing(over = latest)
                // No run to stream, and the account calls the chat running: the record is the source (see [keepFollowing]).
                if (accountSaysRunning(e)) keepFollowing(e, endedRunId = null)
            }
            // The turns the record holds without their steps — or, in a coordinator's chat, without the coordinator's
            // word — get them from their runs' logs, newest first.
            loadTraces(e, agentId, e.shownRuns())
            // A window the first page of runs does not reach (a Project's injected turns, dozens between two of the
            // user's) has its older runs paged in behind it; the replays of the turns they pair with follow.
            if (synchronized(e) { e.recordNeedsRuns() }) pageOlderRuns(e, agentId)
            // A turn the record gave without its text and no log left to ask: the transcript's copy of its reply.
            fillTextFromTranscript(e, agentId)
            adoptDelivered(e)
        } else if (rawWindow == null) {
            // Neither the record nor the runs answered: nothing new to show, and the record's failure already stands.
        }
        // The account's word on the whole chat: how many turns there are, and each one's timing.
        stateRead.await().getOrNull()?.let { state ->
            var project = false
            e.publish(
                mutate = {
                    recordWindow = recordWindow?.withState(state)
                    if (state.isRootProject) projectMode = true
                    project = projectMode
                },
                transform = { copy(isProjectConversation = project) },
            )
        }
        agents.agent(agentId)?.let { prefs.markRead(agentId, it.listedAtMillis) }
        persist(e, backend, tokens)
        RecordLoad(served = true)
    }

    /**
     * The account's word that the chat runs, apart from any run record or stream: the row's status, the account
     * list's running set (Extended mode), or — for a chat on one of the user's machines — the machine reporting the
     * chat as the one it is busy with. Never a run this device itself cancelled.
     */
    private suspend fun accountSaysRunning(e: Entry): Boolean {
        if (synchronized(e) { e.rowSaysRunning() }) return true
        val row = agents.agent(e.agentId) ?: return false
        if (row.envType != EnvType.MACHINE || row.isArchived) return false
        if (synchronized(e) { e.cancelledRunId } != null && row.latestRunId == synchronized(e) { e.cancelledRunId }) return false
        return runCatching { machineBusy(row) }.getOrNull() == true
    }

    /**
     * Keeps the chat followed while the account calls it running and no stream is open on a run of it. Every few
     * seconds, with the pause growing to [KEEP_FOLLOWING_MAX_MS]: the agent's record is read for its latest run
     * (`GET /v1/agents/{id}`, then the run by id), and a run other than [endedRunId] that is active is merged into
     * the list and followed — the steer's next run, the coordinator's next turn. Until there is one, and in Extended
     * mode for as long as no stream says anything, the account's record is read for what the turn has added
     * (`FetchBackgroundComposer` at the known end, the tail re-read when it grew), so the steps arrive from the one
     * source there is: a machine agent whose run list and stream never answer still streams from its record. Ends
     * when the account no longer calls the chat running (the chat's status then takes the records' word), when a
     * stream is open and saying something, or when the screen leaves. Never ends the run itself: no timeout here
     * makes a turn cancelled or finished.
     */
    private fun keepFollowing(e: Entry, endedRunId: String?) {
        synchronized(e) {
            if (e.keepFollowingJob?.isActive == true || e.attached == 0 || e.paused) return
            e.keepFollowingJob = e.scope.launch {
                var wait = KEEP_FOLLOWING_BASE_MS
                var idleLooks = 0
                while (isActive && synchronized(e) { e.attached > 0 && !e.paused }) {
                    // The agent's record first: a newer run named there is followed, and the row it refreshes is what
                    // the account's word below is read from.
                    val followed = followNextRun(e, endedRunId)
                    // A stream open and speaking is the source; this loop stands down for it.
                    val streamSpeaking = synchronized(e) { e.streamJob?.isActive == true && (e.live?.items?.isNotEmpty() == true || (e.state.value.isStreaming && !e.state.value.isReconnecting && e.live == null)) }
                    if (followed || streamSpeaking) return@launch
                    if (!accountSaysRunning(e)) {
                        // The account agrees the chat is idle — after a few looks, for a run that ended a moment ago:
                        // the next run of a steer, of a coordinator's next turn, takes a beat to be named. Then the
                        // records' word stands (see [Entry.chatStatus]).
                        if (endedRunId == null || ++idleLooks >= KEEP_FOLLOWING_IDLE_LOOKS) {
                            e.publish(transform = { copy(runStatus = e.chatStatus(e.latestRun(), streaming = false)) })
                            return@launch
                        }
                    } else {
                        idleLooks = 0
                        readRecordGrowth(e)
                        e.publish(transform = { if (runStatus?.isActive != true) copy(runStatus = RunStatus.RUNNING) else this })
                    }
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(KEEP_FOLLOWING_MAX_MS)
                }
            }
        }
    }

    /**
     * One look for a run of the chat other than [endedRunId] that is active: the agent's record (`GET /v1/agents/{id}`)
     * names it; it is read by id, merged and followed. True when one was found and is followed now.
     */
    private suspend fun followNextRun(e: Entry, endedRunId: String?): Boolean {
        val agentId = e.agentId
        val api = session.current.api
        // The agent's record, read for its latest run alone: the row is left as it is (a record a poll behind a
        // finish the stream saw would otherwise put the spinner back on the finished run).
        net(agentId, "agent")
        val detail = runCatching { api.getAgent(agentId) }.getOrElse { t -> if (t is CancellationException) throw t; null }
        val latestId = (detail?.latestRunId ?: agents.agent(agentId)?.latestRunId)?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) || it == endedRunId } ?: return false
        val known = synchronized(e) { e.runById(latestId) }
        val run = known?.takeIf { it.statusEnum().isActive } ?: runCatching { net(agentId, "run"); api.getRun(agentId, latestId) }.getOrElse { t -> if (t is CancellationException) throw t; null } ?: return false
        // Active as this device knows it: the run it stopped is not the next one to follow, whatever its record says yet.
        if (!e.statusOf(run).isActive) return false
        var follow = false
        e.publish(mutate = {
            if (runs.none { it.id == run.id }) runs = listOf(run) + runs
            else runs = runs.map { if (it.id == run.id) run else it }
            follow = streamJob?.isActive != true || state.value.activeRunId != run.id
        }, transform = { copy(activeRunId = run.id, runStatus = RunStatus.RUNNING) })
        // The row learns the new run from it, adopted as its latest (see [Agent.withLatestRun]) — through the list's
        // own reading of the record, like every run record that reaches a row (see `AgentRepository.recordRun`).
        agents.recordRun(agentId, run, adopt = true)
        if (follow) startStreaming(e, agentId, run)
        // A new run is where a message the account queued from here lands (see [expectDelivery]).
        adoptDelivered(e)
        return true
    }

    /** Extended mode: what the account's record has added since it was last read, shown as the turn's steps so far. */
    private suspend fun readRecordGrowth(e: Entry) {
        val api = record ?: return
        val known = synchronized(e) { e.recordWindow } ?: return
        if (session.isDemo || !capabilities().accountTranscript) return
        val sink = images?.forAgent(e.agentId)
        val wantTurns = synchronized(e) { e.window }
        val built: RecordWindow
        if (known.canAppend) {
            // The delta alone: one small read at the known end, the appended steps when there are any (see [RecordTranscript.append]).
            val delta = runCatching { RecordPager.since(api, e.agentId, known.total) }.getOrElse { t -> if (t is CancellationException) throw t; return }
            if (delta == null) {
                // Rewound: read from the end again.
                val raw = runCatching { RecordPager.tail(api, e.agentId, wantTurns, null) }.getOrElse { t -> if (t is CancellationException) throw t; null } ?: return
                built = RecordTranscript.window(raw, known, state = null, AppClock.now(), wantTurns = wantTurns, build = turnBuilder(e.agentId, sink))
            } else {
                if (delta.steps.isEmpty()) return
                built = RecordTranscript.append(known, delta, AppClock.now(), wantTurns = wantTurns, build = turnBuilder(e.agentId, sink))
                // Nothing grew: the newest turn read again as it was.
                if (built === known) return
            }
        } else {
            val grown = runCatching { RecordPager.grownPast(api, e.agentId, known.total) }.getOrElse { t -> if (t is CancellationException) throw t; false }
            if (!grown) return
            val raw = runCatching { RecordPager.tail(api, e.agentId, wantTurns, known.total) }.getOrElse { t -> if (t is CancellationException) throw t; null } ?: return
            built = RecordTranscript.window(raw, known, state = null, AppClock.now(), wantTurns = wantTurns, build = turnBuilder(e.agentId, sink))
        }
        // The record has grown: the prompts sent from here it now holds hand over to it (see [pruneLocal]).
        // A growth read is a fetch: a reader back within [REOPEN_FRESH_MS] of it finds the chat fresh (see [attach]).
        e.publish(mutate = { if (recordWindow === known) recordWindow = built; fetchedAt = AppClock.now(); pruneLocal() })
        persistRecord(e, built, known, session.current, cacheTokens())
        // The turns the record grew by are the chat's newest runs, and the window pairs turns with runs by position
        // from the newest: the list's first page is read again so its newest end is the record's — a Project's
        // injected turns arrive by the dozen between two reads, and a list a dozen runs short paired every turn with
        // the run a dozen before it. The turns the refreshed list pairs and finds finished get their logs replayed.
        refreshNewestRuns(e)
        loadTraces(e, e.agentId, e.shownRuns())
    }

    /**
     * Reads the run list's first page again and folds it in: the newest runs' records (a finished turn's status, a
     * run started since) with the order the list came in read as [loadFromRecord] reads it. This serves the pairing
     * of record turns with runs (see [Entry.recordNeedsRuns]); the chat's status and follow are left as they are —
     * except a follow on a run the list now shows over, which ends in the frame that first shows the run's footer
     * (see [Entry.endFollowIfOver]), never a frame later.
     */
    private suspend fun refreshNewestRuns(e: Entry) {
        val api = session.current.api
        val agentId = e.agentId
        val first = runCatching { net(agentId, "runs"); api.listRuns(agentId, limit = FIRST_RUN_PAGE) }.getOrElse { t -> if (t is CancellationException) throw t; return }
        val latestId = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
        val (knownRuns, knownComplete) = synchronized(e) { e.runs to e.runsComplete }
        val newest = newestRuns(api, agentId, first, latestId, knownRuns, knownComplete)
        // A page that reaches none of the runs in hand has runs between it and them — a burst of injected turns
        // longer than a page — which are read too, a few pages at most, so the list has no hole to pair turns across.
        var page = newest.page
        val knownIds = knownRuns.mapTo(HashSet()) { it.id }
        var pages = 0
        while (knownIds.isNotEmpty() && page.items.isNotEmpty() && page.items.none { it.id in knownIds } && !page.nextCursor.isNullOrBlank() && pages < MAX_RUN_PAGES) {
            val more = runCatching { net(agentId, "runs"); api.listRuns(agentId, limit = RUN_PAGE_SIZE, cursor = page.nextCursor) }.getOrElse { t -> if (t is CancellationException) throw t; null } ?: break
            if (more.items.isEmpty()) break
            val have = page.items.mapTo(HashSet()) { it.id }
            page = ListRunsResponseDto(items = page.items + more.items.filter { it.id !in have }, nextCursor = more.nextCursor)
            pages++
        }
        patchRowIfFollowedOver(e, agentId, page.items)
        var over: RunDto? = null
        e.publish(
            mutate = {
                mergeNewestPage(page, endKnown = newest.endKnown)
                runOrder = if (newest.ascending) RunOrder.OLDEST_FIRST else RunOrder.NEWEST_FIRST
                latestFetchedById = newest.latestFetched
                pruneLocal()
                over = endFollowIfOver()
            },
            transform = { if (over != null) copy(isStreaming = false, isReconnecting = false, runStatus = e.chatStatus(over, streaming = false)) else this },
        )
        if (synchronized(e) { e.recordNeedsRuns() }) pageOlderRuns(e, agentId)
        adoptDelivered(e)
    }

    /** Widens the record's window by [WINDOW_RUNS] older turns (see [loadOlderNow]). */
    private suspend fun loadOlderFromRecord(e: Entry, window: RecordWindow) {
        val api = record ?: return
        val backend = session.current
        val tokens = cacheTokens()
        // The page read ahead, when it is the one before this window; else read now.
        val older = synchronized(e) { e.prefetchedOlder?.takeIf { it.isPageBefore(window) }?.also { e.prefetchedOlder = null } }
            ?: RecordPager.before(api, e.agentId, window.firstStep, WINDOW_RUNS)
        val sink = images?.forAgent(e.agentId)
        val built = RecordTranscript.prepend(window, older, AppClock.now(), wantTurns = WINDOW_RUNS, build = turnBuilder(e.agentId, sink))
        e.publish(mutate = {
            // Only onto the window this was asked for: a re-read since replaced it, and its turns stand.
            if (recordWindow === window || recordWindow?.firstStep == window.firstStep) recordWindow = built
            this.window += WINDOW_RUNS
            // The page may hold the turn of a prompt sent from here that the newest turns did not: the echo hands over to it.
            pruneLocal()
        })
        prefetchOlderRecord(e, built)
        persistRecord(e, built, window, backend, tokens)
        // The run list is read on to cover the widened window before the replays are asked for: a turn without
        // its run has no footer and no reply from the run's log (see [Entry.recordNeedsRuns]).
        if (synchronized(e) { e.recordNeedsRuns() }) pageOlderRunsNow(e, e.agentId, pages = MAX_RUN_PAGES)
        loadTraces(e, e.agentId, e.shownRuns())
        fillTextFromTranscript(e, e.agentId)
    }

    /** Whether this page ends exactly where [window] begins: the page before it. */
    private fun RecordPager.Raw.isPageBefore(window: RecordWindow): Boolean =
        steps.isNotEmpty() && turnIndexed == window.turnIndexed && firstStep + (if (turnIndexed) steps.mapNotNull { it.turnIndex }.distinct().size else steps.size) == window.firstStep

    /**
     * Reads the record's page before [window] ahead of the reader's next scroll up, so the next [loadOlderFromRecord]
     * has it in hand (see [Entry.prefetchedOlder]). One read at a time, only while a screen shows the chat, and only
     * once the reader has scrolled up once — a reader who never leaves the newest turns costs nothing more.
     */
    private fun prefetchOlderRecord(e: Entry, window: RecordWindow) {
        val api = record ?: return
        synchronized(e) {
            if (!window.hasOlder || e.attached == 0 || e.paused || e.prefetchJob?.isActive == true) return
            if (e.prefetchedOlder?.isPageBefore(window) == true) return
            e.prefetchedOlder = null
            e.prefetchJob = e.scope.launch {
                val raw = runCatching { RecordPager.before(api, e.agentId, window.firstStep, WINDOW_RUNS) }
                    .getOrElse { t -> if (t is CancellationException) throw t; null } ?: return@launch
                synchronized(e) { if (e.attached > 0 && e.recordWindow?.firstStep == window.firstStep) e.prefetchedOlder = raw }
            }
        }
    }

    /**
     * Writes the record's window to disk: the conversation's copy of where it sits, and one file per turn for the
     * turns [previous] did not already hold with the same steps (a finished turn's items never change; the live
     * turn's are rewritten as it grows).
     */
    private suspend fun persistRecord(e: Entry, window: RecordWindow, previous: RecordWindow?, backend: CursorBackend, tokens: CacheTokens) {
        val before = previous?.turns?.associateBy { it.stepIndex }
        val changed = window.turns.takeLast(MAX_RESTORED_WINDOW).filter { turn ->
            val old = before?.get(turn.stepIndex)
            old == null || old.stepCount != turn.stepCount || old.items !== turn.items
        }
        if (changed.isNotEmpty()) {
            val now = AppClock.now()
            withContext(NonCancellable) {
                writeTraces(e.agentId, changed.map { turn -> CachedTrace(turn.traceKey, now - (window.total - turn.stepIndex), turn.items) }, tokens)
            }
        }
        persist(e, backend, tokens)
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

    /** The runs the window renders right now, newest last. */
    private fun Entry.shownRuns(): List<RunDto> = synchronized(this) { if (recordWindow != null) recordTurnsNeedingReplay() else layout().let { it.paired + it.standing } }

    /**
     * Folds the newest page of the run list into [Entry.runs], which hold the chat's newest runs and, behind them, as
     * many older ones as the list has been paged to. The page's records replace what they name and the older runs
     * already held stay; a run started elsewhere since joins at the top. The page's cursor is the way to the rest of
     * the list only when nothing older is held already — otherwise the older records, and the cursor past them,
     * are the entry's own.
     */
    private fun Entry.mergeNewestPage(page: ListRunsResponseDto, endKnown: Boolean = true) {
        val fresh = page.items
        val cursor = page.nextCursor?.takeIf { it.isNotBlank() }
        if (cursor == null) {
            // The page is the whole list: whatever else was held (a copy from another session) is not the chat's.
            // Unless the end was never reached (see [newestRuns]): then these are the newest runs and the rest are
            // simply not to be had, so the prompts before them stand without runs.
            runs = fresh
            olderRunsCursor = null
            runsComplete = endKnown
            return
        }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val older = runs.filter { it.id !in freshIds }
        runs = fresh + older
        if (older.isEmpty()) {
            olderRunsCursor = cursor
            runsComplete = false
        }
    }

    /**
     * The first page of the run list as a page of the chat's newest runs, whichever way the server listed them.
     *
     * The reference documents `GET /v1/agents/{id}/runs` newest first, and the field has shown it oldest first: on a
     * chat of a hundred and sixty turns, the first page was the chat's first twenty runs, and everything built on it
     * — the run followed as the live one, the runs whose logs were asked for, the run the row was patched with — was
     * about turns finished months before. The text of the newest prompts, laid over them, showed with no tool calls,
     * the ongoing turn read as finished, and the row went idle. So the order is read off the page itself and off the
     * agent's [latestRunId]: a page listed oldest first is read on to the end of the list, so that the runs in hand
     * are every run and the newest among them (past [MAX_RUN_PAGES] the newest run alone stands, fetched by id); a
     * page listed newest first that lacks the latest run — the list lagging the agent's record — gets it by id. The
     * cursor a newest-first page carries stays the way to the older records (see [pageOlderRuns]).
     */
    private suspend fun newestRuns(api: CursorApi, agentId: String, first: ListRunsResponseDto, latestId: String?, known: List<RunDto>, knownComplete: Boolean): NewestRuns {
        val items = first.items
        val cursor = first.nextCursor?.takeIf { it.isNotBlank() }
        val ascending = items.size >= 2 && parseIsoMillis(items.first().createdAt) < parseIsoMillis(items.last().createdAt)
        var page = first
        var endKnown = true
        if (ascending && cursor != null && latestId != null && known.any { it.id == latestId }) {
            // Read before, and the agent has started nothing since: the runs in hand are the newest still, the page
            // refreshes the records it names, and the rest of the pages are not read again.
            val fresh = items.associateBy { it.id }
            page = ListRunsResponseDto(items = known.map { fresh[it.id] ?: it }, nextCursor = null)
            endKnown = knownComplete
        } else if (ascending && cursor != null) {
            // Oldest first: the newest runs are at the end of the list, so read to it.
            val all = items.toMutableList()
            var next: String? = cursor
            var pages = 1
            while (next != null && pages < MAX_RUN_PAGES) {
                val more = runCatching { net(agentId, "runs"); api.listRuns(agentId, limit = RUN_PAGE_SIZE, cursor = next) }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    null
                } ?: break
                if (more.items.isEmpty()) { next = null; break }
                all += more.items
                next = more.nextCursor?.takeIf { it.isNotBlank() }
                pages++
            }
            page = if (next == null) {
                // Every run, newest first as the rest of this class expects a complete list to be laid out.
                ListRunsResponseDto(items = all.distinctBy { it.id }.sortedByDescending { parseIsoMillis(it.createdAt) }, nextCursor = null)
            } else {
                // Read as far as it will be, and still short of the end: the runs in hand are old ones, and laying
                // them under the newest prompts would be the very bug. The newest run alone stands, when it is known.
                endKnown = false
                ListRunsResponseDto(items = emptyList(), nextCursor = null)
            }
        }
        if (latestId != null && page.items.none { it.id == latestId }) {
            val latest = runCatching { net(agentId, "run"); api.getRun(agentId, latestId) }.getOrElse { t ->
                if (t is CancellationException) throw t
                null
            }
            if (latest != null) page = page.copy(items = listOf(latest) + page.items.filter { it.id != latest.id })
        }
        return NewestRuns(page, ascending = ascending, endKnown = endKnown, latestFetched = latestId != null && first.items.none { it.id == latestId } && page.items.any { it.id == latestId })
    }

    /** What [newestRuns] made of the first page: the page to merge, and the facts about it the diagnostics report. */
    private class NewestRuns(val page: ListRunsResponseDto, val ascending: Boolean, val endKnown: Boolean, val latestFetched: Boolean)

    /**
     * Pages the rest of the run records behind the newest page, oldest last, so the transcript's prompts pair with
     * their own runs and the older turns are ready to be built when the reader scrolls up to them. The records are
     * small; what is dear — replaying the runs' logs — waits for the window to reach them. Bounded: a chat longer
     * than [MAX_RUN_PAGES] pages has turns whose logs expired long ago, and its oldest prompts are shown without runs.
     * Once the records are in, the window's traces are asked for again: the runs the window pairs with can have
     * changed with the count.
     */
    private fun pageOlderRuns(e: Entry, agentId: String) {
        synchronized(e) {
            if (e.runPagingJob?.isActive == true || e.runsComplete || e.olderRunsCursor == null) return
            e.runPagingJob = e.scope.launch {
                val pages = pageOlderRunsNow(e, agentId, pages = MAX_RUN_PAGES)
                // The bound was reached with a cursor still in hand: the list is as complete as it will be read, so
                // the prompts beyond it stand without runs rather than waiting for pages nobody asks for.
                if (pages >= MAX_RUN_PAGES) {
                    e.publish(mutate = { if (!runsComplete && olderRunsCursor != null) runsComplete = true })
                    persist(e, session.current)
                }
                if (pages > 0 && synchronized(e) { e.attached > 0 }) {
                    loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
                    if (synchronized(e) { e.recordWindow != null }) fillTextFromTranscript(e, agentId)
                }
            }
        }
    }

    /**
     * Fetches up to [pages] pages of older run records and folds each in as it lands. Returns how many landed — a
     * failure leaves the cursor where it was for the next attempt.
     */
    private suspend fun pageOlderRunsNow(e: Entry, agentId: String, pages: Int, onFailure: (Throwable) -> Unit = {}): Int {
        val backend = session.current
        val tokens = cacheTokens()
        var fetched = 0
        for (i in 0 until pages) {
            // In Extended mode the pages serve the record's window: enough of them to cover it, no more.
            val cursor = synchronized(e) { e.olderRunsCursor.takeUnless { e.runsComplete || (e.recordWindow != null && !e.recordNeedsRuns()) } } ?: break
            val page = runCatching { net(agentId, "runs"); backend.api.listRuns(agentId, limit = RUN_PAGE_SIZE, cursor = cursor) }.getOrElse { t ->
                if (t is CancellationException) throw t
                onFailure(t)
                break
            }
            fetched++
            e.publish(
                mutate = {
                    val known = runs.mapTo(HashSet()) { it.id }
                    runs = runs + page.items.filter { it.id !in known }
                    olderRunsCursor = page.nextCursor?.takeIf { it.isNotBlank() && page.items.isNotEmpty() }
                    runsComplete = olderRunsCursor == null
                    pruneLocal()
                },
            )
        }
        if (fetched > 0) persist(e, backend, tokens)
        return fetched
    }

    /**
     * Widens the window by [WINDOW_RUNS] older turns: their run records are fetched when the list has not reached
     * them yet, their items built, and their traces read from disk or replayed, newest first. For the screen's
     * scroll-up; settled on the entry's scope like [resume] is.
     */
    fun loadOlder(agentId: String) = offload(agentId) { e -> loadOlderNow(e) }

    /**
     * Asks again for the traces the last pass could not read (see [TraceStatus.failed]) — the reader's Retry — and
     * for anything of the window still missing that is not known to have expired.
     */
    fun retryTraces(agentId: String) = offload(agentId) { e ->
        synchronized(e) { e.failedTraces.clear() }
        loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
    }

    private suspend fun loadOlderNow(e: Entry) {
        val recordWindow: RecordWindow?
        val proceed = synchronized(e) {
            if (e.loadingOlder || e.attached == 0) return
            recordWindow = e.recordWindow
            if (recordWindow != null) {
                if (!recordWindow.hasOlder) return
            } else if (e.layout().olderCount <= 0) return
            e.loadingOlder = true
            true
        }
        if (!proceed) return
        e.state.update { it.copy(isLoadingOlder = true) }
        if (recordWindow != null) {
            try {
                loadOlderFromRecord(e, recordWindow)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                e.publish(transform = { copy(transcriptError = t.userMessage()) })
            } finally {
                e.publish(mutate = { loadingOlder = false }, transform = { copy(isLoadingOlder = false) })
            }
            return
        }
        try {
            // Records first: the window must not widen onto prompts whose runs have not been fetched, or their
            // footers and traces would be missing while a page is still in flight.
            e.runPagingJob?.takeIf { it.isActive }?.join()
            val needsRecords = synchronized(e) {
                !e.runsComplete && e.olderRunsCursor != null && e.allRuns().size < e.window + WINDOW_RUNS
            }
            // A page that could not be read is said under the transcript, in the server's words, with the way to
            // ask again; what is shown stands, and the cursor stays where it was for the next scroll up, which
            // clears the word when it goes through.
            if (needsRecords) {
                val fetched = pageOlderRunsNow(e, e.agentId, pages = 1) { t -> e.publish(mutate = { olderPageFailed = true }, transform = { copy(transcriptError = t.userMessage()) }) }
                if (fetched > 0 && synchronized(e) { e.olderPageFailed }) e.publish(mutate = { olderPageFailed = false }, transform = { copy(transcriptError = null) })
            }
            e.publish(mutate = { window += WINDOW_RUNS })
            persist(e, session.current)
            loadTraces(e, e.agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
        } finally {
            e.publish(mutate = { loadingOlder = false }, transform = { copy(isLoadingOlder = false) })
        }
    }

    /**
     * Nothing came back from the server. That is the chat's error when there is nothing else to show; with a prompt
     * sent from here on screen it is a revalidation that did not work out, and the next one will.
     */
    private fun Entry.reportLoadFailure(cause: Throwable?) {
        val standingIn = synchronized(this) { local.isNotEmpty() }
        state.update { it.copy(isLoading = false, error = if (standingIn) it.error else cause?.userMessage()) }
        // Republished: the traces the load did not get to ask for read as failed now, with their Retry (see [traceStatus]).
        publish()
    }

    /**
     * Drops the local prompts the server now reports in full — the run listed and, going by position, its message in
     * the transcript. Only ever applied to the server's answer: the disk copy is these prompts' own flattened form and
     * proves nothing about what the server knows.
     */
    private fun Entry.pruneLocal() {
        if (local.isEmpty()) return
        val listed = runs.mapTo(HashSet()) { it.id }
        recordWindow?.let { window ->
            // The record has the prompt's turn: the server reports it in full, and the echo goes. A prompt the server
            // has not answered for (a placeholder run) is kept until it has. The turn pairs with its run once the echo
            // is gone, so a run the list has not reached yet (a coordinator's injected turns fill the first page)
            // joins the list from the answer the server gave when the prompt was sent.
            val held = local.filter { prompt -> prompt.filed && window.turnOf(prompt) != null }
            if (held.isEmpty()) return
            val missing = held.map { it.run }.filter { it.id !in listed }
            if (missing.isNotEmpty()) runs = runs + missing
            local = local - held
            return
        }
        val ordered = allRuns()
        val covered = coveredCount()
        // A prompt steered into a run under way has no run of its own for the transcript to pair it with: its echo
        // stays where the account delivered it, and the transcript's copy of it is left out (see [Entry.shownMessages]).
        local = local.filter { prompt -> prompt.steeredAfter != null || prompt.run.id !in listed || ordered.indexOfFirst { it.id == prompt.run.id } >= covered }
    }

    /** Shows the transcript and traces saved by an earlier visit, if any, while the network answers. */
    private suspend fun restoreFromCache(e: Entry, agentId: String) {
        // An entry that already has its inputs has nothing to restore.
        if (synchronized(e) { e.hasInputs || e.fetched }) return
        val cached = readCache(agentId) ?: return
        // The inputs arrived while the file was being read: the memory copy wins.
        if (synchronized(e) { e.hasInputs || e.fetched }) return
        val latest = (cached.runs + cached.local.map { saved -> saved.run }).maxByOrNull { parseIsoMillis(it.createdAt) }
        // A run that was still active when the cache was written has very likely finished since; the live state
        // ("Working…", Stop) waits for the network unless a fetched agent row confirms the run is still going.
        val list = agents.state.value
        val row = list.agents.firstOrNull { it.id == agentId }
        // The freshest word wins here too (see [Entry.chatStatus]): a fetched account that calls the chat running
        // stands over a copy whose newest run says cancelled or finished — that copy is of an older turn, or stale.
        // An active word is only ever shown from a fetched list; a cached one waits for the network.
        val accountRunning = row?.isRunning == true || agents.runningScan.value.accountIds?.contains(agentId) == true
        val status = e.chatStatus(latest, streaming = false)?.takeUnless { it.isActive && (list.isFromCache || !accountRunning) }
        // The prompts' images live on this device only, so the saved transcript can show them right away too.
        val onDevice = runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap())
        // A copy whose runs are not the chat's newest — a page an earlier build kept from a list read oldest first,
        // killed before the rest — must not be laid under the newest prompts (see [newestRuns]). The row's latest run
        // is the anchor: an incomplete copy without it renders its prompts alone until the network answers.
        val trusted = cached.runsComplete || row?.latestRunId == null || row.latestRunId.startsWith(LOCAL_RUN_PREFIX) || cached.runs.any { it.id == row.latestRunId } || cached.local.any { it.run.id == row.latestRunId }
        e.publish(
            mutate = {
                messages = cached.messages
                runs = if (trusted) cached.runs else emptyList()
                runsComplete = if (trusted) cached.runsComplete else false
                olderRunsCursor = if (trusted) cached.olderRunsCursor else null
                if (cached.window > 0) window = cached.window.coerceIn(WINDOW_RUNS, MAX_RESTORED_WINDOW)
                transcriptUnavailable = cached.transcriptUnavailable
                inputsUpdatedAt = cached.agentUpdatedAtMillis
                // The prompts sent from here go on standing in, exactly as they did when the copy was written.
                local = local + cached.local.filter { saved -> local.none { it.message.id == saved.message.id } }.map { LocalPrompt(it.message, it.run, it.reply, it.steeredAfter) }
                if (promptImages.isEmpty()) promptImages = onDevice
            },
            transform = { if (trusted) copy(activeRunId = latest?.id, runStatus = status, transcriptUnavailable = cached.transcriptUnavailable) else copy(transcriptUnavailable = cached.transcriptUnavailable) },
        )
        // The record's window (Extended mode): its turns' items come from their own files, one read per turn. Only
        // while the record is the transcript's source; with the mode off the documented copy below renders.
        // A saved window of the other record's kind (step-indexed, from before the blob-backed read) names other turns by its indices: the network reads afresh.
        cached.record?.takeIf { it.turns.isNotEmpty() && record != null && !session.isDemo && capabilities().accountTranscript && it.turnIndexed == record.readsTurns }?.let { saved ->
            val state = saved.turnCount.takeIf { it > 0 }?.let { count ->
                RecordState(count, saved.timings.map { TurnTiming(it.durationMs, it.timestampMs) }, pendingToolCalls = 0, isRootProject = false, numPriorInteractionUpdates = 0L, rewindEpoch = 0L)
            }
            // The newest turns' files first — the ones the screen opens on — and the rest of the saved window behind
            // them: a window of thirty turns is megabytes of JSON, and the reader should not wait on the twenty they
            // have to scroll up to before the ten in front of them show their tool calls.
            val newest = saved.turns.takeLast(WINDOW_RUNS)
            val older = saved.turns.dropLast(newest.size)
            var restored: RecordWindow? = null
            fun publishRestored(turns: List<CachedRecordTurn>, files: Map<String, CachedTrace>, onlyOver: RecordWindow?) {
                val built = turns.map { turn -> RecordTurn(turn.stepIndex, turn.stepCount, turn.prompt, turn.projectMode, files[RecordTurn.traceKey(turn.stepIndex, saved.turnIndexed)]?.items ?: emptyList(), errorMessage = turn.errorMessage, turnIndexed = saved.turnIndexed) }
                val window = RecordWindow(saved.total, built.first().stepIndex, built, emptyList(), state, readAtMillis = 0L, turnIndexed = saved.turnIndexed)
                var project = false
                e.publish(
                    mutate = {
                        // Only over what this restore put there: the network may have answered meanwhile, and its
                        // window — built on the newest turns restored, the older ones read again — stands.
                        if (onlyOver == null || recordWindow === onlyOver) {
                            recordWindow = window
                            restored = window
                        }
                        if (built.any { it.projectMode }) projectMode = true
                        project = projectMode
                    },
                    transform = { copy(isProjectConversation = project) },
                )
            }
            val newestFiles = readTraces(agentId, newest.map { RecordTurn.traceKey(it.stepIndex, saved.turnIndexed) })
            publishRestored(newest, newestFiles, onlyOver = null)
            if (older.isNotEmpty()) {
                val olderFiles = readTraces(agentId, older.map { RecordTurn.traceKey(it.stepIndex, saved.turnIndexed) })
                publishRestored(older + newest, olderFiles + newestFiles, onlyOver = restored)
            }
            // The turns the record holds without their steps: the traces their runs' logs gave last time, from disk.
            val replayed = readTraces(agentId, e.shownRuns().map { it.id })
            if (replayed.isNotEmpty()) e.publish(mutate = { traces = replayed.mapValues { it.value.items } + traces })
            return
        }
        // The window's traces come straight from their files — one small read per run, nothing of the older turns —
        // so the chat is whole, tool calls and payloads included, before the network has said a word. The newest
        // runs' files first, the rest of the window behind them, for the same reason as above.
        val shown = e.shownRuns().filter { it.statusEnum().isTerminal }
        val newestRuns = shown.takeLast(WINDOW_RUNS)
        val olderRuns = shown.dropLast(newestRuns.size)
        for (batch in listOf(newestRuns, olderRuns)) {
            if (batch.isEmpty()) continue
            val saved = readTraces(agentId, batch.map { it.id })
            if (saved.isEmpty()) continue
            e.publish(mutate = {
                traces = saved.mapValues { it.value.items } + traces
                // A file from an earlier build without the coordinator's messages or the goal's objective is shown,
                // and asked for again once the network answers (see [loadTraces]).
                staleTraces += saved.filterValues { isStaleTrace(it.items) }.keys
            })
        }
    }

    /** One network call of [kind] for the chat's counters (see [TranscriptPerf]). */
    private fun net(agentId: String, kind: String) = TranscriptPerf.session(agentId).network(kind)

    /** Builds a record turn's items (see [HeadlessTranscript.body]), timed for the chat's counters. */
    private fun turnBuilder(agentId: String, sink: GeneratedImageSink?): (HeadlessTranscript.Turn, String) -> List<TimelineItem> = { turn, key ->
        val startedAt = System.nanoTime()
        HeadlessTranscript.body(turn, key, sink).also { TranscriptPerf.session(agentId).turnBuilt(System.nanoTime() - startedAt) }
    }

    private suspend fun readCache(agentId: String): CachedConversation? {
        val store = cache?.takeIf { !session.isDemo } ?: return null
        TranscriptPerf.session(agentId).conversationRead()
        val cached = store.read(agentId)?.value
        diskIndex[agentId] = cached?.agentUpdatedAtMillis ?: ABSENT
        return cached
    }

    /** The traces the disk holds for [runIds]: a file per run, and only the runs named. */
    private suspend fun readTraces(agentId: String, runIds: Collection<String>): Map<String, CachedTrace> {
        if (runIds.isEmpty()) return emptyMap()
        val store = traceCache?.takeIf { !session.isDemo } ?: return emptyMap()
        val startedAt = System.nanoTime()
        return runCatching { store.read(agentId, runIds) }.getOrDefault(emptyMap())
            .also { TranscriptPerf.session(agentId).traceRead(runIds.size, System.nanoTime() - startedAt) }
    }

    /**
     * The cache generations the work started under. Sampling them inside the write instead would let a pass that a
     * sign-out cancelled — one that finishes its file after the wipe, as the trace replay deliberately does — write
     * the signed-out account's chat back to a cache that has since been reopened for the next one.
     */
    private data class CacheTokens(val conversations: Int, val traces: Int)

    private fun cacheTokens() = CacheTokens(cache?.token() ?: 0, traceCache?.token() ?: 0)

    private suspend fun persist(e: Entry, backend: CursorBackend, tokens: CacheTokens = cacheTokens()) {
        val store = cache ?: return
        if (backend.isDemo) return
        val snapshot = synchronized(e) { if (e.hasInputs || e.local.isNotEmpty()) e.toCached() else null } ?: return
        store.write(snapshot, tokens.conversations)
        diskIndex[snapshot.agentId] = snapshot.agentUpdatedAtMillis
    }

    private suspend fun writeTrace(agentId: String, runId: String, createdAtMillis: Long, items: List<TimelineItem>, tokens: CacheTokens = cacheTokens()) =
        writeTraces(agentId, listOf(CachedTrace(runId, createdAtMillis, items)), tokens)

    /** Each trace to its own file (see [TraceCache]): nothing of the agent's other runs is read or written for it. */
    private suspend fun writeTraces(agentId: String, traces: List<CachedTrace>, tokens: CacheTokens = cacheTokens()) {
        if (traces.isEmpty()) return
        val store = traceCache?.takeIf { !session.isDemo } ?: return
        runCatching { store.put(agentId, traces, tokens.traces) }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Asks for the traces of [finishedRuns]: queued, newest first, behind whatever is already queued, and drained by
     * one worker per entry ([drainTraces]). Asking for more runs while a pass is under way adds to it rather than
     * starting over — the pass that replayed the newest forty runs used to be cancelled the moment the run being
     * followed finished, and the runs it had not reached stayed text-only until the next fetch. A paused entry keeps
     * its queue for [resume]; a detached one drops it.
     */
    private fun loadTraces(e: Entry, agentId: String, finishedRuns: List<RunDto>) {
        synchronized(e) {
            finishedRuns
                .filter { (it.id !in e.traces || it.id in e.staleTraces) && it.id !in e.traceQueue && it.id !in e.traceInFlight && it.id !in e.expiredRuns && parseIsoMillis(it.createdAt) >= e.expiredBefore }
                .sortedByDescending { parseIsoMillis(it.createdAt) }
                .forEach { e.traceQueue[it.id] = it; e.failedTraces -= it.id }
            // A worker still registered drains what was just queued on its next round (it unregisters itself under
            // this monitor the moment it finds the queue empty, so nothing can slip in between).
            if (e.paused || e.traceQueue.isEmpty() || e.traceJob != null) return
            e.traceJob = e.scope.launch { drainTraces(e, agentId) }
        }
    }

    /**
     * Completes the traces of the queued runs, newest first: the disk answers first — one file per run, only the
     * runs asked for — and what it lacks is replayed from the retained stream. The retention window is time-based,
     * so once one run's log has expired every older run's has too and the rest are skipped, this session and the
     * next (see [Entry.expiredBefore]). A few workers pull from the queue, so the newest runs are always the first
     * ones asked. Every trace that lands is written back the moment it does, so it is asked for exactly once in the
     * life of the install; a pass cut short (the screen paused) puts what it had not settled back on the queue.
     */
    private suspend fun drainTraces(e: Entry, agentId: String) {
        val self = currentCoroutineContext()[Job]
        try {
            drainTraces(e, agentId, self)
        } finally {
            // However this worker ends — the queue empty, a pause, a failure — it is no longer the one to drain.
            synchronized(e) { if (e.traceJob === self) e.traceJob = null }
        }
    }

    private suspend fun drainTraces(e: Entry, agentId: String, self: Job?) {
        val tokens = cacheTokens()
        while (true) {
            val batch = synchronized(e) {
                val taken = e.traceQueue.values.sortedByDescending { parseIsoMillis(it.createdAt) }
                e.traceQueue.clear()
                taken.forEach { e.traceInFlight[it.id] = it }
                // Nothing left: this worker is done, and the next request starts the next one. Decided under the
                // monitor [loadTraces] queues under, so a run queued from now on always finds a worker to start.
                if (taken.isEmpty() && e.traceJob === self) e.traceJob = null
                taken
            }
            if (batch.isEmpty()) return
            try {
                // A stale trace is already shown from the disk; what is wanted for it is the replay, not the file again.
                val stale = synchronized(e) { e.staleTraces.toSet() }
                val saved = readTraces(agentId, batch.map { it.id }.filter { it !in stale })
                if (saved.isNotEmpty()) {
                    e.publish(mutate = {
                        traces = saved.mapValues { it.value.items } + traces
                        saved.keys.forEach { traceInFlight.remove(it) }
                        // A file from an earlier build without the coordinator's messages or the goal's objective: shown, and replayed after all.
                        saved.filterValues { isStaleTrace(it.items) }.keys.forEach { runId ->
                            staleTraces += runId
                            batch.firstOrNull { it.id == runId }?.let { traceInFlight[runId] = it }
                        }
                    })
                }
                val pending = synchronized(e) { batch.filter { it.id in e.traceInFlight } }
                if (pending.isEmpty()) continue
                // A log the hub already saw expire (an earlier pass, a reload) settles every older run before any
                // worker starts, so those are never asked again — the answer would depend on which worker finished first.
                val knownExpired = pending.filter { hub.current(agentId, it.id)?.expired == true }.maxOfOrNull { parseIsoMillis(it.createdAt) } ?: Long.MIN_VALUE
                val next = AtomicInteger(0)
                val newestExpired = AtomicLong(maxOf(knownExpired, synchronized(e) { e.expiredBefore }))
                coroutineScope {
                    repeat(minOf(MAX_PARALLEL_REPLAYS, pending.size)) {
                        launch {
                            while (true) {
                                val run = pending.getOrNull(next.getAndIncrement()) ?: return@launch
                                val createdAt = parseIsoMillis(run.createdAt)
                                if (createdAt < newestExpired.get()) {
                                    synchronized(e) { e.expiredRuns += run.id; e.traceInFlight.remove(run.id) }
                                    continue
                                }
                                net(agentId, "replay")
                                val snapshot = hub.replay(agentId, run.id, createdAt.takeIf { it > 0 })
                                when {
                                    snapshot.hasTrace -> {
                                        val trace = settleTrace(e, run, snapshot.items)
                                        // Kept the moment it is whole: a process that goes now loses nothing replayed.
                                        withContext(NonCancellable) { writeTraces(agentId, listOf(trace), tokens) }
                                    }
                                    snapshot.expired -> {
                                        newestExpired.updateAndGet { maxOf(it, createdAt) }
                                        synchronized(e) {
                                            e.expiredRuns += run.id
                                            e.expiredBefore = maxOf(e.expiredBefore, createdAt)
                                        }
                                    }
                                    // A log that could not be read right now is not there yet: said as such, and asked
                                    // for again on the next fetch or at the reader's request (see [retryTraces]).
                                    else -> synchronized(e) { e.failedTraces += run.id }
                                }
                                synchronized(e) { e.traceInFlight.remove(run.id) }
                            }
                        }
                    }
                }
                // The runs whose logs are gone: in Extended mode the account's own transcript still has their turns.
                val gone = synchronized(e) { if (e.recordWindow != null) emptyList() else pending.filter { it.id in e.expiredRuns && (it.id !in e.traces || it.id in e.staleTraces) } }
                if (gone.isNotEmpty()) fillFromRecord(e, agentId, gone, tokens)
                // And the other way round: on the record path, a turn the record gave without its text whose log
                // this pass found gone takes its reply from the documented transcript (see [fillTextFromTranscript]).
                if (synchronized(e) { e.recordWindow != null }) fillTextFromTranscript(e, agentId)
            } finally {
                // Runs this pass did not get to (paused half-way) wait on the queue for the next one — unless a pause
                // already put them there and moved on (see [pause]). What the pass found out about the runs that
                // brought no trace — expired, failed — is published with the items as they are.
                e.publish(mutate = { if (traceJob === self) requeueInFlight() })
            }
        }
    }

    /**
     * Fills the traces of [runs] — finished runs whose documented log has expired — from the account's transcript
     * (Extended mode): the record's turns pair with the chat's runs by position, oldest first, and a turn is taken
     * only when the prompt it starts with is the one the transcript pairs with the run, so a record that counts its
     * turns differently never dresses a run in another turn's work. What lands is kept on disk like a replayed trace.
     */
    private suspend fun fillFromRecord(e: Entry, agentId: String, runs: List<RunDto>, tokens: CacheTokens) {
        val api = record ?: return
        if (session.isDemo || !capabilities().accountTranscript) return
        // The record refused a moment ago: not asked again for the pause it named (see [Entry.recordRefusedUntil]).
        if (AppClock.now() < synchronized(e) { e.recordRefusedUntil }) return
        // The record is the transcript already (see [recordWindow]): what it has of these turns is on screen.
        if (synchronized(e) { e.recordWindow != null }) return
        val (ordered, total, prompts) = synchronized(e) {
            val listed = e.runs.sortedBy { parseIsoMillis(it.createdAt) }
            val prompts = e.messages.filter { it.type == USER_MESSAGE }.map { it.text.trim() }
            Triple(listed, if (e.runsComplete) listed.size else maxOf(prompts.size, listed.size), prompts)
        }
        val wanted = runs.mapNotNull { run -> ordered.indexOfFirst { it.id == run.id }.takeIf { it >= 0 }?.let { run to it } }
        if (wanted.isEmpty()) return
        val unfetched = total - ordered.size
        val oldest = wanted.minOf { (_, index) -> unfetched + index }
        val turns = runCatching { HeadlessTranscript.tailTurns(api, agentId, count = total - oldest) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return
        if (turns.isEmpty()) return
        // The record carries the mode a prompt was sent in; a Project's coordinator is told from that alone.
        if (turns.any { it.projectMode } && synchronized(e) { !e.projectMode }) {
            e.publish(mutate = { projectMode = true }, transform = { copy(isProjectConversation = true) })
        }
        val sink = images?.forAgent(agentId)
        for ((run, index) in wanted) {
            val position = unfetched + index
            // The record's last turn is the chat's last run; count back from there.
            val turn = turns.getOrNull(turns.size - (total - position)) ?: continue
            val prompt = prompts.getOrNull(position)
            if (prompt != null && turn.prompt != null && prompt != turn.prompt.trim()) continue
            val items = HeadlessTranscript.trace(turn, run, sink)
            if (items.none { it !is RunFooter }) continue
            val trace = settleTrace(e, run, items)
            withContext(NonCancellable) { writeTraces(agentId, listOf(trace), tokens) }
        }
    }

    /**
     * The record path's last resort for a turn's words: a turn the record gives without the agent's text — no body
     * at all, or its calls without the reply — whose run's log is out of reach (expired, unreadable this session, or
     * no run to replay) takes its reply from the documented `/v0` transcript, which carries every turn's prompt and
     * text however old. The transcript is read once for the chat (kept in [Entry.messages]) and only when such a
     * turn exists; a turn is paired with its `/v0` prompt by the prompt's text, in order, and takes the assistant
     * messages that follow it up to the next prompt — the same turn's words from the other source, never another's
     * (see `RecordTurn.textFromTranscript`). Bennett's 2026-09-20 report: regular chats "empty except some tool
     * calls here and there" — the record's calls on screen, the reply nowhere, although `/v0` had it all along.
     */
    private suspend fun fillTextFromTranscript(e: Entry, agentId: String) {
        if (session.isDemo) return
        val (window, wanting) = synchronized(e) {
            val window = e.recordWindow ?: return
            val ordered = e.allRuns()
            val trailing = with(e) { local.filter { window.turnOf(it) == null } }.mapTo(HashSet()) { it.run.id }
            val paired = ordered.filter { it.id !in trailing }
            val offset = paired.size - window.turns.size
            val chatRunning = e.isChatRunning()
            window to window.turns.withIndex().filter { (i, turn) ->
                if (turn.prompt == null || turn.hasText || turn.textFromTranscript) return@filter false
                // The newest turn while the chat runs is being written: its words are on their way.
                if (i == window.turns.lastIndex && chatRunning) return@filter false
                val run = paired.getOrNull(offset + i)
                when {
                    run == null -> e.runsComplete || e.olderRunsCursor == null
                    run.id in e.traces -> false
                    run.statusEnum().isActive -> false
                    run.id in e.expiredRuns || parseIsoMillis(run.createdAt) < e.expiredBefore || run.id in e.failedTraces -> true
                    else -> false
                }
            }.map { it.value }
        }
        if (wanting.isEmpty()) return
        val messages = synchronized(e) { e.messages }.takeIf { it.isNotEmpty() }
            ?: runCatching { net(agentId, "transcript"); session.current.api.conversationV0(agentId).messages }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            ?: return
        synchronized(e) { if (e.messages.isEmpty()) e.messages = messages }
        // The transcript's turns: each prompt with the replies up to the next.
        val turns = ArrayList<Pair<String, List<V0ConversationMessageDto>>>()
        var prompt: String? = null
        var replies = ArrayList<V0ConversationMessageDto>()
        for (message in messages) {
            if (message.type == USER_MESSAGE) {
                prompt?.let { turns += it to replies }
                prompt = message.text
                replies = ArrayList()
            } else if (prompt != null) {
                replies += message
            }
        }
        prompt?.let { turns += it to replies }
        // Paired in order: a prompt sent twice pairs with its own copy, not the first.
        val replacements = HashMap<Int, RecordTurn>()
        var from = 0
        for (turn in wanting) {
            val wanted = normalizePrompt(turn.prompt!!)
            val at = (from until turns.size).firstOrNull { normalizePrompt(turns[it].first) == wanted } ?: continue
            from = at + 1
            val text = turns[at].second.filter { it.text.isNotBlank() }
            if (text.isEmpty()) continue
            replacements[turn.stepIndex] = turn.withTranscriptText(text.mapIndexed { i, m -> AssistantMessage("rec-text-${turn.stepIndex}-$i", m.text) })
        }
        if (replacements.isEmpty()) return
        var filled: RecordWindow? = null
        e.publish(mutate = {
            // Only onto the window the turns were read from: a re-read since has its own turns, and reads again.
            if (recordWindow === window) { recordWindow = window.withTurns(replacements); filled = recordWindow }
        })
        filled?.let { persistRecord(e, it, window, session.current, cacheTokens()) }
    }

    /** What a cut-short pass was working on goes back on the queue for the next one. Under the entry's monitor. */
    private fun Entry.requeueInFlight() {
        traceInFlight.values.filter { (it.id !in traces || it.id in staleTraces) && it.id !in expiredRuns }.forEach { traceQueue.putIfAbsent(it.id, it) }
        traceInFlight.clear()
    }

    /**
     * A trace off the disk that this build would read more from than the build that wrote it kept: a coordinator's
     * message without its body, a goal set without its objective. Worth asking the run's log for again.
     */
    private fun isStaleTrace(items: List<TimelineItem>): Boolean = CoordinatorTranscript.needsRefresh(items) || GoalTranscript.needsRefresh(items)

    /** Shows a run's complete trace and hands it to the caller for the file. */
    private fun settleTrace(e: Entry, run: RunDto, items: List<TimelineItem>): CachedTrace {
        // A pass settles runs one after another: they land on screen together rather than one recomposition each.
        e.publishCoalesced {
            traces = traces + (run.id to items)
            staleTraces -= run.id
            if (run.id in partial) partial = partial - run.id
        }
        return CachedTrace(run.id, parseIsoMillis(run.createdAt), items)
    }

    /**
     * Follows [run]: its stream's snapshots become the chat's live turn (see [Entry.applyLive]). [unlessMovedOn] is
     * for a run a load read as the chat's latest: it is followed only while it still is — a follow-up sent from here
     * while the load was in flight is the newer run, already followed, and a load landing after it must not put the
     * chat back on the turn it had moved past (which showed a cancelled run, not streaming, over a follow-up under way).
     */
    private fun startStreaming(e: Entry, agentId: String, run: RunDto, unlessMovedOn: Boolean = false) {
        // Started only once it is the entry's follower, so not even its first snapshot can be taken for a stale one.
        val job = e.scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    if (!e.applyLive(self, run, snapshot)) return@collect
                    if (snapshot.finished) {
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        val finishedAt = snapshot.finishedAtMillis ?: AppClock.now()
                        prefs.markRead(agentId, finishedAt)
                        recordFinish(e, run, snapshot, finishedAt)
                        // The account may still call the chat running — another run named already, or the row running
                        // on (a steer's next run on its way, a record a poll behind its own stream): the next run is
                        // found and followed, and until it is the chat stays running, not dead (see [keepFollowing]).
                        if (run.id != synchronized(e) { e.cancelledRunId }) keepFollowing(e, endedRunId = run.id)
                        // An outcome read from the run record after the stream broke lacks whatever happened in
                        // between; now that the run is over, its retained log is read whole — or, from the record,
                        // the record's tail, which has the finished turn.
                        if (!snapshot.hasTrace && !snapshot.expired) {
                            if (synchronized(e) { e.recordWindow != null }) revalidateNow(e, force = true) else loadTraces(e, agentId, listOf(run))
                        }
                    }
                }
        }
        val following = synchronized(e) {
            // The chat has moved past this run since the load read it: the follow it has stands.
            if (unlessMovedOn && e.latestRun()?.id != run.id) return@synchronized false
            e.streamJob?.cancel()
            // The last screen may have left, or the one still attached been covered, while the load that got here
            // was wrapping up: then nobody is looking, and the next attach or resume decides afresh.
            if (e.attached == 0 || e.paused) return@synchronized false
            e.publish(
                mutate = {
                    streamJob = job
                    // The story so far of the run followed until now stays on screen: the same run's until its new
                    // follower's first snapshot replaces it (a load re-following the run used to blank its items
                    // for a frame), another run's as its stand-in until its whole trace lands (see [Entry.partial]).
                    if (live?.runId != run.id) { keepStory(); live = null }
                },
                transform = { copy(activeRunId = run.id, runStatus = e.statusOf(run), isStreaming = true) },
            )
            true
        }
        if (following) job.start() else job.cancel()
    }

    /**
     * Applies a snapshot of the run [follower] is streaming: the story so far while it runs, and once it has
     * finished on its own `result` the complete trace, filed with the replayed ones (the builder places either after
     * the run's prompt). An outcome read from the run record after the stream broke stays the story so far: it has
     * the final reply and the footer to show, but not everything in between, so it never passes for the trace. A
     * snapshot from a job that is no longer the follower — its cancel raced the emission — is dropped (false).
     *
     * Until the stream has said anything, the run record's status stands rather than the snapshot's default: a fresh
     * run is still CREATING ("Starting…") while its VM boots, not RUNNING because a connection was opened. A snapshot
     * taken between two connections marks the state as reconnecting.
     */
    private fun Entry.applyLive(follower: Job?, run: RunDto, snapshot: LiveRunHub.Snapshot): Boolean = synchronized(this) {
        if (streamJob !== follower) return false
        val mutate: Entry.() -> Unit = {
            if (snapshot.hasTrace) {
                traces = traces + (run.id to snapshot.items)
                if (run.id in partial) partial = partial - run.id
                live = null
            } else {
                live = LiveTrace(run.id, storySoFar(run.id, snapshot))
                if (run.id in partial) partial = partial - run.id
            }
        }
        if (!snapshot.finished && snapshot.eventCount > 0 && state.value.isStreaming && state.value.isReconnecting == snapshot.reconnecting && (state.value.runStatus == snapshot.status || snapshot.eventCount > 1)) {
            // A delta of a run already shown as streaming: the items alone changed, and a burst of them is one publication.
            publishCoalesced(mutate)
            return true
        }
        publish(
            mutate = mutate,
            transform = {
                copy(
                    // A finished stream's word is the run's; the chat's is the freshest there is (see [chatStatus]):
                    // a run the account still calls running — a `/v1` record that says cancelled while the account
                    // runs on, or a steer that ended this run for the next — is not the chat's end.
                    runStatus = when {
                        // The stream's terminal word is the run's. The chat still runs while the account calls it so
                        // — another run named already, or the row running on (a steer's next run on its way, a
                        // record a poll behind) — and [keepFollowing] finds the run to follow; this device's own
                        // cancel is the one word that ends the chat at once.
                        snapshot.finished -> if (run.id != cancelledRunId && (accountNamesNewerRun(run.id) || rowSaysRunning())) RunStatus.RUNNING else snapshot.status
                        snapshot.eventCount > 0 -> snapshot.status
                        else -> runStatus
                    },
                    isStreaming = !snapshot.finished,
                    isReconnecting = snapshot.reconnecting && !snapshot.finished,
                )
            },
        )
        true
    }

    /**
     * The run's story as [snapshot] leaves it, never shorter than the story already shown for the run while the
     * snapshot is not the whole trace: a stream rebuilt from the run's first event after its resume position was
     * refused, published before it has caught up, or settled off the run record with the rebuild half done, hands
     * over fewer items than the screen already had — a fragment of what was streamed, which drawn as it came took
     * the turn's tool calls and text off the screen. The rebuild's items are the same story from its start (the
     * accumulator names them the same way), so the longer of the two is the story; a snapshot that ends the run
     * without the whole story keeps the story shown and takes the end from the snapshot — its footer, and a final
     * reply the story did not have. The whole trace ([LiveRunHub.Snapshot.hasTrace]) is never questioned.
     */
    private fun Entry.storySoFar(runId: String, snapshot: LiveRunHub.Snapshot): List<TimelineItem> {
        val incoming = snapshot.items
        val shown = live?.takeIf { it.runId == runId }?.items ?: partial[runId] ?: return incoming
        if (storySize(incoming.filterNot { it is RunFooter }) >= storySize(shown.filterNot { it is RunFooter })) return incoming
        if (!snapshot.finished) return shown
        val story = shown.filterNot { it is RunFooter }
        val ending = incoming.filter { it is RunFooter || (it is AssistantMessage && story.none { s -> s is AssistantMessage && s.markdown == it.markdown }) }
        return story + ending
    }

    /**
     * A run the hub followed live has ended. Whether or not a screen is (still) streaming it, its outcome is folded
     * into the transcript and its trace — when the stream told the whole story — is kept, so the chat is complete
     * the next time it is opened, from disk if need be.
     */
    private suspend fun onRunFinished(snapshot: LiveRunHub.Snapshot) {
        val e = synchronized(entries) { entries[snapshot.agentId] }
        val run = e?.let { synchronized(it) { it.runById(snapshot.runId) } }
        when {
            e != null && run != null -> recordFinish(e, run, snapshot, snapshot.finishedAtMillis ?: AppClock.now())
            snapshot.hasTrace -> {
                // Nothing in memory places this run yet (never opened here, or started elsewhere): the trace is kept
                // on its own, and the next load fetches the history it belongs to.
                e?.publish(mutate = { traces = traces + (snapshot.runId to snapshot.items) })
                writeTrace(snapshot.agentId, snapshot.runId, snapshot.startedAtMillis, snapshot.items)
            }
        }
    }

    /**
     * Records a run that finished while this device was connected: the outcome goes into the inputs and, when the
     * stream told the whole story, the trace is filed and kept on disk. The screen's own follower and the hub's
     * report both arrive here for the same run; it is recorded once.
     */
    private suspend fun recordFinish(e: Entry, run: RunDto, snapshot: LiveRunHub.Snapshot, finishedAt: Long) {
        val first = synchronized(e) { e.recordedFinishes.add(run.id) }
        if (!first) return
        var followed = false
        e.publish(
            mutate = {
                recordFinishedRun(run, snapshot, finishedAt)
                if (snapshot.hasTrace) {
                    traces = traces + (run.id to snapshot.items)
                    if (run.id in partial) partial = partial - run.id
                }
                followed = live?.runId == run.id
            },
            // With no screen following it, the status would otherwise stay at the last thing the screen saw. The
            // run's end is the run's; the chat's status is the freshest word there is (see [Entry.chatStatus]): an
            // account running on past this run keeps the chat running. And this frame — the finished run's footer
            // in it — is the frame the stream's word on the run ends in, whichever of the hub's two listeners (this
            // one, or the follow's own, see [startStreaming]) publishes first: a run is never shown ended with its
            // stream on (main's CI at `adcd808` caught the frame once). The follow's job settles itself right after.
            transform = {
                if (activeRunId != run.id) this
                else copy(
                    runStatus = if (run.id != e.cancelledRunId && (e.accountNamesNewerRun(run.id) || e.rowSaysRunning())) RunStatus.RUNNING else snapshot.status,
                    isStreaming = isStreaming && !(followed && snapshot.finished),
                    isReconnecting = isReconnecting && !(followed && snapshot.finished),
                )
            },
        )
        persist(e, session.current)
        if (snapshot.hasTrace) writeTrace(e.agentId, run.id, parseIsoMillis(run.createdAt, snapshot.startedAtMillis), snapshot.items)
    }

    /**
     * Folds a run that finished while we watched into the inputs, the way the server will report it: the run itself
     * as terminal and its final reply as an assistant message. The disk copy renders it on the next open before the
     * hub has replayed the trace; in memory the trace stands in for the text, so nothing shows twice. The reply goes
     * wherever this run's message is shown from — the local prompt while it stands in, else the transcript.
     */
    private fun Entry.recordFinishedRun(run: RunDto, snapshot: LiveRunHub.Snapshot, finishedAt: Long) {
        val result = snapshot.result
        val text = result?.text?.trim().orEmpty()
        val terminal = run.copy(
            status = snapshot.status.name,
            updatedAt = Instant.ofEpochMilli(finishedAt).toString(),
            // What the run reports of itself, or nothing: how long this device happened to watch is not the run's
            // duration, and read as one ("Cancelled after 30s") it made a stale record look like the turn's end.
            durationMs = result?.durationMs ?: run.durationMs,
            result = text.ifEmpty { run.result },
            git = result?.git ?: run.git,
        )
        val reply = text.takeIf { it.isNotEmpty() }?.let { V0ConversationMessageDto("res-${run.id}", ASSISTANT_MESSAGE, it) }
        runs = runs.map { if (it.id == run.id) terminal else it }
        local = local.map { if (it.run.id == run.id) it.copy(run = terminal, reply = reply) else it }
        // The transcript may hold the reply already — a load that read it before the stream's end came through
        // (the same words under the transcript's own id): the run's copy is not added on top of it.
        val transcriptHasIt = reply != null && messages.lastOrNull { it.type == ASSISTANT_MESSAGE }?.text?.trim() == text
        if (!standsIn(run.id) && reply != null && messages.none { it.id == reply.id } && !transcriptHasIt) messages = messages + reply
        inputsUpdatedAt = maxOf(inputsUpdatedAt, finishedAt)
    }

    /**
     * Files the follow-up and starts streaming its run. [modelId] (with [modelParams] and the [modelDisplayName] it is
     * shown under) switches the chat to another model from this run on; [planMode] asks for plan or agent mode
     * explicitly. Both null keep the chat as it is — see [AgentRepository.followUp].
     */
    suspend fun sendFollowUp(
        agentId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
        mcpServers: List<McpServer> = emptyList(),
        planMode: Boolean? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        /**
         * Show the prompt in the transcript before the server has answered (the composer's send). A queued message
         * passes false: its card above the composer is what the reader sees until the server accepts it, so a
         * refusal does not flash a bubble on and off (see `FollowUpRepository`).
         */
        showEcho: Boolean = true,
    ): Result<RunDto> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val staged = stageFollowUp(agentId, text, images, show = showEcho)
        return sendStaged(agentId, staged, images, mcpServers, planMode, modelId, modelParams, modelDisplayName)
            .onFailure { t ->
                // Refused as busy, the message is not lost: the caller queues it for the end of the turn. That is
                // nothing for the chat to show as an error. Nor is any failure of a message whose bubble was never
                // shown (a queued one): its card carries the reason, with a retry.
                discardStaged(agentId, staged, t.userMessage().takeUnless { !showEcho || t.toCursorError()?.code == AGENT_BUSY })
            }
    }

    /**
     * Whether [text] is the newest prompt the server holds for this chat, filed no earlier than [sinceMillis]. This
     * is what a follow-up whose send the app did not live to see the end of has to ask before it goes out again: the
     * runs API takes no idempotency key, so nothing else can tell a message that arrived from one that did not.
     *
     * A follow-up the server accepted is, by construction, the last prompt of the chat and the newest run of it. The
     * run's stamp is the server's and [sinceMillis] this device's, so the two are compared with room for the clocks
     * disagreeing — being a little generous costs a duplicate that would not have been sent, never a message shown
     * as sent that was not.
     *
     * A failure is not an answer of no: the caller has to tell "it did not arrive" from "nobody could say".
     */
    suspend fun wasSentSince(agentId: String, text: String, sinceMillis: Long): Result<Boolean> = sentRunSince(agentId, text, sinceMillis).map { it != null }

    /**
     * [wasSentSince] with the run itself: the chat's newest run when the newest prompt the server holds is [text],
     * filed no earlier than [sinceMillis]; null when the server does not have the message. What a send whose reply
     * was lost files the message under, so the chat shows it sent and follows it rather than sending it again.
     */
    suspend fun sentRunSince(agentId: String, text: String, sinceMillis: Long): Result<RunDto?> = runCatching {
        val api = session.current.api
        coroutineScope {
            val conversation = async { api.conversationV0(agentId) }
            val runs = async { api.listRuns(agentId, limit = FIRST_RUN_PAGE) }
            val newest = conversation.await().messages.lastOrNull { it.type == USER_MESSAGE }?.text?.trim()
            val newestRun = runs.await().items.maxByOrNull { parseIsoMillis(it.createdAt) }
            newestRun?.takeIf { newest == text.trim() && parseIsoMillis(it.createdAt) >= sinceMillis - CLOCK_SKEW_ALLOWANCE_MS }
        }
    }

    /**
     * Shows a follow-up in the transcript before its request goes out — as a pending bubble, paired with a placeholder
     * run so it is built like every other turn (ordered by the run's stamp, its images keyed by the run) until the
     * server's run takes its place. [sendStaged] sends it; [discardStaged] takes it down again. Split from
     * [sendFollowUp] so a queued message that is steered can be on screen while the turn it interrupts is still being
     * cancelled.
     */
    suspend fun stageFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList(), files: List<PromptFile> = emptyList(), show: Boolean = true): StagedFollowUp {
        val e = entry(agentId)
        val trimmed = text.trim()
        val now = AppClock.now()
        // Two prompts staged in the same millisecond (a burst from the queue) must not share an id.
        val localId = synchronized(e) { "$LOCAL_RUN_PREFIX${maxOf(now, e.lastLocalStamp + 1).also { e.lastLocalStamp = it }}" }
        val placeholder = e.placeholderRun(localId, now)
        // Written before the request so the bubble shows its images and files from the first frame, like the text.
        // Storage trouble costs the previews, never the send.
        val staged = runCatching { attachments.stage(images, files) }.getOrDefault(StagedAttachments.EMPTY)
        val message = V0ConversationMessageDto(localId, USER_MESSAGE, trimmed)
        if (show) {
            e.publish(
                mutate = {
                    local = local + LocalPrompt(message, placeholder)
                    if (staged.attachments.isNotEmpty()) promptImages = promptImages + (localId to staged.attachments)
                },
                transform = { copy(error = null) },
            )
        }
        return StagedFollowUp(localId, trimmed, staged, now, shown = show, message = message, placeholder = placeholder)
    }

    /**
     * Sends a [stageFollowUp]ed prompt. On success the server's run takes the placeholder's place — the bubble is no
     * longer pending — and its stream starts. On failure the bubble is left as it is, pending, for the caller to try
     * again or [discardStaged].
     *
     * A reply that never came back — the connection reset under the request, the reply cut before its status line,
     * silence past the read timeout — says nothing about whether the server took the message, and the runs API has
     * no idempotency key to ask with. So the server is asked in the one way there is ([sentRunSince]): a message it
     * holds is filed under the run it started, shown as sent and followed, never sent again; one it does not hold
     * goes out once more, once; and when nobody can say — the question itself failed — the failure stands for the
     * caller, which for a queued message flags it rather than sending it. A network handoff mid-send used to send
     * the message twice (OkHttp's own retry of the request, or the queue's after a `409 agent_busy` for the run the
     * first attempt had started); see `OneShotWritesInterceptor`.
     */
    suspend fun sendStaged(
        agentId: String,
        staged: StagedFollowUp,
        images: List<PromptImage> = emptyList(),
        mcpServers: List<McpServer> = emptyList(),
        planMode: Boolean? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
    ): Result<RunDto> {
        val e = entry(agentId)
        var attempts = 0
        while (true) {
            attempts++
            val result = agents.followUp(
                agentId,
                staged.text,
                images,
                planMode = planMode,
                mcpServers = mcpServers,
                modelId = modelId,
                modelParams = modelParams,
                modelDisplayName = modelDisplayName,
            )
            val t = result.exceptionOrNull() ?: return result.map { run -> accepted(e, agentId, staged, run); run }
            if (t is CancellationException) throw t
            if (!t.isLostReply()) return result
            val filed = sentRunSince(agentId, staged.text, staged.stagedAt)
            val run = filed.getOrNull()
            when {
                run != null -> {
                    agents.noteFollowUp(agentId, run, modelId, modelParams, modelDisplayName)
                    accepted(e, agentId, staged, run)
                    return Result.success(run)
                }
                filed.isFailure || attempts >= LOST_REPLY_ATTEMPTS -> return result
            }
        }
    }

    /**
     * A message the account took into its queue behind the turn under way (`AddAsyncFollowupBackgroundComposer` with
     * `synchronous: false`, Extended mode): the card above the composer is what the reader sees of it until the
     * account delivers it. Staged here, not shown, and filed under the run the account starts on it the moment that
     * run is seen (see [adoptDelivered]) — or, when the account promoted it into the turn under way instead, among
     * that run's rows where it arrived — so the message moves from the card into the transcript, never out of both.
     * Bennett's 2026-09-20 report: "it is failing entirely to show my new messages as we speak" — a message queued
     * on the account, delivered as the next turn, and at home nowhere: the next-run look that followed the new run
     * read no transcript, and the transcript's positional pairing would have put it under a turn from an hour before.
     */
    suspend fun expectDelivery(agentId: String, text: String, images: List<PromptImage> = emptyList(), files: List<PromptFile> = emptyList(), followupId: String? = null): StagedFollowUp {
        val staged = stageFollowUp(agentId, text, images, files, show = false)
        awaitDelivery(agentId, staged, followupId)
        return staged
    }

    /**
     * Keeps [staged] — a prompt the account has queued under [followupId], when the send minted one — until the run
     * it is delivered on is seen (see [expectDelivery]). A bubble the composer showed ahead of the request comes down
     * in the same frame: the card is the one place a queued message is, and its staged attachments wait with it, to be
     * filed under the run with it (see [file]).
     */
    private fun awaitDelivery(agentId: String, staged: StagedFollowUp, followupId: String?) {
        val e = entry(agentId)
        // Published: the card shows the message from this frame on, before the account's list has been read again.
        e.publish(mutate = {
            // How many prompts with these words the transcript shows besides the bubble coming down: the bubble is
            // this message, not a prior copy of it.
            val key = QueuePlacement.textKey(staged.text)
            val prior = state.value.items.count { it is UserMessage && it.id != staged.localId && QueuePlacement.textKey(it.text) == key }
            val priorTranscript = messages.count { it.type == USER_MESSAGE && QueuePlacement.textKey(it.text) == key }
            if (staged.shown) {
                local = local.filterNot { it.run.id == staged.localId }
                promptImages = promptImages - staged.localId
            }
            val behind = latestRun()?.id?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
            awaiting = awaiting + Awaiting(staged, behind, AppClock.now(), followupId = followupId, priorCopies = prior, priorTranscriptCopies = priorTranscript)
        })
    }

    /**
     * Where the messages the account queued stand, for the card above the composer (see [QueuePlacement]): the same
     * word [ConversationState.queuePlacement] carries, as a flow of its own for the queue's reader to watch — a
     * delivery is when the account's queue is worth reading again.
     */
    fun queuePlacement(agentId: String): StateFlow<QueuePlacement> = placements.getOrPut(agentId) {
        val source = state(agentId)
        source.map { it.queuePlacement }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, source.value.queuePlacement)
    }

    private val placements = ConcurrentHashMap<String, StateFlow<QueuePlacement>>()

    /**
     * The account's queue for the chat as read at [readAtMillis] (`ListPendingFollowups`, Extended mode): [pending]
     * are the messages still waiting, by the account's followup id and their words.
     *
     * A message staged here for delivery ([expectDelivery]) that the queue no longer lists has been delivered — as
     * the next turn, or promoted into the turn under way — and is filed where it landed (see [adoptDelivered]); one
     * still listed notes the newest run known meanwhile as the run it waits behind. A message filed already
     * ([Delivered]) that the queue no longer lists is confirmed: the account has let it go, and the card had left it
     * out from the moment it was filed. One the queue still lists after the run it was filed under has ended — by a
     * read begun once the end had been seen for a moment, so a read from before the end says nothing — was not
     * carried by that run after all: it comes out of the transcript and goes back on the card, said so, to wait for
     * the run the account does start on it. A read from before the run started (the poll is seconds behind) still
     * listing a message filed a moment ago is the ordinary case, and changes nothing.
     */
    fun noteAccountQueue(agentId: String, pending: List<PendingFollowup>, readAtMillis: Long = AppClock.now()) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        val listedIds = pending.mapTo(HashSet()) { it.id }
        val listedTexts = pending.mapTo(HashSet()) { normalizePrompt(it.text) }
        fun listed(followupId: String?, text: String): Boolean = if (followupId != null) followupId in listedIds else normalizePrompt(text) in listedTexts
        var adopt = false
        var changed = false
        val putBack = ArrayList<Delivered>()
        synchronized(e) {
            if (e.awaiting.isEmpty() && e.delivered.isEmpty() && e.returned.isEmpty()) return
            if (e.awaiting.isNotEmpty()) {
                val ordered = allServerRuns(e)
                e.awaiting = e.awaiting.map { a ->
                    val queued = listed(a.followupId, a.staged.text)
                    when {
                        queued && a.queuedOnAccount -> {
                            // The run it waits behind moves up with the runs the account has started since — on the
                            // messages listed ahead of it, in order, one run each — and never onto its own: the account
                            // keeps listing a message for a moment after starting its run (the read is seconds behind),
                            // and a message taken for one waiting behind its own run would be filed into that run as a
                            // steer, after the run's first rows, rather than ahead of it as the prompt that started it.
                            val ahead = pending.indexOfFirst { p -> if (a.followupId != null) p.id == a.followupId else normalizePrompt(p.text) == normalizePrompt(a.staged.text) }.coerceAtLeast(0)
                            val waited = ordered.firstOrNull { it.id == a.behindRunId }
                            val since = ordered.filter { isNewer(it, waited) && it.id != a.behindRunId }
                            val behind = since.take(ahead).lastOrNull()?.id ?: a.behindRunId
                            if (a.behindRunId == behind) a else a.copy(behindRunId = behind)
                        }
                        !queued && a.queuedOnAccount -> { adopt = true; changed = true; a.copy(queuedOnAccount = false) }
                        else -> a
                    }
                }
            }
            if (e.delivered.isNotEmpty()) {
                val now = AppClock.now()
                e.delivered = e.delivered.filter { d ->
                    if (!listed(d.followupId, d.staged.text)) { changed = true; return@filter false }
                    val run = e.runs.firstOrNull { it.id == d.runId }
                    val over = run != null && !run.statusEnum().isActive
                    if (!over) { d.endedSeenAt = null; return@filter true }
                    val endedSeenAt = d.endedSeenAt ?: now.also { d.endedSeenAt = it }
                    if (readAtMillis < endedSeenAt + PUT_BACK_SLACK_MS) return@filter true
                    // The server's transcript still holds the copy the message was filed off: the message is in the
                    // conversation, and the account's list is the one behind. Nothing to put back.
                    val key0 = QueuePlacement.textKey(d.staged.text)
                    if (e.messages.count { it.type == USER_MESSAGE && QueuePlacement.textKey(it.text) == key0 } > d.priorTranscriptCopies) return@filter true
                    // The run ended, the account still has the message waiting, and the transcript no longer shows
                    // it under that run: it was never that run's.
                    putBack += d
                    changed = true
                    e.local = e.local.filterNot { it.run.id == d.runId && it.message.id == d.localMessageId }
                    e.promptImages = e.promptImages - (if (d.steered) d.localMessageId else d.runId)
                    val key = QueuePlacement.textKey(d.staged.text)
                    val prior = e.state.value.items.count { it is UserMessage && QueuePlacement.textKey(it.text) == key } - 1
                    val priorTranscript = e.messages.count { it.type == USER_MESSAGE && QueuePlacement.textKey(it.text) == key }
                    e.awaiting = e.awaiting + Awaiting(d.staged.withAttachments(attachments.committed(agentId, d.runId, d.images)), d.runId, now, queuedOnAccount = true, followupId = d.followupId, priorCopies = prior.coerceAtLeast(0), priorTranscriptCopies = priorTranscript)
                    d.followupId?.let { e.returned = e.returned + (it to QueuePlacement.RETURNED_NOTE) }
                    false
                }
            }
            // A put-back message the account no longer lists went out or was taken back from another client: the word goes with it.
            if (e.returned.isNotEmpty()) {
                val kept = e.returned.filterKeys { it in listedIds || e.awaiting.any { a -> a.followupId == it } }
                if (kept.size != e.returned.size) { e.returned = kept; changed = true }
            }
        }
        if (changed) e.publish()
        if (putBack.isNotEmpty()) e.scope.launch { persist(e, session.current) }
        if (adopt) e.scope.launch { adoptDelivered(e) }
    }


    /**
     * Files the messages the account has delivered from its queue (see [expectDelivery]) under the runs they landed
     * in. Asked whenever the chat's runs may have grown — a load, a next-run look, a refresh of the run list — and
     * when a read of the account's queue no longer lists a message. A message that has nothing new to be filed
     * under and is still queued as far as the last read of the queue said waits.
     *
     * Where a message landed is read off the server, never guessed from a clock: the run list's newest page and the
     * transcript, once each. The transcript's newest copy of the message, counted from its end in prompts, names
     * the run — the newest run for the newest prompt, the one before for a prompt with one after it (a worker's
     * report that came in behind it) — as far as that run is newer than the run the message waited behind; a copy
     * whose count reaches no newer run was delivered into the run under way (the account promoted it): it is filed
     * among that run's rows after the story streamed so far. A message the transcript does not hold yet is asked
     * for again a few times, a moment apart (the account files it a beat after it starts the run); one the queue
     * has dropped that the transcript never shows was taken back from another client, and is let go — nothing
     * is ever shown that the server does not hold.
     */
    private suspend fun adoptDelivered(e: Entry, attempt: Int = 0) {
        val agentId = e.agentId
        val due = synchronized(e) {
            val ordered = allServerRuns(e)
            e.awaiting.filter { a -> !a.queuedOnAccount || a.behindRunId == null || ordered.any { it.id != a.behindRunId && isNewer(it, ordered.firstOrNull { r -> r.id == a.behindRunId }) } }
        }
        if (due.isEmpty()) return
        val api = session.current.api
        val (page, transcript) = coroutineScope {
            val runs = async { runCatching { net(agentId, "runs"); api.listRuns(agentId, limit = FIRST_RUN_PAGE) }.getOrNull() }
            val conversation = async { runCatching { net(agentId, "transcript"); api.conversationV0(agentId).messages }.getOrNull() }
            runs.await() to conversation.await()
        }
        if (transcript == null) return
        val prompts = transcript.filter { it.type == USER_MESSAGE }
        var retry = false
        val filed = ArrayList<Pair<Awaiting, LocalPrompt>>()
        val dropped = ArrayList<StagedFollowUp>()
        e.publish(mutate = {
            if (page != null) {
                val fresh = page.items.associateBy { it.id }
                val known = runs.mapTo(HashSet()) { it.id }
                runs = page.items.filter { it.id !in known } + runs.map { fresh[it.id] ?: it }
            }
            // The transcript read now is the fresher copy: the load's may predate the delivery.
            if (transcript.size >= messages.size) messages = transcript
            val ordered = allServerRuns(this)
            val taken = HashSet<String>()
            for (a in due) {
                if (a !in awaiting) continue
                val wanted = normalizePrompt(a.staged.text)
                // The transcript's newest copy of the words — one beyond the copies it held when the message was
                // queued: the same words sent before are an earlier turn's, and never this message's home.
                val copies = prompts.indices.filter { normalizePrompt(prompts[it].text) == wanted }
                val at = copies.lastOrNull()?.takeIf { copies.size > a.priorTranscriptCopies }
                if (at == null) {
                    // Not in the transcript yet — or no longer anywhere: gone from the queue and never filed.
                    if (attempt < ADOPT_ATTEMPTS) retry = true else if (!a.queuedOnAccount) { awaiting = awaiting - a; dropped += a.staged }
                    continue
                }
                val after = prompts.size - 1 - at
                val behind = ordered.firstOrNull { it.id == a.behindRunId }
                val newest = ordered.asReversed().filter { it.id !in taken && local.none { p -> p.run.id == it.id && p.steeredAfter == null } }
                val run = newest.getOrNull(after)?.takeIf { behind == null || isNewer(it, behind) }
                    ?: newest.lastOrNull { behind == null || isNewer(it, behind) }
                val prompt = if (run != null) {
                    taken += run.id
                    LocalPrompt(a.staged.message, run)
                } else {
                    // Delivered into the turn under way: after what the run had streamed by now.
                    val into = behind ?: ordered.lastOrNull() ?: continue
                    val story = live?.takeIf { it.runId == into.id }?.items ?: partial[into.id] ?: traces[into.id]
                    LocalPrompt(a.staged.message, into, steeredAfter = story?.size ?: 0)
                }
                filed += a to prompt
                awaiting = awaiting - a
                // Marked delivered in this very frame — the frame that shows the transcript's copy of the message,
                // the `/v0` read just merged — so the card leaves it out from here on, not from the filing's frame.
                delivered = delivered + Delivered(a.staged, a.followupId, prompt.message.id, prompt.run.id, steered = prompt.steeredAfter != null, filedAt = AppClock.now(), images = a.staged.attachments.attachments, priorTranscriptCopies = a.priorTranscriptCopies, priorCopies = a.priorCopies)
                a.followupId?.let { returned = returned - it }
            }
            pruneLocal()
        })
        for ((a, prompt) in filed) file(e, agentId, a, prompt)
        dropped.forEach { attachments.discard(it.attachments) }
        if (retry) {
            delay(ADOPT_RETRY_MS)
            adoptDelivered(e, attempt + 1)
        }
    }

    /**
     * The account has [prompt]'s message where [prompt] says (see [adoptDelivered]): the staged copy becomes the
     * prompt's echo under its run — its images filed under the run — and the chat is written back. Like [accepted],
     * less the follow: the caller follows the run the account is on. The same frame that shows the echo marks the
     * message delivered ([Delivered]), so the card above the composer leaves it out from that frame on, whatever the
     * last read of the account's queue said (see [ConversationState.queuePlacement]).
     */
    private suspend fun file(e: Entry, agentId: String, a: Awaiting, prompt: LocalPrompt) {
        val staged = a.staged
        val localId = staged.localId
        val kept = runCatching { attachments.commit(agentId, prompt.run.id, staged.attachments) }.getOrDefault(staged.attachments.attachments)
        e.publish(
            mutate = {
                local = local.filterNot { it.run.id == localId } + prompt
                // A prompt that started its run keeps its images under the run, like every prompt; one steered into a
                // run keeps them under its own id, the run's key being its own prompt's.
                val imagesKey = if (prompt.steeredAfter == null) prompt.run.id else prompt.message.id
                promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (imagesKey to kept) }
                inputsUpdatedAt = maxOf(inputsUpdatedAt, staged.stagedAt)
                // Marked delivered by the adoption's own frame (see [adoptDelivered]); its images are where the filing put them now.
                delivered.firstOrNull { it.localMessageId == prompt.message.id && it.runId == prompt.run.id }?.images = kept
                pruneLocal()
            },
        )
        persist(e, session.current)
    }

    /** The server's runs of [e]'s chat, oldest first: never a prompt's placeholder. */
    private fun allServerRuns(e: Entry): List<RunDto> = e.runs.sortedBy { parseIsoMillis(it.createdAt) }

    /**
     * The account said the record read itself is gone — the method removed (`unimplemented`, a `404` for it,
     * "has been removed" in its own words) — rather than refusing this call for now.
     */
    private fun Throwable?.isRecordRemoved(): Boolean {
        val connect = this as? ConnectRpcException ?: return false
        return connect.httpCode == 404 || connect.code == "unimplemented" || connect.message?.contains("has been removed", ignoreCase = true) == true
    }

    private fun isNewer(run: RunDto, than: RunDto?): Boolean = than == null || parseIsoMillis(run.createdAt) > parseIsoMillis(than.createdAt)

    /**
     * Files a follow-up through the account service rather than the documented run request — for a mode the
     * documented API cannot carry — and shows it like [sendFollowUp]. [send] answers with the run the account
     * started; when it names none (the message went into the account's queue behind a turn) the bubble comes down
     * without an error and the chat is reloaded, which is where the message shows up next.
     */
    suspend fun sendFollowUpVia(
        agentId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
        files: List<PromptFile> = emptyList(),
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        followupId: String? = null,
        send: suspend () -> String?,
    ): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val staged = stageFollowUp(agentId, text, images, files)
        return sendStagedVia(agentId, staged, modelId, modelParams, modelDisplayName, followupId, send = send)
    }

    /**
     * [sendFollowUpVia] for a prompt already [stageFollowUp]ed — a queued message that carries files, which only the
     * account's follow-up can take. The bubble comes down with the reason when [send] fails, as it does there —
     * unless [discardOnFailure] is off: the composer's own sends keep the bubble up on a failure, with the reason
     * and a retry on it (see `OutgoingMessages`), and take it down themselves when the reader gives the message up.
     */
    suspend fun sendStagedVia(
        agentId: String,
        staged: StagedFollowUp,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        /** The account's id for the follow-up [send] files (`AccountFollowup.followupId`), by which its card row is known (see [expectDelivery]). */
        followupId: String? = null,
        discardOnFailure: Boolean = true,
        send: suspend () -> String?,
    ): Result<Unit> {
        val e = entry(agentId)
        // The bubble learns the account's id for its message before the request goes out, so the card leaves the
        // account's row out by that id from the first read that could name it (see [Entry.queuePlacement]).
        if (staged.shown && followupId != null) e.publish(mutate = { local = local.map { if (it.run.id == staged.localId) it.copy(followupId = followupId) else it } })
        return agents.followUpVia(agentId, modelId, modelParams, modelDisplayName, send)
            .map { run ->
                if (run != null) {
                    accepted(e, agentId, staged, run)
                } else {
                    // The account queued the message behind a turn under way: the bubble shown ahead comes down —
                    // the card above the composer is what shows a queued message — and the message waits here for
                    // the run the account starts on it (see [expectDelivery]), its attachments with it. The two are
                    // one frame: the bubble down and the card up together, so the message is never out of both.
                    // A turn this device did not know of has the chat read again, for the run the account is on;
                    // one it is following already stands.
                    awaitDelivery(agentId, staged, followupId)
                    if (!synchronized(e) { e.isChatRunning() }) reload(agentId)
                }
            }
            // A bubble never shown has no error row to carry the reason: the composer's own word says it.
            .onFailure { t -> if (discardOnFailure) discardStaged(agentId, staged, t.userMessage().takeIf { staged.shown }) }
    }

    /** The server has filed [staged] as [run]: the bubble is no longer pending, its images follow it, and its stream starts. */
    private suspend fun accepted(e: Entry, agentId: String, staged: StagedFollowUp, run: RunDto) {
        val localId = staged.localId
        // Filed under the run so the next history load finds them; the bubble follows the files to their new paths.
        val kept = runCatching { attachments.commit(agentId, run.id, staged.attachments) }.getOrDefault(staged.attachments.attachments)
        e.publish(
            mutate = {
                // A reload that raced the request may already list this run. The local copy stays all the
                // same: [Entry.items] shows the server's copy of the turn once the transcript has it, and ours
                // for as long as only the run list does; the next load prunes it once both have caught up. A
                // prompt not shown ahead of the request (a queued message's) is shown now, filed under its run.
                local = if (local.any { it.run.id == localId }) local.map { if (it.run.id == localId) it.copy(run = run) else it } else local + LocalPrompt(staged.message, run)
                promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (run.id to kept) }
                inputsUpdatedAt = maxOf(inputsUpdatedAt, staged.stagedAt)
            },
        )
        startStreaming(e, agentId, run)
        persist(e, session.current)
    }

    /** Takes a [stageFollowUp]ed prompt that will not be sent down again, with the reason when there is one to show. */
    suspend fun discardStaged(agentId: String, staged: StagedFollowUp, error: String? = null) {
        val e = entry(agentId)
        attachments.discard(staged.attachments)
        e.publish(
            mutate = {
                local = local.filterNot { it.run.id == staged.localId }
                promptImages = promptImages - staged.localId
            },
            transform = { if (error != null) copy(error = error) else this },
        )
    }

    /**
     * Launches a new chat around its prompt, so the composer can open it without waiting for the server. The message
     * is published at once — paired with a placeholder `CREATING` run, so the chat reads "Starting…", its images staged
     * like a follow-up's — and its row is put in the agent list; the request goes out and [onStaged] runs, which is
     * where the composer navigates to the chat. The server's reply swaps in the run it created and,
     * while a screen is attached, starts its stream. A failure takes the prompt and the row down again and is
     * returned. Stopping the chat before the server has answered ([cancelLaunch]) fails the launch with
     * [LaunchCancelledException]; cancelling the caller abandons the request itself — which is why the caller is the
     * [ChatLauncher], whose scope no screen owns, and not the composer.
     *
     * Requires the client-minted [LaunchRequest.agentId] the composer always sends: it is what the chat is shown under
     * before the server has named it, and what lets a retry adopt an agent the first attempt already created.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, onStaged: () -> Unit = {}): Result<Launched> {
        val agentId = requireNotNull(request.agentId) { "A launch needs a client-minted agent id to show the chat under." }
        val e = entry(agentId)
        synchronized(e) {
            if (e.launching) return Result.failure(IllegalStateException("This chat is already being started."))
            e.launching = true
        }
        val now = AppClock.now()
        val localId = "$LOCAL_RUN_PREFIX$now"
        val placeholder = e.placeholderRun(localId, now)
        // Staged before anything is shown, so the bubble has its images and files from its first frame. Storage
        // trouble costs the previews, never the launch.
        val staged = runCatching { attachments.stage(request.images, request.files) }.getOrDefault(StagedAttachments.EMPTY)
        agents.beginLaunch(request, modelDisplayName)
        e.publish(
            mutate = {
                local = local + LocalPrompt(V0ConversationMessageDto(localId, USER_MESSAGE, request.prompt.trim()), placeholder)
                if (staged.attachments.isNotEmpty()) promptImages = promptImages + (localId to staged.attachments)
            },
            transform = { copy(isLoading = false, error = null, activeRunId = null, runStatus = RunStatus.CREATING, isStreaming = false, transcriptUnavailable = false) },
        )
        // The request runs in the entry's scope so a Stop from the chat can cancel it without the launcher's
        // coroutine being involved; the launcher's own cancellation takes the request down with it. It is on the
        // entry before the chat opens, so a Stop tapped the instant the chat is on screen still finds it.
        val inFlight = e.scope.async { agents.launch(request, modelDisplayName, saveImages = false) }
        synchronized(e) { e.launch = inFlight }
        onStaged()

        val result: Result<Launched> = try {
            inFlight.await()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) {
                Result.failure(LaunchCancelledException())
            } else {
                inFlight.cancel()
                Result.failure(cancelled)
            }
        }
        // Whatever happened, the chat must be left consistent — including when the launcher is already cancelled.
        withContext(NonCancellable) {
            result.fold(
                onSuccess = { launched -> settleLaunch(e, launched, localId, staged, now) },
                onFailure = { rollbackLaunch(e, localId, staged) },
            )
            synchronized(e) {
                e.launch = null
                e.launching = false
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    /** The server created the chat: its run takes the placeholder's place, the images are filed under it, and its stream starts. */
    private suspend fun settleLaunch(e: Entry, launched: Launched, localId: String, staged: StagedAttachments, sentAt: Long) {
        val run = launched.run
        if (run == null) {
            // A retry adopted an agent whose run could not be read: the server owns the transcript from here on.
            attachments.discard(staged)
            e.publish(mutate = { local = local.filterNot { it.run.id == localId }; promptImages = promptImages - localId })
            if (e.attached > 0) reload(e.agentId)
            return
        }
        val kept = runCatching { attachments.commit(e.agentId, run.id, staged) }.getOrDefault(staged.attachments)
        e.publish(
            mutate = {
                local = local.map { if (it.run.id == localId) it.copy(run = run) else it }
                promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (run.id to kept) }
                inputsUpdatedAt = maxOf(inputsUpdatedAt, sentAt)
            },
            transform = { copy(activeRunId = run.id, runStatus = run.statusEnum()) },
        )
        // With nobody looking, the stream waits for the next attach, whose load starts it like any active run's.
        if (e.attached > 0 && run.statusEnum().isActive) startStreaming(e, e.agentId, run)
        persist(e, session.current)
    }

    /** The chat was not created: nothing of it stays, on screen or on disk. */
    private suspend fun rollbackLaunch(e: Entry, localId: String, staged: StagedAttachments) {
        attachments.discard(staged)
        e.publish(
            mutate = {
                local = local.filterNot { it.run.id == localId }
                promptImages = promptImages - localId
            },
            transform = { ConversationState(agentId) },
        )
        agents.discardLaunch(e.agentId)
    }

    suspend fun cancelActiveRun(agentId: String): Result<Unit> {
        // A chat the server has not answered for yet has no run to cancel; stopping it gives the launch up instead.
        if (cancelLaunch(agentId)) return Result.success(Unit)
        val runId = runToCancel(agentId) ?: return Result.failure(IllegalStateException("No active run."))
        return cancelRun(agentId, runId)
    }

    /** Cancels one run of the chat and, once the server has agreed, shows the turn as cancelled. */
    suspend fun cancelRun(agentId: String, runId: String): Result<Unit> {
        val e = entry(agentId)
        return agents.cancelRun(agentId, runId).onSuccess {
            e.cancelledRunId = runId
            e.state.update { it.copy(runStatus = RunStatus.CANCELLED) }
        }
    }

    /**
     * The run a Stop is aimed at: the one the server is on, as far as this device knows. The row's latest run while
     * the row says the agent is running — a follow-up that went out while no screen was attached, or a turn started
     * elsewhere, reaches the row before the chat has loaded it, and the run the chat still follows may be over by
     * then — else the run the chat follows. Never a prompt's local placeholder, which the server knows nothing of.
     * Null when nothing is known to be running.
     */
    fun runToCancel(agentId: String): String? {
        val row = agents.agent(agentId)
        return row?.takeIf { it.isRunning }?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
            ?: entry(agentId).state.value.activeRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
    }

    /**
     * Abandons a [launch] the server has not answered yet, from any screen; the launcher gets a
     * [LaunchCancelledException] and the prompt comes down. False when there is nothing in flight for this chat.
     */
    fun cancelLaunch(agentId: String): Boolean {
        val e = synchronized(entries) { entries[agentId] } ?: return false
        val inFlight = synchronized(e) { e.launch?.takeIf { it.isActive } } ?: return false
        inFlight.cancel()
        return true
    }

    /**
     * The run a prompt sent now is paired with until the server has answered. Stamped now, but never before the newest
     * run already known: the transcript is ordered by these stamps, and a device clock behind the server's must not
     * file a new prompt under an older turn.
     */
    private fun Entry.placeholderRun(id: String, now: Long): RunDto {
        val at = Instant.ofEpochMilli(maxOf(now, synchronized(this) { newestRunAt() } + 1)).toString()
        return RunDto(id = id, agentId = agentId, status = RunStatus.CREATING.name, createdAt = at, updatedAt = at)
    }

    fun clearError(agentId: String) = entry(agentId).state.update { it.copy(error = null) }

    fun resetAll() {
        synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = null
            pendingPrefetch = null
        }
        diskIndex.clear()
        synchronized(entries) {
            entries.values.forEach { it.scope.cancel() }
            entries.clear()
        }
    }

    // -- prefetch ----------------------------------------------------------------------------------------------------

    /**
     * Warms the transcripts the user is most likely to open next: the most recently updated idle agents whose
     * cached copy is missing or older than the row. One agent at a time, spaced out, and abandoned at the first
     * failure (offline, rate limited) rather than hammering the API; the next completed list fetch tries again.
     * Text only: traces are replayed when a chat is actually opened.
     */
    private fun schedulePrefetch(list: List<Agent>) {
        if (cache == null || session.isDemo) return
        val candidates = list.asSequence()
            .filter { !it.isArchived && !it.isRunning }
            .sortedByDescending { it.updatedAtMillis }
            .take(prefetchLimit)
            .toList()
        if (candidates.isEmpty()) return
        synchronized(this) {
            // A pass already running is left alone (cancelling it would waste its in-flight request); it picks up
            // the newest candidates when it is done.
            pendingPrefetch = candidates
            if (prefetchJob?.isActive == true) return
            prefetchJob = scope.launch {
                while (true) {
                    val next = synchronized(this@ConversationRepository) { pendingPrefetch.also { pendingPrefetch = null } } ?: break
                    prefetch(next)
                }
            }
        }
    }

    private suspend fun prefetch(candidates: List<Agent>) {
        val backend = session.current
        val tokens = cacheTokens()
        val api = backend.api
        for (agent in candidates) {
            if (!isForeground()) return
            if (isFresh(agent)) continue
            val ok = runCatching {
                coroutineScope {
                    val conversation = async { api.conversationV0(agent.id) }
                    // The newest page alone: it is what the chat opens on, and the rest of the records follow when it does.
                    val runs = async { api.listRuns(agent.id, limit = FIRST_RUN_PAGE) }
                    val transcript = conversation.await().messages
                    val page = runs.await()
                    val latest = page.items.maxByOrNull { parseIsoMillis(it.createdAt) }
                    // The list only said the agent went idle; the run says how the turn ended (an error, say), and the
                    // row is the one place the sidebar learns that from without the chat being opened.
                    val e = entry(agent.id)
                    latest?.let { run -> agents.recordRun(agent.id, e.known(run)) }
                    // A screen that opened it in the meantime owns the entry now.
                    if (e.attached == 0 && e.streamJob == null) {
                        e.publish(
                            mutate = {
                                messages = transcript
                                mergeNewestPage(page)
                                transcriptUnavailable = false
                                inputsUpdatedAt = agent.updatedAtMillis
                                pruneLocal()
                                fetched = true
                            },
                            transform = { copy(isLoading = false, activeRunId = latest?.id, runStatus = latest?.statusEnum()) },
                        )
                        persist(e, backend, tokens)
                    }
                }
            }.isSuccess
            if (!ok) return
            delay(prefetchSpacingMs)
        }
    }

    /**
     * The transcript is fresh when what we hold (in memory or on disk) is as new as the agent row. Each agent's
     * file is read at most once per session for this check; afterwards the index answers from memory.
     */
    private suspend fun isFresh(agent: Agent): Boolean {
        val inMemory = synchronized(entries) { entries[agent.id] }
        if (inMemory != null && inMemory.hasInputs && inMemory.inputsUpdatedAt >= agent.updatedAtMillis) return true
        val onDisk = diskIndex[agent.id] ?: (readCache(agent.id)?.agentUpdatedAtMillis ?: ABSENT)
        return onDisk >= agent.updatedAtMillis
    }

    private companion object {
        const val MAX_ENTRIES = 24
        const val PREFETCH_LIMIT = 6
        const val PREFETCH_SPACING_MS = 400L
        const val ABSENT = -1L
        /** Finished runs replayed at once; older logs mostly answer with `410 stream_expired`, which is cheap. */
        const val MAX_PARALLEL_REPLAYS = 3
        /** The newest runs, fetched first: enough for the window, small enough not to hold the first frame up. */
        const val FIRST_RUN_PAGE = 20
        /** The pages of older run records behind it, at the most the API serves per page. */
        const val RUN_PAGE_SIZE = 100
        /** Room for device and server clocks to disagree when matching a send marker to a run stamp. */
        const val CLOCK_SKEW_ALLOWANCE_MS = 60_000L
        /** A send whose reply was lost, and which the server confirms it does not hold, goes out this many times in all (see [sendStaged]). */
        const val LOST_REPLY_ATTEMPTS = 2
        /** Pages of older run records read past the first: beyond them the oldest prompts are shown without runs. */
        const val MAX_RUN_PAGES = 8
        /** How close two publications may come before the second waits for the burst (see [publishCoalesced]). */
        const val PUBLISH_COALESCE_MS = 80L

        /** A prompt's text as the server and the phone agree on it: runs of whitespace as one space, trimmed. */
        fun normalizePrompt(text: String): String = QueuePlacement.textKey(text)
        /** The pauses between looks for the next run and reads of the record's growth while the account runs on (see [keepFollowing]). */
        const val KEEP_FOLLOWING_BASE_MS = 2_000L
        const val KEEP_FOLLOWING_MAX_MS = 15_000L
        /** How many looks find the account idle after a run ended before the records' word is taken (see [keepFollowing]). */
        const val KEEP_FOLLOWING_IDLE_LOOKS = 3
        /** How many of the newest runs a chat opens on, and by how many the window widens each time the reader scrolls up to its end. */
        const val WINDOW_RUNS = 10
        /** The widest window the disk copy reopens on: the runs whose traces are read before the first frame. */
        const val MAX_RESTORED_WINDOW = 30
        /** How many of the newest turns a cold open of the blob-backed record paints first, the rest of the window behind them (see [loadFromRecord]). */
        const val FIRST_PAINT_TURNS = 3
        /** A chat opened this recently is not fetched again when the app comes to the foreground. */
        const val REVALIDATE_MIN_INTERVAL_MS = 5_000L
        /**
         * A screen back on a chat within this of its last read shows it from memory and fetches nothing but its live
         * turn (see [attach], [reopen]): a reader stepping out of a chat and straight back in costs no round trip.
         */
        const val REOPEN_FRESH_MS = 30_000L
        /** How long after the last screen left a widened window is kept whole, for a reader who comes back (see [trimWindow]). */
        const val TRIM_AFTER_DETACH_MS = 60_000L
        /** How long the record is left alone after it refused or failed without naming a pause (see [Entry.recordRefusedUntil]). */
        const val RECORD_RETRY_MS = 30_000L
        /** The least a pause the server named holds the record off for: a `Retry-After: 0` is still a refusal. */
        const val RECORD_RETRY_MIN_MS = 2_000L
        /**
         * How long the record is left alone once the server has said the read is gone — "FetchBackgroundComposer has
         * been removed", `unimplemented`, a `404` for the method: not a pause, a removal, which the next open or the
         * hour asks about again (Bennett's 2026-09-20 export: the same refusal every thirty seconds, four in six minutes).
         */
        const val RECORD_REMOVED_RETRY_MS = 60 * 60_000L
        /** How many times, a moment apart, the transcript is asked for a message the account delivered before it is given up (see [adoptDelivered]). */
        const val ADOPT_ATTEMPTS = 4
        /**
         * How long after a run is first seen over a read of the account's queue must have begun for its word — the
         * message filed under that run still waiting — to put the message back on the card (see [noteAccountQueue]):
         * a read begun before the end, or on its heels, may predate the account's own bookkeeping.
         */
        const val PUT_BACK_SLACK_MS = 2_000L
        const val ADOPT_RETRY_MS = 2_500L
        /** The two message types of `/v0/agents/{id}/conversation`. */
        const val USER_MESSAGE = "user_message"
        const val ASSISTANT_MESSAGE = "assistant_message"
        /** Ids of the runs local prompts are paired with until the server has answered (the same id as their message, see [LocalPrompt.filed]). */
        const val LOCAL_RUN_PREFIX = "local-"
        /** `POST /v1/agents/{id}/runs` while a run is `CREATING` or `RUNNING`. */
        const val AGENT_BUSY = "agent_busy"
    }
}
