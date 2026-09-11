package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric only because the store persists through [SecureKeyStore], which needs a Context. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class McpServerStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Robolectric has no Android Keystore, so the encrypted store is stood in for by an ordinary private file. */
    private fun secureStore() = SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }

    /** Reads and writes on the caller's thread, so the assertions below need no scheduler. */
    private fun store(secure: SecureKeyStore = secureStore()) = McpServerStore(secure, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `saves, toggles and deletes persist across store instances`() {
        val secure = secureStore()
        val store = store(secure)
        assertThat(store.servers.value).isEmpty()

        val linear = McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp", headers = mapOf("Authorization" to "Bearer k"))
        val github = McpServer(id = "2", name = "github", transport = McpTransport.Stdio, command = "npx", args = listOf("-y", "srv"), env = mapOf("TOKEN" to "t"))
        store.save(linear)
        store.save(github)
        store.setEnabled("1", false)
        assertThat(runBlocking { store.enabled() }).containsExactly(github)

        // Replacing by id keeps the position; a fresh store reads the same list back from the encrypted prefs.
        store.save(github.copy(command = "bunx"))
        val reloaded = store()
        assertThat(reloaded.servers.value.map { it.id to it.command }).containsExactly("1" to "", "2" to "bunx").inOrder()
        assertThat(reloaded.servers.value.first().enabled).isFalse()
        assertThat(reloaded.servers.value.last().env).containsExactly("TOKEN", "t")

        reloaded.delete("1")
        reloaded.delete("2")
        assertThat(store().servers.value).isEmpty()
        assertThat(secure.mcpServersJson()).isNull()
    }

    @Test
    fun `the list is not read on the thread that asks for it`() = runTest {
        store().save(McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp"))

        val scheduler = StandardTestDispatcher(testScheduler)
        val store = McpServerStore(secureStore(), TestScope(scheduler))

        // Nothing has run yet: composition gets an empty list to draw and the flow fills in behind it.
        assertThat(store.servers.value).isEmpty()
        advanceUntilIdle()
        assertThat(store.servers.value.map { it.name }).containsExactly("linear")
    }

    /**
     * The prompt's servers are asked for from the send lambda, on the main thread, so the read cannot happen there.
     * The store is given a scheduler of its own here: letting the caller's run to a standstill must not be enough
     * to answer, because the answer is the store's read, and it must not come back empty either.
     */
    @Test
    fun `the servers a prompt carries wait for the store's own read`() = runTest {
        val secure = secureStore()
        store(secure).save(McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp"))

        val storeScheduler = TestCoroutineScheduler()
        val store = McpServerStore(secure, CoroutineScope(StandardTestDispatcher(storeScheduler)))

        val enabled = async { store.enabled() }
        advanceUntilIdle()
        assertThat(enabled.isCompleted).isFalse()

        storeScheduler.advanceUntilIdle()
        advanceUntilIdle()
        assertThat(enabled.await().map { it.name }).containsExactly("linear")
    }

    @Test
    fun `a server saved before the stored list arrives does not wipe it`() = runTest {
        val secure = secureStore()
        store(secure).save(McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp"))

        val store = McpServerStore(secure, TestScope(StandardTestDispatcher(testScheduler)))
        // The user opens the menu on the empty first frame and adds a server before the read has landed.
        store.save(McpServer(id = "2", name = "github", url = "https://mcp.github.com/mcp"))
        assertThat(store.servers.value.map { it.name }).containsExactly("github")

        advanceUntilIdle()
        assertThat(store.servers.value.map { it.name }).containsExactly("linear", "github").inOrder()
        assertThat(store().servers.value.map { it.name }).containsExactly("linear", "github").inOrder()
    }

    @Test
    fun `whichever write lands last writes the newest list`() = runTest {
        val secure = secureStore()
        val store = McpServerStore(secure, TestScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()

        store.save(McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp"))
        store.save(McpServer(id = "2", name = "github", url = "https://mcp.github.com/mcp"))
        store.setEnabled("1", false)
        advanceUntilIdle()

        assertThat(store().servers.value.map { it.name to it.enabled })
            .containsExactly("linear" to false, "github" to true).inOrder()
    }
}
