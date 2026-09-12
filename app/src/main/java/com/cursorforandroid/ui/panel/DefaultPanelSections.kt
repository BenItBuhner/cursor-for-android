package com.cursorforandroid.ui.panel

import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.projects.ProjectPanelSection

/**
 * The sections the documented API feeds, plus a named placeholder for each Extended-mode section (spec §7), in the
 * panel's order. A later build fills a placeholder by registering a section under the same id — see
 * [PanelRegistry.with] — and every Extended surface keeps its "Needs Extended mode" state while the mode is off.
 */
object DefaultPanelSections {

    val header = PanelSection(
        id = PanelSectionId.Header,
        icon = CursorIcons.Layers,
        hint = { statusLabel(it) },
        expandedByDefault = true,
        content = { state, actions -> OverviewSection(state, actions) },
    )

    /**
     * The question shows when the stream carried one, in either mode; answering it from here is the Extended half
     * (`SubmitInteractionResponseBackgroundComposer`, the `interactions` capability), and the card's chips are choices
     * only then. Without a question, the section names what it would do: the answer's endpoint while the mode is off,
     * or that nothing is waiting.
     */
    val pendingQuestion = PanelSection(
        id = PanelSectionId.PendingQuestion,
        icon = CursorIcons.Bell,
        availability = { capabilities, state ->
            when {
                state.content.pendingQuestion != null -> SectionAvailability.Available
                capabilities.interactions -> SectionAvailability.NotForThisChat("The agent isn't waiting on a question")
                else -> SectionAvailability.RequiresExtended(ANSWER_REASON, ready = true)
            }
        },
        hint = { state ->
            when {
                state.content.pendingQuestion == null -> null
                state.content.pendingQuestionCallId?.let { it in state.controls.answeredCallIds } == true -> "Answered"
                else -> "Waiting"
            }
        },
        expandedByDefault = true,
        content = { state, actions -> PendingQuestionSection(state, actions) },
    )

    /**
     * The pull request's files once it has them; else the branch's diff against its base from the account (Extended,
     * `GetBackgroundComposerDiffDetails`), whole; else the edits the stream carried.
     */
    val changes = PanelSection(
        id = PanelSectionId.Changes,
        icon = CursorIcons.Code,
        hint = { state ->
            val files = state.pullRequest.valueOrNull?.files?.size?.takeIf { it > 0 }
                ?: state.branchDiffFiles.size.takeIf { it > 0 }
                ?: state.content.changes.size
            files.takeIf { it > 0 }?.let { "$it ${if (it == 1) "file" else "files"}" }
        },
        expandedByDefault = true,
        onOpen = {
            it.loadPullRequest()
            it.loadDiff()
        },
        content = { state, actions -> ChangesSection(state, actions) },
    )

    val pullRequest = PanelSection(
        id = PanelSectionId.PullRequest,
        icon = CursorIcons.GitPullRequest,
        hint = ::pullRequestHint,
        onOpen = { it.loadPullRequest() },
        content = { state, actions -> PullRequestSection(state, actions) },
    )

    val files = PanelSection(
        id = PanelSectionId.Files,
        icon = CursorIcons.Folder,
        hint = { state -> state.content.touched.size.takeIf { it > 0 }?.let { "$it touched" } },
        content = { state, actions -> FilesSection(state, actions) },
    )

    val media = PanelSection(
        id = PanelSectionId.Media,
        icon = CursorIcons.Image,
        hint = { state -> mediaTiles(state).size.takeIf { it > 0 }?.toString() },
        onOpen = { it.loadArtifacts() },
        content = { state, actions -> MediaSection(state, actions) },
    )

    val artifacts = PanelSection(
        id = PanelSectionId.Artifacts,
        icon = CursorIcons.Paperclip,
        hint = { state -> state.artifacts.valueOrNull?.size?.takeIf { it > 0 }?.toString() },
        onOpen = { it.loadArtifacts() },
        content = { state, actions -> ArtifactsSection(state, actions) },
    )

    /**
     * The account's queue, steering and the run's controls, behind the `accountQueue` and `steering` capabilities:
     * [QueueSection]. In default mode the queue stays on the device, above the composer, and this names what the mode adds.
     */
    val queue = PanelSection(
        id = PanelSectionId.Queue,
        icon = CursorIcons.Clock,
        availability = { capabilities, _ -> if (capabilities.accountQueue || capabilities.steering) SectionAvailability.Available else SectionAvailability.RequiresExtended(QUEUE_REASON, ready = true) },
        hint = ::queueHint,
        onOpen = { it.refreshQueue() },
        content = { state, actions -> QueueSection(state, actions) },
    )

    /**
     * The Project section is the Projects work's [ProjectPanelSection], mounted here behind the `projects` capability:
     * a coordinator's primaries and actions, a primary's way back to its Project, or "not part of a Project". It
     * owns its view model, so it needs the graph the panel lives in ([LocalPanelGraph]) and the host's navigation.
     */
    val project = PanelSection(
        id = PanelSectionId.Project,
        icon = CursorIcons.Lightning,
        availability = { capabilities, _ -> if (capabilities.projects) SectionAvailability.Available else SectionAvailability.RequiresExtended(PROJECT_REASON, ready = true) },
        hint = { state -> state.agent?.let { if (it.isProjectRoot) "Coordinator" else it.parent?.let { "In a Project" } } },
        content = { state, actions ->
            val graph = LocalPanelGraph.current
            if (graph == null) {
                EmptyRow("The Project section needs the app to render", "Nothing to show outside a running chat.")
            } else {
                ProjectPanelSection(graph, state.agentId, onOpenAgent = actions::openAgent, onOpenProject = actions::openProject)
            }
        },
    )

    /**
     * A Remote Control chat's machine — name, connection state, the documented floors — from the fleet endpoint in
     * either mode; the agent's VM desktop over noVNC behind the `remoteDesktop` capability. The section is always
     * there: for a cloud chat with the mode off, its body is the named "Needs Extended mode" state.
     */
    val remote = PanelSection(
        id = PanelSectionId.Remote,
        icon = CursorIcons.Desktop,
        hint = { state ->
            val agent = state.agent
            when {
                agent == null -> null
                agent.envType == EnvType.MACHINE -> state.machine.valueOrNull?.let { if (it.connected) "Remote Control · online" else "Remote Control · offline" } ?: "Remote Control"
                state.desktop is DesktopState.Open -> "Desktop open"
                !state.capabilities.remoteDesktop -> "Extended mode"
                else -> "Desktop"
            }
        },
        onOpen = { it.loadMachine() },
        content = { state, actions -> RemoteSection(state, actions) },
    )

    val usage = PanelSection(
        id = PanelSectionId.Usage,
        icon = CursorIcons.Database,
        hint = { state -> state.usage.valueOrNull?.total?.total?.takeIf { it > 0 }?.let { TokenUsage.format(it) + " tokens" } },
        onOpen = { it.loadUsage() },
        content = { state, actions -> UsageSection(state, actions) },
    )

    val share = PanelSection(
        id = PanelSectionId.Share,
        icon = CursorIcons.Link,
        content = { state, actions -> ShareSection(state, actions) },
    )

    val all: List<PanelSection> = listOf(header, pendingQuestion, changes, pullRequest, files, media, artifacts, queue, project, remote, usage, share)

    const val QUEUE_REASON = "The account's follow-up queue, steering a running turn, pausing and resuming all go through undocumented endpoints (ListPendingFollowups, InjectBackgroundComposerContext, PauseBackgroundComposer); the queue stays on this device meanwhile"
    const val PROJECT_REASON = "A Project's workers, side chats and shared context come from undocumented endpoints (ListWorkersForManager, ListBackgroundComposerChildren, the Agent Store)"
}
