package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric only because the store persists through [SecureKeyStore], which needs a Context. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class McpServerStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `saves, toggles and deletes persist across store instances`() {
        val secure = SecureKeyStore(context)
        val store = McpServerStore(secure)
        assertThat(store.servers.value).isEmpty()

        val linear = McpServer(id = "1", name = "linear", url = "https://mcp.linear.app/mcp", headers = mapOf("Authorization" to "Bearer k"))
        val github = McpServer(id = "2", name = "github", transport = McpTransport.Stdio, command = "npx", args = listOf("-y", "srv"), env = mapOf("TOKEN" to "t"))
        store.save(linear)
        store.save(github)
        store.setEnabled("1", false)
        assertThat(store.enabled()).containsExactly(github)

        // Replacing by id keeps the position; a fresh store reads the same list back from the encrypted prefs.
        store.save(github.copy(command = "bunx"))
        val reloaded = McpServerStore(SecureKeyStore(context))
        assertThat(reloaded.servers.value.map { it.id to it.command }).containsExactly("1" to "", "2" to "bunx").inOrder()
        assertThat(reloaded.servers.value.first().enabled).isFalse()
        assertThat(reloaded.servers.value.last().env).containsExactly("TOKEN", "t")

        reloaded.delete("1")
        reloaded.delete("2")
        assertThat(McpServerStore(SecureKeyStore(context)).servers.value).isEmpty()
        assertThat(secure.mcpServersJson()).isNull()
    }
}
