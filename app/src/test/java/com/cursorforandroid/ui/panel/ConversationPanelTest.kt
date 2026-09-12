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
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The panel's sections: their order, what each shows for a chat's state, and the named state each degrades to. */
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
        override fun loadPullRequest(force: Boolean) { asked += "pr" }
        override fun loadArtifacts(force: Boolean) { asked += "artifacts" }
        override fun loadUsage(force: Boolean) { asked += "usage" }
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

    @Test
    fun `every registered section has a header, in the spec's order`() {
        show()
        val expected = listOf("Overview", "Pending question", "Changes", "Pull request", "Files", "Images and media", "Artifacts", "Queue and steering", "Project", "Remote", "Usage", "Share")
        expected.forEach { title ->
            compose.onNodeWithTag("panel-sections").performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        assertThat(PanelRegistry.default().sections.map { it.title }).isEqualTo(expected)
    }

    @Test
    fun `the overview reads the agent's facts, and the changes list the conversation's edits`() {
        show()
        // The status is the Overview's hint and its first fact.
        assertThat(compose.onAllNodesWithText("Finished").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("bennett/cursor-for-android").assertIsDisplayed()
        compose.onNodeWithText("cursor/theme-toggle-4f2a").assertIsDisplayed()
        compose.onNodeWithText("Claude Fable 5.1").assertIsDisplayed()
        // Changes is open by default and prefers the pull request's files once they are loaded.
        scrollTo("section-Changes")
        assertThat(shown("From the pull request · 3 files")).isTrue()
        assertThat(compose.onAllNodesWithTag("changed-file").fetchSemanticsNodes()).hasSize(3)
        // Opening the changes section asked for the pull request.
        assertThat(asked).contains("pr")
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
    fun `without a pull request the changes come from the conversation and open into the viewer`() {
        state = PanelFixtures.loaded().copy(pullRequest = RemoteLoad.Idle, agent = PanelFixtures.agent.copy(branches = emptyList()))
        show()
        scrollTo("section-Changes")
        assertThat(shown("From this conversation · 3 files")).isTrue()
        val rows = compose.onAllNodesWithTag("file-change").fetchSemanticsNodes()
        assertThat(rows).hasSize(3)
        click("SettingsScreen.kt")
        assertThat(asked).contains("change:${PanelFixtures.settingsDiff.path}")
        // No pull request: the section for it says so rather than loading forever.
        open(PanelSectionId.PullRequest)
        assertThat(shown("No pull request yet")).isTrue()
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
    fun `files has the touched list, the repository browser and a gated workspace tab`() {
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

        clickTag("files-tab-Workspace")
        assertThat(shown("ListWorkspaceFiles")).isTrue()
        assertThat(shown("Needs Extended mode")).isTrue()
    }

    @Test
    fun `media, artifacts and usage read from the transcript and the documented endpoints`() {
        show()
        open(PanelSectionId.Media)
        scrollTo("media-grid")
        assertThat(asked).contains("artifacts")
        // The recording from the stream and the artifact of the same path show once; the generated image without kept pixels is not a tile.
        assertThat(shown("theme-toggle.mp4")).isTrue()
        assertThat(shown("settings-dark.png")).isTrue()
        assertThat(compose.onAllNodesWithText("Recording").fetchSemanticsNodes()).hasSize(1)

        open(PanelSectionId.Artifacts)
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("notes.md"))
        assertThat(compose.onAllNodesWithTag("artifact").fetchSemanticsNodes()).hasSize(3)
        click("notes.md")
        assertThat(asked).contains("artifact:artifacts/notes.md")

        open(PanelSectionId.Usage)
        scrollTo("usage")
        assertThat(asked).contains("usage")
        assertThat(shown("314.5k")).isTrue()
        assertThat(shown("By run")).isTrue()
    }

    @Test
    fun `the Extended-mode sections name what they need in the default mode, and are the real sections with it on`() {
        show()
        for (id in listOf(PanelSectionId.Queue, PanelSectionId.Project, PanelSectionId.Remote)) {
            open(id)
        }
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText("Remote"))
        assertThat(compose.onAllNodesWithText("Needs Extended mode").fetchSemanticsNodes().size).isAtLeast(3)
        assertThat(shown("ListPendingFollowups")).isTrue()
        assertThat(shown("ListWorkersForManager")).isTrue()
        assertThat(shown("GetMachine")).isTrue()
        // Pending question is open by default: no question waiting, so the section is the answer placeholder.
        scrollTo("section-PendingQuestion")
        assertThat(shown("SubmitInteractionResponseBackgroundComposer")).isTrue()

        // With the mode on, every one of them is the real section (the queue, the Project view, the Remote desktop):
        // no placeholder of either kind is left.
        state = state.copy(capabilities = Capabilities.EXTENDED)
        compose.waitForIdle()
        assertThat(shown("Needs Extended mode")).isFalse()
        assertThat(shown("Arrives with a later build")).isFalse()
    }

    @Test
    fun `the Project section is the Projects work's own, behind the projects capability`() {
        val project = PanelRegistry.default()[PanelSectionId.Project]!!
        val off = project.availability(Capabilities.DOCUMENTED, state)
        assertThat(off).isInstanceOf(SectionAvailability.RequiresExtended::class.java)
        // Ready: the mode alone is what it waits on, not a later build.
        assertThat((off as SectionAvailability.RequiresExtended).ready).isTrue()
        assertThat(project.availability(Capabilities.EXTENDED, state)).isEqualTo(SectionAvailability.Available)
        assertThat(project.availability(Capabilities.DOCUMENTED.copy(projects = true), state)).isEqualTo(SectionAvailability.Available)

        // Rendered on its own, without the graph it owns a view model through, it says so rather than crashing.
        state = state.copy(capabilities = Capabilities.EXTENDED)
        show()
        open(PanelSectionId.Project)
        assertThat(shown("The Project section needs the app to render")).isTrue()
    }

    @Test
    fun `share copies the link and opens cursor com`() {
        show()
        open(PanelSectionId.Share)
        clickTag("share-copy")
        assertThat(asked).contains("copy:https://cursor.com/agents/bc-demo")
        click("Open on cursor.com")
        assertThat(asked).contains("open:https://cursor.com/agents/bc-demo")
    }

    @Test
    fun `a chat with nothing yet degrades every default section to a named empty state`() {
        state = PanelFixtures.empty()
        show()
        assertThat(compose.onAllNodesWithText("Working").fetchSemanticsNodes()).hasSize(2)
        scrollTo("section-Changes")
        assertThat(shown("No changes yet")).isTrue()
        open(PanelSectionId.PullRequest)
        assertThat(shown("No pull request yet")).isTrue()
        open(PanelSectionId.Files)
        assertThat(shown("Nothing touched yet")).isTrue()
        clickTag("files-tab-Repository")
        assertThat(shown("No repository")).isTrue()
        open(PanelSectionId.Media)
        assertThat(shown("No images or recordings yet")).isTrue()
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
