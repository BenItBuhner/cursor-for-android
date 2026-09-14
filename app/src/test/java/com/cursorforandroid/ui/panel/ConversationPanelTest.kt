package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.MachineStatus
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's sections: which of them a chat gets and in what order, what each shows for the chat's state, and the
 * named state each degrades to. A section with nothing to show is not there; one gated on a private surface is not
 * there while the surface is off.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ConversationPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PanelFixtures.loaded())

    /** What the sections asked for, so a test can check a section loads on opening. */
    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun setSectionExpanded(id: PanelSectionId, expanded: Boolean) { asked += "expanded:${id.name}=$expanded" }
        override fun loadPullRequest(force: Boolean) { asked += "pr" }
        override fun loadArtifacts(force: Boolean) { asked += "artifacts" }
        override fun loadUsage(force: Boolean) { asked += "usage" }
        override fun loadMachine(force: Boolean) { asked += if (force) "machine!" else "machine" }
        override fun openUrl(url: String) { asked += "open:$url" }
        override fun copyText(text: String, confirmation: String) { asked += "copy:$text" }
        override fun openArtifact(artifact: Artifact) { asked += "artifact:${artifact.path}" }
        override fun openChange(change: TranscriptContent.FileChange) { asked += "change:${change.path}" }
        override fun openTouched(path: String) { asked += "touched:$path" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag(tag))

    private fun open(section: PanelSectionId) {
        scrollTo("section-${section.name}")
        compose.onNodeWithTag("section-${section.name}").performClick()
    }

    /** Scrolls the sections to the first node with [text] and taps it; a node below the fold takes no tap otherwise. */
    private fun click(text: String) {
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text)[0].performClick()
    }

    private fun clickTag(tag: String) {
        scrollTo(tag)
        compose.onAllNodesWithTag(tag)[0].performClick()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Whether the panel draws [id]'s header, scrolling the list to it: a section past the fold is not composed until it is. */
    private fun hasSection(id: PanelSectionId) = runCatching { scrollTo("section-${id.name}") }.isSuccess

    /** The titles of the sections the registry draws for [state] with [capabilities]. */
    private fun titles(state: PanelState, capabilities: Capabilities = state.capabilities) = PanelRegistry.default().shown(capabilities, state).map { it.title }

    @Test
    fun `the registry keeps the sections in the web panel's order, and a finished chat with a pull request gets the ones it has something for`() {
        assertThat(PanelRegistry.default().sections.map { it.title }).isEqualTo(listOf("Overview", "Pending question", "Changes", "Pull request", "Files", "Artifacts", "Side chats", "Project", "Usage"))
        // No question waiting, no side chats known with the mode off, not part of a Project: those three are not drawn.
        assertThat(titles(PanelFixtures.loaded())).isEqualTo(listOf("Overview", "Changes", "Pull request", "Files", "Artifacts", "Usage"))
        show()
        titles(state).forEach { title ->
            compose.onNodeWithTag("panel-sections").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        // The list asks for the artifacts as the panel opens, so the section can know whether it belongs.
        assertThat(asked).contains("artifacts")
    }

    @Test
    fun `sections with nothing to show are left out, and come in with what feeds them`() {
        // A brand-new chat: nothing touched, no repository, no pull request, no artifacts — its facts and its (empty) changes alone.
        assertThat(titles(PanelFixtures.empty())).isEqualTo(listOf("Overview", "Changes", "Usage"))
        // A question waiting brings the section in, in either mode.
        val asking = PanelFixtures.loaded().copy(content = TranscriptContent.of(PanelFixtures.itemsWithQuestion()))
        assertThat(titles(asking)).contains("Pending question")
        assertThat(titles(asking, Capabilities.EXTENDED)).contains("Pending question")
        // Artifacts: there with a tile or a file, or a failed read worth retrying; not while nothing has been found.
        val bare = PanelFixtures.loaded().copy(content = TranscriptContent.EMPTY, artifacts = RemoteLoad.Loaded(emptyList()))
        assertThat(titles(bare)).doesNotContain("Artifacts")
        assertThat(titles(bare.copy(artifacts = RemoteLoad.Loading))).doesNotContain("Artifacts")
        assertThat(titles(bare.copy(artifacts = RemoteLoad.Failed("rate limited")))).contains("Artifacts")
        assertThat(titles(bare.copy(artifacts = RemoteLoad.Loaded(PanelFixtures.artifacts.take(1))))).contains("Artifacts")
        // Files: the touched list, a browsable repository, or (Extended) the live workspace; none of them, no section.
        val untouched = PanelFixtures.loaded().copy(content = TranscriptContent.EMPTY)
        assertThat(titles(untouched)).contains("Files")
        assertThat(titles(untouched.copy(browser = RepoBrowserState()))).doesNotContain("Files")
        assertThat(titles(untouched.copy(browser = RepoBrowserState()), Capabilities.EXTENDED)).contains("Files")
    }

    @Test
    fun `the pull request section is there with a pull request, or when Extended mode can open one`() {
        val noPr = PanelFixtures.loaded().copy(pullRequest = RemoteLoad.Idle, agent = PanelFixtures.agent.copy(branches = listOf(PanelFixtures.agent.branches.single().copy(prUrl = null))))
        assertThat(titles(noPr)).doesNotContain("Pull request")
        assertThat(titles(noPr, Capabilities.EXTENDED)).contains("Pull request")
        // No branch to open one from: not even in Extended mode.
        assertThat(titles(noPr.copy(agent = PanelFixtures.agent.copy(branches = emptyList())), Capabilities.EXTENDED)).doesNotContain("Pull request")
        // The demo has no host to open one on.
        assertThat(titles(noPr.copy(isDemo = true), Capabilities.EXTENDED)).doesNotContain("Pull request")
        assertThat(titles(PanelFixtures.loaded())).contains("Pull request")
    }

    @Test
    fun `side chats are a section for any chat that has them or can start one, and the Project section is for Project-scoped chats alone`() {
        val plain = PanelFixtures.loaded()
        assertThat(titles(plain)).containsNoneOf("Side chats", "Project")
        // Extended mode can start one off any chat, so the section is there to do it from; not for an archived chat, nor in the demo,
        // nor inside a side chat (the Agents Window's rule: no side chat of a side chat).
        assertThat(titles(plain, Capabilities.EXTENDED)).contains("Side chats")
        assertThat(titles(plain.copy(agent = PanelFixtures.agent.copy(lifecycle = AgentLifecycle.ARCHIVED)), Capabilities.EXTENDED)).doesNotContain("Side chats")
        assertThat(titles(plain.copy(isDemo = true), Capabilities.EXTENDED)).doesNotContain("Side chats")
        assertThat(titles(plain.copy(agent = PanelFixtures.sideChats[0], agentId = "bc-side-1"), Capabilities.EXTENDED)).doesNotContain("Side chats")
        // Side chats the list knows show in either mode.
        assertThat(titles(plain.copy(sideChats = PanelFixtures.sideChats))).contains("Side chats")
        // The Project section: a coordinator, or a chat the account placed under one — never a chat of the account's own, in either mode.
        assertThat(titles(PanelFixtures.projectRoot())).contains("Project")
        assertThat(titles(PanelFixtures.projectRoot(), Capabilities.EXTENDED)).contains("Project")
        val worker = plain.copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER), scopeSignal = LineageSignal.MEMBERSHIP))
        assertThat(titles(worker)).contains("Project")
        assertThat(titles(plain, Capabilities.EXTENDED)).doesNotContain("Project")
        // A worker the coordinator's create_agent named (default mode's word for the record's managerAgentId) is placed like one.
        val created = plain.copy(agent = PanelFixtures.agent.copy(parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER), scopeSignal = LineageSignal.COORDINATOR_CREATED))
        assertThat(titles(created)).contains("Project")
        // A side chat is in a Project only when the chat it branched from is one; a side chat of an ordinary chat is not.
        val sideChat = plain.copy(agent = PanelFixtures.sideChats[0], agentId = "bc-side-1", parentAgent = PanelFixtures.agent)
        assertThat(titles(sideChat)).doesNotContain("Project")
        assertThat(titles(sideChat.copy(parentAgent = PanelFixtures.projectRoot().agent))).contains("Project")
        assertThat(titles(sideChat.copy(parentAgent = null))).doesNotContain("Project")
    }

    @Test
    fun `a side chat's overview names the chat it branched from and opens it`() {
        state = PanelFixtures.loaded().copy(agent = PanelFixtures.sideChats[0], agentId = "bc-side-1", parentAgent = PanelFixtures.agent)
        val opened = mutableListOf<String>()
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, object : PanelActions by PanelActions.None { override fun openAgent(agentId: String) { opened += agentId } }, onClose = {}) } }
        compose.onNodeWithTag("parent-fact").assertIsDisplayed()
        assertThat(shown("Side chat of")).isTrue()
        assertThat(shown("Dark theme toggle for Settings")).isTrue()
        compose.onNodeWithTag("parent-fact").performClick()
        assertThat(opened).containsExactly("bc-demo")
        // The parent's row not loaded yet: the link still stands, to "another chat".
        state = state.copy(parentAgent = null)
        compose.waitForIdle()
        assertThat(shown("another chat")).isTrue()
    }

    @Test
    fun `a section the reader opened or closed is reported, and a reopened panel starts from what was remembered`() {
        var panelOpen by mutableStateOf(true)
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { if (panelOpen) ConversationPanel(state, actions, onClose = {}) } }
        // Usage is closed by default; opening it is reported to whoever remembers, and closing Changes too.
        open(PanelSectionId.Usage)
        assertThat(asked).contains("expanded:Usage=true")
        open(PanelSectionId.Changes)
        assertThat(asked).contains("expanded:Changes=false")
        assertThat(shown("From the pull request · 3 files")).isFalse()

        // Closed and opened again with what was remembered: Usage open, Changes closed, without a tap.
        asked.clear()
        panelOpen = false
        compose.waitForIdle()
        state = state.copy(expandedSections = mapOf(PanelSectionId.Usage to true, PanelSectionId.Changes to false))
        panelOpen = true
        compose.waitForIdle()
        scrollTo("section-${PanelSectionId.Changes.name}")
        assertThat(shown("From the pull request · 3 files")).isFalse()
        assertThat(asked).contains("usage")
    }

    @Test
    fun `the overview reads the agent's facts in a few rows, and the changes list the conversation's edits`() {
        show()
        // The status is the Overview's hint and its first fact.
        assertThat(compose.onAllNodesWithText("Finished").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("bennett/cursor-for-android").assertIsDisplayed()
        compose.onNodeWithText("cursor/theme-toggle-4f2a").assertIsDisplayed()
        compose.onNodeWithText("Claude Fable 5.1").assertIsDisplayed()
        // When it started and how long it worked share a row; a cloud chat's environment and its source are not rows.
        assertThat(shown("Jan 15")).isTrue()
        assertThat(shown(" · worked 34m 0s")).isTrue()
        assertThat(shown("Environment")).isFalse()
        assertThat(shown("Started from")).isFalse()
        // Three files changed; the deleted one carries no counts, so no total is claimed.
        assertThat(compose.onAllNodesWithText("3 files").fetchSemanticsNodes()).isNotEmpty()
        // Changes is open by default and prefers the pull request's files once they are loaded.
        scrollTo("section-Changes")
        assertThat(shown("From the pull request · 3 files")).isTrue()
        assertThat(compose.onAllNodesWithTag("changed-file").fetchSemanticsNodes()).hasSize(3)
        // Opening the changes section asked for the pull request.
        assertThat(asked).contains("pr")
    }

    @Test
    fun `a Remote Control chat's machine is an Overview fact, read from the fleet endpoint, in either mode`() {
        state = PanelFixtures.remoteControl()
        show()
        assertThat(asked).contains("machine")
        assertThat(shown("Remote Control · studio-mac")).isTrue()
        compose.onNodeWithTag("machine-fact").assertIsDisplayed()
        assertThat(shown("Connected · working on this chat")).isTrue()
        compose.onNodeWithTag("machine-fact").performClick()
        assertThat(asked).contains("machine!")

        state = state.copy(machine = RemoteLoad.Loaded(MachineStatus("studio-mac", connected = false)))
        compose.waitForIdle()
        assertThat(shown("Not connected")).isTrue()
        state = state.copy(machine = RemoteLoad.Failed("Fleet endpoints need a pool service account."))
        compose.waitForIdle()
        assertThat(shown("pool service account")).isTrue()
        // A chat in the cloud has no machine row, and asks for none.
        asked.clear()
        state = PanelFixtures.loaded().copy(agent = PanelFixtures.agent.copy(envType = EnvType.CLOUD))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("machine-fact").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a changed file opens onto its patch, and a removed one says there is nothing to show`() {
        show()
        clickTag("changed-file")
        scrollTo("diff-block")
        compose.onNodeWithTag("diff-block").assertIsDisplayed()
        assertThat(shown("+        ToggleRow(\"Dim wallpaper\"")).isTrue()
        assertThat(shown("Removed; nothing to show.")).isTrue()
    }

    @Test
    fun `without a pull request the changes come from the conversation and open into the viewer, and there is no pull request section`() {
        state = PanelFixtures.loaded().copy(pullRequest = RemoteLoad.Idle, agent = PanelFixtures.agent.copy(branches = emptyList()))
        show()
        scrollTo("section-Changes")
        assertThat(shown("From this conversation · 3 files")).isTrue()
        val rows = compose.onAllNodesWithTag("file-change").fetchSemanticsNodes()
        assertThat(rows).hasSize(3)
        click("SettingsScreen.kt")
        assertThat(asked).contains("change:${PanelFixtures.settingsDiff.path}")
        // No pull request and no way to open one from here: nothing to say, so no section.
        assertThat(hasSection(PanelSectionId.PullRequest)).isFalse()
        assertThat(shown("No pull request yet")).isFalse()
    }

    @Test
    fun `the pull request shows its title, body, chips, checks and threads verbatim`() {
        show()
        open(PanelSectionId.PullRequest)
        scrollTo("pull-request")
        compose.onNodeWithText("Settings: a dark theme toggle beside the theme row").assertIsDisplayed()
        assertThat(shown("#97 · by cursor[bot] · cursor/theme-toggle-4f2a → main · +61 -12 · 3 files · 2 commits")).isTrue()
        assertThat(shown("Open")).isTrue()
        assertThat(shown("1 of 3 checks running")).isTrue()
        assertThat(shown("Adds ")).isTrue()
        assertThat(compose.onAllNodesWithTag("check-run").fetchSemanticsNodes()).hasSize(3)
        assertThat(shown("Should the widget follow the toggle too?")).isTrue()
        assertThat(shown("bennett commented")).isTrue()
        click("Open in browser")
        assertThat(asked).contains("open:https://github.com/bennett/cursor-for-android/pull/97")
    }

    @Test
    fun `the pull request section names each degraded state`() {
        state = PanelFixtures.loaded().copy(pullRequest = RemoteLoad.Loading)
        show()
        open(PanelSectionId.PullRequest)
        assertThat(shown("Reading the pull request…")).isTrue()

        state = state.copy(pullRequest = RemoteLoad.Unsupported("This pull request is on Origin. Reading it needs an Origin access token, which this app cannot mint yet.", "https://origin.cursor.com/a/r/pulls/1"))
        compose.waitForIdle()
        scrollTo("section-unsupported")
        compose.onNodeWithTag("section-unsupported").assertIsDisplayed()
        assertThat(shown("Origin access token")).isTrue()

        state = state.copy(pullRequest = RemoteLoad.Failed("GitHub's anonymous rate limit for this network is used up."))
        compose.waitForIdle()
        scrollTo("section-failed")
        compose.onNodeWithTag("section-failed").assertIsDisplayed()
        click("Retry")
        assertThat(asked.count { it == "pr" }).isAtLeast(2)

        state = state.copy(pullRequest = RemoteLoad.Failed("GitHub would not show this pull request anonymously.", retryable = false))
        compose.waitForIdle()
        assertThat(shown("Retry")).isFalse()
        assertThat(shown("Open in browser")).isTrue()
    }

    @Test
    fun `files has the touched list and the repository browser, and no workspace tab while the mode is off`() {
        show()
        open(PanelSectionId.Files)
        assertThat(compose.onAllNodesWithTag("touched-file").fetchSemanticsNodes()).hasSize(4)
        click("CursorTheme.kt")
        assertThat(asked).contains("touched:${PanelFixtures.themeRead.path}")

        clickTag("files-tab-Repository")
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("README.md"))
        assertThat(compose.onAllNodesWithTag("repo-dir").fetchSemanticsNodes()).hasSize(4)
        assertThat(compose.onAllNodesWithTag("repo-file").fetchSemanticsNodes()).hasSize(3)
        // The branch the tree is read at is named.
        assertThat(shown("cursor/theme-toggle-4f2a")).isTrue()

        // The live workspace is an Extended-mode read: its tab is not offered, and nothing names the endpoint.
        assertThat(compose.onAllNodesWithTag("files-tab-Workspace").fetchSemanticsNodes()).isEmpty()
        assertThat(shown("ListWorkspaceFiles")).isFalse()
        assertThat(shown("Needs Extended mode")).isFalse()
        state = state.copy(capabilities = Capabilities.EXTENDED)
        compose.waitForIdle()
        scrollTo("files-tab-Workspace")
        compose.onNodeWithTag("files-tab-Workspace").assertIsDisplayed()
    }

    @Test
    fun `artifacts gather the gallery and the files in one section, and usage reads the documented endpoint`() {
        show()
        open(PanelSectionId.Artifacts)
        scrollTo("media-grid")
        // The recording from the stream and the artifact of the same path show once; the generated image without kept pixels is not a tile.
        assertThat(shown("theme-toggle.mp4")).isTrue()
        assertThat(shown("settings-dark.png")).isTrue()
        assertThat(compose.onAllNodesWithText("Recording").fetchSemanticsNodes()).hasSize(1)
        // The artifacts that are not pictures follow the gallery as rows; the pictures are tiles, not rows too.
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("notes.md"))
        assertThat(compose.onAllNodesWithTag("artifact").fetchSemanticsNodes()).hasSize(1)
        click("notes.md")
        assertThat(asked).contains("artifact:artifacts/notes.md")
        // The hint counts both: two tiles and one file.
        assertThat(PanelRegistry.default()[PanelSectionId.Artifacts]!!.hint(state)).isEqualTo("3")

        open(PanelSectionId.Usage)
        scrollTo("usage")
        assertThat(asked).contains("usage")
        assertThat(shown("314.5k")).isTrue()
        assertThat(shown("By run")).isTrue()
    }

    @Test
    fun `no placeholder names an endpoint while the mode is off, and turning it on brings the sections in`() {
        show()
        for (id in PanelSectionId.entries) {
            if (hasSection(id)) open(id)
        }
        assertThat(shown("Needs Extended mode")).isFalse()
        assertThat(shown("Extended mode")).isFalse()
        assertThat(shown("Not yet")).isFalse()
        assertThat(hasSection(PanelSectionId.SideChats)).isFalse()
        assertThat(hasSection(PanelSectionId.Project)).isFalse()

        state = state.copy(capabilities = Capabilities.EXTENDED)
        compose.waitForIdle()
        assertThat(hasSection(PanelSectionId.SideChats)).isTrue()
        // Not part of a Project: the mode alone brings no Project section.
        assertThat(hasSection(PanelSectionId.Project)).isFalse()
        assertThat(shown("Needs Extended mode")).isFalse()
        assertThat(shown("Arrives with a later build")).isFalse()
    }

    @Test
    fun `the Project section is the Projects work's own, for a coordinator or a chat placed under one`() {
        val project = PanelRegistry.default()[PanelSectionId.Project]!!
        assertThat(project.isShown(Capabilities.DOCUMENTED, PanelFixtures.loaded())).isFalse()
        assertThat(project.isShown(Capabilities.EXTENDED, PanelFixtures.loaded())).isFalse()
        assertThat(project.isShown(Capabilities.DOCUMENTED, PanelFixtures.projectRoot())).isTrue()
        assertThat(project.hint(PanelFixtures.projectRoot())).isEqualTo("Coordinator")

        // Rendered on its own, without the graph it owns a view model through, it says so rather than crashing.
        state = PanelFixtures.projectRoot()
        show()
        open(PanelSectionId.Project)
        assertThat(shown("The Project section needs the app to render")).isTrue()
    }

    @Test
    fun `a chat with nothing yet has its facts and an empty changes section, and nothing else to open`() {
        state = PanelFixtures.empty()
        show()
        assertThat(compose.onAllNodesWithText("Working").fetchSemanticsNodes()).hasSize(2)
        scrollTo("section-Changes")
        assertThat(shown("No changes yet")).isTrue()
        assertThat(hasSection(PanelSectionId.PullRequest)).isFalse()
        assertThat(hasSection(PanelSectionId.Files)).isFalse()
        assertThat(hasSection(PanelSectionId.Artifacts)).isFalse()
        assertThat(hasSection(PanelSectionId.PendingQuestion)).isFalse()
    }

    @Test
    fun `a file opened from the panel takes the sections' place and comes back`() {
        state = PanelFixtures.loaded().copy(browser = PanelFixtures.loaded().browser.copy(file = FileView.Transcript(PanelFixtures.themeRead)))
        show()
        compose.onNodeWithTag("file-viewer").assertIsDisplayed()
        compose.onNodeWithText("CursorTheme.kt").assertIsDisplayed()
        assertThat(shown("As the agent read it · 141 lines")).isTrue()
        assertThat(shown("fun CursorTheme(mode: ThemeMode")).isTrue()
        assertThat(compose.onAllNodesWithTag("panel-sections").fetchSemanticsNodes()).isEmpty()

        state = state.copy(browser = state.browser.copy(file = FileView.Changes(state.content.changes.first())))
        compose.waitForIdle()
        compose.onNodeWithTag("diff-block").assertIsDisplayed()
        assertThat(shown("1 edit · +5 -2")).isTrue()
    }
}
