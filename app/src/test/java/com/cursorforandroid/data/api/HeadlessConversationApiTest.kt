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
import org.junit.After
import org.junit.Before
import org.junit.Test

/** `FetchBackgroundComposer` over Connect JSON: the request's paging fields and the steps read off the answer. */
class HeadlessConversationApiTest {

    private val server = MockWebServer()
    private lateinit var api: HeadlessConversationApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = HeadlessConversationApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `reads a page of the record as steps, prompts, text, thinking, tool calls and their results`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"responses":[
                     {"userMessage":{"text":"Add a README","richText":"{}"}},
                     {"thinking":{"text":"Looking around.","signature":"x"}},
                     {"toolCall":{"tool":9,"toolCallId":"call-1","name":"read_file","rawArgs":"{\"path\":\"README.md\"}","isStreaming":false}},
                     {"finalToolResult":{"toolCallId":"call-1","result":{"readFileResult":{"contents":"# App","totalLines":1}}}},
                     {"text":"Done"},
                     {"text":".","isMessageDone":true},
                     {"status":{"type":"finished","isComplete":true}},
                     {"humanMessage":{"text":"Thanks","createdAt":"2026"}}
                   ],"totalResponses":42}""",
            ),
        )

        val page = api.fetch("bc-1", startIndex = 34, limit = 8)

        assertThat(page.totalResponses).isEqualTo(42)
        assertThat(page.startIndex).isEqualTo(34)
        // One step per response, the status a blank one: the page's steps line up with the indices asked for.
        assertThat(page.steps.map { it.userMessage }).containsExactly("Add a README", null, null, null, null, null, null, "Thanks").inOrder()
        assertThat(page.steps[1].thinking).isEqualTo("Looking around.")
        assertThat(page.steps[2].toolCall!!.callId).isEqualTo("call-1")
        assertThat(page.steps[2].toolCall!!.name).isEqualTo("read_file")
        assertThat(page.steps[2].toolCall!!.args!!.jsonObject["path"]!!.jsonPrimitive.content).isEqualTo("README.md")
        assertThat(page.steps[3].toolResult!!.callId).isEqualTo("call-1")
        assertThat(page.steps[4].text).isEqualTo("Done")
        assertThat(page.steps[5].text).isEqualTo(".")

        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/FetchBackgroundComposer")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body["bcId"]!!.jsonPrimitive.content).isEqualTo("bc-1")
        assertThat(body["startIndex"]!!.jsonPrimitive.content).isEqualTo("34")
        assertThat(body["limit"]!!.jsonPrimitive.content).isEqualTo("8")
    }
}
