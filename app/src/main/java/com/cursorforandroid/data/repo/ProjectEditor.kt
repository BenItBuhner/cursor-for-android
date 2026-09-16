package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectCreationApi
import com.cursorforandroid.data.api.ProjectDraft
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * Creating a Project and editing one — its name, its icon and colour — from anywhere a Project is shown, in
 * Extended mode. Each change goes to the account and lands on the row and in the root registry in the same breath
 * (see [AgentRepository.applyAccountSnapshots]): the sidebar's Projects group is drawn from the registry, so a new
 * Project is listed the moment the account answers, not after the next discovery pass, and a new look or name shows
 * at once rather than after the next account round. The account's own record replaces these facts when it is next
 * read; until then they are the row's.
 *
 * What is editable follows the account service's RPCs: `RenameBackgroundComposer` for the name,
 * `UpdateProjectAppearance` for the look. The repositories a Project owns are set when it is created (the start
 * request's `devcontainer_starting_point`, `repo_config` for several) and the service offers no RPC to change them
 * afterwards — the desktop Agents Window (3.20.21) has none either — so they are shown, not edited.
 */
class ProjectEditor(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val projects: ProjectRepository,
    private val creation: () -> ProjectCreationApi,
    private val capabilities: suspend () -> Capabilities,
    private val now: () -> Long = AppClock::now,
    private val random: Random = Random.Default,
) {

    /**
     * Creates the Project the desktop's way (see `ConnectProjectCreationApi`) and registers it at once. Returns the
     * new coordinator chat's id, for the caller to open. A blank [name] is the desktop's "New Project"; a null
     * [appearance] is the desktop's random default pair (`zri(A_t)`). In demo mode the Project exists on this device
     * only.
     */
    suspend fun create(name: String, appearance: ProjectAppearance?, repoUrls: List<String>): Result<String> = runCatching {
        val draft = ProjectDraft(name.trim().ifEmpty { DEFAULT_NAME }, appearance ?: defaultAppearance(), repoUrls.map { it.trim() }.filter { it.isNotEmpty() })
        val record = if (session.isDemo) {
            ComposerSnapshot(draft.projectId, name = draft.name, archived = false, isProject = true, projectAppearance = draft.appearance, source = AgentSource.API, status = RunStatus.CREATING, record = RecordFields(projectMetadata = "{}"), activityAtMillis = now(), createdAtMillis = now())
        } else {
            if (!capabilities().projects) throw IllegalStateException(NEEDS_EXTENDED_MODE)
            creation().createProject(draft)
        }
        register(record, draft)
        record.id
    }

    /** The record on the row and in the registry now; the row itself from the public API, or stood in until it is known there. */
    private suspend fun register(record: ComposerSnapshot, draft: ProjectDraft) {
        val flagged = if (record.isProject) record else record.copy(isProject = true, record = record.record ?: RecordFields(projectMetadata = "{}"))
        agents.applyAccountSnapshots(listOf(flagged))
        val fetched = if (session.isDemo) null else try {
            agents.loadDetail(record.id).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (fetched == null) agents.upsert(standIn(flagged, draft))
        // The row from the public API knows nothing of Projects; the record dresses it.
        agents.applyAccountSnapshots(listOf(flagged))
    }

    /** A row for a Project the public list does not carry yet: what was asked for, running its kickoff. */
    private fun standIn(record: ComposerSnapshot, draft: ProjectDraft): Agent {
        val at = now()
        return Agent(
            id = record.id,
            name = record.name?.trim()?.takeIf { it.isNotEmpty() } ?: draft.name,
            lifecycle = AgentLifecycle.ACTIVE,
            runStatus = record.status ?: RunStatus.CREATING,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/${record.id}",
            createdAtMillis = record.createdAtMillis ?: at,
            updatedAtMillis = record.listedAtMillis ?: at,
            activityAtMillis = record.activityAtMillis ?: at,
            latestRunId = null,
            repoUrl = draft.repoUrls.firstOrNull(),
            startingRef = null,
            source = record.source ?: AgentSource.API,
            isProject = true,
            projectAppearance = record.projectAppearance ?: draft.appearance,
            scopeSignal = LineageSignal.ACCOUNT_RECORD,
            record = record.record ?: RecordFields(projectMetadata = "{}"),
        )
    }

    /**
     * Renames the Project (`RenameBackgroundComposer`) and, when [appearance] differs from the row's, restyles it
     * (`UpdateProjectAppearance`); both show on the row and in the registry at once. A blank [name] keeps the current.
     */
    suspend fun update(projectId: String, name: String, appearance: ProjectAppearance?): Result<Unit> = runCatching {
        val current = agents.agent(projectId) ?: throw IllegalStateException("This Project isn't loaded.")
        val trimmed = name.trim()
        if (trimmed.length > MAX_NAME_LENGTH) throw IllegalArgumentException("Name must be $MAX_NAME_LENGTH characters or less.")
        val renamed = trimmed.isNotEmpty() && trimmed != current.name
        val restyled = appearance != null && appearance != current.projectAppearance
        if (!renamed && !restyled) return@runCatching
        var finalName = current.name
        var finalLook = current.projectAppearance
        if (session.isDemo) {
            if (renamed) finalName = trimmed
            if (restyled) finalLook = appearance
        } else {
            if (!capabilities().projects) throw IllegalStateException(NEEDS_EXTENDED_MODE)
            if (renamed) finalName = creation().renameProject(projectId, trimmed)
            if (restyled) {
                projects.updateAppearance(projectId, appearance!!).getOrThrow()
                finalLook = agents.agent(projectId)?.projectAppearance ?: appearance
            }
        }
        // The registry's entry — what the Projects group is drawn from — learns the change with the row.
        agents.applyAccountSnapshots(listOf(snapshotOf(agents.agent(projectId) ?: current, finalName, finalLook)))
    }

    /** The row's facts as an account record would carry them, with the name and look just set. */
    private fun snapshotOf(agent: Agent, name: String, appearance: ProjectAppearance?) = ComposerSnapshot(
        id = agent.id,
        name = name,
        archived = agent.isArchived,
        isProject = true,
        projectAppearance = appearance,
        record = agent.record ?: RecordFields(projectMetadata = "{}"),
        parent = agent.parent,
        source = agent.source,
        hasPendingInteraction = agent.hasPendingInteraction,
        model = agent.accountModel,
        activityAtMillis = agent.activityAtMillis,
        createdAtMillis = agent.createdAtMillis.takeIf { it > 0 },
    )

    /** The desktop's default look for a Project whose creator chose none: a random icon from its pool, a random tone. */
    fun defaultAppearance(): ProjectAppearance = ProjectAppearance(DEFAULT_ICONS[random.nextInt(DEFAULT_ICONS.size)], DEFAULT_COLORS[random.nextInt(DEFAULT_COLORS.size)])

    companion object {
        const val DEFAULT_NAME = "New Project"
        const val MAX_NAME_LENGTH = 100
        const val NEEDS_EXTENDED_MODE = "Projects are managed through the account service, which Extended mode turns on in Settings."

        /** The desktop's `A_t`: the icons a Project without a chosen look is given one of at random. */
        val DEFAULT_ICONS: List<String> = listOf(
            "code", "terminal", "bug", "git-branch", "brackets-curly", "chip", "folder", "book-open", "file-text", "files", "library", "globe",
            "browser", "link", "chat-bubbles", "envelope", "megaphone", "paperplane", "briefcase", "calendar", "board-kanban", "list-todo",
            "target", "flag", "database", "chart-bars", "graph-line", "table", "atom", "beaker", "microscope", "brain", "palette", "brush",
            "camera", "image", "music", "magic-wand", "cloud", "server", "shield", "lightning", "rocket", "sparkle", "star", "moon", "heart", "smiley-happy",
        )

        /** The desktop's `TIr`: the tones the same default draws from. */
        val DEFAULT_COLORS: List<String> = listOf("default", "green", "cyan", "blue", "purple", "magenta", "orange", "yellow", "red", "brand")
    }
}
