package com.cursorforandroid.ui.media

import android.view.TextureView
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.media.MediaProblem
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.boundedPixels
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.LoadNoticeCard
import com.cursorforandroid.ui.conversation.NoticeAction
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * What one page has to show and where: the best picture it has so far (the thumbnail's decode, then the
 * screen-sized one; a recording's poster), the rect that picture rests in, its zoom, and — for a recording — its
 * playback. The transform reads it to grow a page out of its thumbnail and to shrink it back from wherever the
 * reader left it.
 */
@Stable
internal class PagePresentation(initial: ImageBitmap?) {
    var bitmap by mutableStateOf(initial)
        private set
    /** The full-size decode has landed; a smaller offer no longer replaces it. */
    var loaded by mutableStateOf(false)
    /** Why the page has nothing to show, in the reader's words; null while it loads or once it shows. */
    var problem by mutableStateOf<MediaProblem?>(null)
    /** Asks of the page's picture so far: Retry and Wake count one up, and the decode is asked for again. */
    var attempt by mutableIntStateOf(0)
        private set
    /** The agent's machine is being woken for this page's read; the spinner says so. */
    var waking by mutableStateOf(false)

    /** Reads the picture again, first waking the agent's machine when [wake]. */
    fun retry(wake: Boolean) {
        problem = null
        waking = wake
        attempt++
    }
    /** The rect the picture rests in, in the page's (and so the viewer's) coordinates. */
    var fitted by mutableStateOf(Rect.Zero)
    val zoom = ZoomState()
    var playback by mutableStateOf<VideoPlayback?>(null)

    val imageSize: IntSize get() = bitmap?.let { IntSize(it.width, it.height) } ?: IntSize.Zero

    /** A decode arrived: kept if it is sharper than what is shown. */
    fun offer(candidate: ImageBitmap) {
        val current = bitmap
        if (current == null || candidate.width >= current.width) bitmap = candidate
    }

    /** Where the picture is drawn right now, zoom, pan and any dismiss drag included. */
    fun displayed(dismiss: DismissState): Rect = ViewerGeometry.displayed(fitted, zoom.scale, zoom.pan, dismiss.drag, dismiss.scale)
}

/** The viewer's "ask to copy" for a page: closes the viewer, then drafts the follow-up its session carried; null when none was. */
private fun MediaViewerState.Session.copyInto(state: MediaViewerState): ((path: String) -> Unit)? =
    onAskToCopy?.let { ask -> { path -> state.close(); ask(path) } }

/** Everything a page shares with the viewer: the loader, the dismiss drag, and what a tap or a dismissing drag does. */
internal class PageEnvironment(
    val loader: MediaLoader,
    val dismiss: DismissState,
    /** The pager the pages sit in: a zoomed picture's drag drives it past the picture's edge, and a page left behind rests once out of its view. */
    val pagerState: PagerState,
    val handover: PagerHandover,
    val scope: CoroutineScope,
    val onTap: () -> Unit,
    val onDismiss: (velocityY: Float) -> Unit,
    /** Hands the file of a page that cannot show it to the browser, or to another app, from the page's own row. */
    val onOpenInBrowser: (url: String) -> Unit = {},
    val onOpenElsewhere: (MediaRef, MediaEntry) -> Unit = { _, _ -> },
) {
    /** Whether [page] is anywhere in the pager's viewport, however little of it. */
    fun isVisible(page: Int): Boolean = pagerState.layoutInfo.visiblePagesInfo.any { it.index == page }
}

/**
 * A picture: decoded progressively — the thumbnail's own decode is on screen from the first frame, a quick small
 * decode stands in for a page reached by a swipe, the screen-sized decode replaces either as it lands (Coil's disk
 * cache serves an artifact fetched for the thumbnail without a second request) — and drawn at its fitted rect with
 * the zoom and pan applied as a layer transform, so a pinch never re-lays the page out.
 */
@Composable
internal fun ImagePage(
    state: MediaViewerState,
    session: MediaViewerState.Session,
    entry: MediaEntry,
    page: Int,
    presentation: PagePresentation,
    environment: PageEnvironment,
    viewport: IntSize,
    isCurrent: Boolean,
) {
    val ref = remember(entry.src, session.agentId) { MediaRef.parse(entry.src, session.agentId) }
    val zoom = presentation.zoom
    val density = LocalDensity.current

    LaunchedEffect(ref, viewport, presentation.attempt) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val target = boundedPixels(viewport.width, viewport.height)
        val wake = presentation.waking
        // A decode that already reaches the viewport on either axis is as sharp as the fit can show; a rotation that
        // turns a portrait viewport into a landscape one asks for the wider decode a wide picture then needs.
        val have = presentation.bitmap
        if (presentation.loaded && have != null && (have.width >= target.width || have.height >= target.height)) return@LaunchedEffect
        // Nothing lands on a page while the transform runs: a decode arriving then is a bitmap swap and a re-layout
        // mid-animation — on the page opening, or on a neighbour no one can see yet — for a difference no one sees
        // at that size, the thumbnail's own decode being bounded by the screen's short edge already. The decodes
        // are asked for only once the page has landed, and the pager's neighbours have the swipe's time to arrive.
        // A page opened from a row with no picture of its own (a file's name, a chip) has nothing to swap: its decode
        // lands as soon as it can, and the transform carries it out of the row from that frame on.
        if (presentation.bitmap != null || page != session.initialIndex) snapshotFlow { state.phase }.first { it != MediaViewerState.Phase.Opening }
        if (presentation.bitmap == null && !wake) {
            // A quick small decode to stand in until the full one lands; its failure is the full one's to report.
            try {
                presentation.offer(environment.loader.image(ref, target.width / 3, target.height / 3).asImageBitmap())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
        try {
            presentation.offer(environment.loader.image(ref, target.width, target.height, wake = wake).asImageBitmap())
            presentation.loaded = true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (presentation.bitmap == null) presentation.problem = MediaLoader.problemOf(t)
        } finally {
            presentation.waking = false
        }
    }
    // The page on screen reports its zoom to the viewer's state, where the chrome and the tests read it. A page
    // left behind rests again — zoom resets on every page change — but only once it is out of view: it stops being
    // the current page as soon as the next one is past halfway, while it is still sliding out, and a picture that
    // snapped to the fit there would be seen doing it.
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            snapshotFlow { Triple(zoom.scale, zoom.pan, presentation.imageSize) }.collect { (scale, pan, decoded) ->
                state.zoomScale = scale
                state.zoomPan = pan
                state.currentDecodeSize = decoded
            }
        } else {
            snapshotFlow { environment.isVisible(page) }.first { !it }
            zoom.reset()
        }
    }
    val enabled = rememberUpdatedState(isCurrent && state.phase == MediaViewerState.Phase.Open)

    val bitmap = presentation.bitmap
    val gestures = Modifier.viewerGestures(
        zoom = zoom,
        dismiss = environment.dismiss,
        handover = environment.handover,
        scope = environment.scope,
        enabled = enabled,
        onTap = environment.onTap,
        onDismiss = environment.onDismiss,
    )
    // Clipped to the page: a zoomed picture hangs past its page on both sides, and the page pulled in beside it
    // would otherwise be drawn under (or over) that overhang.
    Box(Modifier.fillMaxSize().clipToBounds().then(gestures).testTag("viewer-page-$page"), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> {
                val fitted = ViewerGeometry.fitted(viewport.toSize(), bitmap.width, bitmap.height)
                LaunchedEffect(fitted) {
                    presentation.fitted = fitted
                    zoom.onLayout(viewport.toSize(), fitted.size)
                    if (isCurrent) environment.dismiss.viewport = viewport.toSize()
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(with(density) { fitted.width.toDp() }, with(density) { fitted.height.toDp() })
                        .pageTransform(state, page, zoom, environment.dismiss, isCurrent)
                        .testTag("viewer-image-$page"),
                )
            }
            presentation.problem != null -> PageProblem(presentation.problem!!, ref, entry, environment, onRetry = presentation::retry, onCopyIntoWorkspace = session.copyInto(state))
            else -> PageSpinner(waking = presentation.waking)
        }
    }
}

/**
 * The layer transform of a page's picture: the zoom about the centre and the pan, the dismiss drag's displacement
 * and shrink while this is the page on screen, and nothing at all — alpha 0 — while the transform layer is drawing
 * the same picture on its way in or out. All of it read here, in the layer block, so a pinch or a drag costs a
 * layer update and no recomposition.
 */
private fun Modifier.pageTransform(state: MediaViewerState, page: Int, zoom: ZoomState?, dismiss: DismissState, isCurrent: Boolean): Modifier = graphicsLayer {
    val standingIn = page == state.currentIndex && state.phase != MediaViewerState.Phase.Open
    alpha = if (standingIn) 0f else 1f
    val dragScale = if (isCurrent) dismiss.scale else 1f
    val scale = (zoom?.scale ?: 1f) * dragScale
    scaleX = scale
    scaleY = scale
    val translation = (zoom?.pan ?: Offset.Zero) + (if (isCurrent) dismiss.drag else Offset.Zero)
    translationX = translation.x
    translationY = translation.y
}

/**
 * A recording: its poster (the thumbnail's frame, or one probed from the file) until the player has drawn its
 * first frame on the texture underneath, then the video at the same fitted rect. The player exists only while
 * this is the page on screen — a swipe away releases it, a swipe back prepares it again — and pauses whenever
 * the screen does. Sound follows the viewer's mute, kept across pages.
 */
@Composable
internal fun VideoPage(
    state: MediaViewerState,
    session: MediaViewerState.Session,
    entry: MediaEntry,
    page: Int,
    presentation: PagePresentation,
    environment: PageEnvironment,
    viewport: IntSize,
    isCurrent: Boolean,
) {
    val density = LocalDensity.current
    val ref = remember(entry.src, session.agentId) { MediaRef.parse(entry.src, session.agentId) }
    LaunchedEffect(ref, viewport) {
        if (presentation.bitmap == null && viewport.width > 0) {
            val maxPx = maxOf(viewport.width, viewport.height)
            runCatching { environment.loader.videoPoster(ref, maxPx) }.getOrNull()?.frame?.let { presentation.offer(it.asImageBitmap()) }
        }
    }
    val source = rememberPagePlayback(state, session, ref, page, presentation, environment, isCurrent)
    val url = source.url
    val urlProblem = source.problem
    val playback = presentation.playback

    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            state.zoomScale = 1f
            state.zoomPan = Offset.Zero
            snapshotFlow { presentation.imageSize }.collect { state.currentDecodeSize = it }
        }
    }
    val enabled = rememberUpdatedState(isCurrent && state.phase == MediaViewerState.Phase.Open)
    val poster = presentation.bitmap
    // The frame the video is drawn in: the video's own size once the decoder reports it, the poster's until then.
    val videoSize = playback?.videoSize?.takeIf { it.width > 0 } ?: presentation.imageSize.takeIf { it.width > 0 } ?: IntSize(16, 9)
    val fitted = ViewerGeometry.fitted(viewport.toSize(), videoSize.width, videoSize.height)
    LaunchedEffect(fitted) {
        presentation.fitted = fitted
        if (isCurrent) environment.dismiss.viewport = viewport.toSize()
    }
    val gestures = Modifier.viewerGestures(
        zoom = null,
        dismiss = environment.dismiss,
        handover = null,
        scope = environment.scope,
        enabled = enabled,
        onTap = environment.onTap,
        onDismiss = environment.onDismiss,
    )
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .then(gestures)
            .semantics { contentDescription = "Video: ${entry.title}" }
            .testTag("viewer-page-$page"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(with(density) { fitted.width.toDp() }, with(density) { fitted.height.toDp() })
                .pageTransform(state, page, zoom = null, dismiss = environment.dismiss, isCurrent = isCurrent),
        ) {
            if (playback != null) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).apply { isOpaque = false } },
                    update = { view -> playback.player.setVideoTextureView(view) },
                    onRelease = { view -> playback.player.clearVideoTextureView(view) },
                    modifier = Modifier.fillMaxSize().testTag("viewer-video-surface"),
                )
            }
            if (poster != null && playback?.firstFrameRendered != true) {
                Image(poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("viewer-video-poster"))
            }
            val problem = playback?.error?.let { MediaProblem.Failed(it, retryable = false) } ?: urlProblem
            when {
                problem != null -> PageProblem(problem, ref, entry, environment, Modifier.align(Alignment.Center), onRetry = source::retry.takeIf { urlProblem != null && playback?.error == null }, onCopyIntoWorkspace = session.copyInto(state))
                playback == null && url == null -> PageSpinner(waking = source.waking, modifier = Modifier.align(Alignment.Center))
                playback != null && !playback.isPlaying && !playback.isBuffering && state.phase == MediaViewerState.Phase.Open -> {
                    // Paused, not started, or ended: the play disc in the middle, the poster card's own control scaled up.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .pressable({ playback.togglePlay() }, CircleShape)
                            .semantics { contentDescription = if (playback.isEnded) "Replay video" else "Play video" }
                            .testTag("viewer-play"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playback.isEnded) CursorIcons.Refresh else CursorIcons.Play, null, tint = Color.White, modifier = Modifier.size(30.dp).padding(start = if (playback.isEnded) 0.dp else 3.dp))
                    }
                }
                playback?.isBuffering == true -> SpinnerRing(size = 24.dp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Where a playable page's source stands: the URL once resolved, or why it cannot be had. */
@Stable
internal class PageSource {
    var url by mutableStateOf<String?>(null)
    var problem by mutableStateOf<MediaProblem?>(null)
    var attempt by mutableIntStateOf(0)
        private set
    var waking by mutableStateOf(false)

    /** Resolves the source again, first waking the agent's machine when [wake]. */
    fun retry(wake: Boolean) {
        problem = null
        waking = wake
        attempt++
    }
}

/**
 * The player of a recording's or a sound's page: its URL resolved once (never an expired one), a player that exists
 * only while this is the page on screen — a swipe away releases it, a swipe back prepares it again — started on
 * the page a tap opened, pausing whenever the screen does, sampled once a frame while it plays, and following the
 * viewer's mute. What it plays is on [PagePresentation.playback]; the source's state is returned.
 */
@Composable
internal fun rememberPagePlayback(
    state: MediaViewerState,
    session: MediaViewerState.Session,
    ref: MediaRef,
    page: Int,
    presentation: PagePresentation,
    environment: PageEnvironment,
    isCurrent: Boolean,
): PageSource {
    val context = LocalContext.current
    val playerFactory = LocalVideoPlayerFactory.current
    val source = remember(ref) { PageSource() }
    LaunchedEffect(ref, source.attempt) {
        try {
            source.url = environment.loader.playbackUrl(ref, wake = source.waking)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            source.problem = MediaLoader.problemOf(t)
        } finally {
            source.waking = false
        }
    }
    val autoplay = session.autoplay && page == session.initialIndex
    DisposableEffect(source.url, isCurrent) {
        val playbackUrl = source.url
        if (playbackUrl == null || !isCurrent) return@DisposableEffect onDispose { }
        val playback = VideoPlayback(playerFactory(context))
        playback.load(playbackUrl, playWhenReady = autoplay && state.phase != MediaViewerState.Phase.Closing, muted = state.muted)
        presentation.playback = playback
        onDispose {
            presentation.playback = null
            playback.release()
        }
    }
    val playback = presentation.playback
    LaunchedEffect(playback, state.muted) { playback?.setMuted(state.muted) }
    LaunchedEffect(playback, playback?.isPlaying) {
        val live = playback ?: return@LaunchedEffect
        // An infinite-animation frame wait: it runs while the file plays, and a test's clock does not wait on it.
        while (live.isPlaying) {
            live.tick()
            withInfiniteAnimationFrameMillis { }
        }
    }
    LifecycleResumeEffect(playback) {
        onPauseOrDispose { playback?.pause() }
    }
    return source
}

/**
 * A sound: its card — the same picture the transform carries out of the row or chip it was opened from, so the
 * sound opens like a figure does — with the play disc over it while it is paused, and the controls (play, the
 * scrubber, elapsed and total, speed, mute) in the chrome under it. Dismissed like any page: a drag down, back,
 * the X. No background playback: leaving the page or the screen pauses it.
 */
@Composable
internal fun AudioPage(
    state: MediaViewerState,
    session: MediaViewerState.Session,
    entry: MediaEntry,
    page: Int,
    presentation: PagePresentation,
    environment: PageEnvironment,
    viewport: IntSize,
    isCurrent: Boolean,
) {
    val density = LocalDensity.current
    val ref = remember(entry.src, session.agentId) { MediaRef.parse(entry.src, session.agentId) }
    val maxCardPx = with(density) { AudioCardMax.roundToPx() }
    val cardPx = (minOf(viewport.width, viewport.height) * AudioCardShare).toInt().coerceIn(1, maxCardPx.coerceAtLeast(1))
    val artwork = rememberAudioArtwork(cardPx)
    // Offered before the frame draws: the open transform carries this picture from its first frame.
    SideEffect { presentation.offer(artwork) }
    val source = rememberPagePlayback(state, session, ref, page, presentation, environment, isCurrent)
    val playback = presentation.playback
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            state.zoomScale = 1f
            state.zoomPan = Offset.Zero
            snapshotFlow { presentation.imageSize }.collect { state.currentDecodeSize = it }
        }
    }
    val fitted = ViewerGeometry.fitted(viewport.toSize(), artwork.width, artwork.height).let { box ->
        // No bigger than the card itself: a sound is a card, not a picture to enlarge to the screen.
        ViewerGeometry.rectAround(box.center, minOf(box.width, artwork.width.toFloat()), minOf(box.height, artwork.height.toFloat()))
    }
    LaunchedEffect(fitted) {
        presentation.fitted = fitted
        if (isCurrent) environment.dismiss.viewport = viewport.toSize()
    }
    val enabled = rememberUpdatedState(isCurrent && state.phase == MediaViewerState.Phase.Open)
    val gestures = Modifier.viewerGestures(
        zoom = null,
        dismiss = environment.dismiss,
        handover = null,
        scope = environment.scope,
        enabled = enabled,
        onTap = environment.onTap,
        onDismiss = environment.onDismiss,
    )
    val problem = playback?.error?.let { MediaProblem.Failed(it, retryable = false) } ?: source.problem
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .then(gestures)
            .semantics { contentDescription = "Audio: ${entry.title}" }
            .testTag("viewer-page-$page"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(with(density) { fitted.width.toDp() }, with(density) { fitted.height.toDp() })
                .pageTransform(state, page, zoom = null, dismiss = environment.dismiss, isCurrent = isCurrent),
            contentAlignment = Alignment.Center,
        ) {
            Image(artwork, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("viewer-audio-card"))
            // Under the note: the disc while paused, a ring while it loads.
            val under = Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { (fitted.height * 0.1f).toDp() })
            when {
                problem != null -> Unit
                playback == null -> SpinnerRing(size = 20.dp, color = Color.White.copy(alpha = 0.7f), modifier = under.padding(bottom = 22.dp))
                playback.isBuffering -> SpinnerRing(size = 24.dp, color = Color.White.copy(alpha = 0.8f), modifier = under.padding(bottom = 20.dp))
                !playback.isPlaying && state.phase == MediaViewerState.Phase.Open -> PlayDisc(playback, under)
            }
        }
        if (problem != null) {
            PageProblem(problem, ref, entry, environment, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp), onRetry = source::retry.takeIf { source.problem != null && playback?.error == null }, onCopyIntoWorkspace = session.copyInto(state))
        }
    }
}

/** The play disc in the middle of a paused page: the poster card's own control, scaled up. */
@Composable
private fun PlayDisc(playback: VideoPlayback, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(64.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .pressable({ playback.togglePlay() }, CircleShape)
            .semantics { contentDescription = if (playback.isEnded) "Replay" else "Play" }
            .testTag("viewer-play"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playback.isEnded) CursorIcons.Refresh else CursorIcons.Play, null, tint = Color.White, modifier = Modifier.size(30.dp).padding(start = if (playback.isEnded) 0.dp else 3.dp))
    }
}

/**
 * The picture a sound stands as: a square card on the elevated grey, a faint disc and the note glyph in the middle,
 * drawn once per size. The same pixels are the page and the transform's, so the card that lands is the one that flew.
 */
@Composable
private fun rememberAudioArtwork(sizePx: Int): ImageBitmap {
    val density = LocalDensity.current
    val glyph = rememberVectorPainter(CursorIcons.Music)
    return remember(sizePx, glyph, density) {
        val side = sizePx.coerceAtLeast(1)
        val bitmap = ImageBitmap(side, side)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
            drawRoundRect(AudioCardFill, cornerRadius = CornerRadius(side * 0.08f))
            val glyphCenter = androidx.compose.ui.geometry.Offset(side / 2f, side * AudioGlyphAt)
            drawCircle(Color.White.copy(alpha = 0.05f), radius = side * 0.22f, center = glyphCenter)
            val glyphSize = side * 0.22f
            translate(left = glyphCenter.x - glyphSize / 2f, top = glyphCenter.y - glyphSize / 2f) {
                with(glyph) { draw(Size(glyphSize, glyphSize), alpha = 0.9f, colorFilter = ColorFilter.tint(Color.White)) }
            }
        }
        bitmap
    }
}

/**
 * Why a page has nothing to show — in the reader's words, never a decoder's — and what can still be done with the
 * file: the browser, where there is a page for it, or another app on the device.
 */
/**
 * Why a page has nothing to show, as the app's compact notice says anything that went wrong (the transcript's
 * record notice, the queue's cards): the reason as its title, what it means under it, the request that was refused
 * and what came back (`Asked: POST /…/ReadBinaryFile → HTTP 404 not_found`) so the next screenshot names the call,
 * and the ways on — waking the agent's machine, Retry, the browser, another app. It used to be a bare sentence on
 * black with no way on (Bennett's 2026-09-22 frame: "Cursor changed a private endpoint…" and nothing else).
 */
@Composable
internal fun PageProblem(
    problem: MediaProblem,
    ref: MediaRef,
    entry: MediaEntry,
    environment: PageEnvironment,
    modifier: Modifier = Modifier,
    onRetry: ((wake: Boolean) -> Unit)? = null,
    onCopyIntoWorkspace: ((path: String) -> Unit)? = null,
) {
    val browserUrl by produceState<String?>(null, ref) { value = environment.loader.browserUrl(ref) }
    val elsewhere = problem.openable && problem !is MediaProblem.NotReadable && problem !is MediaProblem.Failed &&
        !(problem is MediaProblem.NotMedia && problem.actual == FileFormat.HTML) && problem !is MediaProblem.LfsPointer
    val copyPath = (ref as? MediaRef.Workspace)?.path?.takeIf { problem is MediaProblem.OutsideWorkspace && onCopyIntoWorkspace != null }
    val detail = listOfNotNull(problem.detail, problem.asked?.let { "Asked: $it" }).joinToString("\n").ifEmpty { null }
    val tone = when (problem) {
        is MediaProblem.MachineAsleep, is MediaProblem.NotReadable, is MediaProblem.Unsupported, is MediaProblem.LfsPointer -> NoticeTone.Warning
        else -> NoticeTone.Error
    }
    Box(modifier.padding(horizontal = 20.dp).widthIn(max = 440.dp).testTag("viewer-error")) {
        LoadNoticeCard(title = problem.title, detail = detail, tone = tone, docked = false, titleTag = "viewer-error-title") {
            if (onRetry != null && problem.wakeable) NoticeAction("Wake the machine", { onRetry(true) }, Modifier.testTag("viewer-wake"))
            if (onRetry != null && problem.retryable) NoticeAction("Retry", { onRetry(false) }, Modifier.testTag("viewer-retry"))
            if (copyPath != null) NoticeAction("Ask the agent to copy it into the workspace", { onCopyIntoWorkspace?.invoke(copyPath) }, Modifier.testTag("viewer-ask-copy"))
            browserUrl?.let { url -> NoticeAction("Open in browser", { environment.onOpenInBrowser(url) }, Modifier.testTag("viewer-open-browser")) }
            if (elsewhere) NoticeAction("Open with\u2026", { environment.onOpenElsewhere(ref, entry) }, Modifier.testTag("viewer-open-elsewhere"))
        }
    }
}

/** A page's wait: the ring, and while the agent's machine is being woken, the words for why it is a long one. */
@Composable
private fun PageSpinner(waking: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SpinnerRing(size = 20.dp, color = Color.White.copy(alpha = 0.7f))
        if (waking) Text("Waking the agent's machine\u2026", style = CursorTheme.typography.small, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp).testTag("viewer-waking"))
    }
}

/** Where the note sits on the card, as a share of its height: above the middle, the play disc below it. */
private const val AudioGlyphAt = 0.4f
/** A sound's card: this share of the viewport's short edge, and never more than [AudioCardMax]. */
private const val AudioCardShare = 0.62f
private val AudioCardMax = 320.dp
private val AudioCardFill = Color(0xFF262626)
