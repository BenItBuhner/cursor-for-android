package com.cursorforandroid.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.components.PredictiveBackEasing
import com.cursorforandroid.ui.components.hitTestBoundary
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hosts the media viewer over [content] — the whole app, so that on a tablet the viewer covers the sidebar as well
 * as the chat — and provides [state] to it through [LocalMediaViewer]. The viewer is a layer of this window, not a
 * dialog: that is what lets a thumbnail's picture travel from its place in the transcript to the middle of the
 * screen and back as one continuous shape, with the transcript still visible under the fading scrim.
 *
 * While the viewer is open the system bars follow the chrome — shown with it, hidden with it (a swipe from the edge
 * brings them back briefly) — and their icons go light over the black. [autoHideControlsMillis] is how long the
 * chrome stays without a touch before it goes on its own; null leaves it until tapped.
 */
@Composable
fun MediaViewerHost(
    state: MediaViewerState,
    loader: MediaLoader,
    modifier: Modifier = Modifier,
    autoHideControlsMillis: Long? = AutoHideMillis,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().onGloballyPositioned { state.hostCoordinates = it }) {
        CompositionLocalProvider(LocalMediaViewer provides state) { content() }
        val session = state.session
        if (session != null) {
            key(session.id) { MediaViewerOverlay(state, session, loader, autoHideControlsMillis) }
        }
    }
    ImmersiveSystemBars(state)
}

/** The system bars: light icons and, with the chrome hidden, out of the way, for as long as something is open. */
@Composable
private fun ImmersiveSystemBars(state: MediaViewerState) {
    val view = LocalView.current
    val open = state.isOpen
    val hideBars = open && !state.controlsVisible
    DisposableEffect(view, open) {
        if (!open) return@DisposableEffect onDispose { }
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    LaunchedEffect(view, hideBars, open) {
        if (!open) return@LaunchedEffect
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (hideBars) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What the transform layer draws between: the thumbnail's box (null when no thumbnail is on screen, and the page
 * fades in or out where it is instead), the page's rect — live while opening, so the transform lands wherever the
 * page settles; frozen at the close, wherever the reader had dragged or zoomed the picture to — and whose picture.
 */
@Stable
private class TransformFrames(
    val thumbnail: ThumbnailFrame?,
    val pageRect: () -> Rect,
    val source: () -> PagePresentation?,
    val fallbackSize: IntSize,
    /** The thumbnail's own decode, drawn until the page's presentation is registered: the open's first frame has it. */
    val fallbackBitmap: ImageBitmap? = null,
)

@Composable
private fun MediaViewerOverlay(state: MediaViewerState, session: MediaViewerState.Session, loader: MediaLoader, autoHideControlsMillis: Long?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pagerState = rememberPagerState(initialPage = session.initialIndex) { session.entries.size }
    val presentations = remember { HashMap<Int, PagePresentation>() }
    val dismiss = remember { DismissState() }

    /** The open's frames: out of the tapped thumbnail, into wherever the first page rests once it is laid out. */
    fun openingFrames() = TransformFrames(
        thumbnail = state.originFrame(),
        pageRect = { presentations[session.initialIndex]?.fitted?.takeIf { it != Rect.Zero } ?: restingRect(viewport, session.seenSize) },
        source = { presentations[session.initialIndex] },
        fallbackSize = session.seenSize,
        fallbackBitmap = session.seen,
    )

    // Set as the overlay is first composed, not by an effect a frame later: the frame the viewer appears on already
    // draws the picture over its thumbnail — which the viewer has hidden — rather than a blank where it was.
    var frames by remember { mutableStateOf(if (state.phase == MediaViewerState.Phase.Opening) openingFrames() else null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    val actions = remember(loader) { MediaActions(context, loader) }

    fun currentPresentation(): PagePresentation? = presentations[state.currentIndex]

    /** The transform's frames for a close from wherever the page is now. */
    fun beginClose() {
        if (state.phase == MediaViewerState.Phase.Closing) return
        val presentation = currentPresentation()
        val from = presentation?.takeIf { it.fitted != Rect.Zero }?.displayed(dismiss)
            ?: restingRect(viewport, presentation?.imageSize ?: session.seenSize)
        val src = state.currentSrc
        frames = TransformFrames(
            thumbnail = src?.let(state::targetFrame),
            pageRect = { from },
            source = { presentation },
            fallbackSize = session.seenSize,
        )
        state.phase = MediaViewerState.Phase.Closing
        dismiss.release()
    }

    suspend fun finishClose() {
        state.progress.animateTo(0f, TransformSpec)
        state.finishClose()
    }

    suspend fun reopen() {
        state.progress.animateTo(1f, TransformSpec)
        state.phase = MediaViewerState.Phase.Open
        frames = null
        dismiss.reset()
    }

    // Opening: out of the tapped thumbnail (or, without one, up from a little below scale where the page will be).
    LaunchedEffect(Unit) {
        if (state.phase != MediaViewerState.Phase.Opening) return@LaunchedEffect
        if (frames == null) frames = openingFrames()
        state.progress.snapTo(0f)
        state.progress.animateTo(1f, TransformSpec)
        if (state.phase == MediaViewerState.Phase.Opening) {
            state.phase = MediaViewerState.Phase.Open
            frames = null
        }
    }
    // A close asked for from outside (the X, a test, the chat going away) or from a dismissing drag.
    LaunchedEffect(state.closeRequests) {
        if (state.closeRequests == 0) return@LaunchedEffect
        beginClose()
        finishClose()
    }
    // The pager's page is the viewer's: the thumbnails follow it, the drag belongs to the new page, the chrome comes back.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != state.currentIndex) {
                state.currentIndex = page
                dismiss.reset()
                state.controlsVisible = true
                interactions++
            }
        }
    }
    // The chrome goes on its own after a while without a touch; any touch, page or play brings it back and restarts the wait.
    LaunchedEffect(state.controlsVisible, interactions, state.phase, autoHideControlsMillis) {
        val wait = autoHideControlsMillis ?: return@LaunchedEffect
        if (state.controlsVisible && state.phase == MediaViewerState.Phase.Open) {
            delay(wait)
            state.controlsVisible = false
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NoticeMillis)
            notice = null
        }
    }

    // Back scrubs the close: the picture shrinks toward its thumbnail with the finger, and either finishes the trip
    // or comes back up, the way the drawer and the sheets answer the same gesture.
    PredictiveBackHandler(enabled = true) { events ->
        val startProgress = state.progress.value
        beginClose()
        try {
            events.collect { event -> state.progress.snapTo(startProgress * (1f - BackScrubShare * PredictiveBackEasing.transform(event.progress))) }
        } catch (_: CancellationException) {
            scope.launch { reopen() }
            return@PredictiveBackHandler
        }
        scope.launch { finishClose() }
    }

    // A zoomed picture's drag drives the pager past the picture's edge (see PagerHandover): in a left-to-right
    // layout the finger and the pager's offset run opposite ways, and a fling past this speed commits the page.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val minFlingPx = with(LocalDensity.current) { PageFlingVelocity.toPx() }
    val handover = remember(pagerState, rtl, minFlingPx) { PagerHandover(pagerState, reverse = !rtl, minFlingVelocityPx = minFlingPx) }
    val uriHandler = LocalUriHandler.current
    // The composer's draft, for a picture outside the workspace whose only way in is the agent copying it there.
    val onAskToCopy = LocalTranscriptControls.current.onAskToCopyFile
    val environment = remember(loader, dismiss, scope, pagerState, handover, actions, uriHandler, onAskToCopy) {
        PageEnvironment(
            loader = loader,
            dismiss = dismiss,
            pagerState = pagerState,
            handover = handover,
            scope = scope,
            onTap = {
                state.controlsVisible = !state.controlsVisible
                interactions++
            },
            onDismiss = { state.close() },
            onOpenInBrowser = { url -> if (runCatching { uriHandler.openUri(url) }.isFailure) notice = "Nothing on this device opens links." },
            onOpenElsewhere = { ref, entry -> scope.launch { actions.openWith(ref, entry).onFailure { notice = MediaLoader.problemOf(it).title } } },
            onCopyIntoWorkspace = onAskToCopy?.let { ask -> { path -> state.close(); ask(path) } },
        )
    }
    val current = state.current
    val currentPlayback = currentPresentation()?.playback.takeIf { current?.isPlayable == true }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // Never a root that consumes here: consuming every move cancelled the pager's touch-slop detection
            // (which re-reads each move on the Final pass), so only a swipe fast enough to clear the slop on its
            // first move ever turned a page. Hit testing alone keeps the shell underneath out of reach.
            .hitTestBoundary()
            .semantics { paneTitle = "Media viewer" }
            .testTag("media-viewer"),
    ) {
        // The scrim: black with the transform's progress, thinned as a dismissing drag pulls the page down.
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Color.Black, alpha = (state.progress.value * (1f - dismiss.fraction * DragScrimShare)).coerceIn(0f, 1f))
            },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().testTag("viewer-pager"),
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            // A slow drag commits on how far the page was pulled — past two fifths of the width — rather than
            // Compose's half; a fast one commits on its speed, as it always did. The settle is the pager's spring.
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = ViewerGeometry.PageCommitShare),
            userScrollEnabled = state.phase == MediaViewerState.Phase.Open,
            key = { session.entries[it].src },
        ) { page ->
            val entry = session.entries[page]
            val presentation = remember { PagePresentation(if (page == session.initialIndex) session.seen else null) }
            DisposableEffect(presentation) {
                presentations[page] = presentation
                onDispose { if (presentations[page] === presentation) presentations.remove(page) }
            }
            val isCurrent = page == state.currentIndex
            when (entry.kind) {
                MediaEntry.Kind.Image -> ImagePage(state, session, entry, page, presentation, environment, viewport, isCurrent)
                MediaEntry.Kind.Video -> VideoPage(state, session, entry, page, presentation, environment, viewport, isCurrent)
                MediaEntry.Kind.Audio -> AudioPage(state, session, entry, page, presentation, environment, viewport, isCurrent)
            }
        }
        // The chrome: composed on the open's first frame, so that its arrival costs the landing frame nothing, but
        // faded in only over the transform's last stretch (a layer alpha, read here); out with the close; away
        // while the reader does not want it. Under the transform layer, so the picture on its way passes over it.
        AnimatedVisibility(
            visible = state.phase != MediaViewerState.Phase.Closing && state.controlsVisible,
            enter = fadeIn(tween(ChromeFadeMillis)),
            exit = fadeOut(tween(ChromeFadeMillis)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (state.phase == MediaViewerState.Phase.Opening) ((state.progress.value - ChromeFadeStart) / (1f - ChromeFadeStart)).coerceIn(0f, 1f) else 1f
                },
            ) {
                if (current != null) {
                    val ref = remember(current.src, session.agentId) { MediaRef.parse(current.src, session.agentId) }
                    fun run(block: suspend () -> Result<String?>) {
                        interactions++
                        scope.launch { block().fold(onSuccess = { it?.let { message -> notice = message } }, onFailure = { notice = MediaLoader.problemOf(it).title }) }
                    }
                    ViewerTopBar(
                        index = state.currentIndex,
                        count = session.entries.size,
                        entry = current,
                        onClose = { state.close() },
                        onShare = { run { actions.share(ref, current).map { null } } },
                        onSave = if (MediaActions.canSave) ({ run { actions.save(ref, current).map { it } } }) else null,
                        onOpenWith = if (current.isPlayable) ({ run { actions.openWith(ref, current).map { null } } }) else null,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    ViewerBottomBar(
                        entry = current,
                        playback = currentPlayback,
                        muted = state.muted,
                        onMute = { state.muted = it },
                        onInteract = { interactions++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        // The transform layer: the page's picture on its way between its thumbnail and its place, above everything.
        frames?.let { active ->
            if (state.phase != MediaViewerState.Phase.Open) {
                TransformLayer(state, active, dismiss, Modifier.fillMaxSize().testTag("viewer-transform"))
            }
        }
        notice?.let { text ->
            ViewerNotice(text, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(bottom = 108.dp))
        }
    }
}

/**
 * Draws the picture on its way: at [MediaViewerState.progress] 0 it fills the thumbnail's box, clipped to the box's
 * rounded corners; at 1 it is the page, wherever the page rests or was dragged to. Without a thumbnail it scales up
 * from (or down to) a little under the page's size while fading. One `drawImage` under a transform per frame, and
 * nothing recomposes for it.
 */
@Composable
private fun TransformLayer(state: MediaViewerState, frames: TransformFrames, dismiss: DismissState, modifier: Modifier = Modifier) {
    val clipPath = remember { Path() }
    Spacer(
        modifier.drawBehind {
            val progress = state.progress.value
            val bitmap = frames.source()?.bitmap ?: frames.fallbackBitmap
            val imageSize = bitmap?.let { IntSize(it.width, it.height) } ?: frames.fallbackSize
            if (imageSize.width <= 0 || imageSize.height <= 0) return@drawBehind
            // Before the viewer's own size is measured the layer's is the viewport: the page's rest is known from it.
            val page = frames.pageRect().takeIf { it.width > 0f && it.height > 0f } ?: restingRect(IntSize(size.width.roundToInt(), size.height.roundToInt()), imageSize)
            if (page.width <= 0f || page.height <= 0f) return@drawBehind
            val viewport = Rect(Offset.Zero, size)
            val thumbnail = frames.thumbnail
            val target = thumbnail ?: ThumbnailFrame(
                bounds = ViewerGeometry.rectAround(page.center, page.width * FallbackShrink, page.height * FallbackShrink),
                cornerRadius = FallbackCornerRadius.toPx(),
                crop = false,
            )
            val frame = ViewerGeometry.transition(target, imageSize.width, imageSize.height, page, viewport, progress)
            val alpha = if (thumbnail == null) progress.coerceIn(0f, 1f) else 1f
            if (bitmap == null) return@drawBehind
            val draw: () -> Unit = {
                translate(frame.image.left, frame.image.top) {
                    scale(frame.image.width / imageSize.width, frame.image.height / imageSize.height, pivot = Offset.Zero) {
                        drawImage(bitmap, alpha = alpha)
                    }
                }
            }
            if (frame.cornerRadius > 0.5f) {
                clipPath.reset()
                clipPath.addRoundRect(RoundRect(frame.clip, CornerRadius(frame.cornerRadius)))
                clipPath(clipPath) { draw() }
            } else {
                clipRect(frame.clip.left, frame.clip.top, frame.clip.right, frame.clip.bottom) { draw() }
            }
        },
    )
}

/** Where a page of a picture of [imageSize] rests in [viewport]: the fitted rect, or the viewport when neither is known yet. */
private fun restingRect(viewport: IntSize, imageSize: IntSize): Rect {
    val size = viewport.toSize()
    if (size.width <= 0f || size.height <= 0f) return Rect.Zero
    if (imageSize.width <= 0 || imageSize.height <= 0) return Rect(Offset.Zero, size)
    return ViewerGeometry.fitted(size, imageSize.width, imageSize.height)
}

/** Compose's own line between a drag that is let go and a fling: a page flung faster than this commits whatever the distance. */
private val PageFlingVelocity = 400.dp

/** The transform's curve: Material's emphasised decelerate, the drawer's and sheets' slide, over the same 300 ms. */
internal val TransformSpec: AnimationSpec<Float> = tween(TransformMillis, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
internal const val TransformMillis = 300
private const val ChromeFadeMillis = 160
/** The share of the open transform the chrome stays clear for before it fades in over the rest. */
private const val ChromeFadeStart = 0.6f
private const val AutoHideMillis = 3_500L
private const val NoticeMillis = 2_500L
/** How far toward the thumbnail a full back gesture takes the page before it is committed. */
private const val BackScrubShare = 0.45f
/** How much of the scrim a dismissing drag at its threshold takes away. */
private const val DragScrimShare = 0.85f
private const val FallbackShrink = 0.82f
private val FallbackCornerRadius = 16.dp
