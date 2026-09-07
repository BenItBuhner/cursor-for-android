package com.cursorforandroid.domain

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/** Transports the Cloud Agents API accepts for inline MCP servers; SSE and `mcp-remote` are not supported in the cloud. */
enum class McpTransport(val wire: String, val label: String) {
    Http("http", "HTTP"),
    Stdio("stdio", "stdio"),
}

/**
 * An MCP server the user defined in the app. Enabled servers go out inline as `mcpServers[]` on Create An Agent and
 * Create A Run (cursor.com/docs/cloud-agent/api/endpoints). HTTP servers are proxied by Cursor's backend, so their
 * headers never reach the VM; stdio servers start inside the agent's VM with [env]. Both [headers] and [env] carry
 * credentials, which is why the list is kept in the encrypted store.
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val transport: McpTransport = McpTransport.Http,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    /** Sent with new prompts. Off keeps the definition without attaching it. */
    val enabled: Boolean = true,
) {
    /** Second line of a row: the host for HTTP servers, the command line for stdio ones. */
    val summary: String
        get() = when (transport) {
            McpTransport.Http -> url.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            McpTransport.Stdio -> (listOf(command) + args).joinToString(" ").trim()
        }
}

/** Parsing and validation for the MCP server editor; kept free of Compose so it is unit-testable. */
object McpServerForm {
    /** The API caps inline definitions at 50 per request. */
    const val MAX_SERVERS = 50

    /** `Key: Value` per line; blank lines are skipped. */
    fun parseHeaders(text: String): Result<Map<String, String>> = parsePairs(text, ':', "Headers use one \"Name: value\" per line.")

    /** `KEY=value` per line; blank lines are skipped. */
    fun parseEnv(text: String): Result<Map<String, String>> = parsePairs(text, '=', "Environment variables use one \"NAME=value\" per line.")

    /** One argument per line; blank lines are skipped. */
    fun parseArgs(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun formatHeaders(headers: Map<String, String>): String = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    fun formatEnv(env: Map<String, String>): String = env.entries.joinToString("\n") { "${it.key}=${it.value}" }
    fun formatArgs(args: List<String>): String = args.joinToString("\n")

    /** The first problem with [server] as it would be sent, or null when it is good to save. */
    fun validate(server: McpServer, others: List<McpServer>): String? {
        val name = server.name.trim()
        if (name.isEmpty()) return "Give the server a name."
        if (name.any { it.isWhitespace() }) return "Server names can't contain spaces."
        if (others.any { it.id != server.id && it.name.equals(name, ignoreCase = true) }) return "You already have a server called \"$name\"."
        return when (server.transport) {
            McpTransport.Http -> validateUrl(server.url.trim())
            McpTransport.Stdio -> if (server.command.trim().isEmpty()) "Enter the command that starts the server." else null
        }
    }

    private fun validateUrl(url: String): String? {
        if (url.isEmpty()) return "Enter the server URL."
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "Enter a valid http or https URL."
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return "Enter a valid http or https URL."
        if (uri.userInfo != null) return "URLs with a username or password aren't allowed; use a header instead."
        return null
    }

    private fun parsePairs(text: String, separator: Char, error: String): Result<Map<String, String>> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val index = line.indexOf(separator)
            if (index <= 0) return Result.failure(IllegalArgumentException(error))
            map[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return Result.success(map)
    }
}
