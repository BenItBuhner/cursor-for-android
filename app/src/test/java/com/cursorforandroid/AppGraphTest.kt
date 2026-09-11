package com.cursorforandroid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** What a launch pays for, and what a sign-out takes down when the launch paid for almost none of it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppGraphTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `constructing the graph builds nothing the first frame does not need`() {
        val graph = AppGraph(app)

        assertThat(graph.builtParts()).isEmpty()
        // The session is built, and knowing which backend is current must not be what builds one.
        assertThat(graph.session.current.isDemo).isFalse()
        assertThat(graph.builtParts()).isEmpty()
    }

    @Test
    fun `the demo dataset is seeded by the first call against it, not by entering the demo`() {
        val graph = AppGraph(app)

        runBlocking { graph.session.enterDemo() }
        assertThat(graph.session.current.isDemo).isTrue()
        assertThat(graph.builtParts()).isEmpty()

        runBlocking { graph.session.current.api.listAgents(limit = 1, cursor = null, includeArchived = false) }

        assertThat(graph.builtParts()).contains("demoBackend")
        // Nothing about the demo needs the account's HTTP client.
        assertThat(graph.builtParts()).doesNotContain("realBackend")
        assertThat(graph.builtParts()).doesNotContain("accountClient")
    }

    @Test
    fun `signing out wipes the stores even when nothing in this process ever opened them`() {
        val graph = AppGraph(app)
        val cached = write(File(app.cacheDir, "cursor/agents/list.json"))
        val attachment = write(File(app.filesDir, "attachments/bc-1/run-1/0.png"))
        val generated = write(File(app.filesDir, "generated/bc-1/call-1.png"))
        val draft = write(File(app.filesDir, "draft/composer.json"))
        assertThat(graph.builtParts()).isEmpty()

        runBlocking { graph.signOut() }

        assertThat(cached.exists()).isFalse()
        assertThat(attachment.exists()).isFalse()
        assertThat(generated.exists()).isFalse()
        assertThat(draft.exists()).isFalse()
        // A wipe has to be performed whatever this process touched, so those stores are built here; the
        // repositories the sign-out would only have emptied in memory are left unbuilt. The image loader is in the
        // first group because Coil's disk cache outlives the process that filled it.
        assertThat(graph.builtParts()).containsExactly("attachments", "generatedMedia", "drafts", "artifacts", "media")
    }

    @Test
    fun `signing out of a graph that has built everything still takes all of it down`() {
        val graph = AppGraph(app)
        graph.deferredParts.values.forEach { it.value }
        assertThat(graph.builtParts()).containsExactlyElementsIn(graph.deferredParts.keys)
        val cached = write(File(app.cacheDir, "cursor/agents/list.json"))
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        assertThat(graph.agents.state.value.agents).isNotEmpty()

        runBlocking { graph.signOut() }

        assertThat(graph.agents.state.value.agents).isEmpty()
        assertThat(cached.exists()).isFalse()
    }

    private fun write(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("{}")
        return file
    }
}
