package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.auth.CursorLogin
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * The account sign-in end to end: the browser handshake against a fake api2, the minted key against the fake Cloud
 * Agents API, and what the session records about it. Robolectric only because the stores need a Context.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionManagerCursorLoginTest {

    private val server = MockWebServer()
    private val api = FakeCursorApi()
    private lateinit var keyStore: SecureKeyStore
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        server.start()
        AppClock.nowMillis = { now }
        val context = ApplicationProvider.getApplicationContext<Context>()
        keyStore = SecureKeyStore(context)
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(
            keyStore,
            prefs,
            backend,
            CursorBackend(api, FakeRunStreamer(), isDemo = true),
            browserLogin = lazyOf(CursorLogin(OkHttpClient(), websiteUrl = "https://cursor.test", apiUrl = server.url("/").toString(), sleep = { delay(1) })),
            mintedKeyName = "Cursor for Android (test)",
            mintedKeyTtlMs = 90L * 24 * 60 * 60 * 1000,
        )
    }

    @After
    fun tearDown() {
        session.cancelCursorLogin()
        server.shutdown()
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `signing in with Cursor mints a key, checks it against the API and records how it was obtained`() = runBlocking<Unit> {
        // The browser takes its time: nothing is answered until the test has looked at the waiting state.
        val approved = CountDownLatch(1)
        val answers = ArrayDeque(
            listOf(
                pending(),
                MockResponse().setBody("""{"accessToken":"session-token","refreshToken":"rt"}"""),
                MockResponse().setBody("""{"apiKey":"key_minted"}"""),
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                approved.await()
                return answers.removeFirst()
            }
        }

        val url = session.startCursorLogin()

        assertThat(url).startsWith("https://cursor.test/loginDeepControl?")
        assertThat(session.loginProgress.value).isEqualTo(LoginProgress.WaitingForBrowser(url))
        approved.countDown()
        val signedIn = withTimeout(10_000) { session.state.first { it is SessionState.SignedIn } as SessionState.SignedIn }
        withTimeout(10_000) { session.loginProgress.first { it == LoginProgress.Idle } }

        val expected = CredentialInfo(SignInMethod.Cursor, expiresAtMs = now + 90L * 24 * 60 * 60 * 1000)
        assertThat(signedIn.isDemo).isFalse()
        assertThat(signedIn.user.apiKeyName).isEqualTo("test")
        assertThat(signedIn.credential).isEqualTo(expected)
        assertThat(keyStore.apiKey()).isEqualTo("key_minted")
        assertThat(prefs.credentialInfo.first()).isEqualTo(expected)
        assertThat(api.meCalls).isEqualTo(1)

        // Two polls, then the mint, which spends the session token on a key that lapses; only the key is kept.
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(server.takeRequest().path).isEqualTo("/auth/poll")
        assertThat(server.takeRequest().path).isEqualTo("/auth/poll")
        val mint = server.takeRequest()
        assertThat(mint.path).isEqualTo("/aiserver.v1.DashboardService/CreateUserApiKey")
        assertThat(mint.getHeader("Authorization")).isEqualTo("Bearer session-token")
        assertThat(mint.body.readUtf8()).contains("\"expiresAt\":\"${expected.expiresAtMs}\"")
    }

    @Test
    fun `a team that forbids user API keys leaves the session signed out with the reason`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-token","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"permission_denied","message":"Error"}"""))
        session.restore()

        session.startCursorLogin()
        val failed = withTimeout(10_000) { session.loginProgress.first { it is LoginProgress.Failed } as LoginProgress.Failed }

        assertThat(failed.message).contains("wouldn't create an API key")
        assertThat(failed.message).contains("permission_denied")
        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(keyStore.apiKey()).isNull()
        assertThat(prefs.credentialInfo.first()).isNull()

        // Showing the error once is enough; the screen goes back to its resting state.
        session.dismissLoginError()
        assertThat(session.loginProgress.value).isEqualTo(LoginProgress.Idle)
    }

    @Test
    fun `a minted key the API rejects is discarded like a pasted one`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-token","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody("""{"apiKey":"key_minted"}"""))
        api.failMe = CursorApiException(401, "unauthorized", "Invalid User API Key")
        session.restore()

        session.startCursorLogin()
        val failed = withTimeout(10_000) { session.loginProgress.first { it is LoginProgress.Failed } as LoginProgress.Failed }

        assertThat(failed.message).contains("rejected")
        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(keyStore.apiKey()).isNull()
    }

    @Test
    fun `cancelling stops the polling and clears the progress`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = pending()
        }
        session.restore()

        session.startCursorLogin()
        withTimeout(10_000) { while (server.requestCount == 0) delay(5) }
        session.cancelCursorLogin()

        assertThat(session.loginProgress.value).isEqualTo(LoginProgress.Idle)
        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        delay(100)
        val settled = server.requestCount
        delay(100)
        assertThat(server.requestCount).isEqualTo(settled)
        assertThat(keyStore.apiKey()).isNull()
    }

    @Test
    fun `starting over abandons the previous handshake`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = pending()
        }
        session.restore()

        val first = session.startCursorLogin()
        val second = session.startCursorLogin()

        assertThat(second).isNotEqualTo(first)
        assertThat(session.loginProgress.value).isEqualTo(LoginProgress.WaitingForBrowser(second))
    }

    @Test
    fun `restore signs out when the minted key has lapsed instead of asking the API`() = runBlocking<Unit> {
        keyStore.setApiKey("key_old")
        prefs.setCachedUser(CursorUser("old", "old@example.com", null, null, 1))
        prefs.setCredentialInfo(CredentialInfo(SignInMethod.Cursor, expiresAtMs = now - 1))

        session.restore()

        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(keyStore.apiKey()).isNull()
        assertThat(prefs.cachedUser.first()).isNull()
        assertThat(api.meCalls).isEqualTo(0)
    }

    @Test
    fun `restore keeps a minted key that is still valid and carries its expiry into the session`() = runBlocking<Unit> {
        keyStore.setApiKey("key_minted")
        val credential = CredentialInfo(SignInMethod.Cursor, expiresAtMs = now + 1)
        prefs.setCredentialInfo(credential)

        session.restore()

        val signedIn = withTimeout(10_000) { session.state.first { it is SessionState.SignedIn } as SessionState.SignedIn }
        assertThat(signedIn.credential).isEqualTo(credential)
        assertThat(api.meCalls).isEqualTo(1)
    }

    @Test
    fun `a key stored before the method was recorded counts as pasted`() = runBlocking<Unit> {
        keyStore.setApiKey("key_legacy")

        session.restore()

        val signedIn = withTimeout(10_000) { session.state.first { it is SessionState.SignedIn } as SessionState.SignedIn }
        assertThat(signedIn.credential).isEqualTo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
    }

    @Test
    fun `pasting a key records it as such and signing out forgets both`() = runBlocking<Unit> {
        assertThat(session.signIn("key_pasted").isSuccess).isTrue()
        assertThat((session.state.value as SessionState.SignedIn).credential).isEqualTo(CredentialInfo(SignInMethod.ApiKey, null))
        assertThat(prefs.credentialInfo.first()).isEqualTo(CredentialInfo(SignInMethod.ApiKey, null))

        session.signOut()

        assertThat(prefs.credentialInfo.first()).isNull()
        assertThat(keyStore.apiKey()).isNull()
    }

    private fun pending() = MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Not found")
}
