package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The MCP list's rules, as the desktop's cloud rows apply them (`plusMenuMcpServerMapping.js`, `_toggleCloudMcpServer`). */
class McpConnectorTest {

    private val linear = McpConnector(7, "Linear", url = "https://mcp.linear.app/mcp", enabled = true)
    private val notion = McpConnector(8, "notion", url = "https://mcp.notion.com/mcp")
    private val stdio = McpConnector(9, "Playwright", transport = ConnectorTransport.Stdio, enabled = true)

    @Test
    fun `rows sort by name, case aside`() {
        assertThat(McpConnectors.sorted(listOf(stdio, notion, linear)).map { it.id }).containsExactly(7, 8, 9).inOrder()
    }

    @Test
    fun `a switch sends the whole enabled set with the one row changed`() {
        val rows = listOf(linear, notion, stdio)
        assertThat(McpConnectors.enabledIdsAfter(rows, 8, true)).containsExactly(7, 8, 9).inOrder()
        assertThat(McpConnectors.enabledIdsAfter(rows, 7, false)).containsExactly(9)
        assertThat(McpConnectors.enabledIdsAfter(listOf(linear), 7, false)).isEmpty()
    }

    @Test
    fun `only enabled HTTP servers with a URL are checked`() {
        assertThat(McpConnectors.needsStatus(linear)).isTrue()
        assertThat(McpConnectors.needsStatus(notion)).isFalse()
        assertThat(McpConnectors.needsStatus(stdio)).isFalse()
        assertThat(McpConnectors.needsStatus(linear.copy(url = " "))).isFalse()
    }

    @Test
    fun `a status maps as the desktop maps it - available, then sign-in, then error`() {
        val connected = McpConnectors.withStatus(linear, available = true, requiresAuth = true, authUrl = "https://x", error = "e")
        assertThat(connected.status).isEqualTo(ConnectorStatus.Connected)
        assertThat(connected.authUrl).isNull()

        val auth = McpConnectors.withStatus(linear, available = false, requiresAuth = true, authUrl = "https://linear.app/oauth?state=1", error = null)
        assertThat(auth.status).isEqualTo(ConnectorStatus.NeedsAuth)
        assertThat(auth.canConnect).isTrue()
        assertThat(auth.statusLabel).isEqualTo("Needs sign-in")

        val noUrl = McpConnectors.withStatus(linear, available = false, requiresAuth = true, authUrl = "", error = null)
        assertThat(noUrl.status).isEqualTo(ConnectorStatus.NeedsAuth)
        assertThat(noUrl.canConnect).isFalse()

        val failed = McpConnectors.withStatus(linear, available = false, requiresAuth = false, authUrl = null, error = " timed out ")
        assertThat(failed.status).isEqualTo(ConnectorStatus.Error)
        assertThat(failed.statusLabel).isEqualTo("Error: timed out")

        assertThat(McpConnectors.withStatus(linear, false, false, null, null).status).isEqualTo(ConnectorStatus.Unchecked)
    }

    @Test
    fun `labels say what the row is before and after it is checked`() {
        assertThat(notion.statusLabel).isEqualTo("Off")
        assertThat(notion.copy(team = true).statusLabel).isEqualTo("Team · Off")
        assertThat(linear.statusLabel).isEqualTo("On")
        assertThat(linear.copy(hasToken = true).statusLabel).isEqualTo("Connected")
        assertThat(linear.copy(status = ConnectorStatus.Checking).statusLabel).isEqualTo("Checking…")
        assertThat(stdio.statusLabel).isEqualTo("Runs in the agent's VM")
        assertThat(linear.copy(required = true, team = true).statusLabel).isEqualTo("Required by your team")
        assertThat(notion.copy(blockedByAdmin = true).statusLabel).isEqualTo("Turned off by your team")
    }

    @Test
    fun `required and admin-blocked rows cannot be switched, and only enabled rows connect`() {
        assertThat(linear.canToggle).isTrue()
        assertThat(linear.copy(required = true).canToggle).isFalse()
        assertThat(notion.copy(blockedByAdmin = true).canToggle).isFalse()
        assertThat(notion.copy(status = ConnectorStatus.NeedsAuth, authUrl = "https://x").canConnect).isFalse()
    }

    @Test
    fun `well-known connectors get their brand's glyph by name or host, the rest the MCP mark`() {
        assertThat(McpConnectors.glyph(linear)).isEqualTo("logo-linear")
        assertThat(McpConnectors.glyph(McpConnector(1, "Issues", url = "https://api.githubcopilot.com/mcp/"))).isEqualTo("logo-github")
        assertThat(McpConnectors.glyph(McpConnector(2, "Atlassian", url = "https://mcp.atlassian.com/v1/sse"))).isEqualTo("logo-jira")
        assertThat(McpConnectors.glyph(McpConnector(3, "ADO", url = "https://dev.azure.com/mcp"))).isEqualTo("logo-azure-devops")
        assertThat(McpConnectors.glyph(stdio)).isEqualTo("logo-mcp")
        assertThat(McpConnectors.glyph(McpConnector(4, "internal", url = "not a url"))).isEqualTo("logo-mcp")
    }
}
