package com.cursorforandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
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
        assertThat(store.gitHubToken()).isNull()
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
        val onDisk = File(File(context.applicationInfo.dataDir, "shared_prefs"), "").listFiles().orEmpty()
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
        assertThat(onDisk).doesNotContain("key_memory")
    }

    @Test
    fun `a write the store refuses degrades to memory rather than losing the value`() {
        val prefs = FailingPrefs(backing("read-only"))
        val store = SecureKeyStore(context) { prefs }
        assertThat(store.setApiKey("key_first")).isTrue()

        prefs.failEdits = true
        assertThat(store.setGitHubToken("ghp_token")).isFalse()
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Unavailable)
        assertThat(store.gitHubToken()).isEqualTo("ghp_token")
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
        assertThat(store.gitHubToken()).isEqualTo("ghp_plaintext")
        assertThat(store.mcpServersJson()).isEqualTo("""[{"id":"1","name":"linear"}]""")
        assertThat(prefs.getString("api_key", null)).isEqualTo("key_plaintext")
        assertThat(store.availability.value).isEqualTo(SecureKeyStore.Availability.Encrypted)
        // The plaintext copy is gone, and is never read as a live store again.
        assertThat(sharedPrefsFiles()).doesNotContain("cursor_prefs_fallback.xml")
    }

    @Test
    fun `a plaintext file is deleted unread when the encrypted store has to be recreated`() {
        backing("cursor_prefs_fallback").edit().putString("api_key", "key_plaintext").commit()
        var attempts = 0
        val store = SecureKeyStore(context) {
            attempts++
            if (attempts == 1) throw GeneralSecurityException("keyset invalid") else FailingPrefs(backing("recreated-2"))
        }

        assertThat(store.apiKey()).isNull()
        assertThat(sharedPrefsFiles()).doesNotContain("cursor_prefs_fallback.xml")
    }
}
