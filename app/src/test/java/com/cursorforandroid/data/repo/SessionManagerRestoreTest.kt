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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

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
}
