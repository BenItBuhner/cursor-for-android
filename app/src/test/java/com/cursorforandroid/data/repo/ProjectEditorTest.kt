package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.ProjectActionsApi
import com.cursorforandroid.data.api.ProjectCreationApi
import com.cursorforandroid.data.api.ProjectDraft
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Creating and editing a Project lands on the row and in the root registry at once — the Projects group is drawn
 * from the registry, so a new Project is listed the moment the account answers and a new name or look shows before
 * the next account round — and goes to the account through the RPCs the desktop uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectEditorTest {

    private class FakeCreation : ProjectCreationApi {
        val drafts = CopyOnWriteArrayList<ProjectDraft>()
        val renames = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var failing: Throwable? = null
        @Volatile var answerWithRecord = true

        override suspend fun createProject(draft: ProjectDraft): ComposerSnapshot {
            failing?.let { throw it }
            drafts += draft
            return if (answerWithRecord) {
                ComposerSnapshot(draft.projectId, name = draft.name, archived = false, isProject = true, projectAppearance = draft.appearance, status = RunStatus.CREATING, record = RecordFields(projectMetadata = """{"appearance":{"icon":"${draft.appearance.icon}","colorId":"${draft.appearance.colorId}"}}"""), activityAtMillis = 1_800_000_000_000L, createdAtMillis = 1_800_000_000_000L)
            } else {
                ComposerSnapshot(draft.projectId, name = draft.name, archived = false, isProject = true, projectAppearance = draft.appearance, record = RecordFields(projectMetadata = "{}"))
            }
        }

        override suspend fun renameProject(projectId: String, name: String): String {
            failing?.let { throw it }
            renames += projectId to name
            return name
        }
    }

    private class FakeActions : ProjectLineageApi, ProjectActionsApi, AgentStoreApi {
        val appearances = CopyOnWriteArrayList<Pair<String, ProjectAppearance>>()
        override suspend fun workersForManager(managerId: String): List<WorkerMembership> = emptyList()
        override suspend fun children(parentId: String): List<ComposerSnapshot> = emptyList()
        override suspend fun createWorker(managerId: String, launch: WorkerLaunch): ComposerSnapshot = ComposerSnapshot(launch.workerId)
        override suspend fun setWorkerManager(workerId: String, managerId: String, spawnKind: WorkerSpawnKind) = Unit
        override suspend fun clearWorkerManager(workerId: String) = Unit
        override suspend fun reparent(agentId: String, parentId: String, subagentType: String?) = Unit
        override suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): ProjectAppearance? {
            appearances += projectId to appearance
            return appearance
        }
        override suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot = ComposerSnapshot("bc-side")
        override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome = SteerOutcome.QUEUED
        override suspend fun pause(agentId: String, runId: String?) = Unit
        override suspend fun resume(agentId: String) = Unit
        override suspend fun storeFor(sourceId: String): String? = null
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String = ""
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead? = null
    }

    private val api = FakeCursorApi()
    private val creation = FakeCreation()
    private val actions = FakeActions()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private var extended = true
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        api.addIdleAgent("bc-1", "Chat 1", "run-1")
        api.addIdleAgent("bc-root", "Cesium billing", "run-root")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, recordOf = { null })

    private fun editor(agents: AgentRepository, random: Random = Random(7)) =
        ProjectEditor(session, agents, ProjectRepository(session, agents, actions, actions = actions, store = actions, scope = scope, pollIntervalMs = 60_000, capabilities = capabilities), creation = { creation }, capabilities = capabilities, random = random)

    private fun projectRows(agents: AgentRepository) =
        AgentListOrganizer.organize(agents.state.value.agents, ListPreferences(), LocalAgentState(), nowMillis = 1_800_000_000_000L, zone = ZoneOffset.UTC)
            .firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows?.map { it.agent.id }.orEmpty()

    @Test
    fun `a created Project is in the registry and the Projects group at once, and the public row is fetched by id`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        val editor = editor(agents)
        // The public API will know the new coordinator by its minted id: the fake learns it when the account answers.
        val id = editor.create("Billing launch", ProjectAppearance("rocket", "brand"), listOf("https://github.com/acme/billing")).getOrThrow()
        val draft = creation.drafts.single()
        assertThat(draft.projectId).isEqualTo(id)
        assertThat(draft.name).isEqualTo("Billing launch")
        assertThat(draft.repoUrls).containsExactly("https://github.com/acme/billing")

        assertThat(agents.knownRoots.value.map { it.id }).contains(id)
        assertThat(agents.knownRoots.value.first { it.id == id }.name).isEqualTo("Billing launch")
        assertThat(agents.knownRoots.value.first { it.id == id }.appearance).isEqualTo(ProjectAppearance("rocket", "brand"))
        val row = agents.agent(id)!!
        assertThat(row.isProjectRoot).isTrue()
        assertThat(row.projectAppearance).isEqualTo(ProjectAppearance("rocket", "brand"))
        // The public API had no row for it yet: the stand-in shows it running its kickoff, on its repository.
        assertThat(row.isRunning).isTrue()
        assertThat(row.repoUrl).isEqualTo("https://github.com/acme/billing")
        assertThat(projectRows(agents)).contains(id)
        // The next refresh keeps it a Project: the record dresses whatever the public list brings.
        api.agents[id] = AgentDto(id = id, name = "Billing launch", status = "ACTIVE", createdAt = "2026-04-13T18:30:00.000Z", updatedAt = "2026-04-13T18:30:00.000Z", latestRunId = null)
        agents.refresh()
        assertThat(agents.agent(id)!!.isProjectRoot).isTrue()
        assertThat(projectRows(agents)).contains(id)
    }

    @Test
    fun `a blank name is New Project and an unchosen look is the desktop's random default`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        val id = editor(agents, Random(3)).create("   ", null, emptyList()).getOrThrow()
        val draft = creation.drafts.single()
        assertThat(draft.name).isEqualTo("New Project")
        assertThat(draft.appearance.icon).isIn(ProjectEditor.DEFAULT_ICONS)
        assertThat(draft.appearance.colorId).isIn(ProjectEditor.DEFAULT_COLORS)
        assertThat(draft.repoUrls).isEmpty()
        assertThat(agents.agent(id)!!.name).isEqualTo("New Project")
    }

    @Test
    fun `renaming and restyling go to the account and show on the row and in the registry at once`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-root", name = "Cesium billing", archived = false, isProject = true, projectAppearance = ProjectAppearance("flag", "green"), record = RecordFields(projectMetadata = "{}"))))
        assertThat(agents.knownRoots.value.first { it.id == "bc-root" }.appearance).isEqualTo(ProjectAppearance("flag", "green"))
        val editor = editor(agents)

        editor.update("bc-root", "Cesium billing launch", ProjectAppearance("rocket", "brand")).getOrThrow()

        assertThat(creation.renames).containsExactly("bc-root" to "Cesium billing launch")
        assertThat(actions.appearances).containsExactly("bc-root" to ProjectAppearance("rocket", "brand"))
        val row = agents.agent("bc-root")!!
        assertThat(row.name).isEqualTo("Cesium billing launch")
        assertThat(row.projectAppearance).isEqualTo(ProjectAppearance("rocket", "brand"))
        assertThat(row.isProjectRoot).isTrue()
        val root = agents.knownRoots.value.first { it.id == "bc-root" }
        assertThat(root.name).isEqualTo("Cesium billing launch")
        assertThat(root.appearance).isEqualTo(ProjectAppearance("rocket", "brand"))

        // Nothing changed: nothing is sent. Only the look: only UpdateProjectAppearance. Only the name: only the rename.
        editor.update("bc-root", "Cesium billing launch", ProjectAppearance("rocket", "brand")).getOrThrow()
        assertThat(creation.renames).hasSize(1)
        assertThat(actions.appearances).hasSize(1)
        editor.update("bc-root", "", ProjectAppearance("moon", "purple")).getOrThrow()
        assertThat(creation.renames).hasSize(1)
        assertThat(actions.appearances).hasSize(2)
        assertThat(agents.agent("bc-root")!!.name).isEqualTo("Cesium billing launch")
        editor.update("bc-root", "Cesium", null).getOrThrow()
        assertThat(creation.renames).hasSize(2)
        assertThat(actions.appearances).hasSize(2)
        assertThat(agents.knownRoots.value.first { it.id == "bc-root" }.name).isEqualTo("Cesium")
    }

    @Test
    fun `with Extended mode off nothing is created or edited, and a refusal is the account's words`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-root", name = "Cesium billing", archived = false, isProject = true, record = RecordFields(projectMetadata = "{}"))))
        val editor = editor(agents)
        extended = false
        assertThat(editor.create("X", null, emptyList()).exceptionOrNull()?.message).isEqualTo(ProjectEditor.NEEDS_EXTENDED_MODE)
        assertThat(editor.update("bc-root", "Y", null).exceptionOrNull()?.message).isEqualTo(ProjectEditor.NEEDS_EXTENDED_MODE)
        assertThat(creation.drafts).isEmpty()
        assertThat(creation.renames).isEmpty()
        extended = true
        creation.failing = IllegalStateException("Project creation is not available for this workspace.")
        assertThat(editor.create("X", null, emptyList()).exceptionOrNull()?.message).isEqualTo("Project creation is not available for this workspace.")
        assertThat(agents.knownRoots.value.map { it.id }).containsExactly("bc-root")
    }

    @Test
    fun `an over-long name is refused before anything is sent`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-root", name = "Cesium billing", archived = false, isProject = true, record = RecordFields(projectMetadata = "{}"))))
        val result = editor(agents).update("bc-root", "x".repeat(101), null)
        assertThat(result.isFailure).isTrue()
        assertThat(creation.renames).isEmpty()
    }
}
