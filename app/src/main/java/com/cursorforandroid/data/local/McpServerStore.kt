package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * The user's MCP server definitions, the app-side counterpart of the MCP dropdown on cursor.com/agents. Enabled
 * servers are sent inline with every prompt. The list lives in [SecureKeyStore] because headers and env carry
 * credentials; it is decoded once, on first use, and every change is written straight back.
 */
class McpServerStore(private val secure: SecureKeyStore) {

    private val state: MutableStateFlow<List<McpServer>> by lazy { MutableStateFlow(load()) }

    init {
        // Opening the encrypted prefs can cost tens of milliseconds the first time; do it off the main thread. A
        // concurrent first read waits on this initialisation rather than starting its own (lazy is synchronized).
        CoroutineScope(Dispatchers.IO).launch { state }
    }

    val servers: StateFlow<List<McpServer>> get() = state

    /** The servers that go out with the next prompt. */
    fun enabled(): List<McpServer> = state.value.filter { it.enabled }

    /** Inserts or replaces by id, keeping the list order for an existing server. */
    fun save(server: McpServer) = edit { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, enabled: Boolean) = edit { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    private fun edit(transform: (List<McpServer>) -> List<McpServer>) {
        state.update(transform)
        secure.setMcpServersJson(state.value.takeIf { it.isNotEmpty() }?.let { CursorJson.encodeToString(serializer, it) })
    }

    private fun load(): List<McpServer> =
        secure.mcpServersJson()?.let { runCatching { CursorJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        val serializer = ListSerializer(McpServer.serializer())
    }
}
