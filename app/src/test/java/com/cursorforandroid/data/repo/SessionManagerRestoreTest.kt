package com.cursorforandroid.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a cold start does when the stores it reads are broken: it always leaves [SessionState.Loading] — the splash
 * screen is held for exactly as long as that lasts — and the sign-in screen is told why it is showing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionManagerRestoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val api = FakeCursorApi()

    private fun session(keyStore: SecureKeyStore): SessionManager {
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        return SessionManager(keyStore, PreferencesStore(context), backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
    }

    private fun standIn(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `a secure store that had to be recreated signs out with the reason instead of hanging`() = runBlocking {
        var attempts = 0
        val keyStore = SecureKeyStore(context) {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("keyset invalid") else standIn("restore-reset")
        }
        val session = session(keyStore)

        session.restoreIfNeeded()

        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(session.signedOutReason.value).contains("secure storage had to be reset")
    }

    @Test
    fun `a device with no secure storage signs out and says the sign-in will not be remembered`() = runBlocking {
        val session = session(SecureKeyStore(context) { throw GeneralSecurityException("no keystore") })

        session.restoreIfNeeded()

        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
        assertThat(session.signedOutReason.value).contains("can't store a key securely")
    }

    @Test
    fun `a working store restores the stored key with nothing to explain`() = runBlocking {
        val keyStore = SecureKeyStore(context) { standIn("restore-ok") }
        keyStore.setApiKey("key_stored")
        val session = session(keyStore)

        session.restoreIfNeeded()

        assertThat(session.state.value).isInstanceOf(SessionState.SignedIn::class.java)
        assertThat(session.signedOutReason.value).isNull()
    }

    @Test
    fun `signing in clears a reason left by the previous launch`() = runBlocking {
        val session = session(SecureKeyStore(context) { standIn("restore-clears") })
        session.restoreIfNeeded()
        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)

        session.signIn("key_pasted").getOrThrow()

        assertThat(session.signedOutReason.value).isNull()
    }

    /**
     * The activity's restore runs in its composition: an activity torn down while the Keystore is still opening
     * cancels it, and a restore that went on holding its caller until the open returned would then resume on a UI
     * dispatcher nothing drives any more — which, under Robolectric, wedges Compose for every later test in the JVM.
     */
    @Test
    fun `a restore cancelled while the secure store is still opening lets its caller go at once`() = runBlocking {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = session(SecureKeyStore(context) { opening.countDown(); release.await(); standIn("restore-cancelled") })

        val restore = launch(Dispatchers.Default) { session.restoreIfNeeded() }
        assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue()
        restore.cancel()
        val letGo = withTimeoutOrNull(2_000) { restore.join() } != null
        release.countDown()

        assertThat(letGo).isTrue()
        // Nothing was decided by the cancelled restore; the next one, with the store open, decides it.
        assertThat(session.state.value).isEqualTo(SessionState.Loading)
        session.restoreIfNeeded()
        assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
    }
}
