package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.ui.agents.AgentListUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The New Chat page's automatic pick ([rememberNewChatHome]): Projects for an account that has any and the recent chats
 * for one that has none, never the one and then the other, made once per opening of the page and kept while it stays
 * up, and never over a layout the reader chose in Settings.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewChatHomePickTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var choice by mutableStateOf<NewChatHomeChoice?>(NewChatHomeChoice(null))
    private var projectsKnown by mutableStateOf<Boolean?>(true)
    private var list by mutableStateOf(AgentListUiState())
    private var account by mutableStateOf<String?>("bennett@example.com")
    private var open by mutableStateOf(true)

    /** Every layout the page was drawn with, in order: what a reader would have seen. */
    private val seen = mutableListOf<NewChatHome?>()
    private val shown: NewChatHome? get() = seen.last()

    private val withProjects: AgentListUiState = NewChatHomeFixtures.list()
    private val withoutProjects: AgentListUiState = NewChatHomeFixtures.withoutProjects()

    private fun show() {
        compose.setContent {
            if (open) {
                val home = rememberNewChatHome(choice, projectsKnown, list, account)
                if (seen.isEmpty() || seen.last() != home) seen += home
            }
        }
        compose.waitForIdle()
    }

    /** Leaves the page and comes back to it: a fresh opening. */
    private fun reopen() {
        open = false
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
    }

    @Test
    fun `Projects on disk open the page on Projects from its first frame`() {
        list = withProjects
        show()
        assertThat(seen).containsExactly(NewChatHome.PROJECTS)
    }

    @Test
    fun `no Projects on disk holds the page until the fetch answers, then Projects without the recents first`() {
        list = withoutProjects
        show()
        assertThat(shown).isNull()
        list = withProjects.copy(isCurrent = true)
        compose.waitForIdle()
        assertThat(seen).containsExactly(null, NewChatHome.PROJECTS).inOrder()
    }

    @Test
    fun `an account the fetch confirms has no Projects opens on the recent chats`() {
        list = withoutProjects
        show()
        list = withoutProjects.copy(isCurrent = true)
        compose.waitForIdle()
        assertThat(seen).containsExactly(null, NewChatHome.RECENT).inOrder()
    }

    @Test
    fun `a slow fetch holds the page no longer than the settle bound while the disk has recents to show`() {
        compose.mainClock.autoAdvance = false
        list = withoutProjects
        show()
        compose.mainClock.advanceTimeBy(NEW_CHAT_HOME_SETTLE_MS - 500)
        assertThat(shown).isNull()
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(shown).isEqualTo(NewChatHome.RECENT)
        // A Project turning up afterwards waits for the next opening rather than swapping the list.
        list = withProjects.copy(isCurrent = true)
        compose.mainClock.advanceTimeBy(100)
        assertThat(seen).containsExactly(null, NewChatHome.RECENT).inOrder()
    }

    @Test
    fun `a first Project made while the page is up shows the next time it is opened`() {
        list = withoutProjects.copy(isCurrent = true)
        show()
        list = withProjects.copy(isCurrent = true)
        compose.waitForIdle()
        assertThat(shown).isEqualTo(NewChatHome.RECENT)
        reopen()
        assertThat(shown).isEqualTo(NewChatHome.PROJECTS)
        assertThat(seen).containsExactly(NewChatHome.RECENT, NewChatHome.PROJECTS).inOrder()
    }

    @Test
    fun `the last Project removed while the page is up gives the recents back the next time it is opened`() {
        list = withProjects.copy(isCurrent = true)
        show()
        list = withoutProjects.copy(isCurrent = true)
        compose.waitForIdle()
        assertThat(shown).isEqualTo(NewChatHome.PROJECTS)
        reopen()
        assertThat(shown).isEqualTo(NewChatHome.RECENT)
    }

    @Test
    fun `a layout chosen in Settings wins over the account's Projects, and applies at once`() {
        list = withProjects.copy(isCurrent = true)
        choice = NewChatHomeChoice(NewChatHome.RECENT)
        show()
        assertThat(seen).containsExactly(NewChatHome.RECENT)
        choice = NewChatHomeChoice(NewChatHome.COMPOSER)
        compose.waitForIdle()
        assertThat(shown).isEqualTo(NewChatHome.COMPOSER)
        list = withoutProjects.copy(isCurrent = true)
        choice = NewChatHomeChoice(NewChatHome.PROJECTS)
        reopen()
        assertThat(shown).isEqualTo(NewChatHome.PROJECTS)
    }

    @Test
    fun `nothing is picked before the choice and Extended mode's switch are read`() {
        list = withProjects.copy(isCurrent = true)
        choice = null
        projectsKnown = null
        show()
        assertThat(shown).isNull()
        choice = NewChatHomeChoice(null)
        compose.waitForIdle()
        assertThat(shown).isNull()
        projectsKnown = true
        compose.waitForIdle()
        assertThat(seen).containsExactly(null, NewChatHome.PROJECTS).inOrder()
    }

    @Test
    fun `without Extended mode there are no Projects to have, and the recents show at once`() {
        projectsKnown = false
        list = withoutProjects
        show()
        assertThat(seen).containsExactly(NewChatHome.RECENT)
    }

    @Test
    fun `signing in to another account picks again`() {
        list = withoutProjects.copy(isCurrent = true)
        show()
        assertThat(shown).isEqualTo(NewChatHome.RECENT)
        list = withProjects.copy(isCurrent = true)
        account = "other@example.com"
        compose.waitForIdle()
        assertThat(shown).isEqualTo(NewChatHome.PROJECTS)
    }
}
