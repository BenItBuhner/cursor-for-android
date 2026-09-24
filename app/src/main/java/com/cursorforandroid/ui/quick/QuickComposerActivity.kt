package com.cursorforandroid.ui.quick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.ui.compose.NewAgentViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import androidx.core.net.toUri

/**
 * The composer over the launcher: the home-screen shortcut's "new chat", opened as a sheet on a translucent window
 * rather than as the app. It is the New Chat pane's composer — the same [NewAgentViewModel], the same box, the same
 * pickers, drafts and uploads — with the pane's context row folded to a line. Sending waits for the server, then
 * opens the app on the chat; a refusal stays here, in the server's words. Cancel throws the draft away; leaving any
 * other way (the scrim, back, home) keeps it, on disk, for the next time either composer opens.
 *
 * Its own task, excluded from recents (manifest): dismissing it returns to the launcher it came from, never to a
 * chat the app happened to be on.
 */
class QuickComposerActivity : ComponentActivity() {

    /** The composer's view model, once the screen has built it; what [onStop] keeps the draft through. */
    private var composer: NewAgentViewModel? = null

    /** True once the draft has been sent or thrown away: nothing of it is kept from then on. */
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = appGraph
        setContent {
            val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            // The window stays translucent: the launcher shows through the scrim around the sheet.
            CursorTheme(mode = themeMode, oledBlack = oledBlack, paintWindow = false) {
                QuickComposerHost(
                    graph = graph,
                    onComposer = { composer = it },
                    onDismiss = ::dismiss,
                    onCancel = ::cancel,
                    onOpened = ::openChat,
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    /** Left without a word — the scrim, back, a swipe home: the draft stays for next time. */
    private fun dismiss() {
        composer?.keepDraft()
        finish()
    }

    /** Cancel: the draft goes, on screen and on disk. */
    private fun cancel() {
        settled = true
        composer?.discardDraft()
        finish()
    }

    /** The server has the chat: the app opens on it, through the deep link every other entry point uses. */
    private fun openChat(agentId: String) {
        settled = true
        startActivity(
            Intent(Intent.ACTION_VIEW, CursorEndpoints.webUrl(agentId).toUri(), this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** Signed out: the app is where signing in happens. */
    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStop() {
        // Home, the recents, a call: what is written is kept the moment the sheet leaves the screen, so the process
        // being taken while it is away loses nothing. A draft sent or cancelled is nobody's to keep.
        if (!settled) composer?.keepDraft()
        super.onStop()
    }
}
