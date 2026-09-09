package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What the streamer waits before reconnecting: its own backoff, or the delay a rate limit named. */
class SseRunStreamerRetryTest {

    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private var now = 1_788_900_000_000L // 2026-09-08T20:40:00Z

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun streamer() = SseRunStreamer(
        client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
        apiKeyProvider = { "key" },
        urlFor = { _, _ -> server.url("/stream").toString() },
        now = { now },
        waiter = { waits += it },
    )

    private fun finishedStream() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n")

    /** The retry loop only resumes from a position it holds, so every case starts with a Last-Event-ID. */
    private fun collect(): List<RunStreamEvent> = runBlocking { streamer().stream("bc-1", "run-1", lastEventId = "e-7").toList() }

    @Test
    fun `a rate limit that names its delay in seconds is waited out, not talked over`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(finishedStream())

        val events = collect()

        assertThat(waits).containsExactly(30_000L)
        assertThat(events.last()).isEqualTo(RunStreamEvent.Done)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().getHeader("Last-Event-ID")).isEqualTo("e-7")
    }

    @Test
    fun `a Retry-After that names a time is read as a delta, and one already past falls back to the backoff`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "Tue, 08 Sep 2026 20:40:45 GMT"))
        server.enqueue(finishedStream())
        collect()
        assertThat(waits).containsExactly(45_000L)

        waits.clear()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Tue, 08 Sep 2026 20:00:00 GMT"))
        server.enqueue(finishedStream())
        collect()
        assertThat(waits).containsExactly(1_000L)
    }

    /**
     * A `retry:` is the server saying how long to wait before reconnecting. It is a floor rather than the whole
     * schedule: successive failures still back off, and a rate limit that names a longer delay still wins.
     */
    @Test
    fun `a retry field the stream sent is waited out on every reconnect after it`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("retry: 5000\nid: e-8\nevent: heartbeat\ndata: {}\n\n"),
        )
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(finishedStream())

        collect()

        // The first wait would have been 1 s and the second 2 s; the stream asked for 5 s and outlived the
        // connection that carried it.
        assertThat(waits).containsExactly(5_000L, 5_000L).inOrder()
    }

    @Test
    fun `an implausible delay is honoured only as far as the cap, and a 500 without one keeps the backoff`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "86400"))
        server.enqueue(finishedStream())
        collect()
        assertThat(waits).containsExactly(60_000L)

        waits.clear()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(finishedStream())
        collect()
        assertThat(waits).containsExactly(1_000L, 2_000L).inOrder()
    }
}
