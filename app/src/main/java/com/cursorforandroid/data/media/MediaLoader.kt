package com.cursorforandroid.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.svg.SvgDecoder
import coil3.toBitmap
import com.cursorforandroid.data.api.readCancellably
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.DiskSweep
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.FileRead
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.domain.FileBytes
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import com.cursorforandroid.util.toHex

/** A frame from early in a video plus its length; [frame] is null when the file could not be probed. */
class VideoPoster(val frame: Bitmap?, val durationMs: Long?)

/** Hears a transfer's bytes as they arrive: [read] so far of [total], which is -1 when the source does not say. Called on a worker thread. */
fun interface TransferProgress {
    fun onBytes(read: Long, total: Long)
}

val NoProgress = TransferProgress { _, _ -> }

/** The first [max] bytes of a file, for telling its format by its magic numbers. Blocking. */
internal fun File.head(max: Int = 4096): ByteArray = inputStream().use { input ->
    val buffer = ByteArray(max)
    var filled = 0
    while (filled < max) {
        val n = input.read(buffer, filled, max - filled)
        if (n < 0) break
        filled += n
    }
    buffer.copyOf(filled)
}

/**
 * Fetches the pixels behind a [MediaRef]. Images go through Coil (downsampling to the requested bounds, memory and
 * disk caches keyed by the artifact rather than its rotating presigned URL); video posters come from
 * [MediaMetadataRetriever], which reads only the ranges it needs instead of downloading the recording.
 *
 * Every failure reaches the caller as a [MediaProblemException] naming what went wrong in the reader's words: the
 * bytes are looked at before they are given to a decoder — a base64 or `data:` or JSON wrapper taken off, a Git LFS
 * pointer or a web page behind an image link named as such — and an SVG is drawn rather than refused.
 */
class MediaLoader(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val artifacts: ArtifactRepository,
    /** The reads behind a path of the agent's own machine or repository ([MediaRef.Workspace]); null where none is wired. */
    private val files: () -> AgentFileRepository? = { null },
    /**
     * The Agent Store reads behind `/cursor/stores/…` paths (Extended mode); null where no account is wired. Looked
     * up per use, so that building the loader — which a sign-out does, to wipe Coil's disk cache — builds nothing else.
     */
    private val stores: () -> StoreFileRepository? = { null },
) {
    // BitmapFactory for every raster decode, registered ahead of Coil's ImageDecoder default: the bitmaps are software
    // ones anyway (allowHardware false), the downsampling is the same, and it is the one path that also runs under the
    // JVM test renderer, so a fetched URL — cached as a file, which ImageDecoder there cannot open — decodes like an
    // asset. The SVG decoder goes first: it answers only for SVG, which BitmapFactory would refuse.
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(SvgDecoder.Factory())
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(okHttp))
        }
        .diskCache { imageDiskCache(context.cacheDir) }
        // Coil's default is a share of the device's memory class, which a large heap doubles; decoded images are
        // cheap to decode again from the disk cache, a heap they fill is not.
        .memoryCache { MemoryCache.Builder().maxSizeBytes(minOf(MEMORY_CACHE_BYTES, Runtime.getRuntime().maxMemory() / 8)).build() }
        .build()

    private val posters = LruCache<String, VideoPoster>(12)

    private val copyLocks = List(COPY_LOCK_STRIPES) { Mutex() }

    /** Every figure asked for with a chat named, and how its read went: the transcript diagnostics' `media:` section. */
    val loads = MediaLoads()

    /**
     * Decodes [ref] to fit within [maxWidthPx] x [maxHeightPx] (downsampling only, never upscaling). With [wake], a
     * file of the agent's machine found asleep is read again once the machine is woken (see [AgentFileRepository.read]).
     *
     * Never waits longer than [IMAGE_DEADLINE_MS] ([WAKE_DEADLINE_MS] with [wake]): a read with no answer by then is
     * given up — every step of it answers cancellation — and ends as [MediaProblem.TimedOut], naming the step, which
     * the row offers Retry on. [onStage] hears each step as the read reaches it; [chat] files the read under that chat
     * in [loads].
     */
    suspend fun image(
        ref: MediaRef,
        maxWidthPx: Int,
        maxHeightPx: Int,
        wake: Boolean = false,
        chat: String? = null,
        onStage: (MediaStage) -> Unit = {},
    ): Bitmap = onMain {
        var stage = MediaStage.STARTING
        val reached: (MediaStage) -> Unit = { next ->
            stage = next
            chat?.let { loads.stage(it, ref, next) }
            onStage(next)
        }
        chat?.let { loads.started(it, ref, maxWidthPx, maxHeightPx) }
        val deadline = if (wake) WAKE_DEADLINE_MS else IMAGE_DEADLINE_MS
        try {
            val bitmap = withTimeoutOrNull(deadline) { named { decodeImage(ref, maxWidthPx, maxHeightPx, wake, reached) } }
                ?: throw MediaProblemException(MediaProblem.TimedOut(stage, deadline / 1_000))
            chat?.let { loads.ready(it, ref, bitmap.width, bitmap.height) }
            bitmap
        } catch (e: CancellationException) {
            chat?.let { loads.left(it, ref) }
            throw e
        } catch (e: Throwable) {
            chat?.let { loads.failed(it, ref, problemOf(e)) }
            throw e
        }
    }

    private suspend fun decodeImage(ref: MediaRef, maxWidthPx: Int, maxHeightPx: Int, wake: Boolean, reached: (MediaStage) -> Unit): Bitmap = when (ref) {
        is MediaRef.Remote, is MediaRef.Artifact -> decodeUrl(ref, maxWidthPx, maxHeightPx, reached)
        else -> bytesFor(ref, wake, reached).let { bytes -> reached(MediaStage.DECODE); decodeBytes(ref, bytes, maxWidthPx, maxHeightPx) }
    }

    private suspend fun decodeUrl(ref: MediaRef, maxWidthPx: Int, maxHeightPx: Int, reached: (MediaStage) -> Unit = {}): Bitmap {
        if (ref is MediaRef.Artifact) reached(MediaStage.LINK)
        val url = urlFor(ref)
        reached(MediaStage.FETCH)
        val first = decode(ref, url, maxWidthPx, maxHeightPx)
        if (first is SuccessResult) return first.image.toBitmap()
        var error = (first as ErrorResult).throwable
        // A presigned URL that expired between resolution and fetch: ask for a fresh one exactly once.
        if (ref is MediaRef.Artifact && error.isStaleUrl()) {
            artifacts.invalidate(ref.agentId, ref.path)
            val retry = decode(ref, urlFor(ref), maxWidthPx, maxHeightPx)
            if (retry is SuccessResult) return retry.image.toBitmap()
            error = (retry as ErrorResult).throwable
        }
        if (error is HttpException) throw MediaProblemException(MediaProblem.Failed("The server answered ${error.response.code} for this image."), error)
        if (error is IOException) throw MediaProblemException(MediaProblem.Failed(error.userMessage()), error)
        // The fetch came back and no decoder took it: what came back is looked at, unwrapped, and named if it is not a picture.
        return decodeBytes(ref, download(urlFor(ref), MAX_DIAGNOSE_BYTES), maxWidthPx, maxHeightPx)
    }

    private suspend fun decodeBytes(ref: MediaRef, raw: ByteArray, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        val plain = unwrapped(raw, ref.label, expected = "an image") { it.isImage }
        val result = decode(ref, plain.bytes, maxWidthPx, maxHeightPx)
        if (result is SuccessResult) return result.image.toBitmap()
        throw MediaProblemException(MediaProblem.undecodable(plain.format), (result as ErrorResult).throwable)
    }

    /** [raw] with its wrapper taken off, or the problem that it is not what [accepts] takes. */
    private fun unwrapped(raw: ByteArray, name: String, expected: String, accepts: (FileFormat) -> Boolean): FileBytes.Plain {
        if (raw.isEmpty()) throw MediaProblemException(MediaProblem.Damaged(FileFormat.ofName(name)))
        val plain = when (val read = FileBytes.of(raw, name)) {
            is FileBytes.LfsPointer -> throw MediaProblemException(MediaProblem.LfsPointer)
            is FileBytes.Plain -> read
        }
        val format = plain.format
        if (format != null && !accepts(format)) throw MediaProblemException(MediaProblem.NotMedia(expected, format))
        if (format == null && FileBytes.looksLikeText(plain.bytes)) throw MediaProblemException(MediaProblem.NotMedia(expected, null))
        return plain
    }

    /** The URL ExoPlayer should open for a video or a sound; resolved at play time so it is never an expired one. */
    suspend fun playbackUrl(ref: MediaRef, wake: Boolean = false): String = onMain { named { resolvePlaybackUrl(ref, wake) } }

    private suspend fun resolvePlaybackUrl(ref: MediaRef, wake: Boolean = false): String = when (ref) {
        is MediaRef.Remote -> RemoteUrls.fetchable(ref.url)
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        is MediaRef.Store -> storeUrl(ref)
        is MediaRef.Local -> localPlaybackUrl(ref)
        // The workspace hands out bytes, not a URL: they are kept as a file of the cache and played from there.
        is MediaRef.Workspace -> "file://${materialize(ref, ref.label, wake).absolutePath}"
        is MediaRef.Inline -> throw MediaProblemException(MediaProblem.NotReadable("Embedded recordings aren't supported", null))
        is MediaRef.Unavailable -> throw MediaProblemException(MediaProblem.NotReadable("This file isn't available", unavailableDetail(ref)))
    }

    /**
     * A poster frame and duration for the video at [ref], fitting [maxPx] square; null when unavailable, and
     * remembered as such once the file was there to probe. Kept per size: a tile's small poster is not the viewer's.
     */
    suspend fun videoPoster(ref: MediaRef, maxPx: Int): VideoPoster? = onMain {
        val key = "${ref.cacheKey}@$maxPx"
        posters.get(key)?.let { return@onMain it }
        val url = runCatching { resolvePlaybackUrl(ref) }.getOrElse { return@onMain null }
        val poster = probe(url, maxPx) ?: VideoPoster(frame = null, durationMs = null)
        posters.put(key, poster)
        poster
    }

    /**
     * A file of this device, for the player: named as gone when it is not there rather than handed on for the player
     * to fail on. An APK asset is the player's own to open.
     */
    private suspend fun localPlaybackUrl(ref: MediaRef.Local): String {
        val url = "file://${ref.path}"
        if (url.startsWith(ASSET_PREFIX)) return url
        if (!withContext(Dispatchers.IO) { File(ref.path).isFile }) throw MediaProblemException(MediaProblem.NotReadable(NOT_ON_DEVICE, null))
        return url
    }

    /**
     * The bytes behind [ref] as a file of this app's cache (`cache/media/`), for handing to another app — the share
     * sheet, a viewer, the gallery — through the `FileProvider`. Fetched once per artifact and kept while it is among
     * the most recently used ([MEDIA_MAX_BYTES]); a copy already there is answered without a request. [fileName] gives
     * the copy its name and so its type, as the other app reads it. [onProgress] hears a download's bytes as they
     * arrive, on a worker thread (the total is -1 when the server does not say it).
     *
     * One fetch per copy at a time: a second caller for the same copy waits for the first and is answered its file.
     */
    suspend fun file(ref: MediaRef, fileName: String, onProgress: TransferProgress = NoProgress): File =
        onMain { named { materialize(ref, fileName, onProgress = onProgress) } }

    /**
     * Runs [block] on the bytes behind [ref] as a whole file of this device, and says whether they had to be fetched
     * for it. A file already whole here is used where it is, with no request and no copy: a file of this device's own
     * ([MediaRef.Local]), the copy [file] keeps, or a fetched picture's original bytes in the image cache (held open
     * for [block], so the cache cannot drop them mid-read). Otherwise the bytes are fetched as [file] fetches them.
     */
    suspend fun <T> withFile(ref: MediaRef, fileName: String, onProgress: TransferProgress = NoProgress, block: suspend (file: File, fetched: Boolean) -> T): T = onMain {
        val onDisk = named { wholeOnDisk(ref, fileName) }
        if (onDisk != null) {
            try {
                return@onMain block(onDisk.file, false)
            } finally {
                onDisk.release()
            }
        }
        block(named { materialize(ref, fileName, onProgress = onProgress) }, true)
    }

    private class OnDisk(val file: File, val release: () -> Unit = {})

    private suspend fun wholeOnDisk(ref: MediaRef, fileName: String): OnDisk? = withContext(Dispatchers.IO) {
        if (ref is MediaRef.Local) File(ref.path).takeIf { it.isFile && it.length() > 0L }?.let { return@withContext OnDisk(it) }
        copyFor(ref, fileName).takeIf { it.isFile && it.length() > 0L }?.let {
            it.setLastModified(System.currentTimeMillis())
            return@withContext OnDisk(it)
        }
        if (ref !is MediaRef.Remote && ref !is MediaRef.Artifact) return@withContext null
        val snapshot = imageLoader.diskCache?.openSnapshot(ref.cacheKey) ?: return@withContext null
        val data = snapshot.data.toFile()
        // Only the file itself: a wrapper or a web page the decoder was handed is fetched again and named for what it is.
        if (data.isFile && FileFormat.sniff(data.head())?.isMedia == true) OnDisk(data) { snapshot.close() } else null.also { snapshot.close() }
    }

    private fun copyFor(ref: MediaRef, fileName: String): File =
        File(File(context.cacheDir, MEDIA_DIR), "${ref.cacheKey.hashCode().toUInt().toString(16)}-${safeName(fileName)}")

    /**
     * Keeps [bytes] — a file the panel or the file viewer already holds — as a file of the cache and answers the
     * `file://` reference the media viewer opens it by. The same bytes under the same name are one file.
     */
    suspend fun keep(bytes: ByteArray, fileName: String): String = withContext(Dispatchers.Main.immediate) {
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-1").digest(bytes).toHex().take(16)
            val dir = File(context.cacheDir, "$MEDIA_DIR/$OPENED_DIR").apply { mkdirs() }
            val target = File(dir, "$digest-${safeName(fileName)}")
            if (target.isFile && target.length() == bytes.size.toLong()) {
                target.setLastModified(System.currentTimeMillis())
            } else {
                val partial = File(dir, "${target.name}.part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true).also { partial.delete() }
                trimCopies(dir, OPENED_MAX_BYTES, System.currentTimeMillis())
            }
            "file://${target.absolutePath}"
        }
    }

    /**
     * Where [ref] can be seen outside this app, for a row that cannot show it: the link itself, an artifact's
     * presigned URL, the Project on cursor.com for a store file, the repository's page for a workspace file.
     */
    suspend fun browserUrl(ref: MediaRef): String? = onMain {
        try {
            when (ref) {
                is MediaRef.Remote -> ref.url
                is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
                is MediaRef.Store -> ref.webUrl
                is MediaRef.Workspace -> files()?.webUrl(ref.agentId, ref.path)
                is MediaRef.Local, is MediaRef.Inline, is MediaRef.Unavailable -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The copy of [ref] under `cache/media/`, fetched when it is not there yet. Under the copy's lock: two callers
     * once wrote the same `.part` at once (a second tap on Save while a recording was still coming down), each
     * truncating the other's bytes, so the first handed the gallery a file with a hole in it and the second found its
     * `.part` renamed away and failed; only a third tap, with the copy finally whole, went through.
     */
    private suspend fun materialize(ref: MediaRef, fileName: String, wake: Boolean = false, onProgress: TransferProgress = NoProgress): File = withContext(Dispatchers.IO) {
        val target = copyFor(ref, fileName)
        val dir = target.parentFile!!.apply { mkdirs() }
        copyLocks[(target.name.hashCode() and Int.MAX_VALUE) % COPY_LOCK_STRIPES].withLock {
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val partial = File(dir, "${target.name}.part")
            try {
                partial.delete()
                when (ref) {
                    is MediaRef.Local -> File(ref.path).takeIf { it.isFile }?.copyTo(partial, overwrite = true) ?: throw MediaProblemException(MediaProblem.NotReadable(NOT_ON_DEVICE, null))
                    is MediaRef.Inline -> partial.writeBytes(ref.bytes)
                    is MediaRef.Store -> partial.writeBytes(storeReads().readBytes(ref))
                    is MediaRef.Workspace -> partial.writeBytes(unwrappedFile(workspaceBytes(ref, wake), ref.label))
                    is MediaRef.Remote, is MediaRef.Artifact -> download(ref, partial, onProgress)
                    is MediaRef.Unavailable -> throw MediaProblemException(MediaProblem.NotReadable("This file isn't available", unavailableDetail(ref)))
                }
                if (partial.length() == 0L) throw MediaProblemException(MediaProblem.Failed("The download was empty."))
                if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true)
                trimCopies(dir, MEDIA_MAX_BYTES, System.currentTimeMillis())
                target
            } finally {
                partial.delete()
            }
        }
    }

    /** A workspace file's bytes as they are meant, a wrapper taken off; a pointer or text stays as it came. */
    private fun unwrappedFile(raw: ByteArray, name: String): ByteArray = (FileBytes.of(raw, name) as? FileBytes.Plain)?.bytes ?: raw

    /**
     * The bytes behind a link into [into], whole or not at all. A dropped connection or a server's passing failure
     * (5xx, 408, 429) is tried again up to [DOWNLOAD_ATTEMPTS] times, picking up where the bytes stopped when the
     * server takes a `Range`; an artifact's presigned link refused as stale is asked for afresh once; a body shorter
     * than its `Content-Length` is a failure, never a finished file.
     */
    private suspend fun download(ref: MediaRef, into: File, onProgress: TransferProgress) {
        var url = resolvePlaybackUrl(ref)
        if (url.startsWith(ASSET_PREFIX) || url.startsWith("file:")) return copyLocal(url, into, onProgress)
        var refreshed = false
        var attempt = 1
        while (true) {
            try {
                return transfer(url, into, onProgress)
            } catch (e: HttpRefusal) {
                when {
                    e.code in STALE_URL_CODES && ref is MediaRef.Artifact && !refreshed -> {
                        refreshed = true
                        artifacts.invalidate(ref.agentId, ref.path)
                        url = resolvePlaybackUrl(ref)
                        into.delete()
                    }
                    e.code in TRANSIENT_CODES && attempt < DOWNLOAD_ATTEMPTS -> delay(RETRY_BACKOFF_MS shl (attempt++ - 1))
                    else -> throw MediaProblemException(MediaProblem.Failed("The download answered ${e.code}.", retryable = e.code in TRANSIENT_CODES), e)
                }
            } catch (e: MediaProblemException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= DOWNLOAD_ATTEMPTS) throw e
                delay(RETRY_BACKOFF_MS shl (attempt++ - 1))
            }
        }
    }

    private suspend fun copyLocal(url: String, into: File, onProgress: TransferProgress): Unit = withContext(Dispatchers.IO) {
        if (url.startsWith(ASSET_PREFIX)) {
            context.assets.open(url.removePrefix(ASSET_PREFIX)).use { input -> into.outputStream().use { input.copyCounting(it, 0L, -1L, onProgress) } }
        } else {
            val source = File(url.toUri().path ?: throw IOException("Bad file URL"))
            source.inputStream().use { input -> into.outputStream().use { input.copyCounting(it, 0L, source.length(), onProgress) } }
        }
    }

    /** One request for the rest of [into]: from its end when it has bytes already and the server answers the range. */
    private suspend fun transfer(url: String, into: File, onProgress: TransferProgress) {
        val have = if (into.isFile) into.length() else 0L
        val request = Request.Builder().url(url).apply { if (have > 0L) header("Range", "bytes=$have-") }.build()
        okHttp.newCall(request).readCancellably { response ->
            if (response.code == 416 && have > 0L) {
                into.delete()
                throw IOException("The download's range was refused.")
            }
            if (!response.isSuccessful) throw HttpRefusal(response.code)
            val body = response.body ?: throw IOException("The download was empty.")
            val resumed = have > 0L && response.code == 206 && response.header("Content-Range")?.startsWith("bytes $have-") == true
            val start = if (resumed) have else 0L
            val length = body.contentLength()
            val total = if (length >= 0L) start + length else -1L
            val read = FileOutputStream(into, resumed).use { out -> body.byteStream().use { it.copyCounting(out, start, total, onProgress) } }
            if (total >= 0L && read != total) throw IOException("The download ended early ($read of $total bytes).")
        }
    }

    private fun java.io.InputStream.copyCounting(out: java.io.OutputStream, start: Long, total: Long, onProgress: TransferProgress): Long {
        val buffer = ByteArray(64 * 1024)
        var read = start
        onProgress.onBytes(read, total)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            read += n
            onProgress.onBytes(read, total)
        }
        return read
    }

    private class HttpRefusal(val code: Int) : IOException("The download answered $code.")

    /** Up to [max] bytes behind [url], for looking at what a decoder refused. */
    private suspend fun download(url: String, max: Int): ByteArray {
        if (url.startsWith(ASSET_PREFIX)) return withContext(Dispatchers.IO) { context.assets.open(url.removePrefix(ASSET_PREFIX)).use { it.readAtMost(max) } }
        return okHttp.newCall(Request.Builder().url(url).build()).readCancellably { response ->
            if (!response.isSuccessful) throw MediaProblemException(MediaProblem.Failed("The server answered ${response.code} for this image."))
            response.body?.byteStream()?.use { it.readAtMost(max) } ?: ByteArray(0)
        }
    }

    private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Gives memory back when the system asks: the video posters, and the decoded images — half of them, or all when
     * [hard]. The disk cache stays, so an image on screen again decodes rather than downloads.
     */
    fun trimMemory(hard: Boolean) {
        posters.evictAll()
        val cache = imageLoader.memoryCache ?: return
        if (hard) cache.clear() else cache.trimToSize(cache.size / 2)
    }

    /** Forgets everything fetched for the signed-out account; the disk cache is wiped off the main thread. */
    suspend fun clearCaches() {
        posters.evictAll()
        loads.clear()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
            DiskSweep.deleteTree(File(context.cacheDir, MEDIA_DIR))
        }
    }

    /**
     * Runs [block] confined to the main thread: it starts there (inline when the caller already is there, as a
     * composition effect is), every hop it makes — Coil's fetch and decode, the retriever, a download — comes back
     * to the main thread through the main looper, and the caller resumes there. Every caller is a composition
     * coroutine; one that resumed on a worker thread instead would carry the composition with it — a snapshot write
     * off the main thread, and under the test harness's unconfined effect dispatcher whole frames performed on a
     * worker while the main thread is mid-layout, or a wake-up that races the main thread's and is lost.
     *
     * Wrapping the whole of [block] rather than only its answer matters: with the answer alone hopped to the main
     * thread, the caller's continuation still ran on the worker up to that hop, and the harness's interceptors with it.
     */
    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    /** [block], with whatever it throws named as a [MediaProblem]: a raw decoder or HTTP message never reaches a row. */
    private suspend fun <T> named(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        throw t as? MediaProblemException ?: MediaProblemException(problemOf(t), t)
    }

    private fun storeReads(): StoreFileRepository = stores() ?: throw MediaProblemException(MediaProblem.NotReadable("In the Project's context", StoreFileRepository.NOT_AVAILABLE))

    private suspend fun storeUrl(ref: MediaRef.Store): String = storeReads().downloadUrl(ref)

    private suspend fun urlFor(ref: MediaRef): String = when (ref) {
        is MediaRef.Remote -> RemoteUrls.fetchable(ref.url)
        is MediaRef.Artifact -> artifacts.downloadUrl(ref.agentId, ref.path)
        else -> error("Not a URL: $ref")
    }

    private suspend fun bytesFor(ref: MediaRef, wake: Boolean = false, reached: (MediaStage) -> Unit = {}): ByteArray = when (ref) {
        // Bytes, fetched and kept by the repository (its own disk cache, a dead URL asked for again), decoded like
        // an inline image: the same path as a file of this device, and one the JVM test renderer can take.
        is MediaRef.Store -> storeReads().readBytes(ref) { step ->
            reached(
                when (step) {
                    StoreFileRepository.Step.STORE -> MediaStage.STORE
                    StoreFileRepository.Step.LINK -> MediaStage.LINK
                    StoreFileRepository.Step.DOWNLOAD -> MediaStage.DOWNLOAD
                },
            )
        }
        // Read here rather than handed to Coil as a file: for a file Coil decodes through ImageDecoder, which the
        // JVM test renderer lacks, while bytes go through BitmapFactory like an inline image. The files are small.
        is MediaRef.Local -> withContext(Dispatchers.IO) {
            File(ref.path).takeIf { it.isFile }?.readBytes() ?: throw MediaProblemException(MediaProblem.NotReadable(NOT_ON_DEVICE, null))
        }
        is MediaRef.Inline -> ref.bytes
        is MediaRef.Workspace -> {
            reached(MediaStage.MACHINE)
            workspaceBytes(ref, wake)
        }
        is MediaRef.Unavailable -> throw MediaProblemException(MediaProblem.NotReadable("This image isn't available", unavailableDetail(ref)))
        is MediaRef.Remote, is MediaRef.Artifact -> error("Fetched by URL: $ref")
    }

    private suspend fun workspaceBytes(ref: MediaRef.Workspace, wake: Boolean = false): ByteArray {
        val where = if (AgentFileRepository.isInWorkspace(ref.path, null)) IN_WORKSPACE else ON_MACHINE
        val reads = files() ?: throw MediaProblemException(MediaProblem.NotReadable(where, null))
        return when (val read = reads.read(ref.agentId, ref.path, force = wake, wake = wake)) {
            is FileRead.Loaded -> read.file.bytes
            is FileRead.NotReadable -> throw MediaProblemException(MediaProblem.NotReadable(where, read.reason))
            // A picture the machine would not give may still be one the agent published as an artifact of the same name.
            is FileRead.Failed -> artifactBytes(ref.agentId, ref.path) ?: throw MediaProblemException(problemOf(read))
        }
    }

    /**
     * The bytes of an artifact whose file name matches [path]'s, or null when none does or it cannot be fetched: a
     * generated or saved picture the agent published (`GET /v1/agents/{id}/artifacts`) is a documented copy of a file
     * that lived outside the workspace. The newest match wins (the listing is newest-first).
     */
    private suspend fun artifactBytes(agentId: String, path: String): ByteArray? {
        val name = ArtifactPaths.fileName(path).ifBlank { return null }
        val match = runCatching { artifacts.list(agentId) }.getOrNull()?.firstOrNull { it.name == name } ?: return null
        val url = runCatching { artifacts.downloadUrl(agentId, match.path) }.getOrNull() ?: return null
        return runCatching { download(url, MAX_DIAGNOSE_BYTES) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun unavailableDetail(ref: MediaRef.Unavailable): String? = ref.src.takeIf { it.isNotBlank() && !it.startsWith("data:") }

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
        /** Decoded images kept in memory at most (see the image loader's memory cache). */
        const val MEMORY_CACHE_BYTES = 32L * 1024 * 1024
        /** Under the cache: the copies [file] makes for other apps; `file_paths.xml` lets the FileProvider hand them out. */
        const val MEDIA_DIR = "media"
        /** Under [MEDIA_DIR]: the files the panel and the file viewer hand to the media viewer ([keep]). */
        private const val OPENED_DIR = "opened"
        /**
         * The copies [file] makes at most, least recently used going first. They are for handing a file to another
         * app or the player, a few at a time; kept past that they were a second copy of every artifact ever shared.
         */
        private const val MEDIA_MAX_BYTES = 64L shl 20
        /** [keep]'s copies at most: bytes the app already held, written again at no cost. */
        private const val OPENED_MAX_BYTES = 32L shl 20
        /** A copy nothing has asked for in this long goes at the next start ([sweepCopies]): the other app has long read it. */
        private const val COPY_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        /**
         * A copy used this recently counts against its bound but is never what the bound takes: it may still be on
         * screen (a player reopens its file to seek), or the app it was shared to may not have read it yet.
         */
        private const val IN_USE_MS = 60 * 60 * 1000L
        /**
         * Coil's own default directory, so the entries a build without a bound wrote are adopted and trimmed rather than
         * left behind; its default size was 2% of the disk, up to 250 MB, for pictures the memory cache already holds.
         */
        private const val IMAGE_CACHE_DIR = "coil3_disk_cache"
        private const val IMAGE_CACHE_BYTES = 64L shl 20

        /** One per directory for the process, as Coil's own default is: two caches over one journal corrupt each other. */
        private val imageDiskCaches = HashMap<File, DiskCache>()

        private fun imageDiskCache(cacheDir: File): DiskCache = synchronized(imageDiskCaches) {
            imageDiskCaches.getOrPut(cacheDir) {
                DiskCache.Builder().directory(File(cacheDir, IMAGE_CACHE_DIR).toOkioPath()).maxSizeBytes(IMAGE_CACHE_BYTES).build()
            }
        }

        private fun isPartial(file: File): Boolean = file.name.endsWith(".part")

        private fun trimCopies(dir: File, maxBytes: Long, nowMillis: Long) {
            DiskSweep.trimToBytes(dir, maxBytes, keepAfterMillis = nowMillis - IN_USE_MS, skip = ::isPartial)
        }

        /**
         * At start: the copies made for other apps and the viewer that nothing has asked for in a day, and a transfer's
         * leftover `.part`, then what is left held to the bounds. Blocking; call off the main thread.
         */
        fun sweepCopies(cacheDir: File, nowMillis: Long = System.currentTimeMillis()) {
            val media = File(cacheDir, MEDIA_DIR)
            val opened = File(media, OPENED_DIR)
            for ((dir, max) in listOf(media to MEDIA_MAX_BYTES, opened to OPENED_MAX_BYTES)) {
                DiskSweep.deleteOlderThan(dir, nowMillis - COPY_MAX_AGE_MS)
                trimCopies(dir, max, nowMillis)
            }
        }
        /** Where into a recording its poster is read: a third of the way in, at most this far. A tile's poster is read at the same frame. */
        internal const val POSTER_AT_MS = 1_500L
        /** A file of this device that is not there (any more): the one thing [MediaRef.Local] can go wrong with. */
        const val NOT_ON_DEVICE = "This file is no longer on this device"
        /** A header and one frame's worth of range requests; anything slower than this is a network that has gone. */
        private const val PROBE_TIMEOUT_MS = 15_000L
        /**
         * The longest a figure waits for its pixels: the store, a link and a download of a few megabytes on a slow
         * connection, with room for the account's pause after a refusal. Past it the row says so and offers Retry.
         */
        const val IMAGE_DEADLINE_MS = 60_000L
        /** With the machine being woken first, which waits up to a minute for it ([AgentFileRepository.WAKE_WAIT_MS]). */
        const val WAKE_DEADLINE_MS = 120_000L
        /** What is read again of a response no decoder took, to say what it was: more than any page or wrapper needs. */
        private const val MAX_DIAGNOSE_BYTES = 24 * 1024 * 1024
        private val STALE_URL_CODES = setOf(400, 401, 403, 404)
        /** A server's passing failures: a download that meets one is tried again, as it is after a dropped connection. */
        private val TRANSIENT_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        /** Tries at a download in all, the first included; the waits between them double from [RETRY_BACKOFF_MS]. */
        private const val DOWNLOAD_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 500L
        private const val COPY_LOCK_STRIPES = 16
        private const val IN_WORKSPACE = "In the agent's workspace"
        private const val ON_MACHINE = "On the agent's machine"

        /** A failed read of a file of the agent's machine or repository, in the words the row or the viewer page says it with. */
        fun problemOf(read: FileRead.Failed): MediaProblem = when (read.reason) {
            FileRead.Reason.MachineAsleep -> MediaProblem.MachineAsleep(read.asked)
            FileRead.Reason.MachineGone -> MediaProblem.MachineGone(read.asked)
            FileRead.Reason.NotFound -> MediaProblem.Failed("The agent's machine has no such file", "It was moved or deleted, or went with a machine that was replaced.", asked = read.asked)
            FileRead.Reason.OutsideWorkspace -> MediaProblem.OutsideWorkspace(read.asked)
            FileRead.Reason.Other -> MediaProblem.Failed("Couldn't read this file", read.message, retryable = read.retryable, asked = read.asked)
        }

        private fun safeName(fileName: String): String = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "media" }

        /** Any failure as the reader's words: a named one as it is, the network's in its words, anything else as unreadable. */
        fun problemOf(t: Throwable): MediaProblem = when (t) {
            is MediaProblemException -> t.problem
            is HttpException -> MediaProblem.Failed("The server answered ${t.response.code}.")
            is IOException -> MediaProblem.Failed(t.userMessage())
            else -> MediaProblem.Damaged(null)
        }
    }
}

/** Links the web shows a file at, turned into the link its bytes are at: GitHub's `blob` page into the raw file. */
object RemoteUrls {
    private val GITHUB_BLOB = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/blob/(.+)$""", RegexOption.IGNORE_CASE)

    fun fetchable(url: String): String {
        val match = GITHUB_BLOB.matchEntire(url.trim()) ?: return url
        val (owner, repo, rest) = match.destructured
        val path = rest.substringBefore('?').substringBefore('#')
        return "https://github.com/$owner/$repo/raw/$path"
    }
}
