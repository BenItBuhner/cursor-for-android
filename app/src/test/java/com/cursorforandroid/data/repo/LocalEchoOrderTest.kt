package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Bennett's v0.3.32 frames (`internal/reference/transcript-order-regression-0.3.32{,-b}.png`): in a Project
 * coordinator's chat, his two questions stood at the very bottom of the transcript, each with a one-line stretch
 * under it, while the coordinator's replies to them — and the dozens of injected turns that followed — stood above.
 * The reply before its own prompt.
 *
 * The shape: a prompt sent from the phone is shown at once as a local echo, paired with the run the server names for
 * it, and stays in `Entry.local` until the account's record is seen to hold its turn. The record holds it when the
 * prompt's text is among the newest `local.size + 1` turns; a coordinator's workers report in as injected turns —
 * thirty-nine of them in a minute on his account — so by the next read the user's turn is far from the record's end,
 * the echo is never matched, never pruned, and `recordItems` trails it after every turn the record has, restart
 * after restart. Nothing of the presentation: the items themselves were out of order, whichever path cut them.
 *
 * Here the same chat is run through the repository: the echo, its run streamed to its end, the record catching up
 * with the turn and then the injected turns piling up behind it, a refresh, a restart — and the rows of both
 * presentations (the incremental presenter and the whole one) checked for the one invariant: a reply never precedes
 * the prompt it answers, and a prompt is shown once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalEchoOrderTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A record turn as the account holds it: its prompt (the user's, or Cursor's injected markup), its steps, and its timing. */
    private class Turn(val prompt: String, val steps: List<HeadlessStep>, val startMs: Long, val durationMs: Long = 20_000L)

    /** The account's record of the chat, appended to as the conversation goes on. */
    private class ListRecord : ConversationRecordApi {
        val turns = CopyOnWriteArrayList<Turn>()
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()

        private fun steps(): List<HeadlessStep> = turns.flatMap { t -> listOf(HeadlessStep(userMessage = t.prompt, projectMode = true)) + t.steps + listOf(HeadlessStep(text = ""), HeadlessStep()) }

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            requests += startIndex to limit
            val all = steps()
            val end = minOf(all.size, startIndex + limit)
            return HeadlessPage(if (startIndex >= end) emptyList() else all.subList(startIndex, end), startIndex, all.size)
        }

        override suspend fun state(agentId: String): RecordState =
            RecordState(turns.size, turns.map { TurnTiming(it.durationMs, it.startMs + it.durationMs) }, pendingToolCalls = 0, isRootProject = true, numPriorInteractionUpdates = steps().size.toLong(), rewindEpoch = 0L)
    }

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** After the fake stamps follow-up runs (April 14th 2026 + seconds), so a follow-up's run is the chat's newest. */
    private var now = Instant.parse("2026-04-14T09:00:00Z").toEpochMilli()
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache
    private val agentId = "bc-zenium"
    private val seeded = 12

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"))
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        cache = ConversationCache(disk.child("conversations"))
        traces = TraceCache(disk.child("traces"))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(hub: LiveRunHub, record: ConversationRecordApi) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { Capabilities.EXTENDED },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    // -- the record's steps ----------------------------------------------------------------------------------------

    private fun sendMessage(id: String, text: String): List<HeadlessStep> = listOf(
        HeadlessStep(toolCall = HeadlessToolCall(id, "SendMessage", buildJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(text)) }) }, source = "id=toolCallId name=name args=json")),
        HeadlessStep(toolResult = HeadlessToolResult(id, buildJsonObject { put("success", buildJsonObject { put("messageId", JsonPrimitive(id)) }) })),
    )

    private fun injected(t: Int): String =
        "<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n<task>\nkind: subagent\nstatus: success\ntask_id: bc-w$t\ntitle: Worker $t report\nagent_id: bc-%08d-1a2d-5218-8e2e-7464ea74f671\ndetail: This is the last output of the subagent:\n\nShard $t landed.\n\nAgent ID: bc-%08d-1a2d-5218-8e2e-7464ea74f671\n</task>\n</system_notification>\n<user_query>Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. Don't repeat the same confirmation every time.</user_query>".format(t, t)

    private fun userTurn(t: Int, prompt: String, reply: String, startMs: Long, durationMs: Long = 20_000L) = Turn(prompt, listOf(HeadlessStep(text = "Thinking about it.")) + sendMessage("toolu_send_$t", reply), startMs, durationMs)

    /** The chat before the questions: twelve finished turns, the record holding each, their run logs expired. */
    private suspend fun seed(record: ListRecord) {
        val firstAt = Instant.parse("2026-04-13T00:00:00Z").toEpochMilli()
        val turns = Array(seeded) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Zenium", *turns, firstRunAt = Instant.ofEpochMilli(firstAt).toString())
        agents.refresh()
        for (i in 1..seeded) {
            streamer.emit("run-$i", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit("run-$i", RunStreamEvent.Done)
            record.turns += userTurn(i, "Prompt $i", "Reply $i", firstAt + (i - 1) * 3_600_000L)
        }
    }

    private fun ConversationState.prompts(): List<String> = items.filterIsInstance<UserMessage>().map { it.text }
    private fun ConversationState.replies(): List<String> = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }
        .map { CoordinatorTranscript.reinterpret(it) }.mapNotNull { (it.payload as? ToolPayload.CoordinatorMessage)?.takeUnless { m -> m.missing }?.message }

    /** The message that answers [prompt]: the reply text the streamed and recorded turn carries. */
    private fun replyTo(prompt: String) = "Answer to: $prompt"

    /**
     * The invariant of every frame, in both presentations: each of the user's prompts is shown once, and the reply
     * that answers it comes after it, never before. Injected turns are not prompts and stand where the record puts them.
     */
    private fun assertOrdered(state: ConversationState, presenter: TranscriptPresenter, coordinatorMode: Boolean, prompts: List<String>) {
        val whole = com.cursorforandroid.domain.TranscriptRows.of(
            com.cursorforandroid.domain.GoalTranscript.lift(CoordinatorTranscript.present(state.items, coordinatorMode)), coordinatorMode, runActive = state.isStreaming,
        )
        val incremental = presenter.present(state.items, coordinatorMode, runActive = state.isStreaming).rows
        assertThat(incremental.map { it.key }).isEqualTo(whole.map { it.key })
        // The window shows the newest turns: a prompt whose turn is older than them is a scroll away, not missing.
        for (rows in listOf(whole, incremental)) {
            for (prompt in prompts) {
                val promptAt = rows.indices.filter { i -> (rows[i] as? TranscriptRow.Item)?.item.let { it is UserMessage && it.text == prompt } }
                assertWithMessage("positions of the prompt '${prompt.take(30)}' among ${rows.map { it.key }}").that(promptAt.size).isAtMost(1)
                val replyAt = rows.indexOfFirst { row -> row is TranscriptRow.Message && (row.call.payload as? ToolPayload.CoordinatorMessage)?.message == replyTo(prompt) }
                if (replyAt >= 0) {
                    assertWithMessage("reply to '${prompt.take(30)}' shown at row $replyAt without its prompt among ${rows.map { it.key }}").that(promptAt).hasSize(1)
                    assertWithMessage("reply to '${prompt.take(30)}' at row $replyAt, prompt at row ${promptAt.single()}").that(replyAt).isGreaterThan(promptAt.single())
                }
            }
        }
        // The items themselves, before any presentation: the same order, so the fault can never be the presenter's.
        for (prompt in prompts) {
            val items = state.items
            val promptAt = items.indexOfFirst { it is UserMessage && it.text == prompt }
            val replyAt = items.indexOfFirst { it is ActivityGroup && it.calls.any { c -> (CoordinatorTranscript.reinterpret(c).payload as? ToolPayload.CoordinatorMessage)?.message == replyTo(prompt) } }
            if (replyAt >= 0) assertWithMessage("items: reply to '${prompt.take(30)}' at $replyAt, prompt at $promptAt").that(replyAt).isGreaterThan(promptAt)
            assertWithMessage("items: copies of '${prompt.take(30)}'").that(items.count { it is UserMessage && it.text == prompt }).isAtMost(1)
        }
    }

    /** Every question asked, shown exactly once and before its reply, once the whole chat is on screen. */
    private suspend fun assertWholeChatOrdered(conversations: ConversationRepository, presenter: TranscriptPresenter, prompts: List<String>) {
        var pages = 0
        while (conversations.state(agentId).value.hasOlder && pages++ < 200) {
            awaitUntil { !conversations.state(agentId).value.isLoadingOlder }
            val before = conversations.state(agentId).value.items.size
            conversations.loadOlder(agentId)
            awaitUntil { val s = conversations.state(agentId).value; (s.items.size > before && !s.isLoadingOlder) || !s.hasOlder }
        }
        awaitUntil(10_000) { conversations.state(agentId).value.traceStatus.pending == 0 }
        val state = conversations.state(agentId).value
        assertOrdered(state, presenter, coordinatorMode = true, prompts = prompts)
        for (prompt in prompts) {
            assertWithMessage("whole chat: copies of '${prompt.take(30)}'").that(state.items.count { it is UserMessage && it.text == prompt }).isEqualTo(1)
            assertWithMessage("whole chat: reply to '${prompt.take(30)}'").that(state.items.any { it is ActivityGroup && it.calls.any { c -> (CoordinatorTranscript.reinterpret(c).payload as? ToolPayload.CoordinatorMessage)?.message == replyTo(prompt) } }).isTrue()
        }
    }

    /** The moment the record's copy of the turn for [runId] started: the run's creation, as the fake stamps it. */
    private fun runCreatedAt(runId: String): Long = Instant.parse(api.runs.getValue(runId).createdAt).toEpochMilli()

    /**
     * Workers reporting in after the turn of [afterRunId]: each an injected turn in the record and, as on the account,
     * a finished run in the list, stamped a millisecond apart from that run's creation on (the fake stamps follow-ups
     * a second apart, so they stay in order).
     */
    private fun injectedAfter(record: ListRecord, afterRunId: String, count: Int, firstId: Int) {
        val base = runCreatedAt(afterRunId)
        repeat(count) { i ->
            val at = base + (i + 1)
            val id = "run-injected-${firstId + i}"
            val iso = Instant.ofEpochMilli(at).toString()
            api.runs[id] = com.cursorforandroid.data.api.dto.RunDto(id = id, agentId = agentId, status = "FINISHED", createdAt = iso, updatedAt = Instant.ofEpochMilli(at + 1L).toString(), durationMs = 1L, result = "Logged.")
            record.turns += Turn(injected(firstId + i), listOf(HeadlessStep(text = "Logged; [Worker ${firstId + i}](bc-w${firstId + i}) is on its next shard.")), at, durationMs = 1L)
        }
    }

    /** Sends [text] from the phone, streams the coordinator's turn for it to its end, and answers with the run the server named. */
    private suspend fun ask(conversations: ConversationRepository, text: String): String {
        // The phone's clock moves between two sends, as it does: a local echo is named after the moment it was sent.
        now += 1_000L
        assertThat(conversations.sendFollowUp(agentId, text).isSuccess).isTrue()
        awaitUntil { conversations.state(agentId).value.isStreaming }
        val runId = conversations.state(agentId).value.activeRunId!!
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Thinking("Thinking about it."))
        streamer.emit(runId, RunStreamEvent.ToolCall(SseToolCallDto("$runId-send", "SendMessage", "completed", buildJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(replyTo(text))) }) }, buildJsonObject { put("success", buildJsonObject { put("messageId", JsonPrimitive("m")) }) })))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, null, 20_000L, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { !conversations.state(agentId).value.isStreaming }
        return runId
    }

    @Test
    fun `a question whose turn the record holds behind a wall of injected turns is shown once, before its reply, through refreshes and a restart`() = runBlocking<Unit> {
        val record = ListRecord()
        seed(record)
        val conversations = repository(hub(), record)
        val presenter = TranscriptPresenter()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.prompts().size == 10 }

        // Bennett's first question, streamed to its end. The echo pairs with the server's run; the record has not caught up.
        val q1 = "Also, if we do not have any sort of open source permissive license on this repository yet, we should definitely add one. What do you think will be best?\n\nI still want people to be able to fork it."
        val run1 = ask(conversations, q1)
        assertOrdered(conversations.state(agentId).value, presenter, coordinatorMode = true, prompts = listOf(q1))

        // The record catches up with the turn — and thirty-nine workers report in behind it before anything reads it.
        record.turns += userTurn(100, q1, replyTo(q1), runCreatedAt(run1), durationMs = 10L)
        injectedAfter(record, run1, count = 39, firstId = 200)
        conversations.reload(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.any { it is SystemNotification } && conversations.loadDiagnostics(agentId)?.record?.turnCount == seeded + 1 + 39 }
        awaitUntil(5_000) { conversations.state(agentId).value.let { it.traceStatus.pending == 0 } }
        val refreshed = conversations.state(agentId).value
        println("ORDER after refresh: prompts=${refreshed.prompts().map { it.take(20) }} replies=${refreshed.replies().map { it.take(30) }} hasLocalEcho=${refreshed.items.any { it is UserMessage && it.id.startsWith("local-") }} localRun=$run1")
        assertOrdered(refreshed, presenter, coordinatorMode = true, prompts = listOf(q1))

        // The second question, answered, then more workers behind it; a refresh.
        val q2 = "Let's just only switch to switch the license to Apache 2.0."
        val run2 = ask(conversations, q2)
        assertOrdered(conversations.state(agentId).value, presenter, coordinatorMode = true, prompts = listOf(q1, q2))
        record.turns += userTurn(101, q2, replyTo(q2), runCreatedAt(run2), durationMs = 10L)
        injectedAfter(record, run2, count = 7, firstId = 300)
        conversations.reload(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.count { it is SystemNotification } >= 9 && conversations.loadDiagnostics(agentId)?.record?.turnCount == seeded + 2 + 39 + 7 }
        awaitUntil(5_000) { conversations.state(agentId).value.traceStatus.pending == 0 }
        assertOrdered(conversations.state(agentId).value, presenter, coordinatorMode = true, prompts = listOf(q1, q2))
        assertThat(conversations.loadDiagnostics(agentId)!!.source).isEqualTo("record")

        // A restart on what the disk kept: the same order from the cache, and after the network answers.
        awaitUntil { cache.read(agentId)?.value?.record != null }
        conversations.detach(agentId)
        val next = repository(hub(), record)
        val restartedPresenter = TranscriptPresenter()
        next.attach(agentId)
        awaitUntil { next.state(agentId).value.items.isNotEmpty() }
        assertOrdered(next.state(agentId).value, restartedPresenter, coordinatorMode = true, prompts = listOf(q1, q2))
        awaitUntil { !next.state(agentId).value.isLoading }
        awaitUntil(5_000) { next.state(agentId).value.traceStatus.pending == 0 }
        assertOrdered(next.state(agentId).value, restartedPresenter, coordinatorMode = true, prompts = listOf(q1, q2))
        // Scrolled to the top: both questions once, each before its reply, the record's copies standing for the echoes.
        assertWholeChatOrdered(next, restartedPresenter, listOf(q1, q2))
        assertThat(next.state(agentId).value.items.filterIsInstance<UserMessage>().none { it.id.startsWith("local-") }).isTrue()
    }

    /**
     * The record's copy of a prompt is not always the phone's byte for byte — the server trims, normalises line
     * endings, collapses the blank lines a phone keyboard leaves. Still one prompt, still before its reply.
     */
    @Test
    fun `a record prompt that differs from the echo in whitespace alone is the same prompt`() = runBlocking<Unit> {
        val record = ListRecord()
        seed(record)
        val conversations = repository(hub(), record)
        val presenter = TranscriptPresenter()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.prompts().size == 10 }
        val typed = "Switch the license  to Apache 2.0.\r\n\r\n\r\nKeep the trademark notice.  "
        val run = ask(conversations, typed)
        record.turns += userTurn(100, "Switch the license to Apache 2.0.\n\nKeep the trademark notice.", replyTo(typed), runCreatedAt(run), durationMs = 10L)
        injectedAfter(record, run, count = 5, firstId = 200)
        conversations.reload(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.any { it is SystemNotification } && conversations.loadDiagnostics(agentId)?.record?.turnCount == seeded + 1 + 5 }
        awaitUntil(5_000) { conversations.state(agentId).value.traceStatus.pending == 0 }
        val state = conversations.state(agentId).value
        val copies = state.items.filterIsInstance<UserMessage>().filter { it.text.replace(Regex("\\s+"), " ").trim() == typed.replace(Regex("\\s+"), " ").trim() }
        assertThat(copies).hasSize(1)
        val promptAt = state.items.indexOf(copies.single())
        val replyAt = state.items.indexOfFirst { it is ActivityGroup && it.calls.any { c -> (CoordinatorTranscript.reinterpret(c).payload as? ToolPayload.CoordinatorMessage)?.message == replyTo(typed) } }
        assertThat(replyAt).isGreaterThan(promptAt)
        presenter.present(state.items, coordinatorMode = true, runActive = false)
    }

    /**
     * The invariant over five hundred random orders of the same publications: the echo, its stream, the record
     * catching up with or without injected turns behind it, refreshes and growth reads interleaved — however the
     * publications land, no reply precedes its prompt and no prompt is shown twice.
     */
    @Test
    fun `no reply precedes its prompt across 500 randomized publication orders`() = runBlocking<Unit> {
        val random = Random(20260917)
        val record = ListRecord()
        seed(record)
        val conversations = repository(hub(), record)
        val presenter = TranscriptPresenter()
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.prompts().size == 10 }
        val asked = ArrayList<String>()
        val pendingTurns = ArrayList<Pair<String, String>>()
        var lastRun = "run-$seeded"
        var injectedIds = 2000
        repeat(500) { round ->
            when (random.nextInt(5)) {
                0 -> if (asked.size < 6 && pendingTurns.isEmpty()) {
                    // A question from the phone, streamed to its end; the record has not caught up yet.
                    val q = "Question ${asked.size + 1} of round $round: what about the ${listOf("license", "trademark", "DCO", "release", "renders")[random.nextInt(5)]}?"
                    lastRun = ask(conversations, q)
                    asked += q
                    pendingTurns += q to lastRun
                }
                1 -> if (pendingTurns.isNotEmpty()) {
                    // The record catches up with the question, injected turns behind it or not.
                    val (q, run) = pendingTurns.removeAt(0)
                    record.turns += userTurn(1000 + round, q, replyTo(q), runCreatedAt(run), durationMs = 10L)
                    val count = random.nextInt(6)
                    injectedAfter(record, run, count, injectedIds)
                    injectedIds += count
                }
                2 -> if (pendingTurns.isEmpty()) {
                    // Workers report in with no question of the user's between them.
                    val count = 1 + random.nextInt(3)
                    injectedAfter(record, lastRun, count, injectedIds)
                    injectedIds += count
                    lastRun = "run-injected-${injectedIds - 1}"
                }
                3 -> {
                    conversations.reload(agentId)
                    awaitUntil { !conversations.state(agentId).value.isLoading && conversations.loadDiagnostics(agentId)?.record?.turnCount == record.turns.size }
                    awaitUntil(5_000) { conversations.state(agentId).value.traceStatus.pending == 0 }
                }
                else -> Unit
            }
            delay(random.nextLong(0, 15))
            assertOrdered(conversations.state(agentId).value, presenter, coordinatorMode = true, prompts = asked)
        }
        assertThat(asked.size).isAtLeast(4)
        // The record catches up with whatever it had not, and the whole chat is paged in: every question once, before its reply.
        pendingTurns.forEach { (q, run) -> record.turns += userTurn(1900 + asked.indexOf(q), q, replyTo(q), runCreatedAt(run), durationMs = 10L) }
        conversations.reload(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.loadDiagnostics(agentId)?.record?.turnCount == record.turns.size }
        awaitUntil(5_000) { conversations.state(agentId).value.traceStatus.pending == 0 }
        assertOrdered(conversations.state(agentId).value, presenter, coordinatorMode = true, prompts = asked)
        assertWholeChatOrdered(conversations, presenter, asked)
    }
}
