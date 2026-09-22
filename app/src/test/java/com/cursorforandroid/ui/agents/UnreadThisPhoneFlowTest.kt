package com.cursorforandroid.ui.agents

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.LaunchRequest
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.ui.conversation.ConversationViewModel
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Settings › "Unread only for chats from this phone" end to end, on a signed-in account whose chats were started on
 * the laptop: the sidebar's rows as the app composes them (the account's list, this phone's settings), a chat opened
 * through its real screen model, a chat launched through the real launch. Nothing is marked on the account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UnreadThisPhoneFlowTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val api = FakeCursorApi()
    private lateinit var graph: AppGraph
    private val stores = ArrayList<ViewModelStore>()

    @Before
    fun setUp() = runBlocking {
        AppClock.nowMillis = { NOW }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>(), real = CursorBackend(api, FakeRunStreamer(), isDemo = false))
        graph.session.signIn("key_abc").getOrThrow()
        api.addIdleAgent(LAPTOP, "Refactor billing", "run-laptop", createdAt = "2026-04-13T17:00:00.000Z")
        api.addIdleAgent(ELSEWHERE, "Fix the flaky upload test", "run-elsewhere", createdAt = "2026-04-13T18:00:00.000Z")
        graph.agents.refresh()
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** The ids the sidebar would draw with an unread dot: every row, children included. */
    private suspend fun unread(): Set<String> {
        val sections = AgentListOrganizer.organize(graph.agents.state.value.agents, ListPreferences(), graph.prefs.localAgentState.first(), nowMillis = NOW)
        return sections.flatMap { section -> section.rows.flatMap { listOf(it) + it.descendants() } }.filter { it.isUnread }.mapTo(HashSet()) { it.agent.id }
    }

    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** The chat's screen opened on this phone, as a row tap, the widget or a notification opens it. */
    private fun open(agentId: String) {
        val store = ViewModelStore().also { stores += it }
        ViewModelProvider(store, ConversationViewModel.Factory(graph, agentId))[ConversationViewModel::class.java]
    }

    /** The chat's screen closed again. */
    private fun closeAll() {
        stores.forEach { it.clear() }
        stores.clear()
    }

    /** The account moves [agentId] on (the laptop sends it a message, its run finishes) at [at]. */
    private fun accountMovesOn(agentId: String, at: String) {
        val agent = api.agents.getValue(agentId)
        api.agents[agentId] = agent.copy(status = "IDLE", updatedAt = at)
        agent.latestRunId?.let { runId -> api.runs[runId]?.let { api.runs[runId] = it.copy(status = "FINISHED", updatedAt = at) } }
    }

    @Test
    fun `with the switch on chats started elsewhere show no dot, with it off they show today's`() = runBlocking {
        assertThat(graph.prefs.unreadOnlyTouchedHere.first()).isTrue()
        assertThat(unread()).isEmpty()

        graph.prefs.setUnreadOnlyTouchedHere(false)
        awaitUntil("the switch off") { unread() == setOf(LAPTOP, ELSEWHERE) }

        graph.prefs.setUnreadOnlyTouchedHere(true)
        awaitUntil("the switch on") { unread().isEmpty() }
        // The switch wrote nothing but itself: no read marker, no touched chat, in either direction.
        val local = graph.prefs.localAgentState.first()
        assertThat(local.readMarkers).isEmpty()
        assertThat(local.touchedHereIds).isEmpty()
    }

    @Test
    fun `a chat started elsewhere and then opened here shows as unread when it moves on, as it does today`() = runBlocking {
        assertThat(unread()).isEmpty()

        open(ELSEWHERE)
        awaitUntil("the open to touch the chat") { ELSEWHERE in graph.prefs.localAgentState.first().touchedHereIds }
        awaitUntil("the open to read the chat") { graph.prefs.localAgentState.first().readMarkers.containsKey(ELSEWHERE) }
        assertThat(unread()).isEmpty()
        closeAll()

        // The laptop sends it on; the phone's next look at the list shows the dot, on that chat alone.
        accountMovesOn(ELSEWHERE, "2026-04-13T20:00:00.000Z")
        accountMovesOn(LAPTOP, "2026-04-13T20:05:00.000Z")
        graph.agents.refresh()
        awaitUntil("the opened chat to read unread") { unread() == setOf(ELSEWHERE) }

        // Exactly the dot the switch off shows for it, beside the untouched chat's.
        graph.prefs.setUnreadOnlyTouchedHere(false)
        awaitUntil("the switch off") { unread() == setOf(ELSEWHERE, LAPTOP) }
    }

    @Test
    fun `a chat started here shows as unread when its run finishes`() = runBlocking {
        val request = LaunchRequest(prompt = "Ship the release notes", repoUrl = "https://github.com/acme/app", ref = "main", modelId = null, modelParams = emptyList(), autoCreatePr = false, planMode = false)
        val launched = graph.agents.launch(request, null).getOrThrow().agent
        val local = graph.prefs.localAgentState.first()
        assertThat(local.launchedHereIds).contains(launched.id)
        assertThat(local.touchedHereIds).contains(launched.id)
        assertThat(unread()).isEmpty()

        accountMovesOn(launched.id, "2026-04-13T20:00:00.000Z")
        accountMovesOn(LAPTOP, "2026-04-13T20:05:00.000Z")
        graph.agents.refresh()
        awaitUntil("the finished launch to read unread") { unread() == setOf(launched.id) }
    }

    private companion object {
        const val LAPTOP = "bc-laptop"
        const val ELSEWHERE = "bc-elsewhere"
        val NOW: Long = Instant.parse("2026-04-13T19:00:00Z").toEpochMilli()
    }
}
