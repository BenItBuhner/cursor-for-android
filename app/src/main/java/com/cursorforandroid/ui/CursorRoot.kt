package com.cursorforandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
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
    LaunchedEffect(Unit) { graph.session.restoreIfNeeded() }
    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
        when (val s = session) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> SignInScreen(graph = graph)
            is SessionState.SignedIn -> AppNavHost(
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
