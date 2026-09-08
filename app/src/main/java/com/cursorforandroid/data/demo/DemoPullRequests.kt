package com.cursorforandroid.data.demo

import com.cursorforandroid.data.repo.PullRequestLookup
import com.cursorforandroid.data.repo.PullRequestSource
import com.cursorforandroid.domain.PullRequestRef
import com.cursorforandroid.domain.PullRequestState

/** The pull request source for the demo: the seeds' pull requests in the states they were written with; one a demo run opens is open. */
object DemoPullRequests : PullRequestSource {
    override suspend fun lookup(url: String, ref: PullRequestRef?): PullRequestLookup =
        PullRequestLookup.Found(DemoData.pullRequestStates[url] ?: PullRequestState.Open)
}
