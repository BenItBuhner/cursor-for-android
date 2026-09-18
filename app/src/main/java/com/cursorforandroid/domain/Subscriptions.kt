package com.cursorforandroid.domain

/**
 * What a chat is listening to — the event subscriptions its agent set up (GitHub pull requests and CI, Linear
 * issues and comments, timers) — as far as its own transcript says. The account keeps the subscriptions, and the
 * desktop shows their count as the "Listening N" pill above the composer, but no endpoint this app reads lists
 * them; the one word available is the agent's `subscribe_*` calls on the cursor-subscriptions MCP server (and its
 * `unsubscribe` calls), which the documented stream carries like any tool call. So the count is the successful
 * subscribes less the successful unsubscribes, never below zero, and each subscription is named by its kind.
 */
object Subscriptions {
    data class Listening(val kinds: List<String>) {
        val count: Int get() = kinds.size
        val isEmpty: Boolean get() = kinds.isEmpty()
    }

    val NONE = Listening(emptyList())

    /** The subscriptions [items] set up and did not take down again, oldest first. */
    fun of(items: List<TimelineItem>): Listening {
        val kinds = ArrayList<String>()
        var unsubscribed = 0
        items.asSequence().filterIsInstance<ActivityGroup>().flatMap { it.calls.asSequence() }.forEach { call ->
            if (call.isRunning || call.isError) return@forEach
            val tool = toolNameOf(call) ?: return@forEach
            when {
                tool.startsWith(SUBSCRIBE_PREFIX) -> kinds += kindLabel(tool.removePrefix(SUBSCRIBE_PREFIX))
                tool == UNSUBSCRIBE -> unsubscribed++
            }
        }
        // Which subscription an unsubscribe took down is in arguments the row does not keep; the oldest goes.
        return Listening(kinds.drop(minOf(unsubscribed, kinds.size)))
    }

    /** The tool's own name: the summary for an MCP call ("subscribe_github_pr"), the call's name otherwise. */
    private fun toolNameOf(call: ToolCall): String? {
        val candidate = if (call.kind == ToolKind.Mcp) call.summary.trim().ifEmpty { call.name } else call.name
        val bare = candidate.trim().substringAfterLast('/').substringAfterLast('.').lowercase()
        return bare.takeIf { it.startsWith(SUBSCRIBE_PREFIX) || it == UNSUBSCRIBE }
    }

    /** "GitHub PR", "GitHub CI", "Linear issue", "Linear comment", "Timer", else the target as written. */
    fun kindLabel(target: String): String = when (target) {
        "github_pr" -> "GitHub PR"
        "github_ci" -> "GitHub CI"
        "linear_issue" -> "Linear issue"
        "linear_comment" -> "Linear comment"
        "timer" -> "Timer"
        "slack" -> "Slack"
        else -> target.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private const val SUBSCRIBE_PREFIX = "subscribe_"
    private const val UNSUBSCRIBE = "unsubscribe"
}
