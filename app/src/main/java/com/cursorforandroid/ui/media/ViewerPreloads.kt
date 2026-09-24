package com.cursorforandroid.ui.media

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.components.boundedPixels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A page's picture decoded ahead of the viewer's open, for [viewport]: [bitmap] once it has landed, null until then
 * and for one that could not be read.
 */
@Stable
internal class Preload(val src: String, val viewport: IntSize) {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    private val done = CompletableDeferred<ImageBitmap?>()

    val isDone: Boolean get() = done.isCompleted

    /** The picture, once the decode is over; null when there is none. */
    suspend fun await(): ImageBitmap? = done.await()

    fun finish(result: ImageBitmap?) {
        bitmap = result
        done.complete(result)
    }
}

/**
 * The viewer's decodes started before it opens. A press on a composer's tile starts its page's own decode — the size
 * the page asks for, through the same loader — so the open grows out of a screen-sized picture instead of the tile's
 * thumbnail, and one still on its way when the open starts lands mid-transform, faded in over the thumbnail rather
 * than snapped (see [PagePresentation.offer]). The last [MaxKept] are kept; one asked for again at the same size is
 * the same decode.
 */
internal class ViewerPreloads(
    private val loader: MediaLoader,
    private val scope: CoroutineScope,
    /** The viewer's size, which its pages decode for; zero until the host is laid out. */
    private val viewport: () -> IntSize,
) {
    private val kept = LinkedHashMap<String, Preload>()

    operator fun get(src: String): Preload? = kept[src]

    /** Starts [entry]'s decode unless one for the same size is on its way or has landed; [prepare] runs first. */
    fun start(entry: MediaEntry, agentId: String?, prepare: suspend () -> Unit): Preload? {
        if (entry.kind == MediaEntry.Kind.Audio) return null
        val size = viewport().takeIf { it.width > 0 && it.height > 0 } ?: return null
        val existing = kept.remove(entry.src)
        if (existing != null && existing.viewport == size && (!existing.isDone || existing.bitmap != null)) {
            kept[entry.src] = existing
            return existing
        }
        val preload = Preload(entry.src, size)
        kept[entry.src] = preload
        while (kept.size > MaxKept) kept.remove(kept.keys.first())
        scope.launch {
            var result: ImageBitmap? = null
            try {
                prepare()
                result = decode(entry, MediaRef.parse(entry.src, agentId), size)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            } finally {
                preload.finish(result)
            }
        }
        return preload
    }

    /** The picture [entry]'s page would decode first: the image at the page's bound, a recording's poster at the viewport's long edge. */
    private suspend fun decode(entry: MediaEntry, ref: MediaRef, viewport: IntSize): ImageBitmap? {
        val bitmap = when (entry.kind) {
            MediaEntry.Kind.Image -> boundedPixels(viewport.width, viewport.height).let { target -> loader.image(ref, target.width, target.height) }
            MediaEntry.Kind.Video -> loader.videoPoster(ref, maxOf(viewport.width, viewport.height))?.frame
            MediaEntry.Kind.Audio -> null
        } ?: return null
        // The open's first frames draw it at a tile's size: mipmaps keep that smooth, and the upload starts now.
        bitmap.setHasMipMap(true)
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap()
    }

    private companion object {
        /** A screen-sized decode is a few megabytes; the last couple pressed is all an open can want. */
        const val MaxKept = 2
    }
}
