package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Sending a follow-up under a phone's weather — the composer's own send (`ConversationRepository.sendFollowUp`)
 * and the queue's (`FollowUpRepository`) — over the app's real HTTP stack against [FaultServer]. The invariants:
 *
 *  - every send ends in exactly one of: the server has it once and the chat shows it sent; a visible, named error
 *    carrying the server's words (or the connection's, plainly);
 *  - a message the server took is never sent again — not by OkHttp's own retry, not by the queue, not by a retry
 *    after a lost reply;
 *  - the card never flaps: once "sending", never plainly "queued" again; attempts are bounded and paced;
 *  - a refusal that names a wait (`Retry-After`) is waited out, not talked over.
 *
 * Round trips take 300–900 ms, as they do on a phone; nothing here is measured at 60 ms.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendFaultsTest {

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

    private fun prompts() = rig.conversations.state("bc-1").value.items.filterIsInstance<UserMessage>()
    private fun sends() = server.requests(Route.CreateRun)

    private suspend fun open() {
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
        rig.awaitUntil { rig.conversations.state("bc-1").value.let { !it.isLoading && it.activeRunId == "run-1" } }
    }

    // -- the composer's send -------------------------------------------------------------------------------------

    @Test
    fun `over a slow connection the message is filed once and shows as sent`() = runBlocking<Unit> {
        open()
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(result.isSuccess).isTrue()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        assertThat(sends()).hasSize(1)
        rig.awaitUntil { prompts().any { it.text == "Now the tests" && !it.isPending } }
        assertThat(rig.conversations.state("bc-1").value.error).isNull()
        assertThat(rig.agents.agent("bc-1")!!.let { it.isRunning && it.latestRunId == "run-followup-1" }).isTrue()
    }

    @Test
    fun `a refusal in the server's words fails the send once, with those words, and the retry files it once`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Status(503, "unavailable", "Cursor is briefly unavailable. Try again."))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(result.isFailure).isTrue()
        // A POST is never retried by the client on the server's say-so: one request, and the answer shown as given.
        assertThat(sends()).hasSize(1)
        assertThat(result.exceptionOrNull()!!.userMessage()).isEqualTo("Cursor is briefly unavailable. Try again.")
        rig.awaitUntil { rig.conversations.state("bc-1").value.error == "Cursor is briefly unavailable. Try again." }
        assertThat(prompts().none { it.text == "Now the tests" }).isTrue()
        assertThat(server.sent).isEmpty()

        assertThat(rig.conversations.sendFollowUp("bc-1", "Now the tests").isSuccess).isTrue()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        rig.awaitUntil { rig.conversations.state("bc-1").value.error == null }
    }

    @Test
    fun `a rate limit that names its wait fails the send once and says so in the server's words`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "7"))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(result.isFailure).isTrue()
        assertThat(sends()).hasSize(1)
        val words = result.exceptionOrNull()!!.userMessage()
        assertThat(words).contains("Rate limited by Cursor")
        assertThat(words).contains("Too many requests from this key.")
        assertThat(words).contains("7 s")
        assertThat(server.sent).isEmpty()
    }

    @Test
    fun `silence past the read timeout is asked about, tried once more, and filed once`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Silence(processed = false))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertWithMessage("failure: ${result.exceptionOrNull()}").that(result.isSuccess).isTrue()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        // The server was asked whether it had the message between the two attempts.
        assertThat(sends()).hasSize(2)
        assertThat(server.requests(Route.Conversation).size).isAtLeast(2)
        rig.awaitUntil { prompts().any { it.text == "Now the tests" && !it.isPending } }
    }

    @Test
    fun `silence twice over is a named failure, with nothing filed and nothing left pending`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Silence(processed = false), Fault.Silence(processed = false))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.userMessage()).isEqualTo("Cursor took too long to respond.")
        assertThat(sends()).hasSize(2)
        assertThat(server.sent).isEmpty()
        rig.awaitUntil { rig.conversations.state("bc-1").value.error == "Cursor took too long to respond." }
        assertThat(prompts().none { it.text == "Now the tests" }).isTrue()
    }

    @Test
    fun `a reply lost after the server took the message is not sent again, and the message shows as sent`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.LostReply(processed = true))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertWithMessage("sends seen by the server: ${sends().map { it.fault }}").that(sends()).hasSize(1)
        // The server has it once; the chat asked, learned so, and shows the prompt filed under its run.
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        assertThat(result.isSuccess).isTrue()
        rig.awaitUntil { prompts().any { it.text == "Now the tests" && !it.isPending } }
        assertThat(rig.conversations.state("bc-1").value.error).isNull()
        assertThat(rig.agents.agent("bc-1")!!.latestRunId).isEqualTo("run-followup-1")
    }

    @Test
    fun `a reply lost before the server took the message is tried once more, and filed once`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.LostReply(processed = false))
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(result.isSuccess).isTrue()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        assertThat(sends()).hasSize(2)
    }

    @Test
    fun `a body cut half-way after the server took the message is not sent again`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.TruncatedBody)
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertThat(sends()).hasSize(1)
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        assertThat(result.isSuccess).isTrue()
        rig.awaitUntil { prompts().any { it.text == "Now the tests" && !it.isPending } }
    }

    @Test
    fun `a connection reset before the request is read is ridden out, and the message filed once`() = runBlocking<Unit> {
        open()
        // A network handoff: the pooled connection is gone, and the one the send opens is killed as it opens.
        rig.client.connectionPool.evictAll()
        server.resetNextConnections(1)
        val result = rig.conversations.sendFollowUp("bc-1", "Now the tests")
        assertWithMessage("failure: ${result.exceptionOrNull()} sends=${sends()}").that(result.isSuccess).isTrue()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
    }

    @Test
    fun `a host that does not resolve is said plainly, with nothing sent`() = runBlocking<Unit> {
        val offline = FaultRig("http://cursor-for-android.invalid/", folder.newFolder("offline"))
        try {
            val result = offline.conversations.sendFollowUp("bc-1", "Now the tests")
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()!!.userMessage()).isEqualTo("You're offline. Check your connection.")
            offline.awaitUntil { offline.conversations.state("bc-1").value.error == "You're offline. Check your connection." }
        } finally {
            offline.close()
        }
    }

    // -- the queue's send ------------------------------------------------------------------------------------------

    @Test
    fun `queued — a reply lost after the server took the message is confirmed, not resent, and the card never flaps`() = runBlocking<Unit> {
        open()
        val recording = rig.recordQueue("bc-1")
        server.script(Route.CreateRun, Fault.LostReply(processed = true))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        recording.stop()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        assertThat(sends()).hasSize(1)
        recording.assertNoFlap(item.id)
        assertThat(recording.history(item.id)).containsExactly(QueueRecording.Card.QUEUED, QueueRecording.Card.SENDING).inOrder()
        val outcomes = rig.followUps.sendDiagnostics("bc-1")!!.attempts.map { it.outcome }
        assertThat(outcomes).containsExactly("accepted")
        rig.awaitUntil { prompts().any { it.text == "Now the tests" && !it.isPending } }
    }

    @Test
    fun `queued — a refusal in the server's words parks the card with them, once, and retry files it once`() = runBlocking<Unit> {
        open()
        val recording = rig.recordQueue("bc-1")
        server.script(Route.CreateRun, Fault.Status(503, "unavailable", "Cursor is briefly unavailable. Try again."))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        assertThat(rig.followUps.state("bc-1").value.queue.single().error).isEqualTo("Cursor is briefly unavailable. Try again.")
        assertThat(sends()).hasSize(1)
        assertThat(server.sent).isEmpty()
        // Nothing moves on its own: a failure the server named is the user's to retry. Watched for three seconds,
        // ten times the pause a retry would have taken.
        rig.watch(3_000) { assertThat(sends()).hasSize(1) }

        rig.followUps.retry("bc-1", item.id)
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        recording.stop()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        recording.assertNoFlap(item.id)
        // The user's retry clears the reason a moment before the dispatcher claims the message again: the one
        // "queued" after "sending" the card may show.
        assertThat(recording.history(item.id)).containsExactly(QueueRecording.Card.QUEUED, QueueRecording.Card.SENDING, QueueRecording.Card.FAILED, QueueRecording.Card.QUEUED, QueueRecording.Card.SENDING).inOrder()
    }

    @Test
    fun `queued — a rate limit that names its wait is waited out on the card, and the message goes by itself`() = runBlocking<Unit> {
        open()
        val recording = rig.recordQueue("bc-1")
        server.script(Route.CreateRun, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "2"))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.isHeld == true }
        val held = rig.followUps.state("bc-1").value.queue.single()
        assertThat(held.error).isNull()
        assertThat(held.holdReason).contains("Rate limited")
        assertThat(held.serverReason).isEqualTo("Too many requests from this key.")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        recording.stop()
        val attempts = sends()
        assertThat(attempts).hasSize(2)
        assertWithMessage("the second attempt waited out the Retry-After").that(attempts[1].atMillis - attempts[0].atMillis).isAtLeast(2_000L)
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        recording.assertNoFlap(item.id)
        assertThat(recording.history(item.id)).containsExactly(QueueRecording.Card.QUEUED, QueueRecording.Card.SENDING, QueueRecording.Card.HELD).inOrder()
    }

    @Test
    fun `queued — a second rate limit in a row is the user's to hear, in the server's words`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Status(429, "rate_limited", "Too many requests.", retryAfter = "1"), Fault.Status(429, "rate_limited", "Too many requests.", retryAfter = "1"))
        rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        assertThat(rig.followUps.state("bc-1").value.queue.single().error).contains("Too many requests.")
        assertThat(sends()).hasSize(2)
        assertThat(server.sent).isEmpty()
    }

    @Test
    fun `queued — busy refusals while the turn winds down are paced, held on the card, and sent once when the turn ends`() = runBlocking<Unit> {
        open()
        // A steady round trip, so the gaps between attempts are the pauses alone.
        server.rttMillis = 300L..300L
        val recording = rig.recordQueue("bc-1")
        server.busy = true
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.busyRefusals ?: 0 >= 3 }
        val gaps = sends().map { it.atMillis }.zipWithNext { a, b -> b - a }
        assertWithMessage("the pauses between refused attempts grow: $gaps").that(gaps.zipWithNext().all { (a, b) -> b > a }).isTrue()
        server.busy = false
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        recording.stop()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        recording.assertNoFlap(item.id)
        assertThat(recording.history(item.id).first()).isEqualTo(QueueRecording.Card.QUEUED)
        assertThat(recording.history(item.id).drop(2).all { it == QueueRecording.Card.HELD }).isTrue()
        assertThat(rig.agents.agent("bc-1")!!.runStatus).isEqualTo(RunStatus.RUNNING)
    }

    @Test
    fun `queued — silence is asked about and tried once more, silence again is a named failure on the card, and retry files it once`() = runBlocking<Unit> {
        open()
        val recording = rig.recordQueue("bc-1")
        server.script(Route.CreateRun, Fault.Silence(processed = false), Fault.Silence(processed = false))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        assertThat(rig.followUps.state("bc-1").value.queue.single().error).isEqualTo("Cursor took too long to respond.")
        assertThat(sends()).hasSize(2)
        assertThat(server.sent).isEmpty()
        rig.followUps.retry("bc-1", item.id)
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        recording.stop()
        assertThat(server.sent.map { it.first }).containsExactly("Now the tests")
        recording.assertNoFlap(item.id)
    }
}
