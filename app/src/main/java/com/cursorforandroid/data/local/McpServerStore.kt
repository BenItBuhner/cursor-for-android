package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * The user's MCP server definitions, the app-side counterpart of the MCP dropdown on cursor.com/agents. Enabled
 * servers are sent inline with every prompt. The list lives in [SecureKeyStore] because headers and env carry
 * credentials.
 *
 * Both ends of that store cost real time — opening the encrypted prefs takes tens of milliseconds the first time,
 * and every read and write is an AES pass over JSON — and every caller is a composition or a click lambda, so none
 * of it happens on the thread that asked. [servers] starts empty and fills in from [scope]; an edit updates it at
 * once and the write follows. An edit made before the stored list arrived is replayed over it, so a server saved
 * against a list that was still loading cannot wipe the ones already on disk.
 */
class McpServerStore(
    private val secure: SecureKeyStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val state = MutableStateFlow<List<McpServer>>(emptyList())
    private val lock = Any()
    private val writeMutex = Mutex()
    @Volatile private var loaded = false
    private val replay = mutableListOf<(List<McpServer>) -> List<McpServer>>()

    init {
        scope.launch { loadOnce() }
    }

    val servers: StateFlow<List<McpServer>> = state.asStateFlow()

    /**
     * The servers that go out with the next prompt. A prompt must not silently lose them, so on the vanishing chance
     * the initial read has not landed yet this waits for it instead of answering empty.
     */
    fun enabled(): List<McpServer> {
        loadOnce()
        return state.value.filter { it.enabled }
    }

    /** Inserts or replaces by id, keeping the list order for an existing server. */
    fun save(server: McpServer) = edit { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun setEnabled(id: String, enabled: Boolean) = edit { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    private fun edit(transform: (List<McpServer>) -> List<McpServer>) {
        synchronized(lock) {
            if (!loaded) replay += transform
            state.update(transform)
        }
        scope.launch { persist() }
    }

    /**
     * Serialized against itself, and it re-reads [state] under the lock rather than taking a snapshot at the call
     * site, so whichever write lands last writes the newest list however the two were scheduled.
     */
    private suspend fun persist() = writeMutex.withLock {
        loadOnce()
        secure.setMcpServersJson(state.value.takeIf { it.isNotEmpty() }?.let { CursorJson.encodeToString(serializer, it) })
    }

    private fun loadOnce() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val stored = secure.mcpServersJson()
                ?.let { runCatching { CursorJson.decodeFromString(serializer, it) }.getOrNull() }
                ?: emptyList()
            loaded = true
            if (replay.isEmpty()) {
                if (stored.isNotEmpty()) state.value = stored
            } else {
                state.value = replay.fold(stored) { list, transform -> transform(list) }
                replay.clear()
            }
        }
    }

    private companion object {
        val serializer = ListSerializer(McpServer.serializer())
    }
}
