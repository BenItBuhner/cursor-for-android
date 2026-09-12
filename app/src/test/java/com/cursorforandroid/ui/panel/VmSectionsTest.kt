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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
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
 * The Extended-mode halves of Files, Changes, Pull request and Remote: what each shows with the mode on, the named
 * state each degrades to with it off, and which read each asks for.
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
        override fun loadMachine(force: Boolean) { asked += if (force) "machine!" else "machine" }
        override fun openDesktop(viewOnly: Boolean) { asked += if (viewOnly) "desktop:view" else "desktop:control" }
        override fun setDesktopViewOnly(viewOnly: Boolean) { asked += "viewonly:$viewOnly" }
        override fun closeDesktop() { asked += "desktop:close" }
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

        state = state.copy(capabilities = Capabilities.DOCUMENTED, workspace = WorkspaceBrowserState())
        compose.waitForIdle()
        assertThat(shown("Needs Extended mode")).isTrue()
        assertThat(shown("ListWorkspaceFiles")).isTrue()

        state = state.copy(capabilities = Capabilities.EXTENDED, isDemo = true)
        compose.waitForIdle()
        assertThat(shown("The demo has no workspace to browse")).isTrue()
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
    fun `a workspace file takes the sections' place in the viewer, named as the VM's`() {
        val text = "plugins { id(\"com.android.application\") }\n"
        state = state.copy(browser = state.browser.copy(file = FileView.Workspace(RepoFile("app/build.gradle.kts", text.toByteArray(), text.length.toLong()))))
        show()
        compose.onNodeWithTag("file-viewer").assertIsDisplayed()
        compose.onNodeWithText("build.gradle.kts").assertIsDisplayed()
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

        // Extended off, no pull request: the stream's edits, and the named note about what the mode would add.
        state = PanelFixtures.extended().copy(capabilities = Capabilities.DOCUMENTED, diff = RemoteLoad.Unsupported("Needs Extended mode"))
        compose.waitForIdle()
        assertThat(shown("From this conversation · 3 files")).isTrue()
        assertThat(shown("GetBackgroundComposerDiffDetails")).isTrue()
    }

    @Test
    fun `a branch diff file opens in the viewer as a diff, as its new text, or as nothing to show`() {
        state = state.copy(browser = state.browser.copy(file = FileView.BranchDiff(PanelFixtures.branchDiff.files[0])))
        show()
        compose.onNodeWithTag("diff-block").assertIsDisplayed()
        assertThat(shown("The branch's diff · +5 -2")).isTrue()

        state = state.copy(browser = state.browser.copy(file = FileView.BranchDiff(PanelFixtures.branchDiff.files[2])))
        compose.waitForIdle()
        assertThat(shown("As it was on the base")).isTrue()
        assertThat(shown("// gone")).isTrue()

        state = state.copy(browser = state.browser.copy(file = FileView.BranchDiff(PanelFixtures.branchDiff.files[3])))
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

        // Off: the named state; a chat without a branch: nothing to open one from.
        state = state.copy(capabilities = Capabilities.DOCUMENTED, pullRequestCreation = RemoteLoad.Idle)
        compose.waitForIdle()
        assertThat(shown("MakePRBackgroundComposer")).isTrue()
        state = state.copy(capabilities = Capabilities.EXTENDED, agent = state.agent!!.copy(branches = emptyList()))
        compose.waitForIdle()
        assertThat(shown("no branch yet")).isTrue()
    }

    // ---- Remote -----------------------------------------------------------------------------------------------------

    @Test
    fun `a Remote Control chat names its machine, its state from the fleet endpoint, and the floors, in either mode`() {
        state = PanelFixtures.remoteControl()
        show()
        open(PanelSectionId.Remote)
        compose.waitForIdle()
        assertThat(asked).contains("machine")
        assertThat(shown("Remote Control · studio-mac")).isTrue()
        scrollTo("machine-status")
        compose.onNodeWithTag("machine-status").assertIsDisplayed()
        assertThat(shown("Connected · working on this chat")).isTrue()
        assertThat(shown("Online")).isTrue()
        assertThat(shown("Cursor 3.9.8 or later")).isTrue()
        // The desktop half: off, the named state; on, the relay this build does not carry.
        assertThat(shown("GetMachine")).isTrue()
        state = state.copy(capabilities = Capabilities.EXTENDED)
        compose.waitForIdle()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("desktop-relay"))
        compose.onNodeWithTag("desktop-relay").assertIsDisplayed()
        clickTag("machine-refresh")
        assertThat(asked).contains("machine!")

        state = state.copy(machine = RemoteLoad.Loaded(state.machine.valueOrNull!!.copy(connected = false, isInUse = false, activeAgentId = null)))
        compose.waitForIdle()
        assertThat(shown("Not connected")).isTrue()
        assertThat(shown("Offline")).isTrue()

        state = state.copy(machine = RemoteLoad.Failed("Fleet endpoints need a pool service account."))
        compose.waitForIdle()
        assertThat(shown("pool service account")).isTrue()
    }

    @Test
    fun `a cloud chat offers to view or take control of its desktop, and names every way that can fail`() {
        show()
        open(PanelSectionId.Remote)
        assertThat(shown("Cloud VM")).isTrue()
        clickTag("desktop-view")
        assertThat(asked).contains("desktop:view")
        clickTag("desktop-control")
        assertThat(asked).contains("desktop:control")

        state = state.copy(desktop = DesktopState.Opening)
        compose.waitForIdle()
        assertThat(shown("Finding the desktop…")).isTrue()

        state = state.copy(desktop = DesktopState.Failed(DesktopFailure.Unreachable("The agent's desktop isn't reachable: a finished chat's VM is hibernated.")))
        compose.waitForIdle()
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("desktop-failed"))
        assertThat(shown("hibernated")).isTrue()
        click("Retry")
        assertThat(asked.count { it == "desktop:view" }).isEqualTo(2)

        state = state.copy(desktop = DesktopState.Open(DesktopSession("bc-demo", "wss://t-p-6080.c.cursorvm.com/websockify?network_token=x", viewOnly = false, port = 6080)))
        compose.waitForIdle()
        compose.onNodeWithTag("desktop-screen").assertIsDisplayed()
        assertThat(shown("In control")).isTrue()
        compose.onNodeWithTag("desktop-control-toggle").performClick()
        assertThat(asked).contains("viewonly:true")
        compose.onNodeWithContentDescription("Close the desktop").performClick()
        assertThat(asked).contains("desktop:close")
    }

    @Test
    fun `with the mode off the desktop half is the named state, and the demo has none`() {
        state = state.copy(capabilities = Capabilities.DOCUMENTED)
        show()
        open(PanelSectionId.Remote)
        assertThat(shown("Needs Extended mode")).isTrue()
        assertThat(shown("GetMachine")).isTrue()
        assertThat(compose.onAllNodesWithTag("desktop-view").fetchSemanticsNodes()).isEmpty()

        state = state.copy(capabilities = Capabilities.EXTENDED, isDemo = true)
        compose.waitForIdle()
        assertThat(shown("The demo has no desktop to show")).isTrue()
    }

    @Test
    fun `the section hints follow the mode and the machine`() {
        val remote = PanelRegistry.default()[PanelSectionId.Remote]!!
        assertThat(remote.hint(PanelFixtures.loaded())).isEqualTo("Extended mode")
        assertThat(remote.hint(PanelFixtures.extended())).isEqualTo("Desktop")
        assertThat(remote.hint(PanelFixtures.remoteControl())).isEqualTo("Remote Control · online")
        assertThat(remote.hint(PanelFixtures.remoteControl().copy(machine = RemoteLoad.Idle))).isEqualTo("Remote Control")
        assertThat(remote.hint(PanelFixtures.extended().copy(desktop = DesktopState.Open(DesktopSession("bc-demo", "wss://x"))))).isEqualTo("Desktop open")
        assertThat(remote.availability(Capabilities.DOCUMENTED, PanelFixtures.loaded())).isEqualTo(SectionAvailability.Available)
        val changes = PanelRegistry.default()[PanelSectionId.Changes]!!
        assertThat(changes.hint(PanelFixtures.extended())).isEqualTo("4 files")
    }
}
