package com.cursorforandroid.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent

/**
 * The panel's sections, top to bottom: the set cursor.com/agents keeps beside a conversation — its facts, the code
 * changes, the pull request under review, the files, the artifacts — plus the chat's side chats, its Project where
 * it has one, and the usage the documented API reports. The queue is not a section: queued follow-ups sit above the
 * composer, where Cursor's own clients put them (see `QueuedFollowUps`).
 */
enum class PanelSectionId(val title: String) {
    Header("Overview"),
    PendingQuestion("Pending question"),
    Changes("Changes"),
    PullRequest("Pull request"),
    Files("Files"),
    Artifacts("Artifacts"),
    SideChats("Side chats"),
    Project("Project"),
    Usage("Usage"),
}

/**
 * Whether a section can show anything right now. Every section has a named degraded state rather than a blank: a
 * private surface it needs is off ([RequiresExtended]), or this chat has nothing for it ([NotForThisChat]).
 */
sealed interface SectionAvailability {
    data object Available : SectionAvailability

    /**
     * The section reads or writes through Cursor's undocumented endpoints, which Extended mode gates. [reason] says
     * what it would show; [ready] is false while no build carries the section even with the mode on. A section in
     * this state is left out of the panel rather than shown as a placeholder (see [PanelRegistry.shown]).
     */
    data class RequiresExtended(val reason: String, val ready: Boolean = false) : SectionAvailability

    data class NotForThisChat(val reason: String) : SectionAvailability
}

/** What a section can ask the panel to do; the panel wires these to the view model and the platform. */
interface PanelActions {
    fun loadPullRequest(force: Boolean = false)
    fun loadArtifacts(force: Boolean = false)
    fun loadUsage(force: Boolean = false)
    fun browse(path: String = "", force: Boolean = false)
    fun browseUp()
    fun openRepoFile(path: String)
    fun openTouched(path: String)
    fun openChange(change: TranscriptContent.FileChange)
    fun closeFile()
    fun openUrl(url: String)
    fun copyText(text: String, confirmation: String = "Copied")
    fun shareText(text: String)
    fun openArtifact(artifact: Artifact)
    /** Navigates to another chat — a subagent, a Project's primary or its coordinator, a side chat; a no-op where the host cannot navigate. */
    fun openAgent(agentId: String)
    /** The panel's own toast. */
    fun notify(message: String)
    /** The reader opened or closed a section; remembered for the panel's life (see `PanelState.expandedSections`). */
    fun setSectionExpanded(id: PanelSectionId, expanded: Boolean)

    // -- side chats ---------------------------------------------------------------------------------------------------

    /** Re-reads the chat's side chats from the account (`ListBackgroundComposerChildren`, Extended mode). */
    fun refreshSideChats()
    /** Branches a side chat off this chat (`StartSideChatBackgroundComposer`, Extended mode); [name] is optional. */
    fun startSideChat(name: String?)

    // -- the agent's VM and the account (Extended mode) ---------------------------------------------------------------

    /** The branch's diff against its base (`GetBackgroundComposerDiffDetails`). */
    fun loadDiff(force: Boolean = false)
    fun openBranchDiffFile(file: AgentDiffFile)
    /** The agent's workspace tree (`ListWorkspaceFiles`), walked locally; a file opens through `ReadBinaryFile`. */
    fun loadWorkspace(force: Boolean = false)
    fun browseWorkspace(path: String = "")
    fun browseWorkspaceUp()
    fun openWorkspaceFile(path: String)
    /** A Remote Control chat's machine, from the fleet endpoint. */
    fun loadMachine(force: Boolean = false)
    /** The agent's desktop (`GetMachine`, then noVNC in a WebView), viewing or in control. */
    fun openDesktop(viewOnly: Boolean = true)
    fun setDesktopViewOnly(viewOnly: Boolean)
    fun closeDesktop()
    /** Opens the agent's pull request from here (`MakePRBackgroundComposer`). */
    fun createPullRequest()

    // The chat's controls on the account (Extended mode; see SteeringRepository). Each reports its outcome through [notify].
    /** Answers the `ask_question` call [callId] the agent is waiting on. */
    fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>)
    fun pauseRun()
    fun resumeRun()
    /** The documented cancel of the run under way. */
    fun stopRun()
    /** Wakes the chat's machine ahead of a follow-up. */
    fun wake()

    // -- the panel's tabs (see PanelTab) --------------------------------------------------------------------------------

    fun selectTab(tab: PanelTab)
    /** Closes a document or side chat tab; the fixed tabs stay. */
    fun closeTab(tab: PanelTab)
    /** Opens the side chat [agentId] beside this conversation, as a tab of the panel. */
    fun openSideChat(agentId: String)
    /** Opens [path] of [store] as a document tab (Preview for markdown, Source for the rest). */
    fun openDocument(store: AgentStoreRef, path: String)
    fun openAllFiles()
    fun openProjectTab()
    /** The Chat tab with [section] open: where a pill above the composer sends the reader. */
    fun showSection(section: PanelSectionId)

    // -- Context: the Project's Agent Store and the user's (Extended mode) ---------------------------------------------

    /** Reads which stores the chat has, the Project's notes and the Recents row; folders are listed as they are opened. */
    fun loadContext(force: Boolean = false)
    /** Opens or closes a folder of the All Files tree, listing it on first open. */
    fun toggleFolder(store: AgentStoreRef, path: String)
    /** Reads (or re-reads) the document behind [tab]. */
    fun loadDocument(tab: PanelTab.Document, force: Boolean = false)
    /** Preview (rendered markdown) or Source (the text) for a document tab. */
    fun setDocumentSource(tab: PanelTab.Document, source: Boolean)

    companion object {
        /** Does nothing; for previews and tests of the sections' rendering. */
        val None: PanelActions = object : PanelActions {
            override fun loadPullRequest(force: Boolean) = Unit
            override fun loadArtifacts(force: Boolean) = Unit
            override fun loadUsage(force: Boolean) = Unit
            override fun browse(path: String, force: Boolean) = Unit
            override fun browseUp() = Unit
            override fun openRepoFile(path: String) = Unit
            override fun openTouched(path: String) = Unit
            override fun openChange(change: TranscriptContent.FileChange) = Unit
            override fun closeFile() = Unit
            override fun openUrl(url: String) = Unit
            override fun copyText(text: String, confirmation: String) = Unit
            override fun shareText(text: String) = Unit
            override fun openArtifact(artifact: Artifact) = Unit
            override fun openAgent(agentId: String) = Unit
            override fun notify(message: String) = Unit
            override fun setSectionExpanded(id: PanelSectionId, expanded: Boolean) = Unit
            override fun refreshSideChats() = Unit
            override fun startSideChat(name: String?) = Unit
            override fun loadDiff(force: Boolean) = Unit
            override fun openBranchDiffFile(file: AgentDiffFile) = Unit
            override fun loadWorkspace(force: Boolean) = Unit
            override fun browseWorkspace(path: String) = Unit
            override fun browseWorkspaceUp() = Unit
            override fun openWorkspaceFile(path: String) = Unit
            override fun loadMachine(force: Boolean) = Unit
            override fun openDesktop(viewOnly: Boolean) = Unit
            override fun setDesktopViewOnly(viewOnly: Boolean) = Unit
            override fun closeDesktop() = Unit
            override fun createPullRequest() = Unit
            override fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>) = Unit
            override fun pauseRun() = Unit
            override fun resumeRun() = Unit
            override fun stopRun() = Unit
            override fun wake() = Unit
            override fun selectTab(tab: PanelTab) = Unit
            override fun closeTab(tab: PanelTab) = Unit
            override fun openSideChat(agentId: String) = Unit
            override fun openDocument(store: AgentStoreRef, path: String) = Unit
            override fun openAllFiles() = Unit
            override fun openProjectTab() = Unit
            override fun showSection(section: PanelSectionId) = Unit
            override fun loadContext(force: Boolean) = Unit
            override fun toggleFolder(store: AgentStoreRef, path: String) = Unit
            override fun loadDocument(tab: PanelTab.Document, force: Boolean) = Unit
            override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) = Unit
        }
    }
}

/**
 * One section of the panel. The registry is a list of these, so a later build adds a section — or replaces one — by
 * registering it under the same id, without touching the panel.
 */
class PanelSection(
    val id: PanelSectionId,
    val icon: ImageVector,
    val title: String = id.title,
    /**
     * Whether the section belongs in this chat's panel at all, given the mode and the chat: a section with nothing
     * to show — no pull request, no artifacts, no question waiting, no Project — is left out rather than rendered
     * as an empty row or a placeholder. What it would have offered stays reachable elsewhere (see [PanelRegistry.shown]).
     */
    val visible: (Capabilities, PanelState) -> Boolean = { _, _ -> true },
    /** What the section needs before it can show anything, given the mode and the chat. */
    val availability: (Capabilities, PanelState) -> SectionAvailability = { _, _ -> SectionAvailability.Available },
    /** A few words beside the title — "3 files", "Open · checks passed" — or null. */
    val hint: (PanelState) -> String? = { null },
    val expandedByDefault: Boolean = false,
    /** Runs when the section is opened: the read it needs. */
    val onOpen: (PanelActions) -> Unit = {},
    val content: @Composable (PanelState, PanelActions) -> Unit,
) {
    /** [visible] for this chat, and not gated on a mode that is off: the rule the panel draws sections by. */
    fun isShown(capabilities: Capabilities, state: PanelState): Boolean =
        visible(capabilities, state) && availability(capabilities, state) !is SectionAvailability.RequiresExtended
}

/** The panel's sections in order. Immutable; [with] returns a registry with one section replaced or appended. */
class PanelRegistry(val sections: List<PanelSection>) {
    fun with(section: PanelSection): PanelRegistry {
        val index = sections.indexOfFirst { it.id == section.id }
        return PanelRegistry(if (index < 0) sections + section else sections.toMutableList().also { it[index] = section })
    }

    operator fun get(id: PanelSectionId): PanelSection? = sections.firstOrNull { it.id == id }

    /**
     * The sections the panel draws for this chat, in order: each one that has something to show ([PanelSection.visible])
     * and is not waiting on a mode that is off. A section a private surface gates is left out while the surface is
     * off rather than shown as an "Extended mode" placeholder; turning the mode on in Settings brings it in. In a
     * Project coordinator's chat the Project section — the Project's one surface — follows the Overview, ahead of
     * the code sections a coordinator, which delegates rather than writes, rarely fills.
     */
    fun shown(capabilities: Capabilities, state: PanelState): List<PanelSection> {
        val shown = sections.filter { it.isShown(capabilities, state) }
        if (state.agent?.looksLikeProject != true) return shown
        val project = shown.firstOrNull { it.id == PanelSectionId.Project } ?: return shown
        val rest = shown - project
        val header = rest.indexOfFirst { it.id == PanelSectionId.Header }
        return rest.toMutableList().also { it.add(header + 1, project) }
    }

    companion object {
        /** The default panel: what the documented API feeds, and the Extended-mode sections the mode brings in. */
        fun default(): PanelRegistry = PanelRegistry(DefaultPanelSections.all)
    }
}
