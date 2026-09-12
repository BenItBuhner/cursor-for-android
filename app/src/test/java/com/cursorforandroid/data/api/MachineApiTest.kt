package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.MachineReference
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

/** `GetMachine` over Connect JSON: the pod behind a chat and the URLs its desktop is reached at, read loosely. */
class MachineApiTest {

    private val server = MockWebServer()
    private lateinit var api: MachineApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = MachineApi(
            rpc = ConnectJsonClient(client, base),
            tokens = SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a pod comes back with its network token, and its desktop URLs are built for both ports in order`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"machine":{"pod":{"podId":"pod-1","tenantId":"t-9","networkToken":"tok.abc","cluster":"us-east1","execDaemonAuthToken":"x"}}}"""))

        val pod = api.machine("bc-1") as MachineReference.Pod

        assertThat(pod.podId).isEqualTo("pod-1")
        assertThat(pod.tenantId).isEqualTo("t-9")
        assertThat(pod.cluster).isEqualTo("us-east1")
        assertThat(pod.token).isEqualTo("tok.abc")
        assertThat(pod.ticketMinted).isFalse()
        assertThat(pod.candidateUrls()).containsExactly(
            "wss://t-9-pod-1-6080.us-east1.cursorvm.com:443/websockify?network_token=tok.abc&resume_lower_s=900&resume_upper_s=18000",
            "wss://t-9-pod-1-26058.us-east1.cursorvm.com:443/websockify?network_token=tok.abc&resume_lower_s=900&resume_upper_s=18000",
        ).inOrder()
        // The token never reaches a log line.
        assertThat(pod.toString()).doesNotContain("tok.abc")
        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetMachine")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer s")
        assertThat(request.json().mapValues { it.value.jsonPrimitive.content }).containsExactly("bcId", "bc-1", "mintDesktopTicket", "true")
    }

    @Test
    fun `a minted desktop ticket, under whatever name the answer gives it, takes the token's place`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"machine":{"pod":{"pod_id":"pod-1","tenant_id":"t-9","network_token":"standing","cluster":"eu","desktopTicket":"ticket.xyz"}}}"""))

        val pod = api.machine("bc-1") as MachineReference.Pod

        assertThat(pod.token).isEqualTo("ticket.xyz")
        assertThat(pod.ticketMinted).isTrue()
        assertThat(pod.candidateUrls().first()).contains("network_token=ticket.xyz")
    }

    @Test
    fun `a server that rejects the ticket flag is asked once more without it`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"invalid_argument","message":"unknown field mintDesktopTicket"}"""))
        server.enqueue(MockResponse().setBody("""{"machine":{"pod":{"podId":"pod-1","tenantId":"t-9","networkToken":"tok","cluster":"eu"}}}"""))

        val pod = api.machine("bc-1") as MachineReference.Pod

        assertThat(pod.token).isEqualTo("tok")
        server.takeRequest()
        assertThat(server.takeRequest().json().keys).containsExactly("bcId", "mintDesktopTicket")
        assertThat(server.takeRequest().json().keys).containsExactly("bcId")
    }

    @Test
    fun `a worker machine is named as such, with its desktop's own word on availability`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("""{"machine":{"worker":{"workerId":"w-1","workspaceRootPath":"/home/me/app","desktop":{"available":false,"unavailableReason":"desktop sharing is off"}}}}"""))
        server.enqueue(MockResponse().setBody("""{"machine":{"worker":{"workerId":"w-2","desktop":{"available":true,"wsUrl":"wss://relay","controlAllowed":true}}}}"""))
        server.enqueue(MockResponse().setBody("""{"machine":{"worker":{"workerId":"w-3"}}}"""))

        val off = api.machine("bc-1") as MachineReference.Worker
        assertThat(off.workerId).isEqualTo("w-1")
        assertThat(off.workspaceRootPath).isEqualTo("/home/me/app")
        assertThat(off.available).isFalse()
        assertThat(off.unavailableReason).isEqualTo("desktop sharing is off")
        val on = api.machine("bc-1") as MachineReference.Worker
        assertThat(on.available).isTrue()
        assertThat(on.controlAllowed).isTrue()
        val bare = api.machine("bc-1") as MachineReference.Worker
        assertThat(bare.available).isFalse()
        assertThat(bare.unavailableReason).isNull()
    }

    @Test
    fun `no machine, or a pod missing its coordinates, is a refusal rather than a broken URL`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"machine":{"pod":{"podId":"pod-1"}}}"""))

        assertThat(runCatching { api.machine("bc-1") }.exceptionOrNull()).isInstanceOf(ConnectRpcException::class.java)
        assertThat(runCatching { api.machine("bc-1") }.exceptionOrNull()).isInstanceOf(ConnectRpcException::class.java)
    }

    @Test
    fun `any other refusal is passed through as the Connect error it was`() = runBlocking<Unit> {
        server.enqueue(session("s"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"unimplemented","message":"Error"}"""))

        val error = runCatching { api.machine("bc-1") }.exceptionOrNull() as ConnectRpcException
        assertThat(error.httpCode).isEqualTo(404)
        assertThat(error.code).isEqualTo("unimplemented")
        // One call: a 404 is not an argument problem, so there is no retry without the flag.
        server.takeRequest()
        server.takeRequest()
        assertThat(server.requestCount).isEqualTo(2)
    }

    private fun session(token: String) = MockResponse().setBody("""{"accessToken":"$token","refreshToken":"rt"}""")

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
}
