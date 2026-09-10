package com.cursorforandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.ui.agents.LocalMediaLoader
import com.cursorforandroid.ui.auth.SignInScreen
import com.cursorforandroid.ui.navigation.AppNavHost
import com.cursorforandroid.ui.theme.CursorTheme

@Composable
fun CursorRoot(
    graph: AppGraph,
    deepLinkAgentId: String?,
    onDeepLinkConsumed: () -> Unit,
    newChatRequested: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
) {
    val session by graph.session.state.collectAsStateWithLifecycle()
    // The session settles itself to signed-out when a store cannot be read; this only keeps a future throw from
    // taking the composition (and the process) with it. The Extended mode upgrade step follows the restore: it reads
    // the same stores, and an install it finds signed in is the one it owes a notice and a wipe.
    LaunchedEffect(Unit) {
        runCatching { graph.session.restoreIfNeeded() }
        runCatching { graph.extendedMode.migrateInstall() }
    }
    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
        when (val s = session) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> SignInScreen(graph = graph)
            // The loader is provided here rather than around the whole tree because building it is what first
            // pulls Coil and its HTTP client in, and nothing before this point draws an image.
            is SessionState.SignedIn -> CompositionLocalProvider(LocalMediaLoader provides graph.media) {
                AppNavHost(
                    graph = graph,
                    user = s.user,
                    isDemo = s.isDemo,
                    deepLinkAgentId = deepLinkAgentId,
                    onDeepLinkConsumed = onDeepLinkConsumed,
                    newChatRequested = newChatRequested,
                    onNewChatConsumed = onNewChatConsumed,
                )
            }
        }
    }
}
