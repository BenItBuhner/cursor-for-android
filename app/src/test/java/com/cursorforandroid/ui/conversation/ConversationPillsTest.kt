package com.cursorforandroid.ui.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.Subscriptions
import com.cursorforandroid.ui.panel.PanelFixtures
import com.cursorforandroid.ui.panel.RemoteLoad
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pills above the composer: which show for what, what each says, and where a tap sends the reader. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ConversationPillsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tapped = mutableListOf<String>()

    private fun show(state: ConversationPillsState) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ConversationPills(state, onAgents = { tapped += "agents" }, onChanges = { tapped += "changes" }, onOpenDesktop = { tapped += "desktop" })
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `nothing to say draws no row`() {
        show(ConversationPillsState())
        assertThat(compose.onAllNodesWithTag("conversation-pills").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a coordinator's Agents pill counts the workers working and opens the Project section`() {
        val root = PanelFixtures.agent.copy(id = "bc-root", isProject = true)
        val workers = listOf(
            PanelFixtures.agent.copy(id = "w1", runStatus = RunStatus.RUNNING, parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER)),
            PanelFixtures.agent.copy(id = "w2", runStatus = RunStatus.FINISHED, parent = AgentParent("bc-root", AgentParentKind.PROJECT_WORKER), hasPendingInteraction = true),
            PanelFixtures.agent.copy(id = "s1", parent = AgentParent("bc-root", AgentParentKind.SIDE_CHAT)),
        )
        val agents = ConversationPillsState.AgentsSummary.of("bc-root", listOf(root) + workers, isCoordinator = true)
        assertThat(agents).isEqualTo(ConversationPillsState.AgentsSummary(total = 2, working = 1, needsInput = 1))
        // A chat with nothing under it and no Project of its own has no Agents pill.
        assertThat(ConversationPillsState.AgentsSummary.of("bc-lonely", workers, isCoordinator = false)).isNull()
        assertThat(ConversationPillsState.AgentsSummary.of("bc-empty-root", emptyList(), isCoordinator = true)).isEqualTo(ConversationPillsState.AgentsSummary(0, 0, 0))

        show(ConversationPillsState(agents = agents))
        compose.onNodeWithTag("pill-agents").assertIsDisplayed()
        assertThat(shown("Agents")).isTrue()
        // The count of workers working sits beside the word, as the web writes "Listening 3".
        assertThat(shown("1")).isTrue()
        compose.onNodeWithTag("pill-agents").performClick()
        assertThat(tapped).containsExactly("agents")
    }

    @Test
    fun `Listening carries its count and lists the kinds`() {
        show(ConversationPillsState(listening = Subscriptions.Listening(listOf("GitHub PR", "Timer", "Linear issue"))))
        compose.onNodeWithTag("pill-listening").assertIsDisplayed()
        compose.onNodeWithContentDescription("Listening to 3 subscriptions").performClick()
        compose.onNodeWithText("GitHub PR").assertIsDisplayed()
        compose.onNodeWithText("Linear issue").assertIsDisplayed()
    }

    @Test
    fun `Changes reads the line counts in the git colours and opens the Changes section`() {
        show(ConversationPillsState(changes = ConversationPillsState.ChangesSummary(3167, 139, 9)))
        assertThat(shown("+3167")).isTrue()
        assertThat(shown("−139")).isTrue()
        compose.onNodeWithTag("pill-changes").performClick()
        assertThat(tapped).containsExactly("changes")
    }

    @Test
    fun `Changes without counts says how many files`() {
        show(ConversationPillsState(changes = ConversationPillsState.ChangesSummary(null, null, 4)))
        assertThat(shown("4 files")).isTrue()
    }

    @Test
    fun `Open Desktop shows only where the desktop can be opened, and opens it`() {
        show(ConversationPillsState(canOpenDesktop = true))
        compose.onNodeWithTag("pill-desktop").performClick()
        assertThat(tapped).containsExactly("desktop")
    }

    @Test
    fun `the Changes figures come from the pull request, else the branch diff, else the transcript's edits`() {
        val loaded = PanelFixtures.loaded()
        val fromPullRequest = changesSummary(loaded)!!
        assertThat(fromPullRequest.files).isEqualTo(loaded.pullRequest.valueOrNull!!.files.size)
        assertThat(fromPullRequest.additions).isEqualTo(loaded.pullRequest.valueOrNull!!.files.sumOf { it.additions })
        val fromDiff = changesSummary(PanelFixtures.extended().copy(pullRequest = RemoteLoad.Idle))!!
        assertThat(fromDiff.files).isEqualTo(PanelFixtures.branchDiff.files.size)
        val fromTranscript = changesSummary(loaded.copy(pullRequest = RemoteLoad.Idle, diff = RemoteLoad.Idle))!!
        assertThat(fromTranscript.files).isEqualTo(loaded.content.changes.size)
        assertThat(changesSummary(PanelFixtures.empty())).isNull()
    }
}
