package com.cursorforandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.data.repo.LoginProgress
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.notifications.LiveNotificationCoordinator
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.update.UpdateCoordinator
import com.cursorforandroid.update.UpdateNotifications
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Agent id from a `https://cursor.com/agents/<id>` deep link, consumed once by the nav host. */
    private var pendingAgentId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingAgentId = agentIdFrom(intent)

        val graph = appGraph
        splash.setKeepOnScreenCondition { graph.session.state.value is SessionState.Loading }
        LiveNotifications.ensureChannels(this)
        LiveNotificationCoordinator.bind(this, graph)
        UpdateCoordinator.bind(this, graph)
        resumeUpdateIfAsked(intent)
        returnFromBrowserWhenLoginEnds(graph)

        setContent {
            val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            CursorTheme(mode = themeMode) {
                CursorRoot(
                    graph = graph,
                    deepLinkAgentId = pendingAgentId,
                    onDeepLinkConsumed = { pendingAgentId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        agentIdFrom(intent)?.let { pendingAgentId = it }
        resumeUpdateIfAsked(intent)
    }

    /** The "ready to install" notification opens the app with this action; the confirmation the system wants follows. */
    private fun resumeUpdateIfAsked(intent: Intent?) {
        if (intent?.action == UpdateNotifications.ACTION_INSTALL_UPDATE) appGraph.updates.resumePendingInstall()
    }

    /**
     * The browser sign-in opens cursor.com in a Custom Tab on top of this activity and polls in the background. When
     * the poll ends — signed in, or failed with something to show — while the tab still covers us, relaunching this
     * `singleTask` activity with `CLEAR_TOP` finishes the tab and brings the app back, which is how OAuth libraries
     * dismiss their tabs too. Nothing happens if the user has already returned on their own.
     */
    private fun returnFromBrowserWhenLoginEnds(graph: AppGraph) {
        lifecycleScope.launch {
            var previous: LoginProgress = graph.session.loginProgress.value
            graph.session.loginProgress.collect { progress ->
                val wasInBrowser = previous is LoginProgress.WaitingForBrowser || previous is LoginProgress.Finishing
                val ended = progress is LoginProgress.Failed || (previous is LoginProgress.Finishing && progress is LoginProgress.Idle)
                previous = progress
                if (wasInBrowser && ended && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }
    }

    private fun agentIdFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.host != "cursor.com") return null
        val segments = uri.pathSegments
        val idx = segments.indexOf("agents")
        return segments.getOrNull(idx + 1)?.takeIf { it.startsWith("bc") }
            ?: uri.getQueryParameter("id")?.takeIf { it.startsWith("bc") }
    }
}
