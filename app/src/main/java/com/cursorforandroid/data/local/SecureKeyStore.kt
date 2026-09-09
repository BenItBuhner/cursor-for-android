package com.cursorforandroid.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.CharConversionException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/** The one file secrets are ever written to. */
private const val ENCRYPTED_FILE = "cursor_secure_prefs"

/** Ordinary, non-secret preferences: how the encrypted store has been behaving across launches. */
private const val HEALTH_FILE = "cursor_secure_health"

/**
 * Secrets, encrypted with an Android Keystore-backed master key: the Cursor API key, the GitHub token that reads
 * pull request states, and the MCP server definitions (their headers and environment variables hold credentials).
 *
 * The encrypted store is the only place any of it is ever written. It can genuinely fail to open or to decrypt, and
 * the two reasons are worlds apart. Stored bytes that will never decrypt again — a master key invalidated by an OS
 * upgrade or a lock-screen change, a keyset a restore left unreadable — can only be fixed by starting over. A
 * Keystore that is merely not ready — the device is still locked, the provider is being installed, the disk is busy —
 * is fixed by asking again, and throwing the credentials away for it would be a data loss the user never asked for.
 * So a failure to open is classified ([isPermanentCorruption]) and only the first kind resets anything:
 *
 *  1. Positively identified corruption: start over at once. Delete the encrypted file and the master key and
 *     recreate both. Whatever was stored goes with them, which means signing in again ([Availability.Reset]).
 *  2. Anything else: retry once, then keep the secrets in memory for this process only
 *     ([Availability.Unavailable]) without deleting a thing, and count the launch. A store that has not opened in
 *     [MAX_OPEN_FAILURES] consecutive launches is not going to, and is reset like the first kind.
 *
 * Neither writes anything in the clear. An older build did fall back to plaintext `cursor_prefs_fallback`, which
 * this one migrates into the encrypted store the first time it opens it — including a store it had to recreate,
 * which may be the only copy left — and deletes only once the values have committed.
 */
class SecureKeyStore(
    private val context: Context,
    /** How long the retry of a failed open waits; zero in tests, which have no transient state to wait out. */
    private val openRetryDelayMs: Long = OPEN_RETRY_DELAY_MS,
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

    /**
     * Opens the encrypted store. Corruption is started over from at once; anything else is retried, and only
     * recreated after [MAX_OPEN_FAILURES] launches have failed the same way. Null means memory-only.
     */
    private fun open(): SharedPreferences? {
        val first = runCatching { openEncrypted(context) }
        first.getOrNull()?.let { return opened(it, Availability.Encrypted) }
        val error = first.exceptionOrNull()
        if (error != null && isPermanentCorruption(error)) {
            Log.w(TAG, "Encrypted store cannot be decrypted; recreating it", error)
            return recreate()
        }
        Log.w(TAG, "Encrypted store did not open; trying once more", error)
        if (openRetryDelayMs > 0) runCatching { Thread.sleep(openRetryDelayMs) }
        val second = runCatching { openEncrypted(context) }
        second.getOrNull()?.let { return opened(it, Availability.Encrypted) }
        val retryError = second.exceptionOrNull()
        val failures = recordOpenFailure()
        if (failures >= MAX_OPEN_FAILURES) {
            Log.w(TAG, "Encrypted store has not opened in $failures launches; recreating it", retryError)
            return recreate()
        }
        // Nothing is deleted: the store may well open on the next launch, and it holds the only copy of the secrets.
        Log.w(TAG, "Encrypted store unavailable; secrets stay in memory for this process", retryError)
        _availability.value = Availability.Unavailable
        return null
    }

    /** The store is open: the launch counter starts over and anything an older build left in the clear moves in. */
    private fun opened(prefs: SharedPreferences, availability: Availability): SharedPreferences {
        clearOpenFailures()
        migrateLegacyFallback(prefs)
        _availability.value = availability
        return prefs
    }

    /**
     * Starts the encrypted store over: the file and the master key go, and both are recreated. The legacy plaintext
     * file is deliberately left alone — after this the recreated store is empty, so that file can be the only copy
     * of the secrets there is, and [migrateLegacyFallback] deletes it once it has committed them.
     */
    private fun recreate(): SharedPreferences? {
        deleteEncryptedStore()
        val fresh = runCatching { openEncrypted(context) }.getOrElse {
            Log.w(TAG, "Encrypted store could not be recreated; secrets stay in memory", it)
            _availability.value = Availability.Unavailable
            return null
        }
        return opened(fresh, Availability.Reset)
    }

    /**
     * True only for a failure that says the stored bytes will never be readable again: a GCM tag that does not
     * match what it protects, or a Tink keyset that cannot be parsed or decrypted. A Keystore that is not ready,
     * a device that is still locked, a provider that is missing and plain IO trouble are all transient by
     * comparison, and deleting a perfectly good credential over one of them is the loss this classification exists
     * to prevent.
     */
    private fun isPermanentCorruption(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is AEADBadTagException) return true
            // Tink shades its protobuf, so the keyset parse failure is matched by name rather than by type.
            if (cause.javaClass.name.endsWith("InvalidProtocolBufferException")) return true
            // The one IO failure that is not transient: Tink stores the keyset as hex in the same file as the
            // values, and raises this when what it reads back is not hex at all. Nothing about that improves by
            // asking again, and treating it as transient costs [MAX_OPEN_FAILURES] launches of a device that
            // silently cannot keep a sign-in before the store is started over.
            if (cause is CharConversionException) return true
            if (cause is GeneralSecurityException) {
                val message = cause.message?.lowercase().orEmpty()
                if (KEYSET_FAILURES.any { it in message }) return true
            }
            cause = cause.cause.takeIf { it !== cause }
        }
        return false
    }

    /** Ordinary preferences, so they stay readable exactly when the encrypted ones do not. */
    private fun healthPrefs(): SharedPreferences? =
        runCatching { context.getSharedPreferences(HEALTH_FILE, Context.MODE_PRIVATE) }.getOrNull()

    /** Counts this launch's failure to open and returns the run of them; zero when even this cannot be recorded. */
    private fun recordOpenFailure(): Int {
        val prefs = healthPrefs() ?: return 0
        val failures = runCatching { prefs.getInt(KEY_OPEN_FAILURES, 0) }.getOrDefault(0) + 1
        runCatching { prefs.edit(commit = true) { putInt(KEY_OPEN_FAILURES, failures) } }
        return failures
    }

    private fun clearOpenFailures() {
        val prefs = healthPrefs() ?: return
        if (runCatching { prefs.getInt(KEY_OPEN_FAILURES, 0) }.getOrDefault(0) == 0) return
        runCatching { prefs.edit(commit = true) { remove(KEY_OPEN_FAILURES) } }
    }

    @Volatile
    private var cached: String? = null

    /**
     * The in-process answer to "has a sign-out been through here", which is the authority while this process runs:
     * null until the durable tombstone below has been consulted.
     */
    @Volatile
    private var signedOut: Boolean? = null

    fun apiKey(): String? {
        // A sign-out whose removal could not be proven left a tombstone; the key it could not delete stays unread.
        if (signOutPending()) return null
        return cached ?: read(KEY_API_KEY)?.also { cached = it }
    }

    /**
     * True while a sign-out has not been proven to have removed the stored key, so the key is being withheld. The
     * session retries the removal and the preference cleanup that went with it on the next start.
     */
    fun signOutPending(): Boolean {
        signedOut?.let { return it }
        val stored = runCatching { healthPrefs()?.getBoolean(KEY_SIGNED_OUT, false) }.getOrNull() ?: false
        signedOut = stored
        return stored
    }

    /** False when the key could not be persisted, so this sign-in only lasts as long as the process. */
    fun setApiKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return clearApiKey()
        cached = key
        val written = write(KEY_API_KEY, key)
        // This process is signed in, whatever the last sign-out managed to delete.
        forgetSignOut()
        return written
    }

    /**
     * Removal for a sign-out, fail-closed: it may not report success while the next process start could still read
     * the key. A refused commit takes the whole encrypted store with it — the user is signing out, so losing the
     * store is the right trade — and if even that cannot be proven, a tombstone in the ordinary preferences
     * withholds the key from every read until a sign-in clears it.
     */
    private fun clearApiKey(): Boolean {
        cached = null
        memory.remove(KEY_API_KEY)
        if (removePersistedApiKey()) {
            forgetSignOut()
            return true
        }
        if (discardStoreForSignOut()) {
            forgetSignOut()
            return true
        }
        return markSignedOut()
    }

    private fun removePersistedApiKey(): Boolean {
        val prefs = prefs() ?: return false
        return runCatching { prefs.edit().remove(KEY_API_KEY).commit() }
            .onFailure { Log.w(TAG, "Couldn't remove the API key from the encrypted store", it) }
            .getOrDefault(false)
    }

    /** Deletes the encrypted store and reopens it empty; true only once the key is provably unreadable. */
    private fun discardStoreForSignOut(): Boolean = synchronized(openLock) {
        deleteEncryptedStore()
        val fresh = runCatching { openEncrypted(context) }.getOrNull()
        prefs = fresh
        opened = true
        _availability.value = if (fresh == null) Availability.Unavailable else Availability.Reset
        // A store that cannot even be opened cannot be read for proof either, so it counts as unproven.
        fresh != null && runCatching { fresh.getString(KEY_API_KEY, null) == null }.getOrDefault(false)
    }

    /** Records the sign-out where it can be read before any key is; false when even that could not be written. */
    private fun markSignedOut(): Boolean {
        signedOut = true
        val prefs = healthPrefs() ?: return false
        return runCatching { prefs.edit().putBoolean(KEY_SIGNED_OUT, true).commit() }
            .onFailure { Log.w(TAG, "Couldn't record the sign-out", it) }
            .getOrDefault(false)
    }

    private fun forgetSignOut() {
        val had = signedOut
        signedOut = false
        if (had == false) return
        runCatching { healthPrefs()?.edit(commit = true) { remove(KEY_SIGNED_OUT) } }
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
        deleteEncryptedStore()
        prefs = runCatching { openEncrypted(context) }.getOrNull()
        opened = true
        _availability.value = if (prefs == null) Availability.Unavailable else Availability.Reset
    }

    private fun degradeToMemory() = synchronized(openLock) {
        prefs = null
        opened = true
        _availability.value = Availability.Unavailable
    }

    private fun deleteEncryptedStore() {
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
        /** Launches in a row that may fail to open the store before it is started over. */
        const val MAX_OPEN_FAILURES = 3
        /** Long enough for a Keystore busy with the unlock that woke the app, short enough not to be a stall. */
        const val OPEN_RETRY_DELAY_MS = 150L
        const val KEY_OPEN_FAILURES = "open_failures"
        const val KEY_SIGNED_OUT = "signed_out"
        val MIGRATED_KEYS = listOf(KEY_API_KEY, KEY_MCP_SERVERS, KEY_GITHUB_TOKEN)
        /** What a `GeneralSecurityException` about a keyset that will never decrypt again says about itself. */
        val KEYSET_FAILURES = listOf("keyset", "decryption failed")
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
