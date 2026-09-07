package com.cursorforandroid.ui.components

import android.view.LayoutInflater
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cursorforandroid.R
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.CancellationException

/**
 * What [MarkdownText] needs to turn a media `src` into pixels: the agent whose artifacts the paths refer to and the
 * loader that fetches them. Provided by the conversation screen; absent elsewhere.
 */
class MarkdownMediaContext(val agentId: String?, val loader: MediaLoader)

val LocalMarkdownMedia = staticCompositionLocalOf<MarkdownMediaContext?> { null }

/** Figures never grow past this; anything taller is shown fitted and opens full size on tap. */
private val MediaMaxHeight = 420.dp
private val PlaceholderHeight = 140.dp
private val FallbackWidth = 360.dp
private const val VIDEO_DEFAULT_ASPECT = 16f / 9f

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
    data class Failed(val title: String, val retryable: Boolean) : ImageLoad
}

/**
 * An image from a reply. Laid out the way a browser lays out `<img>`: one source pixel per dp, shrunk to fit the
 * message width and [MediaMaxHeight]. Tapping opens a zoomable full-screen view.
 */
@Composable
fun ImageBlock(src: String, alt: String?, modifier: Modifier = Modifier) {
    val media = LocalMarkdownMedia.current
    val ref = remember(src, media?.agentId) { MediaRef.parse(src, media?.agentId) }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = if (maxWidth.isFinite) maxWidth else FallbackWidth
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val maxHeightPx = with(density) { MediaMaxHeight.roundToPx() }
        var attempt by remember(ref) { mutableIntStateOf(0) }
        var state by remember(ref) { mutableStateOf<ImageLoad>(ImageLoad.Loading) }
        var lightbox by rememberSaveable(ref.cacheKey) { mutableStateOf(false) }

        LaunchedEffect(ref, attempt, maxWidthPx) {
            if (media == null || ref is MediaRef.Unavailable) {
                state = ImageLoad.Failed("Image isn't available", retryable = false)
                return@LaunchedEffect
            }
            state = ImageLoad.Loading
            state = try {
                ImageLoad.Ready(media.loader.image(ref, maxWidthPx, maxHeightPx).asImageBitmap())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                ImageLoad.Failed("Couldn't load image", retryable = true)
            }
        }

        when (val s = state) {
            ImageLoad.Loading -> MediaPlaceholder(Modifier.fillMaxWidth().height(PlaceholderHeight), "Loading image")
            is ImageLoad.Failed -> MediaErrorRow(
                icon = CursorIcons.Image,
                title = s.title,
                detail = alt ?: ref.label,
                onRetry = if (s.retryable) ({ attempt++ }) else null,
            )
            is ImageLoad.Ready -> {
                val size = fitted(s.bitmap.width, s.bitmap.height, maxWidth, MediaMaxHeight)
                Image(
                    bitmap = s.bitmap,
                    contentDescription = alt ?: "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(size)
                        .cursorSurface(Color.Transparent, colors.strokeSubtle, shape)
                        .pressable({ lightbox = true }, shape, role = Role.Image),
                )
                if (lightbox && media != null) {
                    ImageLightbox(ref, s.bitmap, alt, media.loader, onDismiss = { lightbox = false })
                }
            }
        }
    }
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
        val density = LocalDensity.current
        val maxPx = with(density) { maxOf(maxWidth, MediaMaxHeight).roundToPx() }
        var frame by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
        var durationMs by remember(ref) { mutableStateOf<Long?>(null) }
        var playing by rememberSaveable(ref.cacheKey) { mutableStateOf(false) }

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

        val aspect = frame?.let { it.width.toFloat() / it.height } ?: VIDEO_DEFAULT_ASPECT
        val width = minOf(maxWidth, MediaMaxHeight * aspect)
        val cardSize = DpSize(width, width / aspect)
        val label = "Video: ${ref.label}"

        Box(Modifier.size(cardSize).cursorSurface(Color.Black, colors.strokeSubtle, shape)) {
            if (playing) {
                InlineVideoPlayer(ref, loader, Modifier.fillMaxSize())
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

/** One source pixel per dp, shrunk (never enlarged) to fit [maxWidth] x [maxHeight] while keeping the aspect ratio. */
private fun fitted(widthPx: Int, heightPx: Int, maxWidth: Dp, maxHeight: Dp): DpSize {
    val w = widthPx.coerceAtLeast(1).toFloat()
    val h = heightPx.coerceAtLeast(1).toFloat()
    val scale = minOf(1f, maxWidth.value / w, maxHeight.value / h)
    return DpSize((w * scale).dp, (h * scale).dp)
}

/** Full-screen viewer: pinch to zoom, drag to pan, double-tap to toggle 2.5x, close button or back to leave. */
@Composable
private fun ImageLightbox(ref: MediaRef, initial: ImageBitmap, alt: String?, loader: MediaLoader, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var bitmap by remember(ref) { mutableStateOf(initial) }
    // The inline copy was decoded for the message width; fetch one sized for the screen (Coil serves it from cache
    // when the inline decode already was full resolution).
    LaunchedEffect(ref) {
        val targetW = with(density) { (configuration.screenWidthDp * 2).dp.roundToPx() }
        val targetH = with(density) { (configuration.screenHeightDp * 2).dp.roundToPx() }
        runCatching { loader.image(ref, targetW, targetH) }.onSuccess { if (it.width >= bitmap.width) bitmap = it.asImageBitmap() }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transform)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
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
private fun InlineVideoPlayer(ref: MediaRef, loader: MediaLoader, modifier: Modifier = Modifier) {
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
        val exo = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(playbackUrl))
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = "Couldn't play this video."
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
                factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView },
                update = { view -> view.player = current },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize().clip(RectangleShape),
            )
        }
    }
}
