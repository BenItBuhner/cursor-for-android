package com.cursorforandroid.ui.navigation

import com.cursorforandroid.domain.PaletteEntry
import com.cursorforandroid.ui.shortcuts.PaletteMode
import com.cursorforandroid.ui.shortcuts.SwitcherOrder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The quick switcher's order and steps: the open chat, the chats visited this session newest first, then the rest by
 * their last activity; the first Ctrl+Tab on the chat before, Tab and Shift+Tab around the ends, and Ctrl's release
 * opening the selection.
 */
class ShellShortcutsTest {

    private fun entry(id: String, at: Long) = PaletteEntry(id, "Chat $id", null, isProject = false, updatedAtMillis = at)

    private val entries = listOf(entry("a", 5), entry("b", 1), entry("c", 2), entry("d", 9), entry("e", 3))

    private fun ids(order: List<PaletteEntry>) = order.map { it.agentId }

    @Test
    fun `the open chat, then the visited ones, then the rest newest first`() {
        assertThat(ids(SwitcherOrder.of(listOf("b", "c"), entries, current = "a"))).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(ids(SwitcherOrder.of(listOf("c", "gone", "b"), entries, current = null))).containsExactly("c", "b", "d", "a", "e").inOrder()
        assertThat(ids(SwitcherOrder.of(emptyList(), entries, current = "gone", limit = 2))).containsExactly("d", "a").inOrder()
    }

    @Test
    fun `the first Ctrl+Tab lands on the chat before the open one, Ctrl+Shift+Tab on the oldest listed`() {
        val order = SwitcherOrder.of(listOf("b"), entries, current = "a")
        assertThat(SwitcherOrder.start(order, current = "a", forward = true)).isEqualTo(1)
        assertThat(SwitcherOrder.start(order, current = null, forward = true)).isEqualTo(0)
        assertThat(SwitcherOrder.start(order, current = "a", forward = false)).isEqualTo(order.lastIndex)
        assertThat(SwitcherOrder.start(listOf(entry("a", 1)), current = "a", forward = true)).isEqualTo(0)
        assertThat(SwitcherOrder.start(emptyList(), current = "a", forward = true)).isEqualTo(0)
    }

    @Test
    fun `a visit moves the chat to the front, and only the newest twelve are kept`() {
        val shell = ShellShortcuts()
        (1..14).forEach { shell.visit("c$it") }
        shell.visit("c5")
        assertThat(shell.visited).hasSize(SwitcherOrder.LIMIT)
        assertThat(shell.visited.take(3)).containsExactly("c5", "c14", "c13").inOrder()
        assertThat(shell.visited).doesNotContain("c1")
    }

    @Test
    fun `Tab steps around the ends while Ctrl is held, and letting go opens the selection`() {
        val shell = ShellShortcuts()
        shell.visit("b")
        shell.visit("a")
        var asked = 0
        val list = { asked++; entries }

        shell.stepSwitcher(forward = true, current = "a", entries = list)
        assertThat(shell.palette.mode).isEqualTo(PaletteMode.Switcher)
        assertThat(ids(shell.switcherRows)).containsExactly("a", "b", "d", "e", "c").inOrder()
        assertThat(shell.palette.switcherIndex).isEqualTo(1)

        shell.stepSwitcher(forward = true, current = "a", entries = list)
        shell.stepSwitcher(forward = true, current = "a", entries = list)
        assertThat(shell.palette.switcherIndex).isEqualTo(3)
        shell.stepSwitcher(forward = false, current = "a", entries = list)
        assertThat(shell.palette.switcherIndex).isEqualTo(2)
        repeat(3) { shell.stepSwitcher(forward = true, current = "a", entries = list) }
        assertThat(shell.palette.switcherIndex).isEqualTo(0)
        // The rows are fixed when the switcher opens, and hold still while Tab steps through them.
        assertThat(asked).isEqualTo(1)

        shell.stepSwitcher(forward = false, current = "a", entries = list)
        assertThat(shell.releaseSwitcher(commit = true)?.agentId).isEqualTo("c")
        assertThat(shell.palette.isOpen).isFalse()
    }

    @Test
    fun `a switcher put away without Ctrl's release opens nothing, and nothing is let go of when it was not up`() {
        val shell = ShellShortcuts()
        shell.stepSwitcher(forward = true, current = null, entries = { entries })
        assertThat(shell.releaseSwitcher(commit = false)).isNull()
        assertThat(shell.palette.isOpen).isFalse()

        shell.palette.openSearch()
        assertThat(shell.releaseSwitcher(commit = true)).isNull()
        assertThat(shell.palette.mode).isEqualTo(PaletteMode.Search)
    }
}
