package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.http2.Http2Stream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.buffer
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What [StreamThreads] counts as a stream's reader: a thread waiting for a stream's bytes, and not one parked a
 * moment on something the reader only passes through (a class being initialized, a lock), which no stream holds.
 */
class StreamThreadsTest {

    private val servers = mutableListOf<MockWebServer>()
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        servers.forEach { runCatching { it.shutdown() } }
    }

    /** One stream that sends [head] of a frame, then waits to be let go before it finishes the frame and the run. */
    private fun server(head: String): MockWebServer = MockWebServer().apply {
        protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .removeHeader("Content-Length")
                .setBody(object : DuplexResponseBody {
                    override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                        val sink = http2Stream.getSink().buffer()
                        runCatching {
                            sink.writeUtf8(head).flush()
                            release.await(30, TimeUnit.SECONDS)
                            sink.writeUtf8("\"}\n\nevent: result\ndata: {\"runId\":\"run-1\",\"status\":\"FINISHED\"}\n\nevent: done\ndata: {}\n\n").close()
                        }
                    }
                })
        }
        start()
    }.also { servers += it }

    private fun client() = OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).readTimeout(30, TimeUnit.SECONDS).build()

    /** The readers counted that are not the multiplexer's own, once there are any. */
    private fun heldReaders(): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            StreamThreads.census().readers.filterNot { it.startsWith(RunStreamMux.THREAD_PREFIX) }.takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(20)
        }
        return emptyList()
    }

    private fun readHeld(head: String, muxed: Boolean): List<String> = runBlocking {
        val server = server(head)
        val client = client()
        val streamer = SseRunStreamer(client, { "key" }, urlFor = { _, runId -> server.url("/v1/agents/bc-1/runs/$runId/stream").toString() }, mux = if (muxed) RunStreamMux(client) else null)
        val events = launch(Dispatchers.Default) { streamer.stream("bc-1", "run-1").toList() }
        val held = heldReaders()
        release.countDown()
        withTimeout(30_000) { events.join() }
        held
    }

    @Test
    fun `a multiplexed stream gathering a frame too big to wait for holds the thread it blocks, and is counted`() {
        val held = readHeld("id: e-1\nevent: assistant\ndata: {\"text\":\"" + "a".repeat(2 shl 20), muxed = true)
        assertWithMessage("threads counted while the frame waited for its end").that(held).hasSize(1)
    }

    @Test
    fun `a stream read through OkHttp holds its thread while it waits, and is counted`() {
        val held = readHeld("id: e-1\nevent: assistant\ndata: {\"text\":\"a", muxed = false)
        assertWithMessage("threads counted while the stream waited").that(held).hasSize(1)
    }

    @Test
    fun `a wait called from the reader is the stream's, and a wait the reader only passes through is not`() {
        fun stack(vararg frames: String) = frames.map { StackTraceElement(it.substringBeforeLast('.'), it.substringAfterLast('.'), null, -1) }
        val below = arrayOf(
            "com.cursorforandroid.data.api.SseRunStreamer\$frames\$1.invokeSuspend",
            "kotlinx.coroutines.DispatchedTask.run",
            "kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker.run",
        )
        assertThat(StreamThreads.awaitsStream(stack("java.lang.Object.wait", "java.lang.Object.wait", "com.cursorforandroid.data.api.RunStreamMux\$Stream.readBlocking", *below))).isTrue()
        assertThat(StreamThreads.awaitsStream(stack("java.lang.Object.wait", "okhttp3.internal.http2.Http2Stream.waitForIo\$okhttp", "okhttp3.internal.http2.Http2Stream\$FramingSource.read", "com.cursorforandroid.data.api.SseParser.readFrame", *below))).isTrue()
        // Another thread initializing the class: the JVM's wait, no frame of its own.
        assertThat(StreamThreads.awaitsStream(stack("com.cursorforandroid.data.api.dto.SseTextDto\$Companion.serializer", "com.cursorforandroid.data.api.SseParser.parse", *below))).isFalse()
        // The writer's queue, taken on the way to asking for a window update.
        assertThat(
            StreamThreads.awaitsStream(
                stack(
                    "jdk.internal.misc.Unsafe.park",
                    "java.util.concurrent.locks.LockSupport.park",
                    "java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire",
                    "java.util.concurrent.locks.ReentrantLock.lock",
                    "java.util.concurrent.ScheduledThreadPoolExecutor\$DelayedWorkQueue.offer",
                    "com.cursorforandroid.data.api.RunStreamMux\$Connection.write",
                    "com.cursorforandroid.data.api.RunStreamMux\$Stream.read",
                    *below,
                ),
            ),
        ).isFalse()
    }
}
