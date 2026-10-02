package com.cursorforandroid.data.faults

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A picture attached to a sent follow-up shows on its bubble the instant it is sent — and must still be there once
 * the conversation is read again: a swipe-up reload, the chat reopened, the app restarted. Bennett's frame of a
 * reloaded chat: "Alright, so what am I supposed to install for Steam?" as a bubble of words alone, the screenshot
 * it carried gone.
 *
 * `GET /v0/agents/{id}/conversation` returns a user message as text only, so the device's own copy of the picture
 * — filed under the run the message started — was the only one there was. A message the coordinator took into the
 * turn under way starts no run: once the account's record carries it (a `turn_steer` turn, drawn from the record
 * from then on), nothing names its copy, and the bubble loses the picture live, before any restart. A message that
 * did start a run loses it whenever the record's turn and its run are not paired. The record itself carries the
 * message's images (`agent.v1.UserMessage.selected_context.selected_images`, inline or in blobs of their own) and
 * the id the client minted for the message: what is pinned here is that the bubble is drawn with the picture from
 * the record's copy when the record has it, and from the device's copy — by the message's id, else by its words
 * and the moment it was sent — when the record gives the words alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SentImageAfterReloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt, turns = TURNS)
    private val live get() = turns.last()
    private var recorder: Job? = null
    private val frames = CopyOnWriteArrayList<ConversationState>()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 100L..250L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        server.namesQueuedRuns = true
        server.datesQueuedRunsAtQueue = true
        server.queueLagMs = 2_000L
        server.recordsDeliveries = true
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.let { it.steering.detach(agentId); it.close() }
        server.close()
    }

    private fun rig(root: File): FaultRig = FaultRig(server.baseUrl, root, readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA, queuePollMs = 1_000L).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    private fun dump(label: String, s: ConversationState = state) {
        println("== $label: run=${s.runStatus} active=${s.activeRunId} streaming=${s.isStreaming} loading=${s.isLoading}")
        s.items.takeLast(12).forEach { item ->
            when (item) {
                is UserMessage -> println("   user(${item.text.take(32)}) id=${item.id} attachments=${item.attachments.map { "${File(it.path).name}:${File(it.path).isFile}" }}")
                is AssistantMessage -> println("   assistant(${item.markdown.take(32)})")
                else -> println("   ${item::class.simpleName}(${item.id.take(40)})")
            }
        }
    }

    /** The sets on this device's disk, with what names each (see `AttachmentStore.forAgent`): what a bubble without its picture could not find. */
    private fun dumpAttachments() {
        val root = rig?.context?.filesDir?.let { File(it, "attachments") } ?: return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            println("   disk: ${file.relativeTo(root)}" + if (file.name == "meta.json") " " + file.readText().take(300) else " (${file.length()} bytes)")
        }
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { dump("TIMEOUT waiting for $label"); throw t }
    }

    /** Gives the picture time to be read from the record or the disk; what the bubble then shows is for [assertShowsPicture] to say. */
    private suspend fun FaultRig.awaitPicture(text: String) {
        runCatching { awaitUntil(15_000) { pictureOn(text) != null } }
    }

    private suspend fun FaultRig.open() {
        server.composers[agentId] = FaultServer.Composer(agentId, LongProject.AGENT_NAME, live.startedAt, running = true, project = true)
        agents.refresh()
        awaitUntilOr(30_000, "the row placed") { agents.agent(agentId)?.isProjectRoot == true }
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder?.cancel()
        recorder = scope.launch { conversations.state(agentId).collect { frames += it } }
    }

    /** Reopened: the same chat read again, on the same disk, in a new process. */
    private suspend fun FaultRig.reopen(root: File): FaultRig {
        recorder?.cancel()
        steering.detach(agentId)
        close()
        frames.clear()
        return rig(root).also { it.open() }
    }

    /** What the composer does mid-turn with a picture attached: the message staged with it, filed with the account under an id minted here. */
    private suspend fun FaultRig.sendMidTurn(text: String, image: PromptImage): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.queueAhead(agentId, text, images = listOf(image), followupId = followupId)
        conversations.sendQueuedVia(agentId, staged, followupId) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, images = listOf(image), followupId = followupId), refresh = false).getOrThrow()
        }.getOrThrow()
        steering.refreshQueue(agentId)
        return followupId
    }

    private fun bubble(text: String, s: ConversationState = state): UserMessage? = s.items.filterIsInstance<UserMessage>().singleOrNull { it.text == text }

    private fun pictureOn(text: String, s: ConversationState = state): MessageAttachment? = bubble(text, s)?.attachments?.singleOrNull { !it.isFile && File(it.path).isFile }

    /** The one bubble of [text] shows exactly one picture, decodable from a file on this device (what the viewer opens on a tap). */
    private fun assertShowsPicture(label: String, text: String) {
        val message = bubble(text)
        assertWithMessage("$label: the message is not shown once: ${state.items.filterIsInstance<UserMessage>().map { it.text.take(32) }}").that(message).isNotNull()
        val attachments = message!!.attachments
        if (attachments.size != 1 || !File(attachments.single().path).isFile) { dump("$label (picture missing)"); dumpAttachments() }
        assertWithMessage("$label: the bubble shows ${attachments.size} attachments, not the one picture sent with it").that(attachments).hasSize(1)
        val picture = attachments.single()
        assertWithMessage("$label: the picture is a file card").that(picture.isFile).isFalse()
        assertWithMessage("$label: the picture's file is gone: ${picture.path}").that(File(picture.path).isFile).isTrue()
        assertWithMessage("$label: the picture has no size").that(picture.width > 0 && picture.height > 0).isTrue()
    }

    /** No frame shown since the message was filed drew its bubble without the picture. */
    private fun assertEveryFrameShowsPicture(label: String, text: String) {
        frames.forEachIndexed { i, f ->
            val message = bubble(text, f) ?: return@forEachIndexed
            if (message.isPending) return@forEachIndexed
            if (message.attachments.size != 1) dump("$label frame $i (picture missing)", f)
            assertWithMessage("$label frame $i: the bubble drawn without its picture").that(message.attachments).hasSize(1)
        }
    }

    @Test
    fun `Beta - a picture on a message the turn under way took stays, live and after a reopen, the record carrying it`() = runBlocking<Unit> {
        takenIntoTheTurn(recordCarriesImages = true)
    }

    @Test
    fun `Beta - a picture on a message the turn under way took stays after a reopen when the record keeps the words alone`() = runBlocking<Unit> {
        takenIntoTheTurn(recordCarriesImages = false)
    }

    /**
     * Bennett's frame: the message queued mid-turn with a screenshot, taken into the turn under way by the coordinator
     * and answered there; the record carries it as a `turn_steer` turn with no run of its own. The bubble keeps the
     * screenshot once the record has the turn, through the next run starting, and after the chat is reopened in a
     * new process — from the record's copy of the picture, or from the device's when the record keeps the words alone.
     */
    private suspend fun takenIntoTheTurn(recordCarriesImages: Boolean) {
        server.recordsImages = recordCarriesImages
        val root = folder.newFolder("rig")
        val rig = rig(root)
        rig.open()
        rig.awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } }
        val followupId = rig.sendMidTurn(MESSAGE, picture())
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        rig.now += 20_000
        server.logs[live.runId] = server.logs.getValue(live.runId) + ("assistant" to """{"text":"$LAST_WORDS"}""")
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("${live.runId}-last", "assistant_message", LAST_WORDS)
        rig.awaitUntilOr(30_000, "the turn's last words") { state.items.any { it is AssistantMessage && it.markdown.contains(LAST_WORDS) } }
        // The coordinator takes the message between its steps, works on it and answers it, all in the turn under way;
        // the account's record files the message (its picture with it, or not) as the turn's steer.
        rig.now += 5_000
        assertWithMessage("the turn took the message").that(server.takeIntoTurn(agentId, followupId)).isEqualTo(live.runId)
        server.logs[live.runId] = server.logs.getValue(live.runId) +
            ("tool_call" to """{"callId":"call-taken","name":"shell","status":"completed","args":{"command":"ls"},"result":{"success":{"stdout":"app"}}}""") +
            ("assistant" to """{"text":"$REPLY"}""")
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("${live.runId}-reply", "assistant_message", REPLY)
        server.records[agentId] = server.records.getValue(agentId) + listOf(buildJsonObject { put("text", REPLY) }, buildJsonObject { put("text", ""); put("isMessageDone", true) })
        rig.awaitUntilOr(30_000, "the answer streamed") { state.items.any { it is AssistantMessage && it.markdown.contains(REPLY) } }
        // The turn ends; the run the account named starts, nothing streamed yet. The record is read again with it.
        rig.now += 10_000
        server.endTurn(agentId, durationMs = rig.now - live.startedAt)
        val startedAt = LongProject.iso(rig.now)
        server.runs[named] = RunDto(id = named, agentId = agentId, status = "CREATING", createdAt = startedAt, updatedAt = startedAt)
        server.logs[named] = listOf("status" to """{"runId":"$named","status":"CREATING"}""")
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/$named/")
        server.agents[agentId] = server.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = named, updatedAt = startedAt)
        rig.conversations.revalidate(agentId, force = true)
        rig.awaitUntilOr(45_000, "the message filed") { bubble(MESSAGE) != null && rig.conversations.loadDiagnostics(agentId)?.runsLoaded?.let { it >= TURNS + 1 } == true }
        delay(6_000)
        dump("live: the named run starting")
        assertShowsPicture("live, the named run starting", MESSAGE)
        assertEveryFrameShowsPicture("live", MESSAGE)

        val after = rig.reopen(root)
        after.awaitUntilOr(60_000, "the reopened chat loaded") { !state.isLoading && bubble(MESSAGE) != null && state.items.any { it is AssistantMessage && it.markdown.contains(REPLY) } }
        delay(8_000)
        dump("reopened")
        assertShowsPicture("after a reopen", MESSAGE)
        assertEveryFrameShowsPicture("reopened", MESSAGE)
    }

    @Test
    fun `Beta - a picture sent from another device is drawn from the record, inline, and kept across a reopen`() = runBlocking<Unit> {
        sentElsewhere(imageBlobs = false)
    }

    @Test
    fun `Beta - a picture sent from another device is drawn from the record, from its blob, and kept across a reopen`() = runBlocking<Unit> {
        sentElsewhere(imageBlobs = true)
    }

    /**
     * A message this device never sent — from the desktop, with a screenshot — whose turn the record carries with the
     * picture (`selected_images[]`, its bytes inline or in a blob of its own): the bubble shows the picture from the
     * record's copy, kept on this device so the viewer opens it, and still shows it after a reopen.
     */
    private suspend fun sentElsewhere(imageBlobs: Boolean) {
        server.imageBlobs = imageBlobs
        val image = picture()
        // Steered into the turn under way from the desktop, with a screenshot: a turn of the record with no run of its own.
        val turn = listOf(
            server.humanMessage(ELSEWHERE, messageId = "msg-from-the-desktop", images = listOf(FaultServer.SentImage("image/png", Base64.getEncoder().encodeToString(image.bytes))), steer = true, projectMode = true),
            buildJsonObject { put("text", ELSEWHERE_REPLY) },
            buildJsonObject { put("text", ""); put("isMessageDone", true) },
        )
        server.records[agentId] = turns.flatMap { it.record } + turn
        server.transcripts[agentId] = LongProject.v0Transcript(turns) +
            listOf(V0ConversationMessageDto("${live.runId}-elsewhere", "user_message", ELSEWHERE), V0ConversationMessageDto("${live.runId}-elsewhere-a", "assistant_message", ELSEWHERE_REPLY))
        val root = folder.newFolder("rig")
        val rig = rig(root)
        rig.open()
        rig.awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } && bubble(ELSEWHERE) != null }
        rig.awaitPicture(ELSEWHERE)
        dump("open (blobs=$imageBlobs)")
        assertShowsPicture("open, the record's copy (blobs=$imageBlobs)", ELSEWHERE)

        val after = rig.reopen(root)
        after.awaitUntilOr(60_000, "the reopened chat loaded") { !state.isLoading && bubble(ELSEWHERE) != null }
        after.awaitPicture(ELSEWHERE)
        dump("reopened (blobs=$imageBlobs)")
        assertShowsPicture("after a reopen (blobs=$imageBlobs)", ELSEWHERE)
        assertEveryFrameShowsPicture("reopened (blobs=$imageBlobs)", ELSEWHERE)
    }

    /** A small opaque PNG, as the composer would send one. */
    private fun picture(): PromptImage {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(214, 108, 52)) }
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        return PromptImage(out.toByteArray(), "image/png")
    }

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "Alright, so what am I supposed to install for Steam?"
        const val REPLY = "Install the Proton runtime from the screenshot's list; nothing else is needed."
        const val LAST_WORDS = "That settles the launcher question."
        const val ELSEWHERE = "Here is the crash from the desktop build."
        const val ELSEWHERE_REPLY = "That trace points at the shader cache; clearing it is the fix."
    }
}
