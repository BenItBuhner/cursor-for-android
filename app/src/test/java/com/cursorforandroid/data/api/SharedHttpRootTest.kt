package com.cursorforandroid.data.api

import com.cursorforandroid.BuildConfig
import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Every client `AppGraph` builds sits on one root ([CursorApiFactory.newRoot]): one connection pool, one thread pool.
 * What each client does with a request — its timeouts, its interceptors, its name lookup, its per-host limit, whether
 * OkHttp may send a request again on its own — is its own, exactly as when each client was built apart.
 */
class SharedHttpRootTest {

    private val root = CursorApiFactory.newRoot()
    private val api = CursorApiFactory.okHttp(root) { "key" }
    private val sse = CursorApiFactory.sseClient(api)
    private val login = CursorApiFactory.loginClient(root)
    private val media = CursorApiFactory.mediaClient(root)
    private val update = CursorApiFactory.updateClient(root)
    private val cursorServer = CursorApiFactory.cursorServerClient(root)
    private val gitHub = CursorApiFactory.gitHubClient(root)
    private val origin = CursorApiFactory.originClient(root)
    private val all = listOf(api, sse, login, media, update, cursorServer, gitHub, origin)

    private val server = MockWebServer()

    @Before
    fun setUp() {
        // These tests run against the debug build, where the logging clients carry their logger.
        assertThat(BuildConfig.DEBUG).isTrue()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.dispatcher.executorService.shutdownNow()
        root.connectionPool.evictAll()
    }

    @Test
    fun `every client shares the root's connection pool and threads, each with a dispatcher of its own`() {
        for (client in all) {
            assertThat(client.connectionPool).isSameInstanceAs(root.connectionPool)
            assertThat(client.dispatcher.executorService).isSameInstanceAs(root.dispatcher.executorService)
            assertThat(client.dispatcher).isNotSameInstanceAs(root.dispatcher)
        }
        // The SSE client rides the API client's dispatcher, as it always has; every other client has its own.
        assertThat(sse.dispatcher).isSameInstanceAs(api.dispatcher)
        val own = listOf(api, login, media, update, cursorServer, gitHub, origin).map { it.dispatcher }
        assertThat(own.toSet()).hasSize(own.size)
    }

    @Test
    fun `each client keeps its timeouts, interceptors, lookup and recovery as before`() {
        assertThat(config(api)).isEqualTo(Config(20_000, 60_000, 30_000, 300_000, LastGoodDns.CURSOR, listOf("Auth", "OneShotWrites", "Retry", "Logging")))
        assertThat(config(sse)).isEqualTo(Config(20_000, 120_000, 30_000, 0, LastGoodDns.CURSOR, listOf("Auth", "OneShotWrites", "Retry", "Logging")))
        assertThat(config(login)).isEqualTo(Config(20_000, 30_000, 10_000, 45_000, LastGoodDns.CURSOR, listOf("UserAgent")))
        assertThat(config(media)).isEqualTo(Config(20_000, 60_000, 10_000, 0, Dns.SYSTEM, listOf("Logging")))
        assertThat(config(update)).isEqualTo(Config(20_000, 60_000, 10_000, 0, Dns.SYSTEM, listOf("UserAgent", "Retry", "Logging")))
        assertThat(config(cursorServer)).isEqualTo(Config(20_000, 30_000, 10_000, 60_000, Dns.SYSTEM, emptyList()))
        assertThat(config(gitHub)).isEqualTo(Config(20_000, 30_000, 10_000, 45_000, Dns.SYSTEM, listOf("UserAgent", "Logging")))
        // Origin's is GitHub's bare client on the API's lookup, so it can share the API's connection to api.cursor.com.
        assertThat(config(origin)).isEqualTo(Config(20_000, 30_000, 10_000, 45_000, LastGoodDns.CURSOR, listOf("UserAgent", "Logging")))

        for (client in all) {
            assertThat(client.networkInterceptors).isEmpty()
            assertThat(client.retryOnConnectionFailure).isTrue()
            assertThat(client.followRedirects).isTrue()
            assertThat(client.protocols).containsExactly(Protocol.HTTP_2, Protocol.HTTP_1_1).inOrder()
            assertThat(client.pingIntervalMillis).isEqualTo(0)
            assertThat(client.dispatcher.maxRequests).isEqualTo(64)
            assertThat(client.dispatcher.maxRequestsPerHost).isEqualTo(5)
        }
        // Each client's retry interceptor, and the pause it remembers, is its own.
        assertThat(api.interceptors.filterIsInstance<RetryInterceptor>().single())
            .isNotSameInstanceAs(update.interceptors.filterIsInstance<RetryInterceptor>().single())
    }

    @Test
    fun `a client that widens its own per-host limit leaves the others' alone`() {
        login.dispatcher.maxRequestsPerHost = ApiThrottle.ON_THE_WIRE + 2

        assertThat(api.dispatcher.maxRequestsPerHost).isEqualTo(5)
        assertThat(media.dispatcher.maxRequestsPerHost).isEqualTo(5)
        assertThat(origin.dispatcher.maxRequestsPerHost).isEqualTo(5)
    }

    @Test
    fun `origin's requests ride the API client's connection, without the key`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        api.newCall(Request.Builder().url(server.url("/v0/me")).build()).execute().use { assertThat(it.code).isEqualTo(200) }
        origin.newCall(Request.Builder().url(server.url("/v1/origin/repos")).build()).execute().use { assertThat(it.code).isEqualTo(200) }

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer key")
        assertThat(second.getHeader("Authorization")).isNull()
        assertThat(second.sequenceNumber).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a write whose reply is lost goes out once, on the shared root too`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))

        val post = Request.Builder().url(server.url("/v1/agents/bc-1/runs")).post("{}".toRequestBody("application/json".toMediaType())).build()
        assertThrows(IOException::class.java) { api.newCall(post).execute().close() }

        assertThat(server.requestCount).isEqualTo(1)
    }

    private data class Config(val connectMs: Int, val readMs: Int, val writeMs: Int, val callMs: Int, val dns: Dns, val interceptors: List<String>)

    private fun config(client: OkHttpClient) = Config(
        client.connectTimeoutMillis,
        client.readTimeoutMillis,
        client.writeTimeoutMillis,
        client.callTimeoutMillis,
        client.dns,
        client.interceptors.map(::kind),
    )

    private fun kind(interceptor: Interceptor): String = when (interceptor) {
        is AuthInterceptor -> "Auth"
        is OneShotWritesInterceptor -> "OneShotWrites"
        is RetryInterceptor -> "Retry"
        is HttpLoggingInterceptor -> "Logging"
        else -> {
            // The factory's one lambda interceptor: it sets the User-Agent and nothing else.
            val seen = mutableListOf<Request>()
            interceptor.intercept(FakeChain(seen))
            val sent = seen.single()
            check(sent.header("User-Agent") == "cursor-for-android/${BuildConfig.VERSION_NAME}" && sent.headers.size == 1) { "unexpected interceptor $interceptor" }
            "UserAgent"
        }
    }

    private class FakeChain(private val seen: MutableList<Request>) : Interceptor.Chain {
        private val original = Request.Builder().url("https://example.invalid/").build()
        override fun request(): Request = original
        override fun proceed(request: Request): okhttp3.Response {
            seen += request
            return okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(204).message("").build()
        }
        override fun connection(): okhttp3.Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
