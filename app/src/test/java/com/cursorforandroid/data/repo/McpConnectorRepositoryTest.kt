package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectorStatusReport
import com.cursorforandroid.data.api.McpConnectorApi
import com.cursorforandroid.domain.ConnectorStatus
import com.cursorforandroid.domain.ConnectorTransport
import com.cursorforandroid.domain.McpConnector
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

/** The "+" menu's account list: listing, switching with a revert on refusal, sign-ins, and the mode's gate. */
class McpConnectorRepositoryTest {

    private class FakeApi : McpConnectorApi {
        var rows = listOf(
            McpConnector(3, "Sentry", url = "https://mcp.sentry.dev/mcp", enabled = true, pluginId = "42"),
            McpConnector(1, "Linear", url = "https://mcp.linear.app/mcp", enabled = true),
            McpConnector(2, "Notion", url = "https://mcp.notion.com/mcp"),
            McpConnector(4, "playwright", transport = ConnectorTransport.Stdio, enabled = true),
            McpConnector(5, "Slack", url = "https://mcp.slack.com/mcp", required = true, enabled = true),
        )
        var listFails: Exception? = null
        var setFails: Exception? = null
        var statusFails: Exception? = null
        val sets = mutableListOf<List<Int>>()
        val statusCalls = mutableListOf<List<Int>>()
        var authState = 1
        var listCalls = 0
        var logoCalls = 0

        override suspend fun list(): List<McpConnector> {
            listCalls++
            listFails?.let { throw it }
            return rows
        }

        override suspend fun setEnabled(enabledIds: List<Int>) {
            setFails?.let { throw it }
            sets += enabledIds
        }

        override suspend fun statuses(ids: List<Int>): List<ConnectorStatusReport> {
            statusCalls += ids
            statusFails?.let { throw it }
            return ids.mapNotNull { id ->
                when (id) {
                    1 -> ConnectorStatusReport(1, available = false, requiresAuth = true, authUrl = "https://linear.app/oauth?state=${authState++}", error = null)
                    2, 3 -> ConnectorStatusReport(id, available = true, requiresAuth = false, authUrl = null, error = null)
                    else -> null
                }
            }
        }

        override suspend fun pluginLogos(): Map<String, String> {
            logoCalls++
            return mapOf("42" to "https://cdn/sentry.png")
        }
    }

    private val api = FakeApi()
    private var allowed = true
    private val repo = McpConnectorRepository(api = { api }, allowed = { allowed }, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun row(id: Int) = repo.state.value.connectors.first { it.id == id }

    @Test
    fun `a refresh lists by name with logos, and checks the enabled HTTP servers`() = runBlocking<Unit> {
        repo.refresh().join()

        val state = repo.state.value
        assertThat(state.loaded).isTrue()
        assertThat(state.loading).isFalse()
        assertThat(state.connectors.map { it.name }).containsExactly("Linear", "Notion", "playwright", "Sentry", "Slack").inOrder()
        assertThat(api.statusCalls).containsExactly(listOf(1, 3, 5))
        assertThat(row(1).status).isEqualTo(ConnectorStatus.NeedsAuth)
        assertThat(row(1).authUrl).isEqualTo("https://linear.app/oauth?state=1")
        assertThat(row(3).status).isEqualTo(ConnectorStatus.Connected)
        assertThat(row(3).logoUrl).isEqualTo("https://cdn/sentry.png")
        assertThat(row(2).status).isEqualTo(ConnectorStatus.Unchecked)
        assertThat(row(4).status).isEqualTo(ConnectorStatus.Unchecked)
        // Left out of the answer: back to the listing's hint rather than stuck checking.
        assertThat(row(5).status).isEqualTo(ConnectorStatus.Unchecked)

        repo.refresh().join()
        assertThat(api.logoCalls).isEqualTo(1)
    }

    @Test
    fun `a list that cannot be read says why and keeps nothing`() = runBlocking<Unit> {
        api.listFails = IOException("Unauthenticated")
        repo.refresh().join()

        assertThat(repo.state.value.error).isEqualTo("Unauthenticated")
        assertThat(repo.state.value.connectors).isEmpty()
        assertThat(repo.state.value.loading).isFalse()
    }

    @Test
    fun `switching on sends the whole enabled set, then checks the new one`() = runBlocking<Unit> {
        repo.refresh().join()
        api.statusCalls.clear()

        repo.setEnabled(2, true).join()

        // In the rows' order, by name.
        assertThat(api.sets).containsExactly(listOf(1, 2, 4, 3, 5))
        assertThat(row(2).enabled).isTrue()
        assertThat(api.statusCalls).containsExactly(listOf(2))
        assertThat(row(2).status).isEqualTo(ConnectorStatus.Connected)
    }

    @Test
    fun `switching off clears the row's status`() = runBlocking<Unit> {
        repo.refresh().join()

        repo.setEnabled(1, false).join()

        assertThat(api.sets).containsExactly(listOf(4, 3, 5))
        assertThat(row(1).enabled).isFalse()
        assertThat(row(1).status).isEqualTo(ConnectorStatus.Unchecked)
        assertThat(row(1).authUrl).isNull()
    }

    @Test
    fun `a refused switch is put back with a notice`() = runBlocking<Unit> {
        repo.refresh().join()
        val before = row(2)
        api.setFails = IOException("permission denied")

        repo.setEnabled(2, true).join()

        assertThat(row(2)).isEqualTo(before)
        assertThat(repo.state.value.notice).isEqualTo("Couldn't turn on Notion: permission denied")
        repo.clearNotice()
        assertThat(repo.state.value.notice).isNull()
    }

    @Test
    fun `a required connector is not switched`() = runBlocking<Unit> {
        repo.refresh().join()

        repo.setEnabled(5, false).join()

        assertThat(api.sets).isEmpty()
        assertThat(row(5).enabled).isTrue()
    }

    @Test
    fun `a sign-in asks for a fresh URL, and coming back checks again`() = runBlocking<Unit> {
        repo.refresh().join()

        assertThat(repo.signInUrl(1)).isEqualTo("https://linear.app/oauth?state=2")
        api.statusCalls.clear()
        repo.onReturn()
        assertThat(api.statusCalls).containsExactly(listOf(1, 3, 5))

        api.statusCalls.clear()
        repo.onReturn()
        assertThat(api.statusCalls).isEmpty()
    }

    @Test
    fun `a failed check marks the rows as errors rather than leaving them checking`() = runBlocking<Unit> {
        api.statusFails = IOException("timeout")
        repo.refresh().join()

        assertThat(row(1).status).isEqualTo(ConnectorStatus.Error)
        assertThat(row(1).error).isEqualTo("timeout")
        assertThat(row(2).status).isEqualTo(ConnectorStatus.Unchecked)
    }

    @Test
    fun `with the mode off nothing is called and the list stays empty`() = runBlocking<Unit> {
        allowed = false
        repo.refresh().join()
        repo.setEnabled(1, false).join()

        assertThat(api.listCalls).isEqualTo(0)
        assertThat(api.sets).isEmpty()
        assertThat(repo.state.value).isEqualTo(ConnectorsState())
    }

    @Test
    fun `a reset forgets the account's list`() = runBlocking<Unit> {
        repo.refresh().join()
        repo.reset()

        assertThat(repo.state.value).isEqualTo(ConnectorsState())
        repo.refresh().join()
        assertThat(api.logoCalls).isEqualTo(2)
    }
}
