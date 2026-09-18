package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.PromptUploadPart
import com.cursorforandroid.data.api.UploadedFile
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.UploadRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The uploads that start the moment a file is attached, the way Bennett asked the composer to match the desktop and
 * the web: pick, type, send — with the send waiting on nothing once every file is up, held only while one is not,
 * cancelled with a removed chip, retried in place when failed, and never run twice for a file that came back from a
 * draft with its reference.
 */
class AttachmentUploadsTest {

    private val storage = MockWebServer()

    /** Every `PUT` is held until [release] opens the gate for it, so an upload can be caught in flight. */
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val puts = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        storage.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                puts += path
                gates.getOrPut(path) { CountDownLatch(0) }.await(20, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200)
            }
        }
        storage.start()
    }

    @After
    fun tearDown() {
        gates.values.forEach { it.countDown() }
        storage.shutdown()
    }

    private fun hold(name: String) { gates[path(name)] = CountDownLatch(1) }
    private fun release(name: String) { gates[path(name)]?.countDown() }
    private fun path(name: String) = "/put/$name"

    private inner class FakeApi : PromptUploadApi {
        val presigns = CopyOnWriteArrayList<String>()
        val completes = CopyOnWriteArrayList<String>()
        val aborts = CopyOnWriteArrayList<Pair<String, String>>()
        var completion = PromptUploadCompletion.COMPLETED
        var failPresignOf: String? = null

        override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
            presigns += filename
            if (filename == failPresignOf) throw java.io.IOException("storage is away")
            val url = storage.url(path(filename)).toString()
            return PresignedPromptUpload("up-$filename", "s3-$filename", listOf(PromptUploadPart(1, url, 0L, contentLengthBytes)), partSizeBytes = contentLengthBytes, urlsExpireAtMs = null)
        }

        override suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion {
            completes += uploadId
            return completion
        }

        override suspend fun abort(uploadId: String, s3UploadId: String) {
            aborts += uploadId to s3UploadId
        }
    }

    private val api = FakeApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploads = AttachmentUploads(uploader = { PromptUploader(api, OkHttpClient(), partTimeoutMs = 0) }, scope = scope)

    private fun file(name: String, size: Int = 64) = PromptFile(ByteArray(size) { 7 }, name, "application/octet-stream")

    private suspend fun settled(id: String): AttachmentUploads.Status =
        withTimeout(10_000) { uploads.states.first { it[id] != null && it[id] !is AttachmentUploads.Status.Uploading }[id]!! }

    /** Until [name]'s bytes are on their way: presigned, and its `PUT` at the storage's door. */
    private suspend fun inFlight(name: String) = withTimeout(10_000) { while (path(name) !in puts) delay(10) }

    /** Pick, then send: the upload ran on the pick, and the send finds every file done — no upload waits on it. */
    @Test
    fun `a file picked earlier is up before the send, which then waits on nothing`() = runBlocking<Unit> {
        val spec = file("spec.pdf")
        uploads.start("f1", spec)
        val done = settled("f1") as AttachmentUploads.Status.Done
        assertThat(done.file.uploadId).isEqualTo("up-spec.pdf")
        assertThat(done.file.s3UploadId).isEqualTo("s3-spec.pdf")
        assertThat(api.presigns).containsExactly("spec.pdf")
        assertThat(api.completes).containsExactly("up-spec.pdf")
        assertThat(AttachmentUploads.isUploading(uploads.states.value, listOf("f1"))).isFalse()
        assertThat(AttachmentUploads.hint(uploads.states.value, listOf("f1"))).isNull()

        // The send: the same file named by its reference, at once, and nothing more on the wire.
        val named = withTimeoutOrNull(500) { uploads.awaitAll(listOf("f1" to spec)) }
        assertThat(named).isNotNull()
        assertThat(named!!.single().uploadId).isEqualTo("up-spec.pdf")
        assertThat(api.presigns).hasSize(1)
        assertThat(puts).hasSize(1)
    }

    /** While a file is still going up the send is held, and the composer says which of how many. */
    @Test
    fun `send is held while a chip is uploading, with the hint counting the one in flight`() = runBlocking<Unit> {
        hold("a.bin")
        uploads.start("f1", file("a.bin"))
        uploads.start("f2", file("b.bin"))
        uploads.start("f3", file("c.bin"))
        inFlight("a.bin")
        val ids = listOf("f1", "f2", "f3")
        assertThat(AttachmentUploads.isUploading(uploads.states.value, ids)).isTrue()
        assertThat(AttachmentUploads.hint(uploads.states.value, ids)).isEqualTo("Uploading 1 of 3…")
        // Nothing but the first has been presigned: one at a time, in the order attached.
        assertThat(api.presigns).containsExactly("a.bin")

        hold("b.bin")
        release("a.bin")
        settled("f1")
        inFlight("b.bin")
        assertThat(AttachmentUploads.hint(uploads.states.value, ids)).isEqualTo("Uploading 2 of 3…")
        assertThat(AttachmentUploads.hint(uploads.states.value, listOf("f2"))).isEqualTo("Uploading…")

        release("b.bin")
        settled("f2")
        settled("f3")
        assertThat(AttachmentUploads.isUploading(uploads.states.value, ids)).isFalse()
        assertThat(AttachmentUploads.hint(uploads.states.value, ids)).isNull()
        assertThat(uploads.awaitAll(ids.map { it to file(it) }).map { it.uploadId }).containsExactly("up-a.bin", "up-b.bin", "up-c.bin").inOrder()
    }

    /** A chip taken off mid-flight stops its upload and drops what was staged; one taken off when done is aborted too. */
    @Test
    fun `removing a chip cancels its upload and aborts the staged parts, done or not`() = runBlocking<Unit> {
        hold("big.mov")
        uploads.start("f1", file("big.mov"))
        inFlight("big.mov")
        uploads.cancel("f1")
        release("big.mov")
        withTimeout(10_000) { while (api.aborts.none { it.first == "up-big.mov" }) delay(20) }
        assertThat(api.aborts).contains("up-big.mov" to "s3-big.mov")
        assertThat(api.completes).doesNotContain("up-big.mov")
        assertThat(uploads.states.value).doesNotContainKey("f1")

        uploads.start("f2", file("done.pdf"))
        settled("f2")
        uploads.cancel("f2")
        withTimeout(10_000) { while (api.aborts.none { it.first == "up-done.pdf" }) delay(20) }
        assertThat(api.aborts).contains("up-done.pdf" to "s3-done.pdf")
        assertThat(uploads.states.value).isEmpty()
    }

    /** A file that did not get up keeps its chip, marked; the retry runs the upload alone and the send then finds it done. */
    @Test
    fun `a failed upload is retried in place, and a send meeting one retries it first`() = runBlocking<Unit> {
        api.failPresignOf = "flaky.zip"
        val zip = file("flaky.zip")
        uploads.start("f1", zip)
        val failed = settled("f1")
        assertThat(failed).isInstanceOf(AttachmentUploads.Status.Failed::class.java)
        assertThat((failed as AttachmentUploads.Status.Failed).message).contains("flaky.zip")
        assertThat(AttachmentUploads.isUploading(uploads.states.value, listOf("f1"))).isFalse()

        api.failPresignOf = null
        uploads.retry("f1")
        val done = settled("f1")
        assertThat(done).isInstanceOf(AttachmentUploads.Status.Done::class.java)
        assertThat(api.presigns).containsExactly("flaky.zip", "flaky.zip")

        // Failed again, and the send itself is what asks: it runs the upload once more rather than refusing.
        api.failPresignOf = "flaky.zip"
        uploads.cancel("f1")
        uploads.start("f1", zip)
        settled("f1")
        api.failPresignOf = null
        val named = uploads.awaitAll(listOf("f1" to zip))
        assertThat(named.single().uploadId).isEqualTo("up-flaky.zip")
    }

    /** A restored draft's file carries the reference its upload settled on before the restart: it is done from the start, nothing goes up again. */
    @Test
    fun `a file restored with its reference is not uploaded again`() = runBlocking<Unit> {
        val ref = UploadRef("up-earlier", "s3-earlier", "uuid-earlier")
        uploads.start("f1", file("earlier.pdf").withUpload(ref))
        val done = uploads.states.value["f1"]
        assertThat(done).isInstanceOf(AttachmentUploads.Status.Done::class.java)
        val named = (done as AttachmentUploads.Status.Done).file
        assertThat(named.uploadId).isEqualTo("up-earlier")
        assertThat(named.s3UploadId).isEqualTo("s3-earlier")
        assertThat(named.uuid).isEqualTo("uuid-earlier")
        assertThat(named.ref).isEqualTo(ref)
        assertThat(api.presigns).isEmpty()
        assertThat(puts).isEmpty()

        // The uploader's send-time path agrees: a file with a reference costs no call.
        val ensured = PromptUploader(api, OkHttpClient(), partTimeoutMs = 0).ensure(listOf(file("earlier.pdf").withUpload(ref), file("later.pdf")))
        assertThat(ensured.map { it.uploadId }).containsExactly("up-earlier", "up-later.pdf").inOrder()
        assertThat(api.presigns).containsExactly("later.pdf")
    }

    /** A send whose files went out — or into the queue — leaves nothing behind; a second start of the same id is a no-op. */
    @Test
    fun `forgetting sent files drops their state without aborting, and a repeated start is idempotent`() = runBlocking<Unit> {
        val pdf = file("once.pdf")
        uploads.start("f1", pdf)
        uploads.start("f1", pdf)
        settled("f1")
        assertThat(api.presigns).containsExactly("once.pdf")
        assertThat(uploads.ref("f1")).isEqualTo(UploadRef("up-once.pdf", "s3-once.pdf", uploads.uploaded("f1")!!.uuid))
        uploads.forget(listOf("f1"))
        delay(50)
        assertThat(uploads.states.value).isEmpty()
        assertThat(api.aborts).isEmpty()
    }

    @Test
    fun `an UploadedFile carries what the draft keeps of a completed upload`() {
        val ref = UploadRef("up-1", "s3-1", "uuid-1")
        val named = UploadedFile.of(file("x.pdf"), ref)
        assertThat(named.uploadId).isEqualTo("up-1")
        assertThat(named.s3UploadId).isEqualTo("s3-1")
        assertThat(named.uuid).isEqualTo("uuid-1")
        assertThat(named.filename).isEqualTo("x.pdf")
        assertThat(named.ref).isEqualTo(ref)
        assertThat(UploadedFile("y", "text/plain", uploadId = null, data = ByteArray(1)).ref).isNull()
    }

    @After
    fun stopScope() = scope.cancel()
}
