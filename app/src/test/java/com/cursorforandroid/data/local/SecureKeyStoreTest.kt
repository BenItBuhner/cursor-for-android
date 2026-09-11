package com.cursorforandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.CharConversionException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * What happens when the Android Keystore lets the app down: a store that cannot be opened, an entry that will not
 * decrypt, and the plaintext file an older build could leave behind. Robolectric has no Keystore of its own, so the
 * encrypted store is stood in for by an ordinary [SharedPreferences] the test can make fail on demand.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SecureKeyStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Stands in for `EncryptedSharedPreferences`, with the failures the real one raises from its accessors. */
    private class FailingPrefs(
        private val delegate: SharedPreferences,
        @Volatile var failReads: Boolean = false,
        @Volatile var failEdits: Boolean = false,
    ) : SharedPreferences by delegate {
        override fun getString(key: String?, defValue: String?): String? {
            if (failReads) throw SecurityException("AEADBadTagException")
            return delegate.getString(key, defValue)
        }

        override fun edit(): SharedPreferences.Editor {
            if (failEdits) throw GeneralSecurityException("the master key exists but is unusable")
            return delegate.edit()
        }
    }

    private fun backing(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun sharedPrefsFiles(): List<String> =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "").listFiles()?.map { it.name }.orEmpty()

    @Test
    fun `an entry that will not decrypt is dropped instead of throwing, and the next one round-trips`() {
        val prefs = FailingPrefs(backing("stand-in"))
        prefs.edit().putString("api_key", "key_old").commit()
        prefs.failReads = true

        val store = SecureKeyStore(context) { prefs }
        assertThat(store.apiKey()).isNull()
        assertThat(store.mcpServersJson()).isNull()
        // The unreadable entries are gone, so the next launch is clean rather than failing the same way.
        assertThat(prefs.contains("api_key")).isFalse()

        prefs.failReads = false
        assertThat(store.setApiKey("key_new")).isTrue()
        assertThat(SecureKeyStore(context) { prefs }.apiKey()).isEqualTo("key_new")
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Encrypted)
    }

    @Test
    fun `a store that cannot be opened is recreated and says so`() {
        var attempts = 0
        val store = SecureKeyStore(context) {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("keyset invalid") else FailingPrefs(backing("recreated"))
        }

        assertThat(store.apiKey()).isNull()
        assertThat(attempts).isEqualTo(2)
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Reset)
        assertThat(store.setApiKey("key_after_reset")).isTrue()
        assertThat(store.apiKey()).isEqualTo("key_after_reset")
    }

    @Test
    fun `a keyset that is no longer hex is recreated at once rather than retried for three launches`() {
        // What Tink raises when the keyset it kept in the same file as the values reads back as something other
        // than hex, which is what a corrupted store looks like on a device: an IO failure that will never clear.
        var attempts = 0
        val store = SecureKeyStore(context) {
            attempts++
            if (attempts == 1) {
                throw CharConversionException(
                    "can't read keyset; the pref value __androidx_security_crypto_encrypted_prefs_key_keyset__ " +
                        "is not a valid hex string",
                )
            }
            FailingPrefs(backing("recreated-hex"))
        }

        assertThat(store.apiKey()).isNull()
        // Straight to the recreation: not asked a second time, and no launch counted towards MAX_OPEN_FAILURES.
        assertThat(attempts).isEqualTo(2)
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Reset)
        assertThat(backing("cursor_secure_health").getInt("open_failures", 0)).isEqualTo(0)
    }

    @Test
    fun `an IO failure that is not about the keyset is still treated as transient`() {
        // The distinction is the kind of IO failure, not IO trouble in general: a busy disk must not cost the
        // only copy of the credentials.
        var attempts = 0
        val store = SecureKeyStore(context, openRetryDelayMs = 0) {
            attempts++
            throw IOException("failed to read the store")
        }

        assertThat(store.apiKey()).isNull()
        assertThat(attempts).isEqualTo(2)
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
    }

    @Test
    fun `a store that cannot be recreated keeps secrets in memory and never writes them in the clear`() {
        val store = SecureKeyStore(context) { throw GeneralSecurityException("no keystore") }

        assertThat(store.apiKey()).isNull()
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        // Storing fails, which the caller can tell, but this process still works with what it was given.
        assertThat(store.setApiKey("key_memory")).isFalse()
        assertThat(store.setMcpServersJson("""[{"id":"1","name":"linear"}]""")).isFalse()
        assertThat(store.apiKey()).isEqualTo("key_memory")
        assertThat(store.mcpServersJson()).isEqualTo("""[{"id":"1","name":"linear"}]""")

        assertThat(sharedPrefsFiles()).doesNotContain("cursor_prefs_fallback.xml")
        // SharedPreferences writes through a temp file it renames or removes on its own thread; one listed a moment ago
        // may be gone by the time it is read, and a file that is gone holds nothing.
        val onDisk = File(File(context.applicationInfo.dataDir, "shared_prefs"), "").listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { runCatching { it.readText() }.getOrNull() }
            .joinToString("\n")
        assertThat(onDisk).doesNotContain("key_memory")
    }

    @Test
    fun `a write the store refuses degrades to memory rather than losing the value`() {
        val prefs = FailingPrefs(backing("read-only"))
        val store = SecureKeyStore(context) { prefs }
        assertThat(store.setApiKey("key_first")).isTrue()

        prefs.failEdits = true
        assertThat(store.setMcpServersJson("""[{"id":"1","name":"linear"}]""")).isFalse()
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        assertThat(store.mcpServersJson()).isEqualTo("""[{"id":"1","name":"linear"}]""")
    }

    @Test
    fun `a sign-out the store refuses to commit withholds the key from the next start`() {
        val prefs = FailingPrefs(backing("sign-out"))
        val store = SecureKeyStore(context, openRetryDelayMs = 0) { prefs }
        assertThat(store.setApiKey("key_live")).isTrue()

        prefs.failEdits = true
        // Reported as done only because the tombstone landed: the encrypted entry itself is still there.
        assertThat(store.setApiKey(null)).isTrue()
        assertThat(prefs.getString("api_key", null)).isEqualTo("key_live")
        assertThat(store.apiKey()).isNull()

        val next = SecureKeyStore(context, openRetryDelayMs = 0) { prefs }
        assertThat(next.signOutPending()).isTrue()
        assertThat(next.apiKey()).isNull()

        // Signing in again is what clears the tombstone; the new key reads back as usual.
        prefs.failEdits = false
        assertThat(next.setApiKey("key_new")).isTrue()
        val third = SecureKeyStore(context, openRetryDelayMs = 0) { prefs }
        assertThat(third.signOutPending()).isFalse()
        assertThat(third.apiKey()).isEqualTo("key_new")
    }

    @Test
    fun `an ordinary sign-out removes the key and leaves no tombstone behind`() {
        val prefs = FailingPrefs(backing("sign-out-clean"))
        val store = SecureKeyStore(context, openRetryDelayMs = 0) { prefs }
        assertThat(store.setApiKey("key_live")).isTrue()

        assertThat(store.setApiKey(null)).isTrue()
        assertThat(prefs.contains("api_key")).isFalse()

        val next = SecureKeyStore(context, openRetryDelayMs = 0) { prefs }
        assertThat(next.signOutPending()).isFalse()
        assertThat(next.apiKey()).isNull()
    }

    @Test
    fun `values an older build left in the plaintext file are migrated into the encrypted store and deleted`() {
        val legacy = backing("cursor_prefs_fallback")
        legacy.edit()
            .putString("api_key", "key_plaintext")
            .putString("github_token", "ghp_plaintext")
            .putString("mcp_servers", """[{"id":"1","name":"linear"}]""")
            .commit()
        assertThat(sharedPrefsFiles()).contains("cursor_prefs_fallback.xml")

        val prefs = FailingPrefs(backing("stand-in-migration"))
        val store = SecureKeyStore(context) { prefs }

        assertThat(store.apiKey()).isEqualTo("key_plaintext")
        assertThat(store.mcpServersJson()).isEqualTo("""[{"id":"1","name":"linear"}]""")
        assertThat(prefs.getString("api_key", null)).isEqualTo("key_plaintext")
        // The GitHub token nothing reads any more is left behind rather than carried into the encrypted store.
        assertThat(prefs.contains("github_token")).isFalse()
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Encrypted)
        // The plaintext copy is gone, and is never read as a live store again.
        assertThat(sharedPrefsFiles()).doesNotContain("cursor_prefs_fallback.xml")
    }

    @Test
    fun `a GitHub token an earlier version stored is removed the first time the store opens`() {
        val prefs = FailingPrefs(backing("stand-in-github"))
        prefs.edit().putString("api_key", "key_live").putString("github_token", "ghp_stale").commit()

        val store = SecureKeyStore(context) { prefs }

        assertThat(store.apiKey()).isEqualTo("key_live")
        assertThat(prefs.contains("github_token")).isFalse()
    }

    @Test
    fun `a plaintext file is carried into the encrypted store that had to be recreated, not destroyed with it`() {
        backing("cursor_prefs_fallback").edit().putString("api_key", "key_plaintext").commit()
        var attempts = 0
        val store = SecureKeyStore(context, openRetryDelayMs = 0) {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("keyset invalid") else FailingPrefs(backing("recreated-2"))
        }

        // The recreated store is empty, so the plaintext file can be the only copy of the key there is: it is
        // migrated first and only deleted once those values have committed.
        assertThat(store.apiKey()).isEqualTo("key_plaintext")
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Reset)
        assertThat(sharedPrefsFiles()).doesNotContain("cursor_prefs_fallback.xml")
    }

    @Test
    fun `a transient open failure keeps every stored copy for the next launch`() {
        backing("cursor_prefs_fallback").edit().putString("api_key", "key_plaintext").commit()
        var attempts = 0
        val store = SecureKeyStore(context, openRetryDelayMs = 0) {
            attempts++
            throw IllegalStateException("the Keystore is not ready yet")
        }

        assertThat(store.apiKey()).isNull()
        // Asked twice: a Keystore busy with the unlock that woke the app usually answers the second time.
        assertThat(attempts).isEqualTo(2)
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        // Nothing was deleted, so the launch that does open the store still finds the key.
        assertThat(sharedPrefsFiles()).contains("cursor_prefs_fallback.xml")

        val next = SecureKeyStore(context, openRetryDelayMs = 0) { FailingPrefs(backing("stand-in-after-transient")) }
        assertThat(next.apiKey()).isEqualTo("key_plaintext")
        assertThat(next.availability.value).isEqualTo(SecureKeyStore.Availability.Encrypted)
    }

    @Test
    fun `a store that has not opened in three launches is started over`() {
        var attempts = 0
        // Two attempts a launch; the reset on the third launch is the seventh call.
        fun store() = SecureKeyStore(context, openRetryDelayMs = 0) {
            attempts++
            if (attempts <= 6) throw IllegalStateException("the Keystore is not ready yet") else FailingPrefs(backing("recreated-3"))
        }

        assertThat(store().also { it.apiKey() }.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        assertThat(store().also { it.apiKey() }.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        val third = store()
        assertThat(third.apiKey()).isNull()
        assertThat(third.availability.value).isEqualTo(SecureKeyStore.Availability.Reset)
        assertThat(attempts).isEqualTo(7)

        // The run of failures is over, so a store that opens straight away is not reset again.
        val fourth = store()
        assertThat(fourth.apiKey()).isNull()
        assertThat(fourth.availability.value).isEqualTo(SecureKeyStore.Availability.Encrypted)
        assertThat(attempts).isEqualTo(8)
    }
}
