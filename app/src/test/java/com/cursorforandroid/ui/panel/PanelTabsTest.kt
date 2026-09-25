package com.cursorforandroid.ui.panel

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.components.CursorIcons
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
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

/**
 * The panel's tabs: which chats get the Project tab and which tab the panel opens on, the strip's order and its
 * controls, back walking the tabs to the home tab, what the Project tab shows in its notes and All Files faces, and
 * how a document, another chat, a file and a picture read as tabs of their own, each keeping its place while another
 * shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private val projectStore = AgentStoreRef("st-project", AgentStoreKind.CLOUD, sourceId = "bc-root")
    private val userStore = AgentStoreRef("st-user", AgentStoreKind.USER)
    private val notes = ContextDocument(
        projectStore,
        "notes.md",
        "# Cesium billing launch\n\n## Shipping\n\n" +
            "- [ ] Stripe webhook handler — [#218](https://github.com/techlitnow/cesium/pull/218) open\n" +
            "- [x] [Release v0.4.0](https://github.com/techlitnow/cesium/releases/tag/v0.4.0) — shipped\n\n" +
            "See [the spec](docs/spec.md).",
    )
    private val spec = ContextDocument(projectStore, "docs/spec.md", "# Spec\n\nThe plan.")
    private val specTab = PanelTab.Document(projectStore.storeId, spec.path)

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
        documents = mapOf(specTab.key to RemoteLoad.Loaded(spec)),
    )

    private val coordinator = PanelFixtures.agent.copy(
        id = "bc-root",
        name = "Cesium billing launch",
        url = "https://cursor.com/agents/bc-root",
        isProject = true,
        projectAppearance = ProjectAppearance("rocket", "purple"),
    )
    private val sideChat = PanelFixtures.sideChats.first()

    private var state by mutableStateOf(PanelFixtures.loaded())
    private var expand by mutableStateOf<PanelExpand?>(null)
    private val asked = mutableListOf<String>()
    private var closes = 0
    private lateinit var dispatcher: OnBackPressedDispatcher

    /** What each control asks the view model for, and the strip the view model would leave behind it. */
    private val actions = object : PanelActions by PanelActions.None {
        override fun selectTab(tab: PanelTab) {
            asked += "select:${tab.key}"
            state = state.copy(tabs = state.tabs.copy(selectedKey = tab.key))
        }
        override fun closeTab(tab: PanelTab) {
            asked += "close:${tab.key}"
            val tabs = state.tabs
            state = state.copy(tabs = PanelTabsState(tabs.open.filterNot { it.key == tab.key }, tabs.selectedKey.takeUnless { it == tab.key }))
        }
        override fun back() {
            asked += "back"
            state = state.copy(tabs = state.tabs.copy(selectedKey = null))
        }
        override fun openAgent(agentId: String) {
            asked += "agent:$agentId"
            val tab = PanelTab.Agent(agentId)
            val row = state.sideChats.firstOrNull { it.id == agentId }
            state = state.copy(
                tabs = PanelTabsState(state.tabs.open.filterNot { it.key == tab.key } + tab, tab.key),
                tabAgents = if (row == null) state.tabAgents else state.tabAgents + (agentId to row),
            )
        }
        override fun openAgentAsChat(agentId: String) { asked += "chat:$agentId" }
        override fun openDocument(store: AgentStoreRef, path: String) { asked += "doc:${store.storeId}:$path" }
        override fun openMedia(src: String, name: String, isVideo: Boolean) { asked += "media:$src" }
        override fun openProject(allFiles: Boolean) {
            asked += "project:$allFiles"
            state = state.copy(tabs = state.tabs.copy(selectedKey = PanelTab.Project.key), context = state.context.copy(allFiles = allFiles))
        }
        override fun toggleFolder(store: AgentStoreRef, path: String) { asked += "folder:${store.storeId}:$path" }
        override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) {
            asked += "source:$source"
            state = state.copy(context = state.context.copy(sourceTabs = if (source) state.context.sourceTabs + tab.key else state.context.sourceTabs - tab.key))
        }
        override fun loadContext(force: Boolean) { asked += "context" }
        override fun loadDocument(tab: PanelTab.Document, force: Boolean) { asked += "load:${tab.key}" }
        override fun openUrl(url: String) { asked += "url:$url" }
        override fun startSideChat(name: String?) { asked += "start-side-chat" }
    }

    private fun show() {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalPanelExpand provides expand) { ConversationPanel(state, actions, onClose = { closes++ }) }
            }
        }
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

    private fun inBreadcrumb(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("breadcrumb")))

    private fun pressBack() {
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun projectPanel(allFiles: Boolean = false, open: List<PanelTab> = emptyList(), selected: PanelTab? = null): PanelState = PanelFixtures.loaded().copy(
        agentId = coordinator.id,
        agent = coordinator,
        capabilities = Capabilities.EXTENDED,
        tabs = PanelTabsState(open, selected?.key),
        context = loadedContext.copy(allFiles = allFiles),
    )

    @Test
    fun `a chat of its own opens on its sections, and only a Project's chats get the Project tab`() {
        state = PanelFixtures.loaded()
        show()
        compose.onNodeWithTag("panel-tab-details").assertIsSelected()
        assertThat(hasTab("project")).isFalse()
        compose.onNode(hasText(PanelFixtures.agent.name) and hasAnyAncestor(hasTestTag("details-title"))).assertIsDisplayed()
        compose.onNode(hasText("cursor-for-android · cursor/theme-toggle-4f2a") and hasAnyAncestor(hasTestTag("details-title"))).assertIsDisplayed()
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
        // The pinned tabs carry no cross, and without Extended mode the strip has nothing to open from its plus.
        assertThat(compose.onAllNodesWithContentDescription("Close Details").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithTag("panel-tab-add").fetchSemanticsNodes()).isEmpty()
        // At home back is the host's, which shuts the panel; so does the strip's own control.
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
        compose.onNodeWithTag("panel-close").performClick()
        assertThat(closes).isEqualTo(1)

        assertThat(state.homeTab).isEqualTo(PanelTab.Details)
        assertThat(state.stripTabs).containsExactly(PanelTab.Details)
        val root = PanelFixtures.loaded().copy(agentId = coordinator.id, agent = coordinator)
        assertThat(root.projectRootId).isEqualTo("bc-root")
        assertThat(root.homeTab).isEqualTo(PanelTab.Project)
        assertThat(root.stripTabs).containsExactly(PanelTab.Project, PanelTab.Details).inOrder()
        val worker = PanelFixtures.loaded().copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER)), parentAgent = coordinator)
        assertThat(worker.projectRootId).isEqualTo("bc-root")
        assertThat(worker.projectRoot).isEqualTo(coordinator)
        assertThat(worker.homeTab).isEqualTo(PanelTab.Project)
        val sideChatOfPlainChat = PanelFixtures.loaded().copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-other", AgentParentKind.SIDE_CHAT)), parentAgent = PanelFixtures.agent.copy(id = "bc-other"))
        assertThat(sideChatOfPlainChat.hasProjectTab).isFalse()
    }

    @Test
    fun `the strip lists the pinned tabs then the opened ones, and back walks to the home tab without closing any`() {
        val agentTab = PanelTab.Agent(sideChat.id)
        state = projectPanel(open = listOf(specTab, agentTab)).copy(tabAgents = mapOf(sideChat.id to sideChat))
        show()
        compose.onNodeWithTag("panel-tab-project").assertIsSelected()
        assertThat(state.stripTabs.map { it.key }).containsExactly("project", "details", specTab.key, agentTab.key).inOrder()
        val lefts = state.stripTabs.map { tab -> compose.onNodeWithTag("panel-tab-${tab.key}").fetchSemanticsNode().positionInRoot.x }
        assertThat(lefts).isInStrictOrder()
        // Another chat's tab goes by the chat's name; only the tab showing carries its cross.
        assertThat(tabLabel(agentTab, state)).isEqualTo(sideChat.name)
        compose.onNodeWithTag("panel-tab-${agentTab.key}").assertTextContains(sideChat.name)
        assertThat(compose.onAllNodesWithContentDescription("Close ${sideChat.name}").fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithTag("panel-tab-${agentTab.key}").performClick()
        assertThat(asked).contains("select:${agentTab.key}")
        compose.onNodeWithTag("agent-tab").assertIsDisplayed()
        compose.onNodeWithTag("panel-tab-${agentTab.key}").assertIsSelected()
        compose.onNodeWithTag("panel-tab-project").assertIsNotSelected()
        compose.onNodeWithContentDescription("Close ${sideChat.name}").assertExists()
        assertThat(dispatcher.hasEnabledCallbacks()).isTrue()

        pressBack()
        assertThat(asked.count { it == "back" }).isEqualTo(1)
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
        assertThat(hasTab(agentTab.key)).isTrue()
        assertThat(closes).isEqualTo(0)
        assertThat(dispatcher.hasEnabledCallbacks()).isFalse()

        // A selection that is no longer on the strip shows the home tab.
        state = state.copy(tabs = state.tabs.copy(selectedKey = "doc:st-project:gone.md"))
        compose.onNodeWithTag("panel-tab-project").assertIsSelected()
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
    }

    @Test
    fun `the strip offers widening only where the host has room, and says which way it goes`() {
        var toggles = 0
        state = PanelFixtures.loaded()
        expand = PanelExpand(available = true, expanded = false, toggle = { toggles++ })
        show()
        compose.onNodeWithContentDescription("Expand panel").performClick()
        assertThat(toggles).isEqualTo(1)

        expand = PanelExpand(available = true, expanded = true, toggle = { toggles++ })
        compose.onNodeWithContentDescription("Restore panel width").performClick()
        assertThat(toggles).isEqualTo(2)

        expand = PanelExpand(available = false, expanded = false, toggle = { toggles++ })
        assertThat(compose.onAllNodesWithTag("panel-expand").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag("panel-close").assertIsDisplayed()
    }

    @Test
    fun `the Project tab sets the notes under the Project's name, its tasks behind rings`() {
        state = projectPanel()
        show()
        compose.onNodeWithTag("panel-tab-project").assertIsSelected()
        compose.onNodeWithTag("project-notes-title").assertTextEquals("Cesium billing launch")
        // The notes' own title line would repeat the header, so it is left out.
        assertThat(compose.onAllNodesWithText("Cesium billing launch").fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithTag("project-notes-body").assertIsDisplayed()
        assertThat(shown("Shipping")).isTrue()
        assertThat(shown("#218")).isTrue()
        assertThat(shown("Release v0.4.0")).isTrue()
        assertThat(compose.onAllNodesWithContentDescription("To do").fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithContentDescription("Done").fetchSemanticsNodes()).hasSize(1)
        assertThat(asked).contains("context")
    }

    @Test
    fun `links lead with what they point at, and a relative one is read against its store`() {
        assertThat(PanelLinks.glyphFor("https://github.com/techlitnow/cesium/pull/218")).isEqualTo(LinkGlyph.PullRequest)
        assertThat(PanelLinks.glyphFor("https://github.com/techlitnow/cesium/releases/tag/v0.4.0")).isEqualTo(LinkGlyph.GitHub)
        assertThat(PanelLinks.glyphFor("/cursor/stores/st-project/docs/spec.md")).isEqualTo(LinkGlyph.Document)
        assertThat(PanelLinks.glyphFor("/cursor/stores/st-project/media/shot.png")).isNull()
        assertThat(PanelLinks.glyphFor("https://example.com/page")).isNull()

        val base = StoreBase.of("st-project", "docs/spec.md")
        assertThat(base).isEqualTo(StoreBase("st-project", "docs"))
        assertThat(StoreBase.of("st-project", "notes.md").folder).isEmpty()
        assertThat(PanelLinks.resolveTarget("plan.md", base)).isEqualTo("/cursor/stores/st-project/docs/plan.md")
        assertThat(PanelLinks.resolveTarget("../notes.md", base)).isEqualTo("/cursor/stores/st-project/notes.md")
        assertThat(PanelLinks.resolveTarget("./a/b.md#top", base)).isEqualTo("/cursor/stores/st-project/docs/a/b.md")
        // Out of the store, a fragment, an address, an absolute path, an agent: none is the store's.
        assertThat(PanelLinks.resolveTarget("../../escape.md", base)).isNull()
        assertThat(PanelLinks.resolveTarget("#top", base)).isNull()
        assertThat(PanelLinks.resolveTarget("https://cursor.com", base)).isNull()
        assertThat(PanelLinks.resolveTarget("/etc/hosts", base)).isNull()
        assertThat(PanelLinks.resolveTarget("bc-0b7e6b5f-6c43-4c2a-9f55-3a1c1f1d2e3f", base)).isNull()
        assertThat(PanelLinks.resolve("See [the plan](plan.md), [the web](https://cursor.com) and ![a shot](shot.png).", base))
            .isEqualTo("See [the plan](/cursor/stores/st-project/docs/plan.md), [the web](https://cursor.com) and ![a shot](shot.png).")
    }

    @Test
    fun `the header's toggle turns the Project tab to All Files, whose files open as tabs, and back`() {
        state = projectPanel()
        show()
        compose.onNodeWithContentDescription("All Files").performClick()
        assertThat(asked).contains("project:true")
        compose.onNodeWithTag("all-files-tab").assertIsDisplayed()
        compose.onNodeWithContentDescription("All Files").assertIsSelected()
        assertThat(compose.onAllNodesWithTag("store-root").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithContentDescription("Project, open").assertIsDisplayed()
        compose.onNodeWithContentDescription("User, open").assertIsDisplayed()
        assertThat(shown("preferences.md")).isTrue()
        assertThat(shown("Today at")).isTrue()

        compose.onNodeWithContentDescription("Folder docs").performClick()
        assertThat(asked).contains("folder:st-project:docs")
        state = state.copy(context = state.context.copy(expandedFolders = state.context.expandedFolders + ContextPanelState.folderKey(projectStore, "docs")))
        compose.onNodeWithContentDescription("Folder docs, open").assertIsDisplayed()
        compose.onNodeWithContentDescription("File spec.md").performClick()
        assertThat(asked).contains("doc:st-project:docs/spec.md")

        compose.onNodeWithTag("all-files-tab").performScrollToNode(hasTestTag("recents-row"))
        assertThat(compose.onAllNodesWithTag("recent-tile").fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithContentDescription("notes.md").performClick()
        assertThat(asked).contains("doc:st-project:notes.md")

        compose.onNodeWithContentDescription("All Files").performClick()
        assertThat(asked).contains("project:false")
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
    }

    @Test
    fun `without Extended mode the Project tab names the mode and the way to cursor com`() {
        state = projectPanel().copy(context = ContextPanelState(stores = RemoteLoad.Unsupported("Needs Extended mode"), notes = RemoteLoad.Unsupported("Needs Extended mode"), recents = RemoteLoad.Unsupported("Needs Extended mode")))
        show()
        compose.onNodeWithTag("context-unavailable").assertIsDisplayed()
        assertThat(shown("Context needs Extended mode")).isTrue()
        compose.onNodeWithText("Open on cursor.com").performClick()
        assertThat(asked).contains("url:https://cursor.com/agents/bc-root")
    }

    @Test
    fun `a document tab reads under its breadcrumb, previews markdown, switches to its source and closes from its cross`() {
        state = projectPanel(open = listOf(specTab), selected = specTab)
        show()
        compose.onNodeWithTag("document-tab").assertIsDisplayed()
        assertThat(asked).contains("load:${specTab.key}")
        // From the Project down to the file.
        inBreadcrumb("Cesium billing launch").assertExists()
        inBreadcrumb("docs").assertExists()
        inBreadcrumb("spec.md").assertExists()
        compose.onNodeWithTag("segment-Preview").assertIsSelected()
        compose.onNodeWithTag("document-preview").assertIsDisplayed()
        assertThat(shown("The plan.")).isTrue()

        compose.onNodeWithTag("segment-Source").performClick()
        assertThat(asked).contains("source:true")
        compose.onNodeWithTag("segment-Source").assertIsSelected()
        compose.onNodeWithTag("document-source").assertIsDisplayed()
        assertThat(shown("# Spec")).isTrue()

        compose.onNodeWithTag("panel-back").performClick()
        assertThat(asked).contains("back")
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
        assertThat(hasTab(specTab.key)).isTrue()

        state = state.copy(tabs = state.tabs.copy(selectedKey = specTab.key))
        compose.onNodeWithContentDescription("Close spec.md").performClick()
        assertThat(asked).contains("close:${specTab.key}")
        assertThat(asked).doesNotContain("select:${specTab.key}")
        assertThat(hasTab(specTab.key)).isFalse()
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
    }

    @Test
    fun `the strip's plus opens All Files in a Project and starts a side chat`() {
        state = projectPanel()
        show()
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("All files").performClick()
        assertThat(asked).contains("project:true")
        compose.onNodeWithTag("all-files-tab").assertIsDisplayed()
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("New side chat").performClick()
        assertThat(asked).contains("start-side-chat")

        // A chat of its own has no Project's files to list: its plus only starts a side chat.
        state = PanelFixtures.withSideChats()
        compose.onNodeWithTag("panel-tab-add").performClick()
        compose.onNodeWithText("New side chat").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("All files").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `another chat's tab names it and where it stands, opens it as the conversation, and goes back`() {
        val tab = PanelTab.Agent(sideChat.id)
        state = PanelFixtures.withSideChats().copy(tabs = PanelTabsState(listOf(tab), tab.key), tabAgents = mapOf(sideChat.id to sideChat))
        show()
        compose.onNodeWithTag("agent-tab").assertIsDisplayed()
        compose.onNodeWithTag("agent-tab-title").assertTextEquals(sideChat.name)
        assertThat(shown("Running · cursor-for-android")).isTrue()
        // Rendered without the app, the tab has no transcript to attach and says where it would be.
        assertThat(shown("The transcript opens here inside a running conversation.")).isTrue()

        compose.onNodeWithTag("agent-tab-open").performClick()
        assertThat(asked).contains("chat:${sideChat.id}")
        compose.onNodeWithTag("panel-back").performClick()
        assertThat(asked).contains("back")
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
        assertThat(hasTab(tab.key)).isTrue()
    }

    @Test
    fun `a side chat's row opens the side chat in a tab of the panel`() {
        state = PanelFixtures.withSideChats()
        show()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-SideChats"))
        compose.onNodeWithTag("section-SideChats").performClick()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("side-chat"))
        compose.onAllNodesWithTag("side-chat")[0].performClick()
        assertThat(asked).contains("agent:${sideChat.id}")
        compose.onNodeWithTag("agent-tab").assertIsDisplayed()
        compose.onNodeWithTag("panel-tab-agent:${sideChat.id}").assertIsSelected()
        compose.onNodeWithTag("agent-tab-title").assertTextEquals(sideChat.name)
        // A chat of its own: the tab sits beside Details, with no Project tab.
        assertThat(hasTab("project")).isFalse()
    }

    @Test
    fun `a file tab shows where the file stands and where its copy came from, and closes from its cross`() {
        val file = RepoFile(PanelFixtures.toggleWrite.path, "fun ThemeToggle() = Unit\n".toByteArray(), 25L, sha = "abc", downloadUrl = "https://raw.githubusercontent.com/x")
        state = PanelFixtures.withFile(PanelFixtures.loaded(), FileView.Repository(file))
        show()
        compose.onNodeWithTag("file-viewer").assertIsDisplayed()
        compose.onNodeWithTag("panel-tab-file:${file.path}").assertIsSelected().assertTextContains("ThemeToggle.kt")
        inBreadcrumb("settings").assertExists()
        inBreadcrumb("ThemeToggle.kt").assertExists()
        compose.onNodeWithTag("file-caption").assertTextEquals("From the repository · 25 B")

        compose.onNodeWithContentDescription("Open in browser").performClick()
        assertThat(asked).contains("url:https://raw.githubusercontent.com/x")
        compose.onNodeWithContentDescription("Close ThemeToggle.kt").performClick()
        assertThat(asked).contains("close:file:${file.path}")
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
    }

    @Test
    fun `a picture opens as a tab of its own under its name`() {
        val tab = PanelTab.Media("/opt/cursor/artifacts/theme-toggle.png", "theme-toggle.png")
        state = PanelFixtures.loaded().copy(tabs = PanelTabsState(listOf(tab), tab.key))
        show()
        compose.onNodeWithTag("media-tab").assertIsDisplayed()
        // The name heads the tab; with no loader here, the figure's own row under it names the file too.
        compose.onAllNodes(hasText("theme-toggle.png") and hasAnyAncestor(hasTestTag("media-tab")))[0].assertIsDisplayed()
        compose.onNodeWithTag("panel-back").performClick()
        assertThat(asked).contains("back")
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
    }

    @Test
    fun `a tab keeps its scroll while another shows, and a closed one reopens at the top`() {
        val long = ContextDocument(projectStore, "docs/long.md", (1..80).joinToString("\n\n") { "Paragraph $it of the long document." })
        val tab = PanelTab.Document(projectStore.storeId, long.path)
        state = projectPanel(open = listOf(tab), selected = tab).let { it.copy(context = it.context.copy(documents = it.context.documents + (tab.key to RemoteLoad.Loaded(long)))) }
        show()
        compose.onNodeWithTag("document-preview").performScrollToNode(hasText("Paragraph 80 of the long document."))
        assertThat(firstParagraphTop()).isLessThan(0f)

        compose.onNodeWithTag("panel-tab-details").performClick()
        compose.onNodeWithTag("panel-sections").assertIsDisplayed()
        compose.onNodeWithTag("panel-tab-${tab.key}").performClick()
        compose.onNodeWithTag("document-preview").assertIsDisplayed()
        assertThat(firstParagraphTop()).isLessThan(0f)

        compose.onNodeWithContentDescription("Close long.md").performClick()
        compose.onNodeWithTag("project-notes-tab").assertIsDisplayed()
        state = state.copy(tabs = PanelTabsState(listOf(tab), tab.key))
        compose.onNodeWithTag("document-preview").assertIsDisplayed()
        assertThat(firstParagraphTop()).isAtLeast(0f)
    }

    private fun firstParagraphTop(): Float = compose.onNode(hasText("Paragraph 1 of the long document.")).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the tabs' helpers name, place and date what they show`() {
        assertThat(PanelTab.Agent("bc-1").key).isEqualTo("agent:bc-1")
        assertThat(specTab.key).isEqualTo("doc:st-project:docs/spec.md")
        assertThat(PanelTab.File("a/b.kt").key).isEqualTo("file:a/b.kt")
        assertThat(PanelTab.Media("https://x/y.png", "y.png").key).isEqualTo("media:https://x/y.png")
        assertThat(PanelTab.Project.closable).isFalse()
        assertThat(PanelTab.Details.closable).isFalse()
        assertThat(specTab.closable).isTrue()

        val plain = PanelFixtures.loaded()
        assertThat(tabLabel(PanelTab.Project, plain)).isEqualTo("Project")
        assertThat(tabLabel(PanelTab.Details, plain)).isEqualTo("Details")
        assertThat(tabLabel(PanelTab.Agent("bc-unknown"), plain)).isEqualTo("Agent")
        assertThat(tabLabel(specTab, plain)).isEqualTo("spec.md")
        assertThat(tabLabel(PanelTab.File(PanelFixtures.toggleWrite.path), plain)).isEqualTo("ThemeToggle.kt")
        assertThat(tabIcon(PanelTab.Agent("bc-1"))).isNull()
        assertThat(tabIcon(specTab)).isEqualTo(PanelIcons.Markdown)
        assertThat(tabIcon(PanelTab.Media("a.mp4", "a.mp4", isVideo = true))).isEqualTo(CursorIcons.Video)

        assertThat(pathSegments("docs/a/spec.md")).containsExactly("docs", "a", "spec.md").inOrder()
        assertThat(pathSegments("/docs/")).containsExactly("docs")
        assertThat(fileCaption(FileView.Transcript(PanelFixtures.themeRead))).isEqualTo("As the agent read it · 141 lines")
        assertThat(fileCaption(FileView.Loading("a.kt"))).isNull()
        assertThat(agentCaption(null)).isNull()
        assertThat(agentCaption(PanelFixtures.agent)).isEqualTo("Finished · cursor-for-android · cursor/theme-toggle-4f2a")
        assertThat(agentCaption(sideChat)).isEqualTo("Running · cursor-for-android")

        assertThat(withoutLeadingTitle("# Cesium\n\n## Shipping")).isEqualTo("## Shipping")
        assertThat(withoutLeadingTitle("## Shipping\n\nText")).isEqualTo("## Shipping\n\nText")
        assertThat(contentInset(360.dp)).isEqualTo(PanelGutter)
        assertThat(contentInset(775.dp)).isEqualTo(32.dp)

        // Wednesday 2025-01-15 14:00 UTC.
        val now = PanelFixtures.NOW
        val hour = 3_600_000L
        val utc = ZoneOffset.UTC
        assertThat(writtenAt(now - 60_000L, now, utc)).startsWith("Today at ")
        assertThat(writtenAt(now - 26 * hour, now, utc)).startsWith("Yesterday at ")
        assertThat(writtenAt(now - 72 * hour, now, utc)).startsWith(DayOfWeek.SUNDAY.getDisplayName(DateTextStyle.FULL, Locale.getDefault()) + " at ")
        assertThat(writtenAt(now - 240 * hour, now, utc)).doesNotContain("2025")
        assertThat(writtenAt(now - 400 * 24 * hour, now, utc)).contains("2023")
    }
}
