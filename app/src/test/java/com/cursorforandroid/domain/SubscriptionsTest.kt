package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The "Listening N" count as the transcript tells it: subscribes less unsubscribes, failed and running calls aside. */
class SubscriptionsTest {

    private fun mcp(id: String, tool: String, status: String = ToolCall.STATUS_COMPLETED, isError: Boolean = false) =
        ToolCall(callId = id, name = "mcp", kind = ToolKind.Mcp, status = status, summary = tool, server = "cursor-subscriptions", isError = isError)

    @Test
    fun `successful subscribe calls count, named by their kind, oldest first`() {
        val items = listOf(
            ActivityGroup("g1", listOf(mcp("1", "subscribe_github_pr"), mcp("2", "subscribe_timer"), ToolCall("3", "read_file", ToolKind.Read, "completed", "a.kt"))),
            ActivityGroup("g2", listOf(mcp("4", "subscribe_linear_issue"))),
        )
        val listening = Subscriptions.of(items)
        assertThat(listening.count).isEqualTo(3)
        assertThat(listening.kinds).containsExactly("GitHub PR", "Timer", "Linear issue").inOrder()
    }

    @Test
    fun `an unsubscribe takes the oldest down and the count never goes below zero`() {
        val items = listOf(ActivityGroup("g1", listOf(mcp("1", "subscribe_github_ci"), mcp("2", "subscribe_timer"), mcp("3", "unsubscribe"), mcp("4", "unsubscribe"), mcp("5", "unsubscribe"))))
        assertThat(Subscriptions.of(items).kinds).isEmpty()
        val one = listOf(ActivityGroup("g1", listOf(mcp("1", "subscribe_github_ci"), mcp("2", "subscribe_timer"), mcp("3", "unsubscribe"))))
        assertThat(Subscriptions.of(one).kinds).containsExactly("Timer")
    }

    @Test
    fun `a running or failed subscribe is not yet listening`() {
        val items = listOf(ActivityGroup("g1", listOf(mcp("1", "subscribe_github_pr", status = ToolCall.STATUS_RUNNING), mcp("2", "subscribe_timer", isError = true))))
        assertThat(Subscriptions.of(items).isEmpty).isTrue()
    }

    @Test
    fun `a transcript without subscriptions has none`() {
        assertThat(Subscriptions.of(listOf(ActivityGroup("g1", listOf(ToolCall("1", "read_file", ToolKind.Read, "completed", "a.kt")))))).isEqualTo(Subscriptions.NONE)
        assertThat(Subscriptions.of(emptyList()).count).isEqualTo(0)
    }

    @Test
    fun `an unknown target reads as written`() {
        assertThat(Subscriptions.kindLabel("jira_ticket")).isEqualTo("Jira ticket")
    }
}
