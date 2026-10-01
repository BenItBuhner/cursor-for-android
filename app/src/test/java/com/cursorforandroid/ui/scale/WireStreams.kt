package com.cursorforandroid.ui.scale

import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.SseRunStreamer
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.http2.Http2Stream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.internal.duplex.DuplexResponseBody
import okio.BufferedSink
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The workers' run streams held open by an HTTP/2 server as the API holds them, each read by the app's own
 * [SseRunStreamer]: once a worker's stream is open, it is written the worker's next [WorkerScript] event every
 * [beatMs] (a hundred a second, never more), by one thread for every stream, so the server's threads stay few
 * whatever the number of workers. Everything else the app streams goes to the [FakeRunStreamer] as before.
 */
internal class WireStreams(private val runs: List<String>, private val beatMs: Long) : AutoCloseable {
    private val index = runs.withIndex().associate { it.value to it.index }
    private val sinks = ConcurrentHashMap<Int, BufferedSink>()
    private val ticks = LongArray(runs.size)
    private val written = AtomicLongArray(runs.size)
    private val requests = AtomicIntegerArray(runs.size)
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "WireStreams writer").apply { isDaemon = true } }

    private val server = MockWebServer().apply {
        protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val w = index[request.requestUrl?.pathSegments?.getOrNull(4)] ?: return MockResponse().setResponseCode(404)
                requests.incrementAndGet(w)
                return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .removeHeader("Content-Length")
                    .setBody(object : DuplexResponseBody {
                        override fun onRequest(request: RecordedRequest, http2Stream: Http2Stream) {
                            val sink = http2Stream.getSink().buffer()
                            sink.writeUtf8("event: status\ndata: {\"runId\":\"${runs[w]}\",\"status\":\"RUNNING\"}\n\n").flush()
                            written.incrementAndGet(w)
                            sinks[w] = sink
                        }
                    })
            }
        }
        start()
    }

    private val client: OkHttpClient = CursorApiFactory.sseClient(OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build())

    /** The app's streamer: the workers' runs over the wire, anything else through [fake]. */
    fun streamer(fake: FakeRunStreamer): RunStreamer {
        val wire = SseRunStreamer(client, { "key" }, urlFor = { agentId, runId -> server.url("/v1/agents/$agentId/runs/$runId/stream").toString() })
        return object : RunStreamer {
            override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> =
                if (runId in index) wire.stream(agentId, runId, lastEventId) else fake.stream(agentId, runId, lastEventId)
        }
    }

    fun start() {
        writer.scheduleAtFixedRate({
            for ((w, sink) in sinks) {
                val k = ticks[w]
                val (event, data) = WorkerScript.frame(w, k)
                try {
                    sink.writeUtf8("id: ${runs[w]}#${k + 1}\nevent: $event\ndata: $data\n\n").flush()
                } catch (_: IOException) {
                    sinks.remove(w)
                    continue
                }
                ticks[w] = k + 1
                written.incrementAndGet(w)
            }
        }, 0, beatMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        writer.shutdown()
        writer.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Events written on worker [w]'s streams so far, its opening status included. */
    fun written(w: Int): Long = written.get(w)

    /** Streams the server was asked for: one per worker, unless one was opened again. */
    fun opened(): Int = (0 until runs.size).sumOf { requests.get(it) }

    fun streaming(): Int = (0 until runs.size).count { requests.get(it) > 0 }

    override fun close() {
        stop()
        sinks.values.forEach { runCatching { it.close() } }
        runCatching { server.shutdown() }
    }
}
