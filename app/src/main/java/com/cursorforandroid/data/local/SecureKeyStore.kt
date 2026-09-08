package com.cursorforandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap

/** The one file secrets are ever written to. */
private const val ENCRYPTED_FILE = "cursor_secure_prefs"

/**
 * Secrets, encrypted with an Android Keystore-backed master key: the Cursor API key, the GitHub token that reads
 * pull request states, and the MCP server definitions (their headers and environment variables hold credentials).
 *
 * The encrypted store is the only place any of it is ever written. It can genuinely fail to open or to decrypt — a
 * master key invalidated by an OS upgrade or a lock-screen change, a device restore, a Keystore that is wedged — so
 * two fallbacks sit behind it, in this order:
 *
 *  1. Start over: delete the encrypted file and the master key and recreate both. The stored secrets go with them,
 *     which means signing in again, but the app works and the next launch is clean ([Availability.Reset]).
 *  2. Keep them in memory for this process only ([Availability.Unavailable]). Nothing is persisted, so the sign-in
 *     lasts until the process does, and [availability] lets the UI say so.
 *
 * Neither writes anything in the clear. An older build did fall back to plaintext `cursor_prefs_fallback`, which
 * this one migrates into the encrypted store the first time it opens it, and then deletes.
 */
class SecureKeyStore(
    private val context: Context,
    /** Opens the encrypted store. Injected by tests, which have no Android Keystore to open it with. */
    private val openEncrypted: (Context) -> SharedPreferences = ::encryptedPrefs,
) {
    /** Where this process is keeping secrets. */
    enum class Availability {
        /** The encrypted store opened; everything is persisted. */
        Encrypted,

        /** It had to be recreated, so whatever was stored is gone and the user has to sign in again. */
        Reset,

        /** Nothing can be persisted on this device; the secrets last as long as the process. */
        Unavailable,
    }

    private val _availability = MutableStateFlow(Availability.Encrypted)

    /**
     * How securely secrets are being kept. Accurate from the first read of any of them — the session's restore, on
     * a background thread, is always the first — and optimistic until then.
     */
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    /** Holds what this process has been given while [prefs] is null, so a broken device still gets one session. */
    private val memory = ConcurrentHashMap<String, String>()

    private val openLock = Any()

    @Volatile
    private var opened = false

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        if (opened) return prefs
        synchronized(openLock) {
            if (!opened) {
                prefs = open()
                opened = true
            }
            return prefs
        }
    }

    /** Opens the encrypted store, recreating it once if it cannot be opened; null means memory-only. */
    private fun open(): SharedPreferences? {
        runCatching { openEncrypted(context) }
            .onSuccess {
                migrateLegacyFallback(it)
                return it
            }
            .onFailure { Log.w(TAG, "Encrypted store unavailable; recreating it", it) }
        // A master key that no longer matches the file can only be fixed by starting both over.
        discardEncryptedStore()
        discardLegacyFallback()
        return runCatching { openEncrypted(context) }
            .onSuccess { _availability.value = Availability.Reset }
            .onFailure {
                Log.w(TAG, "Encrypted store could not be recreated; secrets stay in memory", it)
                _availability.value = Availability.Unavailable
            }
            .getOrNull()
    }

    @Volatile
    private var cached: String? = null

    fun apiKey(): String? = cached ?: read(KEY_API_KEY)?.also { cached = it }

    /** False when the key could not be persisted, so this sign-in only lasts as long as the process. */
    fun setApiKey(key: String?): Boolean {
        cached = key
        return write(KEY_API_KEY, key)
    }

    /** The MCP server list as JSON; the shape is owned by [McpServerStore]. */
    fun mcpServersJson(): String? = read(KEY_MCP_SERVERS)

    fun setMcpServersJson(json: String?): Boolean = write(KEY_MCP_SERVERS, json)

    @Volatile
    private var cachedGitHubToken: String? = null

    /**
     * The GitHub token pull request states are read with; null without one (public repositories still answer). Like
     * the MCP definitions it belongs to the device rather than the Cursor account, so signing out leaves it alone.
     */
    fun gitHubToken(): String? = cachedGitHubToken ?: read(KEY_GITHUB_TOKEN)?.also { cachedGitHubToken = it }

    fun setGitHubToken(token: String?): Boolean {
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() }
        cachedGitHubToken = trimmed
        return write(KEY_GITHUB_TOKEN, trimmed)
    }

    private fun read(key: String): String? {
        val prefs = prefs() ?: return memory[key]
        runCatching { prefs.getString(key, null) }
            .onSuccess { return it }
            .onFailure { Log.w(TAG, "Couldn't read $key from the encrypted store", it) }
        // An entry that will not decrypt is gone for good, so drop it; a keyset that cannot even be edited takes
        // the whole store with it, which at least leaves the next launch clean instead of failing the same way.
        val dropped = runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
        if (!dropped) reopenAfterFailure()
        return null
    }

    private fun write(key: String, value: String?): Boolean {
        if (value.isNullOrBlank()) memory.remove(key) else memory[key] = value
        val prefs = prefs() ?: return false
        val written = runCatching {
            prefs.edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value) }.commit()
        }.getOrElse {
            Log.w(TAG, "Couldn't write $key to the encrypted store", it)
            false
        }
        // Nothing is worth writing in the clear: this process keeps the value in memory and says storage is gone.
        if (!written) degradeToMemory()
        return written
    }

    /** Starts the encrypted store over after a failure the store could not repair itself. */
    private fun reopenAfterFailure() = synchronized(openLock) {
        discardEncryptedStore()
        prefs = runCatching { openEncrypted(context) }.getOrNull()
        opened = true
        _availability.value = if (prefs == null) Availability.Unavailable else Availability.Reset
    }

    private fun degradeToMemory() = synchronized(openLock) {
        prefs = null
        opened = true
        _availability.value = Availability.Unavailable
    }

    private fun discardEncryptedStore() {
        runCatching { context.deleteSharedPreferences(ENCRYPTED_FILE) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    /**
     * Moves anything an older build left in the plaintext file into the encrypted store and deletes it. Without
     * this the values would be stranded — invisible to the app, still readable to anything that can read the app's
     * files — and the user would be signed out for no visible reason the first time the Keystore worked again.
     */
    private fun migrateLegacyFallback(prefs: SharedPreferences) {
        if (!legacyFallbackFile().isFile) return
        // Committed rather than applied, and the plaintext file is only deleted once it has landed: an apply() that
        // had not reached the disk yet would take the values with it.
        val carried = runCatching {
            val legacy = context.getSharedPreferences(LEGACY_FALLBACK_FILE, Context.MODE_PRIVATE)
            val values = MIGRATED_KEYS.mapNotNull { key ->
                legacy.getString(key, null)?.takeIf { it.isNotBlank() && prefs.getString(key, null) == null }?.let { key to it }
            }
            values.isEmpty() || prefs.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.commit()
        }.getOrElse {
            Log.w(TAG, "Couldn't migrate the legacy plaintext store", it)
            false
        }
        if (carried) discardLegacyFallback()
    }

    private fun discardLegacyFallback() {
        if (legacyFallbackFile().exists()) runCatching { context.deleteSharedPreferences(LEGACY_FALLBACK_FILE) }
    }

    /** Checked as a file so that merely asking about it does not create it. */
    private fun legacyFallbackFile(): File =
        File(File(context.applicationContext.applicationInfo.dataDir, "shared_prefs"), "$LEGACY_FALLBACK_FILE.xml")

    private companion object {
        const val TAG = "SecureKeyStore"
        const val KEY_API_KEY = "api_key"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val LEGACY_FALLBACK_FILE = "cursor_prefs_fallback"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        val MIGRATED_KEYS = listOf(KEY_API_KEY, KEY_MCP_SERVERS, KEY_GITHUB_TOKEN)
    }
}

private fun encryptedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
