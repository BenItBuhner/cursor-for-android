package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.fixtures.MachineFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chat started on one of the user's machines, on the wire, as the desktop starts one there (3.21.18: the New Agent
 * submit → `QPl` → `createAgent` → `_createAgentReal` → `startBackgroundComposerFromSnapshot`, the RPC a cloud start
 * uses too): the repository the account's own environment for it names (`nZS` → `D0t` → `_resolveCloudStartTarget`),
 * else the one picked, named `owner/name` on the starting point; then `use_private_worker`,
 * `selected_private_worker_id`, the `repo=` / `name=` / shared-assignment labels and the owner filter — compared field
 * for field with `fixtures/machines/machine_start_shapes.json`.
 */
class MachineStartApiTest {

    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var environments: MockResponse = ok(MachineFixtures.environments("none"))
    @Volatile private var startAnswer: (String) -> MockResponse = { bcId -> ok(MachineFixtures.response(bcId)) }
    private lateinit var api: ConnectAgentStartApi

    private val bennett = MachineWorker(MachineFixtures.BENNETT_WORKER, "bennett", "bennett/codex-poly-bot", MachineFixtures.OWNER)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                calls += path to body
                return when {
                    path == "/auth/exchange_user_api_key" -> ok("""{"accessToken":"s","refreshToken":"rt"}""")
                    path.endsWith("/ListEnvironments") -> environments
                    path.endsWith("/StartBackgroundComposerFromSnapshot") -> startAnswer(CursorJson.parseToJsonElement(body).jsonObject["bcId"]!!.jsonPrimitive.content)
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"unimplemented","message":"No such method"}""")
                }
            }
        }
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectAgentStartApi(
            ConnectJsonClient(client, base),
            SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
            noRepoEnvironment = { error("a machine's start never asks for the no-repo environment") },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun startOn(machine: MachineStart?, repoUrl: String = MachineFixtures.CODEX_GUESS, ref: String? = null): JsonObject = runBlocking {
        val record = api.start(StartRequest(agentId = "bc-m1", text = "Dig deep", repoUrl = repoUrl, ref = ref, machine = machine))
        assertThat(record.id).isEqualTo("bc-m1")
        startBody()
    }

    private fun startBody(): JsonObject =
        CursorJson.parseToJsonElement(calls.single { it.first.endsWith("/StartBackgroundComposerFromSnapshot") }.second).jsonObject

    private fun methods() = calls.map { it.first.substringAfterLast('/') }.filter { it != "exchange_user_api_key" }

    @Test
    fun `Bennett's machine without an account environment goes out as the desktop's, naming the repository owner-slash-name`() {
        val body = startOn(MachineStart("bennett", bennett, ownerUserId = 7))

        for ((key, expected) in MachineFixtures.start("bennett")) assertThat(body[key]).isEqualTo(expected)
        // The checkout's own branch: no base branch and no ref, as the desktop leaves them for a machine with no local workspace.
        assertThat(body.containsKey("baseBranch")).isFalse()
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject.keys).containsExactly("url", "environmentName")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject["text"]?.jsonPrimitive?.content).isEqualTo("Dig deep")
        // The account is asked for its environments first, the way `_fetchLogicalEnvironments` asks: without their JSON.
        assertThat(methods()).containsExactly("ListEnvironments", "StartBackgroundComposerFromSnapshot").inOrder()
        assertThat(calls.first { it.first.endsWith("/ListEnvironments") }.second).isEqualTo("""{"includeEnvironmentJson":false}""")
    }

    @Test
    fun `the account's environment for the machine's repository names it — its URL, its public id and its name`() {
        environments = ok(MachineFixtures.environments("withCodexPolyBot"))

        val body = startOn(MachineStart("bennett", bennett))

        // The multi-repository environment listed first is no machine's (`BSS`), so the single-repository one answers.
        for ((key, expected) in MachineFixtures.start("bennettEnvironment")) assertThat(body[key]).isEqualTo(expected)
        assertThat(body.containsKey("baseBranch")).isFalse()
    }

    /** The wire difference from what the account refused on Bennett's pick: every other field is the same. */
    @Test
    fun `against what PR 301 sent, only the starting point changes without an environment, and the repository with one`() {
        val before = MachineFixtures.sentByPr301("bennett")
        val fallback = startOn(MachineStart("bennett", bennett))
        assertThat(before.keys.filter { before[it] != fallback[it] }).containsExactly("devcontainerStartingPoint")

        calls.clear()
        environments = ok(MachineFixtures.environments("withCodexPolyBot"))
        val named = startOn(MachineStart("bennett", bennett))
        assertThat(before.keys.filter { before[it] != named[it] }).containsExactly("snapshotNameOrId", "devcontainerStartingPoint", "repoUrl")
    }

    @Test
    fun `an account that cannot list its environments still gets the start, with the repository as picked`() {
        environments = MockResponse().setResponseCode(503).setBody("""{"code":"unavailable","message":"try later"}""")

        val body = startOn(MachineStart("bennett", bennett))

        for ((key, expected) in MachineFixtures.start("bennett")) assertThat(body[key]).isEqualTo(expected)
    }

    @Test
    fun `a branch picked on the machine goes out as the base branch and the starting point's ref`() {
        val body = startOn(MachineStart("bennett", bennett), ref = "feature/accuracy")

        assertThat(body["baseBranch"]?.jsonPrimitive?.content).isEqualTo("feature/accuracy")
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject["ref"]?.jsonPrimitive?.content).isEqualTo("feature/accuracy")
        assertThat(body["labels"]).isEqualTo(MachineFixtures.start("bennett")["labels"])
    }

    /** `Mel`: a repository other than the one the worker registered goes by its label alone, and so names no machine and no owner. */
    @Test
    fun `a repository picked over the machine's own goes out by its label alone`() {
        val body = startOn(MachineStart("bennett", bennett), repoUrl = "https://github.com/acme/web")

        assertThat(body["labels"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content })
            .containsExactly("repo" to "acme/web")
        assertThat(body.containsKey("privateWorkerOwnerFilter")).isFalse()
        assertThat(body["selectedPrivateWorkerId"]?.jsonPrimitive?.content).isEqualTo(MachineFixtures.BENNETT_WORKER)
        assertThat(body["usePrivateWorker"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test
    fun `a machine no listing has named goes by its name and the account's own id`() {
        val body = startOn(MachineStart("bennett", worker = null, ownerUserId = MachineFixtures.OWNER))

        assertThat(body["labels"]).isEqualTo(MachineFixtures.start("bennett")["labels"])
        assertThat(body["privateWorkerOwnerFilter"]).isEqualTo(MachineFixtures.start("bennett")["privateWorkerOwnerFilter"])
        assertThat(body.containsKey("selectedPrivateWorkerId")).isFalse()
    }

    @Test
    fun `a cloud start asks for no environment and names none`() {
        environments = ok(MachineFixtures.environments("withCodexPolyBot"))

        val body = startOn(machine = null)

        assertThat(methods()).containsExactly("StartBackgroundComposerFromSnapshot")
        assertThat(body["devcontainerStartingPoint"]).isEqualTo(MachineFixtures.sentByPr301("bennett")["devcontainerStartingPoint"])
        assertThat(body.containsKey("usePrivateWorker")).isFalse()
    }

    @Test
    fun `the account's refusal comes back with its words and the path it was asked on`() {
        for (refusal in listOf(MachineFixtures.offline, MachineFixtures.noAccess)) {
            startAnswer = { MockResponse().setResponseCode(refusal["status"]!!.jsonPrimitive.content.toInt()).setHeader("Content-Type", "application/json").setBody(refusal["body"].toString()) }

            val refused = assertThrows(ConnectRpcException::class.java) {
                runBlocking { api.start(StartRequest(agentId = "bc-m2", text = "go", repoUrl = "https://github.com/acme/payments-service", machine = MachineStart("studio", MachineWorker(MachineFixtures.STUDIO_WORKER, "studio", "acme/payments-service", 42)))) }
            }
            val body = refusal["body"]!!.jsonObject
            assertThat(refused.httpCode).isEqualTo(400)
            assertThat(refused.code).isEqualTo(body["code"]!!.jsonPrimitive.content)
            assertThat(refused.message).isEqualTo(body["message"]!!.jsonPrimitive.content)
            assertThat(refused.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot")
        }
    }

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
