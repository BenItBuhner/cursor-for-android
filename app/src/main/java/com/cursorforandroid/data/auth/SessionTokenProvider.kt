package com.cursorforandroid.data.auth

import com.cursorforandroid.data.api.ConnectRpc
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.await
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

/** The session could not be started for the stored key. [code] is the backend's reason when it named one. */
class SessionUnavailableException(message: String, val code: String? = null, cause: Throwable? = null) : IOException(message, cause) {
    /** True when trying again later cannot help: the key is rejected or the account's policy forbids this device. */
    val isPermanent: Boolean get() = code == SIGN_IN_POLICY_VIOLATION || code == KEY_REJECTED

    companion object {
        const val SIGN_IN_POLICY_VIOLATION = "sign_in_policy_violation"
        const val KEY_REJECTED = "key_rejected"
    }
}

/**
 * The short-lived Cursor session that the account-level `aiserver.v1` RPCs on api2 take — pins, and whatever else the
 * first-party apps sync through the account rather than the API key. It is derived on demand from the user API key
 * the app already holds with `POST /auth/exchange_user_api_key`, which is the Cursor CLI's `--api-key` login, so a
 * pasted key and a key minted by the browser sign-in are treated alike and no browser round-trip is needed.
 *
 * The session is held in memory only and re-derived when it nears its expiry (the JWT's `exp`), when a call comes
 * back `401`, or when the key it was derived from changes; the API key stays the one credential at rest.
 */
class SessionTokenProvider(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val apiUrl: String = CursorLoginEndpoints.API_URL,
    private val now: () -> Long = AppClock::now,
) {
    private class Session(val apiKey: String, val accessToken: String, val expiresAtMs: Long)

    private val mutex = Mutex()
    @Volatile private var session: Session? = null

    /** A session token good for at least [EXPIRY_MARGIN_MS] more, exchanging the stored key for a new one when needed. */
    suspend fun accessToken(): String {
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw SessionUnavailableException("Not signed in.", SessionUnavailableException.KEY_REJECTED)
        session?.takeIf { it.isFresh(apiKey) }?.let { return it.accessToken }
        return mutex.withLock {
            session?.takeIf { it.isFresh(apiKey) }?.accessToken ?: exchange(apiKey).also { session = it }.accessToken
        }
    }

    /** Forgets the current session, so the next call starts a new one; for after a `401` on an RPC. */
    fun invalidate() {
        session = null
    }

    /** On sign-out: nothing of the account stays in memory either. */
    fun clear() = invalidate()

    private fun Session.isFresh(forKey: String): Boolean = apiKey == forKey && expiresAtMs - now() > EXPIRY_MARGIN_MS

    private suspend fun exchange(apiKey: String): Session = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/auth/exchange_user_api_key")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(ConnectRpc.jsonBody("{}"))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw SessionUnavailableException("Couldn't reach Cursor to start a session.", cause = e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val code = runCatching { CursorJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw when {
                    code == SessionUnavailableException.SIGN_IN_POLICY_VIOLATION -> SessionUnavailableException(
                        "Your organization's device policy doesn't allow this device to sign in.",
                        SessionUnavailableException.SIGN_IN_POLICY_VIOLATION,
                    )
                    r.code == 401 || r.code == 403 -> SessionUnavailableException(
                        "Cursor won't start a session for this API key (HTTP ${r.code}).",
                        SessionUnavailableException.KEY_REJECTED,
                    )
                    else -> SessionUnavailableException("Cursor couldn't start a session (HTTP ${r.code}).", code)
                }
            }
            val tokens = runCatching { CursorJson.decodeFromString(ExchangeResponseDto.serializer(), text) }.getOrNull()
            val accessToken = tokens?.accessToken?.takeIf { it.isNotBlank() }
                ?: throw SessionUnavailableException("Cursor started a session but sent no token.")
            Session(apiKey, accessToken, expiresAtMs = jwtExpiryMs(accessToken) ?: (now() + DEFAULT_LIFETIME_MS))
        }
    }

    @Serializable
    private data class ExchangeResponseDto(val accessToken: String? = null, val refreshToken: String? = null)

    companion object {
        /** A session with less than this left is not handed out: the call it feeds could outlive it. */
        const val EXPIRY_MARGIN_MS = 60_000L
        /** Assumed lifetime of a token whose `exp` cannot be read. */
        const val DEFAULT_LIFETIME_MS = 30 * 60_000L

        /** The `exp` claim of a JWT as epoch millis, without verifying the signature (the server does that). */
        fun jwtExpiryMs(token: String): Long? {
            val payload = token.split('.').getOrNull(1) ?: return null
            val json = runCatching { String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
            val exp = runCatching { CursorJson.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull }.getOrNull()
            return exp?.let { it * 1000 }
        }
    }
}
