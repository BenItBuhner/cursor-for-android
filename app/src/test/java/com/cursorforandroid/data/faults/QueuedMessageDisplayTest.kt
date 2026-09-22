package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.QueuePlacement
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
 * Where a message sent while a chat is mid-turn is shown, by what the account answers and what Cursor's desktop
 * (3.21.18) shows, on both transcript engines:
 *
 *  - A Project's coordinator: the account names the run the message will start the moment it takes the message
 *    (`AddAsyncFollowupBackgroundComposerResponse.run_id`), lists the message as waiting, and starts that very run once
 *    the turn under way is over. The desktop shows a message sent to a busy Project in the conversation straight away;
 *    so does the app — in the transcript from the tap, never on the card — reading "Queued · runs after the current
 *    turn" until its run starts, the turn under way followed to its end (its last words and its footer drawn above the
 *    message) and the named run neither followed nor streamed before then. Bennett's frame of 2026-09-22 19:42: his
 *    message to this very Project under "Starting…" while the coordinator was mid-turn, that turn's reply never drawn.
 *  - An ordinary chat: the desktop keeps the message on the queue tray until it is delivered; the app keeps it on the
 *    card, whether or not the account names a run for it, and the transcript takes it the moment its run starts.
 *
 * At every frame from the tap on each message is in exactly one of the two places, and "Starting…" never stands for a
 * run the account has not started.
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
        fun cardShows(followupId: String) = controls.placed(state.queuePlacement).queue.any { it.id == followupId }
        fun transcriptShows(text: String) = state.items.any { it is UserMessage && it.text == text }
        fun filed(text: String) = state.items.any { it is UserMessage && it.text == text && !it.isPending }
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

    private fun explain(label: String) {
        val s = state
        println("== $label: items=${s.items.size} run=${s.runStatus} streaming=${s.isStreaming} active=${s.activeRunId} queuedBehind=${s.queuedBehindTurn} caption=${s.workingCaption()} placement=${s.queuePlacement}")
        println("   card(raw)=${controls.queue.map { it.id }} card(placed)=${controls.placed(s.queuePlacement).queue.map { it.id }}")
        println("   prompts=${s.items.filterIsInstance<UserMessage>().takeLast(4).map { "${it.id}:${it.text.take(20)}${if (it.isPending) "(pending)" else ""}" }} tail=${s.items.takeLast(6).map { it.id.take(40) }}")
        println("   server: pending=${server.pending[agentId]?.map { "${it.followupId}->${it.runId}@${it.consumedAtMs}" }} delivered=${server.delivered}")
        rig!!.conversations.loadDiagnostics(agentId)?.let { d -> println("   load: source=${d.source} runs=${d.runsLoaded} live=${d.liveRunId} following=${d.following}") }
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

    private fun streamed(runId: String): Boolean = server.seen.any { it.route == Route.Stream && it.path.contains("/$runId/") }

    /** The turn under way ends on the server and the account starts the next run on the oldest queued message. */
    private fun endTurnAndDeliver(): RunDto {
        server.endTurn(agentId)
        val next = server.deliverNext(agentId, log = LongProject.turns(firstAt, turns = TURNS + 1).last().log)!!
        server.outage(Route.Stream, Fault.StreamCut(events = 3), path = "/${next.id}/")
        return next
    }

    /** Every frame since [sentAt] from the first that shows the message anywhere: in one place, never both, never neither. */
    private fun assertOnePlace(followupId: String, sentAt: Long) {
        val since = frames.filter { it.atNanos >= sentAt }
        val first = since.indexOfFirst { it.transcriptShows(MESSAGE) || it.cardShows(followupId) }
        assertWithMessage("the message was never shown").that(first).isAtLeast(0)
        since.drop(first).forEachIndexed { i, f ->
            val card = f.cardShows(followupId)
            val transcript = f.transcriptShows(MESSAGE)
            assertWithMessage("frame $i: on the card and in the transcript at once — placement=${f.state.queuePlacement}").that(card && transcript).isFalse()
            assertWithMessage("frame $i: in neither place — placement=${f.state.queuePlacement}").that(card || transcript).isTrue()
        }
    }

    /** Every frame between [sentAt] and [endAt], the turn under way still running: never "Starting…", never the chat moved off that turn. */
    private fun assertNeverStartingWhileTheTurnRan(sentAt: Long, endAt: Long) {
        frames.filter { it.atNanos in sentAt until endAt }.forEachIndexed { i, f ->
            assertWithMessage("frame $i: \"Starting…\" for a run the account had not started").that(f.state.workingCaption()).isNotEqualTo("Starting…")
            assertWithMessage("frame $i: the chat left the turn under way").that(f.state.activeRunId).isEqualTo(live.runId)
        }
    }

    // -- a Project's coordinator mid-turn: the account names the run at once --------------------------------------------

    @Test
    fun `Stable - a message to a Project's busy coordinator stands in the transcript at once, queued behind the turn, which stays followed to its end`() = runBlocking<Unit> {
        projectMessageQueuedBehindTheTurn(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a message to a Project's busy coordinator stands in the transcript at once, queued behind the turn, which stays followed to its end`() = runBlocking<Unit> {
        projectMessageQueuedBehindTheTurn(TranscriptEngine.BETA)
    }

    private suspend fun projectMessageQueuedBehindTheTurn(engine: TranscriptEngine) {
        server.namesQueuedRuns = true
        val rig = rig(engine)
        rig.open(project = true)
        val sentAt = System.nanoTime()
        val followupId = rig.sendMidTurn(MESSAGE)
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        run {
            val s = state
            // In the transcript at once, the newest thing in it, the account's: not pending, not on the card.
            val at = s.items.indexOfLast { it is UserMessage && it.text == MESSAGE }
            assertWithMessage("the message in the transcript").that(at).isAtLeast(0)
            assertThat((s.items[at] as UserMessage).isPending).isFalse()
            assertThat(at).isEqualTo(s.items.lastIndex)
            // Queued behind the turn, said so: its run has not started.
            assertThat(s.queuedBehindTurn).isTrue()
            assertThat(s.workingCaption()).isEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
            // The chat is on the turn under way: its run, its status, its stream.
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(s.runStatus).isEqualTo(RunStatus.RUNNING)
            assertThat(s.isStreaming).isTrue()
            assertThat(rig.conversations.loadDiagnostics(agentId)?.liveRunId).isEqualTo(live.runId)
            // And so is the row: no "Starting" in the sidebar for a run nothing has started.
            assertThat(rig.agents.agent(agentId)?.latestRunId).isEqualTo(live.runId)
            assertThat(rig.agents.agent(agentId)?.runStatus).isNotEqualTo(RunStatus.CREATING)
        }
        // The account lists it as waiting; the card leaves it out, the transcript being its place.
        rig.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        assertThat(controls.placed(state.queuePlacement).queue.none { it.id == followupId }).isTrue()
        // The turn under way goes on, and is drawn: its last words land above the message.
        server.logs[live.runId] = server.logs.getValue(live.runId) + ("assistant" to """{"text":"$LAST_WORDS"}""")
        rig.awaitUntilOr(30_000, "the turn's last words") { state.items.any { it is AssistantMessage && it.markdown.contains(LAST_WORDS) } }
        run {
            val s = state
            val words = s.items.indexOfFirst { it is AssistantMessage && it.markdown.contains(LAST_WORDS) }
            assertThat(words).isLessThan(s.items.indexOfLast { it is UserMessage && it.text == MESSAGE })
            assertThat(s.queuedBehindTurn).isTrue()
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        val endAt = System.nanoTime()
        // The turn ends; the account starts the run it named on the message.
        val next = endTurnAndDeliver()
        assertThat(next.id).isEqualTo(named)
        rig.awaitUntilOr(45_000, "the named run followed") { state.activeRunId == named && !state.queuedBehindTurn && state.isStreaming }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_500)
        val s = state
        // Once, as its run's prompt; the turn it waited behind ended above it, with its footer.
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        val message = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        val footer = s.items.indexOfFirst { it is RunFooter && it.runId == live.runId }
        assertWithMessage("the turn under way ended above the message: items=${s.items.map { it.id.take(32) }}").that(footer).isIn(0 until message)
        assertThat(s.items.drop(message + 1).firstOrNull { it !is UserMessage }?.id).contains(named)
        assertThat(s.workingCaption()).isNotEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
        assertThat(controls.placed(s.queuePlacement).queue).isEmpty()
        // From the tap: in the transcript, never on the card; filed, it read as queued for as long as the turn ran.
        val since = frames.filter { it.atNanos >= sentAt }
        val first = since.indexOfFirst { it.transcriptShows(MESSAGE) }
        assertWithMessage("the recorder never saw the message").that(first).isAtLeast(0)
        since.drop(first).forEachIndexed { i, f ->
            assertWithMessage("frame $i: on the card").that(f.cardShows(followupId)).isFalse()
            assertWithMessage("frame $i: not in the transcript").that(f.transcriptShows(MESSAGE)).isTrue()
            if (f.atNanos < endAt && f.filed(MESSAGE)) assertWithMessage("frame $i: the caption under the queued message").that(f.state.workingCaption()).isEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
        }
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        assertOnePlace(followupId, sentAt)
    }

    @Test
    fun `a run the account lists as CREATING while it waits behind the Project's turn is neither followed nor the chat's until the turn ends`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        server.listsQueuedRuns = true
        val rig = rig(TranscriptEngine.STABLE)
        rig.open(project = true)
        val sentAt = System.nanoTime()
        val followupId = rig.sendMidTurn(MESSAGE)
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        // A load reads the run list, which now carries the named run as CREATING.
        rig.conversations.revalidate(agentId, force = true)
        rig.awaitUntilOr(30_000, "the run list read with the named run") { rig.conversations.loadDiagnostics(agentId)?.runsLoaded == TURNS + 1 && !state.isLoading }
        delay(1_500)
        run {
            val s = state
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(s.isStreaming).isTrue()
            assertThat(s.queuedBehindTurn).isTrue()
            assertThat(s.workingCaption()).isEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
            assertThat(rig.conversations.loadDiagnostics(agentId)?.liveRunId).isEqualTo(live.runId)
            assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
            assertThat(s.items.indexOfLast { it is UserMessage && it.text == MESSAGE }).isEqualTo(s.items.lastIndex)
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        val endAt = System.nanoTime()
        val next = endTurnAndDeliver()
        assertThat(next.id).isEqualTo(named)
        rig.awaitUntilOr(45_000, "the named run followed") { state.activeRunId == named && !state.queuedBehindTurn && state.isStreaming }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_000)
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        assertOnePlace(followupId, sentAt)
    }

    @Test
    fun `a message waiting behind a Project's turn is still in the transcript, queued and off the card, after the app restarts`() = runBlocking<Unit> {
        server.namesQueuedRuns = true
        val root = folder.newFolder("rig-restarted")
        val before = rig(TranscriptEngine.STABLE, root)
        before.open(project = true)
        val followupId = before.sendMidTurn(MESSAGE)
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        before.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        // The process goes; the chat was written to disk when the account took the message.
        recorder?.cancel()
        before.steering.detach(agentId)
        before.close()
        frames.clear()
        val after = rig(TranscriptEngine.STABLE, root)
        after.open(project = true)
        // From the first frame of the reopened chat on its turn.
        val sentAt = System.nanoTime()
        after.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        delay(1_500)
        run {
            val s = state
            assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
            assertThat(s.items.indexOfLast { it is UserMessage && it.text == MESSAGE }).isEqualTo(s.items.lastIndex)
            assertThat(s.queuedBehindTurn).isTrue()
            assertThat(s.workingCaption()).isEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(controls.placed(s.queuePlacement).queue.none { it.id == followupId }).isTrue()
        }
        assertWithMessage("the named run was streamed before it started").that(streamed(named)).isFalse()
        val endAt = System.nanoTime()
        endTurnAndDeliver()
        after.awaitUntilOr(45_000, "the named run followed") { state.activeRunId == named && !state.queuedBehindTurn && state.isStreaming }
        assertThat(state.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        frames.filter { it.atNanos in sentAt until endAt }.forEachIndexed { i, f ->
            if (f.transcriptShows(MESSAGE)) assertWithMessage("frame $i: on the card beside the transcript's copy").that(f.cardShows(followupId)).isFalse()
        }
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
        assertThat(s.queuedBehindTurn).isFalse()
        assertThat(s.activeRunId).isEqualTo(run)
        assertThat(s.workingCaption()).isNotEqualTo(QueuePlacement.QUEUED_BEHIND_TURN)
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertThat(controls.placed(s.queuePlacement).queue.none { it.id == followupId }).isTrue()
    }

    // -- an ordinary chat mid-turn: the card until the run starts --------------------------------------------------------

    @Test
    fun `Stable - an ordinary chat's message waits on the card although the account names its run, and moves into the transcript when that run starts`() = runBlocking<Unit> {
        chatMessageOnTheCard(TranscriptEngine.STABLE, named = true)
    }

    @Test
    fun `Beta - an ordinary chat's message waits on the card although the account names its run, and moves into the transcript when that run starts`() = runBlocking<Unit> {
        chatMessageOnTheCard(TranscriptEngine.BETA, named = true)
    }

    @Test
    fun `Stable - an ordinary chat's message the account names no run for waits on the card until it is delivered`() = runBlocking<Unit> {
        chatMessageOnTheCard(TranscriptEngine.STABLE, named = false)
    }

    private suspend fun chatMessageOnTheCard(engine: TranscriptEngine, named: Boolean) {
        server.namesQueuedRuns = named
        val rig = rig(engine)
        rig.open(project = false)
        val sentAt = System.nanoTime()
        val followupId = rig.sendMidTurn(MESSAGE)
        val reserved = server.pending.getValue(agentId).single { it.followupId == followupId }.runId
        assertThat(reserved != null).isEqualTo(named)
        run {
            val s = state
            // On the card, from this device's own knowledge until the list names it; not in the transcript.
            assertThat(s.items.none { it is UserMessage && it.text == MESSAGE }).isTrue()
            assertThat(controls.placed(s.queuePlacement).queue.map { it.id }).contains(followupId)
            assertThat(s.queuedBehindTurn).isFalse()
            assertThat(s.workingCaption()).isEqualTo("Working…")
            assertThat(s.activeRunId).isEqualTo(live.runId)
            assertThat(rig.agents.agent(agentId)?.latestRunId).isEqualTo(live.runId)
            assertThat(rig.agents.agent(agentId)?.runStatus).isNotEqualTo(RunStatus.CREATING)
        }
        rig.awaitUntilOr(10_000, "the list to name it") { controls.queue.any { it.id == followupId } }
        assertThat(controls.placed(state.queuePlacement).queue.map { it.id }).contains(followupId)
        reserved?.let { assertWithMessage("the named run was streamed before it started").that(streamed(it)).isFalse() }
        val endAt = System.nanoTime()
        val next = endTurnAndDeliver()
        reserved?.let { assertThat(next.id).isEqualTo(it) }
        rig.awaitUntilOr(45_000, "the message filed under its run") { state.items.any { it is UserMessage && it.text == MESSAGE } && state.activeRunId == next.id }
        rig.awaitUntilOr(20_000, "the list to let it go") { controls.queue.none { it.id == followupId } }
        delay(1_500)
        val s = state
        assertThat(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        val at = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        rig.awaitUntilOr(20_000, "the run's rows") { state.items.drop(at + 1).any { it !is UserMessage && it.id.contains(next.id) } }
        assertThat(controls.placed(s.queuePlacement).queue).isEmpty()
        assertNeverStartingWhileTheTurnRan(sentAt, endAt)
        assertOnePlace(followupId, sentAt)
    }

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "Yeah, that stop the agent confirmation dialog looks perfect. I permit this."
        const val LAST_WORDS = "I've started a worker on it: Scroll pinning."
    }
}
