package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Where the agent's machine serves its files outside the workspace, as Cursor's own client reaches it: the
 * cursor-server — Cursor's VS Code remote server on the pod — behind `GetCursorServerUrl`.
 *
 * The desktop (3.21.18) opens a cloud agent as `vscode-remote://background-composer+<bcId>/workspace`, and a picture a
 * tool call read in `/tmp` as `vscode-remote://background-composer+<bcId>/tmp/x.jpg`. The `cursor-resolver` extension
 * resolves that authority with `GetCursorServerUrl {bc_id, commit, connection_token}` (the token a UUID the client
 * mints, the commit its own build) and connects to `host:port` over TLS (port 443, or an upgrade path) with
 * `Host: host:port` and every header the answer names. The file is then read by the cursor-server, whose file system
 * has no workspace fence — unlike `ReadBinaryFile`, which answers `invalid_argument "File path must stay within the
 * workspace."` for `/tmp`. A remote picture is loaded from that server as
 * `/vscode-remote-resource?path=<path>&tkn=<connection token>` (VS Code's `FileAccess.uriToBrowserUri`; the web build
 * fetches it over `https`). This reads it the same way. See the store's `internal/tmp-image-carried.md`.
 */
interface CursorServerFiles {
    /** `GetCursorServerUrl`: the server for [agentId]'s machine, started or reached with [connectionToken]. */
    suspend fun server(agentId: String, commit: String, connectionToken: String): CursorServer

    /** The bytes of [path] on the machine, from [server]'s remote-resource route. */
    suspend fun read(server: CursorServer, path: String): ByteArray
}

/** `GetCursorServerUrlResponse`: where the server listens, the token it takes, and the headers its ingress wants. */
data class CursorServer(
    val host: String,
    val port: Int,
    val connectionToken: String,
    val headers: List<Pair<String, String>>,
    /** A self-hosted worker's WebSocket path; empty for a Cursor pod. */
    val upgradePath: String? = null,
) {
    /** TLS as the desktop's resolver decides it: port 443, or a worker's upgrade path. */
    val secure: Boolean get() = port == 443 || !upgradePath.isNullOrEmpty()

    /** `…/vscode-remote-resource?path=<path>&tkn=<token>`, the route VS Code's server serves any file of the machine on. */
    fun resourceUrl(path: String): HttpUrl = HttpUrl.Builder()
        .scheme(if (secure) "https" else "http")
        .host(host)
        .port(port)
        .addPathSegment(REMOTE_RESOURCE)
        .addQueryParameter("path", path)
        .addQueryParameter(TOKEN_PARAM, connectionToken)
        .build()

    /** The request line for the diagnostics and the notice, the token left out. */
    fun describe(path: String): String = "GET ${if (secure) "https" else "http"}://$host:$port/$REMOTE_RESOURCE?path=$path"

    companion object {
        const val REMOTE_RESOURCE = "vscode-remote-resource"
        const val TOKEN_PARAM = "tkn"
    }
}

/** The cursor-server answered the resource read with something other than the file. */
class CursorServerReadException(val httpCode: Int, val asked: String, message: String) : IOException(message)

class CursorServerApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
    private val http: OkHttpClient,
) : CursorServerFiles {

    override suspend fun server(agentId: String, commit: String, connectionToken: String): CursorServer {
        val response = rpc.unaryWithSession(
            BackgroundComposerApi.SERVICE,
            "GetCursorServerUrl",
            tokens,
            RequestDto(bcId = agentId, commit = commit, connectionToken = connectionToken),
            RequestDto.serializer(),
            ResponseDto.serializer(),
            retryRefusals = false,
            lane = ApiThrottle.Lane.MEDIA,
        )
        val host = response.host?.trim().orEmpty()
        val port = response.port ?: 0
        if (host.isEmpty() || port !in 1..65535) {
            throw ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "Cursor's answer to GetCursorServerUrl named no server.", path = ConnectRpc.path(BackgroundComposerApi.SERVICE, "GetCursorServerUrl"))
        }
        return CursorServer(
            host = host,
            port = port,
            // The resolver prefers the token the answer returns and falls back to the one it minted.
            connectionToken = response.connectionToken?.takeIf { it.isNotBlank() } ?: connectionToken,
            headers = response.headers.mapNotNull { h -> h.key?.takeIf { it.isNotBlank() }?.let { it to h.value.orEmpty() } },
            upgradePath = response.upgradePath?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun read(server: CursorServer, path: String): ByteArray {
        val request = Request.Builder()
            .url(server.resourceUrl(path))
            // The same Host the desktop's WebSocket upgrade names, and the ingress's headers after it.
            .header("Host", "${server.host}:${server.port}")
            .apply { server.headers.forEach { (key, value) -> header(key, value) } }
            .get()
            .build()
        return http.newCall(request).readCancellably { response ->
            val asked = server.describe(path)
            if (!response.isSuccessful) {
                val said = response.body?.let { body -> runCatching { body.source().readUtf8(minOf(body.contentLength().takeIf { it >= 0 } ?: 200L, 200L)) }.getOrNull() }
                throw CursorServerReadException(response.code, "$asked → HTTP ${response.code}" + (said?.trim()?.takeIf { it.isNotEmpty() }?.let { " \"${it.take(120)}\"" } ?: ""), "The agent's machine answered HTTP ${response.code}.")
            }
            val body = response.body ?: throw CursorServerReadException(response.code, "$asked → HTTP ${response.code} (no body)", "The agent's machine sent nothing.")
            val length = body.contentLength()
            if (length > MAX_BYTES) throw CursorServerReadException(response.code, "$asked → HTTP ${response.code} ($length bytes)", "The file is larger than this app reads.")
            body.source().use { source ->
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) throw CursorServerReadException(response.code, "$asked → HTTP ${response.code} (over ${MAX_BYTES shr 20} MB)", "The file is larger than this app reads.")
                source.readByteArray()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class RequestDto(val bcId: String, @EncodeDefault val commit: String, val connectionToken: String? = null)

    @Serializable
    private data class ResponseDto(
        val host: String? = null,
        val port: Int? = null,
        val connectionToken: String? = null,
        val headers: List<HeaderDto> = emptyList(),
        val upgradePath: String? = null,
    )

    @Serializable
    private data class HeaderDto(val key: String? = null, val value: String? = null)

    companion object {
        /** The build whose cursor-server the pod is asked for: desktop 3.21.18's product commit, as its resolver sends it. */
        const val DESKTOP_COMMIT = "c4730f7d93d787d9ab120af715999f0345ee5bc0"
        private const val MAX_BYTES = 64L shl 20
    }
}
