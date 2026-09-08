package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PullRequestStatusApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.PullRequestRef
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * [PullRequestSource] over the account service's `GetPullRequestMergeStatus`: the view the desktop Agents window and
 * the iOS app show, which knows private repositories and every SCM Cursor connects to, not only GitHub. A failure
 * retrying cannot fix (a rejected key, a device policy) halts it until [reset], so the GitHub fallback is not held
 * up by a doomed request at every pass.
 */
class CursorPullRequestSource(private val api: PullRequestStatusApi) : PullRequestSource {

    @Volatile private var halted = false

    override suspend fun lookup(url: String, ref: PullRequestRef?): PullRequestLookup {
        if (halted) return PullRequestLookup.Failed
        return try {
            api.mergeStatus(url)?.let { PullRequestLookup.Found(it) } ?: PullRequestLookup.Unreadable
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionUnavailableException) {
            if (e.isPermanent) halted = true
            PullRequestLookup.Failed
        } catch (e: ConnectRpcException) {
            // The service will not answer for this PR (gone, or not the account's); anything else may pass.
            if (!e.isUnauthenticated && e.httpCode in 400..499) PullRequestLookup.Unreadable else PullRequestLookup.Failed
        } catch (_: IOException) {
            PullRequestLookup.Failed
        }
    }

    /** On sign-in or sign-out: the next account may fare better. */
    fun reset() {
        halted = false
    }
}
