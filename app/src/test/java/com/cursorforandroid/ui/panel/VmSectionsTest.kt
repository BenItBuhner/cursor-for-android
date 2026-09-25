package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
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
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Extended-mode halves of Files, Changes and Pull request: what each shows with the mode on, what is left of
 * each with it off, and which read each asks for.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class VmSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PanelFixtures.extended())

    private val asked = mutableListOf<String>()
    private val actions = object : PanelActions by PanelActions.None {
        override fun loadPullRequest(force: Boolean) { asked += "pr" }
        override fun loadDiff(force: Boolean) { asked += if (force) "diff!" else "diff" }
        override fun openBranchDiffFile(file: AgentDiffFile) { asked += "diff-file:${file.path}" }
        override fun loadWorkspace(force: Boolean) { asked += if (force) "workspace!" else "workspace" }
        override fun browseWorkspace(path: String) { asked += "browse:$path"; state = state.copy(workspace = state.workspace.copy(path = path)) }
        override fun browseWorkspaceUp() { asked += "up"; state = state.copy(workspace = state.workspace.copy(path = state.workspace.path.substringBeforeLast('/', ""))) }
        override fun openWorkspaceFile(path: String) { asked += "open:$path" }
        override fun createPullRequest() { asked += "create-pr" }
        override fun openUrl(url: String) { asked += "url:$url" }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ConversationPanel(state, actions, onClose = {}) } }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag(tag))

    private fun open(section: PanelSectionId) {
        scrollTo("section-${section.name}")
        compose.onNodeWithTag("section-${section.name}").performClick()
    }

    private fun click(text: String) {
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasText(text))
        compose.onAllNodesWithText(text)[0].performClick()
    }

    private fun clickTag(tag: String) {
        scrollTo(tag)
        compose.onAllNodesWithTag(tag)[0].performClick()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // ---- Files › Workspace ------------------------------------------------------------------------------------------

    @Test
    fun `the workspace tab walks the live tree and opens a file through the VM read`() {
        show()
        // The Overview names the base branch "main", which is also a directory below: fold it away first.
        open(PanelSectionId.Header)
        open(PanelSectionId.Files)
        clickTag("files-tab-Workspace")
        assertThat(shown("Live VM")).isTrue()
        // The root: directories first, then files, sorted.
        assertThat(compose.onAllNodesWithTag("workspace-dir").fetchSemanticsNodes()).hasSize(4)
        assertThat(compose.onAllNodesWithTag("workspace-file").fetchSemanticsNodes()).hasSize(3)
        click("app")
        assertThat(asked).contains("browse:app")
        compose.waitForIdle()
        assertThat(shown("build.gradle.kts")).isTrue()
        assertThat(compose.onAllNodesWithTag("workspace-up").fetchSemanticsNodes()).hasSize(1)
        click("src")
        click("main")
        click("java")
        compose.waitForIdle()
        assertThat(asked).contains("browse:app/src/main/java")
        clickTag("workspace-up")
        compose.waitForIdle()
        assertThat(state.workspace.path).isEqualTo("app/src/main")
        click("res")
        click("values")
        click("strings.xml")
        assertThat(asked).contains("open:app/src/main/res/values/strings.xml")
        clickTag("workspace-refresh")
        assertThat(asked).contains("workspace!")
    }

    @Test
    fun `the workspace tab names its states - loading, failed with retry, gated, and the demo`() {
        state = state.copy(workspace = WorkspaceBrowserState(tree = RemoteLoad.Loading))
        show()
        open(PanelSectionId.Files)
        clickTag("files-tab-Workspace")
        assertThat(shown("Listing the agent's workspace…")).isTrue()

        state = state.copy(workspace = WorkspaceBrowserState(tree = RemoteLoad.Failed("Cursor refused (pod is hibernated).")))
        compose.waitForIdle()
        assertThat(shown("pod is hibernated")).isTrue()
        click("Retry")
        assertThat(asked).contains("workspace!")

        state = state.copy(workspace = WorkspaceBrowserState(tree = RemoteLoad.Failed("Cursor changed a private endpoint", retryable = false)))
        compose.waitForIdle()
        assertThat(shown("Retry")).isFalse()

        // Off: the tab is not offered, and the section falls back to the first tab it still has.
        state = state.copy(capabilities = Capabilities.DOCUMENTED, workspace = WorkspaceBrowserState())
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("files-tab-Workspace").fetchSemanticsNodes()).isEmpty()
        assertThat(shown("ListWorkspaceFiles")).isFalse()
        assertThat(compose.onAllNodesWithTag("touched-file").fetchSemanticsNodes()).isNotEmpty()

        // The demo has no VM: the tab is not offered there either.
        state = state.copy(capabilities = Capabilities.EXTENDED, isDemo = true)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("files-tab-Workspace").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `opening the workspace tab asks for the listing once`() {
        state = state.copy(workspace = WorkspaceBrowserState())
        show()
        open(PanelSectionId.Files)
        clickTag("files-tab-Workspace")
        compose.waitForIdle()
        assertThat(asked.count { it == "workspace" }).isEqualTo(1)
    }

    @Test
    fun `a workspace file opens in a tab of its own, named as the VM's`() {
        val text = "plugins { id(\"com.android.application\") }\n"
        state = PanelFixtures.withFile(state, FileView.Workspace(RepoFile("app/build.gradle.kts", text.toByteArray(), text.length.toLong())))
        show()
        compose.onNodeWithTag("file-viewer").assertIsDisplayed()
        compose.onNode(hasText("build.gradle.kts") and hasAnyAncestor(hasTestTag("breadcrumb"))).assertIsDisplayed()
        assertThat(shown("From the agent's workspace")).isTrue()
        assertThat(shown("com.android.application")).isTrue()
    }

    // ---- Changes › branch diff --------------------------------------------------------------------------------------

    @Test
    fun `without a pull request the changes are the branch's diff from the account, and a row opens its patch`() {
        show()
        scrollTo("section-Changes")
        compose.waitForIdle()
        assertThat(asked).contains("diff")
        assertThat(shown("From the branch · cursor/theme-toggle-4f2a → main · 4 files")).isTrue()
        assertThat(compose.onAllNodesWithTag("branch-diff-file").fetchSemanticsNodes()).hasSize(4)
        assertThat(shown("was ")).isFalse()
        click("SettingsScreen.kt")
        assertThat(asked).contains("diff-file:${PanelFixtures.settingsDiff.path}")
        // The hint counts the branch's files.
        assertThat(compose.onAllNodesWithText("4 files").fetchSemanticsNodes().size).isAtLeast(1)
    }

    @Test
    fun `the pull request's files still come first once it exists, and the stream's edits are the fallback`() {
        state = PanelFixtures.loaded().copy(capabilities = Capabilities.EXTENDED, diff = RemoteLoad.Loaded(PanelFixtures.branchDiff))
        show()
        scrollTo("section-Changes")
        assertThat(shown("From the pull request · 3 files")).isTrue()
        assertThat(compose.onAllNodesWithTag("branch-diff-file").fetchSemanticsNodes()).isEmpty()

        // Extended off, no pull request: the stream's edits, with no note about what the mode would add.
        state = PanelFixtures.extended().copy(capabilities = Capabilities.DOCUMENTED, diff = RemoteLoad.Unsupported("Needs Extended mode"))
        compose.waitForIdle()
        assertThat(shown("From this conversation · 3 files")).isTrue()
        assertThat(shown("GetBackgroundComposerDiffDetails")).isFalse()
        assertThat(shown("Needs Extended mode")).isFalse()
    }

    @Test
    fun `a branch diff file opens in the viewer as a diff, as its new text, or as nothing to show`() {
        state = PanelFixtures.withFile(state, FileView.BranchDiff(PanelFixtures.branchDiff.files[0]))
        show()
        compose.onNodeWithTag("diff-block").assertIsDisplayed()
        assertThat(shown("The branch's diff · +5 -2")).isTrue()

        state = PanelFixtures.withFile(state, FileView.BranchDiff(PanelFixtures.branchDiff.files[2]))
        compose.waitForIdle()
        assertThat(shown("As it was on the base")).isTrue()
        assertThat(shown("// gone")).isTrue()

        state = PanelFixtures.withFile(state, FileView.BranchDiff(PanelFixtures.branchDiff.files[3]))
        compose.waitForIdle()
        assertThat(shown("Nothing to show")).isTrue()
    }

    // ---- Pull request › create --------------------------------------------------------------------------------------

    @Test
    fun `with no pull request, Extended mode offers to open one and reports how it went`() {
        show()
        open(PanelSectionId.PullRequest)
        assertThat(shown("No pull request yet")).isTrue()
        clickTag("create-pull-request")
        assertThat(asked).contains("create-pr")

        state = state.copy(pullRequestCreation = RemoteLoad.Loading)
        compose.waitForIdle()
        assertThat(shown("Asking Cursor to open the pull request…")).isTrue()

        state = state.copy(pullRequestCreation = RemoteLoad.Failed("The branch has no commits yet"))
        compose.waitForIdle()
        assertThat(shown("no commits yet")).isTrue()

        state = state.copy(pullRequestCreation = RemoteLoad.Loaded("https://github.com/bennett/cursor-for-android/pull/98"))
        compose.waitForIdle()
        click("Pull request opened")
        assertThat(asked).contains("url:https://github.com/bennett/cursor-for-android/pull/98")

        // Off, with no pull request: nothing to offer, so no section. A chat without a branch: nothing to open one from.
        state = state.copy(capabilities = Capabilities.DOCUMENTED, pullRequestCreation = RemoteLoad.Idle)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("section-PullRequest").fetchSemanticsNodes()).isEmpty()
        assertThat(shown("MakePRBackgroundComposer")).isFalse()
        state = state.copy(capabilities = Capabilities.EXTENDED, agent = state.agent!!.copy(branches = emptyList()))
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag("section-PullRequest").fetchSemanticsNodes()).isEmpty()
    }

    // ---- Remote -----------------------------------------------------------------------------------------------------

    @Test
    fun `the agent's desktop is not a panel section, and the changes hint counts the branch's files`() {
        // Remote left the panel: the desktop is opened from the chat's header menu, a machine's state is an Overview fact.
        assertThat(PanelRegistry.default().sections.map { it.title }).containsNoneOf("Remote", "Share", "Queue and steering", "Images and media")
        show()
        assertThat(shown("Remote")).isFalse()
        assertThat(compose.onAllNodesWithTag("desktop-view").fetchSemanticsNodes()).isEmpty()
        val changes = PanelRegistry.default()[PanelSectionId.Changes]!!
        assertThat(changes.hint(PanelFixtures.extended())).isEqualTo("4 files")
    }
}
