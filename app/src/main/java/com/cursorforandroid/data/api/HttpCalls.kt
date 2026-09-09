package com.cursorforandroid.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The call's response, awaited so that cancelling the coroutine cancels the call. [Call.execute] blocks in a socket
 * read that does not answer to interruption, so work the caller has given up on — a sign-in the user backed out of,
 * a token exchange for a session that has ended — would hold its thread and its connection until the server answers
 * or a timeout expires. A response that arrives after the caller went away is closed rather than left open.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { this.cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}
