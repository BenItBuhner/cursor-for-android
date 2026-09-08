package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * Where a pull request stands, as GitHub reports it. The Cloud Agents API only ever names a PR (`prUrl`), so this is
 * read from GitHub and remembered on the device; it drives the Git filter and the pull-request pills in the list.
 */
@Serializable
enum class PullRequestState(val label: String) {
    Open("Open"),
    Draft("Draft"),
    Merged("Merged"),
    Closed("Closed");

    companion object {
        /** From GitHub's `state` / `draft` / `merged` fields: `closed` is [Merged] once `merged` is set. */
        fun of(state: String, draft: Boolean, merged: Boolean): PullRequestState? = when (state.lowercase()) {
            "open" -> if (draft) Draft else Open
            "closed" -> if (merged) Merged else Closed
            else -> null
        }
    }
}

/**
 * What the device last learned about one pull request. [state] is null when GitHub would not say — a private
 * repository read without a token, or a PR that is gone — which is remembered too, so the same doomed request is
 * not repeated on every refresh.
 */
@Serializable
data class PullRequestStatus(
    val state: PullRequestState?,
    val checkedAtMillis: Long,
)

/** A GitHub pull request as the Cloud Agents API names it: `https://github.com/{owner}/{repo}/pull/{number}`. */
data class PullRequestRef(val owner: String, val repo: String, val number: Int) {
    companion object {
        private val PATH = Regex("""^/([^/]+)/([^/]+)/pull/(\d+)(?:/.*)?$""")

        /** Null for anything that is not a GitHub pull request URL (a Cursor URL, a repo page, an issue). */
        fun parse(url: String): PullRequestRef? {
            val trimmed = url.trim()
            val withoutScheme = trimmed.substringAfter("://", trimmed)
            val host = withoutScheme.substringBefore('/').lowercase()
            if (host != "github.com" && host != "www.github.com") return null
            val path = "/" + withoutScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
            val match = PATH.matchEntire(path) ?: return null
            val (owner, repo, number) = match.destructured
            return PullRequestRef(owner, repo, number.toIntOrNull() ?: return null)
        }
    }
}
