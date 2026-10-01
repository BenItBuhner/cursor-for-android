package com.cursorforandroid.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.cursorforandroid.data.local.NewChatPageCache
import com.cursorforandroid.data.local.NewChatPageSnapshot
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.ui.agents.AgentListUiState
import kotlinx.coroutines.delay

/** How long the page must hold still before it is written down, so a burst of list updates is one write. */
internal const val NEW_CHAT_PAGE_SAVE_DELAY_MS = 500L

/**
 * Keeps the New Chat page's picture in [cache] for the next launch's first frame: the layout chosen, Extended mode,
 * and the Projects exactly as the page lists them ([homeBlocks]) with the ones hidden there, once the list has loaded
 * and both settings have been read — never the picture this launch was seeded from.
 */
@Composable
internal fun RememberNewChatPage(
    cache: NewChatPageCache,
    user: CursorUser,
    choice: NewChatHomeChoice?,
    extendedMode: Boolean?,
    list: AgentListUiState,
    projectsAvailable: Boolean,
) {
    val snapshot = remember(user, choice, extendedMode, list.hasLoaded, list.projectRows, list.local, projectsAvailable) {
        if (!list.hasLoaded || choice == null || extendedMode == null) {
            null
        } else {
            val block = homeBlocks(NewChatHome.PROJECTS, list, projectsAvailable).firstNotNullOfOrNull { it as? HomeBlock.Projects }
            NewChatPageSnapshot.of(user, choice, extendedMode, block?.rows.orEmpty(), block?.hidden.orEmpty())
        }
    }
    LaunchedEffect(cache, snapshot) {
        snapshot ?: return@LaunchedEffect
        delay(NEW_CHAT_PAGE_SAVE_DELAY_MS)
        cache.save(snapshot)
    }
}
