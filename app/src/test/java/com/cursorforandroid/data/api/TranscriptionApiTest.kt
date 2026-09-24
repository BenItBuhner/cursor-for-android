package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * `aiserver.v1.AiService/TranscribeAudio` on the wire as the desktop's batch dictation sends it (3.22.7,
 * `transcribeAudio` in the workbench bundle): the clip base64 in `audio`, the recorder's MIME type without its codec
 * parameters, no `language` unless one is given, the session token as the bearer; the answer's `text` and the int64
 * `transcriptionTimeMs`, which proto3 JSON writes as a string.
 */
class TranscriptionApiTest {

    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<String>()
    @Volatile private var answer: MockResponse = ok("""{"text":"fix the login","transcriptionTimeMs":"812"}""")
    private lateinit var api: AccountTranscriptionApi

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                bodies += request.body.readUtf8()
                return when (request.path) {
                    "/auth/exchange_user_api_key" -> ok("""{"accessToken":"session-token","refreshToken":"rt"}""")
                    "/aiserver.v1.AiService/TranscribeAudio" -> answer
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"unimplemented","message":"No such method"}""")
                }
            }
        }
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = AccountTranscriptionApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun transcribeCall() = calls.indexOfFirst { it.path == "/aiserver.v1.AiService/TranscribeAudio" }.let { calls[it] to bodies[it] }

    @Test
    fun `the clip goes up base64 with the bare MIME type and the session bearer`() {
        val audio = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0, 7)
        val result = runBlocking { api.transcribe(audio, "audio/webm;codecs=opus", language = null) }

        assertThat(result).isEqualTo(Transcription("fix the login", 812))
        val (request, body) = transcribeCall()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-token")
        assertThat(request.getHeader("Content-Type")).startsWith("application/json")
        val json = CursorJson.parseToJsonElement(body).jsonObject
        assertThat(json.keys).containsExactly("audio", "mimeType")
        assertThat(Base64.getDecoder().decode(json["audio"]!!.jsonPrimitive.content).toList()).isEqualTo(audio.toList())
        assertThat(json["mimeType"]!!.jsonPrimitive.content).isEqualTo("audio/webm")
    }

    @Test
    fun `a language is sent when given, and a number for the time is read as well as a string`() {
        answer = ok("""{"text":"hola","transcriptionTimeMs":95}""")
        val result = runBlocking { api.transcribe(byteArrayOf(1), "audio/mp4", language = "es") }
        assertThat(result).isEqualTo(Transcription("hola", 95))
        val json = CursorJson.parseToJsonElement(transcribeCall().second).jsonObject
        assertThat(json["language"]!!.jsonPrimitive.content).isEqualTo("es")
        assertThat(json["mimeType"]!!.jsonPrimitive.content).isEqualTo("audio/mp4")
    }

    @Test
    fun `an empty answer is empty text, not a failure`() {
        answer = ok("{}")
        assertThat(runBlocking { api.transcribe(byteArrayOf(1), "audio/webm", null) }).isEqualTo(Transcription("", null))
    }

    @Test
    fun `a refusal is the server's Connect error, not retried`() {
        answer = MockResponse().setResponseCode(400).setBody("""{"code":"invalid_argument","message":"Unsupported audio format"}""")
        val e = assertThrows(ConnectRpcException::class.java) { runBlocking { api.transcribe(byteArrayOf(1), "audio/ogg", null) } }
        assertThat(e.code).isEqualTo("invalid_argument")
        assertThat(calls.count { it.path == "/aiserver.v1.AiService/TranscribeAudio" }).isEqualTo(1)
    }
}
