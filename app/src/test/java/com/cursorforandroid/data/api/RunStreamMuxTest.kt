package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.http2.Settings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.buffer
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Run streams read through [RunStreamMux]: every case of the stream's protocol ends exactly as it does through OkHttp
 * over the same HTTP/2 server, and what the multiplexer adds — no thread per stream, one stream's stalled reader
 * stalling nothing else, a cancelled stream reset at once, the server's stream limit kept — holds.
 */
class RunStreamMuxTest {

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { runCatching { it.shutdown() } }

    private fun server(dispatcher: Dispatcher? = null): MockWebServer = MockWebServer().apply {
        protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        dispatcher?.let { this.dispatcher = it }
        start()
    }.also { servers += it }

    private fun client(readTimeoutMs: Long = 5_000L) = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private class Pass(val events: List<RunStreamEvent>, val waits: List<Long>, val lastEventIds: List<String?>, val userAgents: List<String?>)

    /** [responses] served once to a stream read through the multiplexer and once through OkHttp: the two passes. */
    private fun both(lastEventId: String? = null, maxAttempts: Int = 4, readTimeoutMs: Long = 5_000L, allMuxed: Boolean = true, responses: () -> List<MockResponse>): Pair<Pass, Pass> {
        fun pass(multiplexed: Boolean): Pass {
            val server = server()
            responses().forEach(server::enqueue)
            val client = client(readTimeoutMs)
            val waits = mutableListOf<Long>()
            val streamer = SseRunStreamer(
                client, { "key" },
                urlFor = { _, _ -> server.url("/v1/agents/bc-1/runs/run-1/stream").toString() },
                maxAttempts = maxAttempts,
                now = { NOW },
                waiter = { waits += it },
                mux = if (multiplexed) RunStreamMux(client) else null,
            )
            val events = runBlocking { withTimeout(60_000) { streamer.stream("bc-1", "run-1", lastEventId).toList() } }
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            return Pass(events, waits.toList(), requests.map { it.getHeader("Last-Event-ID") }, requests.map { it.getHeader("User-Agent") })
        }
        val muxed = pass(multiplexed = true)
        val okhttp = pass(multiplexed = false)
        // A request the server reset before reading it is recorded without its headers.
        val agents = muxed.userAgents.filterNotNull()
        assertWithMessage("the first request went out through the multiplexer").that(agents.first()).isEqualTo(CursorApiFactory.USER_AGENT)
        if (allMuxed) assertWithMessage("every request went out through the multiplexer").that(agents.toSet()).containsExactly(CursorApiFactory.USER_AGENT)
        assertThat(muxed.events).isEqualTo(okhttp.events)
        assertThat(muxed.waits).isEqualTo(okhttp.waits)
        assertThat(muxed.lastEventIds).isEqualTo(okhttp.lastEventIds)
        return muxed to okhttp
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `frames with ids, comments, CRLF, joined data and unknown events read as through OkHttp`() {
        val (muxed, _) = both {
            listOf(
                sse(
                    ": opening comment\n\n\r\n" +
                        "id: e-1\nevent: assistant\ndata: {\"text\":\"Hel\"}\n\n" +
                        "id: e-2\r\nevent: thinking\r\ndata: {\"text\":\"one\"}\r\n\r\n" +
                        ": keep-alive\n\n" +
                        "id: e-3\nevent: something_new\ndata: {}\n\n" +
                        "id: e-4\nevent: assistant\ndata: {\"text\":\n data: \"lo\"}\n\n" +
                        "id: e-5\nretry: 2500\nevent: heartbeat\ndata: {}\n\n" +
                        "id: e-6\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\",\"text\":\"Hello\"}\n\n" +
                        "event: done\ndata: {}\n\n",
                ),
            )
        }
        assertThat(muxed.events.last()).isEqualTo(RunStreamEvent.Done)
        assertThat(muxed.events.filterIsInstance<RunStreamEvent.Position>().map { it.id }).containsExactly("e-1", "e-2", "e-3", "e-5", "e-6").inOrder()
    }

    @Test
    fun `a body delivered a few bytes at a time splits no frame`() {
        val body = (1..40).joinToString("") { "id: e-$it\r\nevent: assistant\r\ndata: {\"text\":\"word $it \"}\r\n\r\n" } +
            "id: e-41\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n"
        val (muxed, _) = both { listOf(sse(body).throttleBody(7, 1, TimeUnit.MILLISECONDS)) }
        assertThat(muxed.events.filterIsInstance<RunStreamEvent.Assistant>()).hasSize(40)
    }

    @Test
    fun `a stream cut inside a frame resumes from the last whole one`() {
        val (muxed, _) = both {
            listOf(
                sse("id: e-1\nevent: assistant\ndata: {\"text\":\"a\"}\n\nid: e-2\nevent: assistant\ndata: {\"te"),
                sse("id: e-2\nevent: assistant\ndata: {\"text\":\"b\"}\n\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n"),
            )
        }
        assertThat(muxed.lastEventIds).containsExactly(null, "e-1").inOrder()
        assertThat(muxed.waits).containsExactly(1_000L)
    }

    @Test
    fun `a frame bigger than the gathering limit, and one past the parser's cap, read as through OkHttp`() {
        val picture = "a".repeat(3 shl 20)
        val huge = "b".repeat(17 shl 20)
        val (muxed, _) = both {
            listOf(
                sse(
                    "id: e-1\nevent: assistant\ndata: {\"text\":\"$picture\"}\n\n" +
                        "id: e-2\nevent: interaction_update\ndata: $huge\n\n" +
                        "id: e-3\nevent: assistant\ndata: {\"text\":\"after\"}\n\n" +
                        "id: e-4\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n",
                ),
            )
        }
        assertThat(muxed.events.filterIsInstance<RunStreamEvent.Assistant>().map { it.text.length }).containsExactly(3 shl 20, 5).inOrder()
        assertThat(muxed.events.filterIsInstance<RunStreamEvent.Position>().map { it.id }).containsExactly("e-1", "e-2", "e-3", "e-4").inOrder()
    }

    @Test
    fun `refusals end or retry the pass as through OkHttp`() {
        val finished = { sse("event: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n") }
        val cases: List<Pair<String, () -> List<MockResponse>>> = listOf(
            "410" to { listOf(MockResponse().setResponseCode(410).setBody("{\"error\":{\"code\":\"stream_expired\",\"message\":\"gone\"}}")) },
            "401" to { listOf(MockResponse().setResponseCode(401)) },
            "404" to { listOf(MockResponse().setResponseCode(404)) },
            "400 invalid id" to { listOf(MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"invalid_last_event_id\",\"message\":\"Unknown id.\"}}")) },
            "400 other" to { listOf(MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"validation_error\",\"message\":\"Bad.\"}}")) },
            "418" to { listOf(MockResponse().setResponseCode(418)) },
            "429 seconds" to { listOf(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"), finished()) },
            "503 date" to { listOf(MockResponse().setResponseCode(503).setHeader("Retry-After", "Tue, 08 Sep 2026 20:40:45 GMT"), finished()) },
            "500 twice" to { listOf(MockResponse().setResponseCode(500), MockResponse().setResponseCode(500), finished()) },
            "redirect" to { listOf(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"), finished()) },
            "reset" to { listOf(MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(ErrorCode.INTERNAL_ERROR.httpCode), finished()) },
            "in-band error" to { listOf(sse("id: e-8\nevent: assistant\ndata: {\"text\":\"a\"}\n\nevent: error\ndata: {\"code\":\"upstream_error\",\"message\":\"Upstream.\"}\n\n")) },
            "done without result" to { listOf(sse("id: e-8\nevent: assistant\ndata: {\"text\":\"a\"}\n\nevent: done\ndata: {}\n\n")) },
            "id reset" to { listOf(sse("id: e-8\nevent: assistant\ndata: {\"text\":\"a\"}\n\nid:\nevent: heartbeat\ndata: {}\n\n"), finished()) },
        )
        for ((name, responses) in cases) {
            val (muxed, _) = both(lastEventId = "e-7", allMuxed = name != "redirect", responses = responses)
            println("   $name: ${muxed.events.last()} waits=${muxed.waits} ids=${muxed.lastEventIds}")
        }
    }

    @Test
    fun `a stream silent past the read timeout reconnects from where it was`() {
        val (muxed, _) = both(readTimeoutMs = 400L) {
            listOf(
                MockResponse().setHeader("Content-Type", "text/event-stream").removeHeader("Content-Length").setBody(object : DuplexResponseBody {
                    override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                        val sink = http2Stream.getSink().buffer()
                        sink.writeUtf8("id: e-1\nevent: assistant\ndata: {\"text\":\"a\"}\n\n").flush()
                        runCatching { Thread.sleep(5_000) }
                        runCatching { sink.close() }
                    }
                }),
                sse("event: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n"),
            )
        }
        assertThat(muxed.lastEventIds).containsExactly(null, "e-1").inOrder()
        assertThat(muxed.events.last()).isEqualTo(RunStreamEvent.Done)
    }

    /** A held stream that says something every [beatMs] until the client goes; [gone] counts down when it has. */
    private class Held(private val beatMs: Long, private val events: Int = Int.MAX_VALUE, private val chunk: String = "") : Dispatcher() {
        val gone = CountDownLatch(1)
        val open = AtomicInteger()
        var serverSettings: Settings? = null
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .removeHeader("Content-Length")
            .apply { serverSettings?.let { withSettings(it) } }
            .setBody(object : DuplexResponseBody {
                override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                    open.incrementAndGet()
                    val sink = http2Stream.getSink().buffer()
                    try {
                        for (i in 1..events) {
                            sink.writeUtf8("id: e-$i\nevent: assistant\ndata: {\"text\":\"$chunk$i\"}\n\n").flush()
                            if (beatMs > 0) Thread.sleep(beatMs)
                        }
                        sink.writeUtf8("event: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n").close()
                    } catch (_: IOException) {
                        gone.countDown()
                    } finally {
                        open.decrementAndGet()
                    }
                }
            })
    }

    private fun muxedStreamer(server: MockWebServer, mux: RunStreamMux, client: OkHttpClient) =
        SseRunStreamer(client, { "key" }, urlFor = { _, runId -> server.url("/v1/agents/bc-1/runs/$runId/stream").toString() }, mux = mux)

    @Test
    fun `a cancelled stream is reset at once and gives back its slot`() = runBlocking {
        val held = Held(beatMs = 20L)
        val server = server(held)
        val client = client()
        val mux = RunStreamMux(client)
        val first = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { muxedStreamer(server, mux, client).stream("bc-1", "run-1").collect { first.complete(Unit) } }
        withTimeout(5_000) { first.await() }
        assertThat(mux.streamCount).isEqualTo(1)
        job.cancel()
        assertWithMessage("the server's write fails once the stream is reset").that(held.gone.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(mux.streamCount).isEqualTo(0)
    }

    /**
     * Head-of-line: one stream's collector stops taking events while its server sends it far more than its window
     * and the connection's buffer cap; the other stream on the same connection keeps its pace throughout, and the
     * stalled one, let go, reads every event it was sent, in order.
     */
    @Test
    fun `a stream whose reader stalls holds up no other stream`() = runBlocking {
        val bulk = "x".repeat(64 shl 10)
        val server = server(object : Dispatcher() {
            val flood = Held(beatMs = 0L, events = 400, chunk = bulk)
            val steady = Held(beatMs = 10L, events = 300)
            override fun dispatch(request: RecordedRequest) = if ("flood" in request.path!!) flood.dispatch(request) else steady.dispatch(request)
        })
        val client = client(readTimeoutMs = 20_000L)
        val mux = RunStreamMux(client)
        val streamer = muxedStreamer(server, mux, client)
        val gate = CompletableDeferred<Unit>()
        val floodTexts = mutableListOf<Int>()
        val flood = launch(Dispatchers.Default) {
            streamer.stream("bc-1", "flood").collect { event ->
                if (event is RunStreamEvent.Assistant) {
                    floodTexts += event.text.removePrefix(bulk).toInt()
                    if (floodTexts.size == 1) gate.await()
                }
            }
        }
        val steadyAt = mutableListOf<Long>()
        val steady = async(Dispatchers.Default) {
            val events = mutableListOf<RunStreamEvent>()
            streamer.stream("bc-1", "steady").collect {
                if (it is RunStreamEvent.Assistant) steadyAt += System.nanoTime()
                events += it
            }
            events
        }
        val steadyEvents = withTimeout(30_000) { steady.await() }
        assertThat(mux.connectionCount).isEqualTo(1)
        assertThat(steadyEvents.last()).isEqualTo(RunStreamEvent.Done)
        assertThat(steadyAt).hasSize(300)
        val longestGapMs = steadyAt.zipWithNext { a, b -> (b - a) / 1_000_000 }.max()
        println("   steady stream: 300 events, longest gap ${longestGapMs} ms, while the flooded one held ${floodTexts.size}")
        assertWithMessage("the steady stream's longest gap between events").that(longestGapMs).isLessThan(500L)
        assertThat(floodTexts).hasSize(1)
        gate.complete(Unit)
        withTimeout(30_000) { flood.join() }
        assertThat(floodTexts).isEqualTo((1..400).toList())
    }

    @Test
    fun `five hundred streams share one connection and two threads`() = runBlocking {
        val server = server(Held(beatMs = 10L, events = 100))
        val client = client(readTimeoutMs = 20_000L)
        val mux = RunStreamMux(client)
        val streamer = muxedStreamer(server, mux, client)
        var peakThreads = 0
        var peakNames = emptyList<String>()
        var peakStreams = 0
        val sampler = launch(Dispatchers.Default) {
            while (true) {
                val threads = streamThreads()
                if (threads.size > peakThreads) {
                    peakThreads = threads.size
                    peakNames = threads
                }
                peakStreams = maxOf(peakStreams, mux.streamCount)
                kotlinx.coroutines.delay(20)
            }
        }
        val passes = (1..500).map { i -> async(Dispatchers.Default) { streamer.stream("bc-1", "run-$i").toList() } }.awaitAll()
        sampler.cancel()
        println("   500 streams: peak open $peakStreams, peak stream threads $peakThreads $peakNames, connections ${mux.connectionCount}")
        assertThat(passes.map { pass -> pass.count { it is RunStreamEvent.Assistant } }.toSet()).containsExactly(100)
        assertThat(passes.map { it.last() }.toSet()).containsExactly(RunStreamEvent.Done)
        assertThat(peakStreams).isAtLeast(400)
        assertThat(peakThreads).isAtMost(2)
        assertThat(mux.connectionCount).isEqualTo(1)
    }

    @Test
    fun `the server's stream limit opens another connection rather than waiting`() = runBlocking {
        val held = Held(beatMs = 20L).apply { serverSettings = Settings().set(Settings.MAX_CONCURRENT_STREAMS, 2) }
        val server = server(held)
        val client = client()
        val mux = RunStreamMux(client)
        val streamer = muxedStreamer(server, mux, client)
        // One at a time, each flowing before the next opens: by then the server's SETTINGS have arrived.
        val jobs = (1..5).map { i ->
            val first = CompletableDeferred<Unit>()
            launch(Dispatchers.Default) { streamer.stream("bc-1", "run-$i").collect { if (it is RunStreamEvent.Assistant) first.complete(Unit) } }
                .also { withTimeout(5_000) { first.await() } }
        }
        assertThat(mux.streamCount).isEqualTo(5)
        assertThat(mux.connectionCount).isEqualTo(3)
        jobs.forEach { it.cancel() }
    }

    private fun streamThreads(): List<String> = StreamThreads.census().readers

    private companion object {
        const val NOW = 1_788_900_000_000L // 2026-09-08T20:40:00Z
    }
}
