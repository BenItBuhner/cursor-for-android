package com.cursorforandroid.ui.media

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.media.MediaProblem
import com.cursorforandroid.data.media.MediaProblemException
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.MediaRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saving the viewer's media to the gallery, end to end through the real loader, gallery writer and MediaStore calls:
 * a file already on this device goes straight in; a fetched one fills the button's ring as it comes; a second tap
 * while one is running starts nothing (the double tap that once corrupted recordings); a failure offers Retry; a
 * dropped connection picks up where it stopped; the bytes decide the type and extension; nothing partial is left.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaSavesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMediaStore
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outcomes = mutableListOf<MediaSaves.Outcome>()
    private var artifactUrls: (String) -> String = { error("no artifact expected: $it") }
    private val artifactUrlCalls = AtomicInteger()
    private val api = object : FakeCursorApi() {
        override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto {
            artifactUrlCalls.incrementAndGet()
            return DownloadArtifactResponseDto(url = artifactUrls(path))
        }
    }

    @Before
    fun setUp() {
        store = FakeMediaStore.install()
        File(context.cacheDir, MediaLoader.MEDIA_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun saves(http: OkHttpClient = OkHttpClient(), sdk: Int = Build.VERSION.SDK_INT): Pair<MediaSaves, MediaLoader> {
        val loader = MediaLoader(context, http, ArtifactRepository(api = { api }))
        val saves = MediaSaves(scope, loader, GallerySaver(context, sdk))
        saves.outcomes.onEach { outcomes += it }.launchIn(scope)
        return saves to loader
    }

    /** Idles the main looper, where the saves' states land, until [condition] holds. */
    private fun until(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    private fun localFile(name: String, bytes: ByteArray): String {
        val file = File(context.filesDir, "saves-test/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return "file://${file.absolutePath}"
    }

    private fun artifact(name: String) = MediaRef.parse("/opt/cursor/artifacts/$name", AGENT)

    @Test
    fun `a file already on this device saves at once, with no request`() {
        val (saves, _) = saves()
        val src = localFile("figure.png", PNG)
        val ref = MediaRef.parse(src, AGENT)

        assertThat(saves.save(ref, MediaEntry(src, MediaEntry.Kind.Image, fileName = "figure.png"))).isEqualTo(MediaSaves.Start.Started)
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat((saves.state(ref) as MediaSaves.State.Saved).message).isEqualTo("Saved to Pictures")
        assertThat(server.requestCount).isEqualTo(0)
        val row = store.published().single()
        assertThat(row.bytes.toByteArray()).isEqualTo(PNG)
        assertThat(row.mimeType).isEqualTo("image/png")
        assertThat(row.displayName).isEqualTo("figure.png")
        assertThat(row.relativePath).isEqualTo("${Environment.DIRECTORY_PICTURES}/${GallerySaver.GalleryFolder}")
        assertThat(row.collection).isEqualTo(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        assertThat(outcomes.single().saved).isTrue()
    }

    @Test
    fun `a recording already copied for the player or the share sheet saves from that copy, with no second download`() {
        server.enqueue(MockResponse().setBody(Buffer().write(MP4)))
        server.start()
        artifactUrls = { server.url("/clip.mp4").toString() }
        val (saves, loader) = saves()
        val ref = artifact("clip.mp4")
        assertThat(runBlockingOnMain { loader.file(ref, "clip.mp4") }.readBytes()).isEqualTo(MP4)
        assertThat(server.requestCount).isEqualTo(1)

        saves.save(ref, MediaEntry("/opt/cursor/artifacts/clip.mp4", MediaEntry.Kind.Video, fileName = "clip.mp4"))
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat(server.requestCount).isEqualTo(1)
        val row = store.published().single()
        assertThat(row.bytes.toByteArray()).isEqualTo(MP4)
        assertThat(row.mimeType).isEqualTo("video/mp4")
        assertThat(row.collection).isEqualTo(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        assertThat((saves.state(ref) as MediaSaves.State.Saved).message).isEqualTo("Saved to Movies")
    }

    @Test
    fun `a fresh download fills the ring with its bytes, then saves`() {
        val video = bigMp4(400_000)
        val gate = GatedBody(video, gateAt = video.size / 2)
        val (saves, _) = saves(http = OkHttpClient.Builder().addInterceptor(gate).build())
        artifactUrls = { "https://blob.example/recording.mp4?sig=1" }
        val ref = artifact("recording.mp4")

        saves.save(ref, MediaEntry("/opt/cursor/artifacts/recording.mp4", MediaEntry.Kind.Video, fileName = "recording.mp4"))
        // Held at half the bytes: the ring is at half the download's share of it.
        until { (saves.state(ref) as? MediaSaves.State.Working)?.fraction?.let { it >= 0.44f } == true }
        val held = (saves.state(ref) as MediaSaves.State.Working).fraction!!
        assertThat(held).isWithin(0.01f).of(0.45f)
        assertThat(store.published()).isEmpty()

        gate.release()
        until { saves.state(ref) is MediaSaves.State.Saved }
        assertThat(store.published().single().bytes.toByteArray()).isEqualTo(video)
        assertThat(store.rows.values.none { it.pending }).isTrue()
    }

    @Test
    fun `without a size the ring spins rather than guessing`() {
        val gate = GatedBody(bigMp4(100_000), gateAt = 10_000, knownLength = false)
        val (saves, _) = saves(http = OkHttpClient.Builder().addInterceptor(gate).build())
        artifactUrls = { "https://blob.example/stream.mp4" }
        val ref = artifact("stream.mp4")

        saves.save(ref, MediaEntry("/opt/cursor/artifacts/stream.mp4", MediaEntry.Kind.Video, fileName = "stream.mp4"))
        until { gate.served.get() > 0 }
        until { saves.state(ref) == MediaSaves.State.Working(null) }
        gate.release()
        until { saves.state(ref) is MediaSaves.State.Saved }
    }

    @Test
    fun `a second tap while a recording is still coming down starts nothing`() {
        val video = bigMp4(300_000)
        val gate = GatedBody(video, gateAt = 100_000)
        val (saves, _) = saves(http = OkHttpClient.Builder().addInterceptor(gate).build())
        artifactUrls = { "https://blob.example/walkthrough.mp4" }
        val ref = artifact("walkthrough.mp4")
        val entry = MediaEntry("/opt/cursor/artifacts/walkthrough.mp4", MediaEntry.Kind.Video, fileName = "walkthrough.mp4")

        assertThat(saves.save(ref, entry)).isEqualTo(MediaSaves.Start.Started)
        until { gate.served.get() > 0 }
        assertThat(saves.save(ref, entry)).isEqualTo(MediaSaves.Start.AlreadyRunning)
        assertThat(saves.save(ref, entry)).isEqualTo(MediaSaves.Start.AlreadyRunning)
        gate.release()
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat(gate.requests.get()).isEqualTo(1)
        assertThat(store.rows).hasSize(1)
        assertThat(store.published().single().bytes.toByteArray()).isEqualTo(video)
        assertThat(outcomes).hasSize(1)
    }

    /**
     * The flaky saves themselves: two readers of one item at once (Save and Share, or the second tap before this
     * fix) each opened the same `.part` with truncation, so the first came back with a file holed by the second's
     * truncation and the second found its `.part` renamed away. Now the second waits for the first's file.
     */
    @Test
    fun `two readers of one recording at once fetch it once and both get it whole`() {
        val video = bigMp4(300_000)
        val gate = GatedBody(video, gateAt = 150_000)
        val (_, loader) = saves(http = OkHttpClient.Builder().addInterceptor(gate).build())
        artifactUrls = { "https://blob.example/demo.mp4" }
        val ref = artifact("demo.mp4")

        val first = scope.async { loader.file(ref, "demo.mp4") }
        until { gate.served.get() > 0 }
        val second = scope.async { loader.file(ref, "demo.mp4") }
        shadowOf(Looper.getMainLooper()).idle()
        gate.release()
        until { first.isCompleted && second.isCompleted }

        assertThat(gate.requests.get()).isEqualTo(1)
        assertThat(first.getCompleted().readBytes()).isEqualTo(video)
        assertThat(second.getCompleted()).isEqualTo(first.getCompleted())
        assertThat(File(context.cacheDir, MediaLoader.MEDIA_DIR).listFiles()!!.none { it.name.endsWith(".part") }).isTrue()
    }

    @Test
    fun `a failure says why and offers Retry, and the retry saves`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(Buffer().write(MP4)))
        server.start()
        val (saves, _) = saves()
        val src = server.url("/gone.mp4").toString()
        val ref = MediaRef.parse(src, AGENT)
        val entry = MediaEntry(src, MediaEntry.Kind.Video, fileName = "gone.mp4")

        saves.save(ref, entry)
        until { saves.state(ref) is MediaSaves.State.Failed }
        assertThat((saves.state(ref) as MediaSaves.State.Failed).message).isEqualTo("The download answered 404.")
        assertThat(store.rows).isEmpty()
        val failure = outcomes.single()
        assertThat(failure.saved).isFalse()
        assertThat(failure.message).isEqualTo("Couldn't save: The download answered 404.")

        failure.retry!!.invoke()
        until { saves.state(ref) is MediaSaves.State.Saved }
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(store.published().single().bytes.toByteArray()).isEqualTo(MP4)
    }

    @Test
    fun `a dropped connection picks up where the bytes stopped`() {
        val video = bigMp4(256 * 1024)
        val ranges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                synchronized(ranges) { ranges += range }
                if (range == null) return MockResponse().setBody(Buffer().write(video)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${video.size - 1}/${video.size}")
                    .setBody(Buffer().write(video, from, video.size - from))
            }
        }
        server.start()
        val (saves, _) = saves()
        val src = server.url("/long.mp4").toString()
        val ref = MediaRef.parse(src, AGENT)

        saves.save(ref, MediaEntry(src, MediaEntry.Kind.Video, fileName = "long.mp4"))
        until { saves.state(ref) !is MediaSaves.State.Working }

        assertThat(saves.state(ref)).isInstanceOf(MediaSaves.State.Saved::class.java)
        assertThat(store.published().single().bytes.toByteArray()).isEqualTo(video)
        assertThat(ranges.first()).isNull()
        val resumedFrom = ranges.last()!!.removePrefix("bytes=").removeSuffix("-").toInt()
        assertThat(resumedFrom).isGreaterThan(0)
    }

    @Test
    fun `a stale presigned link is asked for again once`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(Buffer().write(MP4)))
        server.start()
        var issued = 0
        artifactUrls = { server.url("/signed-${++issued}.mp4").toString() }
        val (saves, _) = saves()
        val ref = artifact("stale.mp4")

        saves.save(ref, MediaEntry("/opt/cursor/artifacts/stale.mp4", MediaEntry.Kind.Video, fileName = "stale.mp4"))
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat(artifactUrlCalls.get()).isEqualTo(2)
        assertThat(server.takeRequest().path).isEqualTo("/signed-1.mp4")
        assertThat(server.takeRequest().path).isEqualTo("/signed-2.mp4")
    }

    @Test
    fun `a gallery write that fails leaves no entry behind`() {
        val (saves, _) = saves()
        store.failWriteAfter = 100
        val src = localFile("clip.mp4", bigMp4(50_000))
        val ref = MediaRef.parse(src, AGENT)

        saves.save(ref, MediaEntry(src, MediaEntry.Kind.Video, fileName = "clip.mp4"))
        until { saves.state(ref) is MediaSaves.State.Failed }
        assertThat(store.rows).isEmpty()

        store.failWriteAfter = null
        store.refuseUpdates = true
        saves.save(ref, MediaEntry(src, MediaEntry.Kind.Video, fileName = "clip.mp4"))
        until { outcomes.size == 2 }
        assertThat(saves.state(ref)).isInstanceOf(MediaSaves.State.Failed::class.java)
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `a web page behind a video link is refused, not saved as junk`() {
        server.enqueue(MockResponse().setBody("<!doctype html><html><body>Sign in</body></html>"))
        server.start()
        val (saves, _) = saves()
        val src = server.url("/clip.mp4").toString()
        val ref = MediaRef.parse(src, AGENT)

        saves.save(ref, MediaEntry(src, MediaEntry.Kind.Video, fileName = "clip.mp4"))
        until { saves.state(ref) is MediaSaves.State.Failed }

        assertThat((saves.state(ref) as MediaSaves.State.Failed).message).isEqualTo("This link is a web page, not a video")
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `a type the collection refuses goes under Downloads`() {
        val (saves, _) = saves()
        store.refuseCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val src = localFile("logo.png", PNG)
        val ref = MediaRef.parse(src, AGENT)

        saves.save(ref, MediaEntry(src, MediaEntry.Kind.Image, fileName = "logo.png"))
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat((saves.state(ref) as MediaSaves.State.Saved).message).isEqualTo("Saved to Downloads")
        assertThat(store.published().single().relativePath).isEqualTo("${Environment.DIRECTORY_DOWNLOADS}/${GallerySaver.GalleryFolder}")
    }

    @Test
    fun `the bytes decide a recording's type and extension`() {
        fun target(name: String, head: ByteArray, mime: String? = null, kind: MediaEntry.Kind = MediaEntry.Kind.Video) = GallerySaver.targetFor(head, name, mime, kind)

        assertThat(target("clip.mp4", MP4)).isEqualTo(GallerySaver.Target("clip.mp4", "video/mp4", GallerySaver.Folder.Movies))
        assertThat(target("clip.mov", MP4)).isEqualTo(GallerySaver.Target("clip.mp4", "video/mp4", GallerySaver.Folder.Movies))
        assertThat(target("recording", MP4, mime = "video/*")).isEqualTo(GallerySaver.Target("recording.mp4", "video/mp4", GallerySaver.Folder.Movies))
        assertThat(target("screen.webm", WEBM)).isEqualTo(GallerySaver.Target("screen.webm", "video/webm", GallerySaver.Folder.Movies))
        assertThat(target("take.mov", MOV).mimeType).isEqualTo("video/quicktime")
        assertThat(target("shot", PNG, kind = MediaEntry.Kind.Image)).isEqualTo(GallerySaver.Target("shot.png", "image/png", GallerySaver.Folder.Pictures))
        assertThat(target("photo.png", JPEG, kind = MediaEntry.Kind.Image).displayName).isEqualTo("photo.jpg")
        // Bytes this app cannot tell: the declared type, then the name's.
        val unknown = ByteArray(64) { (it * 7 + 3).toByte() }.also { it[0] = 0x00; it[1] = 0x01 }
        assertThat(target("clip", unknown, mime = "video/x-flv")).isEqualTo(GallerySaver.Target("clip", "video/x-flv", GallerySaver.Folder.Movies))
        assertThat(target("clip.mkv", unknown)).isEqualTo(GallerySaver.Target("clip.mkv", "video/x-matroska", GallerySaver.Folder.Movies))
        // Not media at all.
        assertThat(problemOf { target("clip.mp4", "{\"error\":\"expired\"}".toByteArray()) }).isEqualTo(MediaProblem.NotMedia("a video", null))
        assertThat(problemOf { target("clip.mp4", LFS) }).isEqualTo(MediaProblem.LfsPointer)
    }

    @Test
    fun `before Android 10 the storage permission is asked for, then the file goes in whole and is scanned`() {
        val (saves, _) = saves(sdk = Build.VERSION_CODES.P)
        val src = localFile("demo.mp4", MP4)
        val ref = MediaRef.parse(src, AGENT)
        val entry = MediaEntry(src, MediaEntry.Kind.Video, fileName = "demo.mp4")

        assertThat(saves.save(ref, entry)).isEqualTo(MediaSaves.Start.NeedsPermission)
        assertThat(saves.state(ref)).isEqualTo(MediaSaves.State.Idle)
        saves.refuse(ref, entry, GallerySaver.PERMISSION_NEEDED)
        until { outcomes.isNotEmpty() }
        assertThat(saves.state(ref)).isEqualTo(MediaSaves.State.Failed(GallerySaver.PERMISSION_NEEDED))
        assertThat(outcomes.single().retry).isNotNull()

        shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), GallerySaver.GalleryFolder)
        folder.mkdirs()
        File(folder, "demo.mp4").writeBytes(byteArrayOf(1))
        assertThat(saves.save(ref, entry)).isEqualTo(MediaSaves.Start.Started)
        until { saves.state(ref) is MediaSaves.State.Saved }

        assertThat(File(folder, "demo (1).mp4").readBytes()).isEqualTo(MP4)
        assertThat(folder.listFiles()!!.map { it.name }).containsExactly("demo.mp4", "demo (1).mp4")
        assertThat(store.rows).isEmpty()
    }

    private fun problemOf(block: () -> Unit): MediaProblem? = try {
        block()
        null
    } catch (e: MediaProblemException) {
        e.problem
    }

    /** Runs [block] on the main looper, idling it until it answers. */
    private fun <T> runBlockingOnMain(block: suspend () -> T): T {
        val result = scope.async { block() }
        until { result.isCompleted }
        return result.getCompleted()
    }

    /**
     * Answers every request with [bytes], sending the first [gateAt] and then holding the rest until [release] —
     * a download caught halfway, for as long as a test needs to look at it. [knownLength] false leaves the size unsaid.
     */
    private class GatedBody(private val bytes: ByteArray, private val gateAt: Int, private val knownLength: Boolean = true) : Interceptor {
        private val gate = CountDownLatch(1)
        val requests = AtomicInteger()
        val served = AtomicInteger()

        fun release() = gate.countDown()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests.incrementAndGet()
            val stream = object : InputStream() {
                var position = 0
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (position >= bytes.size) return -1
                    if (position >= gateAt) check(gate.await(30, TimeUnit.SECONDS)) { "gate never opened" }
                    val end = if (position < gateAt) minOf(gateAt, position + len) else minOf(bytes.size, position + len)
                    val n = end - position
                    System.arraycopy(bytes, position, b, off, n)
                    position = end
                    served.addAndGet(n)
                    return n
                }
            }
            val body = object : ResponseBody() {
                override fun contentType() = "video/mp4".toMediaType()
                override fun contentLength(): Long = if (knownLength) bytes.size.toLong() else -1L
                override fun source(): BufferedSource = stream.source().buffer()
            }
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }
    }

    private companion object {
        const val AGENT = "bc-saves-test"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(120) { it.toByte() }
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60)
        val MP4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isomiso2".toByteArray() + ByteArray(2_000) { (it % 251).toByte() }
        val MOV = byteArrayOf(0, 0, 0, 0x14) + "ftypqt  ".toByteArray() + byteArrayOf(0, 0, 2, 0) + "qt  ".toByteArray() + ByteArray(100)
        val WEBM = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + "....webm".toByteArray() + ByteArray(100)
        val LFS = "version https://git-lfs.github.com/spec/v1\noid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\nsize 12345\n".toByteArray()

        fun bigMp4(size: Int): ByteArray = MP4.copyOf(size).also { for (i in MP4.size until size) it[i] = (i % 253).toByte() }
    }
}
