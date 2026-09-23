package com.cursorforandroid.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.unit.IntSize

/**
 * The media viewer, as the rest of the app sees it: one is made at the root of the app and reached through
 * [LocalMediaViewer]. Thumbnails register where they are drawn ([rememberThumbnailSlot]) and [open] the viewer on
 * themselves; the viewer grows out of the thumbnail's box, pages through the conversation's media, and shrinks back
 * into whichever thumbnail shows the page it was closed on.
 *
 * The state is the model only: which media is open, which page, how far the open transform has come. The host
 * ([MediaViewerHost]) runs the animations and draws.
 */
@Stable
class MediaViewerState internal constructor(restored: Session?) {

    /** What the viewer was opened on: the media, the page it opened at, and the thumbnail it grew out of. */
    class Session internal constructor(
        val id: Long,
        /** The chat the media belongs to, for resolving artifact paths; null outside one. */
        val agentId: String?,
        val entries: List<MediaEntry>,
        val initialIndex: Int,
        /** The thumbnail the viewer grew out of, to shrink back into when the same page is closed. */
        internal val origin: ThumbnailSlot?,
        /** The thumbnail's own decode, drawn until the page's full-size one lands. */
        internal val seen: ImageBitmap?,
        /** The size of the picture behind [seen], or of the thumbnail if nothing was decoded, for the transform's first frame. */
        internal val seenSize: IntSize,
        /** A video opened by a tap on it starts playing; one reached by a swipe waits for the play button. */
        val autoplay: Boolean,
        /** Drafts a follow-up asking the agent to copy a file into its workspace; null where no composer stands behind the opener. */
        val onAskToCopy: ((path: String) -> Unit)? = null,
    )

    enum class Phase { Closed, Opening, Open, Closing }

    var session by mutableStateOf(restored)
        private set

    var phase by mutableStateOf(if (restored == null) Phase.Closed else Phase.Open)
        internal set

    /** 0 at the thumbnail, 1 at the open page; the transform's position, driven by the host. */
    val progress = Animatable(if (restored == null) 0f else 1f)

    /** The page the pager rests on; the thumbnails read it to know which of them the viewer stands in for. */
    var currentIndex by mutableIntStateOf(restored?.initialIndex ?: 0)
        internal set

    /** The page on screen's zoom about the centre and pan, as it reads them: 1 and none at rest. Mirrored from the page for whoever asks. */
    var zoomScale by mutableFloatStateOf(1f)
        internal set
    var zoomPan by mutableStateOf(Offset.Zero)
        internal set
    /** The pixels the page on screen is drawing: the thumbnail's decode, then the one sized for the viewport. */
    var currentDecodeSize by mutableStateOf(IntSize.Zero)
        internal set

    /** Whether the chrome — close, index, actions, caption, the video's controls — is showing; a tap toggles it. */
    var controlsVisible by mutableStateOf(true)
        internal set

    /** The viewer's sound, kept across pages for the session. */
    var muted by mutableStateOf(false)
        internal set

    /** A count of closes, so the host can react to a close asked for while it is mid-animation. */
    internal var closeRequests by mutableIntStateOf(0)
        private set

    /** The host's own coordinates, the frame every thumbnail box is expressed in. */
    internal var hostCoordinates: LayoutCoordinates? = null

    private val slots = LinkedHashMap<Int, ThumbnailSlot>()
    private var nextSlotId = 1
    private var nextSessionId = 1L

    val isOpen: Boolean get() = session != null

    val current: MediaEntry? get() = session?.entries?.getOrNull(currentIndex)

    /** The reference of the page on screen: the thumbnail showing the same one is hidden while the viewer stands in for it. */
    val currentSrc: String? get() = current?.src

    internal fun register(src: String): ThumbnailSlot {
        val slot = ThumbnailSlot(nextSlotId++, src, this)
        slots[slot.id] = slot
        return slot
    }

    internal fun unregister(slot: ThumbnailSlot) {
        slots.remove(slot.id)
        slot.coordinates = null
    }

    /** Whether [slot] should leave its box blank because the viewer is drawing its picture (open, or on the way). */
    fun isHidden(slot: ThumbnailSlot): Boolean = phase != Phase.Closed && slot.src == currentSrc

    /**
     * Opens the viewer on [src] among [entries] (the conversation's media, in order; a reference not among them is
     * shown on its own). [slot] is the thumbnail tapped, when there is one to grow out of.
     */
    fun open(
        agentId: String?,
        entries: List<MediaEntry>,
        src: String,
        slot: ThumbnailSlot? = null,
        seen: ImageBitmap? = null,
        fallback: MediaEntry? = null,
        autoplay: Boolean = false,
        onAskToCopy: ((path: String) -> Unit)? = null,
    ) {
        if (session != null) return
        val index = entries.indexOfFirst { it.src == src }
        val list = if (index >= 0) entries else listOf(fallback ?: MediaEntry(src, MediaEntry.Kind.Image))
        val at = if (index >= 0) index else 0
        val seenSize = when {
            seen != null -> IntSize(seen.width, seen.height)
            else -> slot?.coordinates?.takeIf { it.isAttached }?.size ?: IntSize.Zero
        }
        currentIndex = at
        controlsVisible = true
        // The count is the session's: a close asked of the last session must not close this one as it opens.
        closeRequests = 0
        session = Session(nextSessionId++, agentId, list, at, slot, seen, seenSize, autoplay, onAskToCopy)
        phase = Phase.Opening
    }

    /** Asks the host to play the close transform and clear the session; a no-op when nothing is open. */
    fun close() {
        if (session == null) return
        closeRequests++
    }

    /** The host is done with the session: nothing is open, every thumbnail draws itself again. */
    internal fun finishClose() {
        session = null
        phase = Phase.Closed
    }

    /**
     * The thumbnail box the page at [src] should shrink into: the one it grew out of, when that is still on screen,
     * else any other thumbnail of the same media that is; null when none is, and the page fades out where it is.
     */
    internal fun targetFrame(src: String): ThumbnailFrame? {
        val host = hostCoordinates?.takeIf { it.isAttached } ?: return null
        val candidates = buildList {
            session?.origin?.takeIf { it.src == src }?.let(::add)
            slots.values.filterTo(this) { it.src == src && it !== session?.origin }
        }
        for (slot in candidates) {
            val frame = slot.frame(host, requireVisible = true) ?: continue
            return frame
        }
        return null
    }

    /** Where the tapped thumbnail is, if it is attached: the box the open transform starts from. */
    internal fun originFrame(): ThumbnailFrame? {
        val host = hostCoordinates?.takeIf { it.isAttached } ?: return null
        return session?.origin?.frame(host, requireVisible = false)
    }

    companion object {
        /** Keeps what is open across a process death: the media, the page; not the transform, which is at rest by then. */
        val Saver: Saver<MediaViewerState, Any> = Saver(
            save = { state ->
                val session = state.session ?: return@Saver null
                listOf(
                    session.agentId,
                    state.currentIndex,
                    session.entries.map { listOf(it.src, it.kind.name, it.caption, it.fileName, it.mimeType, it.durationMs) },
                )
            },
            restore = { saved ->
                val parts = saved as List<*>
                @Suppress("UNCHECKED_CAST")
                val entries = (parts[2] as List<List<Any?>>).map { e ->
                    MediaEntry(e[0] as String, MediaEntry.Kind.valueOf(e[1] as String), e[2] as String?, e[3] as String, e[4] as String?, e[5] as Long?)
                }
                val index = (parts[1] as Int).coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                MediaViewerState(
                    if (entries.isEmpty()) null else Session(0L, parts[0] as String?, entries, index, origin = null, seen = null, seenSize = IntSize.Zero, autoplay = false),
                )
            },
        )
    }
}

/**
 * One thumbnail on screen: where it is drawn ([coordinates], kept current by the layout), how its corners are
 * rounded and whether it crops its picture. Registered for as long as the thumbnail is composed.
 */
class ThumbnailSlot internal constructor(val id: Int, val src: String, internal val viewer: MediaViewerState? = null) {
    /** Plain fields, not state: the layout writes them on every scroll and nothing needs to recompose for it. */
    var coordinates: LayoutCoordinates? = null
    var cornerRadius: Float = 0f
    var crop: Boolean = false

    /**
     * The box in [host]'s coordinates. With [requireVisible], a box that is clipped away by more than half — behind
     * the header, scrolled under the composer — counts as not there, so the page fades where it is instead of
     * flying to a spot nothing is drawn at.
     */
    internal fun frame(host: LayoutCoordinates, requireVisible: Boolean): ThumbnailFrame? {
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        // A thumbnail in another window (a sheet's) shares no hierarchy with the host: there is no box to grow out of.
        if (coords.findRootCoordinates() !== host.findRootCoordinates()) return null
        val bounds = host.localBoundingBoxOf(coords, clipBounds = false)
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        if (requireVisible) {
            val visible = host.localBoundingBoxOf(coords, clipBounds = true)
            if (visible.width * visible.height < bounds.width * bounds.height * VisibleShare) return null
        }
        return ThumbnailFrame(bounds, cornerRadius, crop)
    }

    private companion object {
        const val VisibleShare = 0.5f
    }
}

/** The app's viewer; null where none is hosted (a preview, a component test), and a tap on a figure then does nothing. */
val LocalMediaViewer = staticCompositionLocalOf<MediaViewerState?> { null }

/** The viewer's state, kept across a process death by [MediaViewerState.Saver]. */
@Composable
fun rememberMediaViewerState(): MediaViewerState = rememberSaveable(saver = MediaViewerState.Saver) { MediaViewerState(null) }
