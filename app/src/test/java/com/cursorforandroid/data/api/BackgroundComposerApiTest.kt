package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentScope
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
                   ],"didLoadStatus":true,"hasMore":false,"pinnedBcIds":["bc-1","bc-9"],"didLoadPinnedState":true}""",
            ),
        )

        val list = api.list()

        assertThat(list.pinned).isEqualTo(PinnedIds(setOf("bc-1", "bc-9"), loaded = true))
        // The record's raw fields ride along on every snapshot (asserted apart); the rest is compared whole.
        assertThat(list.composers.map { it.copy(record = null) }).containsAtLeast(
            ComposerSnapshot("bc-1", name = "x", archived = false, source = AgentSource.WEBSITE),
            ComposerSnapshot("bc-7", name = "no pr", archived = true),
            ComposerSnapshot("bc-8", name = "Billing launch", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple")),
            ComposerSnapshot("bc-10", name = "Webhook worker", parent = AgentParent("bc-8", AgentParentKind.PROJECT_WORKER), source = AgentSource.WEBSITE),
            ComposerSnapshot("bc-11", name = "Pricing side chat", parent = AgentParent("bc-8", AgentParentKind.SIDE_CHAT)),
        )
        assertThat(list.composers.first { it.id == "bc-8" }.record).isEqualTo(RecordFields(projectMetadata = """{"appearance":{"icon":"rocket","colorId":"purple"}}"""))
        assertThat(list.composers.first { it.id == "bc-10" }.record?.managerAgentId).isEqualTo("bc-8")
        assertThat(list.composers.first { it.id == "bc-11" }.record?.sideChatParentId).isEqualTo("bc-8")
        // Where each belongs follows from its record alone.
        val scopes = list.composers.associate { it.id to it.scope }
        assertThat(scopes["bc-8"]).isEqualTo(AgentScope.PROJECT_ROOT)
        assertThat(scopes["bc-10"]).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(scopes["bc-11"]).isEqualTo(AgentScope.PROJECT_CHILD)
        assertThat(scopes["bc-1"]).isEqualTo(AgentScope.PRIMARY)
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
        // SDK agents are left out of the list unless asked for, as cursor.com/agents leaves them out until its Source filter
        // says SDK; so are the chats Cursor starts for another chat, whose records say whose they are.
        assertThat(body["includeHiddenSources"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly(
            "BACKGROUND_COMPOSER_SOURCE_SDK",
            "BACKGROUND_COMPOSER_SOURCE_AS_SIDE_CHAT_FROM_CLOUD",
            "BACKGROUND_COMPOSER_SOURCE_AS_SUBAGENT_FROM_CLOUD",
            "BACKGROUND_COMPOSER_SOURCE_CLOUD_META_AGENT",
        ).inOrder()
        // The workers' and subagents' records, which the Agents Window never asks for: they carry the lineage.
        assertThat(body["includeWorkers"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["includeSubagents"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test
    fun `a chat's place among Projects is derived the way the Agents Window derives it`() {
        fun json(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun composer(
            id: String = "bc-x",
            project: String? = null,
            manager: String? = null,
            sideChat: String? = null,
            subagentParent: String? = null,
            newProject: Boolean? = null,
        ) = BackgroundComposerApi.ComposerDto(
            bcId = id,
            projectMetadata = project?.let(::json),
            managerAgentId = manager,
            sideChatInfo = sideChat?.let { BackgroundComposerApi.SideChatInfoDto(parentBcId = it) },
            cloudSubagentParent = subagentParent?.let { BackgroundComposerApi.CloudSubagentParentDto(parentAgentId = it, parentToolCallId = "call-1") },
            startedAsNewProject = newProject,
        )
        val appearance = """{"appearance":{"icon":"flag","colorId":"green"}}"""

        // The desktop's predicate (Cursor 3.20.21 workbench.glass.main.js, CloudAgentRepository): `isProject` is
        // `project_metadata` present at all — `{}` included — and a root is that with no subagent parent, side-chat
        // parent or manager. `startedAsNewProject` is carried and never read for it (Bennett's pinned "Market
        // Opportunities", 0.3.7, was promoted on it); the appearance is read apart and is no part of the flag.
        val empty = BackgroundComposerApi.snapshot(composer(project = "{}"))!!
        assertThat(empty.isProject).isTrue()
        assertThat(empty.projectAppearance).isNull()
        assertThat(empty.record).isEqualTo(RecordFields(projectMetadata = "{}"))
        val newFlag = BackgroundComposerApi.snapshot(composer(newProject = true))!!
        assertThat(newFlag.isProject).isFalse()
        assertThat(newFlag.record).isEqualTo(RecordFields(startedAsNewProject = true))
        val flagged = BackgroundComposerApi.snapshot(composer(project = appearance))!!
        assertThat(flagged.isProject).isTrue()
        assertThat(flagged.projectAppearance).isEqualTo(ProjectAppearance("flag", "green"))
        assertThat(flagged.record?.projectMetadata).isEqualTo(appearance)
        // Half an appearance is no appearance (the desktop's `wQp`); the flag stands all the same.
        val half = BackgroundComposerApi.snapshot(composer(project = """{"appearance":{"icon":"flag"}}"""))!!
        assertThat(half.projectAppearance).isNull()
        assertThat(half.isProject).isTrue()
        assertThat(BackgroundComposerApi.snapshot(composer())!!.isProject).isFalse()
        assertThat(BackgroundComposerApi.snapshot(composer())!!.record).isEqualTo(RecordFields())
        // A worker is never a Project itself, whatever metadata it carries (the desktop's `subagentParentId`).
        val worker = BackgroundComposerApi.snapshot(composer(project = appearance, manager = "bc-m"))!!
        assertThat(worker.isProject).isFalse()
        assertThat(worker.parent).isEqualTo(AgentParent("bc-m", AgentParentKind.PROJECT_WORKER))
        assertThat(worker.record).isEqualTo(RecordFields(projectMetadata = appearance, managerAgentId = "bc-m"))
        assertThat(worker.record?.desktopSubagentParentId).isEqualTo("bc-m")
        assertThat(BackgroundComposerApi.snapshot(composer(project = appearance, sideChat = "bc-s"))!!.isProject).isFalse()
        assertThat(BackgroundComposerApi.snapshot(composer(project = appearance, subagentParent = "bc-p"))!!.isProject).isFalse()
        // The parent is the first of: the agent that spawned it, the chat it branched off, the coordinator it works for.
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "bc-m", sideChat = "bc-s", subagentParent = "bc-p"))!!.parent).isEqualTo(AgentParent("bc-p", AgentParentKind.SUBAGENT))
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "bc-m", sideChat = "bc-s"))!!.parent).isEqualTo(AgentParent("bc-s", AgentParentKind.SIDE_CHAT))
        assertThat(BackgroundComposerApi.snapshot(composer(manager = " bc-m "))!!.parent).isEqualTo(AgentParent("bc-m", AgentParentKind.PROJECT_WORKER))
        // Blank links and a record naming itself say nothing; a record without an id is not a snapshot.
        assertThat(BackgroundComposerApi.snapshot(composer(manager = "  ", sideChat = "", subagentParent = "bc-x"))!!.parent).isNull()
        assertThat(BackgroundComposerApi.snapshot(composer(id = " "))).isNull()
    }

    /**
     * The root discovery pass reads the list to its end: by token where the service gives one, by the page's oldest
     * activity where it does not (the older service's `last_message_activity_at_ms_offset`), and a page that fails
     * ends the pass with what the pages before it said, not with nothing. A page that brings nothing new is the end.
     */
    @Test
    fun `the discovery pass pages by token or by activity, and keeps what it read when a page fails`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r1","projectMetadata":{"appearance":{"icon":"lightning","colorId":"default"}},"lastMessageActivityAtMs":"1700000009000"},{"bcId":"bc-w1","managerAgentId":"bc-r1","lastMessageActivityAtMs":"1700000008000"}],"hasMore":true,"nextPageToken":"page-2"}"""))
        // A meta-agent source makes no root; the flag does. bc-m is a record among the pages, and nothing else.
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r2","projectMetadata":{"appearance":{"icon":"rocket","colorId":"blue"}},"lastMessageActivityAtMs":"1700000007000"},{"bcId":"bc-m","source":"BACKGROUND_COMPOSER_SOURCE_CLOUD_META_AGENT","lastMessageActivityAtMs":"1700000006500"},{"bcId":"bc-x","lastMessageActivityAtMs":"1700000006000"}],"hasMore":true}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val partial = api.scanRoots(maxPages = 10)
        assertThat(partial.roots.map { it.id }).containsExactly("bc-r1", "bc-r2").inOrder()
        assertThat(partial.children.map { it.id }).containsExactly("bc-w1")
        assertThat(partial.pagesRead).isEqualTo(2)
        assertThat(partial.records).isEqualTo(5)
        assertThat(partial.seenIds).containsExactly("bc-r1", "bc-w1", "bc-r2", "bc-m", "bc-x")
        assertThat(partial.complete).isFalse()
        assertThat(partial.failure).startsWith("page 3:")
        server.takeRequest() // the exchange
        val first = server.takeRequest().json()
        assertThat(first["usePageTokens"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(first["pageToken"]).isNull()
        val second = server.takeRequest().json()
        assertThat(second["pageToken"]?.jsonPrimitive?.content).isEqualTo("page-2")
        // No token came back with the second page: the third is asked for by the page's oldest activity.
        val third = server.takeRequest().json()
        assertThat(third["pageToken"]).isNull()
        assertThat(third["lastMessageActivityAtMsOffset"]?.jsonPrimitive?.content).isEqualTo("1700000006000")

        // The pass again: to the end this time, a repeated page being the end whatever the flag says.
        // `projectMetadata: {}` is a root by the desktop's predicate; `startedAsNewProject` alone is not.
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r1","projectMetadata":{"appearance":{"icon":"lightning","colorId":"default"}}},{"bcId":"bc-e","projectMetadata":{}}],"hasMore":true,"nextPageToken":"p2"}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r3","projectMetadata":{"appearance":{"icon":"flag","colorId":"green"}}},{"bcId":"bc-n","startedAsNewProject":true}],"hasMore":true,"nextPageToken":"p3"}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r3","projectMetadata":{"appearance":{"icon":"flag","colorId":"green"}}}],"hasMore":true,"nextPageToken":"p4"}"""))
        val whole = api.scanRoots(maxPages = 10)
        // `project_metadata: {}` is the desktop's flag: bc-e is a root; `startedAsNewProject` alone is not: bc-n is none.
        assertThat(whole.roots.map { it.id }).containsExactly("bc-r1", "bc-e", "bc-r3").inOrder()
        assertThat(whole.seenIds).containsExactly("bc-r1", "bc-e", "bc-r3", "bc-n")
        assertThat(whole.complete).isTrue()
        assertThat(whole.failure).isNull()
        assertThat(whole.pagesRead).isEqualTo(3)

        // A pass that read as many pages as it may is not a failure: the next one reads again.
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-r1","projectMetadata":{"appearance":{"icon":"lightning","colorId":"default"}}}],"hasMore":true,"nextPageToken":"p2"}"""))
        val capped = api.scanRoots(maxPages = 1)
        assertThat(capped.truncated).isTrue()
        assertThat(capped.complete).isFalse()
        assertThat(capped.failure).isNull()
    }

    @Test
    fun `a list with more behind it hands back the service's cursor, and the pages behind it are read on demand`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        // The first page pages by token; the second by the activity offset the older service answers with; the
        // third says there is more still.
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-1"},{"bcId":"bc-2","managerAgentId":"bc-1"}],"hasMore":true,"nextPageToken":"page-2","pinnedBcIds":["bc-1"],"didLoadPinnedState":true}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-3","managerAgentId":"bc-1"},{"bcId":"bc-2"}],"hasMore":true,"nextPageOffset":"1700000000000"}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-4","sideChatInfo":{"parentBcId":"bc-3"}}],"hasMore":true,"nextPageToken":"page-4"}"""))

        val first = api.list()
        // One page, its pins, and where the next begins; nothing read that nobody has scrolled to.
        assertThat(first.composers.map { it.id }).containsExactly("bc-1", "bc-2").inOrder()
        assertThat(first.composers.first { it.id == "bc-2" }.parent).isEqualTo(AgentParent("bc-1", AgentParentKind.PROJECT_WORKER))
        assertThat(first.pinned).isEqualTo(PinnedIds(setOf("bc-1"), loaded = true))
        assertThat(first.nextCursor).isNotNull()
        assertThat(server.requestCount).isEqualTo(2)

        val second = api.listMore(first.nextCursor!!)
        assertThat(second.composers.map { it.id }).containsExactly("bc-3", "bc-2").inOrder()
        assertThat(second.pinned.loaded).isFalse()
        assertThat(second.nextCursor).isNotNull()

        val third = api.listMore(second.nextCursor!!)
        assertThat(third.composers.single().parent).isEqualTo(AgentParent("bc-3", AgentParentKind.SIDE_CHAT))
        assertThat(third.nextCursor).isNotNull()

        server.takeRequest() // the exchange
        val firstRequest = server.takeRequest().json()
        assertThat(firstRequest["pageToken"]).isNull()
        // Tokens are asked for from the first page: the service names the next page's token only when asked.
        assertThat(firstRequest["usePageTokens"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(firstRequest["lastMessageActivityAtMsOffset"]).isNull()
        assertThat(firstRequest["includePinnedState"]?.jsonPrimitive?.content).isEqualTo("true")
        val secondRequest = server.takeRequest().json()
        assertThat(secondRequest["pageToken"]?.jsonPrimitive?.content).isEqualTo("page-2")
        assertThat(secondRequest["usePageTokens"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(secondRequest["includePinnedState"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(secondRequest["includeWorkers"]?.jsonPrimitive?.content).isEqualTo("true")
        val thirdRequest = server.takeRequest().json()
        assertThat(thirdRequest["lastMessageActivityAtMsOffset"]?.jsonPrimitive?.content).isEqualTo("1700000000000")
        assertThat(thirdRequest["pageToken"]).isNull()
        assertThat(server.requestCount).isEqualTo(4)
    }

    /** The last page names no cursor, and a page that says "more" but carries nothing ends the paging too. */
    @Test
    fun `the last page hands back no cursor`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-1"}],"hasMore":false,"nextPageToken":"ignored"}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[],"hasMore":true,"nextPageToken":"page-2"}"""))

        assertThat(api.list().nextCursor).isNull()
        assertThat(api.list().nextCursor).isNull()
    }

    @Test
    fun `one chat's record is read by id, for a Project the windowed list did not reach`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-8","name":"Billing launch","projectMetadata":{"appearance":{"icon":"rocket","colorId":"purple"}}}],"hasMore":false}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-other"}],"hasMore":false}"""))

        assertThat(api.record("bc-8")?.copy(record = null)).isEqualTo(ComposerSnapshot("bc-8", name = "Billing launch", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple")))
        // An answer about another chat is no record of this one.
        assertThat(api.record("bc-9")).isNull()
        server.takeRequest() // the exchange
        val body = server.takeRequest().json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-8")
        assertThat(body["n"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(body["includeArchived"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["includeWorkers"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(body["includePinnedState"]?.jsonPrimitive?.content).isEqualTo("false")
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
