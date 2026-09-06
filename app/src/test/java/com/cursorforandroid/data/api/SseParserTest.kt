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
        val client = OkHttpClient()
        val streamer = SseRunStreamer(client, { "key_test" }, urlFor = { _, _ -> server.url("/v1/agents/bc-1/runs/run-1/stream").toString() })

        val events = streamer.stream("bc-1", "run-1").toList()

        assertThat(events.filterIsInstance<RunStreamEvent.Assistant>().single().text).isEqualTo("part one")
        assertThat(events.filterIsInstance<RunStreamEvent.Result>().single().status).isEqualTo(RunStatus.FINISHED)
        assertThat(events.last()).isEqualTo(RunStreamEvent.Done)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer key_test")
        assertThat(first.getHeader("Accept")).isEqualTo("text/event-stream")
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
        assertThat((events.single() as RunStreamEvent.Error).code).isEqualTo("stream_expired")
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }
}
