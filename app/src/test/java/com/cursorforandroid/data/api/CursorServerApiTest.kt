package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The cursor-server read, built from the exact shape the desktop's `cursor-resolver` uses (3.21.18): the unary
 * `GetCursorServerUrl {bc_id, commit, connection_token}` → `{host, port, connection_token, headers[], upgrade_path}`,
 * then a `GET /vscode-remote-resource?path=<path>&tkn=<token>` off that server with `Host` and the returned headers.
 * This is what a picture a tool call read outside the workspace (`/tmp/…`) is fetched by, the way Cursor's own app
 * reaches it; see the store's `internal/tmp-image-carried.md`.
 */
class CursorServerApiTest {

    private val server = MockWebServer()
    private lateinit var api: CursorServerApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = CursorServerApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
            http = client,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `GetCursorServerUrl is asked with the bcId, the desktop commit and the minted token, and its answer is read whole`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody("""{"host":"pod.example.com","port":443,"connectionToken":"srv-tok","headers":[{"key":"x-cursor","value":"1"}],"upgradePath":""}"""))

        val resolved = api.server("bc-1", CursorServerApi.DESKTOP_COMMIT, "minted-uuid")

        assertThat(resolved.host).isEqualTo("pod.example.com")
        assertThat(resolved.port).isEqualTo(443)
        assertThat(resolved.connectionToken).isEqualTo("srv-tok")
        assertThat(resolved.headers).containsExactly("x-cursor" to "1")
        assertThat(resolved.secure).isTrue()

        server.takeRequest() // the token exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetCursorServerUrl")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(body["commit"]?.jsonPrimitive?.content).isEqualTo(CursorServerApi.DESKTOP_COMMIT)
        assertThat(body["connectionToken"]?.jsonPrimitive?.content).isEqualTo("minted-uuid")
    }

    @Test
    fun `the answer's own token is preferred, and an empty one falls back to the minted token`() {
        val returned = CursorServer("h", 443, "returned", emptyList())
        assertThat(returned.connectionToken).isEqualTo("returned")
        // The URL carries the token as tkn and the path as a query parameter, on the remote-resource route.
        val url = returned.resourceUrl("/tmp/v02_mid.png")
        assertThat(url.encodedPath).isEqualTo("/vscode-remote-resource")
        assertThat(url.queryParameter("path")).isEqualTo("/tmp/v02_mid.png")
        assertThat(url.queryParameter("tkn")).isEqualTo("returned")
        assertThat(url.scheme).isEqualTo("https")
    }

    @Test
    fun `a file outside the workspace is fetched from the remote-resource route with the Host and returned headers`() = runBlocking<Unit> {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)
        val body = Buffer().write(png)
        server.enqueue(MockResponse().setBody(body))
        val host = server.hostName
        val port = server.port
        val resolved = CursorServer(host, port, "srv-tok", listOf("x-cursor" to "1"), upgradePath = null)

        val bytes = api.read(resolved, "/tmp/v02_mid.png")

        assertThat(bytes).isEqualTo(png)
        val request = server.takeRequest()
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/vscode-remote-resource")
        assertThat(request.requestUrl!!.queryParameter("path")).isEqualTo("/tmp/v02_mid.png")
        assertThat(request.requestUrl!!.queryParameter("tkn")).isEqualTo("srv-tok")
        assertThat(request.getHeader("x-cursor")).isEqualTo("1")
    }

    @Test
    fun `a not_found from the machine is a read exception carrying the request line and the status`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val resolved = CursorServer(server.hostName, server.port, "srv-tok", emptyList())
        val error = runCatching { api.read(resolved, "/tmp/gone.png") }.exceptionOrNull()
        assertThat(error).isInstanceOf(CursorServerReadException::class.java)
        error as CursorServerReadException
        assertThat(error.httpCode).isEqualTo(404)
        assertThat(error.asked).contains("/vscode-remote-resource?path=/tmp/gone.png → HTTP 404")
        assertThat(error.asked).doesNotContain("tkn=")
    }
}
