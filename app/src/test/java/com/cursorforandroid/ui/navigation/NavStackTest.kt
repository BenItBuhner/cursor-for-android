package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The back stack's invariants: the root stays put; a destination opened from the one on top is pushed, so back
 * returns to it; a destination picked from the top level sits on the root; no screen twice in a row; a bounded depth;
 * and all of it holds for a stack restored after process death as for one built.
 */
class NavStackTest {

    private val NavStack.screens: List<Screen> get() = entries.map { it.screen }

    private fun agent(n: Int) = Screen.Agent("bc-$n")

    @Test
    fun `opening a chat from the New Chat pane pushes it`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1)).inOrder()
        assertThat(stack.canPop).isTrue()
    }

    @Test
    fun `a chat opened from another chat is pushed over it, and back returns to the one it was opened from`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        val coordinator = stack.top
        stack.openAgent("bc-2")
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1), agent(2)).inOrder()
        // The predictive gesture previews the entry under the top: the chat it was opened from, not the pane.
        assertThat(stack.underTop).isEqualTo(coordinator)
        assertThat(stack.pop()).isTrue()
        // The same entry, so its view models (the composer's draft) and saved state (the scroll) are the ones left.
        assertThat(stack.top).isEqualTo(coordinator)
        assertThat(stack.top.id).isEqualTo(coordinator.id)
    }

    @Test
    fun `opening the chat already on top changes nothing`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        val entry = stack.top
        stack.openAgent("bc-1")
        assertThat(stack.top).isEqualTo(entry)
        assertThat(stack.entries).hasSize(2)
    }

    @Test
    fun `opening the chat just under the top goes back to it instead of stacking it over its own child`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-p")
        val coordinator = stack.top
        stack.openAgent("bc-w")
        // A primary's panel links back to its coordinator: that is the way back, not a third entry.
        stack.openAgent("bc-p")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-p")).inOrder()
        assertThat(stack.top).isEqualTo(coordinator)
    }

    @Test
    fun `a chat further down the trail, opened again from the top, is pushed afresh`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.openAgent("bc-2")
        stack.openAgent("bc-3")
        stack.openAgent("bc-1")
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1), agent(2), agent(3), agent(1)).inOrder()
        // Two visits, two entries: each keeps its own state.
        assertThat(stack.entries[1].id).isNotEqualTo(stack.top.id)
    }

    @Test
    fun `a Project is its coordinator's chat, with no screen of its own, and an old Project route restores as that chat`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-p")
        stack.openAgent("bc-w")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-p"), Screen.Agent("bc-w")).inOrder()
        // A stack saved by a build that had a Project view comes back with the coordinator's chat in its place.
        assertThat(Screen.fromRoute("project/bc-q")).isEqualTo(Screen.Agent("bc-q"))
        assertThat(Screen.fromRoute("project/")).isNull()
        assertThat(Screen.fromRoute(Screen.Agent("bc-q").route)).isEqualTo(Screen.Agent("bc-q"))
    }

    @Test
    fun `Settings opened from a chat is pushed over it, and What's new over Settings`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.open(Screen.Settings)
        stack.open(Screen.WhatsNew)
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1), Screen.Settings, Screen.WhatsNew).inOrder()
        // Asked for again from where it is showing, nothing changes.
        stack.open(Screen.WhatsNew)
        assertThat(stack.screens).hasSize(4)
        stack.pop()
        stack.pop()
        assertThat(stack.top.screen).isEqualTo(agent(1))
    }

    @Test
    fun `opening a chat from Settings pushes it over Settings`() {
        val stack = NavStack(Screen.Home)
        stack.push(Screen.Settings)
        stack.openAgent("bc-1")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Settings, agent(1)).inOrder()
    }

    @Test
    fun `push never puts a screen on top of itself`() {
        val stack = NavStack(Screen.Home)
        val root = stack.top
        stack.push(Screen.Home)
        assertThat(stack.screens).containsExactly(Screen.Home)
        assertThat(stack.top).isEqualTo(root)
        stack.push(agent(1))
        stack.push(agent(1))
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1)).inOrder()
    }

    @Test
    fun `past the cap the oldest entry above the root makes room, and the trail back from the top stays`() {
        val stack = NavStack(Screen.Home)
        val root = stack.top
        for (n in 1..NavStack.MAX_DEPTH + 3) stack.openAgent("bc-$n")
        assertThat(stack.entries).hasSize(NavStack.MAX_DEPTH)
        assertThat(stack.entries.first()).isEqualTo(root)
        assertThat(stack.screens).containsExactlyElementsIn(listOf(Screen.Home) + (5..NavStack.MAX_DEPTH + 3).map(::agent)).inOrder()
        // Back all the way still ends on the pane.
        while (stack.pop()) Unit
        assertThat(stack.top).isEqualTo(root)
    }

    @Test
    fun `a chat opened after the stack was popped back to the pane goes on top of the pane`() {
        // The sequence behind the stuck "Loading…" chat: a chat was on top when the caller last looked, but the user has
        // since swiped back to the New Chat pane. What matters is the stack now, not what the caller remembered.
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.pop()
        stack.openAgent("bc-2")
        assertThat(stack.screens).containsExactly(Screen.Home, agent(2)).inOrder()
        assertThat(stack.canPop).isTrue()
    }

    @Test
    fun `pop never removes the root and resetTo keeps it`() {
        val stack = NavStack(Screen.Home)
        assertThat(stack.pop()).isFalse()
        stack.openAgent("bc-1")
        stack.push(Screen.Settings)
        stack.resetTo(Screen.Home)
        assertThat(stack.screens).containsExactly(Screen.Home)
        stack.resetTo(Screen.Settings)
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Settings).inOrder()
    }

    @Test
    fun `a chat picked from the sidebar over a trail of chats sits on the pane, so back is the pane`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.openAgent("bc-2")
        stack.open(Screen.Settings)
        stack.resetTo(agent(9))
        assertThat(stack.screens).containsExactly(Screen.Home, agent(9)).inOrder()
        stack.pop()
        assertThat(stack.screens).containsExactly(Screen.Home)
    }

    @Test
    fun `a chat picked from the sidebar while it is on the trail comes back as it was left`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        val first = stack.top
        stack.openAgent("bc-2")
        stack.resetTo(agent(1))
        assertThat(stack.screens).containsExactly(Screen.Home, agent(1)).inOrder()
        assertThat(stack.top.id).isEqualTo(first.id)
    }

    @Test
    fun `tapping the destination already on top changes nothing`() {
        val stack = NavStack(Screen.Home)
        stack.resetTo(Screen.Settings)
        val settings = stack.top
        stack.resetTo(Screen.Settings)
        // A new entry would carry a new id, and the screen's view model and scroll position go with it.
        assertThat(stack.top.id).isEqualTo(settings.id)
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Settings).inOrder()

        stack.resetTo(Screen.Home)
        assertThat(stack.top.id).isEqualTo(stack.entries.first().id)
    }

    @Test
    fun `resetting to a destination below the top pops down to it and keeps it`() {
        val stack = NavStack(Screen.Home)
        stack.resetTo(Screen.Settings)
        val settings = stack.top
        stack.openAgent("bc-1")
        stack.resetTo(Screen.Settings)
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Settings).inOrder()
        assertThat(stack.top.id).isEqualTo(settings.id)
    }

    @Test
    fun `the stack saved and restored is the same trail with the same entry ids`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.openAgent("bc-2")
        stack.open(Screen.Settings)
        val saved = with(NavStack.Saver) { SaverScope { true }.save(stack) }!!
        val restored = NavStack.Saver.restore(saved)!!
        assertThat(restored.screens).containsExactly(Screen.Home, agent(1), agent(2), Screen.Settings).inOrder()
        assertThat(restored.entries.map { it.id }).containsExactlyElementsIn(stack.entries.map { it.id }).inOrder()
    }

    @Test
    fun `a restored stack is held to the rules of a built one`() {
        // No New Chat pane at the root (a stack from before it was one, or cut short): one goes under it.
        val unrooted = NavStack.restored(listOf("a", "agent/bc-1", "b", "agent/bc-2"))
        assertThat(unrooted.map { it.screen }).containsExactly(Screen.Home, agent(1), agent(2)).inOrder()
        assertThat(unrooted.drop(1).map { it.id }).containsExactly("a", "b").inOrder()

        // A screen twice in a row, an unknown route and a dangling id: the repeat and the unreadable go.
        val messy = NavStack.restored(listOf("r", "home", "a", "agent/bc-1", "b", "agent/bc-1", "c", "somewhere/else", "d", "settings", "e"))
        assertThat(messy.map { it.screen }).containsExactly(Screen.Home, agent(1), Screen.Settings).inOrder()
        assertThat(messy.map { it.id }).containsExactly("r", "a", "d").inOrder()

        // Deeper than the cap: the root and the newest trail back from the top.
        val deep = NavStack.restored(listOf("r", "home") + (1..NavStack.MAX_DEPTH + 4).flatMap { listOf("id-$it", "agent/bc-$it") })
        assertThat(deep).hasSize(NavStack.MAX_DEPTH)
        assertThat(deep.first().id).isEqualTo("r")
        assertThat(deep.last().screen).isEqualTo(agent(NavStack.MAX_DEPTH + 4))
        assertThat(deep[1].screen).isEqualTo(agent(6))
    }
}
