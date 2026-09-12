package com.cursorforandroid.ui.panel

import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.ui.components.CursorIcons

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
     * Read-only in default mode: the question shows when the stream carried one; answering it is the Extended half
     * (`SubmitInteractionResponseBackgroundComposer`), which stays a placeholder here.
     */
    val pendingQuestion = PanelSection(
        id = PanelSectionId.PendingQuestion,
        icon = CursorIcons.Bell,
        availability = { _, state -> if (state.content.pendingQuestion != null) SectionAvailability.Available else SectionAvailability.RequiresExtended(ANSWER_REASON) },
        hint = { if (it.content.pendingQuestion != null) "Waiting" else null },
        expandedByDefault = true,
        content = { state, actions -> PendingQuestionSection(state, actions) },
    )

    val changes = PanelSection(
        id = PanelSectionId.Changes,
        icon = CursorIcons.Code,
        hint = { state ->
            val files = state.pullRequest.valueOrNull?.files?.size?.takeIf { it > 0 } ?: state.content.changes.size
            files.takeIf { it > 0 }?.let { "$it ${if (it == 1) "file" else "files"}" }
        },
        expandedByDefault = true,
        onOpen = { it.loadPullRequest() },
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

    val queue = PanelSection(
        id = PanelSectionId.Queue,
        icon = CursorIcons.Clock,
        availability = { _, _ -> SectionAvailability.RequiresExtended(QUEUE_REASON) },
        content = { state, _ -> PlaceholderSection(QUEUE_REASON, state) },
    )

    val project = PanelSection(
        id = PanelSectionId.Project,
        icon = CursorIcons.Lightning,
        availability = { _, _ -> SectionAvailability.RequiresExtended(PROJECT_REASON) },
        content = { state, _ -> PlaceholderSection(PROJECT_REASON, state) },
    )

    val remote = PanelSection(
        id = PanelSectionId.Remote,
        icon = CursorIcons.Desktop,
        availability = { _, _ -> SectionAvailability.RequiresExtended(REMOTE_REASON) },
        content = { state, _ -> PlaceholderSection(REMOTE_REASON, state) },
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

    const val QUEUE_REASON = "The account's follow-up queue, steering a running turn, pausing and resuming all go through undocumented endpoints (ListPendingFollowups, InjectBackgroundComposerContext, PauseBackgroundComposer)"
    const val PROJECT_REASON = "A Project's workers, side chats and shared context come from undocumented endpoints (ListWorkersForManager, ListBackgroundComposerChildren, the Agent Store)"
    const val REMOTE_REASON = "Viewing or taking control of the agent's desktop needs GetMachine and the VM's VNC endpoint, which are undocumented; Remote Control agents themselves already list and stream here"
}
