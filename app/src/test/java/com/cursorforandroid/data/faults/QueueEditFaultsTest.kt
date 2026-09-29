package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A queued message taken back to edit while the composer holds a draft, over the app's real HTTP stack against
 * [FaultServer]: the draft that takes its place in line goes out, once, on a phone's 300–900 ms round trips.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueueEditFaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        server.addIdleAgent("bc-1", "Agent", "run-1")
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private suspend fun open() {
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
        rig.awaitUntil { rig.conversations.state("bc-1").value.let { !it.isLoading && it.activeRunId == "run-1" } }
    }

    @Test
    fun `a refused head edited with a draft in the composer - the draft is filed once`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Status(503, "unavailable", "Cursor is briefly unavailable. Try again."))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        rig.followUps.setDraftText("bc-1", "Also bump the version")

        rig.followUps.takeForEdit("bc-1", item.id)

        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        assertThat(server.sent.map { it.first }).containsExactly("Also bump the version")
        assertThat(server.requests(Route.CreateRun)).hasSize(2)
        assertThat(rig.followUps.state("bc-1").value.draft.text).isEqualTo("Now the tests")
    }
}
