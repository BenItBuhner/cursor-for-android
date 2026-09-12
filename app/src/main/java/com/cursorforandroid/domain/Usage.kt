package com.cursorforandroid.domain

/** Token counts as `GET /v1/agents/{id}/usage` reports them, for one run or the whole agent. */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
) {
    val isEmpty: Boolean get() = totalTokens == 0L && inputTokens == 0L && outputTokens == 0L && cacheReadTokens == 0L && cacheWriteTokens == 0L

    /** The total as reported, else the parts added up. */
    val total: Long get() = if (totalTokens > 0) totalTokens else inputTokens + outputTokens + cacheWriteTokens + cacheReadTokens

    companion object {
        /** "12.4k", "1.2M", "980" — the way the desktop abbreviates token counts. */
        fun format(tokens: Long): String = when {
            tokens >= 1_000_000 -> trimmed(tokens / 1_000_000.0) + "M"
            tokens >= 1_000 -> trimmed(tokens / 1_000.0) + "k"
            else -> tokens.toString()
        }

        private fun trimmed(value: Double): String = String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
    }
}

/** One run's usage. */
data class RunUsage(val runId: String, val usage: TokenUsage)

/** Everything the agent has spent, and how it splits by run (newest first when the API says). */
data class AgentUsage(val total: TokenUsage, val runs: List<RunUsage>) {
    val isEmpty: Boolean get() = total.isEmpty && runs.all { it.usage.isEmpty }
}
