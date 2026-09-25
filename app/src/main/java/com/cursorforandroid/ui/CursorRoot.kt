package com.cursorforandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.agents.LocalMediaLoader
import com.cursorforandroid.ui.auth.SignInScreen
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.navigation.AppNavHost
import com.cursorforandroid.ui.onboarding.ModeChoiceScreen
import com.cursorforandroid.ui.theme.CursorTheme

@Composable
fun CursorRoot(
    graph: AppGraph,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
    newChatRequested: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
    searchRequested: Boolean = false,
    onSearchConsumed: () -> Unit = {},
) {
    val session by graph.session.state.collectAsStateWithLifecycle()
    // Whether the account that signed in still owes the first-run mode choice: true after a sign-in through the
    // sign-in screen until the choice screen settles it, false for a restored session, null until the stored flag
    // has been read below.
    val modeChoicePending by graph.onboarding.modeChoicePending.collectAsStateWithLifecycle()
    // The first-run flag is read ahead of the restore, so a session the restore finds signed in has its answer by
    // the frame that shows it. The session settles itself to signed-out when a store cannot be read; this only keeps
    // a future throw from taking the composition (and the process) with it. The Extended mode upgrade step follows
    // the restore: it reads the same stores, and an install it finds signed in is the one it owes a notice and a wipe.
    LaunchedEffect(Unit) {
        runCatching { graph.onboarding.load() }
        runCatching { graph.session.restoreIfNeeded() }
        runCatching { graph.extendedMode.migrateInstall() }
    }
    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
        when (val s = session) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> SignInScreen(graph = graph)
            // The demo signs nothing in and has no account the choice could concern; a real sign-in goes through
            // the choice before the shell is built, so nothing of the shell (its view models, its polling) starts
            // under a screen that is not it. Until the flag is read, this is the same blank the session's own
            // Loading shows.
            is SessionState.SignedIn -> when {
                !s.isDemo && modeChoicePending == true -> ModeChoiceScreen(graph = graph)
                !s.isDemo && modeChoicePending == null -> Unit
                // The loader is provided here rather than around the whole tree because building it is what first
                // pulls Coil and its HTTP client in, and nothing before this point draws an image.
                else -> CompositionLocalProvider(LocalMediaLoader provides graph.media) {
                    // Over every screen and pane, so a sent message can travel from one composer into a bubble on
                    // another screen (see SendMotion).
                    SendMotionHost {
                        AppNavHost(
                            graph = graph,
                            user = s.user,
                            isDemo = s.isDemo,
                            deepLinkAgentId = deepLinkAgentId,
                            onDeepLinkConsumed = onDeepLinkConsumed,
                            newChatRequested = newChatRequested,
                            onNewChatConsumed = onNewChatConsumed,
                            searchRequested = searchRequested,
                            onSearchConsumed = onSearchConsumed,
                        )
                    }
                }
            }
        }
        val extended by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false)
        // Over whatever screen the app opened on, under the status bar: the one place it is seen whichever that is.
        CrashReportCard(
            graph = graph,
            canSend = extended && (session as? SessionState.SignedIn)?.isDemo == false,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
