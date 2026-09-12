package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.FollowupQueueApi
import com.cursorforandroid.data.api.InteractionApi
import com.cursorforandroid.data.api.RunControlApi
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chat's controls on the account, under both settings: with Extended mode off nothing is called and every control
 * answers its named refusal; with it on, each reaches the account, the queue is re-read after every edit, and what
 * the account said is kept for the screens.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SteeringRepositoryTest {

    private class RecordingAccount : InteractionApi, FollowupQueueApi, RunControlApi {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var queue: List<PendingFollowup> = emptyList()
        @Volatile var failing: Throwable? = null
        @Volatile var runId: String? = "run-new"
        @Volatile var resolution = InteractionResolution.SIGNAL_ENQUEUED
        @Volatile var outcome = SteerOutcome.QUEUED

        private fun record(call: String) {
            calls += call
            failing?.let { throw it }
        }

        override suspend fun answerQuestion(agentId: String, toolCallId: String, answers: List<ToolPayload.Question.Answer>): InteractionResolution {
            record("answer:$agentId:$toolCallId:${answers.joinToString("|") { it.questionId + "=" + (it.selectedOptionIds.joinToString(",") + (it.freeformText ?: "")) }}")
            return resolution
        }

        override suspend fun addFollowup(agentId: String, followup: AccountFollowup, synchronous: Boolean): String? {
            record("add:$agentId:${followup.text}:${followup.mode?.name}:${followup.modelId}:$synchronous")
            return runId
        }

        override suspend fun listPending(agentId: String): List<PendingFollowup> {
            record("list:$agentId")
            return queue
        }

        override suspend fun updatePending(agentId: String, followupId: String, text: String) = record("update:$followupId:$text")
        override suspend fun deletePending(agentId: String, followupId: String) = record("delete:$followupId")
        override suspend fun reorderPending(agentId: String, followupId: String, targetFollowupId: String, insertAfter: Boolean) = record("reorder:$followupId:$targetFollowupId:$insertAfter")
        override suspend fun submitPendingNow(agentId: String, followupId: String) = record("now:$followupId")
        override suspend fun markEditing(agentId: String, followupId: String, editing: Boolean) = record("editing:$followupId:$editing")

        override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome {
            record("steer:$agentId:$text:$expectedRunId")
            return outcome
        }

        override suspend fun promoteFollowup(agentId: String, followupId: String, expectedRunId: String?): SteerOutcome {
            record("promote:$followupId:$expectedRunId")
            return outcome
        }

        override suspend fun pause(agentId: String, runId: String?) = record("pause:$agentId:$runId")
        override suspend fun resume(agentId: String) = record("resume:$agentId")
        override suspend fun cancelToolCall(agentId: String, toolCallId: String): Boolean {
            record("cancel:$toolCallId")
            return toolCallId != "call-done"
        }

        override suspend fun wake(agentId: String): Boolean {
            record("wake:$agentId")
            return true
        }
    }

    private val api = FakeCursorApi()
    private val account = RecordingAccount()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private var extended = false
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }
    private val revalidated = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities)

    private fun steering(agents: AgentRepository, pollIntervalMs: Long = 60_000) = SteeringRepository(
        session,
        agents,
        interactions = account,
        queueApi = account,
        runs = account,
        afterAction = { revalidated += it },
        scope = scope,
        pollIntervalMs = pollIntervalMs,
        capabilities = capabilities,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private val answers = listOf(ToolPayload.Question.Answer("q1", listOf("a")))

    @Test
    fun `off, nothing is called and every control answers the named refusal, the queue saying why`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Chat", "run-1")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)

        val refusals = listOf(
            steering.answerQuestion("bc-1", "call-1", answers).exceptionOrNull(),
            steering.sendFollowup("bc-1", AccountFollowup("go")).exceptionOrNull(),
            steering.updatePending("bc-1", "fu-1", "x").exceptionOrNull(),
            steering.deletePending("bc-1", "fu-1").exceptionOrNull(),
            steering.submitPendingNow("bc-1", "fu-1").exceptionOrNull(),
            steering.markEditing("bc-1", "fu-1", true).exceptionOrNull(),
            steering.steer("bc-1", "go").exceptionOrNull(),
            steering.promotePending("bc-1", "fu-1").exceptionOrNull(),
            steering.pause("bc-1").exceptionOrNull(),
            steering.resume("bc-1").exceptionOrNull(),
            steering.cancelToolCall("bc-1", "call-2").exceptionOrNull(),
            steering.wake("bc-1").exceptionOrNull(),
        )
        assertThat(refusals.map { it?.message }.distinct()).containsExactly(SteeringRepository.NEEDS_EXTENDED_MODE)
        steering.refreshQueue("bc-1")
        val state = steering.state("bc-1").value
        assertThat(state.queueLoad).isEqualTo(QueueLoad.Unavailable(SteeringRepository.NEEDS_EXTENDED_MODE))
        assertThat(state.queue).isEmpty()
        assertThat(state.inFlight).isEmpty()
        assertThat(account.calls).isEmpty()
        assertThat(revalidated).isEmpty()
    }

    @Test
    fun `the demo has no account, so every control says so and the queue is unavailable`() = runBlocking<Unit> {
        extended = true
        session.enterDemo()
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)

        assertThat(steering.steer("bc-1", "go").exceptionOrNull()?.message).isEqualTo(SteeringRepository.NOT_IN_DEMO)
        assertThat(steering.answerQuestion("bc-1", "call-1", answers).exceptionOrNull()?.message).isEqualTo(SteeringRepository.NOT_IN_DEMO)
        steering.refreshQueue("bc-1")
        assertThat(steering.state("bc-1").value.queueLoad).isEqualTo(QueueLoad.Unavailable(SteeringRepository.NOT_IN_DEMO))
        assertThat(account.calls).isEmpty()
    }

    @Test
    fun `on, the controls reach the account with the run the row names, and what it said is kept`() = runBlocking<Unit> {
        extended = true
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)

        assertThat(steering.answerQuestion("bc-1", "call-1", answers).getOrNull()).isEqualTo(InteractionResolution.SIGNAL_ENQUEUED)
        assertThat(steering.steer("bc-1", " Use v2 ").getOrNull()).isEqualTo(SteerOutcome.QUEUED)
        assertThat(steering.pause("bc-1").isSuccess).isTrue()
        assertThat(steering.state("bc-1").value.isPaused).isTrue()
        assertThat(steering.resume("bc-1").isSuccess).isTrue()
        assertThat(steering.state("bc-1").value.isPaused).isFalse()
        assertThat(steering.cancelToolCall("bc-1", "call-2").getOrNull()).isTrue()
        assertThat(steering.cancelToolCall("bc-1", "call-done").getOrNull()).isFalse()
        assertThat(steering.wake("bc-1").getOrNull()).isTrue()

        val state = steering.state("bc-1").value
        assertThat(state.answeredCallIds).containsExactly("call-1")
        assertThat(state.lastSteer).isEqualTo(SteerOutcome.QUEUED)
        // Only the cancel the account accepted counts as asked for.
        assertThat(state.cancelledCallIds).containsExactly("call-2")
        assertThat(state.inFlight).isEmpty()
        assertThat(account.calls).containsExactly(
            "answer:bc-1:call-1:q1=a", "steer:bc-1:Use v2:run-9", "pause:bc-1:run-9", "resume:bc-1", "cancel:call-2", "cancel:call-done", "wake:bc-1",
        ).inOrder()
        // An answer and a hold are things the transcript should reflect: the conversation is revalidated after each.
        assertThat(revalidated).containsExactly("bc-1", "bc-1", "bc-1").inOrder()
    }

    @Test
    fun `an answer the account found nobody waiting on is not shown as sent`() = runBlocking<Unit> {
        extended = true
        account.resolution = InteractionResolution.NOT_PAUSED
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)

        assertThat(steering.answerQuestion("bc-1", "call-1", answers).getOrNull()).isEqualTo(InteractionResolution.NOT_PAUSED)
        assertThat(steering.state("bc-1").value.answeredCallIds).isEmpty()
        assertThat(steering.answerQuestion("bc-1", "call-1", emptyList()).exceptionOrNull()).hasMessageThat().isEqualTo("Pick or type an answer first.")
    }

    @Test
    fun `on, the queue is read on attach and again after every edit, and a move names its neighbour`() = runBlocking<Unit> {
        extended = true
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)
        account.queue = listOf(PendingFollowup("fu-1", "First"), PendingFollowup("fu-2", "Second"), PendingFollowup("fu-3", "Third"))

        steering.attach("bc-1")
        val loaded = steering.state("bc-1").first { it.queueLoad == QueueLoad.Loaded }
        assertThat(loaded.queue.map { it.id }).containsExactly("fu-1", "fu-2", "fu-3").inOrder()
        assertThat(account.calls).containsExactly("list:bc-1")

        assertThat(steering.movePending("bc-1", "fu-2", up = true).isSuccess).isTrue()
        assertThat(steering.movePending("bc-1", "fu-2", up = false).isSuccess).isTrue()
        // At the end there is nowhere to go: nothing is asked of the account.
        assertThat(steering.movePending("bc-1", "fu-1", up = true).isSuccess).isTrue()
        assertThat(steering.movePending("bc-1", "fu-9", up = true).exceptionOrNull()).hasMessageThat().contains("no longer queued")
        assertThat(steering.updatePending("bc-1", "fu-1", " First, then tests ").isSuccess).isTrue()
        assertThat(steering.deletePending("bc-1", "fu-3").isSuccess).isTrue()
        assertThat(steering.submitPendingNow("bc-1", "fu-2").isSuccess).isTrue()
        assertThat(steering.markEditing("bc-1", "fu-1", true).isSuccess).isTrue()
        assertThat(steering.promotePending("bc-1", "fu-1").getOrNull()).isEqualTo(SteerOutcome.QUEUED)
        assertThat(steering.sendFollowup("bc-1", AccountFollowup("Queued behind", mode = AgentMode.ASK, modelId = "m")).getOrNull()).isEqualTo("run-new")
        assertThat(steering.sendFollowup("bc-1", AccountFollowup("Now"), now = true).getOrNull()).isEqualTo("run-new")

        assertThat(account.calls).containsExactly(
            "list:bc-1",
            "reorder:fu-2:fu-1:false", "list:bc-1",
            "reorder:fu-2:fu-3:true", "list:bc-1",
            "update:fu-1:First, then tests", "list:bc-1",
            "delete:fu-3", "list:bc-1",
            "now:fu-2", "list:bc-1",
            // The editing flag is a hint to other clients, not a change to the queue: no re-read.
            "editing:fu-1:true",
            "promote:fu-1:run-9", "list:bc-1",
            "add:bc-1:Queued behind:ASK:m:false", "list:bc-1",
            "add:bc-1:Now:null:null:true", "list:bc-1",
        ).inOrder()
        steering.detach("bc-1")
    }

    @Test
    fun `the queue is polled while attached and stops with the last screen`() = runBlocking<Unit> {
        extended = true
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents, pollIntervalMs = 50)

        steering.attach("bc-1")
        steering.attach("bc-1")
        awaitUntil { account.calls.count { it == "list:bc-1" } >= 3 }
        steering.detach("bc-1")
        // One screen still up: the poll goes on.
        val before = account.calls.size
        awaitUntil { account.calls.size > before }
        steering.detach("bc-1")
        delay(150)
        val after = account.calls.size
        delay(150)
        assertThat(account.calls.size).isEqualTo(after)
    }

    @Test
    fun `a private endpoint Cursor changed degrades to the named state, and the failure is the words the screen shows`() = runBlocking<Unit> {
        extended = true
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)

        account.failing = ConnectRpcException(404, null, "Not found")
        steering.refreshQueue("bc-1")
        assertThat(steering.state("bc-1").value.queueLoad).isEqualTo(QueueLoad.Unavailable(SteeringRepository.ENDPOINT_CHANGED))
        assertThat(steering.steer("bc-1", "go").exceptionOrNull()).hasMessageThat().isEqualTo(SteeringRepository.ENDPOINT_CHANGED)

        account.failing = ConnectRpcException(400, "invalid_argument", "followup_id is required")
        assertThat(steering.deletePending("bc-1", "fu-1").exceptionOrNull()).hasMessageThat().isEqualTo("Cursor refused (followup_id is required).")
        // Nothing stays marked in flight after a failure.
        assertThat(steering.state("bc-1").value.inFlight).isEmpty()
    }

    @Test
    fun `turning the mode off empties the queue the screens show and calls nothing more`() = runBlocking<Unit> {
        extended = true
        api.addRunningAgent("bc-1", "Chat", "run-9")
        val agents = agents()
        agents.refresh()
        val steering = steering(agents)
        account.queue = listOf(PendingFollowup("fu-1", "First"))
        steering.refreshQueue("bc-1")
        assertThat(steering.state("bc-1").value.queue).hasSize(1)

        extended = false
        steering.refreshQueue("bc-1")
        val state = steering.state("bc-1").value
        assertThat(state.queue).isEmpty()
        assertThat(state.queueLoad).isEqualTo(QueueLoad.Unavailable(SteeringRepository.NEEDS_EXTENDED_MODE))
        assertThat(steering.submitPendingNow("bc-1", "fu-1").exceptionOrNull()?.message).isEqualTo(SteeringRepository.NEEDS_EXTENDED_MODE)
        assertThat(account.calls).containsExactly("list:bc-1")
    }
}
