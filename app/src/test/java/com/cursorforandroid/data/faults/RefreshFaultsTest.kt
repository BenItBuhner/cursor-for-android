package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.CONNECTION_DROPPED
import com.cursorforandroid.data.api.DeviceNetwork
import com.cursorforandroid.data.api.LOOKUP_FAILED_ONLINE
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.ServerSocket

/**
 * The sidebar's refresh (`AgentRepository.refresh`) under a phone's weather, over the app's real HTTP stack against
 * [FaultServer]. The invariants:
 *
 *  - a refresh that fails never blanks or shrinks the list already shown: the rows stand, `error` carries the
 *    server's words (or the connection's, plainly), and the indicators are let go;
 *  - a blip is ridden out by the retry interceptor — bounded attempts, jittered exponential backoff, `Retry-After`
 *    honoured when the server names one — and the refresh succeeds without a word;
 *  - being offline, or unable to reach the host, is said as that.
 *
 * Round trips take 300–900 ms, as they do on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RefreshFaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        repeat(5) { i -> server.addIdleAgent("bc-$i", "Agent $i", "run-$i", createdAt = "2026-04-1${i}T18:30:00.000Z") }
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private val list get() = rig.agents.state.value

    /** A first refresh that goes through, so every fault below meets a list already on screen. */
    private suspend fun shown() {
        rig.agents.refresh()
        assertThat(list.agents).hasSize(5)
        assertThat(list.error).isNull()
    }

    private fun assertListStands(words: String) {
        assertWithMessage("rows after the failed refresh").that(list.agents.map { it.id }).containsExactly("bc-0", "bc-1", "bc-2", "bc-3", "bc-4")
        assertThat(list.error).isEqualTo(words)
        assertThat(list.isRefreshing).isFalse()
        // Nothing registered as in flight: the tail's row has no request left to stand for.
        assertThat(rig.agents.pending.items).isEmpty()
        assertThat(list.hasLoaded).isTrue()
    }

    @Test
    fun `over a slow connection the refresh completes without a word`() = runBlocking<Unit> {
        shown()
        rig.agents.refresh()
        assertThat(list.agents).hasSize(5)
        assertThat(list.error).isNull()
        assertThat(list.isRefreshing).isFalse()
    }

    @Test
    fun `a host down for the whole refresh leaves the list standing, with the server's words, after bounded attempts`() = runBlocking<Unit> {
        shown()
        server.outage(Route.ListAgents, Fault.Status(503, "unavailable", "Cursor is briefly unavailable. Try again."))
        val before = server.requests(Route.ListAgents).size
        rig.agents.refresh()
        assertListStands("Cursor is briefly unavailable. Try again.")
        // Three attempts, backing off between them: a blip is ridden out, an outage is not hidden.
        val attempts = server.requests(Route.ListAgents).drop(before)
        assertThat(attempts).hasSize(3)
        val gaps = attempts.map { it.atMillis }.zipWithNext { a, b -> b - a }
        assertWithMessage("backoff gaps $gaps").that(gaps.all { it >= 200 }).isTrue()

        server.clear(Route.ListAgents)
        rig.agents.refresh()
        assertThat(list.error).isNull()
        assertThat(list.agents).hasSize(5)
    }

    @Test
    fun `two refusals then an answer is a refresh that rode out a blip`() = runBlocking<Unit> {
        shown()
        server.script(Route.ListAgents, Fault.Status(503, "unavailable", "Try again later."), Fault.Status(503, "unavailable", "Try again later."))
        val before = server.requests(Route.ListAgents).size
        rig.agents.refresh()
        assertThat(list.error).isNull()
        assertThat(list.agents).hasSize(5)
        assertThat(server.requests(Route.ListAgents).size - before).isEqualTo(3)
    }

    @Test
    fun `a rate limit that names its wait is waited out before the one retry`() = runBlocking<Unit> {
        shown()
        server.script(Route.ListAgents, Fault.Status(429, "rate_limited", "Too many requests.", retryAfter = "2"))
        val before = server.requests(Route.ListAgents).size
        rig.agents.refresh()
        assertThat(list.error).isNull()
        val attempts = server.requests(Route.ListAgents).drop(before)
        assertThat(attempts).hasSize(2)
        assertWithMessage("the retry waited out the Retry-After").that(attempts[1].atMillis - attempts[0].atMillis).isAtLeast(2_000L)
    }

    @Test
    fun `a rate limit without a wait is retried after a short pause, not at once`() = runBlocking<Unit> {
        shown()
        server.script(Route.ListAgents, Fault.Status(429, "rate_limited", "Too many requests."))
        val before = server.requests(Route.ListAgents).size
        rig.agents.refresh()
        assertThat(list.error).isNull()
        val attempts = server.requests(Route.ListAgents).drop(before)
        assertThat(attempts).hasSize(2)
        assertThat(attempts[1].atMillis - attempts[0].atMillis).isAtLeast(200L)
    }

    @Test
    fun `a rate limit that outlasts the retries is the reader's to see, in the server's words with the wait`() = runBlocking<Unit> {
        shown()
        server.outage(Route.ListAgents, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1"))
        rig.agents.refresh()
        assertListStands("Rate limited by Cursor: Too many requests from this key. Try again in 1 s.")
    }

    @Test
    fun `a body cut half-way leaves the list standing and says the connection dropped`() = runBlocking<Unit> {
        shown()
        server.script(Route.ListAgents, Fault.TruncatedBody)
        rig.agents.refresh()
        assertListStands(CONNECTION_DROPPED)
    }

    @Test
    fun `silence past the read timeout leaves the list standing and says so, after bounded attempts`() = runBlocking<Unit> {
        shown()
        server.outage(Route.ListAgents, Fault.Silence())
        val before = server.requests(Route.ListAgents).size
        rig.agents.refresh()
        assertListStands("Cursor took too long to respond.")
        assertThat(server.requests(Route.ListAgents).size - before).isEqualTo(3)
    }

    @Test
    fun `a second page that fails keeps the first page's rows and every row already shown`() = runBlocking<Unit> {
        // Pages of three: the first refresh shows the newest page, the reader scrolls to the end for the rest, and
        // the refresh after that re-reads both pages of its window.
        server.pageSize = 3
        rig.agents.refresh()
        assertThat(list.agents).hasSize(3)
        rig.agents.loadMore()
        assertThat(list.agents).hasSize(5)
        server.script(Route.ListAgents, Fault.Pass, Fault.Status(503, "unavailable", "Try again later."), Fault.Status(503, "unavailable", "Try again later."), Fault.Status(503, "unavailable", "Try again later."))
        rig.agents.refresh()
        assertListStands("Try again later.")
    }

    @Test
    fun `a connection killed under the request is ridden out`() = runBlocking<Unit> {
        shown()
        rig.client.connectionPool.evictAll()
        server.resetNextConnections(1)
        rig.agents.refresh()
        assertThat(list.error).isNull()
        assertThat(list.agents).hasSize(5)
    }

    @Test
    fun `a host that does not resolve is said as being offline when the phone has no network, and as the lookup when it has`() = runBlocking<Unit> {
        val offline = FaultRig("http://cursor-for-android.invalid/", folder.newFolder("offline"))
        try {
            DeviceNetwork.install { false }
            offline.agents.refresh()
            assertThat(offline.agents.state.value.error).isEqualTo("You're offline. Check your connection.")
            assertThat(offline.agents.state.value.isRefreshing).isFalse()
            DeviceNetwork.install { true }
            offline.agents.refresh()
            assertThat(offline.agents.state.value.error).isEqualTo(LOOKUP_FAILED_ONLINE)
            assertThat(offline.agents.state.value.isRefreshing).isFalse()
        } finally {
            DeviceNetwork.install { null }
            offline.close()
        }
    }

    @Test
    fun `a host that refuses the connection is said as unreachable`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val unreachable = FaultRig("http://127.0.0.1:$port/", folder.newFolder("unreachable"))
        try {
            unreachable.agents.refresh()
            assertThat(unreachable.agents.state.value.error).isEqualTo("Cursor couldn't be reached. Check your connection.")
        } finally {
            unreachable.close()
        }
    }
}
