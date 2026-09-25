package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ConnectorTransport
import com.cursorforandroid.domain.McpConnector
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The `DashboardService` MCP RPCs over Connect JSON, as the desktop Agents window's cloud MCP rows make them. */
class DashboardMcpConnectorApiTest {

    private val server = MockWebServer()
    private lateinit var api: DashboardMcpConnectorApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = DashboardMcpConnectorApi(ConnectJsonClient(client, base), SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }))
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-1","refreshToken":"rt"}"""))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the list maps McpServerInfo onto rows, int64 plugin ids and all`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"servers":[
                  {"id":7,"name":"Linear","isTeamServer":false,"enabled":true,"type":"streamableHttp","url":"https://mcp.linear.app/mcp","userHasAccessToken":true,"servedBy":"MCP_SERVED_BY_CURSOR"},
                  {"id":9,"name":"Sentry","isTeamServer":true,"enabled":false,"type":"http","url":"https://mcp.sentry.dev/mcp","pluginId":"123456789012","isRequired":true,"logoUrl":"https://cdn.example/sentry.png"},
                  {"id":11,"name":"playwright","enabled":true,"type":"stdio","command":"npx","args":["@playwright/mcp"],"disabledByTeamAdminPolicy":true},
                  {"id":0,"name":"broken"},
                  {"id":12,"name":"  "}
                ]}""",
            ),
        )

        val rows = api.list()

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetAvailableMcpServers")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(request.body.readUtf8()).isEqualTo("{}")
        assertThat(rows).containsExactly(
            McpConnector(7, "Linear", url = "https://mcp.linear.app/mcp", enabled = true, hasToken = true),
            McpConnector(9, "Sentry", url = "https://mcp.sentry.dev/mcp", team = true, required = true, logoUrl = "https://cdn.example/sentry.png", pluginId = "123456789012"),
            McpConnector(11, "playwright", transport = ConnectorTransport.Stdio, enabled = true, blockedByAdmin = true),
        ).inOrder()
    }

    @Test
    fun `switching sends the whole enabled set and takes an empty answer`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(""))

        api.setEnabled(listOf(7, 11))

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/UpdateUserDefaultMcpSettings")
        assertThat(request.body.readUtf8()).isEqualTo("""{"enabledServerIds":[7,11]}""")
    }

    @Test
    fun `switching everything off still sends the empty set`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))

        api.setEnabled(emptyList())

        server.takeRequest()
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"enabledServerIds":[]}""")
    }

    @Test
    fun `statuses are asked for with cursor-com's OAuth callback and come back per server`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"statuses":[
                  {"id":7,"isAvailable":true,"requiresAuth":false,"hasValidToken":true},
                  {"id":9,"isAvailable":false,"requiresAuth":true,"authUrl":"https://mcp.sentry.dev/oauth/authorize?state=s1"},
                  {"id":10,"error":"Server did not respond"}
                ]}""",
            ),
        )

        val reports = api.statuses(listOf(7, 9, 10))

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/CheckHttpMcpStatus")
        assertThat(request.body.readUtf8()).isEqualTo("""{"serverIds":[7,9,10],"oauthRedirectUri":"https://www.cursor.com/agents/mcp/oauth/callback"}""")
        assertThat(reports).containsExactly(
            ConnectorStatusReport(7, available = true, requiresAuth = false, authUrl = null, error = null),
            ConnectorStatusReport(9, available = false, requiresAuth = true, authUrl = "https://mcp.sentry.dev/oauth/authorize?state=s1", error = null),
            ConnectorStatusReport(10, available = false, requiresAuth = false, authUrl = null, error = "Server did not respond"),
        ).inOrder()
    }

    @Test
    fun `no ids asks for nothing`() = runBlocking<Unit> {
        assertThat(api.statuses(emptyList())).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `plugin logos prefer the publisher's brand over the plugin's own`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"snapshotToken":"t","plugins":[
                  {"effectivePlugin":{"plugin":{"id":"42","logoUrl":"https://cdn/p42.png","publisher":{"logoUrl":"https://cdn/brand.png"}},"isEnabled":true}},
                  {"effectivePlugin":{"plugin":{"id":"43","logoUrl":"https://cdn/p43.png"}}},
                  {"effectivePlugin":{"plugin":{"id":"44"}}},
                  {}
                ]}""",
            ),
        )

        val logos = api.pluginLogos()

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.DashboardService/GetCloudAgentPluginsSnapshot")
        assertThat(request.body.readUtf8()).isEqualTo("""{"useReplica":true}""")
        assertThat(logos).containsExactly("42", "https://cdn/brand.png", "43", "https://cdn/p43.png")
    }
}
