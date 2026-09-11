package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ProjectAppearance
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

/** The pin, archive and rename RPCs of `aiserver.v1.BackgroundComposerService` over Connect JSON, against a fake api2 that also plays the token exchange. */
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
    fun `reads the account's pinned ids, pull request states and sources with the status, pinned state and hidden sources requested`() = runBlocking<Unit> {
        server.enqueue(session("session-1"))
        server.enqueue(
            MockResponse().setBody(
                """{"composers":[
                     {"bcId":"bc-1","name":"x","isArchived":false,"prUrl":"https://github.com/acme/app/pull/1","isPrMerged":false,"prStatus":"PR_STATUS_OPEN","source":"BACKGROUND_COMPOSER_SOURCE_WEBSITE"},
                     {"bcId":"bc-2","prUrl":"https://github.com/acme/app/pull/2","isPrMerged":false,"prStatus":"PR_STATUS_DRAFT","source":"BACKGROUND_COMPOSER_SOURCE_SLACK"},
                     {"bcId":"bc-3","prUrl":"https://gitlab.com/acme/app/-/merge_requests/3","isPrMerged":true,"prStatus":"PR_STATUS_MERGED","source":"BACKGROUND_COMPOSER_SOURCE_GITLAB"},
                     {"bcId":"bc-4","prUrl":"https://github.com/acme/app/pull/4","isPrMerged":false,"prStatus":4,"source":21},
                     {"bcId":"bc-5","prUrl":"https://github.com/acme/app/pull/5","isPrMerged":true,"source":"BACKGROUND_COMPOSER_SOURCE_GROK_BOT"},
                     {"bcId":"bc-6","prUrl":"https://github.com/acme/app/pull/6","isPrMerged":false,"source":"BACKGROUND_COMPOSER_SOURCE_TELEPATHY"},
                     {"bcId":"bc-7","name":"no pr","isArchived":true},
                     {"bcId":"bc-8","name":"Billing launch","projectMetadata":{"appearance":{"icon":"rocket","colorId":"purple"}},"startedAsNewProject":false},
                     {"bcId":"bc-10","name":"Webhook worker","managerAgentId":"bc-8","source":"BACKGROUND_COMPOSER_SOURCE_WEBSITE"},
                     {"bcId":"bc-11","name":"Pricing side chat","sideChatInfo":{"parentBcId":"bc-8","seedTurnCount":3}}
                   ],"didLoadStatus":true,"hasMore":true,"pinnedBcIds":["bc-1","bc-9"],"didLoadPinnedState":true,"nextPageToken":"t"}""",
            ),
        )

        val list = api.list()

        assertThat(list.pinned).isEqualTo(PinnedIds(setOf("bc-1", "bc-9"), loaded = true))
        assertThat(list.composers).containsAtLeast(
            ComposerSnapshot("bc-1", name = "x", archived = false),
            ComposerSnapshot("bc-7", name = "no pr", archived = true),
            ComposerSnapshot("bc-8", name = "Billing launch", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple")),
            ComposerSnapshot("bc-10", name = "Webhook worker", parent = AgentParent("bc-8", AgentParentKind.PROJECT_WORKER)),
            ComposerSnapshot("bc-11", name = "Pricing side chat", parent = AgentParent("bc-8", AgentParentKind.SIDE_CHAT)),
        )
        assertThat(list.pullRequests).containsExactly(
            "https://github.com/acme/app/pull/1", PullRequestState.Open,
            "https://github.com/acme/app/pull/2", PullRequestState.Draft,
            "https://gitlab.com/acme/app/-/merge_requests/3", PullRequestState.Merged,
            "https://github.com/acme/app/pull/4", PullRequestState.Closed,
            // No status yet, but the merge flag is set: merged it is. Neither flag nor status: nothing to say.
            "https://github.com/acme/app/pull/5", PullRequestState.Merged,
        )
        // By name or by number; a source this build does not know is still one; a record without one (the proto's
        // zero value is left out of the JSON) says nothing.
        assertThat(list.sources).containsExactly(
            "bc-1", AgentSource.WEBSITE,
            "bc-2", AgentSource.SLACK,
            "bc-3", AgentSource.GITLAB,
            "bc-4", AgentSource.SDK,
            "bc-5", AgentSource.GROK_BOT,
            "bc-6", AgentSource.UNKNOWN,
            "bc-10", AgentSource.WEBSITE,
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
        // SDK agents are left out of the list unless asked for, as cursor.com/agents leaves them out until its Source filter says SDK.
        assertThat(body["includeHiddenSources"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("BACKGROUND_COMPOSER_SOURCE_SDK")
    }

    @Test
    fun `a chat's place among Projects is derived the way the Agents Window derives it`() {
        fun composer(
            id: String = "bc-x",
            project: BackgroundComposerApi.ProjectMetadataDto? = null,
            manager: String? = null,
            sideChat: String? = null,
            subagentParent: String? = null,
        ) = BackgroundComposerApi.ComposerDto(
            bcId = id,
            projectMetadata = project,
            managerAgentId = manager,
            sideChatInfo = sideChat?.let { BackgroundComposerApi.SideChatInfoDto(parentBcId = it) },
            cloudSubagentParent = subagentParent?.let { BackgroundComposerApi.CloudSubagentParentDto(parentAgentId = it, parentToolCallId = "call-1") },
        )
        val appearance = BackgroundComposerApi.ProjectAppearanceDto(icon = "flag", colorId = "green")

        // `projectMetadata` makes a Project, empty or not; the appearance counts only whole.
        assertThat(BackgroundComposerApi.snapshot(composer(project = BackgroundComposerApi.ProjectMetadataDto()))).isEqualTo(ComposerSnapshot("bc-x", isProject = true))
        assertThat(BackgroundComposerApi.snapshot(composer(project = BackgroundComposerApi.ProjectMetadataDto(appearance)))!!.projectAppearance).isEqualTo(ProjectAppearance("flag", "green"))
        assertThat(BackgroundComposerApi.snapshot(composer(project = BackgroundComposerApi.ProjectMetadataDto(BackgroundComposerApi.ProjectAppearanceDto(icon = "flag"))))!!.projectAppearance).isNull()
        assertThat(BackgroundComposerApi.snapshot(composer())!!.isProject).isFalse()
        // A worker is never a Project itself, whatever metadata it carries.
        assertThat(BackgroundComposerApi.snapshot(composer(project = BackgroundComposerApi.ProjectMetadataDto(appearance), manager = "bc-m")))
            .isEqualTo(ComposerSnapshot("bc-x", parent = AgentParent("bc-m", AgentParentKind.PROJECT_WORKER)))
        // The parent is the first of: the agent that spawned it, the chat it branched off, the coordinator it works for.
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "bc-m", sideChat = "bc-s", subagentParent = "bc-p"))!!.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.SUBAGENT))
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "bc-m", sideChat = "bc-s"))!!.parent).isEqualTo(AgentParent("bc-s", AgentParentKind.SIDE_CHAT))
        assertThat(BackgroundComposerApi.snapshot(composer(manager = " bc-m "))!!.parent).isEqualTo(AgentParent("bc-m", AgentParentKind.PROJECT_WORKER))
        // Blank links and a record naming itself say nothing; a record without an id is not a snapshot.
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "  ", sideChat = "", subagentParent = "bc-x"))!!.parent).isNull()
        assertThat(BackgroundComposerApi.snapshot(composer(id = " "))).isNull()
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
    fun `archive and unarchive use ArchiveBackgroundComposer with the unarchive flag`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        api.archive("bc-1")
        api.unarchive("bc-1")

        server.takeRequest()
        val archive = server.takeRequest()
        assertThat(archive.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ArchiveBackgroundComposer")
        val archived = archive.json()
        assertThat(archived["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(archived["unarchive"]?.jsonPrimitive?.content).isEqualTo("false")
        val unarchive = server.takeRequest()
        assertThat(unarchive.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ArchiveBackgroundComposer")
        assertThat(unarchive.json()["unarchive"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test
    fun `rename sends bcId and newName`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))

        api.rename("bc-1", "Billing fix")

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/RenameBackgroundComposer")
        val body = request.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-1")
        assertThat(body["newName"]?.jsonPrimitive?.content).isEqualTo("Billing fix")
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
