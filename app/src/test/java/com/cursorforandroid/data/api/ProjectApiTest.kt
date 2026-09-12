package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
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
