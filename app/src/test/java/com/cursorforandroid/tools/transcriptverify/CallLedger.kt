package com.cursorforandroid.tools.transcriptverify

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every HTTP call the harness's clients make, as method, host and path, the answer's status and how long it took —
 * never a header, a body or a query value: the run list's cursor, the record's indices and the stream's resume
 * position travel in the body or the query and are not what the ledger is for. Installed as an application
 * interceptor on the api.cursor.com client (and so on the SSE client derived from it) and on the api2 client, so the
 * report ends with the account of what was asked of which host, in order.
 */
class CallLedger : Interceptor {

    class Entry(val startedAtMillis: Long, val method: String, val url: String, val code: Int?, val millis: Long, val failure: String?)

    val entries = CopyOnWriteArrayList<Entry>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = System.currentTimeMillis()
        val nanos = System.nanoTime()
        try {
            val response = chain.proceed(request)
            entries += Entry(startedAt, request.method, describe(request.url), response.code, (System.nanoTime() - nanos) / 1_000_000, null)
            return response
        } catch (e: IOException) {
            entries += Entry(startedAt, request.method, describe(request.url), null, (System.nanoTime() - nanos) / 1_000_000, e.javaClass.simpleName)
            throw e
        }
    }

    /** By method and path, how many calls were made and how long they took together: the shape of the run's traffic. */
    fun summary(): String {
        val byPath = entries.groupBy { "${it.method} ${it.url.substringBefore('?')}" }
        return byPath.entries.sortedByDescending { it.value.size }.joinToString("\n") { (key, calls) ->
            val failed = calls.count { it.code == null || it.code >= 400 }
            "  ${calls.size.toString().padStart(4)} × $key  ${calls.sumOf { it.millis }} ms" + (if (failed > 0) "  ($failed failed or refused)" else "")
        }
    }

    fun render(): String = buildString {
        appendLine("calls: ${entries.size} (method host/path → status, ms; query values left out)")
        appendLine(summary())
        appendLine("  in order:")
        entries.forEach { e ->
            appendLine("  ${java.time.Instant.ofEpochMilli(e.startedAtMillis)} ${e.method} ${e.url} → ${e.code ?: e.failure ?: "?"} (${e.millis} ms)")
        }
    }

    private fun describe(url: HttpUrl): String {
        val query = url.queryParameterNames.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?") { "$it=…" }.orEmpty()
        return "${url.host}${url.encodedPath}$query"
    }
}
