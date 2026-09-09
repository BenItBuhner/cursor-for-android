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
import com.cursorforandroid.ui.theme.AppNightMode
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.update.UpdateCoordinator
import com.cursorforandroid.update.UpdateNotifications
import com.cursorforandroid.util.DeepLinks
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Agent id from a `https://cursor.com/agents/<id>` deep link, consumed once by the nav host. */
    private var pendingAgentId by mutableStateOf<String?>(null)

    /** Set by an [ACTION_NEW_CHAT] launch (the widget's "+"); the nav host pops to the New Chat pane and clears it. */
    private var pendingNewChat by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = appGraph
        splash.setKeepOnScreenCondition { graph.session.state.value is SessionState.Loading }
        LiveNotifications.ensureChannels(this)
        LiveNotificationCoordinator.bind(this, graph)
        UpdateCoordinator.bind(this, graph)
        readRequests(intent)
        returnFromBrowserWhenLoginEnds(graph)
        followThemePreference(graph)

        setContent {
            val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            CursorTheme(mode = themeMode, oledBlack = oledBlack) {
                CursorRoot(
                    graph = graph,
                    deepLinkAgentId = pendingAgentId,
                    onDeepLinkConsumed = {
                        pendingAgentId = null
                        DeepLinks.clearAgentLink(intent)
                    },
                    newChatRequested = pendingNewChat,
                    onNewChatConsumed = {
                        pendingNewChat = false
                        DeepLinks.clearAction(intent, ACTION_NEW_CHAT)
                    },
                )
            }
        }
    }

    /**
     * A second launch of this `singleTask` activity. [setIntent] is what makes [getIntent] answer with it: without
     * that, a later recreation reads the *launch* intent again and replays a deep link the user left long ago.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequests(intent)
    }

    /** Takes what the intent asks for: a chat to open, the New Chat pane, or an update to finish installing. */
    private fun readRequests(intent: Intent?) {
        DeepLinks.agentId(intent)?.let { pendingAgentId = it }
        if (intent?.action == ACTION_NEW_CHAT) pendingNewChat = true
        resumeUpdateIfAsked(intent)
    }

    /** The "ready to install" notification opens the app with this action; the confirmation the system wants follows. */
    private fun resumeUpdateIfAsked(intent: Intent?) {
        if (intent?.action != UpdateNotifications.ACTION_INSTALL_UPDATE) return
        DeepLinks.clearAction(intent, UpdateNotifications.ACTION_INSTALL_UPDATE)
        appGraph.updates.resumePendingInstall()
    }

    /**
     * Keeps the platform's per-application night mode on the theme the user chose. Collected rather than read once:
     * the preference is on disk, and it changes while the app is open. `uiMode` is in this activity's `configChanges`,
     * so the configuration change it causes is absorbed rather than recreating anything.
     */
    private fun followThemePreference(graph: AppGraph) {
        lifecycleScope.launch {
            graph.prefs.themeMode.collect { AppNightMode.apply(this@MainActivity, it) }
        }
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

    companion object {
        /** Opens the app on the New Chat pane, whatever it was showing: the home-screen widget's "+". */
        const val ACTION_NEW_CHAT = "com.cursorforandroid.action.NEW_CHAT"
    }
}
