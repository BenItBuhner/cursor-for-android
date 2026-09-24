package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ContextStores
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.panel.ContextPanelState
import com.cursorforandroid.ui.panel.ConversationPanel
import com.cursorforandroid.ui.panel.FileView
import com.cursorforandroid.ui.panel.LocalPanelGraph
import com.cursorforandroid.ui.panel.PanelActions
import com.cursorforandroid.ui.panel.PanelFixtures
import com.cursorforandroid.ui.panel.PanelState
import com.cursorforandroid.ui.panel.PanelTab
import com.cursorforandroid.ui.panel.PanelTabsState
import com.cursorforandroid.ui.panel.RemoteLoad
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The panel tab by tab, in the states the fixtures leave them in: the Project's notes, All Files with its two trees
 * and Recents, a document in Preview and in Source, another chat's transcript, a file of the repository and a
 * picture, each under the strip of tabs. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelTabsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { PanelFixtures.NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val projectStore = AgentStoreRef("st-project", AgentStoreKind.CLOUD, sourceId = "bc-root")
    private val userStore = AgentStoreRef("st-user", AgentStoreKind.USER)
    private val coordinator = PanelFixtures.agent.copy(id = "bc-root", name = "Cursor for Android", branches = emptyList(), isProject = true, projectAppearance = ProjectAppearance("rocket", "purple"))

    private val repo = "https://github.com/BenItBuhner/cursor-for-android"
    private val notes = ContextDocument(
        projectStore,
        "notes.md",
        """
        # Cursor for Android

        ## Shipping

        - [ ] Next patch 0.3.14 — main at `4571ac8`; no pending work assigned; Fable 5.1 Max usable again (rate limit lifted 2026-09-15)
        - [x] [Release v0.3.13]($repo/releases/tag/v0.3.13) — shipped 2026-09-15 from `69b6e18`: icon fidelity audit ([#140]($repo/pull/140)); main bumped to 0.3.14
        - [ ] Signing key handoff — `release.jks` and the four repo secrets plus `RELEASE_CERT_SHA256`, so CI can sign releases
        - [x] [Release v0.3.12]($repo/releases/tag/v0.3.12) — shipped 2026-09-15 from `95b105f`: panel header alignment ([#138]($repo/pull/138))

        ## Deferred features (Extended mode)

        - [ ] Connect streaming client — Project live updates poll every 20 s until built
        - [ ] New Project from composer; Ask/Debug for new chats; Origin token minting; media upload; PR write actions
        - [ ] Live-VM verification of Extended shapes — desktop ticket/port semantics, `UpdatePendingFollowup`

        ## Direction and reference

        - [x] [Project context](docs/project-context.md) — living: decisions, release/PR state, Extended mode scope
        - [x] [Execution plan](docs/execution-plan.md) — living: 47 slices; wave 5 delivered, only the license gate (G2) still blocks anything
        - [x] [API integration spec](docs/api-integration-spec.md) — final: public-vs-private map, leak root cause, panel sections
        - [x] [Private edition feasibility](docs/private-edition-feasibility.md) — SDK-only now; full features via Extended mode

        Older items: [archived](archived.md)
        """.trimIndent(),
    )
    private val feasibility = ContextDocument(
        projectStore,
        "docs/private-edition-feasibility.md",
        """
        # Private Edition — Feasibility and Risk

        Repo: `BenItBuhner/cursor-for-android` @ `9120e41` (main). Scope: read-only research and assessment. No code, branches, repo files, or pull requests were created or changed.

        ## Summary

        A private edition that talks to the undocumented account service is feasible today and carries the API-terms exposure the public edition avoids.

        | Surface | Endpoint | Risk |
        | --- | --- | --- |
        | Steering | `InjectBackgroundComposerContext` | private |
        | Projects | `CreateProjectWorker` | private, beta |
        """.trimIndent(),
    )
    private val feasibilityTab = PanelTab.Document(projectStore.storeId, feasibility.path)
    private val triageTab = PanelTab.Document(projectStore.storeId, "docs/readiness-triage.md")

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private fun context(thumbnail: String): ContextPanelState = ContextPanelState(
        stores = RemoteLoad.Loaded(ContextStores(projectStore, userStore)),
        notes = RemoteLoad.Loaded(notes),
        listings = mapOf(
            ContextPanelState.folderKey(projectStore, "") to RemoteLoad.Loaded(
                listOf(
                    ContextEntry("docs", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 11 * hour),
                    ContextEntry("inbox", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 4 * day),
                    ContextEntry("internal", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 11 * hour),
                    ContextEntry("media", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 11 * hour),
                    ContextEntry("archived.md", isDirectory = false, sizeBytes = 2_400, updatedAtMillis = PanelFixtures.NOW - 11 * hour),
                    ContextEntry("notes.md", isDirectory = false, sizeBytes = 6_100, updatedAtMillis = PanelFixtures.NOW - 11 * hour),
                ),
            ),
            ContextPanelState.folderKey(userStore, "") to RemoteLoad.Loaded(
                listOf(
                    ContextEntry("handoff", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 6 * day),
                    ContextEntry("preferences.md", isDirectory = false, sizeBytes = 900, updatedAtMillis = PanelFixtures.NOW - day),
                ),
            ),
        ),
        expandedFolders = setOf(ContextPanelState.folderKey(projectStore, ""), ContextPanelState.folderKey(userStore, "")),
        recents = RemoteLoad.Loaded(
            listOf(
                RecentContextFile(projectStore, ContextEntry("media/transcript-rich-content.png", isDirectory = false, updatedAtMillis = PanelFixtures.NOW - hour), thumbnail),
                RecentContextFile(projectStore, ContextEntry("media/settings-extended-section.png", isDirectory = false, updatedAtMillis = PanelFixtures.NOW - 2 * hour), thumbnail),
                RecentContextFile(projectStore, ContextEntry("notes.md", isDirectory = false, updatedAtMillis = PanelFixtures.NOW - 11 * hour)),
                RecentContextFile(projectStore, ContextEntry("media/sidebar-pinned-and-running.png", isDirectory = false, updatedAtMillis = PanelFixtures.NOW - 12 * hour), thumbnail),
            ),
        ),
        documents = mapOf(feasibilityTab.key to RemoteLoad.Loaded(feasibility)),
    )

    /** A swatch standing in for a screenshot in the store, written where a local file would be. */
    private fun thumbnail(): String {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(320, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(0x18, 0x18, 0x18))
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = AndroidColor.rgb(0x2A, 0x2A, 0x2A)
        canvas.drawRoundRect(20f, 24f, 300f, 60f, 8f, 8f, paint)
        paint.color = AndroidColor.rgb(0x3A, 0x3A, 0x3A)
        for (i in 0 until 5) canvas.drawRoundRect(20f, 80f + i * 34f, 300f - i * 30f, 104f + i * 34f, 6f, 6f, paint)
        paint.color = AndroidColor.rgb(0x5F, 0xD3, 0xB3)
        canvas.drawCircle(46f, 42f, 8f, paint)
        val file = File(appContext.filesDir, "generated/bc-root/store-thumb.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    /** The coordinator's panel with two of its documents open as tabs, as reference 02 has them, and [selected] showing. */
    private fun coordinatorState(thumbnail: String, selected: PanelTab? = null, allFiles: Boolean = false, more: List<PanelTab> = emptyList()): PanelState =
        PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            capabilities = Capabilities.EXTENDED,
            pullRequest = RemoteLoad.Idle,
            tabs = PanelTabsState(open = listOf(feasibilityTab, triageTab) + more, selectedKey = selected?.key),
            context = context(thumbnail).copy(allFiles = allFiles),
        )

    @Composable
    private fun Panel(state: PanelState, graph: AppGraph? = null) {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember { MediaLoader(appContext, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) }
        val media = remember(loader) { MarkdownMediaContext("bc-root", loader) }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media, LocalPanelGraph provides graph) {
                // The panel as it sits on a phone: the sheet over the chat's canvas at the width the host gives it
                // there, on the canvas colour as the web's panel is.
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("scene")) {
                    Box(Modifier.align(Alignment.CenterEnd).width(363.dp).fillMaxHeight().background(CursorTheme.colors.canvas)) {
                        ConversationPanel(state, PanelActions.None, onClose = {})
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun waitForTag(tag: String, timeoutMillis: Long = 10_000) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    /** The Project tab: the notes under the Project's glyph and name, as cursor.com renders a Project (reference 02). */
    @Test
    fun projectTab() {
        compose.setContent { Panel(coordinatorState(thumbnail())) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Shipping")).fetchSemanticsNodes().isNotEmpty() }
        capture("500_panel_tab_project")
    }

    /** All Files: the Project's tree and the user's, each entry with when it was written, then Recents with its thumbnails (reference 03). */
    @Test
    fun allFilesTab() {
        compose.setContent { Panel(coordinatorState(thumbnail(), allFiles = true)) }
        // The pictures decode off the main thread, in no fixed order and slowly on a loaded CI runner; the frame waits
        // for all three to be on screen.
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("recent-thumbnail"), useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        capture("501_panel_tab_all_files")
    }

    /** A document tab in Preview: the breadcrumb, the toggle, the markdown rendered (reference 04). */
    @Test
    fun documentPreview() {
        compose.setContent { Panel(coordinatorState(thumbnail(), feasibilityTab)) }
        waitForTag("document-preview")
        capture("502_panel_tab_document_preview")
    }

    /** The same document under Source: the text with its line numbers. */
    @Test
    fun documentSource() {
        val state = coordinatorState(thumbnail(), feasibilityTab).let { it.copy(context = it.context.copy(sourceTabs = setOf(feasibilityTab.key))) }
        compose.setContent { Panel(state) }
        waitForTag("document-source")
        capture("503_panel_tab_document_source")
    }

    /**
     * One of the demo Project's workers opened from the coordinator's panel: its name and where it stands over its
     * transcript, read from the demo as the live panel reads it.
     */
    @Test
    fun agentTab() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(appContext, SecureKeyStore(appContext) { appContext.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        val project = checkNotNull(graph.agents.agent(DemoData.PROJECT_ID))
        val worker = checkNotNull(graph.agents.agent(WORKER_ID))
        compose.setContent { Panel(agentState(project, worker), graph) }
        waitForTag("agent-transcript", timeoutMillis = 60_000)
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("The HTTP action verifies", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Handle Stripe", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("504_panel_tab_agent")
    }

    private fun agentState(project: Agent, worker: Agent): PanelState {
        val tab = PanelTab.Agent(worker.id)
        return PanelState(
            agentId = project.id,
            agent = project,
            runStatus = project.runStatus,
            capabilities = Capabilities.EXTENDED,
            tabs = PanelTabsState(open = listOf(tab), selectedKey = tab.key),
            tabAgents = mapOf(worker.id to worker),
        )
    }

    /** A file of the repository opened from a worker's sections: the path down to it, where the copy came from, the source. */
    @Test
    fun fileTab() {
        val worker = PanelFixtures.agent.copy(parent = AgentParent(coordinator.id, AgentParentKind.PROJECT_WORKER))
        val path = "app/src/main/java/com/cursorforandroid/ui/theme/CursorTheme.kt"
        val file = RepoFile(path, THEME_SOURCE.toByteArray(), sizeBytes = 5_812, sha = "4571ac8", downloadUrl = "https://raw.githubusercontent.com/BenItBuhner/cursor-for-android/main/$path")
        val state = PanelFixtures.withFile(PanelFixtures.loaded().copy(agent = worker, parentAgent = coordinator), FileView.Repository(file))
        compose.setContent { Panel(state) }
        waitForTag("file-caption")
        capture("505_panel_tab_file")
    }

    /** A picture from the Project's Recents as its own tab, as large as the tab. */
    @Test
    fun mediaTab() {
        val tab = PanelTab.Media(thumbnail(), "transcript-rich-content.png")
        compose.setContent { Panel(coordinatorState(tab.src, tab, more = listOf(tab))) }
        compose.waitUntil(60_000) { compose.onAllNodes(hasContentDescription(tab.name)).fetchSemanticsNodes().isNotEmpty() }
        capture("506_panel_tab_media")
    }

    private companion object {
        /** The demo Project's open worker: "Stripe webhook handler", its PR waiting on a decision. */
        const val WORKER_ID = "bc-demo-0020"

        val THEME_SOURCE = """
            package com.cursorforandroid.ui.theme

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.CompositionLocalProvider

            /** The app's theme: the palette for [mode], OLED black on request, the type and shapes shared by both. */
            @Composable
            fun CursorTheme(mode: ThemeMode, oledBlack: Boolean = false, content: @Composable () -> Unit) {
                val colors = when (mode) {
                    ThemeMode.Light -> LightColors
                    else -> if (oledBlack) OledColors else DarkColors
                }
                CompositionLocalProvider(
                    LocalCursorColors provides colors,
                    LocalCursorTypography provides CursorTypography,
                    LocalCursorShapes provides CursorShapes,
                    content = content,
                )
            }
        """.trimIndent()
    }
}
