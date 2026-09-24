package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.domain.AgentLink
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Where a tapped link to an agent goes: a listed agent's chat at once, an unlisted one's once it has been read by its
 * id, a refused read to a failure that offers cursor.com, a `#desktop` link to cursor.com — and everything to
 * cursor.com where nothing can open a chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLinkOpenerTest {

    private val listed = "bc-11111111-1111-4111-8111-111111111111"
    private val unlisted = "bc-22222222-2222-4222-8222-222222222222"

    private val chats = mutableListOf<String>()
    private val pages = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private var answer = CompletableDeferred<Result<Unit>>()

    private fun TestScope.opener(inApp: Boolean = true) = AgentLinkOpener(
        scope = this,
        isLoaded = { it == listed },
        fetch = { id -> reads += id; answer.await() },
        openWeb = { pages += it },
        describe = { it.message ?: "?" },
    ).apply { if (inApp) openChat = { chats += it } }

    @Test
    fun `a listed agent's chat opens at once, without a read`() = runTest {
        val opener = opener()
        opener.open(AgentLink(listed))
        assertThat(chats).containsExactly(listed)
        assertThat(reads).isEmpty()
        assertThat(pages).isEmpty()
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
        // A fragment other than the desktop is no reason to leave the app.
        opener.open(AgentLink(listed, "turn-3"))
        assertThat(chats).containsExactly(listed, listed)
    }

    @Test
    fun `a desktop link opens the agent's page with the fragment, listed or not`() = runTest {
        val opener = opener()
        opener.open(AgentLink(listed, "desktop"))
        opener.open(AgentLink(unlisted, "desktop"))
        assertThat(pages).containsExactly("https://cursor.com/agents/$listed#desktop", "https://cursor.com/agents/$unlisted#desktop").inOrder()
        assertThat(chats).isEmpty()
        assertThat(reads).isEmpty()
    }

    @Test
    fun `an unlisted agent is read by its id, then its chat opens`() = runTest {
        val opener = opener()
        opener.open(AgentLink(unlisted))
        runCurrent()
        assertThat(reads).containsExactly(unlisted)
        assertThat(opener.state).isEqualTo(AgentLinkState.Opening(AgentLink(unlisted)))
        assertThat(chats).isEmpty()

        answer.complete(Result.success(Unit))
        runCurrent()
        assertThat(chats).containsExactly(unlisted)
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
        assertThat(pages).isEmpty()
    }

    @Test
    fun `a refused read shows the server's words, and offers the agent's page`() = runTest {
        val opener = opener()
        opener.open(AgentLink(unlisted))
        answer.complete(Result.failure(CursorApiException(404, "not_found", "Agent not found.")))
        runCurrent()
        assertThat(opener.state).isEqualTo(AgentLinkState.Failed(AgentLink(unlisted), "Agent not found."))
        assertThat(chats).isEmpty()

        opener.openOnWeb()
        assertThat(pages).containsExactly("https://cursor.com/agents/$unlisted")
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
    }

    @Test
    fun `the app's own wording of a refusal is the server's message`() = runTest {
        val opener = AgentLinkOpener(this, isLoaded = { false }, fetch = { Result.failure<Unit>(CursorApiException(403, "forbidden", "You don't have access to this agent.")) }, openWeb = { pages += it })
        opener.openChat = { chats += it }
        opener.open(AgentLink(unlisted))
        runCurrent()
        assertThat((opener.state as AgentLinkState.Failed).message).isEqualTo("You don't have access to this agent.")
    }

    @Test
    fun `a read cancelled or put away opens nothing when it answers`() = runTest {
        val opener = opener()
        opener.open(AgentLink(unlisted))
        runCurrent()
        opener.cancel()
        answer.complete(Result.success(Unit))
        runCurrent()
        assertThat(chats).isEmpty()
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)

        // Closing a failure opens nothing either.
        answer = CompletableDeferred()
        opener.open(AgentLink(unlisted))
        answer.complete(Result.failure(IllegalStateException("gone")))
        runCurrent()
        opener.cancel()
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
        assertThat(pages).isEmpty()
        assertThat(chats).isEmpty()
    }

    @Test
    fun `a second link tapped while a read is out replaces the first`() = runTest {
        val opener = opener()
        opener.open(AgentLink(unlisted))
        runCurrent()
        opener.open(AgentLink(listed))
        answer.complete(Result.success(Unit))
        runCurrent()
        assertThat(chats).containsExactly(listed)
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
    }

    @Test
    fun `where nothing can open a chat, every link opens the agent's page`() = runTest {
        val opener = opener(inApp = false)
        opener.open(AgentLink(listed))
        opener.open(AgentLink(unlisted))
        assertThat(pages).containsExactly("https://cursor.com/agents/$listed", "https://cursor.com/agents/$unlisted").inOrder()
        assertThat(reads).isEmpty()
        assertThat(opener.state).isEqualTo(AgentLinkState.Idle)
    }

    @Test
    fun `the routes, on their own`() {
        val isLoaded = { id: String -> id == listed }
        assertThat(AgentLinkRoute.of(AgentLink(listed), isLoaded)).isEqualTo(AgentLinkRoute.Chat(listed))
        assertThat(AgentLinkRoute.of(AgentLink(unlisted), isLoaded)).isEqualTo(AgentLinkRoute.Fetch(unlisted))
        assertThat(AgentLinkRoute.of(AgentLink(listed, "desktop"), isLoaded)).isEqualTo(AgentLinkRoute.Web("https://cursor.com/agents/$listed#desktop"))
        assertThat(AgentLinkRoute.of(AgentLink(unlisted), isLoaded, inApp = false)).isEqualTo(AgentLinkRoute.Web("https://cursor.com/agents/$unlisted"))
    }
}
