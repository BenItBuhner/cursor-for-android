package com.cursorforandroid.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [block] on [Dispatchers.IO] and brings the caller back to the main thread afterwards — the one way a
 * composition coroutine (a `produceState`, a `LaunchedEffect`, a `rememberCoroutineScope` launch) may hop off the
 * main thread for a decode or a file read.
 *
 * A plain `withContext(Dispatchers.IO)` resumes its caller on the caller's own dispatcher. In the app that is
 * Compose's main-confined `AndroidUiDispatcher`, so the state write after the hop lands on the main thread through
 * the looper. Under the Compose test harness the composition's effects run on an unconfined dispatcher, and a
 * caller resumed by the IO worker carries on *on that worker*: a snapshot state written off the main thread, the
 * recomposer woken from the worker, a whole frame performed there when the worker's frame wait races the main
 * thread's — and a wake-up that the main thread's `waitUntil` never sees (the media viewer's 30 s hang, #262; the
 * attachment decodes' 10 s waits). Confining the whole hop to [Dispatchers.Main.immediate] — inline when the caller
 * is already on the main thread, as a composition effect is — makes the IO block's return travel through the main
 * looper and the caller resume on the main thread, whichever thread the work finished on. The same rule as
 * `MediaLoader.onMain`.
 */
suspend fun <T> ioThenMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { withContext(Dispatchers.IO) { block() } }
