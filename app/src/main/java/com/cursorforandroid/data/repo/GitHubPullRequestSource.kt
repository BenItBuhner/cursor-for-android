package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.GitHubApiException
import com.cursorforandroid.data.api.GitHubPullRequestRef
import com.cursorforandroid.domain.Capabilities
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * [PullRequestSource] over GitHub's REST API, for Extended mode off: the state of a pull request on github.com, read
 * anonymously. A pull request anywhere else is [PullRequestLookup.Unreadable] — its pill stays neutral — and so is
 * one an anonymous reader cannot see (a private repository, which GitHub reports as not found). The rate limit ends
 * the pass like an outage would, and [GitHubApi] keeps the next pass from asking until it lifts.
 */
class GitHubPullRequestSource(private val gitHub: GitHubApi) : PullRequestSource {

    override suspend fun lookup(url: String): PullRequestLookup {
        val ref = GitHubPullRequestRef.parse(url) ?: return PullRequestLookup.Unreadable
        return try {
            PullRequestLookup.Found(gitHub.pullRequest(ref).toPullRequestState())
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubApiException) {
            if (e.isNotFound) PullRequestLookup.Unreadable else PullRequestLookup.Failed
        } catch (_: IOException) {
            PullRequestLookup.Failed
        }
    }
}

/**
 * The [PullRequestSource] that follows Extended mode: the account service's word while the mode is on (the view the
 * first-party apps show, every SCM Cursor connects to included), GitHub's REST API for GitHub-hosted repositories
 * while it is off, and nothing for the rest. Decided per lookup, so a change of mode takes effect with the next pull
 * request asked about; the graph resets the repository on the change so nothing learned under one mode stays.
 */
class CapabilityGatedPullRequestSource(
    private val capabilities: suspend () -> Capabilities,
    private val account: PullRequestSource,
    private val gitHub: PullRequestSource,
) : PullRequestSource {
    override suspend fun lookup(url: String): PullRequestLookup =
        if (capabilities().accountPullRequests) account.lookup(url) else gitHub.lookup(url)
}
