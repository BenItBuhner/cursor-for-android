package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The reads behind a `/cursor/stores/…` path in a reply: which store the Project's coordinator owns, found once and
 * kept; a picture's presigned URL, kept until it is about to expire and dropped when a fetch finds it dead; a
 * document's text, kept on disk so it opens again without the network; and every one of them refused, by name,
 * without the account.
 */
class StoreFileRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    private inner class RecordingStore : AgentStoreApi {
        val calls = mutableListOf<String>()
        var storeId: String? = "st-proj"
        var expiresAt: Long? = null
        var text = "# Project UI parity\n\nThe tabbed panel."
        /** Presigned URLs point at the local server when the test serves bytes, at a made-up host otherwise. */
        var serve = false
        override suspend fun storeFor(sourceId: String): String? { calls += "store:$sourceId"; return storeId }
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = emptyList()
        override suspend fun readFile(storeId: String, relativePath: String): String { calls += "read:$storeId:$relativePath"; return text }
        override suspend fun presignRead(requesterId: String, storeId: String, relativePath: String): PresignedStoreRead? {
            calls += "presign:$requesterId:$storeId:$relativePath"
            val url = if (serve) server.url("/signed/$relativePath?n=${calls.size}").toString() else "https://files.cursor.sh/$storeId/$relativePath?n=${calls.size}"
            return PresignedStoreRead(relativePath, url, expiresAt)
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private val fixture = CoordinatorFixtures.json("store_paths_message.json")
    private val store = fixture.getValue("storeId").jsonPrimitive.content
    private val image = MediaRef.parse(fixture.getValue("imagePath").jsonPrimitive.content, "bc-worker") as MediaRef.Store
    private val document = MediaRef.parse(fixture.getValue("documentPath").jsonPrimitive.content, store) as MediaRef.Store

    private var now = 1_700_000_000_000L
    private val api = RecordingStore()
    private var capabilities = Capabilities.EXTENDED

    private fun cache() = JsonDiskCache(File(folder.root, "json"), nowProvider = { now }, dispatcher = Dispatchers.Unconfined)
    private fun repository(api: AgentStoreApi? = this.api, cache: JsonDiskCache? = cache(), maxBlobBytes: Long = StoreFileRepository.MAX_BLOB_BYTES) =
        StoreFileRepository(api = { api }, capabilities = { capabilities }, cache = cache, blobs = File(folder.root, "blobs"), http = OkHttpClient(), now = { now }, maxBlobBytes = maxBlobBytes)

    private fun png(): ByteArray = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()

    @Test
    fun `a picture's bytes are fetched once through the presigned URL and kept on the device`() = runBlocking<Unit> {
        api.serve = true
        val bytes = png()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val files = repository()
        assertThat(files.readBytes(image)).isEqualTo(bytes)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().path).startsWith("/signed/media/ui-parity/tab-landscape-icon-only-project.png")
        // The second read, and a new session's, come from the disk.
        assertThat(files.readBytes(image)).isEqualTo(bytes)
        assertThat(repository(cache = cache()).readBytes(image)).isEqualTo(bytes)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(api.calls.count { it.startsWith("presign:") }).isEqualTo(1)
        // Sign-out drops the files kept.
        files.resetAll()
        assertThat(File(folder.root, "blobs").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a presigned URL found dead is asked for again once, and the store's other answers are failures with their code`() = runBlocking<Unit> {
        api.serve = true
        api.expiresAt = now + 15 * 60_000L
        server.enqueue(MockResponse().setResponseCode(403).setBody("expired"))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        val files = repository()
        assertThat(files.readBytes(image)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(api.calls.count { it.startsWith("presign:") }).isEqualTo(2)
        assertThat(server.takeRequest().path).endsWith("?n=2")
        assertThat(server.takeRequest().path).endsWith("?n=3")

        val other = MediaRef.Store(store, "media/other.png", store)
        server.enqueue(MockResponse().setResponseCode(500))
        val failure = assertThrows(IOException::class.java) { runBlocking { files.readBytes(other) } }
        assertThat(failure).hasMessageThat().contains("500")
        // Twice dead is gone.
        val gone = MediaRef.Store(store, "media/gone.png", store)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(assertThrows(IOException::class.java) { runBlocking { files.readBytes(gone) } }).hasMessageThat().isEqualTo(StoreFileRepository.NO_FILE)
    }

    @Test
    fun `the files kept are bounded, the least recently drawn going first`() = runBlocking<Unit> {
        api.serve = true
        val files = repository(maxBlobBytes = 25)
        val refs = (1..3).map { MediaRef.Store(store, "media/$it.png", store) }
        for (ref in refs) {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10) { 1 })))
            files.readBytes(ref)
            now += 1_000
        }
        val kept = File(folder.root, "blobs").listFiles { f -> f.isFile }!!.map { it.name }
        assertThat(kept).hasSize(2)
        assertThat(kept.none { it.endsWith("1.png") }).isTrue()
        // Drawing the first again fetches it again; the bound holds.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10) { 1 })))
        files.readBytes(refs[0])
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(File(folder.root, "blobs").listFiles { f -> f.isFile }!!.size).isEqualTo(2)
    }

    @Test
    fun `a picture's bytes are behind the presigned URL, asked for as the chat and kept until it is about to expire`() = runBlocking<Unit> {
        api.expiresAt = now + 15 * 60_000L
        val files = repository()
        val url = files.downloadUrl(image)
        assertThat(url).startsWith("https://files.cursor.sh/st-proj/media/ui-parity/tab-landscape-icon-only-project.png")
        assertThat(files.downloadUrl(image)).isEqualTo(url)
        assertThat(api.calls).containsExactly("store:$store", "presign:bc-worker:st-proj:media/ui-parity/tab-landscape-icon-only-project.png").inOrder()

        // A minute before it expires it is not handed out again; a fresh one is asked for.
        now += 14 * 60_000L + 1
        api.expiresAt = now + 15 * 60_000L
        assertThat(files.downloadUrl(image)).isNotEqualTo(url)
        assertThat(api.calls.count { it.startsWith("presign:") }).isEqualTo(2)
        // The store itself was found once: the second presign did not list the stores again.
        assertThat(api.calls.count { it.startsWith("store:") }).isEqualTo(1)

        // A fetch that found the URL dead drops it; the next ask is a new one.
        val third = files.downloadUrl(image)
        files.invalidate(image)
        assertThat(files.downloadUrl(image)).isNotEqualTo(third)
    }

    @Test
    fun `which store a Project owns is kept on disk, so a new session does not list the stores again`() = runBlocking<Unit> {
        val first = repository()
        assertThat(first.storeId(store)).isEqualTo("st-proj")
        val second = repository(cache = cache())
        assertThat(second.storeId(store)).isEqualTo("st-proj")
        assertThat(api.calls.count { it.startsWith("store:") }).isEqualTo(1)
    }

    @Test
    fun `a Project with no store says so, is not asked about on every figure, and is asked again later`() = runBlocking<Unit> {
        api.storeId = null
        val files = repository()
        val failure = assertThrows(IOException::class.java) { runBlocking { files.downloadUrl(image) } }
        assertThat(failure).hasMessageThat().isEqualTo(StoreFileRepository.NO_STORE)
        assertThrows(IOException::class.java) { runBlocking { files.readText(document) } }
        assertThat(api.calls.count { it.startsWith("store:") }).isEqualTo(1)
        assertThat(api.calls.none { it.startsWith("presign:") || it.startsWith("read:") }).isTrue()

        now += 7 * 60 * 60_000L
        api.storeId = "st-proj"
        assertThat(repository(cache = cache()).storeId(store)).isEqualTo("st-proj")
        assertThat(api.calls.count { it.startsWith("store:") }).isEqualTo(2)
    }

    @Test
    fun `a document once read opens from the disk, and a refresh reads it again`() = runBlocking<Unit> {
        val files = repository()
        assertThat(files.readText(document)).isEqualTo("# Project UI parity\n\nThe tabbed panel.")
        api.text = "# Project UI parity\n\nRevised."
        assertThat(files.readText(document)).isEqualTo("# Project UI parity\n\nThe tabbed panel.")
        assertThat(repository(cache = cache()).cachedText(document)).isEqualTo("# Project UI parity\n\nThe tabbed panel.")
        assertThat(files.readText(document, refresh = true)).isEqualTo("# Project UI parity\n\nRevised.")
        assertThat(api.calls.filter { it.startsWith("read:") }).hasSize(2)
        assertThat(api.calls.first { it.startsWith("read:") }).isEqualTo("read:st-proj:docs/project-ui-parity-spec.md")
    }

    @Test
    fun `without the account every read says what would make it possible, and asks nothing`() = runBlocking<Unit> {
        capabilities = Capabilities.DOCUMENTED
        val files = repository()
        assertThat(files.available()).isFalse()
        assertThat(assertThrows(IOException::class.java) { runBlocking { files.downloadUrl(image) } }).hasMessageThat().isEqualTo(StoreFileRepository.NOT_AVAILABLE)
        assertThat(assertThrows(IOException::class.java) { runBlocking { files.readText(document) } }).hasMessageThat().isEqualTo(StoreFileRepository.NOT_AVAILABLE)
        assertThat(api.calls).isEmpty()
        assertThat(repository(api = null).available()).isFalse()

        // A document kept from an Extended session still opens once the setting is off.
        capabilities = Capabilities.EXTENDED
        val cache = cache()
        repository(cache = cache).readText(document)
        capabilities = Capabilities.DOCUMENTED
        assertThat(repository(cache = cache).readText(document)).isEqualTo("# Project UI parity\n\nThe tabbed panel.")
    }
}
