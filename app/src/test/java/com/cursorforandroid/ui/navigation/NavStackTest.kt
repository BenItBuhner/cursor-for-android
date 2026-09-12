package com.cursorforandroid.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The back stack's invariants: the root stays put, a chat opened from a chat swaps rather than stacks, and opening a
 * chat is decided from the stack as it is when the call is made.
 */
class NavStackTest {

    private val NavStack.screens: List<Screen> get() = entries.map { it.screen }

    @Test
    fun `opening a chat from the New Chat pane pushes it`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-1")).inOrder()
        assertThat(stack.canPop).isTrue()
    }

    @Test
    fun `opening a chat from another chat replaces it`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        val first = stack.top
        stack.openAgent("bc-2")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-2")).inOrder()
        assertThat(stack.contains(first)).isFalse()
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
    fun `a Project opens over a chat, a primary opened from it goes back to it, and the route round-trips`() {
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.openProject("bc-p")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-1"), Screen.Project("bc-p")).inOrder()
        // A primary opened from the Project sits over it; back returns to the Project.
        stack.openAgent("bc-w")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-1"), Screen.Project("bc-p"), Screen.Agent("bc-w")).inOrder()
        stack.pop()
        assertThat(stack.top.screen).isEqualTo(Screen.Project("bc-p"))
        // One Project from another swaps; the same one again changes nothing.
        val entry = stack.top
        stack.openProject("bc-p")
        assertThat(stack.top).isEqualTo(entry)
        stack.openProject("bc-q")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-1"), Screen.Project("bc-q")).inOrder()
        assertThat(Screen.fromRoute(Screen.Project("bc-q").route)).isEqualTo(Screen.Project("bc-q"))
        assertThat(Screen.fromRoute("project/")).isNull()
    }

    @Test
    fun `opening a chat from Settings pushes it over Settings`() {
        val stack = NavStack(Screen.Home)
        stack.push(Screen.Settings)
        stack.openAgent("bc-1")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Settings, Screen.Agent("bc-1")).inOrder()
    }

    @Test
    fun `the root is never replaced`() {
        val stack = NavStack(Screen.Home)
        val root = stack.top
        stack.replaceTop(Screen.Agent("bc-1"))
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-1")).inOrder()
        assertThat(stack.contains(root)).isTrue()
        assertThat(stack.underTop).isEqualTo(root)
    }

    @Test
    fun `a chat opened after the stack was popped back to the pane goes on top of the pane`() {
        // The sequence behind the stuck "Loading…" chat: a chat was on top when the caller last looked, but the user has
        // since swiped back to the New Chat pane. What matters is the stack now, not what the caller remembered.
        val stack = NavStack(Screen.Home)
        stack.openAgent("bc-1")
        stack.pop()
        stack.openAgent("bc-2")
        assertThat(stack.screens).containsExactly(Screen.Home, Screen.Agent("bc-2")).inOrder()
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
}
