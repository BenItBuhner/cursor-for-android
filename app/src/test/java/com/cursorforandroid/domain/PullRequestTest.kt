package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PullRequestTest {

    @Test
    fun `parses the pull request URLs the API reports`() {
        assertThat(PullRequestRef.parse("https://github.com/acme/app/pull/123")).isEqualTo(PullRequestRef("acme", "app", 123))
        assertThat(PullRequestRef.parse(" https://www.github.com/acme/app.io/pull/7/files?diff=split#top ")).isEqualTo(PullRequestRef("acme", "app.io", 7))
        assertThat(PullRequestRef.parse("github.com/acme/app/pull/1")).isEqualTo(PullRequestRef("acme", "app", 1))
    }

    @Test
    fun `anything that is not a GitHub pull request is not a reference`() {
        assertThat(PullRequestRef.parse("https://github.com/acme/app")).isNull()
        assertThat(PullRequestRef.parse("https://github.com/acme/app/issues/12")).isNull()
        assertThat(PullRequestRef.parse("https://github.com/acme/app/pull/abc")).isNull()
        assertThat(PullRequestRef.parse("https://cursor.com/agents/bc-1")).isNull()
        assertThat(PullRequestRef.parse("https://gitlab.com/acme/app/-/merge_requests/3")).isNull()
        assertThat(PullRequestRef.parse("")).isNull()
    }

    @Test
    fun `GitHub's state, draft and merged fields map to one state`() {
        assertThat(PullRequestState.of("open", draft = false, merged = false)).isEqualTo(PullRequestState.Open)
        assertThat(PullRequestState.of("open", draft = true, merged = false)).isEqualTo(PullRequestState.Draft)
        assertThat(PullRequestState.of("closed", draft = false, merged = true)).isEqualTo(PullRequestState.Merged)
        assertThat(PullRequestState.of("closed", draft = false, merged = false)).isEqualTo(PullRequestState.Closed)
        assertThat(PullRequestState.of("CLOSED", draft = true, merged = true)).isEqualTo(PullRequestState.Merged)
        assertThat(PullRequestState.of("unknown", draft = false, merged = false)).isNull()
    }

    @Test
    fun `each state has its filter`() {
        PullRequestState.entries.forEach { state -> assertThat(GitFilter.of(state).label).isEqualTo(state.label) }
        assertThat(GitFilter.pullRequestStates).containsExactlyElementsIn(PullRequestState.entries.map(GitFilter::of)).inOrder()
    }
}
