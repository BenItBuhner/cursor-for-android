package com.cursorforandroid.data.api

import okhttp3.Connection
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.io.InterruptedIOException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One handshake at a time to a host that has no connection yet, so the calls that set out together — a list's refresh
 * reads `/v0/agents` and `/v1/agents` at once, a cold start opens a dozen chats' reads — share the one HTTP/2
 * connection the first of them opens instead of each opening its own. OkHttp alone lets every call that finds the
 * pool empty connect in parallel: all but one handshake is wasted, and the check that folds a finished duplicate
 * into the first connection is not atomic with pooling it, so now and then both stay pooled — a second connection
 * to the host for as long as the pool keeps it.
 *
 * The first call to a host without a live connection opens it; the others wait until that call is on a connection
 * (then ride it) or has given up (then open their own), no longer than their connect timeout and never past being
 * cancelled. A host whose connection turned out to be HTTP/1 is not gated again: its calls need a connection each.
 *
 * The same instance is installed twice on a root's clients (see [CursorApiFactory]): as a network interceptor, where
 * it sees which connection each exchange rides, and as the last application interceptor, inside the retries, so
 * each attempt is gated on its own and a first call backing off between attempts holds no one.
 */
class ConnectGate : Interceptor {

    private class Host {
        var connection: WeakReference<Connection>? = null
        /** Until a connection says otherwise: the first calls to a host wait for its protocol to be known. */
        var multiplexed = true
        var opening: CountDownLatch? = null
    }

    private val hosts = ConcurrentHashMap<String, Host>()

    override fun intercept(chain: Interceptor.Chain): Response {
        // Only a network interceptor's chain has a connection (see Interceptor.Chain.connection).
        val connection = chain.connection() ?: return gate(chain)
        val host = host(chain.request().url)
        synchronized(host) {
            if (host.connection?.get() !== connection) host.connection = WeakReference(connection)
            host.multiplexed = connection.protocol() == Protocol.HTTP_2 || connection.protocol() == Protocol.H2_PRIOR_KNOWLEDGE
            host.opening?.countDown()
            host.opening = null
        }
        return chain.proceed(chain.request())
    }

    private fun gate(chain: Interceptor.Chain): Response {
        val host = host(chain.request().url)
        var gated: CountDownLatch? = null
        var leading = false
        synchronized(host) {
            val live = host.connection?.get()?.socket()?.isClosed == false
            if (!live && host.multiplexed) {
                leading = host.opening == null
                gated = host.opening ?: CountDownLatch(1).also { host.opening = it }
            }
        }
        val opening = gated ?: return chain.proceed(chain.request())
        if (!leading) {
            awaitOpening(opening, chain)
            return chain.proceed(chain.request())
        }
        try {
            return chain.proceed(chain.request())
        } finally {
            synchronized(host) { if (host.opening === opening) host.opening = null }
            opening.countDown()
        }
    }

    private fun awaitOpening(opening: CountDownLatch, chain: Interceptor.Chain) {
        val budgetMs = chain.connectTimeoutMillis().toLong()
        val deadline = if (budgetMs > 0) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs) else null
        try {
            while (!chain.call().isCanceled()) {
                val slice = deadline?.let { minOf(it - System.nanoTime(), WAIT_SLICE_NANOS) } ?: WAIT_SLICE_NANOS
                if (slice <= 0 || opening.await(slice, TimeUnit.NANOSECONDS)) return
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted waiting for a connection").apply { initCause(e) }
        }
    }

    private fun host(url: HttpUrl): Host = hosts.getOrPut("${url.scheme}://${url.host}:${url.port}") { Host() }

    private companion object {
        /** How often a waiting call looks up to see whether it was cancelled. */
        val WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}
