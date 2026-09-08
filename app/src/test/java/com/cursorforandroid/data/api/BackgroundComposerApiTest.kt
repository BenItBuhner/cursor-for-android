package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.PullRequestState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The pin RPCs of `aiserver.v1.BackgroundComposerService` over Connect JSON, against a fake api2 that also plays the token exchange. */
class BackgroundComposerApiTest {

    private val server = MockWebServer()
    private lateinit var api: BackgroundComposerApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = BackgroundComposerApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `reads the account's pinned ids and pull request states with the status and pinned state requested`() = runBlocking<Unit> {
        server.enqueue(session("session-1"))
        server.enqueue(
            MockResponse().setBody(
                """{"composers":[
                     {"bcId":"bc-1","name":"x","prUrl":"https://github.com/acme/app/pull/1","isPrMerged":false,"prStatus":"PR_STATUS_OPEN"},
                     {"bcId":"bc-2","prUrl":"https://github.com/acme/app/pull/2","isPrMerged":false,"prStatus":"PR_STATUS_DRAFT"},
                     {"bcId":"bc-3","prUrl":"https://gitlab.com/acme/app/-/merge_requests/3","isPrMerged":true,"prStatus":"PR_STATUS_MERGED"},
                     {"bcId":"bc-4","prUrl":"https://github.com/acme/app/pull/4","isPrMerged":false,"prStatus":4},
                     {"bcId":"bc-5","prUrl":"https://github.com/acme/app/pull/5","isPrMerged":true},
                     {"bcId":"bc-6","prUrl":"https://github.com/acme/app/pull/6","isPrMerged":false},
                     {"bcId":"bc-7","name":"no pr"}
                   ],"didLoadStatus":true,"hasMore":true,"pinnedBcIds":["bc-1","bc-9"],"didLoadPinnedState":true,"nextPageToken":"t"}""",
            ),
        )

        val list = api.list()

        assertThat(list.pinned).isEqualTo(PinnedIds(setOf("bc-1", "bc-9"), loaded = true))
        assertThat(list.pullRequests).containsExactly(
            "https://github.com/acme/app/pull/1", PullRequestState.Open,
            "https://github.com/acme/app/pull/2", PullRequestState.Draft,
            "https://gitlab.com/acme/app/-/merge_requests/3", PullRequestState.Merged,
            "https://github.com/acme/app/pull/4", PullRequestState.Closed,
            // No status yet, but the merge flag is set: merged it is. Neither flag nor status: nothing to say.
            "https://github.com/acme/app/pull/5", PullRequestState.Merged,
        )
        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListBackgroundComposers")
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json")
        val body = request.json()
        assertThat(body["includePinnedState"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["includeStatus"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["includeArchived"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["n"]?.jsonPrimitive?.content).isEqualTo(BackgroundComposerApi.LIST_WINDOW.toString())
    }

    @Test
    fun `a list without the pinned state reports it as not loaded`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composers":[],"didLoadStatus":true,"hasMore":false}"""))

        assertThat(api.list()).isEqualTo(AccountList(PinnedIds(emptySet(), loaded = false), emptyMap()))
    }

    @Test
    fun `reads one pull request's standing from its merge status`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"isMerged":true,"isClosed":true,"mergeableState":"unknown","state":"closed","isDraft":false,"title":"Add pins"}"""))
        server.enqueue(MockResponse().setBody("""{"isMerged":false,"isClosed":true,"state":"closed"}"""))
        server.enqueue(MockResponse().setBody("""{"isMerged":false,"isClosed":false,"state":"open","isDraft":true}"""))
        server.enqueue(MockResponse().setBody("""{"isMerged":false,"isClosed":false,"state":"open","isDraft":false,"mergeableState":"clean"}"""))
        server.enqueue(MockResponse().setBody("""{}"""))

        assertThat(api.mergeStatus("https://github.com/acme/app/pull/3")).isEqualTo(PullRequestState.Merged)
        assertThat(api.mergeStatus("https://github.com/acme/app/pull/4")).isEqualTo(PullRequestState.Closed)
        assertThat(api.mergeStatus("https://github.com/acme/app/pull/2")).isEqualTo(PullRequestState.Draft)
        assertThat(api.mergeStatus("https://github.com/acme/app/pull/1")).isEqualTo(PullRequestState.Open)
        assertThat(api.mergeStatus("https://github.com/acme/app/pull/9")).isNull()

        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetPullRequestMergeStatus")
        assertThat(request.json()["prUrl"]?.jsonPrimitive?.content).isEqualTo("https://github.com/acme/app/pull/3")
    }

    @Test
    fun `pins and unpins send the ids as bcIds and accept an empty response`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        api.pin(listOf("bc-1", "bc-2"))
        api.unpin(listOf("bc-3"))

        server.takeRequest()
        val pin = server.takeRequest()
        assertThat(pin.path).isEqualTo("/aiserver.v1.BackgroundComposerService/PinBackgroundComposers")
        assertThat(pin.json()["bcIds"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("bc-1", "bc-2").inOrder()
        val unpin = server.takeRequest()
        assertThat(unpin.path).isEqualTo("/aiserver.v1.BackgroundComposerService/UnpinBackgroundComposers")
        assertThat(unpin.json()["bcIds"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("bc-3")
    }

    @Test
    fun `nothing to pin means no request`() = runBlocking<Unit> {
        api.pin(emptyList())
        api.unpin(emptyList())

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a lapsed session is exchanged again and the call retried once`() = runBlocking<Unit> {
        server.enqueue(session("stale"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthenticated","message":"Error"}"""))
        server.enqueue(session("fresh"))
        server.enqueue(MockResponse().setBody("{}"))

        api.pin(listOf("bc-1"))

        assertThat(server.requestCount).isEqualTo(4)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer stale")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh")
    }

    @Test
    fun `a refusal carries the status, the Connect code and the explanation`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"code":"permission_denied","message":"Error","details":[{"type":"aiserver.v1.ErrorDetails","debug":{"details":{"title":"Not yours","detail":"This agent belongs to another user."}}}]}""",
            ),
        )

        val error = runCatching { api.pin(listOf("bc-1")) }.exceptionOrNull() as ConnectRpcException

        assertThat(error.httpCode).isEqualTo(403)
        assertThat(error.code).isEqualTo("permission_denied")
        assertThat(error.isUnauthenticated).isFalse()
        assertThat(error).hasMessageThat().isEqualTo("This agent belongs to another user.")
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
