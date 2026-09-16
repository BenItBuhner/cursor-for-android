package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The desktop's rules, taken from the Agents Window (Cursor 3.20.21): who may open one, how the URL is built, what GetMachine's refusals mean, and what a shared report may say. */
class RemoteDesktopTest {

    private val cloud = Agent(
        id = "bc-1",
        name = "Chat",
        lifecycle = AgentLifecycle.ACTIVE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/bc-1",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        latestRunId = null,
        repoUrl = null,
        startingRef = null,
    )

    @Test
    fun `the desktop is offered for a cloud chat, finished or running, and for a Project's workers`() {
        assertThat(DesktopEligibility.canOpen(cloud)).isTrue()
        assertThat(DesktopEligibility.canOpen(cloud.copy(runStatus = RunStatus.RUNNING))).isTrue()
        assertThat(DesktopEligibility.canOpen(cloud.copy(runStatus = null))).isTrue()
        // A worker has a VM of its own (GetMachine says WORKSPACE_ON_COORDINATOR for the ones that do not).
        assertThat(DesktopEligibility.canOpen(cloud.copy(parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER)))).isTrue()
        assertThat(DesktopEligibility.reasonNotOffered(cloud)).isNull()
    }

    @Test
    fun `it is not offered where GetMachine would only refuse - coordinators, side chats, subagent rows, archived or expired chats, machines`() {
        assertThat(DesktopEligibility.canOpen(null)).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(isProject = true))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(parent = AgentParent("bc-p", AgentParentKind.SIDE_CHAT)))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(parent = AgentParent("bc-p", AgentParentKind.SUBAGENT)))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(lifecycle = AgentLifecycle.ARCHIVED))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(runStatus = RunStatus.EXPIRED))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(envType = EnvType.MACHINE, envName = "studio"))).isFalse()
        assertThat(DesktopEligibility.canOpen(cloud.copy(envType = EnvType.POOL, envName = "pool"))).isFalse()
        assertThat(DesktopEligibility.reasonNotOffered(cloud.copy(isProject = true))).contains("coordinator")
        assertThat(DesktopEligibility.reasonNotOffered(cloud.copy(envType = EnvType.MACHINE))).contains("your own machine")
        assertThat(DesktopEligibility.reasonNotOffered(cloud.copy(lifecycle = AgentLifecycle.ARCHIVED))).contains("archived")
    }

    @Test
    fun `the websockify URL is the Agents Window's, default port first, and the host alone is what a report may name`() {
        val pod = MachineReference.Pod(podId = "pod-1", tenantId = "t-9", cluster = "us-east1", token = "tok.secret")
        assertThat(pod.websockifyUrl(26058)).isEqualTo("wss://t-9-pod-1-26058.us-east1.cursorvm.com:443/websockify?network_token=tok.secret&resume_lower_s=900&resume_upper_s=18000")
        assertThat(pod.candidateUrls().map { it.substringBefore(".us-east1").substringAfterLast('-') }).containsExactly("26058", "6080").inOrder()
        assertThat(MachineReference.Pod.DEFAULT_PORT).isEqualTo(26058)
        assertThat(MachineReference.Pod.FALLBACK_PORT).isEqualTo(6080)
        assertThat(pod.host(6080)).isEqualTo("t-9-pod-1-6080.us-east1.cursorvm.com")
        assertThat(pod.toString()).doesNotContain("secret")
    }

    @Test
    fun `GetMachine's reason codes are read off the error's message, as machine-load-failure does`() {
        assertThat(MachineUnavailableReason.parse("[failed_precondition] cursorServerUrlReason=MACHINE_NOT_PROVISIONED")).isEqualTo(MachineUnavailableReason.MACHINE_NOT_PROVISIONED)
        assertThat(MachineUnavailableReason.parse("cursorServerUrlReason=NO_POD_INFO_YET; retry")?.isPreparing).isTrue()
        assertThat(MachineUnavailableReason.parse("cursorServerUrlReason=AGENT_EXPIRED")?.isExpired).isTrue()
        assertThat(MachineUnavailableReason.parse("cursorServerUrlReason=WORKFLOW_ERROR")?.isExpired).isTrue()
        assertThat(MachineUnavailableReason.parse("cursorServerUrlReason=WORKSPACE_ON_COORDINATOR")).isEqualTo(MachineUnavailableReason.WORKSPACE_ON_COORDINATOR)
        assertThat(MachineUnavailableReason.parse("cursorServerUrlReason=SOMETHING_NEW")).isNull()
        assertThat(MachineUnavailableReason.parse("pod is hibernated")).isNull()
        assertThat(MachineUnavailableReason.parse(null)).isNull()
    }

    @Test
    fun `the trace keeps steps in order, closes the running one, and its report carries timings, codes and the host but never a token`() {
        var trace = DesktopTrace("bc-1", appVersion = "0.3.20")
        trace = trace.begin(DesktopTrace.GET_MACHINE, 1_000)
        assertThat(trace.current?.name).isEqualTo(DesktopTrace.GET_MACHINE)
        trace = trace.end(DesktopTrace.GET_MACHINE, 1_412, "pod pod-1 in us-east1 (desktop ticket)")
        trace = trace.begin("probe t-9-pod-1-26058.us-east1.cursorvm.com", 1_412)
        trace = trace.end("probe t-9-pod-1-26058.us-east1.cursorvm.com", 1_900, "HTTP 403", failed = true)
        trace = trace.begin("probe t-9-pod-1-6080.us-east1.cursorvm.com", 1_900).withEndpoint("t-9-pod-1-6080.us-east1.cursorvm.com", 6080)
        trace = trace.end("probe t-9-pod-1-6080.us-east1.cursorvm.com", 2_300, "handshake accepted")
        trace = trace.begin(DesktopTrace.SOCKET, 2_300)
        trace = trace.end(DesktopTrace.SOCKET, 12_300, "timed out after 10 s", failed = true)

        assertThat(trace.steps.map { it.name }).containsExactly(DesktopTrace.GET_MACHINE, "probe t-9-pod-1-26058.us-east1.cursorvm.com", "probe t-9-pod-1-6080.us-east1.cursorvm.com", DesktopTrace.SOCKET).inOrder()
        assertThat(trace.steps[0].durationMillis).isEqualTo(412)
        assertThat(trace.lastFailed?.name).isEqualTo(DesktopTrace.SOCKET)
        assertThat(trace.current).isNull()
        val report = trace.report()
        assertThat(report).contains("app: 0.3.20")
        assertThat(report).contains("endpoint: t-9-pod-1-6080.us-east1.cursorvm.com:6080 (websockify)")
        assertThat(report).contains("GetMachine: pod pod-1 in us-east1 (desktop ticket) (412 ms)")
        assertThat(report).contains("probe t-9-pod-1-26058.us-east1.cursorvm.com: HTTP 403 (488 ms) FAILED")
        assertThat(report).contains("socket handshake: timed out after 10 s (10000 ms) FAILED")
        assertThat(report).doesNotContain("network_token")
        assertThat(report).doesNotContain("?")

        // Ending a step that was never begun still records it, so a report is never missing the step that failed.
        val orphan = DesktopTrace("bc-1").end(DesktopTrace.PAGE, 5, "HTTP 404 for /core/rfb.js", failed = true)
        assertThat(orphan.steps.single().failed).isTrue()
        assertThat(orphan.lastFailed?.outcome).isEqualTo("HTTP 404 for /core/rfb.js")
    }

    @Test
    fun `a session and a failure carry the trace, and a session's string leaves the URL out`() {
        val trace = DesktopTrace("bc-1").begin(DesktopTrace.GET_MACHINE, 0).end(DesktopTrace.GET_MACHINE, 1, "pod")
        val session = DesktopSession("bc-1", "wss://h:443/websockify?network_token=secret", viewOnly = false, port = 26058, trace = trace)
        assertThat(session.toString()).doesNotContain("secret")
        assertThat(session.trace.steps).hasSize(1)
        assertThat(DesktopFailure.Unreachable("no", trace).retryable).isTrue()
        assertThat(DesktopFailure.Refused("no", trace, endpointChanged = true).retryable).isFalse()
        assertThat(DesktopFailure.Refused("no", trace, statusCode = 403).retryable).isFalse()
        assertThat(DesktopFailure.Refused("no", trace, statusCode = 500).retryable).isTrue()
        assertThat(DesktopFailure.NotAvailable("no", trace).retryable).isFalse()
        assertThat(DesktopFailure.Preparing("soon", trace, MachineUnavailableReason.NO_POD_INFO_YET).retryable).isTrue()
    }
}
