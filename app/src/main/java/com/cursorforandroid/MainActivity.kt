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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode

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
