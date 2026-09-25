package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.local.CachedLocalPrompt
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.conversation.workingCaption
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A message sent while a chat is mid-turn waits on the queue card above the composer until its own run starts, then
 * moves into the transcript — a Project's coordinator exactly as an ordinary chat, on both transcript engines.
 *
 * A Project's coordinator names the run a queued message will start the moment it takes the message
 * (`AddAsyncFollowupBackgroundComposerResponse.run_id`), lists the message as waiting, and starts that very run once
 * the turn under way is over. The card is still the message's place — with every action the card offers: reorder,
 * edit, delete, steer into the turn, send now — and the transcript takes it in the frame its run starts (Bennett's
 * frame of 2026-09-23 01:40: a Project's queued message drawn in the transcript, no card; "the purpose of a queue is
 * so that it can actually show various options to edit, forward, delete, and more"). Meanwhile the turn under way is
 * the chat's, followed to its end — its last words and its footer drawn — and the named run is neither followed nor
 * streamed: "Starting…" is only ever a started run's word (his frame of 2026-09-22 19:42: the message under
 * "Starting…" while the coordinator was mid-turn, that turn's reply never drawn).
 *
 * At every frame from the tap on each message is in exactly one of the two places, across a restart too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueuedMessageDisplayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt, turns = TURNS)
    private val live get() = turns.last()
    private var recorder: Job? = null

    /** What the screen pairs: the account's queue as last read and the transcript's frame, with when the pair was seen. */
    private class Frame(val controls: ConversationControls, val state: ConversationState, val atNanos: Long) {
        val card: List<PendingFollowup> get() = controls.placed(state.queuePlacement).queue
        fun cardShows(followupId: String) = card.any { it.id == followupId }
        fun transcriptShows(texts: Set<String>) = state.items.any { it is UserMessage && it.text in texts }
    }

    private val frames = CopyOnWriteArrayList<Frame>()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 150L..350L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        // The account's record, served: the Beta engine reads it, Stable never asks.
        server.records[agentId] = turns.flatMap { it.record }
        // The live run's stream delivers what it has and drops; the hub keeps coming back to it for what is added.
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        // The account's list keeps naming a message for two seconds after its run starts.
        server.queueLagMs = 2_000L
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.let { it.steering.detach(agentId); it.close() }
        server.close()
    }

    private fun rig(engine: TranscriptEngine, root: java.io.File = folder.newFolder("rig-${System.nanoTime()}")): FaultRig = FaultRig(server.baseUrl, root, readTimeoutMs = 8_000L, extended = true, engine = engine, queuePollMs = 1_000L).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value
    private val controls: ConversationControls get() = rig!!.steering.state(agentId).value
    private fun card(): List<PendingFollowup> = controls.placed(state.queuePlacement).queue

    private fun explain(label: String) {
        val s = state
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} caption=${s.workingCaption()} placement=${s.queuePlacement}")
        println("   card(raw)=${controls.queue.map { "${it.id}:${it.text.take(16)}" }} card(placed)=${card().map { "${it.id}${it.note?.let { n -> "[$n]" } ?: ""}" }}")
        println("   prompts=${s.items.filterIsInstance<UserMessage>().takeLast(4).map { "${it.id}:${it.text.take(20)}${if (it.isPending) "(pending)" else ""}" }} tail=${s.items.takeLast(6).map { it.id.take(40) }}")
        println("   server: pending=${server.pending[agentId]?.map { "${it.followupId}->${it.runId}@${it.consumedAtMs}" }} delivered=${server.delivered}")
        rig!!.conversations.loadDiagnostics(agentId)?.let { d -> println("   load: source=${d.source} runs=${d.runsLoaded} live=${d.liveRunId} following=${d.following} queued=${d.queued.map { it.text }}") }
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { explain("TIMEOUT waiting for $label"); throw t }
    }

    /** The chat open mid-turn — a Project's coordinator by the account's own list when [project] — its queue read, every frame recorded. */
    private suspend fun FaultRig.open(project: Boolean) {
        server.composers[agentId] = FaultServer.Composer(agentId, LongProject.AGENT_NAME, live.startedAt, running = true, project = project)
        agents.refresh()
        awaitUntilOr(30_000, "the row placed") { agents.agent(agentId)?.isProjectRoot == project }
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder = scope.launch {
            combine(steering.state(agentId), conversations.state(agentId)) { c, s -> c to s }.collect { (c, s) -> frames += Frame(c, s, System.nanoTime()) }
        }
        awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } && controls.isQueueAvailable }
    }

    /**
     * What the composer does mid-turn in Extended mode (`ConversationViewModel.send` → `OutgoingMessages`): the bubble at
     * the tap, the account's follow-up behind it under an id minted here, the queue read once the transcript has settled it.
     */
    private suspend fun FaultRig.sendMidTurn(text: String): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.stageFollowUp(agentId, text)
        conversations.sendStagedVia(agentId, staged, followupId = followupId, discardOnFailure = false) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, followupId = followupId), refresh = false).getOrThrow()
        }.getOrThrow()
        steering.refreshQueue(agentId)
        return followupId
    }

    /** The run the account named for [followupId] when it took it; null when it named none. */
    private fun namedRun(followupId: String): String? = server.pending.getValue(agentId).single { it.followupId == followupId }.runId

    private fun streamed(runId: String): Boolean = server.seen.any { it.route == Route.Stream && it.path.contains("/$runId/") }

    /** The turn under way ends on the server and the account starts the next run on the oldest queued message. */
    private fun endTurnAndDeliver(): RunDto {
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = nextLog())!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        return next
    }

    private fun nextLog(): List<Pair<String, String>> = LongProject.turns(firstAt, turns = TURNS + 1).last().log

    /**
     * The card's row for [followupId] as a chat's card draws it: the account's own row once its list names the message
     * — its words, no word of this device's under it, nothing being edited — the same row a chat shows.
     */
    private fun assertCardRowAsAChat(followupId: String, text: String) {
        val row = card().singleOrNull { it.id == followupId }
        assertWithMessage("the message on the card: ${card().map { it.id }}").that(row).isNotNull()
        assertThat(row!!.text).isEqualTo(text)
        assertThat(row.note).isNull()
        assertThat(row.isEditing).isFalse()
        assertThat(row).isEqualTo(controls.queue.single { it.id == followupId })
    }

    /** Every frame since [since] from the first that shows the message anywhere: in one place, never both, never neither. */
    private fun assertOnePlace(followupId: String, texts: Set<String>, since: Long, until: Long = Long.MAX_VALUE) {
        val seen = frames.filter { it.atNanos in since until until }
        val first = seen.indexOfFirst { it.transcriptShows(texts) || it.cardShows(followupId) }
        assertWithMessage("the message was never shown").that(first).isAtLeast(0)
        seen.drop(first).forEachIndexed { i, f ->
            val card = f.cardShows(followupId)
            val transcript = f.transcriptShows(texts)
            if (card && transcript) {
                val all = seen.drop(first)
                println("== BOTH at frame $i of ${all.size}")
                for (j in maxOf(0, i - 5)..minOf(all.lastIndex, i + 2)) {
                    val g = all[j]
                    println("   frame $j @${(g.atNanos - since) / 1_000_000}ms raw=${g.controls.queue.map { it.id.take(12) }} load=${g.controls.queueLoad} placement=${g.state.queuePlacement} prompts=${g.state.items.filterIsInstance<UserMessage>().takeLast(2).map { "${it.id.take(24)}${if (it.isPending) "(pending)" else ""}" }} active=${g.state.activeRunId}")
                }
            }
            assertWithMessage("frame $i: on the card and in the transcript at once — card=${f.card.map { it.id }} placement=${f.state.queuePlacement}").that(card && transcript).isFalse()
            assertWithMessage("frame $i: in neither place — card=${f.card.map { it.id }} placement=${f.state.queuePlacement}").that(card || transcript).isTrue()
        }
    }

    /** Every frame between [since] and [until], the turn under way still running: never "Starting…", never the chat moved off that turn. */
    private fun assertNeverStartingWhileTheTurnRan(since: Long, until: Long) {
        frames.filter { it.atNanos in since until until }.forEachIndexed { i, f ->
            assertWithMessage("frame $i: \"Starting…\" for a run the account had not started").that(f.state.workingCaption()).isNotEqualTo("Starting…")
            assertWithMessage("frame $i: the chat left the turn under way").that(f.state.activeRunId).isEqualTo(live.runId)
        }
    }

    // -- the card until the message's own run starts: a Project's coordinator and a chat alike ------------------------

    @Test
    fun `Stable - a message to a Project's busy coordinator waits on the card as a chat's does, and moves into the transcript when its own run starts`() = runBlocking<Unit> {
        onTheCardUntilItsRunStarts(TranscriptEngine.STABLE, project = true, named = true)
    }

    @Test
    fun `Beta - a message to a Project's busy coordinator waits on the card as a chat's does, and moves into the transcript when its own run starts`() = runBlocking<Unit> {
        onTheCardUntilItsRunStarts(TranscriptEngine.BETA, project = true, named = true)
    }

    @Test
    fun `Stable - an ordinary chat's message waits on the card although the account names its run, and moves into the transcript when that run starts`() = runBlocking<Unit> {
        onTheCardUntilItsRunStarts(TranscriptEngine.STABLE, project = false, named = true)
    }

    @Test
    fun `Beta - an ordinary chat's message waits on the card although the account names its run, and moves into the transcript when that run starts`() = runBlocking<Unit> {
        onTheCardUntilItsRunStarts(TranscriptEngine.BETA, project = false, named = true)
    }

    @Test
    fun `Stable - an ordinary chat's message the account names no run for waits on the card until it is delivered`() = runBlocking<Unit> {
        onTheCardUntilItsRunStarts(TranscriptEngine.STABLE, project = false, named = false)
    }

    private suspend fun onTheCardUntilItsRunStarts(engine: TranscriptEngine, project: Boolean, named: Boolean) {
        server.namesQueuedRuns = named
        val rig = rig(engine)
        rig.open(project)
        val sentAt = System.nanoTime()
        val followupId = rig.sendMidTurn(MESSAGE)
        val reserved = namedRun(followupId)
        assertThat(reserved != null).isEqualTo(named)
        run {
            val s = state
            // On the card from the account's answer on, from this device's own knowledge until the list names it; not in the transcript.
            assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
            assertThat(card().map { it.id }).contains(followupId)
            // The chat is on the turn under way: its run, its status, its stream — and the row with it.
            assertThat(s.workingCaption()).isEqualTo("Working…")
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(s.runStatus).isEqualTo(RunStatus.RUNNING)
            assertThat(s.isStreaming).isTrue()
            assertThat(rig.conversations.loadDiagnostics(agentId)?.liveRunId).isEqualTo(live.runId)
            assertThat(rig.agents.agent(agentId)?.latestRunId).isEqualTo(live.runId)
            assertThat(rig.agents.agent(agentId)?.runStatus).isNotEqualTo(RunStatus.CREATING)
        }
        rig.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        assertCardRowAsAChat(followupId, MESSAGE)
        // The turn under way goes on, and is drawn: its last words land while the message waits on the card.
        server.logs[live.runId] = server.logs.getValue(live.runId) + ("assistant" to """{"text":"$LAST_WORDS"}""")
        rig.awaitUntilOr(30_000, "the turn's last words") { state.items.any { it is AssistantMessage && it.markdown.contains(LAST_WORDS) } }
        assertThat(state.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        assertCardRowAsAChat(followupId, MESSAGE)
        reserved?.let { assertWithMessage("the named run was streamed before it started").that(streamed(it)).isFalse() }
        val endAt = System.nanoTime()
        // The turn ends; the account starts the next run on the message — the one it named, when it named one.
        val next = endTurnAndDeliver()
        reserved?.let { assertThat(next.id).isEqualTo(it) }
        rig.awaitUntilOr(45_000, "the message filed under its run") { state.items.any { it is UserMessage && it.text == MESSAGE } && state.activeRunId == next.id }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_500)
        val s = state
        // Once, as its run's prompt; the turn it waited behind ended above it, its last words and its footer drawn.
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        val message = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        assertThat(s.items.indexOfFirst { it is AssistantMessage && it.markdown.contains(LAST_WORDS) }).isIn(0 until message)
        assertWithMessage("the turn under way ended above the message: items=${s.items.map { it.id.take(32) }}").that(s.items.indexOfFirst { it is RunFooter && it.runId == live.runId }).isIn(0 until message)
        rig.awaitUntilOr(20_000, "the run's rows") { state.items.drop(message + 1).any { it !is UserMessage && it.id.contains(next.id) } }
        assertThat(card()).isEmpty()
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        assertOnePlace(followupId, setOf(MESSAGE), sentAt)
    }

    // -- queued at the tap: the card from the first frame, never a bubble first ----------------------------------------

    @Test
    fun `Stable - a message to a Project's busy coordinator is on the card from the tap, never a bubble first, though the account's list says idle`() = runBlocking<Unit> {
        queuedAtTheTap(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a message to a Project's busy coordinator is on the card from the tap, never a bubble first, though the account's list says idle`() = runBlocking<Unit> {
        queuedAtTheTap(TranscriptEngine.BETA)
    }

    /**
     * What the composer does now, mid-turn in Extended mode (`ConversationViewModel.send` → `OutgoingMessages` with a
     * queued route): the decision taken at the tap from what the chat shows, and the message on the card before any
     * request — Bennett, 0.4.1: a message to his running coordinator played the send into a bubble, then popped onto
     * the card a round trip later. Here the account's list, a poll behind the coordinator, says the composer is idle.
     */
    private suspend fun queuedAtTheTap(engine: TranscriptEngine) {
        server.namesQueuedRuns = true
        val rig = rig(engine)
        rig.open(project = true)
        server.composers[agentId] = server.composers.getValue(agentId).copy(running = false)
        rig.agents.refresh()
        rig.awaitUntilOr(20_000, "the account's stale word") { rig.agents.runningScan.value.accountWord[agentId]?.running == false }
        val decision = rig.followUps.decide(agentId)
        assertWithMessage("decided at the tap: $decision").that(decision.busy).isTrue()

        val sentAt = System.nanoTime()
        val followupId = AccountFollowup.newId()
        val staged = rig.conversations.queueAhead(agentId, MESSAGE, followupId = followupId)
        // The frame the tap published: on the card, in flight, and nowhere in the transcript.
        assertThat(card().map { it.id }).contains(followupId)
        assertThat(controls.placed(state.queuePlacement).inFlightQueueIds).contains(followupId)
        assertThat(state.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        rig.conversations.sendQueuedVia(agentId, staged, followupId) {
            rig.steering.sendFollowup(agentId, AccountFollowup(text = MESSAGE, followupId = followupId), refresh = false).getOrThrow()
        }.getOrThrow()
        rig.steering.refreshQueue(agentId)
        // Queued: the account holds it, the row takes its actions.
        assertThat(controls.placed(state.queuePlacement).inFlightQueueIds).doesNotContain(followupId)
        rig.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        assertCardRowAsAChat(followupId, MESSAGE)
        assertThat(state.activeRunId).isEqualTo(live.runId)

        val next = endTurnAndDeliver()
        rig.awaitUntilOr(45_000, "the message filed under its run") { state.items.any { it is UserMessage && it.text == MESSAGE } && state.activeRunId == next.id }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_000)
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(card()).isEmpty()
        val bubbled = frames.filter { it.atNanos >= sentAt }.firstOrNull { f -> f.state.items.any { it is UserMessage && it.text == MESSAGE && it.isPending } }
        assertWithMessage("a pending bubble before the card: ${bubbled?.state?.items?.filterIsInstance<UserMessage>()?.map { it.id }}").that(bubbled).isNull()
        assertOnePlace(followupId, setOf(MESSAGE), sentAt)
    }

    // -- every card action in a Project ----------------------------------------------------------------------------------

    @Test
    fun `Stable - a Project's queued messages take every card action, reorder, edit, delete, steer now and send now, each on the card until its own run starts`() = runBlocking<Unit> {
        cardActionsInAProject(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a Project's queued messages take every card action, reorder, edit, delete, steer now and send now, each on the card until its own run starts`() = runBlocking<Unit> {
        cardActionsInAProject(TranscriptEngine.BETA)
    }

    private suspend fun cardActionsInAProject(engine: TranscriptEngine) {
        server.namesQueuedRuns = true
        server.queueLagMs = 1_500L
        val rig = rig(engine)
        rig.open(project = true)
        val sentAt = System.nanoTime()
        val first = rig.sendMidTurn(FIRST)
        val second = rig.sendMidTurn(SECOND)
        val third = rig.sendMidTurn(THIRD)
        // Every send answered: from here each bubble shown at its tap has come down for the card.
        val queuedAt = System.nanoTime()
        val secondRun = namedRun(second)!!
        rig.awaitUntilOr(20_000, "the list to name all three") { controls.queue.map { it.id }.containsAll(listOf(first, second, third)) }
        assertThat(card().map { it.id }).containsExactly(first, second, third).inOrder()
        assertCardRowAsAChat(first, FIRST)
        assertCardRowAsAChat(second, SECOND)
        assertCardRowAsAChat(third, THIRD)
        assertThat(state.items.none { it is UserMessage && it.text in setOf(FIRST, SECOND, THIRD) }).isTrue()

        // Reorder: the third moves up, ahead of the second.
        rig.steering.movePending(agentId, third, up = true).getOrThrow()
        rig.awaitUntilOr(10_000, "the new order") { card().map { it.id } == listOf(first, third, second) }

        // Edit the second, as the card's editor does: marked while it is reworded, its row reading the new words under its id.
        rig.steering.markEditing(agentId, second, editing = true).getOrThrow()
        rig.steering.updatePending(agentId, second, EDITED).getOrThrow()
        rig.steering.markEditing(agentId, second, editing = false).getOrThrow()
        rig.awaitUntilOr(10_000, "the row to read the new words") { card().any { it.id == second && it.text == EDITED } }
        assertThat(card().map { it.id }).containsExactly(first, third, second).inOrder()

        // Delete the third: off the card at once, never "delivering", never in the transcript.
        rig.steering.deletePending(agentId, third).getOrThrow()
        rig.awaitUntilOr(10_000, "the deleted row to go") { card().none { it.id == third } }
        delay(2_500)
        assertWithMessage("a deleted message must not come back").that(card().map { it.id }).containsExactly(first, second).inOrder()
        assertThat(card().none { it.note != null }).isTrue()

        // Steer the first now: delivered into the turn under way, filed once among its rows, off the card. No run starts.
        rig.steering.promotePending(agentId, first).getOrThrow()
        rig.awaitUntilOr(45_000, "the steer filed") { state.items.any { it is UserMessage && it.text == FIRST } && card().none { it.id == first } }
        delay(1_200)
        run {
            val s = state
            assertThat(s.items.count { it is UserMessage && it.text == FIRST }).isEqualTo(1)
            val at = s.items.indexOfFirst { it is UserMessage && it.text == FIRST }
            assertWithMessage("among the running turn's rows").that(s.items.take(at).any { it.id.startsWith("activity-${live.runId}-") }).isTrue()
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(card().map { it.id }).containsExactly(second)
            assertCardRowAsAChat(second, EDITED)
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(secondRun)).isFalse()
        val sendNowAt = System.nanoTime()

        // Send the second now: the account ends the turn under way for it and starts the run it named.
        server.logs[secondRun] = nextLog()
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/$secondRun/")
        rig.steering.submitPendingNow(agentId, second).getOrThrow()
        rig.awaitUntilOr(45_000, "the sent-now run followed with its message") { state.activeRunId == secondRun && state.items.any { it is UserMessage && it.text == EDITED } }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == second } }
        delay(1_500)
        run {
            val s = state
            assertThat(s.items.count { it is UserMessage && it.text == EDITED }).isEqualTo(1)
            assertThat(s.items.none { it is UserMessage && (it.text == SECOND || it.text == THIRD) }).isTrue()
            val at = s.items.indexOfFirst { it is UserMessage && it.text == EDITED }
            rig.awaitUntilOr(20_000, "the run's rows") { state.items.drop(at + 1).any { it !is UserMessage && it.id.contains(secondRun) } }
            assertThat(card()).isEmpty()
        }
        assertNeverStartingWhileTheTurnRan(sentAt, sendNowAt)
        // Each message in one place at every frame; the deleted one on the card alone, then nowhere.
        assertOnePlace(first, setOf(FIRST), sentAt)
        assertOnePlace(second, setOf(SECOND, EDITED), sentAt)
        assertWithMessage("the deleted message was in the transcript").that(frames.filter { it.atNanos >= queuedAt }.none { it.transcriptShows(setOf(THIRD)) }).isTrue()
    }

    // -- a run the list carries while it waits, a restart, an upgrade, an idle Project ---------------------------------

    @Test
    fun `a run the account lists as CREATING while it waits behind the Project's turn is neither followed nor the chat's, the message on the card until it starts`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        server.listsQueuedRuns = true
        val rig = rig(TranscriptEngine.STABLE)
        rig.open(project = true)
        val sentAt = System.nanoTime()
        val followupId = rig.sendMidTurn(MESSAGE)
        val named = namedRun(followupId)!!
        // A load reads the run list, which now carries the named run as CREATING.
        rig.conversations.revalidate(agentId, force = true)
        rig.awaitUntilOr(30_000, "the run list read with the named run") { rig.conversations.loadDiagnostics(agentId)?.runsLoaded == TURNS + 1 && !state.isLoading }
        rig.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        delay(1_500)
        run {
            val s = state
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(s.isStreaming).isTrue()
            assertThat(s.workingCaption()).isEqualTo("Working…")
            assertThat(rig.conversations.loadDiagnostics(agentId)?.liveRunId).isEqualTo(live.runId)
            assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
            assertCardRowAsAChat(followupId, MESSAGE)
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        val endAt = System.nanoTime()
        val next = endTurnAndDeliver()
        assertThat(next.id).isEqualTo(named)
        rig.awaitUntilOr(45_000, "the named run followed with its message") { state.activeRunId == named && state.items.any { it is UserMessage && it.text == MESSAGE } }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_000)
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(card()).isEmpty()
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        assertOnePlace(followupId, setOf(MESSAGE), sentAt)
    }

    @Test
    fun `a message waiting behind a Project's turn is still on the card after the app restarts, and moves into the transcript in one frame when its run starts`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        val root = folder.newFolder("rig-restarted")
        val before = rig(TranscriptEngine.STABLE, root)
        before.open(project = true)
        val followupId = before.sendMidTurn(MESSAGE)
        val named = namedRun(followupId)!!
        before.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        // The process goes; the chat was written to disk when the account took the message.
        recorder?.cancel()
        before.steering.detach(agentId)
        before.close()
        frames.clear()
        val after = rig(TranscriptEngine.STABLE, root)
        after.open(project = true)
        val openedAt = System.nanoTime()
        after.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        delay(1_500)
        run {
            val s = state
            assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
            assertCardRowAsAChat(followupId, MESSAGE)
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(s.workingCaption()).isEqualTo("Working…")
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        val endAt = System.nanoTime()
        endTurnAndDeliver()
        after.awaitUntilOr(45_000, "the named run followed with its message") { state.activeRunId == named && state.items.any { it is UserMessage && it.text == MESSAGE } }
        after.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_000)
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(card()).isEmpty()
        assertNeverStartingWhileTheTurnRan(openedAt, endAt)
        // The restored chat knows the message for its own: the frame its run's prompt appears is the frame the card lets it go.
        assertOnePlace(followupId, setOf(MESSAGE), openedAt)
    }

    @Test
    fun `a Project's message an earlier build kept in the transcript while it waited is on the card after the upgrade, and nothing follows its run`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        val root = folder.newFolder("rig-upgraded")
        val before = rig(TranscriptEngine.STABLE, root)
        before.open(project = true)
        val followupId = before.sendMidTurn(MESSAGE)
        val named = namedRun(followupId)!!
        before.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        val saved = before.conversationCache.read(agentId)!!.value
        recorder?.cancel()
        before.steering.detach(agentId)
        before.close()
        frames.clear()
        // What 0.3.70–0.3.75 wrote instead: the message as a prompt filed under the named run, marked waiting behind the turn.
        val at = LongProject.iso(1_800_000_000_000L)
        val waiting = CachedLocalPrompt(V0ConversationMessageDto("local-1800000000000", "user_message", MESSAGE), RunDto(id = named, agentId = agentId, status = "CREATING", createdAt = at, updatedAt = at), waitsBehind = live.runId)
        before.conversationCache.write(saved.copy(awaiting = emptyList(), local = saved.local + waiting))
        val after = rig(TranscriptEngine.STABLE, root)
        after.open(project = true)
        val openedAt = System.nanoTime()
        after.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        delay(1_500)
        val s = state
        assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
        assertCardRowAsAChat(followupId, MESSAGE)
        assertThat(s.activeRunId).isEqualTo(live.runId)
        assertThat(s.workingCaption()).isEqualTo("Working…")
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        assertNeverStartingWhileTheTurnRan(openedAt, System.nanoTime())
    }

    @Test
    fun `a message to an idle Project starts its run at once, and Starting is that run's word`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        val rig = rig(TranscriptEngine.STABLE)
        rig.open(project = true)
        server.endTurn(agentId)
        // The account's own list says so too: the coordinator is idle.
        server.composers[agentId] = server.composers.getValue(agentId).copy(running = false)
        rig.agents.refresh()
        rig.awaitUntilOr(30_000, "the turn seen over") { state.runStatus?.isActive == false && !state.isStreaming }
        val followupId = rig.sendMidTurn(MESSAGE)
        val run = server.delivered.single { it.first == followupId }.second
        val s = state
        assertThat(s.activeRunId).isEqualTo(run)
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(card().none { it.id == followupId }).isTrue()
    }

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "Yeah, that stop the agent confirmation dialog looks perfect. I permit this."
        const val LAST_WORDS = "I've started a worker on it: Scroll pinning."
        const val FIRST = "First: compare the free tiers."
        const val SECOND = "Second: then the trial lengths."
        const val EDITED = "Second: then the trial lengths, and the annual discounts."
        const val THIRD = "Third: and file both under research."
    }
}
