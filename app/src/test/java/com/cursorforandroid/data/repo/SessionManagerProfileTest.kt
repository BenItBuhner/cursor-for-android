package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountProfile
import com.cursorforandroid.data.api.ProfileApi
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** The account service's profile folded into the signed-in user behind `/v1/me`: the picture, and the fallbacks and guards around it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionManagerProfileTest {

    private class FakeProfileApi : ProfileApi {
        @Volatile var answer: AccountProfile? = AccountProfile("https://pics.example/alex.png", "alex@example.com", "Alex", "Rivera", "Acme")
        @Volatile var failure: Throwable? = null
        /** When set, [profile] waits for it, so a test can look at the state before the picture lands. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var calls = 0

        override suspend fun profile(): AccountProfile {
            calls++
            gate?.await()
            failure?.let { throw it }
            return answer ?: throw IOException("no answer")
        }
    }

    private val api = FakeCursorApi()
    private val profile = FakeProfileApi()
    private lateinit var keyStore: SecureKeyStore
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        keyStore = SecureKeyStore(context)
        prefs = PreferencesStore(context)
        session = SessionManager(
            keyStore,
            prefs,
            CursorBackend(api, FakeRunStreamer(), isDemo = false),
            CursorBackend(api, FakeRunStreamer(), isDemo = true),
            profile = profile,
        )
    }

    private suspend fun signedInUser(condition: (CursorUser) -> Boolean): CursorUser = withTimeout(10_000) {
        session.state.first { it is SessionState.SignedIn && condition(it.user) }.let { (it as SessionState.SignedIn).user }
    }

    @Test
    fun `signing in shows the account at once and the picture (and the names the key API lacked) once the account service answers`() = runBlocking<Unit> {
        profile.gate = CompletableDeferred()

        val user = session.signIn("key_abc").getOrThrow()

        assertThat(user.profilePictureUrl).isNull()
        assertThat((session.state.value as SessionState.SignedIn).user).isEqualTo(user)
        profile.gate?.complete(Unit)

        val enriched = signedInUser { it.profilePictureUrl != null }
        assertThat(enriched.profilePictureUrl).isEqualTo("https://pics.example/alex.png")
        assertThat(enriched.firstName).isEqualTo("Alex")
        assertThat(enriched.lastName).isEqualTo("Rivera")
        assertThat(enriched.email).isEqualTo("alex@example.com")
        assertThat(enriched.apiKeyName).isEqualTo("test")
        assertThat(prefs.cachedUser.first()).isEqualTo(enriched)
        assertThat(profile.calls).isEqualTo(1)
    }

    @Test
    fun `an account service that fails leaves the user as the key API described it`() = runBlocking<Unit> {
        profile.failure = IOException("offline")

        val user = session.signIn("key_abc").getOrThrow()
        withTimeout(10_000) { while (profile.calls == 0) kotlinx.coroutines.delay(10) }

        assertThat((session.state.value as SessionState.SignedIn).user).isEqualTo(user)
        assertThat(prefs.cachedUser.first()).isEqualTo(user)
    }

    @Test
    fun `restore keeps last time's picture while the key is re-validated, then refreshes it`() = runBlocking<Unit> {
        keyStore.setApiKey("key_abc")
        prefs.setCredentialInfo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
        prefs.setCachedUser(CursorUser("test", "alex@example.com", "Alex", "Rivera", null, profilePictureUrl = "https://pics.example/old.png"))
        profile.answer = profile.answer?.copy(profilePictureUrl = "https://pics.example/new.png")

        session.restore()

        // The cached account (old picture) is on screen immediately; `/v1/me` re-validates without dropping it.
        assertThat((session.state.value as SessionState.SignedIn).user.profilePictureUrl).isEqualTo("https://pics.example/old.png")
        val refreshed = signedInUser { it.profilePictureUrl == "https://pics.example/new.png" }
        assertThat(prefs.cachedUser.first()).isEqualTo(refreshed)
    }

    @Test
    fun `an answer that lands after signing out is dropped`() = runBlocking<Unit> {
        profile.gate = CompletableDeferred()
        session.signIn("key_abc").getOrThrow()

        session.signOut()
        profile.gate?.complete(Unit)
        withTimeout(10_000) { while (profile.calls == 0) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(100)

        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(prefs.cachedUser.first()).isNull()
    }

    @Test
    fun `the demo has no account to ask`() = runBlocking<Unit> {
        session.enterDemo()
        kotlinx.coroutines.delay(50)

        assertThat(profile.calls).isEqualTo(0)
        assertThat((session.state.value as SessionState.SignedIn).user.profilePictureUrl).isNull()
    }
}
