package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.fixtures.MachineFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** `GetRepositoryBranches` on the wire, as the desktop's branch list asks it: the repository's URL and the first page. */
class RepositoryBranchesApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectRepositoryBranchesApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectRepositoryBranchesApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
        server.enqueue(MockResponse().setBody("""{"accessToken":"s","refreshToken":"rt"}"""))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the repository's branches come back by name, its default one marked`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(MachineFixtures.branches("codexPolyBot")))

        val branches = runBlocking { api.branches(MachineFixtures.CODEX_ORIGIN) }

        assertThat(branches).containsExactly(AccountBranch("main", true), AccountBranch("feature/accuracy", false), AccountBranch("fix/slippage", false)).inOrder()
        server.takeRequest()
        val asked = server.takeRequest()
        assertThat(asked.path).isEqualTo("/aiserver.v1.BackgroundComposerService/GetRepositoryBranches")
        assertThat(asked.body.readUtf8()).isEqualTo("""{"repoUrl":"${MachineFixtures.CODEX_ORIGIN}","page":1}""")
    }

    @Test
    fun `a repository the account cannot reach is its refusal`() {
        val refusal = MachineFixtures.noAccess
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(refusal["body"].toString()))

        val refused = assertThrows(ConnectRpcException::class.java) { runBlocking { api.branches(MachineFixtures.CODEX_GUESS) } }

        assertThat(refused.code).isEqualTo("invalid_argument")
    }
}
