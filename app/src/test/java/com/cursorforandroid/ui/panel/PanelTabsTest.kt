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
 * The panel's tabs: which tabs a chat gets, what each Context tab shows for its state, how a document tab reads and
 * toggles between Preview and Source, and how the strip and the Side chats section hand the reader between tabs.
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
        override fun selectTab(tab: PanelTab) { asked += "select:${tab.key}"; state = state.copy(tabs = state.tabs.copy(selected = tab)) }
        override fun closeTab(tab: PanelTab) { asked += "close:${tab.key}" }
        override fun openSideChat(agentId: String) { asked += "side:$agentId" }
        override fun openAgent(agentId: String) { asked += "agent:$agentId" }
        override fun openDocument(store: AgentStoreRef, path: String) { asked += "doc:${store.storeId}:$path" }
        override fun openAllFiles() { asked += "files" }
        override fun toggleFolder(store: AgentStoreRef, path: String) { asked += "folder:${store.storeId}:$path" }
        override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) { asked += "source:$source"; state = state.copy(context = state.context.copy(sourceTabs = if (source) state.context.sourceTabs + tab.key else state.context.sourceTabs - tab.key)) }
        override fun loadContext(force: Boolean) { asked += "context" }
        override fun loadDocument(tab: PanelTab.Document, force: Boolean) { asked += "load:${tab.key}" }
        override fun openUrl(url: String) { asked += "url:$url" }
        override fun startSideChat(name: String?) { asked += "start-side-chat" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
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

    @Test
    fun `a chat of its own has Chat and All Files, a Project's coordinator and its worker the Project tab too`() {
        val plain = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.AllFiles))
        state = PanelFixtures.loaded().copy(tabs = plain)
        show()
        assertThat(hasTab("chat")).isTrue()
        assertThat(hasTab("files")).isTrue()
        assertThat(hasTab("project")).isFalse()
        compose.onNodeWithTag("panel-tab-chat").assertIsSelected()
        assertThat(shown("Overview")).isTrue()

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
    fun `the Project tab renders the notes under the Project's name and offers them as a document`() {
        state = PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            capabilities = Capabilities.EXTENDED,
            tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.Project, PanelTab.AllFiles), selected = PanelTab.Project),
            context = loadedContext,
        )
        show()
        compose.onNodeWithTag("project-notes-title").assertIsDisplayed()
        assertThat(shown("Cesium billing launch")).isTrue()
        assertThat(shown("Shipping")).isTrue()
        assertThat(shown("Usage events aggregation")).isTrue()
        assertThat(asked).contains("context")
        compose.onNodeWithTag("project-notes-open").performClick()
        assertThat(asked).contains("doc:st-project:notes.md")
    }

    @Test
    fun `without Extended mode the Project tab names the mode and the way to cursor com`() {
        state = PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.Project, PanelTab.AllFiles), selected = PanelTab.Project),
            context = ContextPanelState(stores = RemoteLoad.Unsupported("Needs Extended mode"), notes = RemoteLoad.Unsupported("Needs Extended mode"), recents = RemoteLoad.Unsupported("Needs Extended mode")),
        )
        show()
        compose.onNodeWithTag("context-unavailable").assertIsDisplayed()
        compose.onNodeWithText("Open on cursor.com").performClick()
        assertThat(asked.any { it.startsWith("url:https://cursor.com/agents/") }).isTrue()
    }

    @Test
    fun `All Files lists both stores as trees with their times, opens folders and files, and shows Recents`() {
        state = PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            capabilities = Capabilities.EXTENDED,
            tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.Project, PanelTab.AllFiles), selected = PanelTab.AllFiles),
            context = loadedContext,
        )
        show()
        assertThat(compose.onAllNodesWithTag("store-root").fetchSemanticsNodes()).hasSize(2)
        assertThat(shown("User")).isTrue()
        assertThat(shown("preferences.md")).isTrue()
        // The Project's root is open: its folder and file with the time each was written.
        compose.onNodeWithContentDescription("Folder docs").assertIsDisplayed()
        assertThat(shown("Today at")).isTrue()
        compose.onNodeWithContentDescription("Folder docs").performClick()
        assertThat(asked).contains("folder:st-project:docs")
        // The folder opened in state lists its file, which opens as a document.
        state = state.copy(context = state.context.copy(expandedFolders = state.context.expandedFolders + ContextPanelState.folderKey(projectStore, "docs")))
        compose.onNodeWithContentDescription("File spec.md").performClick()
        assertThat(asked).contains("doc:st-project:docs/spec.md")
        compose.onNodeWithTag("all-files-tab").performScrollToNode(hasTestTag("recents-row"))
        assertThat(compose.onAllNodesWithTag("recent-tile").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `a document tab shows the breadcrumb, previews markdown and switches to its source`() {
        val tab = PanelTab.Document(projectStore.storeId, "docs/spec.md")
        state = PanelFixtures.loaded().copy(
            agentId = coordinator.id,
            agent = coordinator,
            capabilities = Capabilities.EXTENDED,
            tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.Project, PanelTab.AllFiles, tab), selected = tab),
            context = loadedContext,
        )
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
    fun `the strip selects tabs and its plus opens a file or a side chat`() {
        state = PanelFixtures.withSideChats().copy(tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.AllFiles), selected = PanelTab.Chat))
        show()
        compose.onNodeWithTag("panel-tab-files").performClick()
        assertThat(asked).contains("select:files")
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("Open a file…").assertIsDisplayed()
        compose.onNodeWithText("New side chat").performClick()
        assertThat(asked).contains("start-side-chat")
    }

    @Test
    fun `a side chat row opens the side chat as a tab, its trailing button as a chat`() {
        state = PanelFixtures.withSideChats().copy(tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.AllFiles), selected = PanelTab.Chat))
        show()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-SideChats"))
        compose.onNodeWithTag("section-SideChats").performClick()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("side-chat"))
        val sideChat = state.sideChats.first()
        compose.onAllNodesWithTag("side-chat")[0].performClick()
        assertThat(asked).contains("side:${sideChat.id}")
        compose.onNodeWithContentDescription("Open ${sideChat.name} as a chat").performClick()
        assertThat(asked).contains("agent:${sideChat.id}")
        // The tab, once open, is on the strip by the side chat's name and closable.
        val tab = PanelTab.SideChat(sideChat.id)
        state = state.copy(tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.AllFiles, tab), selected = tab))
        compose.onNodeWithTag("panel-tab-${tab.key}").assertIsSelected()
        assertThat(shown(sideChat.name)).isTrue()
        compose.onNodeWithTag("side-chat-tab").assertIsDisplayed()
    }

    @Test
    fun `tab keys round trip and a closed selection falls back to the first tab`() {
        val document = PanelTab.Document("st-project", "docs/spec.md")
        assertThat(PanelTab.fromKey(document.key)).isEqualTo(document)
        assertThat(PanelTab.fromKey(PanelTab.SideChat("bc-side").key)).isEqualTo(PanelTab.SideChat("bc-side"))
        assertThat(PanelTab.fromKey("chat")).isEqualTo(PanelTab.Chat)
        assertThat(PanelTab.fromKey("project")).isEqualTo(PanelTab.Project)
        assertThat(PanelTab.fromKey("files")).isEqualTo(PanelTab.AllFiles)
        assertThat(PanelTab.fromKey("doc:x")).isNull()
        assertThat(PanelTab.fromKey("nope")).isNull()
        val tabs = PanelTabsState(open = listOf(PanelTab.Chat, PanelTab.AllFiles), selected = document)
        assertThat(tabs.current).isEqualTo(PanelTab.Chat)
        assertThat(document.closable).isTrue()
        assertThat(PanelTab.Chat.closable).isFalse()
    }
}
