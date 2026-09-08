package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetryInterceptorTest {

    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private var now = 1_800_000_000_000L
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(retrying())
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun retrying(onSleep: (Long) -> Unit = {}) = RetryInterceptor(
        maxAttempts = 3,
        baseDelayMs = 100,
        now = { now },
        sleeper = { ms, _ -> sleeps += ms; onSleep(ms) },
        random = { 1.0 },
    )

    private fun get(path: String = "/v1/agents", accept: String? = null) = Request.Builder()
        .url(server.url(path))
        .apply { accept?.let { header("Accept", it) } }
        .build()

    @Test
    fun `a transient server error is retried with backoff and the retry succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        client.newCall(get()).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.body!!.string()).isEqualTo("""{"items":[]}""")
        }
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(sleeps).containsExactly(100L, 200L).inOrder()
    }

    @Test
    fun `the last attempt's failure is returned as-is`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(500) }
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `rate limiting waits for Retry-After when the server names it`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(200) }
        assertThat(sleeps).containsExactly(2_000L)
    }

    @Test
    fun `rate limiting also understands a Retry-After that names a time`() {
        // 1_800_000_000_000 is 2027-01-15T08:00:00Z; the server asks for eight seconds' patience.
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Fri, 15 Jan 2027 08:00:08 GMT"))
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(200) }
        assertThat(sleeps).containsExactly(8_000L)

        // A time already past, and a header in no form at all, both fall back to the jittered backoff.
        sleeps.clear()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Fri, 15 Jan 2027 07:59:00 GMT"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "soon"))
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(200) }
        assertThat(sleeps).containsExactly(100L, 200L).inOrder()
    }

    @Test
    fun `a call cancelled during the backoff is not retried`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))
        lateinit var call: okhttp3.Call
        // The wait is abandoned as soon as the call is cancelled, so the dispatcher thread is not held for it.
        val cancelling = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(retrying(onSleep = { call.cancel() }))
            .build()
        call = cancelling.newCall(get())

        assertThat(runCatching { call.execute().close() }.isFailure).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a dropped connection is retried`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(200) }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `client errors, writes and event streams are never retried`() {
        server.enqueue(MockResponse().setResponseCode(404))
        client.newCall(get()).execute().use { response -> assertThat(response.code).isEqualTo(404) }

        server.enqueue(MockResponse().setResponseCode(503))
        val post = Request.Builder().url(server.url("/v1/agents")).post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(post).execute().use { response -> assertThat(response.code).isEqualTo(503) }

        server.enqueue(MockResponse().setResponseCode(503))
        client.newCall(get("/v1/agents/a/runs/r/stream", accept = "text/event-stream")).execute().use { response -> assertThat(response.code).isEqualTo(503) }

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(sleeps).isEmpty()
    }
}
