package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.DesktopProbe
import com.cursorforandroid.data.api.MachineLookupApi
import com.cursorforandroid.data.api.ProbeResult
import com.cursorforandroid.data.api.dto.ListWorkersResponseDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.MachineReference
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** How opening the agent's desktop ended. */
sealed interface DesktopOpen {
    data class Opened(val session: DesktopSession) : DesktopOpen
    data class Failed(val failure: DesktopFailure) : DesktopOpen
}

/**
 * The panel's Remote section. Two halves, on two footings. The public one: a Remote Control chat runs on one of the
 * user's machines (`env.type == machine`), and `GET /v0/private-workers` says whether that machine is reporting to
 * Cursor and what it is busy with ([machineStatus]); nothing here needs Extended mode. The private one: the agent's
 * VM desktop, reached through `GetMachine` for the pod and its ticket, then a probe of the websockify URLs Cursor's
 * bundle builds for it, so a stopped VM or a moved endpoint is a named state before any WebView is shown ([openDesktop]).
 * That half is gated on [Capabilities.remoteDesktop] and makes no call while the flag is off.
 */
class RemoteRepository(
    private val workers: suspend () -> ListWorkersResponseDto,
    private val machines: MachineLookupApi,
    private val probe: DesktopProbe,
    private val capabilities: suspend () -> Capabilities,
    private val isDemo: () -> Boolean = { false },
    private val now: () -> Long = AppClock::now,
) {
    private class Cached<T>(val value: T, val at: Long)

    private var workerList: Cached<List<WorkerDto>>? = null
    private val workersLock = Mutex()

    /**
     * Where [agent]'s machine stands, for a chat that runs on one; null for a chat that does not. The fleet endpoint
     * is read once per [WORKERS_TTL_MS] for every machine chat; a key that cannot call it (the fleet docs want a pool
     * service account) surfaces as the failure it is, not as "not connected".
     */
    suspend fun machineStatus(agent: Agent, force: Boolean = false): Result<MachineStatus>? {
        if (agent.envType != EnvType.MACHINE) return null
        val name = DeviceTarget.machine(agent.envName.orEmpty()).apiName ?: agent.envName?.trim()?.takeIf { it.isNotEmpty() } ?: "this machine"
        if (isDemo()) return Result.success(MachineStatus(name, connected = true, isInUse = agent.isRunning, activeAgentId = agent.id.takeIf { agent.isRunning }))
        val listed = listWorkers(force).getOrElse { return Result.failure(it) }
        val wanted = name.lowercase()
        val worker = listed.firstOrNull { it.activeBcId == agent.id } ?: listed.firstOrNull { wanted in it.names() }
        return Result.success(
            if (worker == null) MachineStatus(name, connected = false)
            else MachineStatus(name, connected = true, isInUse = worker.isInUse || worker.activeBcId != null, activeAgentId = worker.activeBcId?.takeIf { it.isNotBlank() }),
        )
    }

    private suspend fun listWorkers(force: Boolean): Result<List<WorkerDto>> {
        if (!force) workerList?.takeIf { now() - it.at < WORKERS_TTL_MS }?.let { return Result.success(it.value) }
        return workersLock.withLock {
            if (!force) workerList?.takeIf { now() - it.at < WORKERS_TTL_MS }?.let { return@withLock Result.success(it.value) }
            try {
                Result.success(workers().listed().also { workerList = Cached(it, now()) })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(IOException(t.userMessage(), t))
            }
        }
    }

    /**
     * The agent's desktop: `GetMachine` for the pod, then the first websockify URL that accepts a WebSocket. A worker
     * machine has no desktop reachable from here (its stream is a relay this app does not carry) and says so; a pod
     * whose every candidate fails the probe is unreachable — stopped, hibernated, or behind an endpoint that moved.
     */
    suspend fun openDesktop(agent: Agent, viewOnly: Boolean = true): DesktopOpen {
        if (isDemo()) return DesktopOpen.Failed(DesktopFailure.NotAvailable(NOT_IN_DEMO))
        if (!capabilities().remoteDesktop) return DesktopOpen.Failed(DesktopFailure.NotAvailable(NEEDS_EXTENDED_MODE))
        val reference = try {
            machines.machine(agent.id)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return DesktopOpen.Failed(describeLookup(t))
        }
        return when (reference) {
            is MachineReference.Worker -> DesktopOpen.Failed(
                DesktopFailure.NoDesktop(
                    reference.unavailableReason?.let { "The machine reports its desktop as unavailable: $it." }
                        ?: if (reference.available) WORKER_DESKTOP_RELAY else WORKER_NO_DESKTOP,
                ),
            )
            is MachineReference.Pod -> {
                val candidates = reference.candidateUrls()
                val (url, failures) = probe.firstReachable(candidates)
                if (url == null) {
                    DesktopOpen.Failed(DesktopFailure.Unreachable(describeProbe(failures, agent)))
                } else {
                    val port = MachineReference.Pod.DESKTOP_PORTS.getOrNull(candidates.indexOf(url))
                    DesktopOpen.Opened(DesktopSession(agent.id, url, viewOnly = viewOnly, port = port))
                }
            }
        }
    }

    fun reset() {
        workerList = null
    }

    private fun describeLookup(t: Throwable): DesktopFailure = when (t) {
        is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) DesktopFailure.NotAvailable(NEEDS_EXTENDED_MODE) else DesktopFailure.Refused(t.message ?: "Cursor couldn't start a session for this key.")
        is ConnectRpcException -> when {
            t.httpCode == 404 || t.code == "unimplemented" -> DesktopFailure.Refused(ENDPOINT_CHANGED, endpointChanged = true)
            t.code == "not_found" || t.code == "failed_precondition" -> DesktopFailure.Unreachable("The agent's VM isn't running right now (${t.message}). A finished chat's VM is hibernated; a follow-up wakes it.")
            else -> DesktopFailure.Refused("Cursor refused (${t.message}).")
        }
        is IOException -> DesktopFailure.Refused(t.userMessage())
        else -> DesktopFailure.Refused(t.message ?: "Couldn't find the agent's machine.")
    }

    private fun describeProbe(failures: List<ProbeResult>, agent: Agent): String {
        val refused = failures.filterIsInstance<ProbeResult.Refused>()
        val running = agent.isRunning
        return when {
            refused.any { it.httpCode == 401 || it.httpCode == 403 } -> "The desktop refused this ticket (HTTP ${refused.first { it.httpCode == 401 || it.httpCode == 403 }.httpCode}). Cursor may have changed how desktop sessions are opened; try again, or open the chat on cursor.com."
            refused.isNotEmpty() -> "The desktop endpoint answered but would not open a session (HTTP ${refused.first().httpCode}) on any of the ports this app knows. Cursor may have moved it."
            running -> "The agent's desktop isn't reachable right now. The VM may still be starting; try again in a moment."
            else -> "The agent's desktop isn't reachable: a finished chat's VM is hibernated, and a stopped one has no desktop. A follow-up wakes it."
        }
    }

    companion object {
        const val WORKERS_TTL_MS = 30_000L
        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: the desktop is opened through GetMachine and the VM's own endpoint, neither of them documented."
        const val NOT_IN_DEMO = "The demo has no desktop to show."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; the desktop is unavailable until the app is updated."
        const val WORKER_DESKTOP_RELAY = "This chat runs on your own machine. Its desktop streams through a relay this build doesn't carry yet; open it in Cursor on the desktop."
        const val WORKER_NO_DESKTOP = "This chat runs on your own machine, which shares no desktop. Start its worker with --share-desktop to offer one."
    }
}
