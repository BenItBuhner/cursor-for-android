package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's two surfaces and the Project panel's tabs: which chats get the Project tab, what the Project tab
 * shows in its notes and All Files modes, how a document tab reads and toggles between Preview and Source, and how
 * the strip and the Side chats section hand the reader between tabs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private val projectStore = AgentStoreRef("st-project", AgentStoreKind.CLOUD, sourceId = "bc-root")
    private val userStore = AgentStoreRef("st-user", AgentStoreKind.USER)
    private val notes = ContextDocument(projectStore, "notes.md", "# Cesium billing launch\n\n## Shipping\n\n- [ ] Usage events aggregation")
    private val spec = ContextDocument(projectStore, "docs/spec.md", "# Spec\n\nThe plan.")

    private val loadedContext = ContextPanelState(
        stores = RemoteLoad.Loaded(ContextStores(projectStore, userStore)),
        notes = RemoteLoad.Loaded(notes),
        listings = mapOf(
            ContextPanelState.folderKey(projectStore, "") to RemoteLoad.Loaded(listOf(ContextEntry("docs", isDirectory = true, updatedAtMillis = PanelFixtures.NOW - 3_600_000L), ContextEntry("notes.md", isDirectory = false, sizeBytes = 120, updatedAtMillis = PanelFixtures.NOW - 60_000L))),
            ContextPanelState.folderKey(projectStore, "docs") to RemoteLoad.Loaded(listOf(ContextEntry("docs/spec.md", isDirectory = false, sizeBytes = 900, updatedAtMillis = PanelFixtures.NOW - 3_600_000L))),
            ContextPanelState.folderKey(userStore, "") to RemoteLoad.Loaded(listOf(ContextEntry("preferences.md", isDirectory = false, sizeBytes = 80, updatedAtMillis = PanelFixtures.NOW - 86_400_000L))),
        ),
        expandedFolders = setOf(ContextPanelState.folderKey(projectStore, ""), ContextPanelState.folderKey(userStore, "")),
        recents = RemoteLoad.Loaded(listOf(RecentContextFile(projectStore, ContextEntry("notes.md", isDirectory = false, updatedAtMillis = PanelFixtures.NOW - 60_000L)))),
        documents = mapOf(PanelTab.Document(projectStore.storeId, "docs/spec.md").key to RemoteLoad.Loaded(spec)),
    )

    private val coordinator = PanelFixtures.agent.copy(id = "bc-root", name = "Cesium billing launch", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple"))

    private var state by mutableStateOf(PanelFixtures.loaded())
    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun showSurface(surface: PanelSurface) { asked += "surface:$surface"; state = state.copy(surface = surface) }
        override fun selectTab(tab: PanelTab) { asked += "select:${tab.key}"; state = state.copy(surface = PanelSurface.Project, tabs = state.tabs.copy(selected = tab)) }
        override fun closeTab(tab: PanelTab) { asked += "close:${tab.key}" }
        override fun openSideChat(agentId: String) { asked += "side:$agentId" }
        override fun openAgent(agentId: String) { asked += "agent:$agentId" }
        override fun openDocument(store: AgentStoreRef, path: String) { asked += "doc:${store.storeId}:$path" }
        override fun openProject(allFiles: Boolean) { asked += "project:$allFiles"; state = state.copy(surface = PanelSurface.Project, tabs = state.tabs.copy(selected = PanelTab.Project), context = state.context.copy(allFiles = allFiles)) }
        override fun toggleFolder(store: AgentStoreRef, path: String) { asked += "folder:${store.storeId}:$path" }
        override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) { asked += "source:$source"; state = state.copy(context = state.context.copy(sourceTabs = if (source) state.context.sourceTabs + tab.key else state.context.sourceTabs - tab.key)) }
        override fun loadContext(force: Boolean) { asked += "context" }
        override fun loadDocument(tab: PanelTab.Document, force: Boolean) { asked += "load:${tab.key}" }
        override fun openUrl(url: String) { asked += "url:$url" }
        override fun startSideChat(name: String?) { asked += "start-side-chat" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}, onExpand = { asked += "expand" }) } }
    }

    // The tree's "Today at 2:37 AM" reads against the clock; pinned to the fixtures' day.
    @Before
    fun pinClock() {
        AppClock.nowMillis = { PanelFixtures.NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun hasTab(key: String) = compose.onAllNodesWithTag("panel-tab-$key").fetchSemanticsNodes().isNotEmpty()

    private fun projectPanel(tab: PanelTab = PanelTab.Project, allFiles: Boolean = false, vararg extra: PanelTab): PanelState = PanelFixtures.loaded().copy(
        agentId = coordinator.id,
        agent = coordinator,
        capabilities = Capabilities.EXTENDED,
        surface = PanelSurface.Project,
        tabs = PanelTabsState(open = listOf(PanelTab.Project) + extra, selected = tab),
        context = loadedContext.copy(allFiles = allFiles),
    )

    @Test
    fun `a chat of its own has the sections and no Project tab, a coordinator and its worker get the Project tab`() {
        state = PanelFixtures.loaded()
        show()
        // The Chat surface: the sections as they were, the chat named at the top, no strip.
        assertThat(shown("Overview")).isTrue()
        assertThat(compose.onAllNodesWithTag("panel-tabs").fetchSemanticsNodes()).isEmpty()
        assertThat(state.hasProjectTab).isFalse()

        val root = PanelFixtures.loaded().copy(agentId = coordinator.id, agent = coordinator)
        assertThat(root.hasProjectTab).isTrue()
        assertThat(root.projectRootId).isEqualTo("bc-root")
        val worker = PanelFixtures.loaded().copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER)), parentAgent = coordinator)
        assertThat(worker.projectRootId).isEqualTo("bc-root")
        assertThat(worker.projectRoot).isEqualTo(coordinator)
        val sideChatOfPlainChat = PanelFixtures.loaded().copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-other", AgentParentKind.SIDE_CHAT)), parentAgent = PanelFixtures.agent.copy(id = "bc-other"))
        assertThat(sideChatOfPlainChat.hasProjectTab).isFalse()
    }

    @Test
    fun `the Project tab renders the notes under the Project's name, with the strip's controls`() {
        state = projectPanel()
        show()
        compose.onNodeWithTag("panel-tab-project").assertIsSelected()
        assertThat(hasTab("chat")).isFalse()
        assertThat(hasTab("files")).isFalse()
        compose.onNodeWithTag("project-notes-title").assertIsDisplayed()
        assertThat(shown("Cesium billing launch")).isTrue()
        assertThat(shown("Shipping")).isTrue()
        assertThat(shown("Usage events aggregation")).isTrue()
        assertThat(asked).contains("context")
        compose.onNodeWithTag("panel-expand").performClick()
        assertThat(asked).contains("expand")
    }

    @Test
    fun `the header's toggle switches the Project tab to All Files and back`() {
        state = projectPanel()
        show()
        compose.onNodeWithContentDescription("All Files").performClick()
        assertThat(asked).contains("project:true")
        compose.onNodeWithTag("all-files-tab").assertIsDisplayed()
        compose.onNodeWithContentDescription("All Files").assertIsSelected()
        assertThat(compose.onAllNodesWithTag("store-root").fetchSemanticsNodes()).hasSize(2)
        assertThat(shown("User")).isTrue()
        assertThat(shown("preferences.md")).isTrue()
        compose.onNodeWithContentDescription("Folder docs").assertIsDisplayed()
        assertThat(shown("Today at")).isTrue()
        compose.onNodeWithContentDescription("Folder docs").performClick()
        assertThat(asked).contains("folder:st-project:docs")
        state = state.copy(context = state.context.copy(expandedFolders = state.context.expandedFolders + ContextPanelState.folderKey(projectStore, "docs")))
        compose.onNodeWithContentDescription("File spec.md").performClick()
        assertThat(asked).contains("doc:st-project:docs/spec.md")
        compose.onNodeWithTag("all-files-tab").performScrollToNode(hasTestTag("recents-row"))
        assertThat(compose.onAllNodesWithTag("recent-tile").fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithContentDescription("All Files").performClick()
        assertThat(asked).contains("project:false")
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
    }

    @Test
    fun `without Extended mode the Project tab names the mode and the way to cursor com`() {
        state = projectPanel().copy(context = ContextPanelState(stores = RemoteLoad.Unsupported("Needs Extended mode"), notes = RemoteLoad.Unsupported("Needs Extended mode"), recents = RemoteLoad.Unsupported("Needs Extended mode")))
        show()
        compose.onNodeWithTag("context-unavailable").assertIsDisplayed()
        compose.onNodeWithText("Open on cursor.com").performClick()
        assertThat(asked.any { it.startsWith("url:https://cursor.com/agents/") }).isTrue()
    }

    @Test
    fun `a document tab shows the breadcrumb, previews markdown and switches to its source`() {
        val tab = PanelTab.Document(projectStore.storeId, "docs/spec.md")
        state = projectPanel(tab = tab, extra = arrayOf(tab))
        show()
        compose.onNodeWithTag("document-tab").assertIsDisplayed()
        assertThat(asked).contains("load:${tab.key}")
        assertThat(shown("docs")).isTrue()
        assertThat(shown("spec.md")).isTrue()
        compose.onNodeWithTag("document-preview").assertIsDisplayed()
        assertThat(shown("The plan.")).isTrue()
        compose.onNodeWithTag("segment-Source").performClick()
        assertThat(asked).contains("source:true")
        compose.onNodeWithTag("document-source").assertIsDisplayed()
        assertThat(shown("# Spec")).isTrue()
        // The tab's cross closes it without selecting it.
        compose.onNodeWithContentDescription("Close spec.md").performClick()
        assertThat(asked).contains("close:${tab.key}")
        assertThat(asked).doesNotContain("select:${tab.key}")
    }

    @Test
    fun `the strip's plus opens a file or a side chat`() {
        state = projectPanel().copy(sideChats = PanelFixtures.withSideChats().sideChats)
        show()
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("Open a file…").performClick()
        assertThat(asked).contains("project:true")
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("New side chat").performClick()
        assertThat(asked).contains("start-side-chat")
    }

    @Test
    fun `a side chat row opens the side chat as a tab, its trailing button as a chat`() {
        state = PanelFixtures.withSideChats()
        show()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-SideChats"))
        compose.onNodeWithTag("section-SideChats").performClick()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("side-chat"))
        val sideChat = state.sideChats.first()
        compose.onAllNodesWithTag("side-chat")[0].performClick()
        assertThat(asked).contains("side:${sideChat.id}")
        compose.onNodeWithContentDescription("Open ${sideChat.name} as a chat").performClick()
        assertThat(asked).contains("agent:${sideChat.id}")
        // The tab, once open, is on the strip by the side chat's name and closable; a chat of its own has no Project tab beside it.
        val tab = PanelTab.SideChat(sideChat.id)
        state = state.copy(surface = PanelSurface.Project, tabs = PanelTabsState(open = listOf(tab), selected = tab))
        compose.onNodeWithTag("panel-tab-${tab.key}").assertIsSelected()
        assertThat(hasTab("project")).isFalse()
        assertThat(shown(sideChat.name)).isTrue()
        compose.onNodeWithTag("side-chat-tab").assertIsDisplayed()
    }

    @Test
    fun `a chat in a Project reaches its sections from the Project panel and back`() {
        state = projectPanel()
        show()
        assertThat(compose.onAllNodesWithTag("panel-sections").fetchSemanticsNodes()).isEmpty()
        state = state.copy(surface = PanelSurface.Chat)
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
        compose.onNodeWithTag("panel-to-project").performClick()
        assertThat(asked).contains("project:false")
        compose.onNodeWithTag("panel-tabs").assertIsDisplayed()
    }

    @Test
    fun `tab keys round trip and a closed selection falls back to the first tab`() {
        val document = PanelTab.Document("st-project", "docs/spec.md")
        assertThat(PanelTab.fromKey(document.key)).isEqualTo(document)
        assertThat(PanelTab.fromKey(PanelTab.SideChat("bc-side").key)).isEqualTo(PanelTab.SideChat("bc-side"))
        assertThat(PanelTab.fromKey("project")).isEqualTo(PanelTab.Project)
        assertThat(PanelTab.fromKey("chat")).isNull()
        assertThat(PanelTab.fromKey("files")).isNull()
        assertThat(PanelTab.fromKey("doc:x")).isNull()
        val tabs = PanelTabsState(open = listOf(PanelTab.Project), selected = document)
        assertThat(tabs.current).isEqualTo(PanelTab.Project)
        assertThat(PanelTabsState(open = emptyList(), selected = null).current).isNull()
        assertThat(document.closable).isTrue()
        assertThat(PanelTab.Project.closable).isFalse()
    }
}
