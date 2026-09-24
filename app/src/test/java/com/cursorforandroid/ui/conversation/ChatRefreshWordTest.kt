package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ctrl+R and Ctrl+Shift+R on a chat, against the demo backend. Ctrl+R is the pull's catch-up, answered where the pull
 * is (see [CatchUpStatus]); Ctrl+Shift+R reads the whole chat again and leaves its word on it. The demo's first paint
 * carries the conversation's own ids and the read again rebuilds the chat from its run's trace, under new ones — the
 * same reply, which is not new.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatRefreshWordTest {

    // Real time: the catch-up's answer is held on screen for a moment, and a virtual clock no one advances would hold it forever.
    @get:Rule
    val mainDispatcher = MainDispatcherRule { Dispatchers.Unconfined }

    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.session.enterDemo()
        graph.agents.refresh()
    }

    private fun opened(agentId: String): ConversationViewModel = ConversationViewModel(graph, agentId).also { vm ->
        runBlocking { withTimeout(20_000) { vm.conversation.first { !it.isLoading && it.items.isNotEmpty() } } }
    }

    private fun ConversationViewModel.word(): String? = runBlocking { withTimeout(20_000) { toastMessage.first { it != null } } }

    private fun ConversationViewModel.answer(): CatchUpStatus = runBlocking {
        withTimeout(20_000) { catchUpStatus.first { it is CatchUpStatus.Done || it is CatchUpStatus.Failed } }
    }

    @Test
    fun `Ctrl+R on a chat with nothing new says it is up to date`() {
        val vm = opened(HOUSE_ID)
        vm.catchUp()
        assertThat(vm.answer()).isEqualTo(CatchUpStatus.Done(newMessages = 0, changed = false))
    }

    @Test
    fun `Ctrl+R pressed as the chat opens waits for its first read, and counts nothing that read brought`() {
        runBlocking {
            val house = DemoData.seeds.first { it.id == HOUSE_ID }
            graph.caches.conversations.write(CachedConversation(HOUSE_ID, DemoData.transcript(house), runs = emptyList()))
        }
        val vm = ConversationViewModel(graph, HOUSE_ID)
        vm.catchUp()
        assertThat((vm.answer() as CatchUpStatus.Done).newMessages).isEqualTo(0)
        assertThat(vm.conversation.value.items).isNotEmpty()
        assertThat(vm.toastMessage.value).isNull()
    }

    @Test
    fun `Ctrl+Shift+R reads the transcript again from nothing, and says it is up to date all the same`() {
        val vm = opened(HOUSE_ID)
        vm.reloadTranscriptWithWord()
        assertThat(vm.word()).isEqualTo(RefreshWord.UP_TO_DATE)
        assertThat(vm.conversation.value.items).isNotEmpty()
    }

    private companion object {
        const val HOUSE_ID = "bc-demo-0005"
    }
}
