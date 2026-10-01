package com.cursorforandroid.data.api

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CertificatePinner
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Reader
import okhttp3.internal.http2.Http2Writer
import okhttp3.internal.http2.Settings
import okhttp3.internal.http2.StreamResetException
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.resume

/**
 * Run streams over HTTP/2 without a thread per stream. OkHttp reads a response body only by blocking a thread in it
 * (its connection's one reader fills each stream's buffer and wakes whoever waits there), so a coordinator's 500
 * followed subagents held 500 parked threads. Here each connection has one reader, which moves a DATA frame's bytes
 * into its stream's inbox and resumes the stream's coroutine, and one writer, which sends what the streams ask for
 * (HEADERS, WINDOW_UPDATE, RST_STREAM) and watches them for silence. A stream that is waiting holds no thread.
 *
 * Flow control keeps one stream's slow reader from stalling the others: a stream's window is credited back only as
 * its consumer takes bytes out of its inbox, so a stream nobody reads fills its own [STREAM_WINDOW] and the server
 * stops sending on it alone; the connection's window is credited as bytes arrive, so the others keep flowing. Only
 * while the connection holds more than [BUFFER_CAP] unread across its streams does it credit on consumption instead,
 * which bounds what a crowd of stalled streams can hold.
 *
 * It speaks for [client] as OkHttp would — its DNS, socket factory, TLS, protocols, timeouts — and takes on only what
 * it can do the same way: an `https` URL with no proxy and no certificate pins that negotiates `h2`, or a cleartext
 * one on a client configured for HTTP/2 prior knowledge. Anything else ([canServe] false, or [Http2Unavailable] from
 * [open]) is the caller's to send through OkHttp as before. Streams have connections of their own, beside OkHttp's pool.
 *
 * [onOpen] and [onRead] observe the wire, for tests that count calls and bytes (OkHttp's listeners never see these).
 */
class RunStreamMux(
    private val client: OkHttpClient,
    private val onOpen: () -> Unit = {},
    private val onRead: (Long) -> Unit = {},
) {
    /** A server that answered without `h2`: its streams go through OkHttp from then on. */
    class Http2Unavailable(message: String) : Exception(message)

    /** The response head of a stream: its status and headers, without the pseudo-headers. */
    class Head(val code: Int, val headers: Headers)

    private val lock = Any()
    private val connections = HashMap<String, MutableList<Connection>>()
    private val connecting = HashMap<String, Deferred<Connection>>()
    private val noH2 = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connections open at this moment. */
    val connectionCount: Int get() = synchronized(lock) { connections.values.sumOf { it.size } }

    /** Streams open at this moment, across every connection. */
    val streamCount: Int get() = synchronized(lock) { connections.values.flatten() }.sumOf { it.openStreams() }

    /** Whether [url] can be streamed here at all; false sends it through OkHttp. */
    fun canServe(url: HttpUrl): Boolean {
        if (key(url) in noH2) return false
        val direct = client.proxy?.let { it.type() == Proxy.Type.DIRECT }
            ?: runCatching { client.proxySelector.select(url.toUri()) }.getOrNull().orEmpty().all { it.type() == Proxy.Type.DIRECT }
        if (!direct) return false
        return if (url.isHttps) {
            client.certificatePinner == CertificatePinner.DEFAULT && Protocol.HTTP_2 in client.protocols &&
                client.connectionSpecs.any { it.isTls } && runCatching { client.sslSocketFactory }.isSuccess
        } else {
            client.protocols == listOf(Protocol.H2_PRIOR_KNOWLEDGE) && Platform.get().isCleartextTrafficPermitted(url.host)
        }
    }

    /**
     * A stream for [request] (a `GET`), its HEADERS on their way: on a connection to its host with room for it, or a
     * new one. Throws [IOException] for a connection that would not open (the caller's retry), [Http2Unavailable] for
     * a server without `h2`.
     */
    suspend fun open(request: Request): Stream {
        val url = request.url
        val key = key(url)
        val block = headerBlock(request)
        while (true) {
            val pending: Deferred<Connection>
            synchronized(lock) {
                connections[key]?.firstOrNull { it.reserve() }?.let { return it.newStream(block) }
                pending = connecting[key] ?: scope.async(start = CoroutineStart.LAZY) { connect(url, key) }.also {
                    connecting[key] = it
                    it.start()
                }
            }
            try {
                pending.await()
            } catch (e: RuntimeException) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IOException(e.message ?: "connection failed", e)
            }
        }
    }

    private fun key(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    private suspend fun connect(url: HttpUrl, key: String): Connection {
        try {
            val socket = openSocket(url)
            val connection = try {
                Connection(socket, key, url.host).also { it.begin() }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            val wait = client.connectTimeoutMillis.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE
            if (withTimeoutOrNull(wait) { connection.ready.await() } == null) {
                connection.shutdown(SocketTimeoutException("timeout"))
                throw SocketTimeoutException("timeout")
            }
            synchronized(lock) {
                if (!connection.isShutdown) connections.getOrPut(key) { mutableListOf() }.add(connection)
            }
            return connection
        } finally {
            synchronized(lock) { connecting.remove(key) }
        }
    }

    private fun openSocket(url: HttpUrl): Socket {
        var failure: IOException? = null
        for (address in client.dns.lookup(url.host)) {
            val raw = client.socketFactory.createSocket()
            try {
                Platform.get().connectSocket(raw, InetSocketAddress(address, url.port), client.connectTimeoutMillis)
            } catch (e: IOException) {
                runCatching { raw.close() }
                failure = failure?.also { it.addSuppressed(e) } ?: e
                continue
            }
            if (!url.isHttps) return raw
            return try {
                handshake(raw, url)
            } catch (e: Throwable) {
                runCatching { raw.close() }
                throw e
            }
        }
        throw failure ?: java.net.UnknownHostException("${url.host}: no addresses")
    }

    /** TLS as OkHttp sets it up for [client]: its connection specs, ALPN, hostname verifier. Pins never get here. */
    private fun handshake(raw: Socket, url: HttpUrl): Socket {
        val ssl = client.sslSocketFactory.createSocket(raw, url.host, url.port, true) as SSLSocket
        val spec = client.connectionSpecs.firstOrNull { it.isTls && it.isCompatible(ssl) }
            ?: throw java.net.UnknownServiceException("no TLS connection spec fits ${url.host}")
        spec.tlsVersions?.map { it.javaName }?.let { wanted -> ssl.enabledProtocols = ssl.enabledProtocols.filter { it in wanted }.toTypedArray() }
        spec.cipherSuites?.map { it.javaName }?.let { wanted -> ssl.enabledCipherSuites = ssl.enabledCipherSuites.filter { it in wanted }.toTypedArray() }
        val platform = Platform.get()
        if (spec.supportsTlsExtensions) platform.configureTlsExtensions(ssl, url.host, client.protocols)
        ssl.soTimeout = client.readTimeoutMillis
        try {
            ssl.startHandshake()
            if (!client.hostnameVerifier.verify(url.host, ssl.session)) throw SSLPeerUnverifiedException("Hostname ${url.host} not verified")
            val alpn = if (spec.supportsTlsExtensions) platform.getSelectedProtocol(ssl) else null
            if (alpn != Protocol.HTTP_2.toString()) {
                noH2 += key(url)
                throw Http2Unavailable("${url.host} negotiated ${alpn ?: "no protocol"}")
            }
        } finally {
            platform.afterHandshake(ssl)
        }
        ssl.soTimeout = 0
        return ssl
    }

    private fun headerBlock(request: Request): List<Header> {
        val url = request.url
        val authority = (if (':' in url.host) "[${url.host}]" else url.host) +
            (if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else "")
        val block = ArrayList<Header>(request.headers.size + 4)
        block += Header(Header.TARGET_METHOD, request.method)
        block += Header(Header.TARGET_PATH, url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
        block += Header(Header.TARGET_AUTHORITY, authority)
        block += Header(Header.TARGET_SCHEME, url.scheme)
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i).lowercase()
            if (name !in CONNECTION_HEADERS) block += Header(name, request.headers.value(i))
        }
        return block
    }

    private fun removed(connection: Connection) {
        synchronized(lock) {
            val list = connections[connection.key] ?: return
            list.remove(connection)
            if (list.isEmpty()) connections.remove(connection.key)
        }
    }

    /** One HTTP/2 connection: its socket, its reader thread and its writer, and the streams it carries. Locks before its streams. */
    internal inner class Connection(private val socket: Socket, val key: String, host: String) : Http2Reader.Handler {
        val ready = CompletableDeferred<Unit>()
        private val sink = socket.sink().also { it.timeout().timeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS) }.buffer()
        private val writer = Http2Writer(sink, true)
        private val reader = Http2Reader(socket.source().buffer(), true)
        private val writes = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "$THREAD_PREFIX writer $host").apply { isDaemon = true } }
        private val readerThread = Thread(::readLoop, "$THREAD_PREFIX reader $host").apply { isDaemon = true }
        private val scratch = Buffer()

        // Guarded by this.
        private val peerSettings = Settings()
        private val streams = HashMap<Int, Stream>()
        private var reserved = 0
        private var nextStreamId = 1
        private var shutdown = false
        private var maxConcurrent = Int.MAX_VALUE
        private var buffered = 0L
        private var owedCredit = 0L
        private var deferredCredit = 0L
        private var idleSince = System.nanoTime()
        @Volatile private var lastFrameAt = System.nanoTime()

        val isShutdown: Boolean get() = synchronized(this) { shutdown }

        fun openStreams(): Int = synchronized(this) { reserved }

        fun begin() {
            writer.connectionPreface()
            writer.settings(Settings().set(Settings.ENABLE_PUSH, 0).set(Settings.INITIAL_WINDOW_SIZE, STREAM_WINDOW))
            writer.windowUpdate(0, (CONN_WINDOW - Settings.DEFAULT_INITIAL_WINDOW_SIZE).toLong())
            writer.flush()
            writes.removeOnCancelPolicy = true
            val timeout = client.readTimeoutMillis.toLong()
            val tick = if (timeout > 0) (timeout / 8).coerceIn(10L, 1_000L) else 1_000L
            writes.scheduleWithFixedDelay(::watch, tick, tick, TimeUnit.MILLISECONDS)
            readerThread.start()
        }

        fun reserve(): Boolean = synchronized(this) {
            if (shutdown || reserved >= maxConcurrent || nextStreamId > MAX_STREAM_ID) return false
            reserved++
            true
        }

        /** A stream in a slot [reserve] took; its id is given when its HEADERS are written, so ids go out in order. */
        fun newStream(block: List<Header>): Stream {
            val stream = Stream(this)
            onOpen()
            write {
                val id = synchronized(this) {
                    if (stream.isClosed) return@write
                    if (shutdown) 0 else nextStreamId.also {
                        nextStreamId += 2
                        stream.id = it
                        streams[it] = stream
                    }
                }
                if (id == 0) {
                    stream.fail(IOException("connection shut down"))
                    return@write
                }
                writer.headers(true, id, block)
                writer.flush()
            }
            return stream
        }

        /** [stream] is done with, having left [discarded] bytes unread; [reset] asks the server to stop sending it. */
        fun release(stream: Stream, id: Int, discarded: Long, reset: Boolean) {
            val drained = synchronized(this) {
                if (id != 0) streams.remove(id)
                reserved--
                if (reserved == 0) idleSince = System.nanoTime()
                shutdown && reserved == 0
            }
            if (discarded > 0) consumed(discarded)
            if (reset && id != 0) write { writer.rstStream(id, ErrorCode.CANCEL); writer.flush() }
            if (drained) write { shutdown(IOException("connection closed")) }
        }

        /** Bytes a stream's consumer took (or dropped): the connection's credit for them, if it was held back. */
        fun consumed(bytes: Long) {
            val increment = synchronized(this) {
                buffered -= bytes
                val released = minOf(deferredCredit, bytes)
                deferredCredit -= released
                owedCredit += released
                takeCredit()
            }
            if (increment > 0) write { writer.windowUpdate(0, increment); writer.flush() }
        }

        fun creditStream(id: Int, increment: Long) = write { writer.windowUpdate(id, increment); writer.flush() }

        /** Credit owed the server, once there is enough of it to be worth a frame. Called holding this. */
        private fun takeCredit(): Long = if (owedCredit < CONN_WINDOW / 2) 0L else owedCredit.also { owedCredit = 0L }

        private fun write(task: () -> Unit) {
            try {
                writes.execute {
                    try {
                        task()
                    } catch (e: IOException) {
                        shutdown(e)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Shut down: its streams have been failed already.
            }
        }

        private fun readLoop() {
            val failure = try {
                reader.readConnectionPreface(this)
                ready.complete(Unit)
                while (reader.nextFrame(false, this)) lastFrameAt = System.nanoTime()
                IOException("connection closed")
            } catch (e: IOException) {
                e
            } catch (e: RuntimeException) {
                IOException(e.message ?: "HTTP/2 protocol error", e)
            }
            shutdown(failure)
        }

        /** Nothing is written or read on this connection again; every stream still on it fails with [cause]. */
        fun shutdown(cause: IOException) {
            val failed = synchronized(this) {
                shutdown = true
                streams.values.toList().also { streams.clear() }
            }
            failed.forEach { it.fail(cause) }
            ready.completeExceptionally(cause)
            removed(this)
            writes.shutdownNow()
            runCatching { socket.close() }
        }

        /** The writer's watch: streams silent past the read timeout fail as OkHttp's read would, and an idle connection closes. */
        private fun watch() {
            val now = System.nanoTime()
            val timeoutNs = TimeUnit.MILLISECONDS.toNanos(client.readTimeoutMillis.toLong())
            val (open, idle) = synchronized(this) { streams.values.toList() to (reserved == 0 && now - idleSince >= IDLE_NS) }
            if (idle) {
                runCatching { writer.goAway(0, ErrorCode.NO_ERROR, ByteArray(0)); writer.flush() }
                shutdown(IOException("idle"))
                return
            }
            if (timeoutNs <= 0) return
            var timedOut = false
            for (stream in open) if (stream.silentFor(now) >= timeoutNs) {
                stream.fail(SocketTimeoutException("timeout"))
                timedOut = true
            }
            // As OkHttp treats a degraded connection: a stream timed out, and the connection has been as quiet.
            if (timedOut && now - lastFrameAt >= timeoutNs) shutdown(SocketTimeoutException("timeout"))
        }

        override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
            scratch.clear()
            source.readFully(scratch, length.toLong())
            onRead(length.toLong())
            val size = length.toLong()
            val stream: Stream?
            val increment = synchronized(this) {
                stream = streams[streamId]
                if (stream != null) {
                    buffered += size
                    if (buffered <= BUFFER_CAP) owedCredit += size else deferredCredit += size
                } else {
                    owedCredit += size
                }
                takeCredit()
            }
            if (increment > 0) write { writer.windowUpdate(0, increment); writer.flush() }
            if (stream != null && !stream.receive(scratch, inFinished)) consumed(size)
            scratch.clear()
        }

        override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
            synchronized(this) { streams[streamId] }?.receiveHeaders(headerBlock, inFinished)
        }

        override fun rstStream(streamId: Int, errorCode: ErrorCode) {
            synchronized(this) { streams[streamId] }?.fail(StreamResetException(errorCode))
        }

        override fun settings(clearPrevious: Boolean, settings: Settings) {
            val applied = synchronized(this) {
                if (clearPrevious) peerSettings.clear()
                peerSettings.merge(settings)
                maxConcurrent = peerSettings.getMaxConcurrentStreams()
                Settings().apply { merge(peerSettings) }
            }
            write { writer.applyAndAckSettings(applied) }
        }

        override fun ackSettings() = Unit

        override fun ping(ack: Boolean, payload1: Int, payload2: Int) {
            if (!ack) write { writer.ping(true, payload1, payload2); writer.flush() }
        }

        /** No new streams here; those the server never took fail as refused, which the caller retries on another connection. */
        override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {
            val refused = synchronized(this) {
                shutdown = true
                streams.filterKeys { it > lastGoodStreamId }.values.toList().onEach { streams.remove(it.id) }
            }
            removed(this)
            refused.forEach { it.fail(StreamResetException(ErrorCode.REFUSED_STREAM)) }
        }

        override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) = Unit
        override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) = Unit
        override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {
            write { writer.rstStream(promisedStreamId, ErrorCode.PROTOCOL_ERROR); writer.flush() }
        }
        override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) = Unit
    }

    /**
     * One run stream: the response head, then the body's bytes as the connection's reader hands them over. Read by
     * one consumer at a time — [awaitHead], then [read] — which suspends while there is nothing to read; [close] once
     * done with it, however it ended.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    inner class Stream internal constructor(private val owner: Connection) {
        private val monitor = this as java.lang.Object
        /** Given under the connection's lock when the stream's HEADERS go out; 0 until then. */
        @Volatile internal var id = 0
        // Guarded by this.
        private val inbox = Buffer()
        private var head: Head? = null
        private var remoteFinished = false
        private var failure: IOException? = null
        private var closed = false
        private var waiter: CancellableContinuation<Unit>? = null
        private var blockedReaders = 0
        private var waitingSince = System.nanoTime()
        private var unacked = 0L

        internal val isClosed: Boolean get() = synchronized(this) { closed }

        /** How long the consumer has waited with nothing to take: what OkHttp's read timeout measures. */
        internal fun silentFor(now: Long): Long = synchronized(this) {
            if (waitingSince == 0L || closed || remoteFinished || failure != null) 0L else now - waitingSince
        }

        /** The reader's DATA for this stream; false when nobody will read it (the connection's to credit). */
        internal fun receive(bytes: Buffer, finished: Boolean): Boolean {
            val wake = synchronized(this) {
                if (closed) return false
                inbox.write(bytes, bytes.size)
                if (finished) remoteFinished = true
                takeWaiter()
            }
            wake?.resume(Unit)
            return true
        }

        internal fun receiveHeaders(block: List<Header>, finished: Boolean) {
            val wake = synchronized(this) {
                if (head == null) {
                    val status = block.firstOrNull { it.name == Header.RESPONSE_STATUS }?.value?.utf8()?.toIntOrNull()
                    if (status == null) {
                        if (failure == null) failure = IOException("Expected ':status' header not present")
                    } else if (status !in 100..199) {
                        val headers = Headers.Builder()
                        for (header in block) if (!header.name.startsWith(Header.PSEUDO_PREFIX)) headers.addUnsafeNonAscii(header.name.utf8(), header.value.utf8())
                        head = Head(status, headers.build())
                    }
                }
                if (finished) remoteFinished = true
                takeWaiter()
            }
            wake?.resume(Unit)
        }

        /** The stream failed: what already arrived is still read, then [e] is thrown. Nothing after a clean end. */
        internal fun fail(e: IOException) {
            val wake = synchronized(this) {
                if (failure == null && !remoteFinished) failure = e
                takeWaiter()
            }
            wake?.resume(Unit)
        }

        /** Called holding this. */
        private fun takeWaiter(): CancellableContinuation<Unit>? {
            if (blockedReaders > 0) monitor.notifyAll()
            return waiter.also { waiter = null }
        }

        /** The response head; throws what failed the stream before it came. */
        suspend fun awaitHead(): Head {
            while (true) {
                synchronized(this) {
                    head?.let { return it }
                    failure?.let { throw it }
                    if (remoteFinished) throw IOException("stream ended before its headers")
                    if (closed) throw IOException("stream closed")
                }
                awaitChange { head != null || failure != null || remoteFinished || closed }
            }
        }

        /**
         * Moves what has arrived into [sink], suspending until something has: its byte count, or -1 once the stream
         * ended. What arrived before a failure is read first; then the failure is thrown.
         */
        suspend fun read(sink: Buffer): Long {
            while (true) {
                takeInto(sink)?.let { return it }
                awaitChange { inbox.size > 0 || failure != null || remoteFinished || closed }
            }
        }

        /** [read] on a thread that may block in it, for a frame too big to gather without one (see `SseStreamReader`). */
        fun readBlocking(sink: Buffer): Long {
            while (true) {
                takeInto(sink)?.let { return it }
                synchronized(this) {
                    if (inbox.size == 0L && failure == null && !remoteFinished && !closed) {
                        if (waitingSince == 0L) waitingSince = System.nanoTime()
                        blockedReaders++
                        try {
                            monitor.wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException("interrupted")
                        } finally {
                            blockedReaders--
                        }
                    }
                }
            }
        }

        /** What [read] returns without waiting, or null when it has to wait. */
        private fun takeInto(sink: Buffer): Long? {
            val count: Long
            var credit = 0L
            val streamId: Int
            synchronized(this) {
                count = inbox.size
                if (count == 0L) {
                    failure?.let { throw it }
                    if (remoteFinished) return -1L
                    if (closed) throw IOException("stream closed")
                    return null
                }
                sink.write(inbox, count)
                waitingSince = 0L
                if (!remoteFinished) {
                    unacked += count
                    if (unacked >= STREAM_WINDOW / 2) credit = unacked.also { unacked = 0L }
                }
                streamId = id
            }
            if (credit > 0) owner.creditStream(streamId, credit)
            owner.consumed(count)
            return count
        }

        private suspend fun awaitChange(ready: () -> Boolean) {
            suspendCancellableCoroutine { cont ->
                val now = synchronized(this) {
                    ready() || false.also {
                        waiter = cont
                        if (waitingSince == 0L) waitingSince = System.nanoTime()
                    }
                }
                if (now) cont.resume(Unit) else cont.invokeOnCancellation { synchronized(this) { if (waiter === cont) waiter = null } }
            }
        }

        /** Done with: what is unread is dropped, and a stream the server is still sending is reset. */
        fun close() {
            val discarded: Long
            val reset: Boolean
            val streamId: Int
            val wake: CancellableContinuation<Unit>?
            synchronized(owner) {
                synchronized(this) {
                    if (closed) return
                    closed = true
                    discarded = inbox.size
                    inbox.clear()
                    reset = !remoteFinished && failure !is StreamResetException
                    streamId = id
                    wake = takeWaiter()
                }
            }
            wake?.resume(Unit)
            owner.release(this, streamId, discarded, reset)
        }
    }

    companion object {
        /** Names the threads this keeps: a reader and a writer per connection, whatever the number of streams. */
        const val THREAD_PREFIX = "RunStreams"

        /** Each stream's receive window: what a stream whose consumer stopped reading holds before the server stops sending it. */
        const val STREAM_WINDOW = 1 shl 20

        /** The connection's receive window, OkHttp's own. */
        private const val CONN_WINDOW = 16 shl 20

        /** Unread bytes across a connection's streams past which the connection's window waits on their readers too. */
        private const val BUFFER_CAP = 16L shl 20

        /** How long a connection with no streams stays open for the next. */
        private val IDLE_NS = TimeUnit.SECONDS.toNanos(60)

        private const val MAX_STREAM_ID = Int.MAX_VALUE - 2

        /** Headers HTTP/2 forbids (RFC 9113 §8.2.2), dropped as OkHttp drops them. */
        private val CONNECTION_HEADERS = setOf("connection", "host", "keep-alive", "proxy-connection", "te", "transfer-encoding", "encoding", "upgrade")
    }
}
