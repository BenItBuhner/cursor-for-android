package com.cursorforandroid.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.network.HttpException
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/** A frame from early in a video plus its length; [frame] is null when the file could not be probed. */
class VideoPoster(val frame: Bitmap?, val durationMs: Long?)

/**
 * Fetches the pixels behind a [MediaRef]. Images go through Coil (downsampling to the requested bounds, memory and
 * disk caches keyed by the artifact rather than its rotating presigned URL); video posters come from
 * [MediaMetadataRetriever], which reads only the ranges it needs instead of downloading the recording.
 */
class MediaLoader(
    private val context: Context,
    okHttp: OkHttpClient,
    private val artifacts: ArtifactRepository,
) {
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(okHttp)) }
        .build()

    private val posters = LruCache<String, VideoPoster>(12)

    /** Decodes [ref] to fit within [maxWidthPx] x [maxHeightPx] (downsampling only, never upscaling). */
    suspend fun image(ref: MediaRef, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        val data = dataFor(ref)
        val first = decode(ref, data, maxWidthPx, maxHeightPx)
        if (first is SuccessResult) return first.image.toBitmap()
        val error = (first as ErrorResult).throwable
        // A presigned URL that expired between resolution and fetch: ask for a fresh one exactly once.
        if (ref is MediaRef.Artifact && error.isStaleUrl()) {
            artifacts.invalidate(ref.agentId, ref.path)
            val retry = decode(ref, dataFor(ref), maxWidthPx, maxHeightPx)
            if (retry is SuccessResult) return retry.image.toBitmap()
            throw (retry as ErrorResult).throwable
        }
        throw error
    }

    /** The URL ExoPlayer should open for a video; resolved at play time so it is never an expired one. */
    suspend fun playbackUrl(ref: MediaRef): String = when (ref) {
        is MediaRef.Remote -> ref.url
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        is MediaRef.Inline -> throw IOException("Embedded videos aren't supported.")
        is MediaRef.Unavailable -> throw IOException("This video isn't available.")
    }

    /** A poster frame and duration for the video at [ref]; null (and remembered as such) when unavailable. */
    suspend fun videoPoster(ref: MediaRef, maxPx: Int): VideoPoster? {
        posters.get(ref.cacheKey)?.let { return it }
        val url = runCatching { playbackUrl(ref) }.getOrElse { return null }
        val poster = probe(url, maxPx) ?: VideoPoster(frame = null, durationMs = null)
        posters.put(ref.cacheKey, poster)
        return poster
    }

    /** Forgets everything fetched for the signed-out account; the disk cache is wiped off the main thread. */
    suspend fun clearCaches() {
        posters.evictAll()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
    }

    private suspend fun dataFor(ref: MediaRef): Any = when (ref) {
        is MediaRef.Remote -> ref.url
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        is MediaRef.Inline -> ref.bytes
        is MediaRef.Unavailable -> throw IOException("This image isn't available.")
    }

    private suspend fun decode(ref: MediaRef, data: Any, maxWidthPx: Int, maxHeightPx: Int) = imageLoader.execute(
        ImageRequest.Builder(context)
            .data(data)
            .size(maxWidthPx.coerceAtLeast(1), maxHeightPx.coerceAtLeast(1))
            .precision(Precision.INEXACT)
            .scale(Scale.FIT)
            .memoryCacheKey(ref.cacheKey)
            .diskCacheKey(ref.cacheKey)
            // Software bitmaps: Compose draws them fine and the same code path runs under Robolectric.
            .allowHardware(false)
            .build(),
    )

    private fun Throwable.isStaleUrl(): Boolean = (this as? HttpException)?.response?.code in STALE_URL_CODES

    /**
     * For an `http(s)` URL the retriever opens its own connection inside the platform media stack — outside every
     * timeout [com.cursorforandroid.data.api.CursorApiFactory] sets — and answers no cancellation of its own, so
     * without a deadline a stalled host pins this thread for good and leaves a permanent spinner in the reply. The
     * timeout arrives as a thread interrupt, which the platform's socket reads can at least see, and a probe that
     * runs out of it is remembered as "no poster" like any other failure rather than retried on every recomposition.
     */
    private suspend fun probe(url: String, maxPx: Int): VideoPoster? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                when {
                    url.startsWith(ASSET_PREFIX) -> context.assets.openFd(url.removePrefix(ASSET_PREFIX)).use { fd ->
                        retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    url.startsWith("file://") -> retriever.setDataSource(url.toUri().path)
                    else -> retriever.setDataSource(url, emptyMap())
                }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                // A frame a moment in, not the black or blank first frame recordings tend to start on.
                val timeUs = (durationMs?.let { minOf(it / 3, POSTER_AT_MS) } ?: 0L) * 1_000
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxPx, maxPx)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (frame == null && durationMs == null) null else VideoPoster(frame, durationMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    companion object {
        /** Coil's and ExoPlayer's spelling for APK assets; the demo backend serves its sample media this way. */
        const val ASSET_PREFIX = "file:///android_asset/"
        private const val POSTER_AT_MS = 1_500L
        /** A header and one frame's worth of range requests; anything slower than this is a network that has gone. */
        private const val PROBE_TIMEOUT_MS = 15_000L
        private val STALE_URL_CODES = setOf(400, 401, 403, 404)
    }
}
