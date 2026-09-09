package com.cursorforandroid.ui.components

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cursorforandroid.R
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/**
 * What [MarkdownText] needs to turn a media `src` into pixels: the agent whose artifacts the paths refer to and the
 * loader that fetches them. Provided by the conversation screen; absent elsewhere.
 */
class MarkdownMediaContext(val agentId: String?, val loader: MediaLoader)

val LocalMarkdownMedia = staticCompositionLocalOf<MarkdownMediaContext?> { null }

/** Figures never grow past this (or 45 % of the screen on short displays); taller media is fitted and opens full size on tap. */
private val MediaMaxHeightCap = 420.dp
private val PlaceholderHeight = 140.dp
private val FallbackWidth = 360.dp
private const val VIDEO_DEFAULT_ASPECT = 16f / 9f

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
    data class Failed(val title: String, val retryable: Boolean) : ImageLoad
}

@Composable
private fun mediaMaxHeight(): Dp = minOf(MediaMaxHeightCap, (LocalConfiguration.current.screenHeightDp * 0.45f).dp)

/**
 * An image from a reply. Laid out the way a browser lays out `<img>`: one source pixel per dp, shrunk to fit the
 * message width and [mediaMaxHeight]. Tapping opens a zoomable full-screen view.
 */
@Composable
fun ImageBlock(src: String, alt: String?, modifier: Modifier = Modifier) {
    val media = LocalMarkdownMedia.current
    val ref = remember(src, media?.agentId) { MediaRef.parse(src, media?.agentId) }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = if (maxWidth.isFinite) maxWidth else FallbackWidth
        val maxHeight = mediaMaxHeight()
        val request = inlineDecodeBounds()
        var attempt by remember(ref) { mutableIntStateOf(0) }
        var state by remember(ref) { mutableStateOf<ImageLoad>(ImageLoad.Loading) }
        var lightbox by rememberSaveable(ref.cacheKey) { mutableStateOf(false) }

        // Keyed on the artifact, not on the measured width: the column is re-measured whenever the device is
        // rotated (configChanges absorbs it) or the sidebar appears, and that must not throw the decoded bitmap
        // away and blank the figure back to a placeholder. The decode is sized for the device instead, and the
        // layout fits it to whatever width it ends up with.
        LaunchedEffect(ref, attempt) {
            if (media == null || ref is MediaRef.Unavailable) {
                state = ImageLoad.Failed("Image isn't available", retryable = false)
                return@LaunchedEffect
            }
            state = ImageLoad.Loading
            state = try {
                ImageLoad.Ready(media.loader.image(ref, request.width, request.height).asImageBitmap())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                ImageLoad.Failed("Couldn't load image", retryable = true)
            }
        }

        val ready = (state as? ImageLoad.Ready)?.bitmap
        when (val s = state) {
            ImageLoad.Loading -> MediaPlaceholder(Modifier.fillMaxWidth().height(PlaceholderHeight), "Loading image")
            is ImageLoad.Failed -> MediaErrorRow(
                icon = CursorIcons.Image,
                title = s.title,
                detail = alt ?: ref.label,
                onRetry = if (s.retryable) ({ attempt++ }) else null,
            )
            is ImageLoad.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = alt ?: "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(fitted(s.bitmap.width, s.bitmap.height, maxWidth, maxHeight))
                    .cursorSurface(Color.Transparent, colors.strokeSubtle, shape)
                    .pressable({ lightbox = true }, shape, role = Role.Image),
            )
        }
        // Outside the load state: the viewer carries its own bitmap, and one that is open must not be torn down
        // because the figure behind it went back to loading.
        if (lightbox && media != null) {
            ImageLightbox(ref, ready, alt, media.loader, onDismiss = { lightbox = false })
        }
    }
}

/**
 * How large an inline figure is decoded. Bounded by the device's short edge rather than by the measured column:
 * that is the widest a message column ever is in portrait, it does not change when the display is re-measured, and
 * a figure is never laid out taller than [MediaMaxHeightCap] anyway.
 */
@Composable
private fun inlineDecodeBounds(): IntSize {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val edge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    return with(density) { boundedPixels(edge.roundToPx(), MediaMaxHeightCap.roundToPx()) }
}

/** A `<video>` from a reply: poster frame with a play button; tapping swaps in an inline player. */
@Composable
fun VideoBlock(src: String, poster: String?, modifier: Modifier = Modifier) {
    val media = LocalMarkdownMedia.current
    val loader = media?.loader
    val ref = remember(src, media?.agentId) { MediaRef.parse(src, media?.agentId) }
    val posterRef = remember(poster, media?.agentId) { poster?.let { MediaRef.parse(it, media?.agentId) } }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg

    if (loader == null || ref is MediaRef.Unavailable || ref is MediaRef.Inline) {
        MediaErrorRow(icon = CursorIcons.Video, title = "Video isn't available", detail = ref.label, onRetry = null, modifier = modifier)
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = if (maxWidth.isFinite) maxWidth else FallbackWidth
        val maxHeight = mediaMaxHeight()
        val density = LocalDensity.current
        val maxPx = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }
        var frame by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
        var durationMs by remember(ref) { mutableStateOf<Long?>(null) }
        /** Width / height reported by the decoder once playback starts; corrects a card sized from a poster or the default. */
        var playbackAspect by remember(ref) { mutableStateOf<Float?>(null) }
        // Plain remember: a video scrolled out of the list stops and comes back as its poster, not auto-playing.
        var playing by remember(ref) { mutableStateOf(false) }

        LaunchedEffect(ref, posterRef, maxPx) {
            val explicitPoster = posterRef?.takeIf { it !is MediaRef.Unavailable }
            if (explicitPoster != null) {
                runCatching { loader.image(explicitPoster, maxPx, maxPx) }.onSuccess { frame = it.asImageBitmap() }
            }
            loader.videoPoster(ref, maxPx)?.let { probe ->
                durationMs = probe.durationMs
                if (frame == null) frame = probe.frame?.asImageBitmap()
            }
        }

        val aspect = playbackAspect ?: frame?.let { it.width.toFloat() / it.height } ?: VIDEO_DEFAULT_ASPECT
        val width = minOf(maxWidth, maxHeight * aspect)
        val cardSize = DpSize(width, width / aspect)
        val label = "Video: ${ref.label}"

        Box(Modifier.size(cardSize).cursorSurface(Color.Black, colors.strokeSubtle, shape)) {
            if (playing) {
                InlineVideoPlayer(ref, loader, onAspect = { playbackAspect = it }, modifier = Modifier.fillMaxSize())
            } else {
                frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = label }
                        .pressable({ playing = true }, RectangleShape),
                )
                PlayButton(Modifier.align(Alignment.Center))
                if (frame == null) {
                    Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CursorIcons.Video, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ref.label, style = CursorTheme.typography.code, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                durationMs?.let { ms ->
                    Text(
                        TimeFormat.clock(ms),
                        style = CursorTheme.typography.tiny.copy(lineHeight = 12.sp),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CursorTheme.shapes.sm)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 40dp white disc with the canvas-coloured play glyph, the composer's prominent-button treatment scaled up. */
@Composable
private fun PlayButton(modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(Color.White.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(CursorIcons.Play, null, tint = Color(0xFF141414), modifier = Modifier.size(22.dp).padding(start = 1.dp))
    }
}

@Composable
private fun MediaPlaceholder(modifier: Modifier, description: String) {
    val colors = CursorTheme.colors
    Box(
        modifier.cursorSurface(colors.fillFaint, colors.strokeSubtle, CursorTheme.shapes.lg).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        SpinnerRing(size = 14.dp)
    }
}

/** Compact card for a figure that cannot be shown: what it was, why, and a retry when one makes sense. */
@Composable
private fun MediaErrorRow(icon: ImageVector, title: String, detail: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    Row(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
            .then(if (onRetry != null) Modifier.pressable(onRetry, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = CursorTheme.typography.base, color = colors.textSecondary)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = CursorTheme.typography.code, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRetry != null) {
            Spacer(Modifier.width(8.dp))
            Text("Retry", style = CursorTheme.typography.small, color = colors.link)
        }
    }
}

/** ARGB_8888 at [MaxDecodePixels] is about 12 MB; no single figure may ask for more than that. */
private const val MaxDecodePixels = 3_000_000L

/** Shrinks a decode request, keeping its proportions, until it fits inside [MaxDecodePixels]. */
internal fun boundedPixels(widthPx: Int, heightPx: Int): IntSize {
    val width = widthPx.coerceAtLeast(1)
    val height = heightPx.coerceAtLeast(1)
    val pixels = width.toLong() * height
    if (pixels <= MaxDecodePixels) return IntSize(width, height)
    val scale = sqrt(MaxDecodePixels.toDouble() / pixels)
    return IntSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}

/**
 * Where a zoomed figure may be dragged to. The image is drawn `Fit` inside [viewport], so only the part of it that
 * hangs off an edge once scaled is pannable; beyond that the drag would pull the picture off the screen and leave
 * the viewer showing nothing but black with no way back other than closing it.
 */
internal fun clampedPan(offset: Offset, scale: Float, imageWidth: Int, imageHeight: Int, viewport: IntSize): Offset {
    if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val fit = minOf(
        viewport.width.toFloat() / imageWidth.coerceAtLeast(1),
        viewport.height.toFloat() / imageHeight.coerceAtLeast(1),
    )
    val maxX = (imageWidth * fit * scale - viewport.width) / 2f
    val maxY = (imageHeight * fit * scale - viewport.height) / 2f
    return Offset(withinOverhang(offset.x, maxX), withinOverhang(offset.y, maxY))
}

private fun withinOverhang(value: Float, max: Float): Float = if (max <= 0f) 0f else value.coerceIn(-max, max)

/** One source pixel per dp, shrunk (never enlarged) to fit [maxWidth] x [maxHeight] while keeping the aspect ratio. */
private fun fitted(widthPx: Int, heightPx: Int, maxWidth: Dp, maxHeight: Dp): DpSize {
    val w = widthPx.coerceAtLeast(1).toFloat()
    val h = heightPx.coerceAtLeast(1).toFloat()
    val scale = minOf(1f, maxWidth.value / w, maxHeight.value / h)
    return DpSize((w * scale).dp, (h * scale).dp)
}

/** Full-screen viewer: pinch to zoom, drag to pan, double-tap to toggle 2.5x, close button or back to leave. */
@Composable
private fun ImageLightbox(ref: MediaRef, initial: ImageBitmap?, alt: String?, loader: MediaLoader, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var bitmap by remember(ref) { mutableStateOf(initial) }
    LaunchedEffect(initial) { if (bitmap == null) bitmap = initial }
    // The inline copy was decoded for the message width; fetch one sized for the screen (Coil serves it from cache
    // when the inline decode already was full resolution). Bounded by the screen rather than by twice its pixels:
    // the old request was 2x the screen in each direction, four times its area, and a screenshot close to that
    // shape decoded to tens of megabytes of software bitmap with nothing between it and OutOfMemoryError.
    LaunchedEffect(ref) {
        val target = with(density) {
            boundedPixels(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx())
        }
        runCatching { loader.image(ref, target.width, target.height) }
            .onSuccess { if (it.width >= (bitmap?.width ?: 0)) bitmap = it.asImageBitmap() }
    }
    var scale by rememberSaveable(ref.cacheKey) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(ref.cacheKey) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(ref.cacheKey) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        val shown = bitmap
        val panned = if (scale == 1f || shown == null) {
            Offset.Zero
        } else {
            clampedPan(Offset(offsetX + panChange.x, offsetY + panChange.y), scale, shown.width, shown.height, viewport)
        }
        offsetX = panned.x
        offsetY = panned.y
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val shown = bitmap
            if (shown == null) {
                SpinnerRing(size = 20.dp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
            } else {
                Image(
                    bitmap = shown,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .transformable(transform)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offsetX = 0f
                                offsetY = 0f
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
                FlatIconButton(CursorIcons.Close, "Close", onClick = onDismiss, tint = Color.White)
            }
            if (!alt.isNullOrBlank()) {
                Text(
                    alt,
                    style = CursorTheme.typography.small,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * ExoPlayer inside the poster card. The player is created when a URL is known and released when the block leaves
 * the composition; it pauses whenever the screen does.
 */
@Composable
private fun InlineVideoPlayer(ref: MediaRef, loader: MediaLoader, onAspect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var url by remember(ref) { mutableStateOf<String?>(null) }
    var error by remember(ref) { mutableStateOf<String?>(null) }
    var player by remember(ref) { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(ref) {
        try {
            url = loader.playbackUrl(ref)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            error = t.userMessage()
        }
    }

    DisposableEffect(url) {
        val playbackUrl = url ?: return@DisposableEffect onDispose { }
        // Audio focus so a recording ducks the user's music instead of playing over it and pauses when another app
        // takes over, and becoming-noisy so unplugging headphones stops it rather than putting it on the speaker.
        // The lifecycle pause below covers leaving the screen, not an interruption while it is still in front.
        val exo = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            setMediaItem(MediaItem.fromUri(playbackUrl))
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = "Couldn't play this video."
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
        player = exo
        onDispose {
            player = null
            exo.release()
        }
    }
    LifecycleResumeEffect(player) {
        onPauseOrDispose { player?.pause() }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = player
        when {
            error != null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                Icon(CursorIcons.Warning, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(error!!, style = CursorTheme.typography.base, color = Color.White.copy(alpha = 0.8f))
            }
            current == null -> SpinnerRing(size = 16.dp, color = Color.White.copy(alpha = 0.7f))
            else -> AndroidView(
                factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.view_video_player, FrameLayout(ctx), false) as PlayerView },
                update = { view -> view.player = current },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize().clip(RectangleShape),
            )
        }
    }
}
