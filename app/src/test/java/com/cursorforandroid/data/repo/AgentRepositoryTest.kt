package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric only because [SessionManager] needs a Context for its stores. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryTest {

    private val demoApi = FakeCursorApi()
    private val realApi = FakeCursorApi()
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesStore(context)
        val real = CursorBackend(realApi, FakeRunStreamer(), isDemo = false)
        val demo = CursorBackend(demoApi, FakeRunStreamer(), isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, real, demo)
        session.enterDemo()
        agents = AgentRepository(session, prefs)
        demoApi.addRunningAgent("bc-demo", "Demo agent", "run-demo")
        realApi.addRunningAgent("bc-real", "Real agent", "run-real")
    }

    @Test
    fun `refreshIfStale skips a list fetched recently and fetches again after a reset`() = runBlocking<Unit> {
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
        assertThat(agents.lastRefreshedAt).isGreaterThan(0L)
        assertThat(demoApi.listAgentsCalls).isEqualTo(1)

        agents.refreshIfStale(maxAgeMs = 60_000)
        assertThat(demoApi.listAgentsCalls).isEqualTo(1)

        agents.reset()
        assertThat(agents.state.value.agents).isEmpty()
        assertThat(agents.state.value.hasLoaded).isFalse()
        assertThat(agents.lastRefreshedAt).isEqualTo(0L)

        agents.refreshIfStale(maxAgeMs = 60_000)
        assertThat(demoApi.listAgentsCalls).isEqualTo(2)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
    }

    @Test
    fun `signing out of one backend and into another never shows the previous list`() = runBlocking<Unit> {
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")

        // What AppGraph.signOut does, then a sign-in with a key.
        agents.reset()
        session.signOut()
        assertThat(agents.state.value.agents).isEmpty()
        assertThat(session.signIn("key_test").isSuccess).isTrue()

        // The list is stale for the new backend no matter how recently the old one was fetched.
        agents.refreshIfStale(maxAgeMs = Long.MAX_VALUE)
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-real")
        assertThat(realApi.listAgentsCalls).isEqualTo(1)
    }

    @Test
    fun `a cached list from another backend is dropped before the new fetch publishes`() = runBlocking<Unit> {
        agents.refresh()
        session.signOut()
        assertThat(session.signIn("key_test").isSuccess).isTrue()
        // Without the explicit reset the demo rows linger until the next refresh, which must replace, not merge.
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-real")
    }

    @Test
    fun `duplicate summaries across pages collapse to one row`() = runBlocking<Unit> {
        demoApi.agents["bc-dup"] = demoApi.agents.getValue("bc-demo")
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-demo")
    }
}
