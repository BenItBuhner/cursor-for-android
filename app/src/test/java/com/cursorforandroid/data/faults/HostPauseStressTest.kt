package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.HostPause
import com.cursorforandroid.data.api.RetryInterceptor
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A `429` naming a wait longer than a call sleeps, against the app's real REST client and run streamer on a
 * [FaultServer] at 300–900 ms RTT, in virtual time (the interceptor's `now` / `sleeper` and the streamer's `waiter`
 * seams): the host is asked once, and neither the REST reads nor the streams go back to it inside the window.
 * Each scenario prints a `NETAUDIT` line (the networking audit's NET-4).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HostPauseStressTest {

    private val closers = ArrayList<AutoCloseable>()
    private val virtual = AtomicLong(0)
    private val pauses = HostPause { virtual.get() }
    /** Every request that reached the wire, with the virtual time it left at. */
    private val sentAt = CopyOnWriteArrayList<Pair<String, Long>>()
    private val waits = CopyOnWriteArrayList<Long>()

    @After
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it.close() } }
    }

    private fun server(): FaultServer = FaultServer(rttMillis = 300L..900L).start().also { closers += it }

    private fun client(): OkHttpClient = CursorApiFactory.okHttp(CursorApiFactory.newRoot(), pauses) { "fault-key" }.newBuilder()
        .dns(Dns.SYSTEM)
        .apply { interceptors().removeAll { it is RetryInterceptor } }
        .addInterceptor(RetryInterceptor(now = { virtual.get() }, sleeper = { ms, _ -> virtual.addAndGet(ms) }, random = { 1.0 }, pauses = pauses))
        .addNetworkInterceptor { chain -> sentAt += chain.request().url.encodedPath to virtual.get(); chain.proceed(chain.request()) }
        .build()
        .also { client -> closers += AutoCloseable { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() } }

    private fun streamer(server: FaultServer, client: OkHttpClient) = SseRunStreamer(
        CursorApiFactory.sseClient(client),
        apiKeyProvider = { "fault-key" },
        urlFor = { agentId, runId -> "${server.baseUrl}v1/agents/$agentId/runs/$runId/stream" },
        now = { virtual.get() },
        waiter = { ms -> waits += ms; virtual.addAndGet(ms) },
        pauses = pauses,
    )

    private fun runningAgent(server: FaultServer, id: String, runId: String) {
        server.addRunningAgent(id, id, runId)
        server.logs[runId] = listOf(
            "status" to """{"runId":"$runId","status":"RUNNING"}""",
            "thinking" to """{"text":"Reading the code first."}""",
            "assistant" to """{"text":"Working on it."}""",
        )
    }

    private fun OkHttpClient.get(url: String): Pair<Int, String?> =
        newCall(Request.Builder().url(url).build()).execute().use { it.code to it.header("Retry-After") }

    private fun sent(path: String) = sentAt.filter { it.first == path }.map { it.second }

    @Test
    fun `a REST 429 naming 30 s is asked once, and reads and streams wait the window out`(): Unit = runBlocking {
        val server = server()
        server.addIdleAgent("bc-a", "a", "run-a")
        runningAgent(server, "bc-b", "run-b")
        val client = client()
        server.script(Route.GetAgent, Fault.Status(429, "rate_limited", "Slow down.", retryAfter = "30"))

        val (code, _) = client.get("${server.baseUrl}v1/agents/bc-a")
        // Inside the window: a read hears the refusal with the wait left, without going out.
        virtual.addAndGet(5_000)
        val (heldCode, heldRetryAfter) = client.get("${server.baseUrl}v1/agents/bc-a")
        val attemptsInWindow = sent("/v1/agents/bc-a")
        // A stream asked for now waits the rest of the window before it connects.
        withTimeout(30_000) { streamer(server, client).stream("bc-b", "run-b", null).first() }
        val streamSentAt = sent("/v1/agents/bc-b/runs/run-b/stream").single()
        val (after, _) = client.get("${server.baseUrl}v1/agents/bc-a")
        println(
            "NETAUDIT rest-429 (after): finalCode=$code requestsInWindow=${attemptsInWindow.size} attemptsAtVirtualMs=$attemptsInWindow " +
                "heldRead=$heldCode Retry-After=$heldRetryAfter streamWaitsMs=$waits streamSentAt=$streamSentAt afterWindow=$after " +
                "(before: 3 requests at 0 / 10000 / 20000 ms, stream sent at once)",
        )
        assertThat(code).isEqualTo(429)
        assertThat(attemptsInWindow).containsExactly(0L)
        assertThat(server.requests(Route.GetAgent)).hasSize(2)
        assertThat(heldCode).isEqualTo(429)
        assertThat(heldRetryAfter).isEqualTo("25")
        assertThat(waits).containsExactly(25_000L)
        assertThat(streamSentAt).isEqualTo(30_000L)
        assertThat(after).isEqualTo(200)
        assertThat(sent("/v1/agents/bc-a")).containsExactly(0L, 30_000L).inOrder()
    }

    @Test
    fun `a stream 429 pauses the host for the record read and the reconnect after the give-up`(): Unit = runBlocking {
        val server = server()
        runningAgent(server, "bc-a", "run-a")
        repeat(5) { server.script(Route.Stream, Fault.Status(429, "rate_limited", "Slow down.", retryAfter = "30")) }
        val client = client()
        val streamer = streamer(server, client)

        val events = withTimeout(60_000) { streamer.stream("bc-a", "run-a", null).toList() }
        val error = events.filterIsInstance<RunStreamEvent.Error>().single()
        val passWaits = waits.toList()
        val gaveUpAt = virtual.get()
        // What the hub does after a give-up: read the run record, then open the next pass.
        val (recordCode, _) = client.get("${server.baseUrl}v1/agents/bc-a/runs/run-a")
        waits.clear()
        withTimeout(30_000) { streamer.stream("bc-a", "run-a", null).first() }
        val streams = sent("/v1/agents/bc-a/runs/run-a/stream")
        println(
            "NETAUDIT stream-429 (after): passRequests=5 waitsMs=$passWaits error=${error.code} recordRead=$recordCode " +
                "recordRequests=${server.requests(Route.GetRun).size} reconnectWaitsMs=$waits reconnectSentAt=${streams.last()} gaveUpAt=$gaveUpAt " +
                "(before: record read unpaused, reconnect 1 s after the give-up)",
        )
        assertThat(passWaits).containsExactly(30_000L, 30_000L, 30_000L, 30_000L).inOrder()
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(recordCode).isEqualTo(429)
        assertThat(server.requests(Route.GetRun)).isEmpty()
        assertThat(waits).containsExactly(30_000L)
        assertThat(streams).hasSize(6)
        assertThat(streams.last()).isEqualTo(gaveUpAt + 30_000L)
    }
}
