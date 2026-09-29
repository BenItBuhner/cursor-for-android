package com.cursorforandroid

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a launch starts for the first screen before any screen asks: a session restored signed in has its list read
 * and fetched from the graph, once, and handed to the sidebar that comes up; the demo, a first-run choice still owed
 * and a signed-out launch start nothing, and a sign-out takes the handle with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StartupHandOffTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val keyStore = SecureKeyStore(app, openRetryDelayMs = 0) { app.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }
    private val api = FakeCursorApi().apply { addIdleAgent("bc-1", "Fix the login bug", "run-1") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun pinClock() {
        AppClock.nowMillis = { 1_776_200_000_000L }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun graph() = AppGraph(app, keyStore, real = CursorBackend(api, FakeRunStreamer(), isDemo = false))

    /** An account this device signed in before: its key, how it signed in, who it is, and the upgrade step behind it. */
    private fun signedInBefore(graph: AppGraph) = runBlocking {
        keyStore.setApiKey("key_stored")
        graph.prefs.setCredentialInfo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
        graph.prefs.setCachedUser(CursorUser("u1", "dev@example.com", "Dev", "Eloper", 1L))
        graph.prefs.setExtendedModeIntroduced(noticePending = false)
    }

    private fun awaitStartupFetch(graph: AppGraph): Job {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            graph.takeStartupListFetch()?.let { return it }
            Thread.sleep(5)
        }
        error("the launch never started the list fetch")
    }

    /** Long enough for a hand-off that was going to start something to have started it. */
    private fun settle(graph: AppGraph, start: Job) = runBlocking {
        start.join()
        Thread.sleep(300)
        assertThat(graph.session.state.value).isNotInstanceOf(SessionState.Loading::class.java)
    }

    @Test
    fun `a restored session has its list fetched by the launch, handed to the first sidebar only`() = runBlocking<Unit> {
        val graph = graph()
        signedInBefore(graph)

        graph.startSession(scope)
        val fetch = awaitStartupFetch(graph)
        fetch.join()

        assertThat(graph.agents.state.value.agents.map { it.id }).containsExactly("bc-1")
        assertThat(api.listAgentsCalls).isEqualTo(1)
        // The sidebar that comes up next joins it; any later one fetches for itself.
        assertThat(graph.takeStartupListFetch()).isNull()
        // Nothing of Extended mode is built for an account without it.
        assertThat(graph.builtParts()).doesNotContain("sessionTokens")
    }

    @Test
    fun `asking again, as the root does after the activity, starts nothing twice`() = runBlocking<Unit> {
        val graph = graph()
        signedInBefore(graph)

        val first = graph.startSession(scope)
        assertThat(graph.startSession(scope)).isSameInstanceAs(first)
        awaitStartupFetch(graph).join()
        settle(graph, first)

        assertThat(api.listAgentsCalls).isEqualTo(1)
        assertThat(graph.takeStartupListFetch()).isNull()
    }

    @Test
    fun `the demo starts nothing`() {
        val graph = graph()
        runBlocking { graph.session.enterDemo() }

        settle(graph, graph.startSession(scope))

        assertThat(graph.takeStartupListFetch()).isNull()
        assertThat(graph.builtParts()).containsNoneOf("agents", "pullRequests")
    }

    @Test
    fun `a first-run choice still owed starts nothing`() {
        val graph = graph()
        signedInBefore(graph)
        runBlocking { graph.prefs.setModeChoicePending(true) }

        settle(graph, graph.startSession(scope))

        assertThat(graph.session.state.value).isInstanceOf(SessionState.SignedIn::class.java)
        assertThat(graph.takeStartupListFetch()).isNull()
        assertThat(graph.builtParts()).containsNoneOf("agents", "pullRequests")
        assertThat(api.listAgentsCalls).isEqualTo(0)
    }

    @Test
    fun `a launch with no account starts nothing`() {
        val graph = graph()

        settle(graph, graph.startSession(scope))

        assertThat(graph.session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(graph.takeStartupListFetch()).isNull()
        assertThat(graph.builtParts()).containsNoneOf("agents", "pullRequests")
    }

    @Test
    fun `a sign-out takes the launch's fetch with it`() = runBlocking<Unit> {
        val graph = graph()
        signedInBefore(graph)
        graph.startSession(scope)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (api.listAgentsCalls == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertThat(api.listAgentsCalls).isEqualTo(1)

        graph.signOut()

        assertThat(graph.takeStartupListFetch()).isNull()
        assertThat(graph.agents.state.value.agents).isEmpty()
    }
}
