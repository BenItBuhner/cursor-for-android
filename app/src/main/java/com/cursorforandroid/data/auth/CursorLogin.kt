package com.cursorforandroid.data.auth

import com.cursorforandroid.data.api.ConnectRpc
import com.cursorforandroid.data.api.CursorJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.math.pow

/** The hosts of the browser login: the confirmation page on cursor.com, the token release and dashboard RPCs on api2. */
object CursorLoginEndpoints {
    const val WEBSITE_URL = "https://cursor.com"
    const val API_URL = "https://api2.cursor.sh"
}

/**
 * One browser-login attempt. The [verifier] never leaves the device except to `/auth/poll` — in its body, or in the
 * query string of the GET a backend without that route falls back to; the page only ever sees its SHA-256
 * [challenge][loginUrl], so nothing that can observe the browser can redeem the login.
 */
data class LoginHandshake(val uuid: String, val verifier: String, val loginUrl: String)

/** Session tokens released once the browser confirmed the login. Used once, to mint an API key, then dropped. */
data class SessionTokens(val accessToken: String, val refreshToken: String)

class CursorLoginException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sign in with a Cursor account the way Cursor's own CLI and SDK do it, and end up with a plain user API key.
 *
 * 1. [startHandshake] derives a PKCE pair and the `cursor.com/loginDeepControl` URL the user opens in a browser.
 * 2. [awaitTokens] polls `POST /auth/poll` with the verifier until the page has confirmed the login (`404` = pending).
 * 3. [mintApiKey] spends the short-lived session token once on `DashboardService/CreateUserApiKey`; the returned
 *    key is the only credential the app keeps, and it is an ordinary key the Cloud Agents API already accepts.
 *
 * The RPC uses the Connect protocol's JSON encoding, so no protobuf runtime is needed.
 */
class CursorLogin(
    private val client: OkHttpClient,
    private val websiteUrl: String = CursorLoginEndpoints.WEBSITE_URL,
    private val apiUrl: String = CursorLoginEndpoints.API_URL,
    private val random: SecureRandom = SecureRandom(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    fun startHandshake(): LoginHandshake {
        val verifier = ByteArray(32).also(random::nextBytes).base64Url()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).base64Url()
        val uuid = UUID.randomUUID().toString()
        // `redirectTarget=sdk` attributes the login to a third-party app on the confirmation and "all set" pages;
        // `supportsSelectedTeamLogin` lets a member of several teams pick the one this key should belong to.
        val url = "${websiteUrl.trimEnd('/')}/loginDeepControl?challenge=$challenge&uuid=$uuid&mode=login" +
            "&redirectTarget=sdk&supportsSelectedTeamLogin=true"
        return LoginHandshake(uuid = uuid, verifier = verifier, loginUrl = url)
    }

    /**
     * Waits for the browser to confirm the login. The backend answers `404 Not found` until then; the delay between
     * polls grows from [baseDelayMs] by 1.2x up to [maxDelayMs] (about twenty minutes over the default attempts).
     * Fails after three consecutive unexpected answers, so a rejected or expired login does not spin.
     */
    suspend fun awaitTokens(
        handshake: LoginHandshake,
        maxAttempts: Int = POLL_MAX_ATTEMPTS,
        baseDelayMs: Long = POLL_BASE_DELAY_MS,
        maxDelayMs: Long = POLL_MAX_DELAY_MS,
    ): SessionTokens {
        var consecutiveErrors = 0
        var useGet = false
        var pendingSeen = false
        var attempt = 0
        while (attempt < maxAttempts) {
            currentCoroutineContext().ensureActive()
            val wait = (baseDelayMs * 1.2.pow(attempt)).toLong().coerceAtMost(maxDelayMs)
            attempt++
            val response = try {
                client.newCall(pollRequest(handshake, useGet)).execute()
            } catch (e: IOException) {
                if (++consecutiveErrors >= POLL_MAX_CONSECUTIVE_ERRORS) {
                    throw CursorLoginException("Couldn't reach Cursor to finish signing in. Check your connection and try again.", e)
                }
                sleep(wait)
                continue
            }
            response.use { r ->
                when {
                    r.code == 404 -> {
                        if (!pendingSeen) {
                            // A backend without `POST /auth/poll` answers with a route-not-found JSON rather than the
                            // plain "Not found" of a pending login; fall back to the query-string form once.
                            val body = r.body?.string().orEmpty().trim()
                            if (!useGet && body != PENDING_BODY && isRouteNotFound(body)) {
                                useGet = true
                                return@use
                            }
                            if (useGet && isRouteNotFound(body)) {
                                throw CursorLoginException("Cursor's sign-in service is unavailable right now. Try again later.")
                            }
                            pendingSeen = true
                        }
                        consecutiveErrors = 0
                        sleep(wait)
                    }
                    !r.isSuccessful -> {
                        if (++consecutiveErrors >= POLL_MAX_CONSECUTIVE_ERRORS) {
                            throw CursorLoginException("Cursor rejected this sign-in (HTTP ${r.code}). Start over and try again.")
                        }
                        sleep(wait)
                    }
                    else -> {
                        val tokens = runCatching { CursorJson.decodeFromString(PollTokensDto.serializer(), r.body?.string().orEmpty()) }.getOrNull()
                        val access = tokens?.accessToken?.takeIf { it.isNotBlank() }
                        val refresh = tokens?.refreshToken.orEmpty()
                        if (access == null) throw CursorLoginException("Cursor confirmed the sign-in but sent no session. Try again.")
                        return SessionTokens(accessToken = access, refreshToken = refresh)
                    }
                }
            }
        }
        throw CursorLoginException("The sign-in wasn't confirmed in time. Start over and try again.")
    }

    /**
     * Creates a user API key named [name] on the signed-in account, expiring at [expiresAtMs] (epoch millis; null
     * for a key that never expires). Teams can restrict user API keys, in which case the RPC fails and the error
     * carries the dashboard's explanation.
     */
    suspend fun mintApiKey(accessToken: String, name: String, expiresAtMs: Long?): String {
        val body = CursorJson.encodeToString(
            CreateUserApiKeyRequestDto.serializer(),
            // proto3's JSON mapping writes int64 as a decimal string.
            CreateUserApiKeyRequestDto(name = name, expiresAt = expiresAtMs?.toString()),
        )
        val request = ConnectRpc.request(apiUrl, "aiserver.v1.DashboardService", "CreateUserApiKey", accessToken, body)
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw CursorLoginException("Signed in, but the connection dropped before Cursor could issue a key for this app. Try again.", e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val reason = ConnectRpc.errorReason(text) ?: "HTTP ${r.code}"
                throw CursorLoginException(
                    "Signed in, but Cursor wouldn't create an API key for this app ($reason). " +
                        "Your team's settings may restrict user API keys; paste a key from the dashboard instead.",
                )
            }
            val parsed = runCatching { CursorJson.decodeFromString(CreateUserApiKeyResponseDto.serializer(), text) }.getOrNull()
            return parsed?.apiKey?.takeIf { it.isNotBlank() } ?: parsed?.apiKeySnake?.takeIf { it.isNotBlank() }
                ?: throw CursorLoginException("Signed in, but Cursor returned an empty API key. Try again.")
        }
    }

    private fun pollRequest(handshake: LoginHandshake, useGet: Boolean): Request {
        val base = "${apiUrl.trimEnd('/')}/auth/poll"
        val builder = Request.Builder().header("Accept", "application/json")
        return if (useGet) {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("uuid", handshake.uuid)
                .addQueryParameter("verifier", handshake.verifier)
                .build()
            builder.url(url).get().build()
        } else {
            val json = CursorJson.encodeToString(PollRequestDto.serializer(), PollRequestDto(handshake.uuid, handshake.verifier))
            builder.url(base).post(ConnectRpc.jsonBody(json)).build()
        }
    }

    /**
     * Fastify's `{"message":"Route POST:/auth/poll not found", …}`, as opposed to the pending login's plain text.
     * Anchored on the whole shape — the word, the route this actually is, and the verdict — so a message that merely
     * mentions a missing route somewhere else cannot send the poll down the fallback.
     */
    private fun isRouteNotFound(body: String): Boolean {
        if (!body.startsWith("{")) return false
        val message = runCatching { CursorJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            ?: return false
        return message.startsWith("Route ") && message.contains("/auth/poll") && message.contains("not found")
    }

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    @Serializable
    private data class PollRequestDto(val uuid: String, val verifier: String)

    @Serializable
    private data class PollTokensDto(val accessToken: String? = null, val refreshToken: String? = null)

    @Serializable
    private data class CreateUserApiKeyRequestDto(val name: String, val expiresAt: String? = null)

    @Serializable
    private data class CreateUserApiKeyResponseDto(
        val apiKey: String? = null,
        @SerialName("api_key") val apiKeySnake: String? = null,
    )

    companion object {
        private const val PENDING_BODY = "Not found"
        const val POLL_MAX_ATTEMPTS = 150
        const val POLL_BASE_DELAY_MS = 1_000L
        const val POLL_MAX_DELAY_MS = 10_000L
        private const val POLL_MAX_CONSECUTIVE_ERRORS = 3

        /** Lifetime of a key minted by the browser login: the Cursor SDK's default of 90 days. */
        const val API_KEY_TTL_MS = 90L * 24 * 60 * 60 * 1000
    }
}
