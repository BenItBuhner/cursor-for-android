package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.ConnectorStatusReport
import com.cursorforandroid.data.api.McpConnectorApi
import com.cursorforandroid.domain.ConnectorTransport
import com.cursorforandroid.domain.McpConnector

/**
 * The demo's MCP list: a few well-known connectors in each state the page can show, switched in memory. Nothing
 * here reaches a network; a sign-in URL points at the connector's public docs so "Connect" has somewhere to go.
 */
class DemoMcpConnectorApi(initial: List<McpConnector> = CONNECTORS) : McpConnectorApi {
    private val lock = Any()
    private var enabled: Set<Int> = initial.filter { it.enabled }.map { it.id }.toSet()
    private val all = initial

    override suspend fun list(): List<McpConnector> = synchronized(lock) { all.map { it.copy(enabled = it.id in enabled) } }

    override suspend fun setEnabled(enabledIds: List<Int>) = synchronized(lock) { enabled = enabledIds.toSet() }

    override suspend fun statuses(ids: List<Int>): List<ConnectorStatusReport> = ids.mapNotNull { id ->
        when (id) {
            GITHUB, SENTRY -> ConnectorStatusReport(id, available = true, requiresAuth = false, authUrl = null, error = null)
            LINEAR -> ConnectorStatusReport(id, available = false, requiresAuth = true, authUrl = "https://linear.app/docs/mcp", error = null)
            NOTION -> ConnectorStatusReport(id, available = false, requiresAuth = true, authUrl = "https://developers.notion.com/docs/mcp", error = null)
            FIGMA -> ConnectorStatusReport(id, available = false, requiresAuth = false, authUrl = null, error = "Server did not respond")
            else -> null
        }
    }

    override suspend fun pluginLogos(): Map<String, String> = emptyMap()

    companion object {
        const val GITHUB = 101
        const val LINEAR = 102
        const val NOTION = 103
        const val SENTRY = 104
        const val SLACK = 105
        const val FIGMA = 106
        const val PLAYWRIGHT = 107

        val CONNECTORS = listOf(
            McpConnector(GITHUB, "GitHub", url = "https://api.githubcopilot.com/mcp/", enabled = true, hasToken = true),
            McpConnector(LINEAR, "Linear", url = "https://mcp.linear.app/mcp", enabled = true),
            McpConnector(NOTION, "Notion", url = "https://mcp.notion.com/mcp", enabled = false),
            McpConnector(SENTRY, "Sentry", url = "https://mcp.sentry.dev/mcp", enabled = true, team = true, required = true),
            McpConnector(SLACK, "Slack", url = "https://mcp.slack.com/mcp", enabled = false, team = true, blockedByAdmin = true),
            McpConnector(FIGMA, "Figma", url = "https://mcp.figma.com/mcp", enabled = true),
            McpConnector(PLAYWRIGHT, "playwright", transport = ConnectorTransport.Stdio, enabled = true),
        )
    }
}
