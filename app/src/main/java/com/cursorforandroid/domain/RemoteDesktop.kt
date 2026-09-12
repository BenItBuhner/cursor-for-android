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
     * A Cursor-hosted pod: the desktop is noVNC behind websockify on the pod's `cursorvm.com` host. [token] is what
     * the URL's `network_token` takes — the desktop ticket the call minted when it did, else the pod's network
     * token. Never logged: [toString] leaves it out.
     */
    data class Pod(
        val podId: String,
        val tenantId: String,
        val cluster: String,
        val token: String,
        /** True when the account answered with a per-call desktop ticket rather than the pod's standing token. */
        val ticketMinted: Boolean = false,
    ) : MachineReference {
        /**
         * The websockify URLs to try, in order: the noVNC port first, then the second display path Cursor's bundle
         * also builds (which condition picks it is unverified, so the panel probes both).
         */
        fun candidateUrls(): List<String> = DESKTOP_PORTS.map { port ->
            "wss://$tenantId-$podId-$port.$cluster.cursorvm.com:443/websockify?network_token=$token&resume_lower_s=900&resume_upper_s=18000"
        }

        override fun toString(): String = "Pod(podId=$podId, tenantId=$tenantId, cluster=$cluster, ticketMinted=$ticketMinted)"

        companion object {
            /** noVNC/websockify, then the second display path of the desktop bundle. */
            val DESKTOP_PORTS = listOf(6080, 26058)
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

/** The panel's desktop session: what the WebView connects to and whether the viewer may drive it. */
data class DesktopSession(
    val agentId: String,
    /** The websockify URL that answered the probe; carries the token, so never logged. */
    val url: String,
    val viewOnly: Boolean = true,
    /** The port that answered, for the section's own record. */
    val port: Int? = null,
) {
    override fun toString(): String = "DesktopSession(agentId=$agentId, viewOnly=$viewOnly, port=$port)"
}

/** Why the desktop could not be opened, in words the section shows. */
sealed interface DesktopFailure {
    val message: String

    /** Extended mode is off, or the demo: nothing was called. */
    data class NotAvailable(override val message: String) : DesktopFailure

    /** The chat runs where no desktop can be reached from here (a self-hosted worker), with the worker's own reason when it gave one. */
    data class NoDesktop(override val message: String) : DesktopFailure

    /** `GetMachine` refused or changed shape. */
    data class Refused(override val message: String, val endpointChanged: Boolean = false) : DesktopFailure

    /** Every candidate URL failed the probe: the VM is stopped or hibernated, or the endpoint moved. */
    data class Unreachable(override val message: String) : DesktopFailure
}
