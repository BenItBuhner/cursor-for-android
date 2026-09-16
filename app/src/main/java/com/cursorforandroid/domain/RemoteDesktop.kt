package com.cursorforandroid.domain

/**
 * Where a Remote Control chat's machine stands, from `GET /v0/private-workers`: whether the machine is reporting to
 * Cursor at all, and whether it is busy — with this chat or with another.
 */
data class MachineStatus(
    /** The machine's name as the chat's `env.name` gives it. */
    val name: String,
    /** The fleet endpoint lists the machine right now. */
    val connected: Boolean,
    val isInUse: Boolean = false,
    /** The chat the machine is working for, when the endpoint said. */
    val activeAgentId: String? = null,
) {
    fun isBusyWith(agentId: String): Boolean = connected && activeAgentId == agentId

    /** One line for the panel: "Connected · working on this chat", "Connected · idle", "Not connected". */
    fun label(agentId: String): String = when {
        !connected -> "Not connected"
        isBusyWith(agentId) -> "Connected · working on this chat"
        isInUse -> "Connected · busy with another chat"
        else -> "Connected · idle"
    }
}

/** Where the agent's desktop can be reached, from `GetMachine`. */
sealed interface MachineReference {
    /**
     * A Cursor-hosted pod: the desktop is noVNC behind websockify on the pod's `cursorvm.com` host, reached the way
     * the Agents Window builds it (`vm-websocket-url.js` in Cursor 3.20.21: `wss://{tenant}-{pod}-{port}.{cluster}
     * .cursorvm.com:443/websockify?network_token=…&resume_lower_s=900&resume_upper_s=18000`). [token] is the
     * `networkToken` the call answered with — a short-lived desktop ticket when `mintDesktopTicket` was honoured.
     * Never logged: [toString] leaves it out.
     */
    data class Pod(
        val podId: String,
        val tenantId: String,
        val cluster: String,
        val token: String,
        /** True when the account answered with a field that calls itself a ticket beside the network token. */
        val ticketMinted: Boolean = false,
    ) : MachineReference {
        /** The `cursorvm.com` host that serves [port]; safe to show and to share (no token in it). */
        fun host(port: Int): String = "$tenantId-$podId-$port.$cluster.$DESKTOP_DOMAIN"

        /** The websockify URL for [port], as the Agents Window builds it. */
        fun websockifyUrl(port: Int): String = "wss://${host(port)}:443/websockify?network_token=$token&resume_lower_s=900&resume_upper_s=18000"

        /** The URLs to try, in the Agents Window's order: its default port first, then the one it falls back to after ten seconds. */
        fun candidateUrls(): List<String> = DESKTOP_PORTS.map(::websockifyUrl)

        override fun toString(): String = "Pod(podId=$podId, tenantId=$tenantId, cluster=$cluster, ticketMinted=$ticketMinted)"

        companion object {
            const val DESKTOP_DOMAIN = "cursorvm.com"
            /** The Agents Window's default desktop port, then its fallback (`use-vnc-connection-with-fallback.react.js`). */
            const val DEFAULT_PORT = 26058
            const val FALLBACK_PORT = 6080
            val DESKTOP_PORTS = listOf(DEFAULT_PORT, FALLBACK_PORT)
        }
    }

    /**
     * A self-hosted worker (Remote Control, a team pool): its desktop streams through `LifecycleService/OpenDesktopSession`
     * and a bidirectional relay this app does not carry. [available] and [unavailableReason] are the worker's own word.
     */
    data class Worker(
        val workerId: String,
        val workspaceRootPath: String? = null,
        val available: Boolean = false,
        val controlAllowed: Boolean = false,
        val unavailableReason: String? = null,
    ) : MachineReference
}

/**
 * Why `GetMachine` had no machine to give, as the Agents Window reads it off the error's message
 * (`machine-load-failure.js`: `cursorServerUrlReason=<CODE>`). Each code is a named state there, and here.
 */
enum class MachineUnavailableReason(val code: String) {
    /** The chat is over: expired, archived, or its workflow errored. No retry. */
    AGENT_EXPIRED("AGENT_EXPIRED"),
    AGENT_ARCHIVED("AGENT_ARCHIVED"),
    WORKFLOW_ERROR("WORKFLOW_ERROR"),
    /** A Project worker created on its coordinator's VM: the desktop is the coordinator's. No retry. */
    WORKSPACE_ON_COORDINATOR("WORKSPACE_ON_COORDINATOR"),
    /** The chat never started a VM (a Project coordinator, a chat that has not run yet). Retried while the chat runs. */
    MACHINE_NOT_PROVISIONED("MACHINE_NOT_PROVISIONED"),
    /** The VM is coming up; the Agents Window polls these every ten seconds. */
    NO_POD_INFO_YET("NO_POD_INFO_YET"),
    NO_VM_CONNECTION_INFO_YET("NO_VM_CONNECTION_INFO_YET"),
    EXEC_DAEMON_NOT_READY("EXEC_DAEMON_NOT_READY"),
    POD_REPLACEMENT_IN_PROGRESS("POD_REPLACEMENT_IN_PROGRESS"),
    ;

    val isExpired: Boolean get() = this == AGENT_EXPIRED || this == AGENT_ARCHIVED || this == WORKFLOW_ERROR
    val isPreparing: Boolean get() = this == NO_POD_INFO_YET || this == NO_VM_CONNECTION_INFO_YET || this == EXEC_DAEMON_NOT_READY || this == POD_REPLACEMENT_IN_PROGRESS

    companion object {
        private val PATTERN = Regex("cursorServerUrlReason=([A-Z_]+)")

        /** The reason named in an error message, if it names one this build knows. */
        fun parse(message: String?): MachineUnavailableReason? {
            val code = message?.let { PATTERN.find(it)?.groupValues?.get(1) } ?: return null
            return entries.firstOrNull { it.code == code }
        }
    }
}

/**
 * Which chats the desktop may be offered for. The Agents Window offers it for every agent whose header says
 * `source: "cloud"` — a cloud composer, as opposed to a local or Remote Control one — and lets `GetMachine` say the
 * rest. A phone menu is smaller, so the chats `GetMachine` would only refuse are left out up front: a Project
 * coordinator (no VM of its own to show), a side chat or a subagent listed as a row, a chat that is archived or has
 * expired, and a chat on the user's own machine (its desktop streams through a relay this app does not carry).
 */
object DesktopEligibility {
    fun canOpen(agent: Agent?): Boolean {
        if (agent == null) return false
        return agent.envType == EnvType.CLOUD &&
            !agent.isArchived &&
            agent.runStatus != RunStatus.EXPIRED &&
            !agent.looksLikeProject &&
            agent.parent?.kind != AgentParentKind.SIDE_CHAT &&
            agent.parent?.kind != AgentParentKind.SUBAGENT
    }

    /** Why [canOpen] is false, in a sentence, for the places that name it. */
    fun reasonNotOffered(agent: Agent?): String? = when {
        agent == null -> "The chat is not loaded yet."
        agent.envType == EnvType.MACHINE -> "This chat runs on your own machine, whose desktop streams through a relay this app doesn't carry."
        agent.envType != EnvType.CLOUD -> "Only a chat in Cursor's cloud has a desktop to show."
        agent.isArchived -> "An archived chat's VM is gone."
        agent.runStatus == RunStatus.EXPIRED -> "An expired chat's VM is gone."
        agent.looksLikeProject -> "A Project's coordinator has no VM of its own; open one of its workers."
        agent.parent?.kind == AgentParentKind.SIDE_CHAT -> "A side chat shares its parent's VM; open the parent."
        agent.parent?.kind == AgentParentKind.SUBAGENT -> "A subagent's desktop is its parent's; open the parent."
        else -> null
    }
}

/**
 * Where the bundled noVNC page is served from inside the WebView: a host under the reserved `.invalid` domain, which
 * never resolves, answered entirely from the app's own assets. It is also the `Origin` the desktop endpoint sees,
 * from the probe and from the page alike.
 */
object DesktopPage {
    const val HOST = "desktop.cursor-for-android.invalid"
    const val ORIGIN = "https://$HOST"
    const val URL = "$ORIGIN/desktop.html"
    /** The asset directory the page and noVNC's modules are read from. */
    const val ASSET_DIR = "novnc"
}

/** One step of opening the desktop, timed, with what came of it. Never carries a token or a query string. */
data class DesktopStep(
    val name: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    /** "pod t-…", "HTTP 403", "connected", "timed out after 10 s"; null while the step runs. */
    val outcome: String? = null,
    val failed: Boolean = false,
) {
    val durationMillis: Long? get() = endedAtMillis?.let { it - startedAtMillis }
    val isRunning: Boolean get() = endedAtMillis == null
}

/**
 * What happened on the way to the desktop, for the failure view and for its "Share diagnostics": the steps in order
 * with their timings and outcomes, the host and port tried, the status codes — and nothing that could open the
 * desktop for someone else (no token, no query string). [report] renders it for the share sheet.
 */
data class DesktopTrace(
    val agentId: String,
    val steps: List<DesktopStep> = emptyList(),
    /** The `cursorvm.com` host the session uses, once known. */
    val host: String? = null,
    val port: Int? = null,
    val appVersion: String? = null,
) {
    val current: DesktopStep? get() = steps.lastOrNull { it.isRunning }
    val lastFailed: DesktopStep? get() = steps.lastOrNull { it.failed }

    fun begin(name: String, at: Long): DesktopTrace = copy(steps = steps.filterNot { it.name == name && it.isRunning } + DesktopStep(name, at))

    fun end(name: String, at: Long, outcome: String, failed: Boolean = false): DesktopTrace {
        val index = steps.indexOfLast { it.name == name && it.isRunning }
        if (index < 0) return copy(steps = steps + DesktopStep(name, at, at, outcome, failed))
        return copy(steps = steps.toMutableList().also { it[index] = it[index].copy(endedAtMillis = at, outcome = outcome, failed = failed) })
    }

    fun withEndpoint(host: String?, port: Int?): DesktopTrace = copy(host = host, port = port)

    /** The redacted report: one line per step, then the endpoint. Tokens never reach it; the query string is not recorded. */
    fun report(): String = buildString {
        appendLine("Cursor for Android desktop diagnostics")
        appendLine("agent: $agentId")
        appVersion?.let { appendLine("app: $it") }
        host?.let { appendLine("endpoint: $it${port?.let { p -> ":$p" } ?: ""} (websockify)") }
        appendLine("steps:")
        steps.forEach { step ->
            val duration = step.durationMillis?.let { "${it} ms" } ?: "running"
            appendLine("  ${step.name}: ${step.outcome ?: "…"} ($duration)${if (step.failed) " FAILED" else ""}")
        }
    }.trimEnd()

    companion object {
        const val GET_MACHINE = "GetMachine"
        const val PROBE = "probe"
        const val PAGE = "viewer page"
        const val SCRIPT = "viewer script"
        const val SOCKET = "socket handshake"
        const val FIRST_FRAME = "first frame"
    }
}

/** The panel's desktop session: what the WebView connects to and whether the viewer may drive it. */
data class DesktopSession(
    val agentId: String,
    /** The websockify URL that answered the probe; carries the token, so never logged. */
    val url: String,
    val viewOnly: Boolean = true,
    /** The port that answered, for the section's own record. */
    val port: Int? = null,
    /** What it took to get here, for the viewer's own report. */
    val trace: DesktopTrace = DesktopTrace(agentId),
) {
    override fun toString(): String = "DesktopSession(agentId=$agentId, viewOnly=$viewOnly, port=$port)"
}

/** Why the desktop could not be opened, in words the viewer shows, with what was tried. */
sealed interface DesktopFailure {
    val message: String
    val trace: DesktopTrace
    /** Whether asking again could help. */
    val retryable: Boolean

    /** Extended mode is off, or the demo: nothing was called. */
    data class NotAvailable(override val message: String, override val trace: DesktopTrace) : DesktopFailure {
        override val retryable: Boolean get() = false
    }

    /** The chat has no desktop to reach from here: a worker machine, a coordinator's VM, an expired chat. */
    data class NoDesktop(override val message: String, override val trace: DesktopTrace, val reason: MachineUnavailableReason? = null, override val retryable: Boolean = false) : DesktopFailure

    /** The VM is still coming up; `GetMachine` said so in one of its preparing codes. */
    data class Preparing(override val message: String, override val trace: DesktopTrace, val reason: MachineUnavailableReason) : DesktopFailure {
        override val retryable: Boolean get() = true
    }

    /** `GetMachine` refused or changed shape. */
    data class Refused(override val message: String, override val trace: DesktopTrace, val endpointChanged: Boolean = false, val statusCode: Int? = null) : DesktopFailure {
        override val retryable: Boolean get() = !endpointChanged && statusCode != 401 && statusCode != 403
    }

    /** Every candidate URL failed the probe: the VM is stopped or hibernated, or the endpoint moved. */
    data class Unreachable(override val message: String, override val trace: DesktopTrace) : DesktopFailure {
        override val retryable: Boolean get() = true
    }
}
