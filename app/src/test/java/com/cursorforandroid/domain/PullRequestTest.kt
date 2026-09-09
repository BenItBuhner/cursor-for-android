package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PullRequestTest {

    @Test
    fun `each state has its filter`() {
        PullRequestState.entries.forEach { state -> assertThat(GitFilter.of(state).label).isEqualTo(state.label) }
        assertThat(GitFilter.pullRequestStates).containsExactlyElementsIn(PullRequestState.entries.map(GitFilter::of)).inOrder()
    }
}
