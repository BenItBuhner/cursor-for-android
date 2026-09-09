package com.cursorforandroid.data.api

import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test

class SseParserTest {

    private val sample = """
        event: status
        data: {"runId":"run-1","status":"RUNNING"}

        id: 1713033000000-0
        event: assistant
        data: {"text":"I'll update the README now."}

        : keepalive comment

        id: 1713033005000-0
        event: tool_call
        data: {"callId":"call-1","name":"read_file","status":"running","args":{"path":"README.md"}}

        id: 1713033010000-0
        event: result
        data: {"runId":"run-1","status":"FINISHED","text":"Done.","durationMs":12357,"git":{"branches":[{"repoUrl":"github.com/o/r","branch":"cursor/x"}]}}

        id: 1713033010000-0
        event: done
        data: {}

    """.trimIndent().replace("\n", "\n") + "\n"

    @Test
    fun `parses frames with ids, comments and multi-frame payloads`() {
        val source = Buffer().writeUtf8(sample)
        val frames = generateSequence { SseParser.readFrame(source) }.toList()
        assertThat(frames.map { it.event }).containsExactly("status", "assistant", "tool_call", "result", "done").inOrder()
        assertThat(frames[0].id).isNull()
        assertThat(frames[1].id).isEqualTo("1713033000000-0")
        assertThat(frames[1].data).isEqualTo("""{"text":"I'll update the README now."}""")
    }

    @Test
    fun `multi-line data is joined with newlines`() {
        val source = Buffer().writeUtf8("event: assistant\ndata: {\"text\":\ndata: \"hi\"}\n\n")
        val frame = SseParser.readFrame(source)!!
        assertThat(frame.data).isEqualTo("{\"text\":\n\"hi\"}")
    }

    @Test
    fun `maps frames to typed events and ignores unknown types`() {
        val source = Buffer().writeUtf8(sample)
        val events = generateSequence { SseParser.readFrame(source) }.mapNotNull(SseParser::toEvent).toList()
        assertThat(events[0]).isEqualTo(RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        assertThat(events[1]).isEqualTo(RunStreamEvent.Assistant("I'll update the README now."))
        val tool = events[2] as RunStreamEvent.ToolCall
        assertThat(tool.call.name).isEqualTo("read_file")
        val result = events[3] as RunStreamEvent.Result
        assertThat(result.status).isEqualTo(RunStatus.FINISHED)
        assertThat(result.durationMs).isEqualTo(12357L)
        assertThat(result.git!!.branches.single().branch).isEqualTo("cursor/x")
        assertThat(events[4]).isEqualTo(RunStreamEvent.Done)
        assertThat(SseParser.toEvent(SseFrame("interaction_update", null, "{}"))).isNull()
    }

    /**
     * "Once the end of the file is reached, any pending data must be discarded." A connection cut mid-frame must
     * not look like a frame the server finished writing — least of all one worth resuming past.
     */
    @Test
    fun `a frame the stream was cut off in the middle of is discarded`() {
        val source = Buffer().writeUtf8(
            "id: 10-0\nevent: assistant\ndata: {\"text\":\"first\"}\n\n" +
                "id: 11-0\nevent: assistant\ndata: {\"text\":\"sec",
        )
        val frames = generateSequence { SseParser.readFrame(source) }.toList()
        assertThat(frames.map { it.id }).containsExactly("10-0")
    }

    @Test
    fun `a frame whose data cannot be read is neither delivered nor resumable`() {
        assertThat(SseParser.parse(SseFrame("assistant", "9-0", "{not json"))).isEqualTo(SseParser.Parsed.Undecodable)
        assertThat(SseParser.parse(SseFrame("interaction_update", "9-0", "{}"))).isEqualTo(SseParser.Parsed.Ignored)
        assertThat(SseParser.parse(SseFrame("heartbeat", null, ""))).isEqualTo(SseParser.Parsed.Delivered(RunStreamEvent.Heartbeat))
    }

    /**
     * The specification's own rules for `id:` and `retry:`: an empty id says the stream has no resume position (it
     * is not an id of ""), an id with a NUL in it is not an id at all, and a `retry:` is digits only.
     */
    @Test
    fun `an empty id resets the resume position, a NUL id is ignored and retry is read as digits`() {
        val source = Buffer().writeUtf8(
            "id: 10-0\nevent: heartbeat\ndata: {}\n\n" +
                "id: \nevent: heartbeat\ndata: {}\n\n" +
                "id: 11\u00000\nevent: heartbeat\ndata: {}\n\n" +
                "retry: 2500\nevent: heartbeat\ndata: {}\n\n" +
                "retry: 5s\nevent: heartbeat\ndata: {}\n\n" +
                "retry: 1\nevent: heartbeat\ndata: {}\n\n" +
                "retry: 999999\nevent: heartbeat\ndata: {}\n\n",
        )
        val frames = generateSequence { SseParser.readFrame(source) }.toList()

        assertThat(frames[0].id).isEqualTo("10-0")
        assertThat(frames[0].resetId).isFalse()
        assertThat(frames[1].id).isNull()
        assertThat(frames[1].resetId).isTrue()
        // Ignored outright: the position stays whatever the stream last gave, rather than becoming this.
        assertThat(frames[2].id).isNull()
        assertThat(frames[2].resetId).isFalse()
        assertThat(frames[3].retryMillis).isEqualTo(2_500L)
        assertThat(frames[4].retryMillis).isNull()
        // Honoured only as far as it is worth honouring: not a reconnect loop, not longer than the caller's own patience.
        assertThat(frames[5].retryMillis).isEqualTo(1_000L)
        assertThat(frames[6].retryMillis).isEqualTo(60_000L)
    }

    /**
     * An empty id leaves the stream with no position to resume from, and a connection without one replays the run
     * from its first event — into an accumulator that already holds part of it. The pass has to end instead.
     */
    @Test
    fun `an id reset means a reconnect ends the pass rather than doubling the run`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n" +
                    "id: \nevent: assistant\ndata: {\"text\":\" part two\"}\n\n",
            ),
        )
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() })
        val events = streamer.stream("bc-1", "run-1").toList()

        assertThat(events.map { it::class.simpleName }).containsExactly("Assistant", "Assistant", "Error").inOrder()
        val error = events.last() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(error.resumeFrom).isNull()
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    /**
     * A `result` frame this client cannot read is a frame it never received: the run is not finished as far as it
     * knows, and the resume position must still point at the last frame that was.
     */
    @Test
    fun `a result that cannot be read is asked for again instead of ending the run`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n" +
                    "id: 11-0\nevent: result\ndata: {not json\n\n" +
                    "id: 11-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: 11-0\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\",\"text\":\"ok\"}\n\n" +
                    "id: 12-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() }, waiter = {})
        val events = streamer.stream("bc-1", "run-1").toList()

        assertThat(events.map { it::class.simpleName }).containsExactly("Assistant", "Result", "Done").inOrder()
        assertThat((events[1] as RunStreamEvent.Result).text).isEqualTo("ok")
        // Reconnected from the last frame it could read, so the server sends the result again.
        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Last-Event-ID")).isEqualTo("10-0")
        server.shutdown()
    }

    @Test
    fun `a truncated last frame is not resumed past`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n" +
                    "id: 11-0\nevent: assistant\ndata: {\"text\":\"part t",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() }, maxAttempts = 1)
        val events = streamer.stream("bc-1", "run-1").toList()

        assertThat(events.map { it::class.simpleName }).containsExactly("Assistant", "Error").inOrder()
        assertThat((events.first() as RunStreamEvent.Assistant).text).isEqualTo("part one")
        // The half-written frame neither arrived nor moved the resume position on.
        assertThat((events.last() as RunStreamEvent.Error).resumeFrom).isEqualTo("10-0")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Last-Event-ID")).isEqualTo("10-0")
        server.shutdown()
    }

    /**
     * A connection with nothing to resume from replays the run from its first event, which would double every reply
     * already accumulated. The pass ends instead, so the caller rebuilds from nothing.
     */
    @Test
    fun `a retry with no resume position ends the pass instead of starting the run over`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() }, maxAttempts = 4)
        val events = streamer.stream("bc-1", "run-1").toList()

        val error = events.single() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(error.resumeFrom).isNull()
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test
    fun `streamer reconnects with Last-Event-ID after a dropped connection`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    "id: 11-0\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\",\"text\":\"ok\"}\n\n" +
                    "id: 11-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.start()
        // Go through the production client factory so the auth interceptor is exercised too.
        val client = CursorApiFactory.sseClient(CursorApiFactory.okHttp { "key_test" })
        val streamer = SseRunStreamer(client, { "key_test" }, urlFor = { _, _ -> server.url("/v1/agents/bc-1/runs/run-1/stream").toString() })

        val events = streamer.stream("bc-1", "run-1").toList()

        assertThat(events.filterIsInstance<RunStreamEvent.Assistant>().single().text).isEqualTo("part one")
        assertThat(events.filterIsInstance<RunStreamEvent.Result>().single().status).isEqualTo(RunStatus.FINISHED)
        assertThat(events.last()).isEqualTo(RunStreamEvent.Done)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer key_test")
        assertThat(first.getHeader("Accept")).isEqualTo("text/event-stream")
        assertThat(first.getHeader("User-Agent")).startsWith("cursor-for-android/")
        assertThat(first.getHeader("Last-Event-ID")).isNull()
        assertThat(second.getHeader("Last-Event-ID")).isEqualTo("10-0")
        server.shutdown()
    }

    @Test
    fun `stream expiry surfaces a terminal error instead of retrying forever`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(410).setBody("{\"error\":{\"code\":\"stream_expired\",\"message\":\"expired\"}}"))
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() })
        val events = streamer.stream("bc-1", "run-1").toList()
        assertThat(events).hasSize(1)
        val error = events.single() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_expired")
        assertThat(error.isExpired).isTrue()
        assertThat(error.isFatal).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test
    fun `an in-band error ends the pass at once with the position to resume from`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n" +
                    "id: 11-0\nevent: error\ndata: {\"code\":\"stream_unavailable\",\"message\":\"Run stream is no longer available\"}\n\n" +
                    "id: 11-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() })
        val events = streamer.stream("bc-1", "run-1").toList()
        // What arrived is kept; the error is the last word, and it is not retried here: only the caller can tell a
        // dead stream from a finished run, by reading the run record.
        assertThat(events.map { it::class.simpleName }).containsExactly("Status", "Assistant", "Error").inOrder()
        val error = events.last() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(error.message).isEqualTo("Run stream is no longer available")
        assertThat(error.resumeFrom).isEqualTo("11-0")
        assertThat(error.isFatal).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test
    fun `done without a result is a stream that ended early, not a finished run`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    "id: 10-0\nevent: thinking\ndata: {\"text\":\"hmm\"}\n\n" +
                    "id: 10-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() })
        val events = streamer.stream("bc-1", "run-1").toList()
        val error = events.last() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_closed")
        assertThat(error.resumeFrom).isEqualTo("10-0")
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test
    fun `a rejected resume position asks for a fresh start instead of resuming again`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"invalid_last_event_id\",\"message\":\"Unknown event id\"}}"))
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() })
        val events = streamer.stream("bc-1", "run-1", lastEventId = "stale-0").toList()
        val error = events.single() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("invalid_last_event_id")
        assertThat(error.resumeFrom).isNull()
        assertThat(error.isFatal).isFalse()
        assertThat(server.takeRequest().getHeader("Last-Event-ID")).isEqualTo("stale-0")
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test
    fun `transport failures are retried a few times, then handed back with the resume position`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    "id: 10-0\nevent: assistant\ndata: {\"text\":\"part one\"}\n\n",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val streamer = SseRunStreamer(OkHttpClient(), { null }, urlFor = { _, _ -> server.url("/stream").toString() }, maxAttempts = 1)
        val events = streamer.stream("bc-1", "run-1").toList()
        assertThat(events.map { it::class.simpleName }).containsExactly("Status", "Assistant", "Error").inOrder()
        val error = events.last() as RunStreamEvent.Error
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(error.resumeFrom).isEqualTo("10-0")
        // The connection that closed early was retried once, with the resume position; the 503 used up the budget.
        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Last-Event-ID")).isEqualTo("10-0")
        server.shutdown()
    }
}
