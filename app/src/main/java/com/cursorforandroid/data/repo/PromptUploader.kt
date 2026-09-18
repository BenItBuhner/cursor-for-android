package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.data.api.await
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.UploadRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An upload that did not get through, worded for the composer: which file, and what happened to it. */
class PromptUploadException(val filename: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where an upload stands, per file, for the chip's ring: bytes put against bytes to put. */
fun interface UploadProgress {
    fun onProgress(fileIndex: Int, uploadedBytes: Long, totalBytes: Long)

    companion object {
        val NONE = UploadProgress { _, _, _ -> }
    }
}

/**
 * Puts a prompt's files where the account keeps them, the way the desktop's `prompt-upload-client.ts` does (Cursor
 * 3.20.21): `PresignPromptUpload` for the file, one `PUT` per part to its presigned URL — the body the part's slice of
 * the file and nothing else, no `Content-Type` (the desktop's `fetch(url, {method: "PUT", body: file.slice(offset,
 * offset + size)})` sends the slice of a `File` as a typeless `Blob`; the object's type is what the presign was told),
 * each bounded by [PART_TIMEOUT_MS] — then `CompletePromptUpload`, whose status has to be `COMPLETED`. A part or the
 * completion failing aborts the staged upload (`AbortPromptUpload`) and fails the file, named. The desktop's fallback
 * for the upload path being off — the bytes inline as `UploadedFile.data` — is taken here when the account refuses
 * to presign at all (the method unimplemented, the account not allowed it): nothing has moved yet, and the request can
 * still carry the file itself.
 */
class PromptUploader(
    private val api: PromptUploadApi,
    private val http: OkHttpClient,
    /** The team the account is on, when known; `PresignPromptUpload.team_id` is optional. */
    private val teamId: suspend () -> Int? = { null },
    private val partTimeoutMs: Long = PART_TIMEOUT_MS,
) {

    /** Every file in turn, so the chips fill one after another; the first failure stops the rest. */
    suspend fun upload(files: List<PromptFile>, progress: UploadProgress = UploadProgress.NONE): List<UploadedFile> =
        files.mapIndexed { index, file -> upload(file) { done, total -> progress.onProgress(index, done, total) } }

    /**
     * What the prompt names each of [files] as: the reference a file already carries ([PromptFile.upload], its upload
     * having run when it was attached), so nothing is sent for it here, else the upload made now. With every file
     * already up this makes no call at all — the send goes out at once.
     */
    suspend fun ensure(files: List<PromptFile>, progress: UploadProgress = UploadProgress.NONE): List<UploadedFile> =
        files.mapIndexed { index, file ->
            file.upload?.let { ref -> UploadedFile.of(file, ref) } ?: upload(file) { done, total -> progress.onProgress(index, done, total) }
        }

    /** Drops a staged upload the prompt will not reference after all (`AbortPromptUpload`); best effort, like the desktop's `CKy`. */
    suspend fun abort(ref: UploadRef) {
        try {
            api.abort(ref.uploadId, ref.s3UploadId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The staged parts expire on their own.
        }
    }

    suspend fun upload(file: PromptFile, progress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): UploadedFile {
        val mimeType = file.mimeType.ifBlank { PromptFile.OCTET_STREAM }
        val total = file.sizeBytes.toLong()
        // The desktop carries an empty file inline; there is nothing to stage.
        if (total == 0L) return UploadedFile(file.name, mimeType, uploadId = null, data = file.bytes)
        val presigned = try {
            api.presign(file.name, mimeType, total, teamId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectRpcException) {
            if (e.refusesUploads()) {
                progress(total, total)
                return UploadedFile(file.name, mimeType, uploadId = null, data = file.bytes)
            }
            throw PromptUploadException(file.name, "Couldn't upload ${file.name}: ${e.message}", e)
        } catch (e: IOException) {
            throw PromptUploadException(file.name, "Couldn't upload ${file.name}: ${e.message ?: "the connection failed"}", e)
        }
        if (presigned.parts.isEmpty()) {
            abortQuietly(presigned)
            throw PromptUploadException(file.name, "Couldn't upload ${file.name}: Cursor returned no upload instructions.")
        }
        progress(0L, total)
        try {
            var done = 0L
            for (part in presigned.parts) {
                val start = part.offsetBytes.coerceIn(0L, total).toInt()
                val end = (part.offsetBytes + part.sizeBytes).coerceIn(start.toLong(), total).toInt()
                putPart(file, part.url, start, end)
                done += (end - start)
                progress(done.coerceAtMost(total), total)
            }
            val status = api.complete(presigned.uploadId, presigned.s3UploadId)
            if (status != PromptUploadCompletion.COMPLETED) {
                throw PromptUploadException(file.name, "Couldn't upload ${file.name}: the upload did not complete (${status.name.lowercase().replace('_', ' ')}).")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { abortQuietly(presigned) }
            throw e
        } catch (e: PromptUploadException) {
            abortQuietly(presigned)
            throw e
        } catch (e: IOException) {
            abortQuietly(presigned)
            throw PromptUploadException(file.name, "Couldn't upload ${file.name}: ${e.message ?: "the connection failed"}", e)
        }
        return UploadedFile(file.name, mimeType, uploadId = presigned.uploadId, s3UploadId = presigned.s3UploadId)
    }

    /** One part: `PUT <url>` with the slice as its body, no type, bounded in time like the desktop's 120 s `AbortSignal.timeout`. */
    private suspend fun putPart(file: PromptFile, url: String, start: Int, end: Int) {
        val client = if (partTimeoutMs > 0) http.newBuilder().callTimeout(partTimeoutMs, TimeUnit.MILLISECONDS).build() else http
        val body = file.bytes.toRequestBody(null, start, end - start)
        val request = Request.Builder().url(url).put(body).build()
        val response = withContext(Dispatchers.IO) {
            if (partTimeoutMs > 0) withTimeout(partTimeoutMs + 1_000) { client.newCall(request).await() } else client.newCall(request).await()
        }
        response.use {
            if (!it.isSuccessful) {
                val code = s3ErrorCode(it.body?.string().orEmpty())
                throw PromptUploadException(file.name, "Couldn't upload ${file.name}: the storage answered ${it.code}${code?.let { c -> " ($c)" }.orEmpty()}.")
            }
        }
    }

    private suspend fun abortQuietly(presigned: PresignedPromptUpload) {
        try {
            api.abort(presigned.uploadId, presigned.s3UploadId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best effort, as the desktop's `CKy` is: the staged parts expire on their own.
        }
    }

    /** The account has no upload path for this session: the same footing as the desktop with its presign gate off. */
    private fun ConnectRpcException.refusesUploads(): Boolean =
        code == "unimplemented" || code == "permission_denied" || code == "failed_precondition" || httpCode == 404 || httpCode == 501

    companion object {
        /** The desktop's per-part bound (`g6p = 12e4`). */
        const val PART_TIMEOUT_MS = 120_000L

        /** The `<Code>…</Code>` of an S3 error body, as the desktop's `uploadToSignedUrl` reads it for its logs. */
        fun s3ErrorCode(body: String): String? = Regex("<Code>([A-Za-z0-9]{1,64})</Code>").find(body.take(500))?.groupValues?.get(1)
    }
}
