package com.cursorforandroid.notifications

import android.content.Context
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The POST_NOTIFICATIONS prompt is launched once, from the composition's thread, while its launcher is registered.
 * It was launched after the "asked" write: under the Compose test harness the effect resumed on the write's thread,
 * and the launcher's registration — keyed by a contract made anew each recomposition — was being replaced by the
 * recomposition that same write set off. `HingeSplitScreenshotTest.railOpen` failed on main run 36665777372 with
 * "Attempting to launch an unregistered ActivityResultLauncher".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationPermissionPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private val launches = CopyOnWriteArrayList<Thread>()

    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches += Thread.currentThread()
            }
        }
    }

    @Test
    fun `the prompt is launched once, on the main thread, and the ask is remembered`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                NotificationPermissionPrompt(graph, hasRunningAgents = true)
            }
        }
        compose.waitUntil(10_000) { runBlocking { graph.prefs.notificationPermissionAsked.first() } }
        compose.waitForIdle()
        assertThat(launches.map { it.name }).containsExactly(Looper.getMainLooper().thread.name)
    }
}
