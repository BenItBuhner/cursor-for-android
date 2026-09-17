package com.cursorforandroid.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
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
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
    private val okHttp: OkHttpClient,
    private val artifacts: ArtifactRepository,
    /**
     * The Agent Store reads behind `/cursor/stores/…` paths (Extended mode); null where no account is wired. Looked
     * up per use, so that building the loader — which a sign-out does, to wipe Coil's disk cache — builds nothing else.
     */
    private val stores: () -> StoreFileRepository? = { null },
) {
    // BitmapFactory for every decode, registered ahead of Coil's ImageDecoder default: the bitmaps are software ones
    // anyway (allowHardware false), the downsampling is the same, and it is the one path that also runs under the JVM
    // test renderer, so a fetched URL — cached as a file, which ImageDecoder there cannot open — decodes like an asset.
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(okHttp))
        }
        .build()

    private val posters = LruCache<String, VideoPoster>(12)

    /** Decodes [ref] to fit within [maxWidthPx] x [maxHeightPx] (downsampling only, never upscaling). */
    suspend fun image(ref: MediaRef, maxWidthPx: Int, maxHeightPx: Int): Bitmap = onMain { decodeImage(ref, maxWidthPx, maxHeightPx) }

    private suspend fun decodeImage(ref: MediaRef, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
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
    suspend fun playbackUrl(ref: MediaRef): String = onMain { resolvePlaybackUrl(ref) }

    private suspend fun resolvePlaybackUrl(ref: MediaRef): String = when (ref) {
        is MediaRef.Remote -> ref.url
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        is MediaRef.Store -> storeUrl(ref)
        is MediaRef.Local -> "file://${ref.path}"
        is MediaRef.Inline -> throw IOException("Embedded videos aren't supported.")
        is MediaRef.Unavailable -> throw IOException("This video isn't available.")
    }

    /** A poster frame and duration for the video at [ref]; null (and remembered as such) when unavailable. */
    suspend fun videoPoster(ref: MediaRef, maxPx: Int): VideoPoster? = onMain {
        posters.get(ref.cacheKey)?.let { return@onMain it }
        val url = runCatching { resolvePlaybackUrl(ref) }.getOrElse { return@onMain null }
        val poster = probe(url, maxPx) ?: VideoPoster(frame = null, durationMs = null)
        posters.put(ref.cacheKey, poster)
        poster
    }

    /**
     * The bytes behind [ref] as a file of this app's cache (`cache/media/`), for handing to another app — the share
     * sheet, a viewer, the gallery — through the `FileProvider`. Fetched once per artifact and kept; a copy already
     * there is answered without a request. [fileName] gives the copy its name and so its type, as the other app
     * reads it.
     */
    suspend fun file(ref: MediaRef, fileName: String): File = onMain { materialize(ref, fileName) }

    private suspend fun materialize(ref: MediaRef, fileName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, MEDIA_DIR).apply { mkdirs() }
        val safeName = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "media" }
        val target = File(dir, "${ref.cacheKey.hashCode().toUInt().toString(16)}-$safeName")
        if (target.isFile && target.length() > 0L) return@withContext target
        val partial = File(dir, "${target.name}.part")
        try {
            when (ref) {
                is MediaRef.Local -> File(ref.path).takeIf { it.isFile }?.copyTo(partial, overwrite = true) ?: throw IOException("This file is no longer on this device.")
                is MediaRef.Inline -> partial.writeBytes(ref.bytes)
                is MediaRef.Store -> partial.writeBytes(storeReads().readBytes(ref))
                is MediaRef.Remote, is MediaRef.Artifact -> download(resolvePlaybackUrl(ref), partial)
                is MediaRef.Unavailable -> throw IOException("This file isn't available.")
            }
            if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true)
            target
        } finally {
            partial.delete()
        }
    }

    private fun download(url: String, into: File) {
        if (url.startsWith(ASSET_PREFIX)) {
            context.assets.open(url.removePrefix(ASSET_PREFIX)).use { input -> into.outputStream().use { input.copyTo(it) } }
            return
        }
        if (url.startsWith("file:")) {
            File(url.toUri().path ?: throw IOException("Bad file URL")).copyTo(into, overwrite = true)
            return
        }
        okHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download answered ${response.code}.")
            val body = response.body ?: throw IOException("The download was empty.")
            into.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /** Forgets everything fetched for the signed-out account; the disk cache is wiped off the main thread. */
    suspend fun clearCaches() {
        posters.evictAll()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
            File(context.cacheDir, MEDIA_DIR).deleteRecursively()
        }
    }

    /**
     * Runs [block] and hands its answer (or its failure) back on the main thread. Every caller is a composition
     * effect, and Coil, the retriever and the downloads all complete on their own threads: resuming a composition's
     * coroutine on one of those puts snapshot writes and, under the test harness's unconfined effect dispatcher,
     * whole frames on a worker thread while the main thread is mid-layout.
     */
    private suspend fun <T> onMain(block: suspend () -> T): T {
        val result = runCatching { block() }
        return withContext(Dispatchers.Main.immediate) { result.getOrThrow() }
    }

    private fun storeReads(): StoreFileRepository = stores() ?: throw IOException(StoreFileRepository.NOT_AVAILABLE)

    private suspend fun storeUrl(ref: MediaRef.Store): String = storeReads().downloadUrl(ref)

    private suspend fun dataFor(ref: MediaRef): Any = when (ref) {
        is MediaRef.Remote -> ref.url
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        // Bytes, fetched and kept by the repository (its own disk cache, a dead URL asked for again), decoded like
        // an inline image: the same path as a file of this device, and one the JVM test renderer can take.
        is MediaRef.Store -> storeReads().readBytes(ref)
        // Read here rather than handed to Coil as a file: for a file Coil decodes through ImageDecoder, which the
        // JVM test renderer lacks, while bytes go through BitmapFactory like an inline image. The files are small.
        is MediaRef.Local -> withContext(Dispatchers.IO) {
            File(ref.path).takeIf { it.isFile }?.readBytes() ?: throw IOException("This image is no longer on this device.")
        }
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
        /** Under the cache: the copies [file] makes for other apps; `file_paths.xml` lets the FileProvider hand them out. */
        const val MEDIA_DIR = "media"
        private const val POSTER_AT_MS = 1_500L
        /** A header and one frame's worth of range requests; anything slower than this is a network that has gone. */
        private const val PROBE_TIMEOUT_MS = 15_000L
        private val STALE_URL_CODES = setOf(400, 401, 403, 404)
    }
}
