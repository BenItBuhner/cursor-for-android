package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * What one stream costs in memory while it replays a run with pictures in it. Background live sync keeps up to twenty
 * streams at once, and each replays its run from the first event when it starts, so a switch between Projects starts
 * a burst of them: a frame costing many times its size, or a stream reading far ahead of its collector, is heap the
 * whole burst multiplies.
 */
class StreamMemoryTest {

    private fun imageFrame(id: Int, payloadBytes: Int): String {
        val b64 = "QUJD".repeat(payloadBytes / 4)
        return "id: $id-0\nevent: interaction_update\ndata: {\"type\":\"tool-call-completed\",\"callId\":\"img-$id\"," +
            "\"toolCall\":{\"type\":\"generateImage\",\"args\":{\"prompt\":\"x\"},\"result\":{\"status\":\"success\",\"value\":{\"imageData\":\"$b64\"}}}}\n\n"
    }

    @Test
    fun `reading a picture's frame allocates about twice its size, not nine times`() {
        val allocated = threadAllocatedBytes() ?: return assumeTrue("no allocation counter on this JVM", false)
        val payload = 12 shl 20
        val source = Buffer().writeUtf8(imageFrame(1, payload))
        val before = allocated()
        val frame = SseParser.readFrame(source)!!
        val cost = allocated() - before

        assertThat(frame.data.length).isGreaterThan(payload)
        // It was ~9x: the line decoded, cut twice, appended to a builder of chars that grew by doubling, copied out and trimmed.
        assertThat(cost).isLessThan(3L * payload)
        assertThat(SseParser.parse(frame)).isInstanceOf(SseParser.Parsed.Delivered::class.java)
    }

    @Test
    fun `a stream whose collector is busy reads only a few frames ahead of it`() = runBlocking {
        val frames = 40
        val frameBytes = 256 shl 10
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n" +
                    (1..frames).joinToString("") { imageFrame(it, frameBytes) } +
                    "id: 99-0\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\",\"text\":\"ok\"}\n\nid: 99-0\nevent: done\ndata: {}\n\n",
            ),
        )
        server.start()
        val read = AtomicLong()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body!!
            response.newBuilder().body(CountingBody(body, read)).build()
        }.build()
        val streamer = SseRunStreamer(client, { null }, urlFor = { _, _ -> server.url("/stream").toString() })

        var readWhileBusy = -1L
        withTimeout(30_000) {
            var seen = 0
            streamer.stream("bc-1", "run-1").collect {
                if (it is RunStreamEvent.Interaction && ++seen == 1) {
                    // The collector is busy with the first picture (writing it to the device, say) for a while.
                    delay(1_000)
                    readWhileBusy = read.get()
                }
            }
        }
        server.shutdown()

        // It read the whole run — all 40 pictures, decoded and queued — before: the hand-off held 64 events.
        assertThat(readWhileBusy).isGreaterThan(0L)
        assertThat(readWhileBusy).isLessThan(8L * frameBytes)
    }

    private class CountingBody(private val body: ResponseBody, private val read: AtomicLong) : ResponseBody() {
        private val counted: BufferedSource by lazy {
            object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) read.addAndGet(it) }
            }.buffer()
        }
        override fun contentType(): MediaType? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun source(): BufferedSource = counted
    }

    /** The current thread's allocation counter, where the JVM has one (read reflectively: the Android stubs hide it). */
    private fun threadAllocatedBytes(): (() -> Long)? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        { method.invoke(bean, id) as Long }
    }.getOrNull()
}
