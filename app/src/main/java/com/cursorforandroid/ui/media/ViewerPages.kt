package com.cursorforandroid.ui.media

import android.view.TextureView
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.boundedPixels
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

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
    var error by mutableStateOf<String?>(null)
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

/** Everything a page shares with the viewer: the loader, the dismiss drag, and what a tap or a dismissing drag does. */
internal class PageEnvironment(
    val loader: MediaLoader,
    val dismiss: DismissState,
    val scope: CoroutineScope,
    val onTap: () -> Unit,
    val onDismiss: (velocityY: Float) -> Unit,
)

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

    LaunchedEffect(ref, viewport) {
        if (viewport.width <= 0 || viewport.height <= 0 || presentation.loaded) return@LaunchedEffect
        val target = boundedPixels(viewport.width, viewport.height)
        if (presentation.bitmap == null) {
            runCatching { environment.loader.image(ref, target.width / 3, target.height / 3) }.onSuccess { presentation.offer(it.asImageBitmap()) }
        }
        try {
            presentation.offer(environment.loader.image(ref, target.width, target.height).asImageBitmap())
            presentation.loaded = true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (presentation.bitmap == null) presentation.error = t.userMessage().takeIf { it.isNotBlank() } ?: "Couldn't load this image."
        }
    }
    // The page that scrolled out of view rests again: zoom resets on every page change. The page on screen reports
    // its zoom to the viewer's state, where the chrome and the tests read it.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            zoom.reset()
            return@LaunchedEffect
        }
        snapshotFlow { zoom.scale to zoom.pan }.collect { (scale, pan) ->
            state.zoomScale = scale
            state.zoomPan = pan
        }
    }

    val bitmap = presentation.bitmap
    val gestures = Modifier.viewerGestures(
        zoom = zoom,
        dismiss = environment.dismiss,
        scope = environment.scope,
        enabled = isCurrent && state.phase == MediaViewerState.Phase.Open,
        onTap = environment.onTap,
        onDismiss = environment.onDismiss,
    )
    Box(Modifier.fillMaxSize().then(gestures).testTag("viewer-page-$page"), contentAlignment = Alignment.Center) {
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
            presentation.error != null -> Text(
                presentation.error!!,
                style = CursorTheme.typography.base,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp).testTag("viewer-error"),
            )
            else -> SpinnerRing(size = 20.dp, color = Color.White.copy(alpha = 0.7f))
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val ref = remember(entry.src, session.agentId) { MediaRef.parse(entry.src, session.agentId) }
    val playerFactory = LocalVideoPlayerFactory.current
    var url by remember(ref) { mutableStateOf<String?>(null) }
    var urlError by remember(ref) { mutableStateOf<String?>(null) }

    LaunchedEffect(ref, viewport) {
        if (presentation.bitmap == null && viewport.width > 0) {
            val maxPx = maxOf(viewport.width, viewport.height)
            runCatching { environment.loader.videoPoster(ref, maxPx) }.getOrNull()?.frame?.let { presentation.offer(it.asImageBitmap()) }
        }
    }
    LaunchedEffect(ref) {
        try {
            url = environment.loader.playbackUrl(ref)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            urlError = t.userMessage().takeIf { it.isNotBlank() } ?: "This video isn't available."
        }
    }
    // The player is this page's while it is the one on screen; the first page of a tapped recording starts playing.
    val autoplay = session.autoplay && page == session.initialIndex
    DisposableEffect(url, isCurrent) {
        val playbackUrl = url
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
        // An infinite-animation frame wait: it runs while the recording plays, and a test's clock does not wait on it.
        while (live.isPlaying) {
            live.tick()
            withInfiniteAnimationFrameMillis { }
        }
    }
    LifecycleResumeEffect(playback) {
        onPauseOrDispose { playback?.pause() }
    }

    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            state.zoomScale = 1f
            state.zoomPan = Offset.Zero
        }
    }
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
        scope = environment.scope,
        enabled = isCurrent && state.phase == MediaViewerState.Phase.Open,
        onTap = environment.onTap,
        onDismiss = environment.onDismiss,
    )
    Box(
        Modifier
            .fillMaxSize()
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
            val message = playback?.error ?: urlError
            when {
                message != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(CursorIcons.Warning, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                    Text(message, style = CursorTheme.typography.base, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).testTag("viewer-error"))
                }
                playback == null && url == null -> SpinnerRing(size = 20.dp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
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
