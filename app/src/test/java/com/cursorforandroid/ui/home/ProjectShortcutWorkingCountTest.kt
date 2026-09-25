package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A Project shortcut's working count: beside the working glyph in its top corner from two chats at work on, the glyph
 * alone for one, nothing for a Project at rest; said to accessibility services as "N agents working"; and the line under
 * the name that used to say it gone, so every shortcut is as short as its icon row and its name.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectShortcutWorkingCountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ProjectShortcutGrid(NewChatHomeFixtures.shortcutStates(), Modifier.padding(16.dp))
            }
        }
        compose.waitForIdle()
    }

    private fun shortcut(name: String): SemanticsNodeInteraction = compose.onNode(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT) and hasText(name))

    private fun label(name: String): List<String> =
        shortcut(name).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun counts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.WORKING_COUNT), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun `each working shortcut says how many agents are working, and a shortcut at rest says nothing of it`() {
        show()
        assertThat(label(NewChatHomeFixtures.ONE_WORKING)).containsExactly("1 agent working")
        assertThat(label(NewChatHomeFixtures.TWO_WORKING)).containsExactly("2 agents working")
        assertThat(label(NewChatHomeFixtures.TWELVE_WORKING)).containsExactly("12 agents working")
        for (name in listOf(NewChatHomeFixtures.UNREAD, NewChatHomeFixtures.FAILED, NewChatHomeFixtures.IDLE)) {
            assertThat(label(name)).isEmpty()
        }
    }

    @Test
    fun `the count stands beside the glyph from two on, and only there`() {
        show()
        val shortcuts = listOf(NewChatHomeFixtures.TWO_WORKING, NewChatHomeFixtures.TWELVE_WORKING).map { shortcut(it).fetchSemanticsNode().boundsInRoot }
        val counts = counts()
        assertThat(counts).hasSize(2)
        assertThat(counts.map { count -> shortcuts.indexOfFirst { it.contains(count.boundsInRoot.center) } }).containsExactly(0, 1)
        // The digit is in the label already; on its own it would be read out a second time.
        assertThat(counts.map { it.config.getOrNull(SemanticsProperties.Text) }).containsExactly(null, null)
    }

    @Test
    fun `the count is centred on the glyph's line and ends before it, in the top corner`() {
        show()
        val slots = compose.onAllNodes(hasTestTag(NewChatHomeTags.WORKING), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        for (count in counts().map { it.boundsInRoot }) {
            val slot = slots.single { it.contains(count.center) }
            assertThat(abs(count.center.y - slot.center.y)).isLessThan(1f)
            // The glyph (16dp) and the 4dp gap before it follow the count inside the slot.
            assertThat(slot.right - count.right).isWithin(1f).of(with(compose.density) { 20.dp.toPx() })
        }
    }

    @Test
    fun `no shortcut keeps a line under its name, so each is its icon row and its name tall`() {
        show()
        assertThat(compose.onAllNodes(hasText("working", substring = true), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("chats", substring = true), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        val padding = with(compose.density) { 12.dp.toPx() }
        val names = listOf(
            NewChatHomeFixtures.ONE_WORKING, NewChatHomeFixtures.TWO_WORKING, NewChatHomeFixtures.TWELVE_WORKING,
            NewChatHomeFixtures.UNREAD, NewChatHomeFixtures.FAILED, NewChatHomeFixtures.IDLE,
        )
        for (name in names) {
            val card = shortcut(name).fetchSemanticsNode().boundsInRoot
            val title = compose.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            // Nothing between the name and the card's own padding.
            assertThat(card.bottom - title.bottom).isWithin(1f).of(padding)
        }
    }
}
