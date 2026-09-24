package com.cursorforandroid.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The viewer's saves to the gallery, one per item (keyed by [MediaRef.cacheKey]) and each with the state its Save
 * button shows. They run in [scope], not the viewer's: a save goes on — and its button still shows it — across a
 * swipe to another page, the viewer's close and its next open. A tap on an item already saving starts nothing.
 *
 * A file already whole on this device ([MediaLoader.withFile]) goes straight into the gallery; otherwise it is
 * fetched first, the button's ring filling with the bytes when the server says how many there are.
 */
@Stable
class MediaSaves(private val scope: CoroutineScope, private val loader: MediaLoader, private val gallery: GallerySaver) {

    sealed interface State {
        data object Idle : State

        /** Under way: [fraction] of it done, or null while there is no telling how much is left. */
        data class Working(val fraction: Float?) : State

        data class Saved(val message: String) : State

        data class Failed(val message: String) : State
    }

    /** How a save ended, for the viewer's notice and the haptic: [retry] starts the same save again. */
    class Outcome(val saved: Boolean, val message: String, val retry: (() -> Unit)?)

    enum class Start { Started, AlreadyRunning, NeedsPermission }

    private val states = mutableStateMapOf<String, State>()
    private val jobs = HashMap<String, Job>()
    private val outcomeFlow = MutableSharedFlow<Outcome>(extraBufferCapacity = 16)

    /** Every save's end, as it happens. */
    val outcomes: SharedFlow<Outcome> = outcomeFlow.asSharedFlow()

    fun state(ref: MediaRef): State = states[ref.cacheKey] ?: State.Idle

    /** Starts saving [entry] unless it is already under way, or answers that the storage permission has to be asked for first. */
    fun save(ref: MediaRef, entry: MediaEntry): Start {
        val key = ref.cacheKey
        if (jobs[key]?.isActive == true) return Start.AlreadyRunning
        if (gallery.needsPermission) return Start.NeedsPermission
        states[key] = State.Working(null)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val shown = AtomicInteger(-1)
            // Called on a worker: only a whole percent more is posted to the main thread, and only while this save is the item's.
            fun report(fraction: Float?) {
                val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: -1
                if (shown.getAndSet(percent) == percent) return
                scope.launch { if (jobs[key] === job && states[key] is State.Working) states[key] = State.Working(fraction) }
            }
            try {
                val folder = loader.withFile(ref, entry.fileName, onProgress = { read, total -> report(if (total > 0L) FetchShare * read / total else null) }) { file, fetched ->
                    gallery.save(file, entry.fileName, entry.mimeType, entry.kind) { copied -> report(if (fetched) FetchShare + (1f - FetchShare) * copied else copied) }
                }
                finish(key, job, State.Saved("Saved to ${folder.label}"), Outcome(true, "Saved to ${folder.label}", null))
            } catch (e: CancellationException) {
                if (jobs[key] === job) states.remove(key)
                throw e
            } catch (t: Throwable) {
                val reason = MediaLoader.problemOf(t).title
                finish(key, job, State.Failed(reason), Outcome(false, "Couldn't save: $reason", retry = { save(ref, entry) }))
            }
        }
        jobs[key] = job
        job.start()
        return Start.Started
    }

    /** Marks [entry]'s save as failed without trying it: the storage permission it needed was refused. */
    fun refuse(ref: MediaRef, entry: MediaEntry, reason: String) {
        if (jobs[ref.cacheKey]?.isActive == true) return
        states[ref.cacheKey] = State.Failed(reason)
        outcomeFlow.tryEmit(Outcome(false, reason, retry = { save(ref, entry) }))
    }

    private fun finish(key: String, job: Job, state: State, outcome: Outcome) {
        if (jobs[key] !== job) return
        states[key] = state
        jobs.remove(key)
        outcomeFlow.tryEmit(outcome)
    }

    private companion object {
        /** The ring's share for the download when there is one; the copy into the gallery fills the rest. */
        const val FetchShare = 0.9f
    }
}

/** Saves that live as long as this composition: the host's own when the app hands it none. */
@Composable
internal fun rememberMediaSaves(loader: MediaLoader): MediaSaves {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    return remember(loader, scope) { MediaSaves(scope, loader, GallerySaver(context)) }
}
