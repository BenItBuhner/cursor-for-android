package com.cursorforandroid.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TranscriptContent

/** The panel's sections, top to bottom, by the names the spec gives them (§7). */
enum class PanelSectionId(val title: String) {
    Header("Overview"),
    PendingQuestion("Pending question"),
    Changes("Changes"),
    PullRequest("Pull request"),
    Files("Files"),
    Media("Images and media"),
    Artifacts("Artifacts"),
    Queue("Queue and steering"),
    Project("Project"),
    Remote("Remote"),
    Usage("Usage"),
    Share("Share"),
}

/**
 * Whether a section can show anything right now. Every section has a named degraded state rather than a blank: a
 * private surface it needs is off ([RequiresExtended]), or this chat has nothing for it ([NotForThisChat]).
 */
sealed interface SectionAvailability {
    data object Available : SectionAvailability

    /**
     * The section reads or writes through Cursor's undocumented endpoints, which Extended mode gates. [reason] says
     * what it would show; [ready] is false while no build carries the section even with the mode on.
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
    /** The panel's own toast. */
    fun notify(message: String)

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
            override fun notify(message: String) = Unit
        }
    }
}

/**
 * One section of the panel. The registry is a list of these, so a later build adds an Extended-mode section — or
 * fills in a placeholder — by registering a section under the same id, without touching the panel.
 */
class PanelSection(
    val id: PanelSectionId,
    val icon: ImageVector,
    val title: String = id.title,
    /** What the section needs before it can show anything, given the mode and the chat. */
    val availability: (Capabilities, PanelState) -> SectionAvailability = { _, _ -> SectionAvailability.Available },
    /** A few words beside the title — "3 files", "Open · checks passed" — or null. */
    val hint: (PanelState) -> String? = { null },
    val expandedByDefault: Boolean = false,
    /** Runs when the section is opened: the read it needs. */
    val onOpen: (PanelActions) -> Unit = {},
    val content: @Composable (PanelState, PanelActions) -> Unit,
)

/** The panel's sections in order. Immutable; [with] returns a registry with one section replaced or appended. */
class PanelRegistry(val sections: List<PanelSection>) {
    fun with(section: PanelSection): PanelRegistry {
        val index = sections.indexOfFirst { it.id == section.id }
        return PanelRegistry(if (index < 0) sections + section else sections.toMutableList().also { it[index] = section })
    }

    operator fun get(id: PanelSectionId): PanelSection? = sections.firstOrNull { it.id == id }

    companion object {
        /** The default-mode panel: what the documented API feeds, and a named placeholder for every Extended-mode section. */
        fun default(): PanelRegistry = PanelRegistry(DefaultPanelSections.all)
    }
}
