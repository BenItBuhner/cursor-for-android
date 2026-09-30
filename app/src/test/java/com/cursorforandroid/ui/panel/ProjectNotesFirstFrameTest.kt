package com.cursorforandroid.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A long-running Project's `notes.md` (and a long document) opens on the panel without composing the whole file: a
 * block is a lazy item of its own, so the first frame holds about a screenful whatever the file's length. A file
 * parsed before shows at once; one not parsed yet shows the loading row while it is parsed off the main thread.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectNotesFirstFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val store = AgentStoreRef("st-project", AgentStoreKind.CLOUD, sourceId = "bc-root")
    private val coordinator = PanelFixtures.agent.copy(id = "bc-root", name = "Long Project", isProject = true, projectAppearance = ProjectAppearance("rocket", "purple"))
    private var state by mutableStateOf(PanelFixtures.loaded())
    private var shown by mutableStateOf(true)

    /** About [kb] KB of a coordinator's running notes: a heading, a paragraph and four tasks per workstream. */
    private fun notes(kb: Int, word: String = "Workstream"): Pair<String, Int> {
        var sections = 0
        val text = buildString {
            while (length < kb * 1024) {
                sections++
                append("## $word $sections\n\n")
                append("The coordinator's running notes for this part: what shipped, what the workers are on, and what is blocked. ".repeat(2)).append("\n\n")
                repeat(4) { append("- [${if (it % 2 == 0) "x" else " "}] Task $sections.$it — [PR #${sections * 10 + it}](https://github.com/acme/app/pull/${sections * 10 + it}) by `bc-worker-$it`\n") }
                append("\n")
            }
        }
        return text to sections
    }

    private fun panel(document: ContextDocument, tab: PanelTab.Document? = null) = PanelFixtures.loaded().copy(
        agentId = coordinator.id,
        agent = coordinator,
        capabilities = Capabilities.EXTENDED,
        tabs = PanelTabsState(listOfNotNull(tab), tab?.key),
        context = ContextPanelState(
            notes = RemoteLoad.Loaded(if (tab == null) document else null),
            documents = if (tab == null) emptyMap() else mapOf(tab.key to RemoteLoad.Loaded(document)),
        ),
    )

    private fun show() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                if (shown) ConversationPanel(state, PanelActions.None, onClose = {})
            }
        }
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    @Before
    fun clearCache() = MarkdownCache.clear()

    @Test
    fun `long notes read off the main thread, compose about a screenful, and show at once when opened again`() {
        val (text, sections) = notes(96)
        state = panel(ContextDocument(store, "notes.md", text))
        compose.mainClock.autoAdvance = false
        show()
        // Not parsed yet: the loading row, the parse on its way off the main thread.
        assertThat(count("Reading the Project's notes…")).isEqualTo(1)
        assertThat(count("Workstream ")).isEqualTo(0)

        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("project-notes-body")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(sections).isGreaterThan(150)
        assertThat(count("Workstream ")).isAtMost(ScreenfulOfSections)

        // Shut and opened again: already parsed, so the first frame is the notes, not the loading row.
        shown = false
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        shown = true
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            assertThat(count("Reading the Project's notes…")).isEqualTo(0)
        }
        assertThat(count("Workstream ")).isIn(1..ScreenfulOfSections)

        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("project-notes-tab").performScrollToNode(hasText("Workstream $sections"))
        assertThat(compose.onAllNodes(hasText("Workstream 1"), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `notes read again keep the shown notes on screen while the new text is parsed`() {
        state = panel(ContextDocument(store, "notes.md", notes(24).first))
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("project-notes-body")).fetchSemanticsNodes().isNotEmpty() }

        // The coordinator rewrote them: the new text is not parsed yet, and the notes shown stay until it is.
        compose.mainClock.autoAdvance = false
        state = panel(ContextDocument(store, "notes.md", notes(24, word = "Milestone").first))
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            assertThat(count("Reading the Project's notes…")).isEqualTo(0)
            assertThat(count("Workstream ") + count("Milestone ")).isGreaterThan(0)
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { count("Milestone ") > 0 }
        assertThat(count("Workstream ")).isEqualTo(0)
    }

    @Test
    fun `a long document tab composes about a screenful`() {
        val (text, sections) = notes(96, word = "Chapter")
        val document = ContextDocument(store, "docs/long.md", text)
        val tab = PanelTab.Document(store.storeId, document.path)
        state = panel(document, tab)
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("document-preview")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(count("Chapter ")).isIn(1..ScreenfulOfSections)
        compose.onNodeWithTag("document-preview").performScrollToNode(hasText("Chapter $sections"))
        assertThat(count("Chapter ")).isAtMost(ScreenfulOfSections)
    }

    private companion object {
        /** A workstream is about 300dp tall, so a 914dp screen shows three or four; generous room for prefetch. */
        const val ScreenfulOfSections = 10
    }
}
