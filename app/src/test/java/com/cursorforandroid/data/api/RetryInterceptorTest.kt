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
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxAttempts = 3, baseDelayMs = 100, sleeper = { sleeps += it }, random = { 1.0 }))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

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
