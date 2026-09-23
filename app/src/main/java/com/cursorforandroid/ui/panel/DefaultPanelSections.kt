package com.cursorforandroid.ui.panel

import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.projects.projectPanelItems

/**
 * The panel's sections in order, each with the rule that puts it in a chat's panel or leaves it out. The set and
 * the order follow what cursor.com/agents keeps beside a conversation: the chat's facts, its code changes, the pull
 * request under review, its files and artifacts — then what this app adds (side chats, the Project, usage). A
 * section with nothing to show for the chat is left out; one a private surface gates stays out while the surface is
 * off. What the panel no longer carries stays reachable: queued follow-ups above the composer, the agent's desktop
 * and the share sheet from the chat's header menu, a Remote Control machine's state in the Overview.
 */
object DefaultPanelSections {

    /** The chat's facts, compact: status, repository, branch, model, when and how long, what changed; the run's controls in Extended mode. */
    val header = PanelSection(
        id = PanelSectionId.Header,
        icon = CursorIcons.Layers,
        hint = { statusLabel(it) },
        expandedByDefault = true,
        // A Remote Control chat's machine is one of its facts; the read is a no-op for a chat in the cloud.
        onOpen = { it.loadMachine() },
        content = { state, actions -> OverviewSection(state, actions) },
    )

    /**
     * The question the agent is waiting on, while there is one — in either mode; answering it from here is the
     * Extended half (`SubmitInteractionResponseBackgroundComposer`, the `interactions` capability), read-only with a
     * link to cursor.com otherwise. Without a question there is nothing to show, and the section is not there.
     */
    val pendingQuestion = PanelSection(
        id = PanelSectionId.PendingQuestion,
        icon = CursorIcons.Bell,
        visible = { _, state -> state.content.pendingQuestion != null },
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
     * `GetBackgroundComposerDiffDetails`), whole; else the edits the stream carried. Always there: it is what the
     * panel is for, and "no changes yet" is a fact about the chat.
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

    /** The pull request verbatim while the chat has one — or, in Extended mode, the offer to open one from here. */
    val pullRequest = PanelSection(
        id = PanelSectionId.PullRequest,
        icon = CursorIcons.GitPullRequest,
        visible = { capabilities, state -> state.hasPullRequest || canCreatePullRequest(capabilities, state) },
        hint = ::pullRequestHint,
        onOpen = { it.loadPullRequest() },
        content = { state, actions -> PullRequestSection(state, actions) },
    )

    /**
     * The files the agent touched, the repository's tree at the branch, and (Extended) the live workspace. Left out
     * for a chat with none of the three: nothing touched, no repository to browse, no VM to list.
     */
    val files = PanelSection(
        id = PanelSectionId.Files,
        icon = CursorIcons.Folder,
        visible = { capabilities, state -> filesTabs(capabilities, state).isNotEmpty() },
        hint = { state -> state.content.touched.size.takeIf { it > 0 }?.let { "$it touched" } },
        content = { state, actions -> FilesSection(state, actions) },
    )

    /**
     * Everything the chat produced or was given to look at — generated images, recordings, published screenshots,
     * the images attached to prompts — as a gallery, then the artifacts that are not pictures as a list. There once
     * the chat has any of them (or the list could not be read, which is worth a retry); not before.
     */
    val artifacts = PanelSection(
        id = PanelSectionId.Artifacts,
        icon = CursorIcons.Paperclip,
        visible = { _, state -> artifactCount(state) > 0 || state.artifacts is RemoteLoad.Failed },
        hint = { state -> artifactCount(state).takeIf { it > 0 }?.toString() },
        onOpen = { it.loadArtifacts() },
        content = { state, actions -> ArtifactsSection(state, actions) },
    )

    /**
     * The chats branched off this one, for any chat — as in the Agents Window, which lists every cloud chat's side
     * chats and offers "Ask in Side Chat" on each: the ones the list knows in either mode; in Extended mode read from
     * the account (`ListBackgroundComposerChildren`) and started from here (`StartSideChatBackgroundComposer`). The
     * documented API has no side-chat surface, so with the mode off and none known there is nothing to show or to
     * do, and the section is not there.
     */
    val sideChats = PanelSection(
        id = PanelSectionId.SideChats,
        icon = CursorIcons.Ask,
        visible = { capabilities, state -> state.sideChats.isNotEmpty() || canStartSideChat(capabilities, state) },
        hint = { state -> state.sideChats.size.takeIf { it > 0 }?.toString() },
        onOpen = { it.refreshSideChats() },
        content = { state, actions -> SideChatsSection(state, actions) },
    )

    /**
     * The Project section is a Cursor Project's one surface in the app ([projectPanelItems]): in its
     * coordinator's chat, the primaries with their live status and menus, New primary, Adopt a chat, the icon and
     * colour editor, the subagents and the shared context; in a primary's, side chat's or subagent's chat, the way
     * back to the coordinator's chat. It owns its view model, so it needs the graph the panel lives in
     * ([LocalPanelGraph]) and the host's navigation. Any other chat has no Project, and no Project section — a side
     * chat of an ordinary chat included: its way back is the Overview's "Side chat of" row. A coordinator can have
     * hundreds of primaries, so its rows are the panel list's own items ([PanelSection.items]).
     */
    val project = PanelSection(
        id = PanelSectionId.Project,
        icon = CursorIcons.Lightning,
        visible = { _, state -> isProjectScoped(state) },
        hint = { state -> state.agent?.let { if (it.looksLikeProject) "Coordinator" else it.parent?.let { "In a Project" } } },
        expandedByDefault = true,
        items = { state, actions ->
            val graph = LocalPanelGraph.current
            if (graph == null) {
                { sectionRow("project-no-graph") { EmptyRow("The Project section needs the app to render", "Nothing to show outside a running chat.") } }
            } else {
                projectPanelItems(graph, state.agentId, onOpenAgent = actions::openAgent, onNotify = actions::notify)
            }
        },
    )

    /** Token counts from the documented usage endpoint; rarely wanted, so closed until opened. */
    val usage = PanelSection(
        id = PanelSectionId.Usage,
        icon = CursorIcons.Database,
        hint = { state -> state.usage.valueOrNull?.total?.total?.takeIf { it > 0 }?.let { TokenUsage.format(it) + " tokens" } },
        onOpen = { it.loadUsage() },
        content = { state, actions -> UsageSection(state, actions) },
    )

    val all: List<PanelSection> = listOf(header, pendingQuestion, changes, pullRequest, files, artifacts, sideChats, project, usage)

    /** Extended mode can open the pull request from here (`MakePRBackgroundComposer`) for a chat with a branch on a repository. */
    fun canCreatePullRequest(capabilities: Capabilities, state: PanelState): Boolean {
        val agent = state.agent ?: return false
        return capabilities.scmPullRequests && !state.isDemo && agent.hasBranch && agent.repoUrl != null
    }

    /**
     * Extended mode can branch a side chat off any chat that is still active and is not a side chat itself — the two
     * refusals the Agents Window makes before asking the account ("Cannot create a side chat inside a side chat",
     * "This agent is no longer active"). The demo has no account to ask.
     */
    fun canStartSideChat(capabilities: Capabilities, state: PanelState): Boolean {
        val agent = state.agent ?: return false
        return capabilities.projects && !state.isDemo && !agent.isArchived && agent.parent?.kind != AgentParentKind.SIDE_CHAT
    }

    /** The gallery's tiles plus the artifacts that are not pictures: what the Artifacts section has to show. */
    fun artifactCount(state: PanelState): Int = mediaTiles(state).size + artifactFiles(state).size

    /**
     * Whether the chat is part of a Cursor Project: its coordinator, a primary the coordinator created or adopted
     * (its parent is a manager by definition), or a side chat or subagent whose parent the list shows to be a
     * Project — on positive evidence, never on a coordinator's transcript merely mentioning it. A side chat of a
     * chat of the account's own is not in a Project, whatever placed it.
     */
    fun isProjectScoped(state: PanelState): Boolean {
        val agent = state.agent ?: return false
        if (agent.looksLikeProject) return true
        if (!agent.isProjectScopedByEvidence) return false
        return agent.parent?.kind == AgentParentKind.PROJECT_WORKER || state.parentAgent?.looksLikeProject == true
    }
}
