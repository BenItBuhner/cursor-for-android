package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ConnectorTransport
import com.cursorforandroid.domain.McpConnector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One `CheckHttpMcpStatus` answer, before it is mapped onto its row (see `McpConnectors.withStatus`). */
data class ConnectorStatusReport(val id: Int, val available: Boolean, val requiresAuth: Boolean, val authUrl: String?, val error: String?)

/**
 * The account's MCP list: the servers and connectors cursor.com/agents and the desktop Agents window offer in their
 * MCP dropdowns, whether each is on for the account's cloud agents, and whether Cursor's backend can reach it with
 * the user's credentials. An interface so the repository can be tested against a fake.
 */
interface McpConnectorApi {
    /** Every server the account can use, the team's included, with their enabled state. */
    suspend fun list(): List<McpConnector>

    /** Makes [enabledIds] the account's whole enabled set. */
    suspend fun setEnabled(enabledIds: List<Int>)

    /** Whether each of [ids] (enabled HTTP servers) is reachable, needs a sign-in (and where), or failed. */
    suspend fun statuses(ids: List<Int>): List<ConnectorStatusReport>

    /** The plugins' brand logos by plugin id, for the servers a plugin brings. */
    suspend fun pluginLogos(): Map<String, String>
}

/**
 * `aiserver.v1.DashboardService`'s MCP corner, the calls behind the MCP dropdown in the desktop Agents window (and,
 * per Cursor's docs, the one on cursor.com/agents): `GetAvailableMcpServers` lists, `UpdateUserDefaultMcpSettings`
 * replaces the enabled set, `CheckHttpMcpStatus` reports reachability and hands out the sign-in URL, and
 * `GetCloudAgentPluginsSnapshot` carries the plugin logos. A sign-in finishes on cursor.com: the status is asked for
 * with the web's own OAuth callback ([OAUTH_REDIRECT]), which exchanges the code in the browser that holds the
 * cursor.com session, so the app only opens the URL and asks again when the user is back.
 */
class DashboardMcpConnectorApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : McpConnectorApi {

    override suspend fun list(): List<McpConnector> {
        val response = call("GetAvailableMcpServers", EmptyDto(), EmptyDto.serializer(), AvailableServersDto.serializer())
        return response.servers.mapNotNull { it.toConnector() }
    }

    override suspend fun setEnabled(enabledIds: List<Int>) {
        call("UpdateUserDefaultMcpSettings", UserDefaultsDto(enabledIds), UserDefaultsDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun statuses(ids: List<Int>): List<ConnectorStatusReport> {
        if (ids.isEmpty()) return emptyList()
        val response = call("CheckHttpMcpStatus", StatusRequestDto(serverIds = ids, oauthRedirectUri = OAUTH_REDIRECT), StatusRequestDto.serializer(), StatusResponseDto.serializer())
        return response.statuses.map { ConnectorStatusReport(it.id, it.isAvailable, it.requiresAuth, it.authUrl, it.error) }
    }

    override suspend fun pluginLogos(): Map<String, String> {
        val response = call("GetCloudAgentPluginsSnapshot", SnapshotRequestDto(useReplica = true), SnapshotRequestDto.serializer(), SnapshotResponseDto.serializer())
        return response.plugins.mapNotNull { entry ->
            val plugin = entry.effectivePlugin?.plugin ?: return@mapNotNull null
            val id = plugin.id?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val logo = plugin.publisher?.logoUrl?.takeIf { it.isNotBlank() } ?: plugin.logoUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to logo
        }.toMap()
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    @Serializable
    private class EmptyDto

    @Serializable
    private data class UserDefaultsDto(val enabledServerIds: List<Int>)

    @Serializable
    private data class StatusRequestDto(val serverIds: List<Int>, val oauthRedirectUri: String)

    @Serializable
    private data class SnapshotRequestDto(val useReplica: Boolean)

    @Serializable
    private data class AvailableServersDto(val servers: List<ServerInfoDto> = emptyList())

    /**
     * `aiserver.v1.McpServerInfo`. `plugin_id` is an int64, a string in proto3 JSON. `accounts`, `served_by` and the
     * static-credential fields are the desktop's multi-account business and not needed for one row per server.
     */
    @Serializable
    private data class ServerInfoDto(
        val id: Int = 0,
        val name: String = "",
        val isTeamServer: Boolean = false,
        val enabled: Boolean = false,
        val type: String = "",
        val url: String? = null,
        val pluginId: JsonPrimitive? = null,
        val userHasAccessToken: Boolean? = null,
        val isRequired: Boolean = false,
        val disabledByTeamAdminPolicy: Boolean = false,
        val logoUrl: String? = null,
    ) {
        val plugin: String? get() = pluginId?.contentOrNull?.takeIf { it.isNotBlank() && it != "0" }

        fun toConnector(): McpConnector? {
            if (id <= 0 || name.isBlank()) return null
            return McpConnector(
                id = id,
                name = name.trim(),
                transport = if (type.equals("stdio", ignoreCase = true)) ConnectorTransport.Stdio else ConnectorTransport.Http,
                url = url?.takeIf { it.isNotBlank() },
                enabled = enabled,
                team = isTeamServer,
                required = isRequired,
                blockedByAdmin = disabledByTeamAdminPolicy,
                logoUrl = logoUrl?.takeIf { it.isNotBlank() },
                hasToken = userHasAccessToken == true,
                pluginId = plugin,
            )
        }
    }

    @Serializable
    private data class StatusResponseDto(val statuses: List<StatusDto> = emptyList())

    @Serializable
    private data class StatusDto(
        val id: Int = 0,
        val isAvailable: Boolean = false,
        val requiresAuth: Boolean = false,
        val authUrl: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class SnapshotResponseDto(val plugins: List<SnapshotPluginDto> = emptyList())

    @Serializable
    private data class SnapshotPluginDto(val effectivePlugin: EffectivePluginDto? = null)

    @Serializable
    private data class EffectivePluginDto(val plugin: PluginDto? = null)

    @Serializable
    private data class PluginDto(val id: JsonPrimitive? = null, val logoUrl: String? = null, val publisher: PublisherDto? = null)

    @Serializable
    private data class PublisherDto(val logoUrl: String? = null)

    companion object {
        const val SERVICE = "aiserver.v1.DashboardService"

        /**
         * The OAuth callback cursor.com/agents registers for its MCP sign-ins (the desktop bundle's web constant and
         * the MCP docs' redirect list). It completes the exchange (`CompleteMcpOAuth`) in the browser, under the
         * cursor.com session the Custom Tab shares, which is why the app hands the sign-in to the browser.
         */
        const val OAUTH_REDIRECT = "https://www.cursor.com/agents/mcp/oauth/callback"
    }
}
