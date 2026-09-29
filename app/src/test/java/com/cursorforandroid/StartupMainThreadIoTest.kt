package com.cursorforandroid

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.Permission
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A launch into a signed-in account, from the application's creation to its list on screen, reads and writes no file
 * on the main thread and never opens the key store there: the settings, the key, the cached list and whatever the
 * first screen reads all come in on background threads. Directory lookups (where the app's files are) are not reads.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StartupMainThreadIoTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyStoreOpenedOnMain = CopyOnWriteArrayList<String>()
    private fun standIn() = context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE)
    private val keyStore = SecureKeyStore(context, openRetryDelayMs = 0) {
        if (Looper.getMainLooper().isCurrentThread) keyStoreOpenedOnMain += Throwable().stackTraceToString()
        standIn()
    }
    private val api = FakeCursorApi().apply {
        repeat(12) { i -> addIdleAgent("bc-$i", "Startup chat $i", "run-$i", createdAt = "2026-04-13T18:%02d:00.000Z".format(i)) }
    }
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previousSecurityManager: SecurityManager? = null

    @Before
    fun pinClock() {
        AppClock.nowMillis = { 1_776_200_000_000L }
    }

    @After
    fun tearDown() {
        System.setSecurityManager(previousSecurityManager)
        processScope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun graph(keyStore: SecureKeyStore = this.keyStore) = AppGraph(context, keyStore, real = CursorBackend(api, FakeRunStreamer(), isDemo = false))

    /** What an earlier process left: the account, and its list on disk. */
    private fun signedInEarlier() {
        val earlierKeyStore = SecureKeyStore(context, openRetryDelayMs = 0) { standIn() }
        val earlier = graph(earlierKeyStore)
        runBlocking {
            earlierKeyStore.setApiKey("key_stored")
            earlier.prefs.setCredentialInfo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
            earlier.prefs.setCachedUser(CursorUser("u1", "dev@example.com", "Dev", "Eloper", 1L))
            earlier.prefs.setExtendedModeIntroduced(noticePending = false)
            earlier.session.restoreIfNeeded()
            earlier.agents.refresh()
        }
        val list = File(context.cacheDir, "cursor")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (list.walk().none { it.isFile && "agent" in it.path } && System.nanoTime() < deadline) Thread.sleep(10)
    }

    @Test
    fun `a launch into a signed-in account does no file I-O on the main thread`() {
        signedInEarlier()
        val recorder = MainThreadFileIo().also {
            previousSecurityManager = System.getSecurityManager()
            System.setSecurityManager(it)
        }

        // CursorApp.onCreate, then MainActivity.onCreate and its first composition.
        val graph = graph()
        graph.warmUp()
        graph.startSession(processScope)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CursorRoot(graph = graph, deepLinkAgentId = null, onDeepLinkConsumed = {}) }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Startup chat", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { api.listAgentsCalls > 0 && !graph.agents.state.value.isFromCache }
        compose.waitForIdle()
        System.setSecurityManager(previousSecurityManager)

        assertWithMessage("files the main thread read or wrote:\n${recorder.accesses.joinToString("\n\n")}")
            .that(recorder.accesses).isEmpty()
        assertWithMessage("the key store opened on the main thread:\n${keyStoreOpenedOnMain.joinToString("\n\n")}")
            .that(keyStoreOpenedOnMain).isEmpty()
    }

    /**
     * Records every file opened for reading or writing on the main thread by the app's own code; allows everything,
     * records nothing else. A file stream, random access file or channel is an open; a `File.exists` or `mkdirs` is not.
     */
    private class MainThreadFileIo : SecurityManager() {
        val accesses = CopyOnWriteArrayList<String>()

        override fun checkPermission(perm: Permission?) = Unit
        override fun checkPermission(perm: Permission?, context: Any?) = Unit
        override fun checkRead(file: String) = record(file)
        override fun checkWrite(file: String) = record(file)

        private fun record(file: String) {
            if (!Looper.getMainLooper().isCurrentThread) return
            // The JVM's own: its trust store (read as an HTTP client is built; Android's is read lazily, per
            // certificate) and the classes it loads.
            if (file.startsWith(JAVA_HOME) || file.endsWith(".class") || file.endsWith(".jar")) return
            val stack = Thread.currentThread().stackTrace
            val opens = stack.any { frame -> OPENS.any { (type, method) -> frame.className == type && frame.methodName == method } }
            if (!opens) return
            val appFrames = stack.filter { it.className.startsWith("com.cursorforandroid.") && !it.className.startsWith(StartupMainThreadIoTest::class.java.name) }
            if (appFrames.isEmpty()) return
            accesses += "$file\n" + appFrames.take(12).joinToString("\n") { "    at $it" }
        }

        private companion object {
            val JAVA_HOME: String = File(checkNotNull(System.getProperty("java.home"))).canonicalPath
            val OPENS = listOf(
                "java.io.FileInputStream" to "<init>",
                "java.io.FileOutputStream" to "<init>",
                "java.io.RandomAccessFile" to "<init>",
                "sun.nio.fs.UnixChannelFactory" to "open",
            )
        }
    }
}
