package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The server-streaming Connect call in its JSON encoding, as [ConnectJsonClient.serverStream] reads it: the request
 * enveloped under `application/connect+json`, each message read out of its envelope, the end-of-stream envelope's
 * error thrown with the request path on it, a refusal before the stream thrown the same way, and the connection
 * left the moment the caller has what it came for. The wire [ConversationStateReader] reads the conversation
 * state off (`StreamConversation`), pinned apart from what it carries.
 */
class ConnectStreamTest {

    private val server = MockWebServer()
    private lateinit var rpc: ConnectJsonClient
    private lateinit var tokens: SessionTokenProvider

    @Serializable
    private data class Ask(val bcId: String)

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        rpc = ConnectJsonClient(client, server.url("/").toString())
        tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun session() = server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))

    @Test
    fun `messages come out of their envelopes in order and the caller may leave early`() = runBlocking<Unit> {
        session()
        server.enqueue(ConnectStreamFixtures.streamResponse(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}""")))
        val seen = ArrayList<String>()
        val count = rpc.serverStreamWithSession("aiserver.v1.BackgroundComposerService", "StreamConversation", tokens, Ask("bc-1"), Ask.serializer()) { m ->
            seen += m.keys.single()
            m.keys.single() != "b"
        }
        assertThat(seen).containsExactly("a", "b").inOrder()
        assertThat(count).isEqualTo(2)
        server.takeRequest(5, TimeUnit.SECONDS) // the session
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StreamConversation")
        assertThat(request.getHeader("Content-Type")).startsWith("application/connect+json")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(ConnectStreamFixtures.requestJson(request)).isEqualTo("""{"bcId":"bc-1"}""")
    }

    @Test
    fun `a stream that ends in an error throws it with the request path`() = runBlocking<Unit> {
        session()
        server.enqueue(ConnectStreamFixtures.errorStream("unimplemented", "streamConversation has been removed"))
        val failure = runCatching { rpc.serverStreamWithSession("aiserver.v1.BackgroundComposerService", "StreamConversation", tokens, Ask("bc-1"), Ask.serializer()) { true } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ConnectRpcException::class.java)
        failure as ConnectRpcException
        assertThat(failure.code).isEqualTo("unimplemented")
        assertThat(failure.message).isEqualTo("streamConversation has been removed")
        assertThat(failure.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StreamConversation")
    }

    @Test
    fun `a refusal before the stream is read as a unary refusal, path and all`() = runBlocking<Unit> {
        session()
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"code":"unimplemented","message":"streamConversation has been removed"}"""))
        val failure = runCatching { rpc.serverStreamWithSession("aiserver.v1.BackgroundComposerService", "StreamConversation", tokens, Ask("bc-1"), Ask.serializer()) { true } }.exceptionOrNull() as ConnectRpcException
        assertThat(failure.httpCode).isEqualTo(404)
        assertThat(failure.code).isEqualTo("unimplemented")
        assertThat(failure.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StreamConversation")
    }

    @Test
    fun `a unary refusal carries the path too`() = runBlocking<Unit> {
        session()
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"code":"unimplemented","message":"getLatestAgentConversationState has been removed"}"""))
        val failure = runCatching { rpc.unaryWithSession("aiserver.v1.BackgroundComposerService", "GetLatestAgentConversationState", tokens, Ask("bc-1"), Ask.serializer(), Ask.serializer()) }.exceptionOrNull() as ConnectRpcException
        assertThat(failure.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetLatestAgentConversationState")
        assertThat(failure.message).isEqualTo("getLatestAgentConversationState has been removed")
    }

    @Test
    fun `the state reader takes the conversation state and the prefetched blobs off the stream and leaves`() = runBlocking<Unit> {
        session()
        val blobs = BlobCache()
        blobs.put("bc-1", "aGVsZA==", byteArrayOf(1, 2, 3))
        server.enqueue(
            ConnectStreamFixtures.prewarmResponse(
                """{"turns":["dHVybi0w","dHVybi0x"],"turnTimings":[{"durationMs":"30000","timestampMs":"1800000000000"}],"isRootProjectConversation":true}""",
                blobs = listOf("dHVybi0x" to byteArrayOf(9, 9), "c3RlcA==" to byteArrayOf(7)),
                cloudAgentExtra = """"numPriorInteractionUpdates":42""",
            ),
        )
        val reader = ConversationStateReader(rpc, tokens, blobs)
        val initial = reader.read("bc-1")
        assertThat((initial.conversationState!!["turns"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }).containsExactly("dHVybi0w", "dHVybi0x").inOrder()
        assertThat(initial.cloudAgentState!!["numPriorInteractionUpdates"]!!.jsonPrimitive.content).isEqualTo("42")
        assertThat(initial.prefetchedCount).isEqualTo(2)
        assertThat(initial.kinds).containsExactly("prefetchedBlobs", "initialState").inOrder()
        assertThat(blobs.get("bc-1", "dHVybi0x")).isEqualTo(byteArrayOf(9, 9))
        assertThat(blobs.get("bc-1", "c3RlcA==")).isEqualTo(byteArrayOf(7))
        // The request told the server what this device held already, as the desktop's prewarm does.
        server.takeRequest(5, TimeUnit.SECONDS)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = CursorJson.parseToJsonElement(ConnectStreamFixtures.requestJson(request)).jsonObject
        assertThat(body["purpose"]!!.jsonPrimitive.content).isEqualTo("STREAM_CONVERSATION_PURPOSE_PREWARM")
        assertThat(body["shouldSendPrefetchedBlobsFirst"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["prefetchOnlyLastStepPerTurn"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["filterHeavyStepData"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["maxBlobsAfterPrefetch"]!!.jsonPrimitive.content).isEqualTo("30")
        assertThat(body["preFetchedBlobIds"].toString()).isEqualTo("""["aGVsZA=="]""")
    }
}
