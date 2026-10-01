package com.cursorforandroid.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.NewChatHomeChoice
import com.cursorforandroid.ui.agents.AgentListUiState
import kotlinx.coroutines.delay

/**
 * How long the pane waits on a fetch to confirm that an account whose disk copy has no Projects still has none, while
 * that copy's recent chats are there to show: past it, the recents. A big account's first pass can take far longer,
 * and a Project made elsewhere since is shown the next time the page is opened.
 */
internal const val NEW_CHAT_HOME_SETTLE_MS = 2_500L

/**
 * What the New Chat pane lists this time it is on screen: the layout chosen in Settings ([choice]) whenever there is
 * one, else the automatic pick — Projects when the account has any, the recent chats when it has none
 * ([NewChatHome.automatic]).
 *
 * The automatic pick is made once per opening of the page (this is remembered by the page's entry, which leaves
 * composition when the page does) and per [account], and kept while the page stays up: a first Project made or the
 * last one removed meanwhile never swaps the list under the reader; the next opening picks again. It waits — the
 * pane lists nothing, as for a preference not read yet — until it can be made without being taken back: [choice] read,
 * [projectsAvailable] read, and Projects in the list (the disk's copy counts) or the list current
 * ([AgentListUiState.isCurrent]) — or, with the disk's recents to show, [NEW_CHAT_HOME_SETTLE_MS] gone by.
 */
@Composable
internal fun rememberNewChatHome(
    choice: NewChatHomeChoice?,
    projectsAvailable: Boolean?,
    list: AgentListUiState,
    account: Any?,
): NewChatHome? {
    var picked by remember(account) { mutableStateOf<NewChatHome?>(null) }
    var waited by remember(account) { mutableStateOf(false) }
    val deciding = choice != null && choice.chosen == null && picked == null
    LaunchedEffect(account, deciding) {
        if (deciding && !waited) {
            delay(NEW_CHAT_HOME_SETTLE_MS)
            waited = true
        }
    }
    val candidate = if (deciding) {
        NewChatHome.automatic(projectsAvailable, hasProjects = list.projectRows.isNotEmpty(), settled = list.isCurrent || (waited && list.hasLoaded))
    } else {
        null
    }
    SideEffect { if (candidate != null && picked == null) picked = candidate }
    return when {
        choice == null -> null
        choice.chosen != null -> choice.chosen
        else -> picked ?: candidate
    }
}
