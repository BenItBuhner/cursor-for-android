package com.cursorforandroid.data.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The API key → session exchange against a fake api2: request shape, caching on the JWT's expiry, and the failure modes. */
class SessionTokenProviderTest {

    private val server = MockWebServer()
    private var now = 1_800_000_000_000L
    private var apiKey: String? = "key_abc"
    private lateinit var tokens: SessionTokenProvider

    @Before
    fun setUp() {
        server.start()
        tokens = SessionTokenProvider(
            client = OkHttpClient(),
            apiKeyProvider = { apiKey },
            apiUrl = server.url("/").toString(),
            now = { now },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `exchanges the API key with a bare JSON body and caches the session until it nears its expiry`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"${jwt(expSeconds = now / 1000 + 3600)}","refreshToken":"rt"}"""))

        val first = tokens.accessToken()
        val second = tokens.accessToken()

        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/auth/exchange_user_api_key")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer key_abc")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json")
        assertThat(request.body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `a session about to expire is exchanged again`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"${jwt(expSeconds = now / 1000 + 3600)}","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"${jwt(expSeconds = now / 1000 + 7200)}","refreshToken":"rt"}"""))

        val first = tokens.accessToken()
        now += 3600_000L - SessionTokenProvider.EXPIRY_MARGIN_MS + 1
        val second = tokens.accessToken()

        assertThat(second).isNotEqualTo(first)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a token without a readable expiry lives for the default lifetime`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"opaque-token","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"opaque-token-2","refreshToken":"rt"}"""))

        assertThat(tokens.accessToken()).isEqualTo("opaque-token")
        now += SessionTokenProvider.DEFAULT_LIFETIME_MS - SessionTokenProvider.EXPIRY_MARGIN_MS - 1
        assertThat(tokens.accessToken()).isEqualTo("opaque-token")
        now += 2
        assertThat(tokens.accessToken()).isEqualTo("opaque-token-2")
    }

    @Test
    fun `invalidating drops the session, and a changed key starts a new one`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"accessToken":"t$it","refreshToken":"rt"}""")) }

        assertThat(tokens.accessToken()).isEqualTo("t0")
        tokens.invalidate()
        assertThat(tokens.accessToken()).isEqualTo("t1")
        apiKey = "key_other"
        assertThat(tokens.accessToken()).isEqualTo("t2")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer key_abc")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer key_abc")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer key_other")
    }

    @Test
    fun `a device policy refusal is permanent and explained`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"sign_in_policy_violation"}"""))

        val error = runCatching { tokens.accessToken() }.exceptionOrNull() as SessionUnavailableException

        assertThat(error.code).isEqualTo(SessionUnavailableException.SIGN_IN_POLICY_VIOLATION)
        assertThat(error.isPermanent).isTrue()
        assertThat(error).hasMessageThat().contains("device policy")
    }

    @Test
    fun `a rejected key is permanent, a server error is not`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(503))

        val rejected = runCatching { tokens.accessToken() }.exceptionOrNull() as SessionUnavailableException
        val outage = runCatching { tokens.accessToken() }.exceptionOrNull() as SessionUnavailableException

        assertThat(rejected.isPermanent).isTrue()
        assertThat(outage.isPermanent).isFalse()
        assertThat(outage).hasMessageThat().contains("HTTP 503")
    }

    @Test
    fun `no stored key means no session`() = runBlocking<Unit> {
        apiKey = null

        val error = runCatching { tokens.accessToken() }.exceptionOrNull()

        assertThat(error).isInstanceOf(SessionUnavailableException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `reads the expiry claim of a JWT without verifying it`() {
        assertThat(SessionTokenProvider.jwtExpiryMs(jwt(expSeconds = 1_700_000_000L))).isEqualTo(1_700_000_000_000L)
        assertThat(SessionTokenProvider.jwtExpiryMs("not-a-jwt")).isNull()
        assertThat(SessionTokenProvider.jwtExpiryMs("a.${base64Url("""{"sub":"me"}""")}.c")).isNull()
    }

    private fun jwt(expSeconds: Long): String = "${base64Url("""{"alg":"HS256","typ":"JWT"}""")}.${base64Url("""{"sub":"auth0|u","exp":$expSeconds}""")}.sig"

    private fun base64Url(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
