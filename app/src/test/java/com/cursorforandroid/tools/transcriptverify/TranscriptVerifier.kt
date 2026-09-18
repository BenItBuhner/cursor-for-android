package com.cursorforandroid.tools.transcriptverify

import android.content.Context
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.RecordPager
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.SendGate
import com.cursorforandroid.domain.SummaryRow
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptDiagnostics
import com.cursorforandroid.domain.TranscriptLoadDiagnostics
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TurnShape
import com.cursorforandroid.domain.UserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * What the harness runs the pipeline against: the app's own clients — real ones over the network in a live run, the
 * same classes over an in-process server in replay — plus the ledger they are wired through. Built by
 * [TranscriptVerifyHarness] for a live run and by [ReplayServer] for the fixtures; nothing here knows which.
 */
class VerifyBackend(
    val api: CursorApi,
    val streamer: RunStreamer,
    /** The account's record (Extended mode); null in default mode. */
    val record: ConversationRecordApi?,
    /** The account's list, for its word on the chat (Extended mode); null in default mode. */
    val accountList: (suspend () -> AccountList)?,
    val ledger: CallLedger,
    /** Words about the backend for the report's header, no secrets: hosts, mode. */
    val description: String,
)

/** Outcome of a run, for the caller: the report text, and what was found, in the facts a test asserts on. */
class VerifyOutcome(
    val report: String,
    val state: ConversationState?,
    val rows: List<TranscriptRow>,
    val load: TranscriptLoadDiagnostics?,
    val turnLines: List<String>,
    val sendResult: SendOutcome?,
) {
    /** The rows drawn as the coordinator's messages to the user, in order. */
    val messageRows: List<TranscriptRow.Message> get() = rows.filterIsInstance<TranscriptRow.Message>()
}

class SendOutcome(val decision: SendGate.Decision, val sent: Boolean, val runId: String?, val refusal: String?, val replyStage: String?, val echoReconciled: Boolean?)

/**
 * The pipeline, end to end, as the app runs it: the stores, the session, the agent list, the live hub, the
 * conversation repository, the follow-up gate, the presenter — the same constructors `AppGraph` uses, over the same
 * caches on a fresh directory — driven the way a screen drives them (attach, scroll to the top, follow, send), and
 * reported stage by stage. One instance per run; [run] is called once.
 */
class TranscriptVerifier(
    private val context: Context,
    private val backend: VerifyBackend,
    private val options: TranscriptVerifyOptions,
    private val agentId: String,
    private val out: Appendable,
    /** Where the caches go: a fresh directory, so the run is a cold open like a first install's. */
    private val cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** The waits, shortened by the replay test. */
    private val firstLoadTimeoutMs: Long = 180_000L,
    private val pageTimeoutMs: Long = 60_000L,
    private val settleTimeoutMs: Long = 90_000L,
    /** How often the account's list is read again while a turn is followed: the app's list poll (`AgentsViewModel.POLL_INTERVAL_MS`). */
    private val accountRoundMs: Long = 30_000L,
) {
    private val report = StringBuilder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val caps: Capabilities = Capabilities.of(options.extended)

    private fun line(text: String = "") {
        report.appendLine(text)
        out.append(text).append('\n')
        if (out is java.io.Flushable) out.flush()
    }

    private fun section(title: String) {
        line()
        line("== $title ==")
    }

    private fun stamp(): String = Instant.ofEpochMilli(now()).toString()

    suspend fun run(): VerifyOutcome {
        val startedAt = now()
        line("Cursor for Android transcript-verify · app ${BuildConfig.VERSION_NAME} · ${stamp()}")
        line("mode=${if (options.extended) "extended" else "default"} agent=$agentId ${backend.description}")
        line("turns=${options.turns ?: "all"} followSeconds=${options.followSeconds} send=${if (options.send == null) "no" else "yes"} showText=${options.showText}")

        // -- the stack, as AppGraph builds it ------------------------------------------------------------------------
        val prefs = PreferencesStore(context)
        val keyStore = SecureKeyStore(context, openRetryDelayMs = 0)
        val real = CursorBackend(backend.api, backend.streamer, isDemo = false)
        val demo = CursorBackend(FakeCursorApi(), FakeRunStreamer(), isDemo = true)
        val session = SessionManager(keyStore, prefs, real, demo, scope = scope, capabilities = { caps })
        val disk = JsonDiskCache(cacheDir)
        val attachments = AttachmentStore(context)
        val agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10, capabilities = { caps })
        val hub = LiveRunHub(session, agents, scope = scope)
        val traces = TraceCache(disk.child("traces"))
        val conversations = ConversationRepository(
            session, agents, prefs, hub, attachments,
            cache = ConversationCache(disk.child("conversations")), traceCache = traces,
            isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
            record = if (options.extended) backend.record else null, capabilities = { caps },
        )
        val followUps = FollowUpRepository(conversations, agents, hub, mcpServers = { emptyList() }, persist = { false }, scope = scope)
        val presenter = TranscriptPresenter()

        // -- 1. the row -------------------------------------------------------------------------------------------------
        section("agent")
        val row = agents.loadDetail(agentId).fold(
            onSuccess = { it },
            onFailure = { t -> line("GET /v1/agents/{id} failed: ${describeError(t)}"); null },
        )
        row?.let { line("row: ${describe(it)}") }

        // -- 2. the account's word (Extended) --------------------------------------------------------------------------
        if (options.extended) accountRound(agents, "account")

        // -- 3. open the chat as a screen would -----------------------------------------------------------------------
        section("load")
        val opened = now()
        conversations.attach(agentId)
        val firstLoaded = awaitState(conversations, firstLoadTimeoutMs) { !it.isLoading }
        var state = conversations.state(agentId).value
        line("first load: ${if (firstLoaded) "done" else "TIMED OUT after ${firstLoadTimeoutMs / 1000} s"} in ${now() - opened} ms · items=${state.items.size} hasOlder=${state.hasOlder} runStatus=${state.runStatus ?: "-"} streaming=${state.isStreaming} error=${state.error ?: "-"} transcriptError=${state.transcriptError ?: "-"} project=${state.isProjectConversation}")

        // -- 4. scroll to the top (or to --turns) ---------------------------------------------------------------------
        var pages = 0
        var pagingNote = "reached the start"
        while (conversations.state(agentId).value.hasOlder && pages < MAX_PAGES) {
            val current = conversations.state(agentId).value
            if (options.turns != null && turnsShown(current.items) >= options.turns) { pagingNote = "stopped at --turns ${options.turns}"; break }
            val before = current.items.size
            conversations.loadOlder(agentId)
            val landed = awaitState(conversations, pageTimeoutMs) { s -> (s.items.size > before && !s.isLoadingOlder) || !s.hasOlder }
            pages++
            if (!landed) { pagingNote = "page $pages TIMED OUT after ${pageTimeoutMs / 1000} s"; break }
        }
        if (pages >= MAX_PAGES) pagingNote = "stopped at the page cap ($MAX_PAGES)"
        state = conversations.state(agentId).value
        line("older turns: $pages page(s) requested, $pagingNote · items=${state.items.size} turnsShown=${turnsShown(state.items)} hasOlder=${state.hasOlder}")

        // -- 5. let the replays land -------------------------------------------------------------------------------------
        val settled = awaitState(conversations, settleTimeoutMs) { it.traceStatus.pending == 0 && !it.isLoadingOlder }
        state = conversations.state(agentId).value
        line("traces: ${if (settled) "settled" else "still pending after ${settleTimeoutMs / 1000} s"} shown=${state.traceStatus.shown} pending=${state.traceStatus.pending} expired=${state.traceStatus.expired} failed=${state.traceStatus.failed}")

        // -- 6. follow a turn still running --------------------------------------------------------------------------
        if (state.isStreaming || state.runStatus?.isActive == true) {
            line("live: the chat is running (status=${state.runStatus}, streaming=${state.isStreaming}); following for up to ${options.followSeconds} s")
            follow(conversations, agents, options.followSeconds * 1000L)
            state = conversations.state(agentId).value
        } else {
            line("live: the chat is idle (status=${state.runStatus ?: "-"})")
        }

        // -- 7. present ------------------------------------------------------------------------------------------------
        val agent = agents.agent(agentId)
        val coordinatorWord = agent?.looksLikeProject == true || state.isProjectConversation
        val presented = presenter.present(state.items, coordinatorWord, runActive = state.isStreaming || state.runStatus?.isActive == true)
        val load = conversations.loadDiagnostics(agentId)
        line("presented: rows=${presented.rows.size} items=${presented.items.size} coordinatorMode=${presented.coordinatorMode} (list/record word=$coordinatorWord, content=${CoordinatorTranscript.hasCoordinatorContent(state.items)}) messages=${presented.rows.count { it is TranscriptRow.Message }} goal=${presented.goal != null}")
        load?.let { renderLoad(it) }

        // -- 8. the independent pass ------------------------------------------------------------------------------------
        val independent = if (options.extended && backend.record != null) independentRecordPass(backend.record) else independentDefaultPass()

        // -- 9. turns ----------------------------------------------------------------------------------------------------
        section("turns (${if (load?.source == "record") "the record's window" else "the run list"}, oldest first)")
        val turnLines = turnLines(load, independent, state)
        turnLines.forEach(::line)

        // -- 10. rows ----------------------------------------------------------------------------------------------------
        section("rows (TranscriptPresenter, in order)")
        presented.rows.forEachIndexed { i, r -> line("  ${i.toString().padStart(3)} ${describe(r)}") }

        // -- 11. shapes of every turn read ------------------------------------------------------------------------------
        independent?.shapes?.takeIf { it.isNotEmpty() }?.let { shapes ->
            section("shapes (independent read of the record)")
            line(TranscriptDiagnostics.renderShapes(shapes, "${shapes.size} turns of the record read independently").trimEnd())
        }

        // -- 12. the app's own export -----------------------------------------------------------------------------------
        section("transcript diagnostics export (what Settings › Debug shares)")
        var sendOutcome: SendOutcome? = null
        // -- 13. send ------------------------------------------------------------------------------------------------
        if (options.send != null) {
            sendOutcome = send(conversations, followUps, agents, options.send)
        }
        val export = TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input(
                appVersion = BuildConfig.VERSION_NAME,
                nowIso = stamp(),
                extendedMode = options.extended,
                agentId = agentId,
                agent = agents.agent(agentId),
                state = conversations.state(agentId).value.let {
                    TranscriptDiagnostics.State(it.items, it.isLoading, it.isStreaming, it.isReconnecting, it.runStatus, it.hasOlder, it.transcriptUnavailable, it.isProjectConversation, it.error)
                },
                placement = agents.placementOf(agentId),
                load = conversations.loadDiagnostics(agentId),
                perf = TranscriptPerf.sessionOrNull(agentId)?.snapshot(),
                send = followUps.sendDiagnostics(agentId),
            ),
        )
        line(export.trimEnd())

        // -- 14. the ledger --------------------------------------------------------------------------------------------
        section("calls")
        line(backend.ledger.render().trimEnd())
        line()
        line("done in ${(now() - startedAt) / 1000} s")

        conversations.detach(agentId)
        scope.cancel()
        options.out?.let { file -> file.parentFile?.mkdirs(); file.writeText(report.toString()); out.append("report written to ${file.absolutePath}\n") }
        return VerifyOutcome(report.toString(), state, presented.rows, load, turnLines, sendOutcome)
    }

    // -- waits --------------------------------------------------------------------------------------------------------

    private suspend fun awaitState(conversations: ConversationRepository, timeoutMs: Long, condition: (ConversationState) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition(conversations.state(agentId).value)) delay(100)
            true
        } == true

    /** Turns on screen: the user's prompts and the injected turns, whichever kind of item each stands as. */
    private fun turnsShown(items: List<TimelineItem>): Int = items.count { it is UserMessage || it is SystemNotification }

    /**
     * Follows the running turn, logging each change of status, streaming and item count, until it ends or the budget
     * is spent. Every [accountRoundMs] the account's list is read again (Extended mode), as the app's list poll and
     * the account round behind it do while a chat is open: the status precedence and the send gate read that word.
     */
    private suspend fun follow(conversations: ConversationRepository, agents: AgentRepository, budgetMs: Long) {
        val started = now()
        var last = ""
        var lastRound = started
        while (now() - started < budgetMs) {
            val s = conversations.state(agentId).value
            val word = "status=${s.runStatus ?: "-"} streaming=${s.isStreaming} reconnecting=${s.isReconnecting} items=${s.items.size} message=${CoordinatorTranscript.messageStage(newestTurn(s.items))}"
            if (word != last) { line("  +${(now() - started) / 1000}s $word"); last = word }
            if (!s.isStreaming && s.runStatus?.isActive != true) { line("  the turn ended after ${(now() - started) / 1000} s"); return }
            if (options.extended && now() - lastRound >= accountRoundMs) {
                lastRound = now()
                accountRound(agents, "  +${(now() - started) / 1000}s account round")
            }
            delay(500)
        }
        line("  still running after ${budgetMs / 1000} s; the report goes on with what arrived")
    }

    /**
     * One read of the account's list, applied as the app's account round applies it (`PinRepository.readList`):
     * the records onto the rows and the running set, the sources onto the rows. Read-only.
     */
    private suspend fun accountRound(agents: AgentRepository, label: String) {
        val list = backend.accountList ?: run { line("$label: no account list wired"); return }
        try {
            val token = agents.token()
            val account = list()
            agents.applyAccountSnapshots(account.composers, token)
            agents.applySources(account.sources, token)
            val mine = account.composers.firstOrNull { it.id == agentId }
            val word = agents.runningScan.value.accountWord[agentId]
            line(
                "$label: ListBackgroundComposers page=${account.composers.size} records, this chat " +
                    (mine?.let { "status=${it.status?.name ?: "-"} running=${it.status?.isActive == true} isProject=${it.isProject} scope=${it.scope.name} archived=${it.archived} activityAt=${it.activityAtMillis?.let { ms -> Instant.ofEpochMilli(ms) } ?: "-"}" }
                        ?: "not on the first page (the account's word on it stays unread, as in the app until the list scrolls to it)") +
                    " · word for the gate: ${word?.let { "running=${it.running} at=${Instant.ofEpochMilli(it.atMillis)}" } ?: "none"}",
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            line("$label: ListBackgroundComposers failed: ${describeError(t)}")
        }
    }

    /** The items from the last user prompt or injected turn on: the newest turn as shown. */
    private fun newestTurn(items: List<TimelineItem>): List<TimelineItem> {
        val start = items.indexOfLast { it is UserMessage || it is SystemNotification }
        return if (start < 0) items else items.subList(start, items.size)
    }

    // -- the independent pass --------------------------------------------------------------------------------------------

    /** One turn of the record as read straight off the pages: where it starts, what its steps say, what its body renders to. */
    class IndependentTurn(val stepIndex: Int, val turnIndex: Int, val prompt: String, val promptChars: Int, val promptExcerpt: String?, val steps: Int, val shape: TurnShape, val messageStage: String, val hasBody: Boolean, val timing: com.cursorforandroid.data.api.TurnTiming?)

    class Independent(val turns: List<IndependentTurn>, val shapes: List<TurnShape>, val runs: List<RunDto>, val state: RecordState?, val note: String)

    private suspend fun independentRecordPass(record: ConversationRecordApi): Independent? {
        section("independent pass (the record read again from its end; the run list paged whole)")
        return try {
            val want = options.turns ?: Int.MAX_VALUE / 4
            var raw = RecordPager.tail(record, agentId, wantTurns = minOf(want, 10))
            if (raw == null) { line("the record is empty"); return Independent(emptyList(), emptyList(), emptyList(), null, "empty") }
            var reads = 0
            while (raw!!.firstStep > 0 && reads < MAX_INDEPENDENT_READS && HeadlessTranscript.split(raw.steps).count { it.prompt != null } < want) {
                val older = RecordPager.before(record, agentId, raw.firstStep, wantTurns = minOf(want, 10))
                if (older.steps.isEmpty()) break
                raw = RecordPager.Raw(older.steps + raw.steps, older.firstStep, raw.total)
                reads++
            }
            val state = runCatching { record.state(agentId) }.getOrElse { t -> line("GetLatestAgentConversationState failed: ${describeError(t)}"); null }
            val turns = HeadlessTranscript.split(raw.steps)
            val turnCount = maxOf(state?.turnCount ?: 0, turns.size)
            var index = raw.firstStep
            val independent = ArrayList<IndependentTurn>()
            turns.forEachIndexed { i, turn ->
                val stepIndex = index
                index += turn.steps.size + (if (turn.prompt != null) 1 else 0)
                val shape = HeadlessTranscript.shape(turn, stepIndex)
                val items = HeadlessTranscript.body(turn, "verify-$stepIndex")
                val turnIndex = turnCount - turns.size + i
                independent += IndependentTurn(
                    stepIndex = stepIndex,
                    turnIndex = turnIndex,
                    prompt = shape.prompt,
                    promptChars = turn.prompt?.length ?: 0,
                    promptExcerpt = turn.prompt?.let(::excerpt),
                    steps = turn.steps.size,
                    shape = shape,
                    messageStage = CoordinatorTranscript.messageStage(items),
                    hasBody = items.any { it is ActivityGroup || it is AssistantMessage },
                    timing = state?.timings?.getOrNull(turnIndex),
                )
            }
            val runs = allRuns()
            line("record: total=${raw.total} steps, read from step ${raw.firstStep} (${raw.steps.size} steps, ${turns.size} turns) in ${reads + 1} read(s); state: turnCount=${state?.turnCount ?: "-"} timings=${state?.timings?.size ?: "-"} pendingToolCalls=${state?.pendingToolCalls ?: "-"} isRootProject=${state?.isRootProject ?: "-"} rewindEpoch=${state?.rewindEpoch ?: "-"}")
            line("runs: ${runs.size} listed (${runs.count { it.status == "FINISHED" }} finished, ${runs.count { it.status == "RUNNING" || it.status == "CREATING" }} active, ${runs.count { it.status == "CANCELLED" }} cancelled, ${runs.count { it.status == "ERROR" }} error) · record turns=${turnCount} · ${if (runs.size == turnCount) "one run per turn" else "MISMATCH: the run list and the record disagree on the turn count (${runs.size} runs vs $turnCount turns)"}")
            val byStage = independent.groupingBy { it.messageStage.substringBefore(' ') }.eachCount()
            line("sendMessage by stage over the turns read: ${byStage.entries.joinToString(", ") { "${it.key}=${it.value}" }} · user prompts=${independent.count { it.prompt == "user" }} injected=${independent.count { it.prompt == "injected" }} promptless=${independent.count { it.prompt == "none" }} · turns without a body=${independent.count { !it.hasBody }}")
            Independent(independent, independent.map { it.shape }, runs, state, "ok")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            line("the independent pass failed: ${describeError(t)}")
            null
        }
    }

    private suspend fun independentDefaultPass(): Independent? {
        section("independent pass (the documented transcript and the run list, paged whole)")
        return try {
            val conversation = backend.api.conversationV0(agentId)
            val prompts = conversation.messages.filter { it.type == "user_message" }
            val runs = allRuns()
            line("v0 transcript: ${conversation.messages.size} messages, ${prompts.size} prompts (${prompts.count { SystemNotifications.isInjected(it.text) }} injected), ${conversation.messages.count { it.type == "assistant_message" }} replies")
            line("runs: ${runs.size} listed · ${if (runs.size == prompts.size) "one run per prompt" else "MISMATCH: ${runs.size} runs vs ${prompts.size} prompts"}")
            Independent(emptyList(), emptyList(), runs, null, "ok")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            line("the independent pass failed: ${describeError(t)}")
            null
        }
    }

    /** Every run of the chat, oldest first, through every page the list offers. */
    private suspend fun allRuns(): List<RunDto> {
        val all = ArrayList<RunDto>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = backend.api.listRuns(agentId, limit = 100, cursor = cursor)
            all += page.items
            cursor = page.nextCursor?.takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_RUN_PAGES)
        return all.distinctBy { it.id }.sortedBy { parseIso(it.createdAt) ?: 0L }
    }

    // -- turns --------------------------------------------------------------------------------------------------------

    private fun turnLines(load: TranscriptLoadDiagnostics?, independent: Independent?, state: ConversationState): List<String> {
        val lines = ArrayList<String>()
        if (load == null) { lines += "  (no load diagnostics: the chat has no entry)"; return lines }
        val byStep = independent?.turns?.associateBy { it.stepIndex }.orEmpty()
        val runsByTail = independent?.runs?.associateBy { tail(it.id) }.orEmpty()
        load.runs.forEachIndexed { i, run ->
            val stepIndex = run.idTail.takeIf { it.startsWith("turn@") }?.substringAfter("turn@")?.substringBefore('/')?.toIntOrNull()
            val pairedTail = run.idTail.substringAfter('/', "").takeIf { it.isNotEmpty() }
            val own = stepIndex?.let { byStep[it] }
            val sb = StringBuilder()
            sb.append("  #").append(i.toString().padStart(4)).append(' ')
            sb.append(if (stepIndex != null) "turn@$stepIndex" else "run ${run.idTail}")
            own?.let { sb.append(" [${it.turnIndex}]") }
            sb.append(" kind=").append(own?.prompt ?: if (stepIndex == null) "run" else "?")
            own?.let { sb.append(" prompt=").append(it.promptChars).append("ch"); it.promptExcerpt?.let { e -> sb.append(" \"").append(e).append('"') } }
            own?.let { sb.append(" steps=").append(it.steps).append(" project=").append(it.shape.projectMode) }
            sb.append(" run=").append(pairedTail ?: "UNPAIRED").append('/').append(run.status)
            sb.append(" trace=").append(run.trace).append(" items=").append(run.items)
            run.message?.let { sb.append(" sendMessage: ").append(it) }
            own?.let {
                sb.append(" | record-alone: sendMessage=").append(it.messageStage).append(" body=").append(it.hasBody)
                // The message calls as the record carried them: each call's name and how its arguments came together,
                // and the parser branches of the steps that carried any call — which key the record put them under.
                val messageCalls = it.shape.calls.filter { c -> ToolNames.coordinatorTool(c.name) == ToolNames.USER_MESSAGE_TOOL }
                if (messageCalls.isNotEmpty()) {
                    sb.append(" msgCalls=[").append(messageCalls.joinToString("; ") { c -> "${c.name} args=${c.args} steps=${c.steps} result=${c.result}" }).append(']')
                    val branches = it.shape.steps.map { st -> st.branch }.filter { b -> b.startsWith("tool_call") || b.startsWith("streamed_back_tool_call") }.distinct()
                    if (branches.isNotEmpty()) sb.append(" branches=[").append(branches.joinToString("; ")).append(']')
                }
            }
            // The pairing checked by time: the run whose life covers the turn's end, as the state times it.
            val timing = own?.timing
            if (timing?.timestampMs != null && independent != null && independent.runs.isNotEmpty()) {
                val byTime = independent.runs.minByOrNull { r -> distance(r, timing.timestampMs) }
                val delta = byTime?.let { distance(it, timing.timestampMs) }
                sb.append(" timeRun=").append(byTime?.let { tail(it.id) } ?: "-")
                if (delta != null && delta > 0) sb.append("(Δ").append(delta / 1000).append("s)")
                if (byTime != null && pairedTail != null && tail(byTime.id) != pairedTail) sb.append(" PAIR-MISMATCH")
                if (byTime != null && pairedTail == null) sb.append(" (a run exists for this turn's time; the window's pairing gave none)")
            }
            pairedTail?.let { runsByTail[it] }?.let { r -> sb.append(" runAt=").append(r.createdAt.take(19)) }
            lines += sb.toString()
        }
        if (load.runs.isEmpty()) lines += "  (the load has no run lines)"
        // What the screen holds beyond the window's lines: the user's prompts, so a prompt without a turn is visible.
        val prompts = state.items.filterIsInstance<UserMessage>()
        lines += "  user prompts on screen: ${prompts.size} (${prompts.count { it.isPending }} pending, ${prompts.count { it.id.startsWith("local-") }} local echoes)"
        return lines
    }

    /** How far [at] lies outside the run's life (0 inside it), by the run's own stamps. */
    private fun distance(run: RunDto, at: Long): Long {
        val start = parseIso(run.createdAt) ?: return Long.MAX_VALUE / 2
        val end = parseIso(run.updatedAt) ?: start
        return when {
            at < start -> start - at
            at > end -> at - end
            else -> 0L
        }
    }

    // -- send ---------------------------------------------------------------------------------------------------------

    private suspend fun send(conversations: ConversationRepository, followUps: FollowUpRepository, agents: AgentRepository, text: String): SendOutcome {
        section("send")
        // The account's word again, as the app's account round re-reads it every half minute: the gate takes the
        // freshest source, and a word from before the turn ended would call the agent busy.
        if (options.extended) accountRound(agents, "account re-read before the decision")
        val decision = followUps.decide(agentId)
        val i = decision.inputs
        line("gate: ${if (decision.busy) "BUSY" else "idle"} by=${decision.source.name.lowercase()} row=${if (!i.rowLoaded) "none" else if (i.rowRunning) "running" else "idle"} chat=${i.chatRunStatus ?: "-"} streaming=${i.chatStreaming} reconnecting=${i.chatReconnecting} account=${if (!i.accountScanned) "unread" else if (i.accountRunning) "running" else "idle"}")
        if (decision.busy) {
            line("not sent: the send gate reads the agent as busy; the composer would queue the message (${if (options.extended) "on the account" else "on this device"}). Nothing was sent.")
            return SendOutcome(decision, sent = false, runId = null, refusal = "busy by ${decision.source}", replyStage = null, echoReconciled = null)
        }
        line("sending ${text.length} characters through ConversationRepository.sendFollowUp (the composer's documented path: POST /v1/agents/{id}/runs)")
        val sentAt = now()
        val result = conversations.sendFollowUp(agentId, text)
        val run = result.getOrNull()
        if (run == null) {
            val t = result.exceptionOrNull()!!
            val error = t.toCursorError()
            val path = when {
                error?.code == "agent_busy" -> "409 agent_busy: the composer would queue it (${if (options.extended) "the account's queue, AddAsyncFollowupBackgroundComposer" else "this device's queue, held with a growing pause"})"
                else -> "refused: ${describeError(t)}"
            }
            line("acceptance: NOT accepted after ${now() - sentAt} ms — $path")
            return SendOutcome(decision, sent = true, runId = null, refusal = describeError(t), replyStage = null, echoReconciled = null)
        }
        line("acceptance: run ${run.id} status=${run.status} createdAt=${run.createdAt} named after ${now() - sentAt} ms; the row now reads ${agents.agent(agentId)?.let { if (it.isRunning) "running" else "idle" } ?: "-"}")
        val echoAtOnce = conversations.state(agentId).value.items.filterIsInstance<UserMessage>().lastOrNull()
        line("echo: ${echoAtOnce?.let { "prompt ${it.id} pending=${it.isPending} chars=${it.text.length}" } ?: "no prompt on screen"}")
        follow(conversations, agents, options.followSeconds * 1000L)
        // The record catching up with the echo: the reply's turn from the account, the local copy pruned.
        val caughtUp = awaitState(conversations, 30_000L) { s -> s.items.none { it.id.startsWith("local-") } }
        val s = conversations.state(agentId).value
        val stage = CoordinatorTranscript.messageStage(newestTurn(s.items))
        val replies = newestTurn(s.items).filterIsInstance<AssistantMessage>()
        line("reply: sendMessage=$stage assistantTexts=${replies.size} (${replies.sumOf { it.markdown.length }} chars) footer=${newestTurn(s.items).filterIsInstance<RunFooter>().lastOrNull()?.status ?: "-"} echo=${if (caughtUp) "reconciled with the server's copy" else "still the local copy after 30 s"}")
        return SendOutcome(decision, sent = true, runId = run.id, refusal = null, replyStage = stage, echoReconciled = caughtUp)
    }

    // -- rendering ----------------------------------------------------------------------------------------------------

    private fun renderLoad(load: TranscriptLoadDiagnostics) {
        line("load: source=${load.source} fetched=${load.fetched} at=${load.fetchedAtIso ?: "-"} messages=${load.messages} prompts=${load.prompts} runs=${load.runsLoaded} complete=${load.runsComplete} olderCursor=${load.hasOlderCursor} order=${load.runOrder ?: "-"} window=${load.window} turns=[${load.windowStart},${load.chatTurns})")
        load.record?.let { r -> line("record: total=${r.total} firstStep=${r.firstStep} turnsLoaded=${r.turnsLoaded} turnCount=${r.turnCount ?: "-"} state=${if (r.stateRead) "read" else "-"} empty=${r.empty} error=${r.error ?: "-"}") }
        load.status?.let { st -> line("status: shown=${st.shown} latestRun=${st.latestRun} streaming=${st.streaming} rowRunning=${st.rowRunning} accountRunning=${st.accountRunning} rowNewerThanRecordMs=${st.rowNewerThanRecordMs ?: "-"}") }
        line("traces: queue=${load.traceQueue} inFlight=${load.traceInFlight} worker=${load.traceWorkerRunning} expiredRuns=${load.expiredRuns} expiredBefore=${load.expiredBeforeIso ?: "-"} failed=${load.failedTraces}")
        line("live: run=${load.liveRunId ?: "-"} following=${load.following} stream=${load.liveStream?.let { "events:${it.events},status:${it.status},reconnecting:${it.reconnecting},expired:${it.expired},finished:${it.finished},items:${it.items}" } ?: "none"}")
        line("errors: last=${load.lastError ?: "-"} transcript=${load.transcriptError ?: "-"} transcriptUnavailable=${load.transcriptUnavailable}")
    }

    private fun describe(agent: Agent): String =
        "id=${agent.id} status=${agent.runStatus ?: "-"} running=${agent.isRunning} lifecycle=${agent.lifecycle} latestRun=${agent.latestRunId ?: "-"} updatedAt=${Instant.ofEpochMilli(agent.updatedAtMillis)} isProject=${agent.isProject} isProjectRoot=${agent.isProjectRoot} looksLikeProject=${agent.looksLikeProject} scope=${agent.scope} parent=${agent.parent?.let { "${it.id}/${it.kind}" } ?: "-"} source=${agent.source ?: "-"} env=${agent.envType} model=${agent.modelId ?: "-"}"

    private fun describe(row: TranscriptRow): String = when (row) {
        is TranscriptRow.Item -> "item " + describe(row.item)
        is TranscriptRow.Event -> "event kind=${row.notification.kind} ×${row.count} title=\"${row.notification.title}\""
        is TranscriptRow.Events -> "events count=${row.count} startsOpen=${row.startsOpen} \"${row.summary.text}\""
        is TranscriptRow.Message -> "MESSAGE " + describeMessage(row.call)
        is TranscriptRow.Worker -> "worker ${row.call.name} linked=${row.call.linkedAgentIds.size}"
        is TranscriptRow.Media -> "media calls=${row.group.calls.count { it.hasMedia }}"
        is TranscriptRow.Question -> "question ${row.call.name} answered=${(row.call.payload as? ToolPayload.Question)?.isAnswered}"
        is TranscriptRow.Stretch -> "stretch \"${row.summary.text}\" live=${row.live} entries=${row.entries.size} [${describeEntries(row.entries)}]"
    }

    /** The entries in order, runs of the same kind counted: `note(112ch) call(sendToAgent:completed) footer(FINISHED,12s)×149`. */
    private fun describeEntries(entries: List<TranscriptRow.Entry>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < entries.size) {
            val word = describe(entries[i])
            var j = i + 1
            while (j < entries.size && describe(entries[j]) == word) j++
            out += if (j - i > 1) "$word×${j - i}" else word
            i = j
        }
        return out.joinToString(" ")
    }

    private fun describe(entry: TranscriptRow.Entry): String = when (entry) {
        is TranscriptRow.Entry.Thought -> "thought(${entry.block.text.length}ch)"
        is TranscriptRow.Entry.Call -> "call(${entry.call.name}:${entry.call.status}${if (entry.call.isError) ":error" else ""})"
        is TranscriptRow.Entry.Note -> "note(${entry.message.markdown.length}ch)"
        is TranscriptRow.Entry.Footer -> "footer(${entry.footer.status}${entry.footer.durationMs?.let { ",${it / 1000}s" } ?: ""}${if (entry.interrupted) ",interrupted" else ""})"
        is TranscriptRow.Entry.Line -> "line"
        is TranscriptRow.Entry.Event -> "event(${entry.row.notification.kind})"
        is TranscriptRow.Entry.Events -> "events(${entry.group.count})"
    }

    private fun describe(item: TimelineItem): String = when (item) {
        is UserMessage -> "user id=${item.id} chars=${item.text.length}${if (item.isPending) " pending" else ""}" + (excerpt(item.text)?.let { " \"$it\"" } ?: "")
        is AssistantMessage -> "assistant chars=${item.markdown.length}${if (item.isStreaming) " streaming" else ""}" + (excerpt(item.markdown)?.let { " \"$it\"" } ?: "")
        is SummaryRow -> "summary"
        is NoticeCard -> "notice tone=${item.tone} \"${item.title}\""
        is RunFooter -> "footer status=${item.status} duration=${item.durationMs ?: "-"}"
        is SystemNotification -> "notification kind=${item.kind} title=\"${item.title}\" narration=${item.narration?.length ?: 0}ch"
        is ActivityGroup -> "activity steps=${item.steps.size} calls=[${item.calls.joinToString(",") { it.name }}]"
        else -> item.javaClass.simpleName
    }

    private fun describeMessage(call: ToolCall): String {
        val read = CoordinatorTranscript.reinterpret(call)
        val payload = read.payload as? ToolPayload.CoordinatorMessage
        return "${call.name} status=${call.status}${if (call.isError) "(error)" else ""} chars=${payload?.message?.length ?: 0} missing=${payload?.missing ?: "-"} recovered=${payload?.recovered ?: "-"} truncated=${call.truncated?.args ?: false} id=${tail(call.callId)}" +
            (payload?.message?.let(::excerpt)?.let { " \"$it\"" } ?: "")
    }

    private fun excerpt(text: String): String? {
        if (!options.showText) return null
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= EXCERPT_CHARS) flat else flat.take(EXCERPT_CHARS) + "…"
    }

    private fun describeError(t: Throwable): String {
        val e = t.toCursorError()
        return if (e != null) "HTTP ${e.httpCode} ${e.code}: ${e.message}" else "${t.javaClass.simpleName}: ${t.userMessage()}"
    }

    private fun tail(id: String): String = ProjectDiagnostics.tail(id)

    private fun parseIso(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val MAX_PAGES = 400
        const val MAX_RUN_PAGES = 200
        const val MAX_INDEPENDENT_READS = 200
        const val EXCERPT_CHARS = 80
    }
}
