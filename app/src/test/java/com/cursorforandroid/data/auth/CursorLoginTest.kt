package com.cursorforandroid.data.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The browser sign-in against a fake api2: PKCE derivation, the poll loop and the key-minting RPC. */
class CursorLoginTest {

    private val server = MockWebServer()
    private val delays = mutableListOf<Long>()
    private val callsStarted = AtomicInteger()
    private val callsCanceled = AtomicInteger()

    /** For the calls the sign-in makes: [okhttp3.Call.enqueue] reports both of these on the thread that asked. */
    private val watcher = object : EventListener() {
        override fun callStart(call: Call) { callsStarted.incrementAndGet() }
        override fun canceled(call: Call) { callsCanceled.incrementAndGet() }
    }

    private lateinit var login: CursorLogin

    @Before
    fun setUp() {
        server.start()
        login = CursorLogin(
            client = OkHttpClient.Builder().eventListener(watcher).build(),
            websiteUrl = "https://cursor.test",
            apiUrl = server.url("/").toString(),
            sleep = { delays += it },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `handshake derives the challenge from the verifier and keeps the verifier out of the URL`() {
        val handshake = login.startHandshake()

        assertThat(handshake.verifier).matches("[A-Za-z0-9_-]{43}")
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(handshake.verifier.toByteArray()))
        assertThat(UUID.fromString(handshake.uuid).toString()).isEqualTo(handshake.uuid)
        assertThat(handshake.loginUrl).startsWith("https://cursor.test/loginDeepControl?")
        assertThat(handshake.loginUrl).contains("challenge=$expectedChallenge")
        assertThat(handshake.loginUrl).contains("uuid=${handshake.uuid}")
        assertThat(handshake.loginUrl).contains("mode=login")
        assertThat(handshake.loginUrl).contains("redirectTarget=sdk")
        assertThat(handshake.loginUrl).doesNotContain(handshake.verifier)

        assertThat(login.startHandshake().verifier).isNotEqualTo(handshake.verifier)
    }

    @Test
    fun `polls with the verifier in a POST body until the login is confirmed`() = runBlocking<Unit> {
        server.enqueue(pending())
        server.enqueue(pending())
        server.enqueue(MockResponse().setBody("""{"accessToken":"at-1","refreshToken":"rt-1"}"""))
        val handshake = login.startHandshake()

        val tokens = login.awaitTokens(handshake)

        assertThat(tokens).isEqualTo(SessionTokens("at-1", "rt-1"))
        assertThat(server.requestCount).isEqualTo(3)
        repeat(3) {
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/auth/poll")
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertThat(body["uuid"]?.jsonPrimitive?.content).isEqualTo(handshake.uuid)
            assertThat(body["verifier"]?.jsonPrimitive?.content).isEqualTo(handshake.verifier)
        }
        // 1s, then 1.2x per pending answer.
        assertThat(delays).containsExactly(1_000L, 1_200L).inOrder()
    }

    @Test
    fun `falls back to GET once when the backend has no POST route`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Route POST:/auth/poll not found","error":"Not Found","statusCode":404}"""))
        server.enqueue(pending())
        server.enqueue(MockResponse().setBody("""{"accessToken":"at","refreshToken":"rt"}"""))
        val handshake = login.startHandshake()

        assertThat(login.awaitTokens(handshake).accessToken).isEqualTo("at")

        server.takeRequest()
        val fallback = server.takeRequest()
        assertThat(fallback.method).isEqualTo("GET")
        assertThat(fallback.requestUrl?.queryParameter("uuid")).isEqualTo(handshake.uuid)
        assertThat(fallback.requestUrl?.queryParameter("verifier")).isEqualTo(handshake.verifier)
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }

    @Test
    fun `a 404 that is not this route's keeps the verifier out of the query string`() = runBlocking<Unit> {
        // Pending logins answer "Not found" in plain text; a route-not-found for something else is not our cue either.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Route GET:/v1/nope not found","error":"Not Found","statusCode":404}"""))
        server.enqueue(MockResponse().setBody("""{"accessToken":"at","refreshToken":"rt"}"""))
        val handshake = login.startHandshake()

        assertThat(login.awaitTokens(handshake).accessToken).isEqualTo("at")

        assertThat(server.takeRequest().method).isEqualTo("POST")
        val second = server.takeRequest()
        assertThat(second.method).isEqualTo("POST")
        assertThat(second.path).doesNotContain(handshake.verifier)
    }

    @Test
    fun `gives up after three consecutive unexpected answers`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

        val error = runCatching { login.awaitTokens(login.startHandshake()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CursorLoginException::class.java)
        assertThat(error).hasMessageThat().contains("HTTP 500")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a pending login that is never confirmed times out`() = runBlocking<Unit> {
        repeat(2) { server.enqueue(pending()) }

        val error = runCatching { login.awaitTokens(login.startHandshake(), maxAttempts = 2) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CursorLoginException::class.java)
        assertThat(error).hasMessageThat().contains("wasn't confirmed in time")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `mints the key over Connect JSON with the session token and the expiry in millis`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"apiKey":"key_minted"}"""))

        val key = login.mintApiKey(accessToken = "session-token", name = "Cursor for Android (Pixel)", expiresAtMs = 1_735_689_600_000L)

        assertThat(key).isEqualTo("key_minted")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/CreateUserApiKey")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-token")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body["name"]?.jsonPrimitive?.content).isEqualTo("Cursor for Android (Pixel)")
        assertThat(body["expiresAt"]?.jsonPrimitive?.content).isEqualTo("1735689600000")
    }

    @Test
    fun `a key without an expiry omits the field`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"api_key":"key_forever"}"""))

        assertThat(login.mintApiKey("t", "name", expiresAtMs = null)).isEqualTo("key_forever")

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(body.keys).containsExactly("name")
    }

    @Test
    fun `a refused key surfaces the dashboard's explanation and points at the pasted-key path`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(
                """{"code":"unauthenticated","message":"Error","details":[{"type":"aiserver.v1.ErrorDetails","debug":{"error":"ERROR_NOT_LOGGED_IN","details":{"title":"Authentication error","detail":"If you are logged in, try logging out and back in.","isRetryable":false}}}]}""",
            ),
        )

        val error = runCatching { login.mintApiKey("t", "name", null) }.exceptionOrNull()

        assertThat(error).isInstanceOf(CursorLoginException::class.java)
        assertThat(error).hasMessageThat().contains("If you are logged in, try logging out and back in.")
        assertThat(error).hasMessageThat().contains("paste a key from the dashboard")
    }

    @Test
    fun `a refusal without details falls back to the Connect code`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"permission_denied","message":"Error"}"""))

        val error = runCatching { login.mintApiKey("t", "name", null) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("permission_denied")
    }

    /**
     * The poll waits minutes for the browser to confirm, and a blocking socket read does not answer to interruption:
     * leaving the sign-in screen has to cancel the call itself, or the abandoned attempt keeps a thread and its
     * connection for as long as the server takes to answer a request nobody wants any more.
     */
    @Test
    fun `a sign-in that is left behind cancels the poll on the wire`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val handshake = login.startHandshake()
        // Its own scope, so a poll that will not let go cannot hold this test open.
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val poll = scope.launch { login.awaitTokens(handshake) }
            // The poll is on the wire, and this is a response that never comes.
            server.takeRequest()

            runBlocking {
                poll.cancel()
                withTimeout(CANCEL_LIMIT_MS) { poll.join() }
            }
            assertThat(callsCanceled.get()).isEqualTo(1)
        } finally {
            scope.cancel()
        }
    }

    /** Minting leaves a key on the account, so a sign-in that has already been given up on must not ask for one. */
    @Test
    fun `a sign-in already abandoned does not mint a key`() = runBlocking<Unit> {
        val minting = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            cancel()
            login.mintApiKey("session-token", "Cursor for Android (Pixel)", expiresAtMs = null)
        }

        minting.join()

        assertThat(callsStarted.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun pending() = MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Not found")

    private companion object {
        /** Only ever reached by a cancellation that did not take; the call it waits on is cancelled at once.  */
        const val CANCEL_LIMIT_MS = 10_000L
    }
}
