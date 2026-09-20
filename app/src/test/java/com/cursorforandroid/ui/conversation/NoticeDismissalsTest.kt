package com.cursorforandroid.ui.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Closing the record's notice over a real chat on the app's own stack (Extended mode, the account service refusing
 * the record): the X hides it now and on the next open; it stays hidden while the refusal reads the same; it comes
 * back when the refusal changes its words, and when the record is served and then refused again; and the
 * diagnostics the menu shares carry the refusal whether or not the card is on screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NoticeDismissalsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val rateLimited = FaultServer.Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "2")

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 0L..0L).start()
        val firstAt = 1_800_000_000_000L - 6 * LongProject.TURN_SPACING_MS
        val turns = LongProject.turns(firstAt, turns = 6)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(newest.startedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        server.outage(FaultServer.Route.Stream, FaultServer.Fault.StreamCut(events = newest.log.size), path = "/${newest.runId}/")
        server.outage(FaultServer.Route.Record, rateLimited)
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun state(): ConversationState = rig!!.conversations.state(agentId).value

    /** The load has ended with a transcript on screen — the state the dock reads its notices from. */
    private suspend fun FaultRig.awaitSettled(where: (ConversationState) -> Boolean = { true }) = awaitUntil(30_000) { state().let { LoadNotices.isSettled(it) && where(it) } }

    private fun recordNotice(): LoadNotice = LoadNotices.recordFallback(state().recordFallback!!)

    @Test
    fun `closing the record's notice hides it now and on the next open, until it changes or clears and recurs`() = runBlocking<Unit> {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), extended = true).also { rig = it }
        val conversations = rig.conversations
        val dismissals = NoticeDismissals(agentId, rig.prefs, conversations.state(agentId), rig.scope)
        conversations.attach(agentId)
        rig.awaitSettled { it.recordFallback != null }
        val notice = recordNotice()
        assertThat(notice.title).contains("Too many requests")
        // Nothing closed yet, once the device's record has been read: the notice shows.
        rig.awaitUntil { dismissals.hidden.value != null }
        assertThat(LoadNotices.shown(state(), dismissals.hidden.value)).containsExactly(notice)

        // The X: hidden, and remembered on the device for this chat.
        dismissals.dismiss(notice)
        rig.awaitUntil { LoadNotices.shown(state(), dismissals.hidden.value).isEmpty() }
        rig.awaitUntil { rig.prefs.dismissedNotices.first()[agentId] == setOf(notice.identity) }
        // The diagnostics still carry the refusal: closing the card changes what is shown, not what is known.
        val record = conversations.loadDiagnostics(agentId)!!.record!!
        assertThat(record.error).contains("Too many requests")
        assertThat(record.fallback!!.text).startsWith("fallback=runs")
        assertThat(state().recordFallback).isNotNull()

        // The next open reads the same refusal: still closed, from the first frame the record has been read.
        val reopened = NoticeDismissals(agentId, rig.prefs, conversations.state(agentId), rig.scope)
        rig.awaitUntil { reopened.hidden.value != null }
        assertThat(LoadNotices.shown(state(), reopened.hidden.value)).isEmpty()
        // A refresh that ends in the same refusal (a new one, stamped with the clock as moved on) keeps it closed.
        rig.now += 60_000
        conversations.reload(agentId)
        rig.awaitSettled { it.recordFallback?.sinceMillis == rig.now }
        assertThat(recordNotice().identity).isEqualTo(notice.identity)
        assertThat(LoadNotices.shown(state(), dismissals.hidden.value)).isEmpty()
        assertThat(rig.prefs.dismissedNotices.first()[agentId]).containsExactly(notice.identity)

        // The server's words change: a different notice, which shows — and the one that is gone is forgotten.
        server.outage(FaultServer.Route.Record, FaultServer.Fault.Status(503, "unavailable", "Service unavailable"))
        rig.now += 60_000
        conversations.reload(agentId)
        rig.awaitSettled { it.recordFallback?.reason?.contains("Service unavailable") == true }
        val reworded = recordNotice()
        assertThat(reworded.identity).isNotEqualTo(notice.identity)
        rig.awaitUntil { LoadNotices.shown(state(), dismissals.hidden.value) == listOf(reworded) }
        rig.awaitUntil { rig.prefs.dismissedNotices.first()[agentId] == null }
        dismissals.dismiss(reworded)
        rig.awaitUntil { rig.prefs.dismissedNotices.first()[agentId] == setOf(reworded.identity) }

        // The record is served: the condition has cleared, and the closing is forgotten with it.
        server.clear(FaultServer.Route.Record)
        rig.now += 60_000
        conversations.reload(agentId)
        rig.awaitSettled { it.recordFallback == null }
        rig.awaitUntil { dismissals.hidden.value == emptySet<String>() }
        rig.awaitUntil { rig.prefs.dismissedNotices.first()[agentId] == null }

        // Refused again, in the words closed before, with the record's window emptied by "Reload transcript": the
        // notice is new to the reader and shows.
        server.outage(FaultServer.Route.Record, FaultServer.Fault.Status(503, "unavailable", "Service unavailable"))
        rig.now += 60_000
        conversations.reloadTranscript(agentId)
        rig.awaitSettled { it.recordFallback != null }
        assertThat(recordNotice().identity).isEqualTo(reworded.identity)
        rig.awaitUntil { LoadNotices.shown(state(), dismissals.hidden.value) == listOf(recordNotice()) }
        assertThat(rig.prefs.dismissedNotices.first()[agentId]).isNull()
    }
}
