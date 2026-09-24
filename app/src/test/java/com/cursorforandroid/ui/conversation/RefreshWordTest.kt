package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.repo.CatchUp
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.newMessageCount
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The word Ctrl+R and Ctrl+Shift+R leave on the chat, as the pull gesture's: "Up to date", "N new", or the reason. */
class RefreshWordTest {

    private val before = ConversationState("a", items = listOf(UserMessage("u1", "Hi"), AssistantMessage("a1", "Hello")), isLoading = false)

    @Test
    fun `nothing new is up to date, whatever else the refresh rebuilt`() {
        val after = before.copy(items = before.items + ActivityGroup("g1", listOf(ThinkingBlock("thinking"))))
        assertThat(RefreshWord.of(before, after)).isEqualTo(RefreshWord.UP_TO_DATE)
        assertThat(RefreshWord.of(before, before)).isEqualTo("Up to date")
    }

    @Test
    fun `the prompts and replies the refresh brought are counted`() {
        val after = before.copy(items = before.items + UserMessage("u2", "More?") + AssistantMessage("a2", "Sure") + ActivityGroup("g2", emptyList()))
        assertThat(RefreshWord.of(before, after)).isEqualTo("2 new")
        assertThat(newMessageCount(emptyList(), after.items)).isEqualTo(4)
    }

    @Test
    fun `a reply the reload rebuilt from the run's trace, under a new id, is the same reply`() {
        val rebuilt = before.copy(
            items = listOf(UserMessage("server-u1", "Hi  "), ActivityGroup("activity-run-1-0", emptyList()), AssistantMessage("asst-run-1-1", "Hello\n")),
        )
        assertThat(RefreshWord.of(before, rebuilt)).isEqualTo(RefreshWord.UP_TO_DATE)
    }

    @Test
    fun `the same prompt sent again is new, and a reply still streaming is not counted yet`() {
        val after = before.copy(items = before.items + UserMessage("u2", "Hi") + AssistantMessage("a2", "Hel", isStreaming = true))
        assertThat(RefreshWord.of(before, after)).isEqualTo("1 new")
    }

    @Test
    fun `a notice the refresh brought is counted by its id`() {
        val notice = SystemNotification("n1", SystemNotification.Kind.Subagent, "Subagent completed", raw = "<subagent_notification/>")
        val after = before.copy(items = before.items + notice)
        assertThat(RefreshWord.of(before, after)).isEqualTo("1 new")
        assertThat(RefreshWord.of(after, after)).isEqualTo(RefreshWord.UP_TO_DATE)
    }

    @Test
    fun `a read that did not go through says why`() {
        assertThat(RefreshWord.of(before, before.copy(error = "No connection"))).isEqualTo("No connection")
    }

    @Test
    fun `a catch-up's word is its count, or its failure`() {
        assertThat(RefreshWord.of(CatchUp())).isEqualTo(RefreshWord.UP_TO_DATE)
        assertThat(RefreshWord.of(CatchUp(changed = true))).isEqualTo(RefreshWord.UP_TO_DATE)
        assertThat(RefreshWord.of(CatchUp(newMessages = 3, changed = true))).isEqualTo("3 new")
        assertThat(RefreshWord.of(CatchUp(newMessages = 1, error = "No connection"))).isEqualTo("No connection")
    }
}
