package com.cursorforandroid.domain

import java.net.URI

/** Where a connector runs: Cursor's backend proxies the HTTP ones; stdio ones start inside the agent's VM. */
enum class ConnectorTransport { Http, Stdio }

/**
 * What the account last said about reaching a connector, as the desktop's cloud rows put it
 * (`plusMenuMcpServerMapping.js`): only an enabled HTTP connector is ever checked.
 */
enum class ConnectorStatus {
    /** Off, stdio, or not asked about yet. */
    Unchecked,
    /** `CheckHttpMcpStatus` is on its way. */
    Checking,
    /** `is_available`: Cursor's backend reaches it with the user's credentials. */
    Connected,
    /** `requires_auth`: the user has to sign in to it, at the status's `auth_url`. */
    NeedsAuth,
    /** Neither available nor asking for sign-in: the status's `error`. */
    Error,
}

/**
 * One row of the account's MCP list: the servers and connectors cursor.com/agents and the desktop Agents window
 * list in their MCP dropdowns (`DashboardService/GetAvailableMcpServers`). Its [enabled] state is the account's,
 * so it applies to every cloud agent the account starts, wherever it is started from.
 */
data class McpConnector(
    /** `McpServerInfo.id`: the number `UpdateUserDefaultMcpSettings` and `CheckHttpMcpStatus` take. */
    val id: Int,
    val name: String,
    val transport: ConnectorTransport = ConnectorTransport.Http,
    val url: String? = null,
    val enabled: Boolean = false,
    /** Configured by a team admin (Dashboard › Plugins & MCPs) rather than by the user. */
    val team: Boolean = false,
    /** The team requires it: it stays on. */
    val required: Boolean = false,
    /** The team's policy switched it off: it stays off. */
    val blockedByAdmin: Boolean = false,
    /** The plugin brand's logo or the server's own, when the account has one. */
    val logoUrl: String? = null,
    /** The plugin that brings it, whose brand logo stands for it (`GetCloudAgentPluginsSnapshot`). */
    val pluginId: String? = null,
    /** The listing's `user_has_access_token`: a hint that it is connected, until a status says otherwise. */
    val hasToken: Boolean = false,
    val status: ConnectorStatus = ConnectorStatus.Unchecked,
    /** Where the user signs in when [status] is [ConnectorStatus.NeedsAuth]. */
    val authUrl: String? = null,
    val error: String? = null,
) {
    val canToggle: Boolean get() = !required && !blockedByAdmin

    /** The row offers "Connect": enabled, asking for sign-in, and the account gave a place to do it. */
    val canConnect: Boolean get() = enabled && status == ConnectorStatus.NeedsAuth && !authUrl.isNullOrBlank()

    /** The row's second line. */
    val statusLabel: String
        get() = when {
            blockedByAdmin -> "Turned off by your team"
            !enabled -> if (team) "Team · Off" else "Off"
            else -> when (status) {
                ConnectorStatus.Connected -> "Connected"
                ConnectorStatus.NeedsAuth -> "Needs sign-in"
                ConnectorStatus.Error -> error?.takeIf { it.isNotBlank() }?.let { "Error: $it" } ?: "Error"
                ConnectorStatus.Checking -> "Checking…"
                ConnectorStatus.Unchecked -> when {
                    transport == ConnectorTransport.Stdio -> "Runs in the agent's VM"
                    required -> "Required by your team"
                    team -> "Team"
                    hasToken -> "Connected"
                    else -> "On"
                }
            }
        }
}

/** The list's rules, kept free of Compose and of the network so they are unit-testable. */
object McpConnectors {
    /** Sorted by name, as the desktop sorts its cloud rows. */
    fun sorted(connectors: List<McpConnector>): List<McpConnector> = connectors.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * What `UpdateUserDefaultMcpSettings` takes after [id] is switched to [enabled]: the whole enabled set, not a
     * delta — the desktop's `_toggleCloudMcpServer` sends every enabled id the same way.
     */
    fun enabledIdsAfter(connectors: List<McpConnector>, id: Int, enabled: Boolean): List<Int> =
        connectors.mapNotNull { c ->
            val on = if (c.id == id) enabled else c.enabled
            c.id.takeIf { on }
        }

    /** The desktop asks for a status only for enabled HTTP servers with a URL (`opm`). */
    fun needsStatus(connector: McpConnector): Boolean =
        connector.enabled && connector.transport == ConnectorTransport.Http && !connector.url.isNullOrBlank()

    /** The connector after a `CheckHttpMcpStatus` answer, mapped as the desktop's `rpm` maps it. */
    fun withStatus(connector: McpConnector, available: Boolean, requiresAuth: Boolean, authUrl: String?, error: String?): McpConnector = when {
        available -> connector.copy(status = ConnectorStatus.Connected, authUrl = null, error = null)
        requiresAuth -> connector.copy(status = ConnectorStatus.NeedsAuth, authUrl = authUrl?.takeIf { it.isNotBlank() }, error = null)
        !error.isNullOrBlank() -> connector.copy(status = ConnectorStatus.Error, authUrl = null, error = error.trim())
        else -> connector.copy(status = ConnectorStatus.Unchecked, authUrl = null, error = null)
    }

    /**
     * The `ProjectIcons` glyph that stands for a well-known connector until (or instead of) its logo, by name and
     * then by its URL's host; the generic MCP mark otherwise.
     */
    fun glyph(connector: McpConnector): String {
        val host = connector.url?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.lowercase().orEmpty()
        val haystack = connector.name.lowercase() + " " + host
        return BRANDS.firstOrNull { (keys, _) -> keys.any { it in haystack } }?.second ?: "logo-mcp"
    }

    private val BRANDS = listOf(
        listOf("github") to "logo-github",
        listOf("gitlab") to "logo-gitlab",
        listOf("linear") to "logo-linear",
        listOf("notion") to "logo-notion",
        listOf("sentry") to "logo-sentry",
        listOf("slack") to "logo-slack",
        listOf("figma") to "logo-figma",
        listOf("jira", "atlassian") to "logo-jira",
        listOf("azure devops", "dev.azure") to "logo-azure-devops",
        listOf("azure") to "logo-azure",
        listOf("teams") to "logo-microsoft-teams",
    )
}
