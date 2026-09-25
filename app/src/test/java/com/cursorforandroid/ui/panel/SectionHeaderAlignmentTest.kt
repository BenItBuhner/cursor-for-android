package com.cursorforandroid.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every section header ends the same way: the hint and the chevron against the end edge, whatever the title and
 * however short the hint. Measured, not eyeballed — a header whose "8 >" drifted to the middle of the row is what
 * this guards against.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SectionHeaderAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    private val panelWidth = 363.dp
    private val HeaderEndPadding = PanelGutter

    private fun show(state: PanelState, registry: PanelRegistry = PanelRegistry.default()) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.width(panelWidth).fillMaxHeight()) {
                    ConversationPanel(state, PanelActions.None, onClose = {}, registry = registry)
                }
            }
        }
    }

    // The header row takes the tap, so its children merge into it: the hint and title are read off the unmerged tree.
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    @Test
    fun `every header's hint ends the same distance from the end edge, with the chevron after it`() {
        // Extended, with side chats: every section is there and every one has a hint, of a different length each.
        val state = PanelFixtures.withSideChats().copy(expandedSections = PanelSectionId.entries.associateWith { false })
        show(state)
        val shown = PanelRegistry.default().shown(Capabilities.EXTENDED, state)
        assertThat(shown.map { it.hint(state) }).doesNotContain(null)
        // The header's node is the tappable row, padding included: the hint stops one gap, one chevron and the row's
        // end padding short of its end edge — and the row spans the panel.
        val gap = px((HeaderEndPadding + HintChevronGap + ChevronSize).value)
        val rightEdges = shown.map { section ->
            val header = bounds("section-${section.id.name}")
            val hint = bounds("section-hint-${section.id.name}")
            assertThat(header.right - hint.right).isWithin(1.5f).of(gap)
            assertThat(header.right).isWithin(1.5f).of(px(panelWidth.value))
            hint.right
        }
        assertThat(rightEdges.max() - rightEdges.min()).isAtMost(1.5f)
        // A header without a hint puts its chevron in the same place: nothing sits between the title and the edge.
        val bare = state.copy(usage = RemoteLoad.Idle)
        assertThat(PanelRegistry.default()[PanelSectionId.Usage]!!.hint(bare)).isNull()
    }

    @Test
    fun `a long hint is capped and ellipsised, the title keeps its room, and the end edge still holds`() {
        val long = PanelSection(
            id = PanelSectionId.Header,
            icon = CursorIcons.Layers,
            hint = { "Open · checks running · 14 of 16 checks passed · review requested from four teammates" },
            content = { _, _ -> },
        )
        val state = PanelFixtures.loaded().copy(expandedSections = mapOf(PanelSectionId.Header to false))
        show(state, PanelRegistry(listOf(long)))
        val header = bounds("section-Header")
        val hint = bounds("section-hint-Header")
        assertThat(hint.width).isWithin(1.5f).of(px(HintMaxWidth.value))
        assertThat(header.right - hint.right).isWithin(1.5f).of(px((HeaderEndPadding + HintChevronGap + ChevronSize).value))
        compose.onNodeWithText("Overview", useUnmergedTree = true).assertIsDisplayed()
        val title = compose.onNodeWithText("Overview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(title.width).isGreaterThan(px(60f))
        assertThat(title.right).isAtMost(hint.left)
    }
}
