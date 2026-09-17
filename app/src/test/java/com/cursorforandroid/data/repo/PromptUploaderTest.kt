package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.PromptUploadPart
import com.cursorforandroid.domain.PromptFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The upload dance as the desktop's `prompt-upload-client.ts` performs it: presign, one `PUT` per part carrying that
 * part's slice and nothing else, then complete — with the staged upload aborted when a step fails.
 */
class PromptUploaderTest {

    private val storage = MockWebServer()

    @Before
    fun setUp() = storage.start()

    @After
    fun tearDown() = storage.shutdown()

    private class FakeApi(
        private val presigned: (String, String, Long) -> PresignedPromptUpload,
        private val completion: PromptUploadCompletion = PromptUploadCompletion.COMPLETED,
    ) : PromptUploadApi {
        val presigns = ArrayList<Triple<String, String, Long>>()
        val completes = ArrayList<Pair<String, String>>()
        val aborts = ArrayList<Pair<String, String>>()

        override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
            presigns += Triple(filename, mimeType, contentLengthBytes)
            return presigned(filename, mimeType, contentLengthBytes)
        }

        override suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion {
            completes += uploadId to s3UploadId
            return completion
        }

        override suspend fun abort(uploadId: String, s3UploadId: String) {
            aborts += uploadId to s3UploadId
        }
    }

    private fun parts(total: Int, partSize: Int): List<PromptUploadPart> {
        val parts = ArrayList<PromptUploadPart>()
        var offset = 0
        var number = 1
        while (offset < total) {
            val size = minOf(partSize, total - offset)
            parts += PromptUploadPart(number, storage.url("/part$number").toString(), offset.toLong(), size.toLong())
            number++
            offset += size
        }
        return parts
    }

    @Test
    fun `each part is PUT with its slice of the file and no content type, then the upload is completed and referenced`() = runBlocking<Unit> {
        val bytes = ByteArray(10) { it.toByte() }
        val api = FakeApi({ _, _, size -> PresignedPromptUpload("up-1", "s3-1", parts(size.toInt(), 4), 4L, null) })
        repeat(3) { storage.enqueue(MockResponse().setResponseCode(200)) }
        val progress = ArrayList<Pair<Long, Long>>()

        val document = PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(bytes, "trace.bin", "application/octet-stream")) { done, total -> progress += done to total }

        assertThat(api.presigns).containsExactly(Triple("trace.bin", "application/octet-stream", 10L))
        assertThat(document.uploadId).isEqualTo("up-1")
        assertThat(document.data).isNull()
        assertThat(document.filename).isEqualTo("trace.bin")
        assertThat(document.mimeType).isEqualTo("application/octet-stream")
        assertThat(document.uuid).isNotEmpty()
        val put1 = storage.takeRequest()
        assertThat(put1.method).isEqualTo("PUT")
        assertThat(put1.path).isEqualTo("/part1")
        assertThat(put1.getHeader("Content-Type")).isNull()
        assertThat(put1.body.readByteArray()).isEqualTo(bytes.copyOfRange(0, 4))
        assertThat(storage.takeRequest().body.readByteArray()).isEqualTo(bytes.copyOfRange(4, 8))
        assertThat(storage.takeRequest().body.readByteArray()).isEqualTo(bytes.copyOfRange(8, 10))
        assertThat(api.completes).containsExactly("up-1" to "s3-1")
        assertThat(api.aborts).isEmpty()
        assertThat(progress).containsExactly(0L to 10L, 4L to 10L, 8L to 10L, 10L to 10L).inOrder()
    }

    @Test
    fun `a part the storage refuses aborts the staged upload and names the file, with S3's code when it gave one`() = runBlocking<Unit> {
        val api = FakeApi({ _, _, size -> PresignedPromptUpload("up-2", "s3-2", parts(size.toInt(), 8), 8L, null) })
        storage.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>AccessDenied</Code><Message>nope</Message></Error>"))

        val failure = runCatching { PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(ByteArray(5), "a.zip", "application/zip")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PromptUploadException::class.java)
        assertThat((failure as PromptUploadException).filename).isEqualTo("a.zip")
        assertThat(failure).hasMessageThat().contains("a.zip")
        assertThat(failure).hasMessageThat().contains("403")
        assertThat(failure).hasMessageThat().contains("AccessDenied")
        assertThat(api.completes).isEmpty()
        assertThat(api.aborts).containsExactly("up-2" to "s3-2")
    }

    @Test
    fun `a completion that is not COMPLETED fails the file and aborts, as the desktop's PromptUploadError does`() = runBlocking<Unit> {
        val api = FakeApi({ _, _, size -> PresignedPromptUpload("up-3", "s3-3", parts(size.toInt(), 8), 8L, null) }, completion = PromptUploadCompletion.SIZE_MISMATCH)
        storage.enqueue(MockResponse().setResponseCode(200))

        val failure = runCatching { PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(ByteArray(5), "a.zip", "application/zip")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PromptUploadException::class.java)
        assertThat(failure).hasMessageThat().contains("size mismatch")
        assertThat(api.aborts).containsExactly("up-3" to "s3-3")
    }

    @Test
    fun `an account with no upload path carries the bytes inline, the desktop's footing with its presign gate off`() = runBlocking<Unit> {
        val api = FakeApi({ _, _, _ -> throw ConnectRpcException(404, "unimplemented", "unknown method PresignPromptUpload") })
        val bytes = byteArrayOf(9, 8, 7)

        val document = PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(bytes, "notes.txt", "text/plain"))

        assertThat(document.uploadId).isNull()
        assertThat(document.data).isEqualTo(bytes)
        assertThat(document.filename).isEqualTo("notes.txt")
        assertThat(storage.requestCount).isEqualTo(0)
        assertThat(api.aborts).isEmpty()
    }

    @Test
    fun `any other refusal of the presign fails the file rather than sending its bytes inline`() = runBlocking<Unit> {
        val api = FakeApi({ _, _, _ -> throw ConnectRpcException(500, "internal", "storage unavailable") })

        val failure = runCatching { PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(byteArrayOf(1), "a.txt", "text/plain")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PromptUploadException::class.java)
        assertThat(failure).hasMessageThat().contains("storage unavailable")
    }

    @Test
    fun `an empty file is never staged, it goes inline like the desktop's size-zero case`() = runBlocking<Unit> {
        val api = FakeApi({ _, _, _ -> error("not asked") })

        val document = PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(PromptFile(ByteArray(0), "empty.log", "text/plain"))

        assertThat(document.uploadId).isNull()
        assertThat(document.data).isEqualTo(ByteArray(0))
        assertThat(api.presigns).isEmpty()
    }

    @Test
    fun `several files go up one after another, each reported under its own index`() = runBlocking<Unit> {
        var next = 0
        val api = FakeApi({ _, _, size -> PresignedPromptUpload("up-${++next}", "s3-$next", parts(size.toInt(), 8), 8L, null) })
        repeat(2) { storage.enqueue(MockResponse().setResponseCode(200)) }
        val seen = ArrayList<Triple<Int, Long, Long>>()

        val documents = PromptUploader(api, OkHttpClient(), partTimeoutMs = 0L).upload(
            listOf(PromptFile(ByteArray(3), "a.txt", "text/plain"), PromptFile(ByteArray(2), "b.txt", "text/plain")),
        ) { index, done, total -> seen += Triple(index, done, total) }

        assertThat(documents.map { it.uploadId }).containsExactly("up-1", "up-2").inOrder()
        assertThat(seen).containsExactly(Triple(0, 0L, 3L), Triple(0, 3L, 3L), Triple(1, 0L, 2L), Triple(1, 2L, 2L)).inOrder()
    }

    @Test
    fun `the S3 error code is read off the body the way the desktop's uploadToSignedUrl reads it`() {
        assertThat(PromptUploader.s3ErrorCode("<Error><Code>EntityTooLarge</Code></Error>")).isEqualTo("EntityTooLarge")
        assertThat(PromptUploader.s3ErrorCode("plain text")).isNull()
        assertThat(PromptUploader.PART_TIMEOUT_MS).isEqualTo(120_000L)
    }
}
