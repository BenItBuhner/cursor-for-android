package com.cursorforandroid.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.FakeVm
import com.cursorforandroid.data.repo.WorkspaceRepository
import com.cursorforandroid.data.repo.agentOn
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.RepoContents
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * The bytes that reached the decoder and came back as "…not encoded as a valid image format", one per format and
 * wrapper: each now decodes, or fails as a named [MediaProblem] — never with a decoder's message.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MediaLoaderDecodeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun raster(format: Bitmap.CompressFormat, width: Int = 40, height: Int = 24): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(0xFF3366AA.toInt())
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private val png by lazy { raster(Bitmap.CompressFormat.PNG) }

    /** A 1x1 GIF, the smallest there is. */
    private val gif = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )

    /** A 2x1 24-bit BMP. */
    private val bmp: ByteArray = run {
        val header = byteArrayOf(0x42, 0x4D, 62, 0, 0, 0, 0, 0, 0, 0, 54, 0, 0, 0)
        val info = byteArrayOf(40, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1, 0, 24, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0x13, 0x0B, 0, 0, 0x13, 0x0B, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val pixels = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        header + info + pixels
    }

    private val svg = """<?xml version="1.0" encoding="UTF-8"?>
        |<!-- exported by the agent -->
        |<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" viewBox="0 0 120 60"><rect width="120" height="60" fill="#3366aa"/><circle cx="30" cy="30" r="20" fill="#fff"/></svg>
        |""".trimMargin().toByteArray()

    private fun loader(files: AgentFileRepository? = null) =
        MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }), files = { files })

    private fun local(name: String, bytes: ByteArray): MediaRef.Local {
        val file = File(context.filesDir, "decode-test/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return MediaRef.Local(file.absolutePath)
    }

    /** Runs [block] on the main thread as a composition would, pumping the looper until it answers. */
    private fun <T> onMain(block: suspend () -> T): Result<T> {
        var result: Result<T>? = null
        CoroutineScope(Dispatchers.Main.immediate).launch { result = runCatching { block() } }
        val deadline = System.currentTimeMillis() + 20_000
        while (result == null) {
            check(System.currentTimeMillis() < deadline) { "the loader never answered" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result!!
    }

    private fun decode(loader: MediaLoader, ref: MediaRef): Result<Bitmap> = onMain { loader.image(ref, 400, 400) }

    private fun problem(result: Result<*>): MediaProblem {
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(MediaProblemException::class.java)
        return (error as MediaProblemException).problem
    }

    @Test
    fun `PNG, JPEG, WebP, GIF and BMP files decode`() {
        val loader = loader()
        for ((name, bytes) in listOf("a.png" to png, "a.jpg" to raster(Bitmap.CompressFormat.JPEG), "a.webp" to raster(Bitmap.CompressFormat.WEBP), "a.gif" to gif, "a.bmp" to bmp)) {
            val bitmap = decode(loader, local(name, bytes)).getOrThrow()
            assertThat(bitmap.width).isGreaterThan(0)
        }
    }

    @Test
    fun `an SVG is drawn, not refused`() {
        val bitmap = decode(loader(), local("diagram.svg", svg)).getOrThrow()
        assertThat(bitmap.width).isGreaterThan(0)
        assertThat(bitmap.getPixel(bitmap.width - 2, 2)).isNotEqualTo(0)
    }

    @Test
    fun `a picture wrapped in base64, a data URI or JSON is unwrapped before the decoder`() {
        val loader = loader()
        val base64 = Base64.getEncoder().encodeToString(png)
        val wrapped = mapOf(
            "b64.png" to Base64.getMimeEncoder().encode(png),
            "uri.png" to "data:image/png;base64,$base64".toByteArray(),
            "json.png" to "{\"content\":\"$base64\",\"mimeType\":\"image/png\"}".toByteArray(),
        )
        for ((name, bytes) in wrapped) {
            assertThat(decode(loader, local(name, bytes)).getOrThrow().width).isEqualTo(40)
        }
    }

    @Test
    fun `a Git LFS pointer is named as one`() {
        val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 1200000\n".toByteArray()
        assertThat(problem(decode(loader(), local("lfs.png", pointer)))).isEqualTo(MediaProblem.LfsPointer)
    }

    @Test
    fun `a text file named like a picture says it is text`() {
        assertThat(problem(decode(loader(), local("not-found.png", "404: Not Found".toByteArray())))).isEqualTo(MediaProblem.NotMedia("an image", null))
    }

    @Test
    fun `a PDF named like a picture says what it is`() {
        assertThat(problem(decode(loader(), local("doc.png", "%PDF-1.7\n%âãÏÓ\n".toByteArray())))).isEqualTo(MediaProblem.NotMedia("an image", FileFormat.PDF))
    }

    @Test
    fun `HEIC this renderer cannot draw is named as unsupported, not as an invalid format`() {
        val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic\u0000\u0000\u0000\u0000mif1heic".toByteArray(Charsets.ISO_8859_1) + ByteArray(256) { (it * 13).toByte() }
        val problem = problem(decode(loader(), local("photo.heic", heic)))
        assertThat(problem).isEqualTo(MediaProblem.Unsupported(FileFormat.HEIC))
        assertThat(problem.title).doesNotContain("valid image format")
    }

    @Test
    fun `a web page behind an image link is named as a page`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!DOCTYPE html><html><head><title>shot.png</title></head><body></body></html>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!DOCTYPE html><html><head><title>shot.png</title></head><body></body></html>"))
        val problem = problem(decode(loader(), MediaRef.Remote(server.url("/o/r/blob/main/shot.png").toString())))
        assertThat(problem).isEqualTo(MediaProblem.NotMedia("an image", FileFormat.HTML))
        assertThat(problem.title).isEqualTo("This link is a web page, not an image")
    }

    @Test
    fun `a base64 body behind a URL is unwrapped`() {
        repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(Base64.getEncoder().encodeToString(png))) }
        assertThat(decode(loader(), MediaRef.Remote(server.url("/shot.png").toString())).getOrThrow().width).isEqualTo(40)
    }

    @Test
    fun `a URL's refusal is named in the server's number, never a decoder's words`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val problem = problem(decode(loader(), MediaRef.Remote(server.url("/gone.png").toString())))
        assertThat(problem).isInstanceOf(MediaProblem.Failed::class.java)
        assertThat(problem.title).contains("404")
    }

    @Test
    fun `GitHub's blob page is read as the raw file`() {
        assertThat(RemoteUrls.fetchable("https://github.com/BenItBuhner/cursor-for-android/blob/main/screenshots/45_panel_workspace_files.png"))
            .isEqualTo("https://github.com/BenItBuhner/cursor-for-android/raw/main/screenshots/45_panel_workspace_files.png")
        assertThat(RemoteUrls.fetchable("https://example.com/a.png")).isEqualTo("https://example.com/a.png")
    }

    // -- the agent's workspace and repository -------------------------------------------------------------------

    private fun agentFiles(vm: FakeVm, capabilities: Capabilities, repo: (String) -> Result<RepoContents> = { Result.failure(java.io.IOException("not on GitHub")) }) = AgentFileRepository(
        workspace = WorkspaceRepository(vm, vm, capabilities = { capabilities }),
        repository = { _, _, path -> repo(path) },
        agent = { id -> agentOn(id) },
    )

    @Test
    fun `a workspace path in a reply is read from the agent's VM in Extended mode, its absolute path mapped onto the tree`() {
        val vm = FakeVm(mapOf("screenshots/45.png" to png))
        val loader = loader(agentFiles(vm, Capabilities.EXTENDED))
        val ref = MediaRef.parse("/workspace/screenshots/45.png", "bc-1")
        assertThat(ref).isEqualTo(MediaRef.Workspace("bc-1", "/workspace/screenshots/45.png"))
        assertThat(decode(loader, ref).getOrThrow().width).isEqualTo(40)
        assertThat(vm.reads).containsExactly("screenshots/45.png")
    }

    @Test
    fun `without Extended mode a workspace path is read from the repository at the agent's branch`() {
        val vm = FakeVm(emptyMap())
        var asked: String? = null
        val files = agentFiles(vm, Capabilities.DOCUMENTED) { path ->
            asked = path
            Result.success(RepoContents.File(com.cursorforandroid.domain.RepoFile(path, png, png.size.toLong())))
        }
        assertThat(decode(loader(files), MediaRef.parse("screenshots/45.png", "bc-1")).getOrThrow().width).isEqualTo(40)
        assertThat(asked).isEqualTo("screenshots/45.png")
        assertThat(vm.reads).isEmpty()
    }

    @Test
    fun `where neither can be read the figure says where the file is and why`() {
        val files = AgentFileRepository(
            workspace = WorkspaceRepository(FakeVm(emptyMap()), FakeVm(emptyMap()), capabilities = { Capabilities.DOCUMENTED }),
            repository = { _, _, _ -> Result.failure(java.io.IOException("unused")) },
            agent = { null },
        )
        val problem = problem(decode(loader(files), MediaRef.parse("/workspace/demo.png", "bc-1")))
        assertThat(problem).isEqualTo(MediaProblem.NotReadable("In the agent's workspace", WorkspaceRepository.NEEDS_EXTENDED_MODE))
    }
}
