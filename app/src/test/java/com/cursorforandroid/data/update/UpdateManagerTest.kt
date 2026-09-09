package com.cursorforandroid.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.UpdatePhase
import com.cursorforandroid.domain.UpdateState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The update flow against a mock GitHub: the release list, the APK bytes and the checksums come from a
 * [MockWebServer]; the device is a [FakeUpdatePlatform]. Robolectric only for [PreferencesStore] and `Intent`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpdateManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferencesStore
    private lateinit var platform: FakeUpdatePlatform
    private lateinit var downloads: File
    private lateinit var cache: UpdateCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_788_900_000_000L

    /** The stable APK and the release candidate served by the mock; the digests in the release list match them. */
    private val stableApk = ByteArray(200_000) { (it % 199).toByte() }
    private val rcApk = ByteArray(150_000) { (it % 97).toByte() }
    private var stableApkBytes: ByteArray = stableApk
    // Appended from MockWebServer's dispatcher thread while tests assert; copy-on-write keeps the reads race-free.
    private val listRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val downloadRequests = CopyOnWriteArrayList<String>()
    private var releasesEtag = "W/\"list-1\""
    /** When set, the release list is held back until the latch opens, keeping a check (and the lock) in flight. */
    @Volatile
    private var listGate: CountDownLatch? = null

    @Before
    fun setUp() {
        prefs = PreferencesStore(context)
        platform = FakeUpdatePlatform()
        downloads = folder.newFolder("updates")
        cache = UpdateCache(JsonDiskCache(folder.newFolder("update-check"), dispatcher = Dispatchers.Unconfined))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    path.startsWith("/repos/${GitHubFixtures.OWNER_REPO}/releases") -> {
                        listRequests += request
                        listGate?.await(10, TimeUnit.SECONDS)
                        if (request.getHeader("If-None-Match") == releasesEtag) {
                            MockResponse().setResponseCode(304)
                        } else {
                            MockResponse().setHeader("ETag", releasesEtag).setBody(
                                GitHubFixtures.releasesJson(
                                    downloads = server.url("/download").toString().trimEnd('/'),
                                    apk020Sha256 = sha256(stableApk),
                                    apk020Size = stableApk.size.toLong(),
                                    rcSize = rcApk.size.toLong(),
                                ),
                            )
                        }
                    }
                    path.endsWith("/v0.2.0/cursor-for-android-0.2.0.apk") -> { downloadRequests += path; MockResponse().setBody(Buffer().write(stableApkBytes)) }
                    path.endsWith("/v0.3.0-rc.1/cursor-for-android-0.3.0-rc.1.apk") -> { downloadRequests += path; MockResponse().setBody(Buffer().write(rcApk)) }
                    path.endsWith("/v0.3.0-rc.1/SHA256SUMS.txt") -> { downloadRequests += path; MockResponse().setBody("${sha256(rcApk)}  cursor-for-android-0.3.0-rc.1.apk\n") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun manager(backgroundIdleMs: Long = 0L, sleep: suspend (Long) -> Unit = { delay(it) }): UpdateManager = UpdateManager(
        client = GitHubReleasesClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            GitHubFixtures.OWNER_REPO,
            apiBaseUrl = server.url("/").toString(),
            // Free space is GitHubReleasesClientTest's subject; here every download has room.
            freeSpace = { Long.MAX_VALUE / 2 },
        ),
        prefs = prefs,
        cache = cache,
        platform = platform,
        downloadDir = downloads,
        agentsRunning = { agentsRunning },
        scope = scope,
        now = { now },
        backgroundIdleMs = backgroundIdleMs,
        sleep = sleep,
    )

    private var agentsRunning = false

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The versionCode a download's name carries, whether it is still a `.part` or the checked file. */
    private fun versionCodeOf(file: File) = file.name.removeSuffix(".part").removeSuffix(".apk").toLong()

    /** The session the fake's last install created, which is what its verdict will name. */
    private fun lastSession() = platform.sessions.last()

    private suspend fun UpdateManager.awaitState(timeoutMs: Long = 10_000, predicate: (UpdateState) -> Boolean): UpdateState =
        withTimeout(timeoutMs) { state.first(predicate) }

    private fun apk(versionCode: Int) = File(downloads, "$versionCode.apk")

    // ---- the manual path --------------------------------------------------------------------------------------------

    @Test
    fun `check, download, install - and the success is recorded`() = runBlocking {
        val manager = manager()
        val seen = mutableListOf<UpdateState>()
        val watcher = manager.state.onEach { seen += it }.launchIn(this)

        val available = manager.check() as UpdateState.Available
        assertThat(available.release.tagName).isEqualTo("v0.2.0")
        assertThat(available.signatureMismatch).isFalse()
        assertThat(available.checkedAtMs).isEqualTo(now)
        assertThat(prefs.updateLastCheckedAt.first()).isEqualTo(now)

        val downloaded = manager.download() as UpdateState.Downloaded
        assertThat(downloaded.apk).isEqualTo(apk(20099))
        assertThat(downloaded.apk.readBytes()).isEqualTo(stableApk)
        assertThat(downloadRequests).containsExactly("/download/v0.2.0/cursor-for-android-0.2.0.apk")
        val progress = seen.filterIsInstance<UpdateState.Downloading>()
        assertThat(progress.first().bytesRead).isEqualTo(0L)
        assertThat(progress.last().bytesRead).isEqualTo(stableApk.size.toLong())
        assertThat(progress.last().totalBytes).isEqualTo(stableApk.size.toLong())
        assertThat(progress.last().fraction).isEqualTo(1f)

        val installing = manager.install()
        assertThat(installing).isEqualTo(UpdateState.Installing(available.release))
        assertThat(platform.installs.single().first).isEqualTo(apk(20099))
        assertThat(prefs.pendingUpdateVersionCode.first()).isEqualTo(20099)

        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_SUCCESS, null, null)
        assertThat(manager.state.value).isEqualTo(UpdateState.Installed("0.2.0"))
        assertThat(apk(20099).exists()).isFalse()
        withTimeout(5_000) { while (prefs.pendingUpdateVersionCode.first() != null) delay(10) }
        assertThat(platform.cancelledNotifications).isEqualTo(1)
        watcher.cancel()
    }

    @Test
    fun `a download that does not match GitHub's digest is thrown away and can be retried`() = runBlocking {
        val manager = manager()
        manager.check()
        stableApkBytes = stableApk.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val failed = manager.download() as UpdateState.Failed
        assertThat(failed.phase).isEqualTo(UpdatePhase.Download)
        assertThat(failed.message).contains("didn't match the checksum")
        assertThat(failed.release?.tagName).isEqualTo("v0.2.0")
        assertThat(downloads.listFiles()!!.toList()).isEmpty()

        stableApkBytes = stableApk
        assertThat(manager.download()).isInstanceOf(UpdateState.Downloaded::class.java)
    }

    @Test
    fun `without an asset digest the release's SHA256SUMS file verifies the download`() = runBlocking {
        prefs.setIncludePreReleases(true)
        val manager = manager()
        val available = manager.check() as UpdateState.Available
        assertThat(available.release.tagName).isEqualTo("v0.3.0-rc.1")
        assertThat(available.release.apk.sha256).isNull()

        val downloaded = manager.download() as UpdateState.Downloaded
        assertThat(downloaded.apk).isEqualTo(apk(30051))
        assertThat(downloadRequests).containsExactly("/download/v0.3.0-rc.1/SHA256SUMS.txt", "/download/v0.3.0-rc.1/cursor-for-android-0.3.0-rc.1.apk").inOrder()
    }

    @Test
    fun `a release signed with another key is offered as a page, never downloaded`() = runBlocking {
        platform.signatures = setOf("f".repeat(64))
        val manager = manager()
        val available = manager.check() as UpdateState.Available
        assertThat(available.signatureMismatch).isTrue()
        assertThat(manager.download()).isEqualTo(available)
        assertThat(downloadRequests).isEmpty()
    }

    @Test
    fun `an APK built as another package cannot update this build`() = runBlocking {
        platform.inspection = { ApkInfo("com.cursorforandroid.debug", 20099, platform.signatures) }
        val manager = manager()
        manager.check()
        val failed = manager.download() as UpdateState.Failed
        assertThat(failed.message).contains("built as com.cursorforandroid.debug")
        assertThat(downloads.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `an APK whose own certificate differs from the installed one is refused after download`() = runBlocking {
        platform.inspection = { file -> ApkInfo(platform.applicationId, versionCodeOf(file), setOf("e".repeat(64))) }
        val manager = manager()
        manager.check()
        val failed = manager.download() as UpdateState.Failed
        assertThat(failed.message).isEqualTo(UpdateManager.SIGNATURE_MISMATCH_MESSAGE)
    }

    @Test
    fun `an APK that is not the build its release tag stands for is refused`() = runBlocking {
        // A mispackaged release: correctly signed, this package, newer than what is installed — and not v0.2.0.
        platform.inspection = { ApkInfo(platform.applicationId, 9_000_099L, platform.signatures, "9.0.0") }
        val manager = manager()
        manager.check()
        val failed = manager.download() as UpdateState.Failed
        assertThat(failed.message).contains("is build 9000099, not the 20099")
        assertThat(downloads.listFiles()!!.toList()).isEmpty()
        assertThat(platform.installs).isEmpty()
    }

    @Test
    fun `an APK that calls itself another version is refused, build metadata aside`() = runBlocking {
        platform.inspection = { file -> ApkInfo(platform.applicationId, versionCodeOf(file), platform.signatures, "0.2.0-rc.1") }
        val manager = manager()
        manager.check()
        assertThat((manager.download() as UpdateState.Failed).message).contains("calls itself 0.2.0-rc.1")

        // The same version with the commit it was built from is the same release.
        platform.inspection = { file -> ApkInfo(platform.applicationId, versionCodeOf(file), platform.signatures, "0.2.0+g1a2b3c4") }
        assertThat(manager.download()).isInstanceOf(UpdateState.Downloaded::class.java)
    }

    @Test
    fun `a downloaded file is only ready once something vouches for it, and is re-checked before it is installed`() = runBlocking {
        val manager = manager()
        manager.check()
        manager.download()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)

        // What a process killed between the rename and the checks would leave: the final name, nothing vouching for it.
        cache.clearVerified()
        val restored = manager()
        restored.ensureRestored()
        assertThat(restored.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(apk(20099).exists()).isFalse()

        // Downloaded again, then altered on disk: the digest recorded then is what the install is checked against.
        restored.download()
        apk(20099).writeBytes(stableApk.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val failed = restored.install() as UpdateState.Failed
        assertThat(failed.message).contains("changed on disk")
        assertThat(platform.installs).isEmpty()
    }

    @Test
    fun `the pre-release channel follows the installed build until chosen, and switching it fetches a fresh list`() = runBlocking {
        val manager = manager()
        assertThat(manager.includePreReleases.first()).isFalse()
        assertThat((manager.check() as UpdateState.Available).release.tagName).isEqualTo("v0.2.0")

        manager.setIncludePreReleases(true)
        val rc = manager.awaitState { it is UpdateState.Available && it.release.tagName == "v0.3.0-rc.1" }
        assertThat((rc as UpdateState.Available).release.isPreRelease).isTrue()
        // The cached decision was for the other channel, so the second request went without an ETag.
        assertThat(listRequests).hasSize(2)
        assertThat(listRequests[1].getHeader("If-None-Match")).isNull()

        // An explicit choice wins over the build's own channel.
        prefs.setIncludePreReleases(false)
        platform.installedVersionName = "0.3.0-rc.1"
        assertThat(manager().includePreReleases.first()).isFalse()
    }

    @Test
    fun `a build that is itself a pre-release sees pre-releases until told otherwise`() = runBlocking {
        platform.installedVersionName = "0.2.0-dev.42+gabc1234"
        platform.installedVersionCode = 20024
        val manager = manager()
        assertThat(manager.includePreReleases.first()).isTrue()
        assertThat((manager.check() as UpdateState.Available).release.tagName).isEqualTo("v0.3.0-rc.1")
    }

    @Test
    fun `a repeat check is a conditional request and keeps the decision`() = runBlocking {
        val manager = manager()
        manager.check()
        now += 60_000
        val again = manager.check() as UpdateState.Available
        assertThat(again.release.tagName).isEqualTo("v0.2.0")
        assertThat(again.checkedAtMs).isEqualTo(now)
        assertThat(listRequests).hasSize(2)
        assertThat(listRequests[1].getHeader("If-None-Match")).isEqualTo(releasesEtag)
        assertThat(cache.read()!!.etag).isEqualTo(releasesEtag)
    }

    @Test
    fun `a stable release only reachable on a later page is still found`() = runBlocking {
        fun releaseList(vararg tags: Pair<String, Boolean>) = tags.joinToString(",", "[", "]") { (tag, prerelease) ->
            val version = tag.removePrefix("v")
            """{"tag_name":"$tag","prerelease":$prerelease,"assets":[
                {"name":"cursor-for-android-$version.apk","size":1,"browser_download_url":"${server.url("/download")}/$tag/x.apk"}]}"""
        }
        val firstPage = releaseList(*(1..20).map { "v0.3.0-rc.$it" to true }.toTypedArray())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/repos/${GitHubFixtures.OWNER_REPO}/releases") ->
                    MockResponse().setHeader("Link", """<${server.url("/page2")}>; rel="next"""").setBody(firstPage)
                request.path == "/page2" -> MockResponse().setBody(releaseList("v0.2.0" to false))
                else -> MockResponse().setResponseCode(404)
            }
        }
        // The stable channel would otherwise be told it is up to date: twenty release candidates fill page one.
        val available = manager().check() as UpdateState.Available
        assertThat(available.release.tagName).isEqualTo("v0.2.0")
    }

    @Test
    fun `a build that is current reads as up to date, and a failed check says why`() = runBlocking {
        platform.installedVersionCode = 20099
        platform.installedVersionName = "0.2.0"
        val manager = manager()
        assertThat(manager.check()).isEqualTo(UpdateState.UpToDate(now))

        server.shutdown()
        val failed = manager.check() as UpdateState.Failed
        assertThat(failed.phase).isEqualTo(UpdatePhase.Check)
        assertThat(failed.release).isNull()
    }

    @Test
    fun `cancelling a download goes back to the offer and leaves no file`() = runBlocking {
        val manager = manager()
        manager.check()
        // Slow the APK down so there is something to cancel.
        val slow = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = slow.dispatch(request)
                return if (request.path!!.endsWith(".apk")) response.throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS) else response
            }
        }
        manager.downloadNow()
        manager.awaitState { it is UpdateState.Downloading && it.bytesRead > 0 }
        manager.cancelDownload()
        val after = manager.awaitState { it is UpdateState.Available }
        assertThat((after as UpdateState.Available).release.tagName).isEqualTo("v0.2.0")
        withTimeout(5_000) { while (downloads.listFiles()!!.isNotEmpty()) delay(20) }
    }

    // ---- automatic mode ---------------------------------------------------------------------------------------------

    @Test
    fun `the scheduled run downloads only on Wi-Fi and installs only once the app is off screen`() = runBlocking {
        val manager = manager()
        platform.metered = true
        manager.runScheduled()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(downloadRequests).isEmpty()

        platform.metered = false
        platform.visible = true
        manager.runScheduled()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)
        assertThat(platform.installs).isEmpty()
        assertThat(platform.notified).isEmpty()

        // Off screen, but an agent's run is being streamed by the service: not now.
        platform.visible = false
        agentsRunning = true
        manager.onAppStopped()
        assertThat(platform.installs).isEmpty()

        agentsRunning = false
        manager.onAppStopped()
        assertThat(platform.installs).hasSize(1)
        assertThat(manager.state.value).isInstanceOf(UpdateState.Installing::class.java)
        // The session is committed; another background pass must not commit a second one.
        manager.runScheduled()
        assertThat(platform.installs).hasSize(1)
    }

    @Test
    fun `a user's action that arrives while an automatic pass holds the lock waits its turn instead of being dropped`() = runBlocking {
        val manager = manager()
        manager.check()
        manager.download()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)

        // The app comes forward: the automatic pass takes the lock and sits in its (held-back) check.
        val gate = CountDownLatch(1)
        listGate = gate
        platform.visible = true
        val pass = launch(Dispatchers.Default) { manager.runScheduled() }
        withTimeout(5_000) { while (listRequests.size < 2) delay(10) }

        // Meanwhile the user taps Install (or the "ready to install" notification). It must not vanish.
        manager.installNow()
        delay(300)
        assertThat(platform.installs).isEmpty()

        gate.countDown()
        pass.join()
        manager.awaitState { it is UpdateState.Installing }
        // The state flips before the session is recorded and written, so the session list is what to wait on.
        withTimeout(5_000) { while (platform.installs.isEmpty()) delay(10) }
        assertThat(platform.installs).hasSize(1)
    }

    @Test
    fun `cancelling from Settings ends the transfer without cancelling the pass that was running it`() = runBlocking {
        val manager = manager()
        manager.check()
        val slow = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = slow.dispatch(request)
                return if (request.path!!.endsWith(".apk")) response.throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS) else response
            }
        }
        // The periodic job is what is downloading here; the user cancels from Settings.
        val pass = launch(Dispatchers.Default) { manager.runScheduled() }
        manager.awaitState { it is UpdateState.Downloading && it.bytesRead > 0 }
        manager.cancelDownload()
        pass.join() // completes normally: only the transfer was cancelled
        assertThat(manager.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(platform.installs).isEmpty()
    }

    @Test
    fun `automatic updates switched off leave everything alone`() = runBlocking {
        prefs.setAutoUpdate(false)
        val manager = manager()
        manager.runScheduled()
        manager.onAppStarted()
        manager.onAppStopped()
        assertThat(listRequests).isEmpty()
        assertThat(manager.state.value).isEqualTo(UpdateState.Idle)
    }

    @Test
    fun `before Android 12, or without install permission, a downloaded update is announced once`() = runBlocking {
        platform.sdkInt = 30
        val manager = manager()
        manager.runScheduled()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)
        assertThat(platform.installs).isEmpty()
        assertThat(platform.notified.map { it.tagName }).containsExactly("v0.2.0")
        manager.runScheduled()
        manager.onAppStopped()
        assertThat(platform.notified).hasSize(1)

        // Android 12 without "Install unknown apps" allowed behaves the same way.
        platform.sdkInt = 35
        platform.canInstall = false
        prefs.setNotifiedUpdateVersionCode(null)
        manager.onAppStopped()
        assertThat(platform.installs).isEmpty()
        assertThat(platform.notified).hasSize(2)
    }

    @Test
    fun `a notification the system would not show is not remembered as shown`() = runBlocking {
        platform.sdkInt = 30
        platform.notificationsPost = false
        val manager = manager()
        manager.runScheduled()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)
        assertThat(platform.notified).isEmpty()
        assertThat(prefs.notifiedUpdateVersionCode.first()).isNull()

        // Notifications allowed later: the offer is made again rather than counted as already announced.
        platform.notificationsPost = true
        manager.onAppStopped()
        assertThat(platform.notified.map { it.tagName }).containsExactly("v0.2.0")
        assertThat(prefs.notifiedUpdateVersionCode.first()).isEqualTo(20099)
        manager.onAppStopped()
        assertThat(platform.notified).hasSize(1)
    }

    @Test
    fun `an install the app decided on is abandoned when the user comes back, or a run starts, while it settles`() = runBlocking {
        val manager = manager(backgroundIdleMs = 300)
        manager.check()
        manager.download()

        // The app is off screen when the decision is made and on screen a moment later: nothing is committed.
        val pass = launch(Dispatchers.Default) { manager.onAppStopped() }
        delay(100)
        platform.visible = true
        pass.join()
        assertThat(platform.installs).isEmpty()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)

        // Same for a stream that begins while the window is being waited out.
        platform.visible = false
        val second = launch(Dispatchers.Default) { manager.onAppStopped() }
        delay(100)
        agentsRunning = true
        second.join()
        assertThat(platform.installs).isEmpty()

        agentsRunning = false
        manager.onAppStopped()
        assertThat(platform.installs).hasSize(1)
    }

    @Test
    fun `the foreground check runs at most every six hours`() = runBlocking {
        val manager = manager()
        manager.onAppStarted()
        assertThat(listRequests).hasSize(1)
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java) // Wi-Fi, so it downloaded too
        now += 5 * 60 * 60 * 1000
        manager.onAppStarted()
        assertThat(listRequests).hasSize(1)
        now += 2 * 60 * 60 * 1000
        manager.onAppStarted()
        assertThat(listRequests).hasSize(2)
    }

    @Test
    fun `a download deferred on a metered network happens on the next foreground pass over Wi-Fi, check or no check`() = runBlocking {
        platform.metered = true
        val manager = manager()
        manager.onAppStarted()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(downloadRequests).isEmpty()

        // Ten minutes later, on Wi-Fi: the check is still throttled, the download is not.
        now += 10 * 60 * 1000
        platform.metered = false
        manager.onAppStarted()
        assertThat(listRequests).hasSize(1)
        assertThat(manager.state.value).isInstanceOf(UpdateState.Downloaded::class.java)
    }

    // ---- across processes -------------------------------------------------------------------------------------------

    @Test
    fun `a fresh process recognises the update it was replaced by`() = runBlocking {
        val before = manager()
        before.check()
        before.download()
        before.install()
        assertThat(prefs.pendingUpdateVersionCode.first()).isEqualTo(20099)

        // The system installed the session and restarted the app as 0.2.0.
        platform.installedVersionCode = 20099
        platform.installedVersionName = "0.2.0"
        val after = manager()
        after.ensureRestored()
        assertThat(after.state.value).isEqualTo(UpdateState.Installed("0.2.0"))
        assertThat(prefs.pendingUpdateVersionCode.first()).isNull()
        assertThat(downloads.listFiles()!!.toList()).isEmpty()
        assertThat(cache.read()).isNull()
        // The next check finds nothing newer.
        assertThat(after.check()).isEqualTo(UpdateState.UpToDate(now))
    }

    @Test
    fun `a fresh process picks up a download its predecessor never got to install`() = runBlocking {
        val before = manager()
        before.check()
        before.download()
        before.install()
        val release = before.state.value.release!!

        // Still 0.1.0: the confirmation was never answered before the process died. The session was committed, though,
        // and its verdict may be moments away (it may even be what started this process), so it is left alone.
        val after = manager()
        after.ensureRestored()
        assertThat(after.state.value).isEqualTo(UpdateState.Installing(release))
        assertThat(platform.abandoned).isEqualTo(0)
        assertThat(prefs.pendingUpdateVersionCode.first()).isNull()

        // Opening from the notification gives up on it and starts a new session. The state flips before the session is
        // written, so the session list is what to wait on.
        after.resumePendingInstall()
        after.awaitState { it is UpdateState.Installing }
        withTimeout(5_000) { while (platform.installs.size < 2) delay(10) }
        assertThat(platform.abandoned).isEqualTo(1)
    }

    @Test
    fun `a session whose verdict never came is abandoned once it is too old to expect one`() = runBlocking {
        val before = manager()
        before.check()
        before.download()
        before.install()

        now += UpdateManager.PENDING_INSTALL_TTL_MS + 1
        val after = manager()
        after.ensureRestored()
        assertThat(after.state.value).isInstanceOf(UpdateState.Downloaded::class.java)
        assertThat(platform.abandoned).isEqualTo(1)
    }

    @Test
    fun `a verdict that never arrives is given up on by the process that is waiting for it`() = runBlocking {
        val waited = CopyOnWriteArrayList<Long>()
        val deadline = CompletableDeferred<Unit>()
        val manager = manager(sleep = { waited += it; deadline.await() })
        manager.check()
        manager.download()
        manager.install()
        assertThat(manager.state.value).isInstanceOf(UpdateState.Installing::class.java)
        withTimeout(5_000) { while (waited.isEmpty()) delay(10) }
        assertThat(waited.single()).isEqualTo(UpdateManager.PENDING_INSTALL_TTL_MS)

        // Ten minutes on, the installer has no such session: no verdict is coming, so this process — not the next
        // one, ten minutes after a restart — puts the ready download back in front of the user.
        now += UpdateManager.PENDING_INSTALL_TTL_MS + 1
        platform.forgetSessions()
        deadline.complete(Unit)
        val recovered = manager.awaitState { it is UpdateState.Downloaded } as UpdateState.Downloaded
        assertThat(recovered.apk).isEqualTo(apk(20099))
        assertThat(platform.abandoned).isEqualTo(1)
        assertThat(cache.readPending()).isNull()
        withTimeout(5_000) { while (prefs.pendingUpdateVersionCode.first() != null) delay(10) }

        // Installing is what an `Installing` state turns into a no-op, so it is the proof the manager is unstuck.
        manager.installNow()
        withTimeout(5_000) { while (platform.installs.size < 2) delay(10) }
    }

    @Test
    fun `a session the installer is still working on is left alone at the deadline, and can still be given up on`() = runBlocking {
        val deadline = CompletableDeferred<Unit>()
        val manager = manager(sleep = { deadline.await() })
        manager.check()
        manager.download()
        manager.install()
        val release = manager.state.value.release!!

        now += UpdateManager.PENDING_INSTALL_TTL_MS + 1
        deadline.complete(Unit)
        withTimeout(5_000) { while (platform.sessionQueries.isEmpty()) delay(10) }
        assertThat(platform.sessionQueries).containsExactly(lastSession())
        assertThat(manager.state.value).isEqualTo(UpdateState.Installing(release))
        assertThat(platform.abandoned).isEqualTo(0)

        // Settings offers Cancel for exactly this state, and it is the only way out of a session that never ends.
        manager.cancelInstall()
        assertThat(manager.awaitState { it is UpdateState.Downloaded }).isEqualTo(UpdateState.Downloaded(release, apk(20099)))
        assertThat(platform.abandoned).isEqualTo(1)
        assertThat(cache.readPending()).isNull()
    }

    @Test
    fun `a process that died before its commit is not mistaken for an install in flight`() = runBlocking {
        val staged = CompletableDeferred<Unit>()
        val stuck = CompletableDeferred<Unit>()
        platform.beforeCommit = {
            staged.complete(Unit)
            stuck.await()
        }
        val before = manager()
        before.check()
        before.download()
        val release = before.state.value.release!!

        // The session is recorded and the process goes away on its way to the commit.
        val dying = launch(Dispatchers.Default) { before.install() }
        withTimeout(5_000) { staged.await() }
        assertThat(cache.readPending()!!.commitIssued).isFalse()
        dying.cancelAndJoin()

        // Nothing will ever report on that session, so the fresh process must not wait ten minutes for it.
        platform.beforeCommit = null
        val after = manager()
        after.ensureRestored()
        assertThat(after.state.value).isEqualTo(UpdateState.Downloaded(release, apk(20099)))
        assertThat(cache.readPending()).isNull()
        assertThat(prefs.pendingUpdateVersionCode.first()).isNull()
        assertThat(after.install()).isInstanceOf(UpdateState.Installing::class.java)
    }

    @Test
    fun `a cold process serves the verdict from what was recorded before the commit`() = runBlocking {
        val before = manager()
        before.check()
        before.download()
        before.install()
        val release = before.state.value.release!!
        val session = lastSession()

        // The process that committed is gone and nothing was restored yet: this manager has never seen the release.
        val after = manager()
        assertThat(after.state.value).isEqualTo(UpdateState.Idle)
        after.onInstallStatus(release.versionCode, session, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        assertThat(after.state.value).isEqualTo(UpdateState.Installing(release, awaitingConfirmation = true))
        assertThat(platform.notified.map { it.tagName }).containsExactly("v0.2.0")

        // The installer redelivering a verdict must not undo the one already acted on.
        after.onInstallStatus(release.versionCode, session, PackageInstaller.STATUS_SUCCESS, null, null)
        assertThat(after.state.value).isEqualTo(UpdateState.Installed("0.2.0"))
        after.onInstallStatus(release.versionCode, session, PackageInstaller.STATUS_SUCCESS, null, null)
        assertThat(platform.cancelledNotifications).isEqualTo(1)
    }

    @Test
    fun `a remembered offer is shown at once, unless the build has caught up with it`() = runBlocking {
        manager().check()
        val remembered = manager()
        remembered.ensureRestored()
        assertThat(remembered.state.value).isInstanceOf(UpdateState.Available::class.java)
        assertThat(listRequests).hasSize(1)

        platform.installedVersionCode = 20099
        val caughtUp = manager()
        caughtUp.ensureRestored()
        assertThat(caughtUp.state.value).isEqualTo(UpdateState.UpToDate(now))
    }

    // ---- the installer's verdicts -----------------------------------------------------------------------------------

    @Test
    fun `a pending confirmation is shown at once when the app is visible, and announced otherwise`() = runBlocking {
        val manager = manager()
        manager.check()
        manager.download()
        manager.install()
        val release = manager.state.value.release!!
        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")

        platform.visible = true
        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirmation)
        assertThat(manager.state.value).isEqualTo(UpdateState.Installing(release, awaitingConfirmation = true))
        assertThat(platform.confirmations).containsExactly(confirmation)
        assertThat(platform.notified).isEmpty()

        platform.visible = false
        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirmation)
        assertThat(platform.notified.map { it.tagName }).containsExactly("v0.2.0")

        // Back in the app from the notification: the same confirmation comes up again.
        manager.resumePendingInstall()
        withTimeout(5_000) { while (platform.confirmations.size < 2) delay(10) }
        assertThat(platform.installs).hasSize(1)

        // Declined: the download is kept and offered again.
        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_FAILURE_ABORTED, "user cancelled", null)
        assertThat(manager.state.value).isEqualTo(UpdateState.Downloaded(release, apk(20099)))
        assertThat(apk(20099).exists()).isTrue()
    }

    @Test
    fun `failures are translated, a verdict for another release is ignored, and an invalid file is dropped`() = runBlocking {
        val manager = manager()
        manager.check()
        manager.download()
        manager.install()
        val release = manager.state.value.release!!

        manager.onInstallStatus(99999, lastSession(), PackageInstaller.STATUS_FAILURE, "stale", null)
        assertThat(manager.state.value).isEqualTo(UpdateState.Installing(release))

        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null)
        val conflict = manager.state.value as UpdateState.Failed
        assertThat(conflict.phase).isEqualTo(UpdatePhase.Install)
        assertThat(conflict.message).isEqualTo(UpdateManager.SIGNATURE_MISMATCH_MESSAGE)

        manager.install()
        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_FAILURE_STORAGE, null, null)
        assertThat((manager.state.value as UpdateState.Failed).message).contains("storage")

        manager.install()
        manager.onInstallStatus(20099, lastSession(), PackageInstaller.STATUS_FAILURE_INVALID, null, null)
        assertThat((manager.state.value as UpdateState.Failed).message).contains("valid")
        assertThat(apk(20099).exists()).isFalse()
        // Retrying an install without its file means downloading again.
        assertThat(manager.install()).isInstanceOf(UpdateState.Available::class.java)
    }

    @Test
    fun `a session that cannot be created reads as a failed install`() = runBlocking {
        val manager = manager()
        manager.check()
        manager.download()
        platform.installError = java.io.IOException("Failed to allocate session")
        val failed = manager.install() as UpdateState.Failed
        assertThat(failed.phase).isEqualTo(UpdatePhase.Install)
        assertThat(failed.message).contains("allocate session")
        assertThat(prefs.pendingUpdateVersionCode.first()).isNull()
        platform.installError = null
        assertThat(manager.install()).isInstanceOf(UpdateState.Installing::class.java)
    }
}
