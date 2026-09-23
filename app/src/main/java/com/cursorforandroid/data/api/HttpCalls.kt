package com.cursorforandroid.data.api

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * [block] given the call's response on the IO dispatcher, the call cancelled whenever the coroutine is — its body
 * included. [await] answers to cancellation only until the headers arrive; the body is read after that in blocking
 * reads, which a cancelled coroutine cannot interrupt. A stream the server holds open (the account's live stream
 * sends heartbeats for as long as it is read) was read on for good behind a screen that had left, with its thread,
 * its HTTP/2 stream and its throttle permit; cancelling the call ends the read.
 */
suspend fun <T> Call.readCancellably(block: (Response) -> T): T = coroutineScope {
    val call = this@readCancellably
    val done = AtomicBoolean(false)
    val cancelWithCaller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!done.get()) call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { call.await().use(block) }
    } catch (e: IOException) {
        // The read the cancellation ended: the caller's cancellation, not a failure of the network.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        done.set(true)
        cancelWithCaller.cancel()
    }
}
