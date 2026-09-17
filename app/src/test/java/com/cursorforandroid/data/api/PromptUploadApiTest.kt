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
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The prompt-upload corner of `aiserver.v1.BackgroundComposerService` over Connect JSON, against the shapes read from
 * the Cursor 3.20.21 descriptors (`PresignPromptUploadRequest` @17.197M, `PresignPromptUploadResponse.Multipart`,
 * `PromptUploadPart`, `CompletePromptUploadRequest/Response`, `AbortPromptUploadRequest`, `PromptUploadCompletionStatus`).
 */
class PromptUploadApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectPromptUploadApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectPromptUploadApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a presign names the file, its type and its length as int64, and reads the multipart instructions back`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"uploadId":"up-1","urlsExpireAtMs":"1700000600000","multipart":{"s3UploadId":"s3-9","partSizeBytes":"5242880","parts":[
                     {"partNumber":2,"url":"https://s3/part2","offsetBytes":"5242880","sizeBytes":"1048576"},
                     {"partNumber":1,"url":"https://s3/part1","offsetBytes":"0","sizeBytes":5242880}
                   ]}}""",
            ),
        )

        val presigned = api.presign("report.pdf", "application/pdf", 6_291_456L, teamId = 42)

        assertThat(presigned.uploadId).isEqualTo("up-1")
        assertThat(presigned.s3UploadId).isEqualTo("s3-9")
        assertThat(presigned.partSizeBytes).isEqualTo(5_242_880L)
        assertThat(presigned.urlsExpireAtMs).isEqualTo(1_700_000_600_000L)
        // Parts come back in part order whatever order the account listed them in.
        assertThat(presigned.parts).containsExactly(
            PromptUploadPart(1, "https://s3/part1", 0L, 5_242_880L),
            PromptUploadPart(2, "https://s3/part2", 5_242_880L, 1_048_576L),
        ).inOrder()

        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/PresignPromptUpload")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        val body = request.json()
        assertThat(body["filename"]?.jsonPrimitive?.content).isEqualTo("report.pdf")
        assertThat(body["mimeType"]?.jsonPrimitive?.content).isEqualTo("application/pdf")
        // int64 travels as a decimal string in proto3 JSON, as the desktop's BigInt(size) does.
        assertThat(body["contentLengthBytes"]?.jsonPrimitive?.content).isEqualTo("6291456")
        assertThat(body["contentLengthBytes"]?.jsonPrimitive?.isString).isTrue()
        assertThat(body["teamId"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test
    fun `a blank type goes out as octet-stream and no team leaves the field off, as the desktop sends it`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"uploadId":"up-2","multipart":{"s3UploadId":"s3","parts":[{"partNumber":1,"url":"https://s3/p","offsetBytes":0,"sizeBytes":3}]}}"""))

        api.presign("notes", "", 3L)

        server.takeRequest()
        val body = server.takeRequest().json()
        assertThat(body["mimeType"]?.jsonPrimitive?.content).isEqualTo("application/octet-stream")
        assertThat(body.containsKey("teamId")).isFalse()
    }

    @Test
    fun `a presign without instructions is refused, as the desktop refuses one whose upload case is not multipart`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"uploadId":"up-3"}"""))
        server.enqueue(MockResponse().setBody("""{"multipart":{"s3UploadId":"s3","parts":[]}}"""))

        val noParts = runCatching { api.presign("a.bin", "application/octet-stream", 1L) }.exceptionOrNull()
        assertThat(noParts).isInstanceOf(ConnectRpcException::class.java)
        assertThat(noParts).hasMessageThat().contains("no upload instructions")
        val noId = runCatching { api.presign("a.bin", "application/octet-stream", 1L) }.exceptionOrNull()
        assertThat(noId).hasMessageThat().contains("upload id")
    }

    @Test
    fun `completion carries both ids and its status is read by name or by number, and abort carries the same ids`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"status":"PROMPT_UPLOAD_COMPLETION_STATUS_COMPLETED","sizeBytes":"6291456"}"""))
        server.enqueue(MockResponse().setBody("""{"status":4}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(api.complete("up-1", "s3-9")).isEqualTo(PromptUploadCompletion.COMPLETED)
        assertThat(api.complete("up-1", "s3-9")).isEqualTo(PromptUploadCompletion.SIZE_MISMATCH)
        assertThat(api.complete("up-1", "s3-9")).isEqualTo(PromptUploadCompletion.UNSPECIFIED)
        api.abort("up-1", "s3-9")

        server.takeRequest()
        val complete = server.takeRequest()
        assertThat(complete.path).isEqualTo("/aiserver.v1.BackgroundComposerService/CompletePromptUpload")
        assertThat(complete.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("uploadId", "up-1", "s3UploadId", "s3-9")
        server.takeRequest()
        server.takeRequest()
        val abort = server.takeRequest()
        assertThat(abort.path).isEqualTo("/aiserver.v1.BackgroundComposerService/AbortPromptUpload")
        assertThat(abort.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("uploadId", "up-1", "s3UploadId", "s3-9")
    }

    @Test
    fun `the completion enum matches the descriptor's numbering`() {
        assertThat(PromptUploadCompletion.entries.map { it.number }).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
        assertThat(PromptUploadCompletion.INVALID_PARTS.number).isEqualTo(5)
        assertThat(PromptUploadCompletion.SIZE_MISMATCH.number).isEqualTo(4)
        assertThat(PromptUploadCompletion.NO_PARTS.wireName).isEqualTo("PROMPT_UPLOAD_COMPLETION_STATUS_NO_PARTS")
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
