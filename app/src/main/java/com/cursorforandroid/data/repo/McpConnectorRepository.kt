package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectorStatusReport
import com.cursorforandroid.data.api.McpConnectorApi
import com.cursorforandroid.domain.ConnectorStatus
import com.cursorforandroid.domain.McpConnector
import com.cursorforandroid.domain.McpConnectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** What the "+" menu's MCP page shows in Extended mode. */
data class ConnectorsState(
    val connectors: List<McpConnector> = emptyList(),
    val loading: Boolean = false,
    /** The list has been read at least once since the last reset. */
    val loaded: Boolean = false,
    /** Why the list could not be read. */
    val error: String? = null,
    /** Why the last switch did not stick; it was put back. */
    val notice: String? = null,
)

/**
 * The account's MCP connectors ([McpConnectorApi]) for the composer's "+" menu: listed when the page opens, switched
 * on and off for every cloud agent the account starts, and checked for a sign-in. A switch shows at once and is put
 * back when the account refuses it, as the desktop's `_toggleCloudMcpServer` does. Switches go out one at a time,
 * each with the whole enabled set as it stands, so two quick taps cannot race each other into a stale set.
 *
 * [allowed] is Extended mode's `accountConnectors` (the demo answers from its own [McpConnectorApi]); with it off
 * nothing is called and the state stays empty. The graph resets this on sign-out and on every mode change.
 */
class McpConnectorRepository(
    private val api: () -> McpConnectorApi,
    private val allowed: suspend () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(ConnectorsState())
    val state: StateFlow<ConnectorsState> = _state.asStateFlow()

    /** Bumped by [reset]: an answer to a call made before it is dropped. */
    private val generation = AtomicInteger()
    private val writes = Mutex()
    @Volatile private var refreshing: Job? = null
    @Volatile private var logos: Map<String, String>? = null
    @Volatile private var awaitingSignIn = false

    /** Reads the list and the enabled connectors' statuses again; a read already on its way is joined rather than repeated. */
    fun refresh(): Job {
        refreshing?.takeIf { it.isActive }?.let { return it }
        val gen = generation.get()
        return scope.launch {
            if (!allowed()) {
                _state.value = ConnectorsState()
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            val listed = try {
                api().list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation.get()) _state.update { it.copy(loading = false, error = e.message?.takeIf { m -> m.isNotBlank() } ?: "Couldn't load your MCP servers.") }
                return@launch
            }
            val brandLogos = logos ?: runCatching { api().pluginLogos() }.getOrElse { if (it is CancellationException) throw it else emptyMap() }.also { logos = it }
            val rows = McpConnectors.sorted(listed).map { c ->
                val logo = c.pluginId?.let(brandLogos::get) ?: c.logoUrl
                val checking = McpConnectors.needsStatus(c)
                c.copy(logoUrl = logo, status = if (checking) ConnectorStatus.Checking else ConnectorStatus.Unchecked)
            }
            if (gen != generation.get()) return@launch
            _state.update { it.copy(connectors = rows, loading = false, loaded = true, error = null) }
            checkStatuses(rows.filter(McpConnectors::needsStatus).map { it.id }, gen)
        }.also { refreshing = it }
    }

    /**
     * Switches [id] for the account. The row changes now; the account is told with the whole enabled set, and a
     * refusal puts the row back with a [ConnectorsState.notice]. A connector switched on is checked for a sign-in.
     */
    fun setEnabled(id: Int, enabled: Boolean): Job {
        val gen = generation.get()
        val before = _state.value.connectors.firstOrNull { it.id == id }
        _state.update { s ->
            s.copy(
                notice = null,
                connectors = s.connectors.map {
                    if (it.id != id) it
                    else it.copy(enabled = enabled, status = if (enabled && McpConnectors.needsStatus(it.copy(enabled = true))) ConnectorStatus.Checking else ConnectorStatus.Unchecked, authUrl = null, error = null)
                },
            )
        }
        return scope.launch {
            if (before == null || !before.canToggle || !allowed()) {
                if (before != null) restore(before, gen, null)
                return@launch
            }
            val failure = writes.withLock {
                if (gen != generation.get()) return@launch
                val ids = McpConnectors.enabledIdsAfter(_state.value.connectors, id, enabled)
                try {
                    api().setEnabled(ids)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            }
            if (failure != null) {
                val verb = if (enabled) "turn on" else "turn off"
                restore(before, gen, "Couldn't $verb ${before.name}: ${failure.message?.takeIf { it.isNotBlank() } ?: "the account refused."}")
                return@launch
            }
            val now = _state.value.connectors.firstOrNull { it.id == id }
            if (now != null && McpConnectors.needsStatus(now)) checkStatuses(listOf(id), gen)
        }
    }

    /**
     * Where the user signs in to [id]: asked for afresh, since the account's URL carries a one-time state, falling back
     * to the one the row already has. The next [onReturn] reads the statuses again.
     */
    suspend fun signInUrl(id: Int): String? {
        val gen = generation.get()
        val current = _state.value.connectors.firstOrNull { it.id == id } ?: return null
        val fresh = runCatching { api().statuses(listOf(id)).firstOrNull { it.id == id } }.getOrElse { if (it is CancellationException) throw it else null }
        if (fresh != null && gen == generation.get()) apply(listOf(fresh))
        val url = _state.value.connectors.firstOrNull { it.id == id }?.authUrl ?: current.authUrl
        if (url != null) awaitingSignIn = true
        return url
    }

    /** The app is back in front: after a sign-in was handed to the browser, the statuses are read again. */
    fun onReturn() {
        if (!awaitingSignIn) return
        awaitingSignIn = false
        val ids = _state.value.connectors.filter(McpConnectors::needsStatus).map { it.id }
        if (ids.isEmpty()) return
        val gen = generation.get()
        _state.update { s -> s.copy(connectors = s.connectors.map { if (it.id in ids) it.copy(status = ConnectorStatus.Checking) else it }) }
        scope.launch { checkStatuses(ids, gen) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Forgets everything the account said: signed out, or Extended mode switched. */
    fun reset() {
        generation.incrementAndGet()
        refreshing?.cancel()
        refreshing = null
        logos = null
        awaitingSignIn = false
        _state.value = ConnectorsState()
    }

    private suspend fun checkStatuses(ids: List<Int>, gen: Int) {
        if (ids.isEmpty()) return
        val reports = try {
            api().statuses(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation.get()) return
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't check the connection."
            _state.update { s -> s.copy(connectors = s.connectors.map { if (it.id in ids && it.status == ConnectorStatus.Checking) it.copy(status = ConnectorStatus.Error, error = message) else it }) }
            return
        }
        if (gen != generation.get()) return
        apply(reports)
        // A server the answer left out is not known to be anything; it goes back to its listing's hint.
        val answered = reports.map { it.id }.toSet()
        _state.update { s -> s.copy(connectors = s.connectors.map { if (it.id in ids && it.id !in answered && it.status == ConnectorStatus.Checking) it.copy(status = ConnectorStatus.Unchecked) else it }) }
    }

    private fun apply(reports: List<ConnectorStatusReport>) {
        val byId = reports.associateBy { it.id }
        _state.update { s ->
            s.copy(connectors = s.connectors.map { c ->
                val r = byId[c.id]
                if (r == null || !c.enabled) c else McpConnectors.withStatus(c, r.available, r.requiresAuth, r.authUrl, r.error)
            })
        }
    }

    private fun restore(before: McpConnector, gen: Int, notice: String?) {
        if (gen != generation.get()) return
        _state.update { s -> s.copy(notice = notice ?: s.notice, connectors = s.connectors.map { if (it.id == before.id) before else it }) }
    }
}
