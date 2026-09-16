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
import com.cursorforandroid.domain.DesktopTrace
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.MachineReference
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.MachineUnavailableReason
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Named in the diagnostics report, so a shared one says which build produced it. */
    private val appVersion: String? = null,
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
     * The agent's desktop, the way the Agents Window opens it (Cursor 3.20.21): `GetMachine {bcId, mintDesktopTicket}`
     * for the pod, then noVNC against `wss://{tenant}-{pod}-{port}.{cluster}.cursorvm.com/websockify?network_token=…`
     * on its default port, falling back to the other after ten seconds. Here the fallback is decided by a WebSocket
     * probe before any WebView is shown, and every step is timed into a [DesktopTrace] that [progress] follows and a
     * failure carries. `GetMachine`'s refusals are read as the Agents Window reads them (`cursorServerUrlReason=…`):
     * an expired or archived chat, a worker whose workspace is its coordinator's, a chat with no VM, a VM still coming
     * up — each a named state. A worker machine's desktop needs a relay this app does not carry and says so.
     */
    suspend fun openDesktop(agent: Agent, viewOnly: Boolean = true, progress: (DesktopTrace) -> Unit = {}): DesktopOpen {
        var trace = DesktopTrace(agent.id, appVersion = appVersion)
        fun update(next: DesktopTrace) {
            trace = next
            progress(next)
        }
        if (isDemo()) return DesktopOpen.Failed(DesktopFailure.NotAvailable(NOT_IN_DEMO, trace))
        if (!capabilities().remoteDesktop) return DesktopOpen.Failed(DesktopFailure.NotAvailable(NEEDS_EXTENDED_MODE, trace))

        update(trace.begin(DesktopTrace.GET_MACHINE, now()))
        val reference = try {
            withTimeoutOrNull(STEP_TIMEOUT_MS) { machines.machine(agent.id) } ?: run {
                update(trace.end(DesktopTrace.GET_MACHINE, now(), "timed out after ${STEP_TIMEOUT_MS / 1000} s", failed = true))
                return DesktopOpen.Failed(DesktopFailure.Refused("GetMachine did not answer within ${STEP_TIMEOUT_MS / 1000} seconds.", trace))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            update(trace.end(DesktopTrace.GET_MACHINE, now(), lookupOutcome(t), failed = true))
            return DesktopOpen.Failed(describeLookup(t, agent, trace))
        }
        return when (reference) {
            is MachineReference.Worker -> {
                update(trace.end(DesktopTrace.GET_MACHINE, now(), "worker ${reference.workerId}"))
                DesktopOpen.Failed(
                    DesktopFailure.NoDesktop(
                        reference.unavailableReason?.let { "The machine reports its desktop as unavailable: $it." }
                            ?: if (reference.available) WORKER_DESKTOP_RELAY else WORKER_NO_DESKTOP,
                        trace,
                    ),
                )
            }
            is MachineReference.Pod -> {
                update(trace.end(DesktopTrace.GET_MACHINE, now(), "pod ${reference.podId} in ${reference.cluster} (${if (reference.ticketMinted) "desktop ticket" else "network token"})"))
                val failures = ArrayList<ProbeResult>()
                for (port in MachineReference.Pod.DESKTOP_PORTS) {
                    val host = reference.host(port)
                    update(trace.begin("${DesktopTrace.PROBE} $host", now()))
                    val result = probe.probe(reference.websockifyUrl(port))
                    when (result) {
                        ProbeResult.Reachable -> {
                            update(trace.end("${DesktopTrace.PROBE} $host", now(), "handshake accepted").withEndpoint(host, port))
                            return DesktopOpen.Opened(DesktopSession(agent.id, reference.websockifyUrl(port), viewOnly = viewOnly, port = port, trace = trace))
                        }
                        is ProbeResult.Refused -> update(trace.end("${DesktopTrace.PROBE} $host", now(), "HTTP ${result.httpCode}", failed = true))
                        is ProbeResult.Unreachable -> update(trace.end("${DesktopTrace.PROBE} $host", now(), result.message, failed = true))
                    }
                    failures += result
                }
                DesktopOpen.Failed(DesktopFailure.Unreachable(describeProbe(failures, agent), trace.withEndpoint(reference.host(MachineReference.Pod.DEFAULT_PORT), null)))
            }
        }
    }

    fun reset() {
        workerList = null
    }

    /** What `GetMachine`'s refusal says, in the Agents Window's terms first (`cursorServerUrlReason`), then Connect's. */
    private fun describeLookup(t: Throwable, agent: Agent, trace: DesktopTrace): DesktopFailure {
        val reason = MachineUnavailableReason.parse(t.message)
        if (reason != null) {
            return when {
                reason.isExpired -> DesktopFailure.NoDesktop("This chat's VM is gone: ${reason.code.lowercase().replace('_', ' ')}. An archived or expired chat has no desktop.", trace, reason)
                reason == MachineUnavailableReason.WORKSPACE_ON_COORDINATOR -> DesktopFailure.NoDesktop("This worker runs on its coordinator's machine. Open the coordinator's workspace instead.", trace, reason)
                reason == MachineUnavailableReason.MACHINE_NOT_PROVISIONED -> DesktopFailure.NoDesktop(
                    if (agent.isRunning) "The chat has no VM yet (MACHINE_NOT_PROVISIONED). It is starting one; try again in a moment." else "The chat has no VM (MACHINE_NOT_PROVISIONED): a Project coordinator, or a chat that never started one, has no desktop to show.",
                    trace,
                    reason,
                    retryable = agent.isRunning,
                )
                else -> DesktopFailure.Preparing("The VM is still coming up (${reason.code}). The Agents Window waits ten seconds and asks again; so can you.", trace, reason)
            }
        }
        return when (t) {
            is SessionUnavailableException -> if (t.code == SessionUnavailableException.EXTENDED_MODE_OFF) DesktopFailure.NotAvailable(NEEDS_EXTENDED_MODE, trace) else DesktopFailure.Refused(t.message ?: "Cursor couldn't start a session for this key.", trace)
            is ConnectRpcException -> when {
                t.httpCode == 404 || t.code == "unimplemented" -> DesktopFailure.Refused(ENDPOINT_CHANGED, trace, endpointChanged = true, statusCode = t.httpCode)
                t.isUnauthenticated || t.code == "permission_denied" || t.httpCode == 403 -> DesktopFailure.Refused("You are not authorized to access this agent's desktop (HTTP ${t.httpCode}).", trace, statusCode = t.httpCode)
                t.code == "not_found" -> DesktopFailure.Unreachable("Cursor has no machine for this chat right now (not found). A finished chat's VM is hibernated; a follow-up wakes it.", trace)
                t.code == "failed_precondition" -> DesktopFailure.Unreachable("The agent's VM isn't running right now (${t.message}). A finished chat's VM is hibernated; a follow-up wakes it.", trace)
                else -> DesktopFailure.Refused("Cursor refused (${t.message}).", trace, statusCode = t.httpCode)
            }
            is IOException -> DesktopFailure.Refused(t.userMessage(), trace)
            else -> DesktopFailure.Refused(t.message ?: "Couldn't find the agent's machine.", trace)
        }
    }

    /** The one-line outcome of a failed `GetMachine`, for the trace: the reason code when named, else the status. */
    private fun lookupOutcome(t: Throwable): String = MachineUnavailableReason.parse(t.message)?.code ?: when (t) {
        is ConnectRpcException -> "HTTP ${t.httpCode}${t.code?.let { " $it" } ?: ""}"
        is SessionUnavailableException -> t.code ?: "no session"
        else -> t.javaClass.simpleName
    }

    private fun describeProbe(failures: List<ProbeResult>, agent: Agent): String {
        val refused = failures.filterIsInstance<ProbeResult.Refused>()
        val running = agent.isRunning
        return when {
            refused.any { it.httpCode == 401 || it.httpCode == 403 } -> "The desktop refused this ticket (HTTP ${refused.first { it.httpCode == 401 || it.httpCode == 403 }.httpCode}). Cursor may have changed how desktop sessions are opened; try again, or open the chat on cursor.com."
            refused.isNotEmpty() -> "The desktop endpoint answered but would not open a session (HTTP ${refused.first().httpCode}) on port ${MachineReference.Pod.DEFAULT_PORT} or ${MachineReference.Pod.FALLBACK_PORT}. Cursor may have moved it."
            running -> "The agent's desktop isn't reachable right now on port ${MachineReference.Pod.DEFAULT_PORT} or ${MachineReference.Pod.FALLBACK_PORT}. The VM may still be starting; try again in a moment."
            else -> "The agent's desktop isn't reachable: a finished chat's VM is hibernated, and a stopped one has no desktop. A follow-up wakes it."
        }
    }

    companion object {
        const val WORKERS_TTL_MS = 30_000L
        /** What each step gets before it is called out as the one that hung: the Agents Window's own connect timeout. */
        const val STEP_TIMEOUT_MS = 10_000L
        const val NEEDS_EXTENDED_MODE = "Needs Extended mode: the desktop is opened through GetMachine and the VM's own endpoint, neither of them documented."
        const val NOT_IN_DEMO = "The demo has no desktop to show."
        const val ENDPOINT_CHANGED = "Cursor changed a private endpoint; the desktop is unavailable until the app is updated."
        const val WORKER_DESKTOP_RELAY = "This chat runs on your own machine. Its desktop streams through a relay this build doesn't carry yet; open it in Cursor on the desktop."
        const val WORKER_NO_DESKTOP = "This chat runs on your own machine, which shares no desktop. Start its worker with --share-desktop to offer one."
    }
}
