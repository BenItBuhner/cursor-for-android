package com.cursorforandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.media.MediaProblem
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaActions
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.rememberThumbnailSlot
import com.cursorforandroid.ui.media.thumbnailSlot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * What [MarkdownText] needs to turn a media `src` into pixels: the agent whose artifacts the paths refer to, the
 * loader that fetches them, and the conversation's media in order ([entries], asked for at the tap so that a
 * streaming transcript is not re-read on every token) so that a tapped figure opens the viewer among its neighbours.
 * Provided by the conversation screen; absent elsewhere.
 *
 * [canReadStores] says the account's store reads are on (Extended mode), so a `/cursor/stores/…` figure is fetched
 * rather than stood in for; [onOpenStorePath] is where a tapped store link (or a store figure that cannot be shown)
 * goes — the document sheet, or the Project on cursor.com when nothing here can read it. [onBeforeOpen] runs as a
 * figure opens the viewer: a sheet showing the figure puts itself away with it, since the viewer is a layer of the
 * window and a sheet is a window of its own over it.
 */
class MarkdownMediaContext(
    val agentId: String?,
    val loader: MediaLoader,
    val canReadStores: Boolean = false,
    val onOpenStorePath: ((StorePath) -> Unit)? = null,
    val entries: () -> List<MediaEntry> = { emptyList() },
    val onBeforeOpen: (() -> Unit)? = null,
) {
    /** The same context for a surface that has to step aside as the viewer opens. */
    fun withBeforeOpen(action: () -> Unit) = MarkdownMediaContext(agentId, loader, canReadStores, onOpenStorePath, entries, action)
}

val LocalMarkdownMedia = staticCompositionLocalOf<MarkdownMediaContext?> { null }

/** Figures never grow past this (or 45 % of the screen on short displays); taller media is fitted and opens full size on tap. */
private val MediaMaxHeightCap = 420.dp
private val PlaceholderHeight = 140.dp
private val FallbackWidth = 360.dp
private const val VIDEO_DEFAULT_ASPECT = 16f / 9f

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
    data class Failed(val problem: MediaProblem) : ImageLoad
}

@Composable
private fun mediaMaxHeight(): Dp = minOf(MediaMaxHeightCap, (LocalConfiguration.current.screenHeightDp * 0.45f).dp)

/**
 * An image from a reply. Laid out the way a browser lays out `<img>`: one source pixel per dp, shrunk to fit the
 * message width and [mediaMaxHeight]. Tapping opens the media viewer, which grows out of this very figure.
 */
@Composable
fun ImageBlock(src: String, alt: String?, modifier: Modifier = Modifier, heightCap: Dp? = null) {
    val media = LocalMarkdownMedia.current
    val viewer = LocalMediaViewer.current
    val ref = remember(src, media?.agentId) { MediaRef.parse(src, media?.agentId) }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg

    // A store figure nothing here can read (default mode, no account) is a card pointing at the Project on cursor.com,
    // not a dead "isn't available"; Cursor's own client resolves the same path against the Project's context.
    if (ref is MediaRef.Store && (media == null || !media.canReadStores)) {
        StoreFileCard(ref, alt, CursorIcons.Image, modifier)
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = if (maxWidth.isFinite) maxWidth else FallbackWidth
        // A tile in a gallery is held to the height its caller gives it; a figure in a reply to the screen's share.
        val maxHeight = heightCap ?: mediaMaxHeight()
        val request = inlineDecodeBounds()
        var attempt by remember(ref) { mutableIntStateOf(0) }
        var wake by remember(ref) { mutableStateOf(false) }
        var state by remember(ref) { mutableStateOf<ImageLoad>(ImageLoad.Loading) }

        // Keyed on the artifact, not on the measured width: the column is re-measured whenever the device is
        // rotated (configChanges absorbs it) or the sidebar appears, and that must not throw the decoded bitmap
        // away and blank the figure back to a placeholder. The decode is sized for the device instead, and the
        // layout fits it to whatever width it ends up with.
        LaunchedEffect(ref, attempt) {
            if (media == null || ref is MediaRef.Unavailable) {
                state = ImageLoad.Failed(MediaProblem.NotReadable("Image isn't available", null))
                return@LaunchedEffect
            }
            state = ImageLoad.Loading
            state = try {
                ImageLoad.Ready(media.loader.image(ref, request.width, request.height, wake = wake).asImageBitmap())
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                ImageLoad.Failed(MediaLoader.problemOf(t))
            } finally {
                wake = false
            }
        }

        when (val s = state) {
            ImageLoad.Loading -> MediaPlaceholder(Modifier.fillMaxWidth().height(PlaceholderHeight), if (wake) "Waking the agent's machine" else "Loading image")
            is ImageLoad.Failed -> if (ref is MediaRef.Store) {
                // The store answered nothing for it: the card still opens the Project, and a tap on Retry asks again.
                StoreFileCard(ref, alt, CursorIcons.Image, title = s.problem.title, onRetry = { attempt++ })
            } else {
                MediaProblemRow(
                    icon = CursorIcons.Image,
                    problem = s.problem,
                    ref = ref,
                    entry = MediaEntry(src, MediaEntry.Kind.Image, caption = alt),
                    detail = alt ?: ref.label,
                    onRetry = if (s.problem.retryable) ({ attempt++ }) else null,
                    onWake = if (s.problem.wakeable) ({ wake = true; attempt++ }) else null,
                )
            }
            is ImageLoad.Ready -> {
                val slot = rememberThumbnailSlot(src, shape, crop = false)
                Image(
                    bitmap = s.bitmap,
                    contentDescription = alt ?: "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .thumbnailSlot(slot)
                        .size(fitted(s.bitmap.width, s.bitmap.height, maxWidth, maxHeight))
                        .cursorSurface(Color.Transparent, colors.strokeSubtle, shape)
                        .pressable(
                            {
                                if (viewer != null && media != null) {
                                    media.onBeforeOpen?.invoke()
                                    viewer.open(media.agentId, media.entries(), src, slot, seen = s.bitmap, fallback = MediaEntry(src, MediaEntry.Kind.Image, caption = alt))
                                }
                            },
                            shape,
                            role = Role.Image,
                        ),
                )
            }
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

/**
 * A `<video>` from a reply: poster frame with a play button and the recording's length. Tapping opens the media
 * viewer on it, playing, grown out of this card; the viewer is where the controls are.
 */
@Composable
fun VideoBlock(src: String, poster: String?, modifier: Modifier = Modifier, heightCap: Dp? = null) {
    val media = LocalMarkdownMedia.current
    val viewer = LocalMediaViewer.current
    val ref = remember(src, media?.agentId) { MediaRef.parse(src, media?.agentId) }
    val posterRef = remember(poster, media?.agentId) { poster?.let { MediaRef.parse(it, media?.agentId) } }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg

    val audio = FileFormat.ofName(src)?.isAudio == true
    if (ref is MediaRef.Store && (media == null || !media.canReadStores)) {
        StoreFileCard(ref, null, if (audio) CursorIcons.Music else CursorIcons.Video, modifier)
        return
    }
    if (media == null || ref is MediaRef.Unavailable || ref is MediaRef.Inline) {
        MediaErrorRow(icon = if (audio) CursorIcons.Music else CursorIcons.Video, title = if (audio) "Sound isn't available" else "Video isn't available", detail = ref.label, onRetry = null, modifier = modifier)
        return
    }
    if (audio) {
        AudioChip(src, ref.label, subtitle = FileFormat.ofName(src)?.label ?: "Audio", modifier = modifier)
        return
    }
    val loader = media.loader

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidth = if (maxWidth.isFinite) maxWidth else FallbackWidth
        val maxHeight = heightCap ?: mediaMaxHeight()
        val density = LocalDensity.current
        val maxPx = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }
        var frame by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
        var durationMs by remember(ref) { mutableStateOf<Long?>(null) }

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
        val width = minOf(maxWidth, maxHeight * aspect)
        val cardSize = DpSize(width, width / aspect)
        val label = "Video: ${ref.label}"
        val slot = rememberThumbnailSlot(src, shape, crop = true)
        val open = {
            if (viewer != null) {
                media.onBeforeOpen?.invoke()
                viewer.open(media.agentId, media.entries(), src, slot, seen = frame, fallback = MediaEntry(src, MediaEntry.Kind.Video, durationMs = durationMs), autoplay = true)
            }
        }

        Box(Modifier.thumbnailSlot(slot).size(cardSize).cursorSurface(Color.Black, colors.strokeSubtle, shape)) {
            frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("video-poster")) }
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = label }
                    .pressable(open, RectangleShape),
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

/**
 * A file of a Project's context that cannot be drawn here — without the account's store reads (default mode), or
 * because the store answered nothing for it — as a card naming the file, saying where it is, and opening the
 * Project on cursor.com, where Cursor's own client shows it. Tapping the card is the way there; [onRetry] asks the
 * store again when there is a store to ask.
 */
@Composable
internal fun StoreFileCard(
    ref: MediaRef.Store,
    alt: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val media = LocalMarkdownMedia.current
    val uriHandler = LocalUriHandler.current
    val open: () -> Unit = {
        val handler = media?.onOpenStorePath
        if (handler != null) handler(ref.path) else runCatching { uriHandler.openUri(ref.webUrl) }
    }
    Row(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
            .pressable(open, shape)
            .semantics { contentDescription = "Open ${ref.label} on cursor.com" }
            .testTag("store-file-card")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title ?: (alt?.takeIf { it.isNotBlank() } ?: ref.label), style = CursorTheme.typography.base, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (title != null && !alt.isNullOrBlank()) "$alt · ${ref.path.text}" else ref.path.text,
                style = CursorTheme.typography.code,
                color = colors.textQuaternary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onRetry != null) {
            Text("Retry", style = CursorTheme.typography.small, color = colors.link, modifier = Modifier.pressable(onRetry, CursorTheme.shapes.base).padding(horizontal = 4.dp, vertical = 2.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text("Open on cursor.com", style = CursorTheme.typography.small, color = colors.link)
    }
}

/**
 * A figure that cannot be drawn, named by its [problem] — never a decoder's words — with what can still be done:
 * Retry when asking again could help, the browser when the file has a page there, another app on the device when
 * this one cannot draw the format.
 */
@Composable
internal fun MediaProblemRow(
    icon: ImageVector,
    problem: MediaProblem,
    ref: MediaRef,
    entry: MediaEntry,
    detail: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onWake: (() -> Unit)? = null,
) {
    val media = LocalMarkdownMedia.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val browserUrl by produceState<String?>(null, ref, media) { value = media?.loader?.browserUrl(ref) }
    val elsewhere = media != null && problem.openable && problem !is MediaProblem.NotReadable && problem !is MediaProblem.Failed &&
        problem !is MediaProblem.LfsPointer && !(problem is MediaProblem.NotMedia && problem.actual == FileFormat.HTML)
    var notice by remember(ref) { mutableStateOf<String?>(null) }
    val onCopy = (ref as? MediaRef.Workspace)?.path?.takeIf { problem is MediaProblem.OutsideWorkspace }?.let { path -> LocalTranscriptControls.current.onAskToCopyFile?.let { ask -> { ask(path) } } }
    val actions = buildList {
        onWake?.let { add("Wake the machine" to it) }
        onCopy?.let { add("Ask the agent to copy it into the workspace" to it) }
        browserUrl?.let { url -> add("Open in browser" to { if (runCatching { uriHandler.openUri(url) }.isFailure) notice = "Nothing on this device opens links." }) }
        if (elsewhere && media != null) {
            add(
                "Open with\u2026" to {
                    scope.launch { MediaActions(context, media.loader).openWith(ref, entry).onFailure { notice = MediaLoader.problemOf(it).title } }
                    Unit
                },
            )
        }
    }
    MediaErrorRow(
        icon = icon,
        title = problem.title,
        detail = listOfNotNull(notice ?: problem.detail, detail).joinToString(" · ").ifBlank { null },
        onRetry = onRetry,
        actions = actions,
        asked = problem.asked,
        modifier = modifier.testTag("media-problem"),
    )
}

/**
 * A sound in a reply, a file card or a panel row: its note glyph, its name and what it is, as a chip. Tapping opens
 * the media viewer on it, playing, grown out of this chip — the same transform a figure opens with.
 */
@Composable
internal fun AudioChip(src: String, name: String, subtitle: String?, modifier: Modifier = Modifier, entries: (() -> List<MediaEntry>)? = null) {
    val media = LocalMarkdownMedia.current
    val viewer = LocalMediaViewer.current
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val slot = rememberThumbnailSlot(src, shape, crop = true)
    Row(
        modifier
            .thumbnailSlot(slot)
            .widthIn(max = 360.dp)
            .cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
            .pressable(
                {
                    if (viewer != null) {
                        media?.onBeforeOpen?.invoke()
                        viewer.open(media?.agentId, (entries ?: media?.entries)?.invoke().orEmpty(), src, slot, fallback = MediaEntry(src, MediaEntry.Kind.Audio, fileName = name), autoplay = true)
                    }
                },
                shape,
                role = Role.Button,
            )
            .semantics { contentDescription = "Play $name" }
            .testTag("audio-chip")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(colors.fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(CursorIcons.Play, null, tint = colors.iconSecondary, modifier = Modifier.size(14.dp).padding(start = 1.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = CursorTheme.typography.base, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(CursorIcons.Music, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
    }
}

/** Compact card for a figure that cannot be shown: what it was, why, a retry when one makes sense, and [actions] under it. */
@Composable
private fun MediaErrorRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    /** The request a refusal answered and what came back, under the reason: which call it was, from a screenshot. */
    asked: String? = null,
) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
            .then(if (onRetry != null && actions.isEmpty()) Modifier.pressable(onRetry, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = CursorTheme.typography.base, color = colors.textSecondary)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = CursorTheme.typography.code, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                asked?.let { Text("Asked: $it", style = CursorTheme.typography.code, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("media-problem-asked")) }
            }
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                Text("Retry", style = CursorTheme.typography.small, color = colors.link, modifier = if (actions.isEmpty()) Modifier else Modifier.pressable(onRetry, CursorTheme.shapes.base).padding(4.dp))
            }
        }
        if (actions.isNotEmpty()) {
            Row(Modifier.padding(start = 24.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                actions.forEach { (label, action) ->
                    Text(label, style = CursorTheme.typography.small, color = colors.link, modifier = Modifier.pressable(action, CursorTheme.shapes.base).padding(vertical = 3.dp).testTag("media-action"))
                }
            }
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

/** One source pixel per dp, shrunk (never enlarged) to fit [maxWidth] x [maxHeight] while keeping the aspect ratio. */
private fun fitted(widthPx: Int, heightPx: Int, maxWidth: Dp, maxHeight: Dp): DpSize {
    val w = widthPx.coerceAtLeast(1).toFloat()
    val h = heightPx.coerceAtLeast(1).toFloat()
    val scale = minOf(1f, maxWidth.value / w, maxHeight.value / h)
    return DpSize((w * scale).dp, (h * scale).dp)
}
