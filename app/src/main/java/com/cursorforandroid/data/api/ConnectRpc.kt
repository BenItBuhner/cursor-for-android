package com.cursorforandroid.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * A failed Connect call: the HTTP status, the protocol's `code` (`unauthenticated`, `permission_denied`, …) when the
 * body carried one, and the most readable explanation the body offered.
 */
class ConnectRpcException(val httpCode: Int, val code: String?, message: String) : IOException(message) {
    val isUnauthenticated: Boolean get() = httpCode == 401 || code == "unauthenticated"
}

/**
 * The Connect protocol (connectrpc.com) in its JSON encoding, which is how the app reaches the account-level
 * `aiserver.v1` services on api2 — the same handshake `@cursor/sdk` performs. A unary call is one `POST` to
 * `/<package>.<Service>/<Method>` with a JSON body in proto3's JSON mapping (lowerCamelCase fields, int64 as decimal
 * strings) and `Connect-Protocol-Version: 1`; no protobuf runtime is needed.
 */
object ConnectRpc {
    private val JSON = "application/json".toMediaType()

    /** A bare `application/json` body; the String overload would append a charset parameter. */
    fun jsonBody(json: String): RequestBody = json.toByteArray(Charsets.UTF_8).toRequestBody(JSON)

    fun request(baseUrl: String, service: String, method: String, accessToken: String, json: String): Request =
        Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$service/$method")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Connect-Protocol-Version", "1")
            .post(jsonBody(json))
            .build()

    /** The protocol's `code` field of an error body, if the body is one. */
    fun errorCode(body: String): String? = parse(body)?.string("code")

    /**
     * A Connect error is `{ code, message, details[] }`; Cursor's `message` is often just "Error", and the readable
     * explanation sits in `details[].debug.details.detail`.
     */
    fun errorReason(body: String): String? {
        val root = parse(body) ?: return null
        val debugDetails = (root["details"] as? JsonArray)
            ?.firstNotNullOfOrNull { ((it as? JsonObject)?.get("debug") as? JsonObject)?.get("details") as? JsonObject }
        val detail = debugDetails?.string("detail") ?: debugDetails?.string("title")
        val message = root.string("message")?.takeIf { it.isNotBlank() && !it.equals("Error", ignoreCase = true) }
        return detail ?: message ?: root.string("code")
    }

    private fun parse(body: String): JsonObject? = runCatching { CursorJson.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/** Unary Connect calls against one base URL, with the caller's serializers for the request and response messages. */
class ConnectJsonClient(private val client: OkHttpClient, private val baseUrl: String) {

    suspend fun <I, O> unary(
        service: String,
        method: String,
        accessToken: String,
        body: I,
        requestSerializer: KSerializer<I>,
        responseSerializer: KSerializer<O>,
    ): O = withContext(Dispatchers.IO) {
        val json = CursorJson.encodeToString(requestSerializer, body)
        client.newCall(ConnectRpc.request(baseUrl, service, method, accessToken, json)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ConnectRpcException(
                    httpCode = response.code,
                    code = ConnectRpc.errorCode(text),
                    message = ConnectRpc.errorReason(text) ?: "HTTP ${response.code}",
                )
            }
            CursorJson.decodeFromString(responseSerializer, text.ifBlank { "{}" })
        }
    }
}
