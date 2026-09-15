package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.ContextStores
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.domain.Subscriptions
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.conversation.ConversationPills
import com.cursorforandroid.ui.conversation.ConversationPillsState
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.navigation.RailState
import com.cursorforandroid.ui.panel.ContextPanelState
import com.cursorforandroid.ui.panel.ConversationPanel
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
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * The panel as a tabbed surface, tab by tab, in the states the fixtures leave them in: the Project's notes, All Files
 * with its two trees and Recents, a document in Preview and in Source; the pills above the composer; a Task row; and
 * the whole shell on a tablet with the glyph rail and the panel pinned as a pane. Written to `screenshots/`; CI
 * compares them pixel for pixel.
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

    private val notes = ContextDocument(
        projectStore,
        "notes.md",
        """
        # Cursor for Android

        ## Shipping

        - [ ] Next patch 0.3.14 — main at `4571ac8`; no pending work assigned; Fable 5.1 Max usable again (rate limit lifted 2026-09-15)
        - [x] Release v0.3.13 — shipped 2026-09-15 from `69b6e18`: icon fidelity audit (#140); main bumped to 0.3.14
        - [ ] Signing key handoff — `release.jks` and secrets sit in the store's internal/release-signing; Bennett must back them up and set the four repo secrets plus `RELEASE_CERT_SHA256` so CI can sign releases
        - [x] Release v0.3.12 — shipped 2026-09-15 from `95b105f`: panel header alignment (#138)

        ## Deferred features (Extended mode)

        - [ ] Connect streaming client — Project live updates poll every 20 s until built
        - [ ] New Project from composer; Ask/Debug for new chats; Origin token minting; media upload; PR write actions
        - [ ] Live-VM verification of Extended shapes — desktop ticket/port semantics, `UpdatePendingFollowup`

        ## Direction and reference

        - [x] **Project context** — living: decisions, release/PR state, Extended mode scope
        - [x] **Execution plan** — living: 47 slices; wave 5 delivered, only the license gate (G2) still blocks anything
        - [x] **API integration spec** — final: public-vs-private map, leak root cause, panel sections
        - [ ] **Private edition feasibility** — SDK-only now; full features via Extended mode

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

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private fun context(thumbnail: String): ContextPanelState {
        val docsTab = PanelTab.Document(projectStore.storeId, feasibility.path)
        return ContextPanelState(
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
            documents = mapOf(docsTab.key to RemoteLoad.Loaded(feasibility)),
        )
    }

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

    private fun coordinatorState(thumbnail: String, tab: PanelTab): PanelState {
        val docsTab = PanelTab.Document(projectStore.storeId, feasibility.path)
        return PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            capabilities = Capabilities.EXTENDED,
            pullRequest = RemoteLoad.Idle,
            tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.Project, PanelTab.AllFiles, docsTab), selected = tab),
            context = context(thumbnail),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Panel(state: PanelState) {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember { MediaLoader(appContext, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() })) }
        val lightbox = rememberLightboxState("bc-root")
        val media = remember(loader, lightbox) { MarkdownMediaContext("bc-root", loader, lightbox) }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("scene")) {
                    Box(Modifier.align(Alignment.CenterEnd).width(363.dp).fillMaxHeight().background(CursorTheme.colors.sidebar)) {
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

    /** The Project tab: the notes under the Project's glyph and name, as cursor.com renders a Project. */
    @Test
    fun projectTab() {
        compose.setContent { Panel(coordinatorState(thumbnail(), PanelTab.Project)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Shipping")).fetchSemanticsNodes().isNotEmpty() }
        capture("69_panel_project_tab")
    }

    /** All Files: the Project's tree and the user's, each entry with when it was written, then Recents with its thumbnails. */
    @Test
    fun allFilesTab() {
        compose.setContent { Panel(coordinatorState(thumbnail(), PanelTab.AllFiles)) }
        // The pictures decode off the main thread, in no fixed order; the frame waits for all three to be on screen.
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Thumbnail of ", substring = true)).fetchSemanticsNodes().size == 3 }
        capture("70_panel_all_files")
    }

    /** A document tab in Preview: the breadcrumb, the toggle, the markdown rendered. */
    @Test
    fun documentPreview() {
        compose.setContent { Panel(coordinatorState(thumbnail(), PanelTab.Document(projectStore.storeId, feasibility.path))) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("document-preview")).fetchSemanticsNodes().isNotEmpty() }
        capture("71_panel_document_preview")
    }

    /** The same document under Source: the text with its line numbers. */
    @Test
    fun documentSource() {
        val tab = PanelTab.Document(projectStore.storeId, feasibility.path)
        val state = coordinatorState(thumbnail(), tab).let { it.copy(context = it.context.copy(sourceTabs = setOf(tab.key))) }
        compose.setContent { Panel(state) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("document-source")).fetchSemanticsNodes().isNotEmpty() }
        capture("72_panel_document_source")
    }

    /** The pills above the composer, every one of them on: Agents with a worker working, Listening 3, Changes, Open Desktop. */
    @Test
    fun composerPills() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(12.dp).testTag("scene")) {
                        ConversationPills(
                            ConversationPillsState(
                                agents = ConversationPillsState.AgentsSummary(total = 2, working = 1, needsInput = 0),
                                listening = Subscriptions.Listening(listOf("GitHub PR", "GitHub CI", "Timer")),
                                changes = ConversationPillsState.ChangesSummary(3167, 139, 9),
                                canOpenDesktop = true,
                            ),
                            onAgents = {},
                            onChanges = {},
                            onOpenDesktop = {},
                        )
                    }
                }
            }
        }
        capture("73_composer_pills")
    }

    /** A Task row inside an open stretch: the dot, the title, the cloud glyph, "Completed" with the time it ran. */
    @Test
    fun taskRow() {
        val task = ToolCall(
            callId = "t1",
            name = "task_v2",
            kind = ToolKind.Task,
            status = ToolCall.STATUS_COMPLETED,
            summary = "Icon fidelity gaps: target and layered triangle",
            payload = ToolPayload.Subagent("Icon fidelity gaps: target and layered triangle", agentId = "bc-icons", durationMs = 45 * 60_000L + 9_000L),
        )
        val thought = com.cursorforandroid.domain.ThinkingBlock("The two glyphs read as different objects; a full audit against the desktop's sheet is the fix, not two spot changes.", durationSeconds = 3)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {})) {
                    Column(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene")) {
                        TimelineItemView(ActivityGroup("g1", listOf(thought, task)))
                    }
                }
            }
        }
        // The task sits behind the stretch's summary row ("Worked" in the web's frame): open it, as the reference did.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Explored", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Explored", substring = true))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("task-row")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        capture("74_transcript_task_row")
    }

    /**
     * The shell on a tablet: the glyph rail with the demo Project's glyph, the coordinator's chat with its Agents
     * pill, and the panel pinned beside it as a pane on the Project tab.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletIconRailPane() {
        AppClock.nowMillis = { FIXED_NOW }
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(appContext, SecureKeyStore(appContext) { appContext.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking {
            graph.session.enterDemo()
            graph.prefs.setRailState("Expanded", RailState.IconOnly.name)
            // The chat is open in the frame, so its Project reads as read whatever the demo's rows do meanwhile.
            graph.prefs.markRead(DemoData.PROJECT_ID, FIXED_NOW + 365L * 24 * 60 * 60_000L)
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        windowWidthDp = 1000,
                        windowHeightDp = 720,
                        deepLinkAgentId = DemoData.PROJECT_ID,
                        onDeepLinkConsumed = {},
                    )
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Follow up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(60_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitUntil(60_000) { graph.conversations.state(DemoData.PROJECT_ID).value.items.count { it is ActivityGroup } >= 2 }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("PR #215 (usage aggregation) is", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("icon-rail")).fetchSemanticsNodes().isNotEmpty() }
        // The read marker reaches the rows the glyph is drawn from a beat after the list; the frame waits for it.
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Project Cesium billing launch") and hasStateDescription("unread")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("panel-pane")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Shipping")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "75_tablet_icon_rail_pane.png").path, RoborazziOptions())
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC; the walkthrough's clock, so the demo's ages read the same. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
