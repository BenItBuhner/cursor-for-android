package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
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

/** The Project lineage reads of `aiserver.v1.BackgroundComposerService` over Connect JSON, against a fake api2 that also plays the token exchange. */
class ProjectApiTest {

    private val server = MockWebServer()
    private lateinit var api: ProjectApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ProjectApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the coordinator's workers come back with how each was spawned, by name or by number`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"memberships":[
                     {"workerBcId":"bc-w1","managerBcId":"bc-m","spawnKind":"MANAGER_SPAWN_KIND_CREATED","toolCallId":"call-1","status":"WORKER_MEMBERSHIP_STATUS_ACTIVE"},
                     {"workerBcId":"bc-w2","managerBcId":"bc-m","spawnKind":2},
                     {"workerBcId":"bc-w3","spawnKind":"MANAGER_SPAWN_KIND_HOLOGRAM"},
                     {"workerBcId":"  "}
                   ]}""",
            ),
        )

        val workers = api.workersForManager("bc-m")

        assertThat(workers).containsExactly(
            WorkerMembership("bc-w1", "bc-m", WorkerSpawnKind.CREATED, toolCallId = "call-1", status = "WORKER_MEMBERSHIP_STATUS_ACTIVE"),
            WorkerMembership("bc-w2", "bc-m", WorkerSpawnKind.ADOPTED),
            // The manager is the one asked about when the record leaves it out; a kind this build has not heard of is still one.
            WorkerMembership("bc-w3", "bc-m", WorkerSpawnKind.UNKNOWN),
        ).inOrder()
        server.takeRequest() // the exchange
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListWorkersForManager")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(request.json()["managerBcId"]?.jsonPrimitive?.content).isEqualTo("bc-m")
    }

    @Test
    fun `a chat's children are the side chats and subagents branched off it, read like the list's records`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(
            MockResponse().setBody(
                """{"composers":[
                     {"bcId":"bc-s","name":"Pricing copy","sideChatInfo":{"parentBcId":"bc-p","seedTurnCount":2},"source":"BACKGROUND_COMPOSER_SOURCE_AS_SIDE_CHAT_FROM_CLOUD"},
                     {"bcId":"bc-t","name":"Backfill","cloudSubagentParent":{"parentAgentId":"bc-p","parentToolCallId":"call-9"}},
                     {"bcId":""}
                   ]}""",
            ),
        )

        val children = api.children("bc-p")

        assertThat(children).containsExactly(
            ComposerSnapshot("bc-s", name = "Pricing copy", parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT), source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD),
            ComposerSnapshot("bc-t", name = "Backfill", parent = AgentParent("bc-p", AgentParentKind.SUBAGENT)),
        ).inOrder()
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListBackgroundComposerChildren")
        assertThat(request.json()["parentBcId"]?.jsonPrimitive?.content).isEqualTo("bc-p")
    }

    @Test
    fun `the lineage of a root folds both reads into who belongs to it`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"memberships":[{"workerBcId":"bc-w","managerBcId":"bc-m","spawnKind":"MANAGER_SPAWN_KIND_CREATED"}]}"""))
        server.enqueue(MockResponse().setBody("""{"composers":[{"bcId":"bc-s","sideChatInfo":{"parentBcId":"bc-m"}},{"bcId":"bc-w","managerAgentId":"bc-m"}]}"""))

        val lineage = api.lineage("bc-m")

        assertThat(lineage.rootId).isEqualTo("bc-m")
        assertThat(lineage.workers.map { it.workerId }).containsExactly("bc-w")
        assertThat(lineage.children).containsExactly("bc-s", AgentParentKind.SIDE_CHAT, "bc-w", AgentParentKind.PROJECT_WORKER)
        assertThat(lineage.members).containsExactly("bc-s", AgentParentKind.SIDE_CHAT, "bc-w", AgentParentKind.PROJECT_WORKER)
        assertThat(lineage.isEmpty).isFalse()
    }

    @Test
    fun `a new primary goes out as CreateProjectWorker with the launch request the desktop sends`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-new","name":"Webhooks","managerAgentId":"bc-m"},"initialRunId":"run-1"}"""))

        val created = api.createWorker("bc-m", WorkerLaunch(prompt = "Handle the webhooks", name = "Webhooks", repoUrl = "https://github.com/acme/app", baseBranch = "main", modelId = "claude-4", autoCreatePr = true, workerId = "bc-new"))

        assertThat(created).isEqualTo(ComposerSnapshot("bc-new", name = "Webhooks", parent = AgentParent("bc-m", AgentParentKind.PROJECT_WORKER)))
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/CreateProjectWorker")
        val body = request.json()
        assertThat(body["managerBcId"]?.jsonPrimitive?.content).isEqualTo("bc-m")
        assertThat(body["creationId"]?.jsonPrimitive?.content).isEqualTo("bc-new")
        val start = body["startRequest"]!!.jsonObject
        assertThat(start["prompt"]?.jsonPrimitive?.content).isEqualTo("Handle the webhooks")
        assertThat(start["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-new")
        assertThat(start["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(start["repoUrl"]?.jsonPrimitive?.content).isEqualTo("https://github.com/acme/app")
        assertThat(start["baseBranch"]?.jsonPrimitive?.content).isEqualTo("main")
        assertThat(start["autoCreatePr"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(start["returnImmediately"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(start["requestedModels"]?.jsonArray?.single()?.jsonObject?.get("modelId")?.jsonPrimitive?.content).isEqualTo("claude-4")
    }

    @Test
    fun `adopting, releasing, re-parenting and restyling send the desktop's requests`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"projectMetadata":{"appearance":{"icon":"flag","colorId":"green"}}}"""))

        api.setWorkerManager("bc-w", "bc-m")
        api.clearWorkerManager("bc-w")
        api.reparent("bc-w", "bc-p")
        val appearance = api.updateAppearance("bc-m", ProjectAppearance("flag", "green"))

        assertThat(appearance).isEqualTo(ProjectAppearance("flag", "green"))
        server.takeRequest()
        val adopt = server.takeRequest()
        assertThat(adopt.path).isEqualTo("/aiserver.v1.BackgroundComposerService/SetWorkerManager")
        assertThat(adopt.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("workerBcId", "bc-w", "managerBcId", "bc-m", "spawnKind", "MANAGER_SPAWN_KIND_ADOPTED")
        val release = server.takeRequest()
        assertThat(release.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ClearWorkerManager")
        assertThat(release.json()["workerBcId"]?.jsonPrimitive?.content).isEqualTo("bc-w")
        val reparent = server.takeRequest()
        assertThat(reparent.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ReparentBackgroundComposer")
        assertThat(reparent.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-w", "parentAgentId", "bc-p", "parentAgentType", "CLOUD_SUBAGENT_PARENT_AGENT_TYPE_CLOUD")
        val restyle = server.takeRequest()
        assertThat(restyle.path).isEqualTo("/aiserver.v1.BackgroundComposerService/UpdateProjectAppearance")
        val restyled = restyle.json()
        assertThat(restyled["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-m")
        assertThat(restyled["appearance"]?.jsonObject?.get("colorId")?.jsonPrimitive?.content).isEqualTo("green")
    }

    @Test
    fun `a side chat is started off its parent with a creation id, and comes back as the account's record`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"composer":{"bcId":"bc-s","name":"Pricing","sideChatInfo":{"parentBcId":"bc-m","seedTurnCount":4},"source":"BACKGROUND_COMPOSER_SOURCE_AS_SIDE_CHAT_FROM_CLOUD"}}"""))

        val side = api.startSideChat("bc-m", "Pricing")

        assertThat(side.id).isEqualTo("bc-s")
        assertThat(side.parent).isEqualTo(AgentParent("bc-m", AgentParentKind.SIDE_CHAT))
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartSideChatBackgroundComposer")
        val body = request.json()
        assertThat(body["parentBcId"]?.jsonPrimitive?.content).isEqualTo("bc-m")
        assertThat(body["name"]?.jsonPrimitive?.content).isEqualTo("Pricing")
        assertThat(body["creationSource"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body["creationId"]?.jsonPrimitive?.content).startsWith("sc-")
    }

    @Test
    fun `a steer is an injection into the running turn, and pause and resume name the chat`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"outcome":"OUTCOME_QUEUED_FOR_NEXT_TURN"}"""))
        server.enqueue(MockResponse().setBody("""{"outcome":3}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(api.steer("bc-w", "Use the v2 endpoint", "run-9")).isEqualTo(SteerOutcome.QUEUED_FOR_NEXT_TURN)
        assertThat(api.steer("bc-w", "Again", null)).isEqualTo(SteerOutcome.REJECTED)
        api.pause("bc-w", "run-9")
        api.resume("bc-w")

        server.takeRequest()
        val steer = server.takeRequest()
        assertThat(steer.path).isEqualTo("/aiserver.v1.BackgroundComposerService/InjectBackgroundComposerContext")
        val body = steer.json()
        assertThat(body["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-w")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        val action = body["injectContextAction"]!!.jsonObject
        assertThat(action["injectionId"]?.jsonPrimitive?.content).startsWith("inj-")
        assertThat(action["expectedRunId"]?.jsonPrimitive?.content).isEqualTo("run-9")
        val message = action["userContext"]!!.jsonObject["userMessage"]!!.jsonObject
        assertThat(message["text"]?.jsonPrimitive?.content).isEqualTo("Use the v2 endpoint")
        assertThat(message["messageId"]?.jsonPrimitive?.content).startsWith("msg-")
        assertThat(server.takeRequest().json()["injectContextAction"]!!.jsonObject.containsKey("expectedRunId")).isFalse()
        val pause = server.takeRequest()
        assertThat(pause.path).isEqualTo("/aiserver.v1.BackgroundComposerService/PauseBackgroundComposer")
        assertThat(pause.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-w", "source", "BACKGROUND_COMPOSER_SOURCE_API", "runId", "run-9")
        val resume = server.takeRequest()
        assertThat(resume.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ResumeBackgroundComposer")
        assertThat(resume.json()["bcId"]?.jsonPrimitive?.content).isEqualTo("bc-w")
    }

    @Test
    fun `the Project's context is the store its coordinator owns, listed entry by entry and read as text`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"stores":[{"storeId":"st-user","kind":"AGENT_STORE_KIND_USER","sourceId":"u-1"}],"hasMore":true,"nextPageToken":"p2"}"""))
        server.enqueue(MockResponse().setBody("""{"stores":[{"storeId":"st-proj","kind":"AGENT_STORE_KIND_CLOUD","source":{"kind":"AGENT_STORE_SOURCE_KIND_CLOUD","sourceId":"bc-m"}}],"hasMore":false}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"normalizedRelativePath":"","entries":[
                     {"name":"notes.md","relativePath":"notes.md","kind":"AGENT_STORE_ENTRY_KIND_FILE","sizeBytes":"1200","updatedAtMs":"1700000000000"},
                     {"name":"docs","relativePath":"docs","kind":1},
                     {"name":"plan.md","relativePath":"plan.md","kind":2,"sizeBytes":300}
                   ]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"content":"# Notes\nHello"}"""))

        assertThat(api.storeFor("bc-m")).isEqualTo("st-proj")
        val entries = api.entries("st-proj", "")
        assertThat(entries.map { it.relativePath }).containsExactly("docs", "notes.md", "plan.md").inOrder()
        assertThat(entries.first().isDirectory).isTrue()
        assertThat(entries[1].sizeBytes).isEqualTo(1200L)
        assertThat(entries[1].updatedAtMillis).isEqualTo(1_700_000_000_000L)
        assertThat(api.readFile("st-proj", "notes.md")).isEqualTo("# Notes\nHello")

        server.takeRequest()
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListAgentStores")
        assertThat(first.json()["n"]?.jsonPrimitive?.content).isEqualTo("50")
        assertThat(server.takeRequest().json()["pageToken"]?.jsonPrimitive?.content).isEqualTo("p2")
        val list = server.takeRequest()
        assertThat(list.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ListAgentStoreEntries")
        assertThat(list.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("storeId", "st-proj", "relativePath", "")
        val read = server.takeRequest()
        assertThat(read.path).isEqualTo("/aiserver.v1.BackgroundComposerService/ReadAgentStoreFile")
        assertThat(read.json()["relativePath"]?.jsonPrimitive?.content).isEqualTo("notes.md")
    }

    @Test
    fun `no store for the coordinator is no store, not the first one listed`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"stores":[{"storeId":"st-user","sourceId":"u-1"}],"hasMore":false}"""))

        assertThat(api.storeFor("bc-m")).isNull()
    }

    @Test
    fun `steer outcomes are read as the account spells them`() {
        assertThat(SteerOutcome.parse("OUTCOME_QUEUED")).isEqualTo(SteerOutcome.QUEUED)
        assertThat(SteerOutcome.parse("queued_for_next_turn")).isEqualTo(SteerOutcome.QUEUED_FOR_NEXT_TURN)
        assertThat(SteerOutcome.parse("2")).isEqualTo(SteerOutcome.QUEUED_FOR_NEXT_TURN)
        assertThat(SteerOutcome.parse("OUTCOME_UNSPECIFIED")).isEqualTo(SteerOutcome.UNKNOWN)
        assertThat(SteerOutcome.parse(null)).isEqualTo(SteerOutcome.UNKNOWN)
    }

    @Test
    fun `spawn kinds are read as the account spells them`() {
        assertThat(WorkerSpawnKind.parse("MANAGER_SPAWN_KIND_CREATED_SAME_VM")).isEqualTo(WorkerSpawnKind.CREATED_SAME_VM)
        assertThat(WorkerSpawnKind.parse("adopted")).isEqualTo(WorkerSpawnKind.ADOPTED)
        assertThat(WorkerSpawnKind.parse("1")).isEqualTo(WorkerSpawnKind.CREATED)
        assertThat(WorkerSpawnKind.parse("9")).isEqualTo(WorkerSpawnKind.UNKNOWN)
        assertThat(WorkerSpawnKind.parse(" ")).isNull()
        assertThat(WorkerSpawnKind.CREATED.wireName).isEqualTo("MANAGER_SPAWN_KIND_CREATED")
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
