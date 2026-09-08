package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PullRequestStatusApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.PullRequestRef
import com.cursorforandroid.domain.PullRequestState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/** How the account service's answers and refusals read as lookups, and when it stops being asked. */
class CursorPullRequestSourceTest {

    private var answer: () -> PullRequestState? = { PullRequestState.Open }
    private var calls = 0
    private val api = PullRequestStatusApi { _ -> calls++; answer() }
    private val source = CursorPullRequestSource(api)
    private val url = "https://github.com/acme/app/pull/1"
    private val ref = PullRequestRef.parse(url)

    @Test
    fun `an answer is found, no answer is unreadable`() = runBlocking<Unit> {
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Found(PullRequestState.Open))
        answer = { null }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Unreadable)
    }

    @Test
    fun `a refusal for one pull request is unreadable, a server or network failure is a failure`() = runBlocking<Unit> {
        answer = { throw ConnectRpcException(404, "not_found", "No such PR.") }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Unreadable)
        answer = { throw ConnectRpcException(503, "unavailable", "Try later.") }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Failed)
        answer = { throw IOException("offline") }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Failed)
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `a session failure retrying cannot fix halts the source until it is reset`() = runBlocking<Unit> {
        answer = { throw SessionUnavailableException("Device policy.", SessionUnavailableException.SIGN_IN_POLICY_VIOLATION) }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Failed)
        answer = { PullRequestState.Merged }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Failed)
        assertThat(calls).isEqualTo(1)

        source.reset()
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Found(PullRequestState.Merged))
    }

    @Test
    fun `a session failure that may pass does not halt it`() = runBlocking<Unit> {
        answer = { throw SessionUnavailableException("Couldn't reach Cursor.") }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Failed)
        answer = { PullRequestState.Closed }
        assertThat(source.lookup(url, ref)).isEqualTo(PullRequestLookup.Found(PullRequestState.Closed))
    }
}
