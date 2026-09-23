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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * A chat started on one of the user's machines, on the wire, as the desktop starts one there (3.21.18: the New Agent
 * submit → `QPl` → `createAgent` → `_createAgentReal` → `startBackgroundComposerFromSnapshot`): the ordinary start plus
 * `use_private_worker`, `selected_private_worker_id`, the `repo=` / `name=` / shared-assignment labels and the owner
 * filter, compared field for field with `fixtures/machines/machine_start_shapes.json`.
 */
class MachineStartApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectAgentStartApi

    private val bennett = MachineWorker(MachineFixtures.BENNETT_WORKER, "bennett", "bennett/codex-poly-bot", MachineFixtures.OWNER)

    @Before
    fun setUp() {
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

    private fun startOn(machine: MachineStart, repoUrl: String = "https://github.com/bennett/codex-poly-bot", ref: String? = null): JsonObject = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setBody(MachineFixtures.response("bc-m1")))
        val record = api.start(StartRequest(agentId = "bc-m1", text = "Dig deep", repoUrl = repoUrl, ref = ref, machine = machine))
        assertThat(record.id).isEqualTo("bc-m1")
        server.takeRequest()
        server.takeRequest().also { assertThat(it.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot") }.json()
    }

    @Test
    fun `Bennett's machine goes out the desktop's way, naming the machine, its worker and its owner`() {
        val body = startOn(MachineStart("bennett", bennett, ownerUserId = 7))

        for ((key, expected) in MachineFixtures.start("bennett")) assertThat(body[key]).isEqualTo(expected)
        // The checkout's own branch: no base branch, no ref on the starting point, and no environment name either.
        assertThat(body.containsKey("baseBranch")).isFalse()
        assertThat(body["devcontainerStartingPoint"]!!.jsonObject.keys).containsExactly("url")
        assertThat(body["source"]?.jsonPrimitive?.content).isEqualTo("BACKGROUND_COMPOSER_SOURCE_API")
        assertThat(body["conversationAction"]!!.jsonObject["userMessageAction"]!!.jsonObject["userMessage"]!!.jsonObject["text"]?.jsonPrimitive?.content).isEqualTo("Dig deep")
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
    fun `the account's refusal comes back with its words and the path it was asked on`() {
        val offline = MachineFixtures.offline
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
        server.enqueue(MockResponse().setResponseCode(offline["status"]!!.jsonPrimitive.content.toInt()).setHeader("Content-Type", "application/json").setBody(offline["body"].toString()))

        val refused = assertThrows(ConnectRpcException::class.java) {
            runBlocking { api.start(StartRequest(agentId = "bc-m2", text = "go", repoUrl = "https://github.com/acme/payments-service", machine = MachineStart("studio", MachineWorker(MachineFixtures.STUDIO_WORKER, "studio", "acme/payments-service", 42)))) }
        }
        assertThat(refused.httpCode).isEqualTo(400)
        assertThat(refused.code).isEqualTo("failed_precondition")
        assertThat(refused.message).isEqualTo("The requested self-hosted worker is not connected.")
        assertThat(refused.path).isEqualTo("/aiserver.v1.BackgroundComposerService/StartBackgroundComposerFromSnapshot")
    }

    private fun RecordedRequest.json() = CursorJson.parseToJsonElement(body.readUtf8()).jsonObject
}
