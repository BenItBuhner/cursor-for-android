package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.local.BlobDiskStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Bennett's 2026-09-24 frame (v0.3.92, Extended, a Project of 1,444 turns, window [1384,1444)): under the last
 * "Worked 59m 44s" the notice "Couldn't refresh the transcript: Comparison method violates its general contract!",
 * the transcript stuck at what an earlier read had painted, the open 54.8 s with the first paint at 32.5 s. The chat's
 * blobs on the phone ran to thousands (`prefetched=4222`) and filled the store: the store sorted its files asking each
 * its time on every comparison while the chat's own readers stamped the files they read, so the sort threw — from the
 * state read's list of blobs held (every refresh failed) and from the trim each write past the budget ran (the blob
 * read that wrote failed, and the store, still over, trimmed again on the next write, and the next).
 *
 * The Project opened here at a phone's round trip (300–900 ms) and bandwidth, the disk full of another chat's blobs
 * and a reader stamping blobs the whole time, as the phone's readers do. Every read, and every Retry after, paints
 * without a word of failure; the report gives the first paint for the record.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FullBlobStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val agentId = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private lateinit var turns: List<BigProject.Turn>
    private val stamping = AtomicBoolean(true)
    private var reader: Thread? = null

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L).start()
        server.bytesPerSecond = 600_000L
        server.pageSize = 100
        val firstAt = now - BigProject.TURNS * BigProject.TURN_SPACING_MS - 60_000L
        turns = BigProject.turns(firstAt)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(firstAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = BigProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
    }

    @After
    fun tearDown() {
        stamping.set(false)
        reader?.join()
        rigs.forEach { it.close() }
        server.close()
    }

    /** The store under [root] holding [count] blobs of another chat of [bytes] each: what the Project's workers' chats left on the phone. */
    private fun fillWithAnotherChat(root: File, count: Int, bytes: Int) = runBlocking {
        val store = BlobDiskStore(JsonDiskCache(File(root, "blobs"), dispatcher = Dispatchers.IO))
        repeat(count) { i -> store.write("bc-worker-a1b2", "worker-blob-$i", ByteArray(bytes)) }
    }

    /** A reader stamping blobs under [root] one after another at random, as a read of a blob from the disk stamps it; the files listed again every 100 ms. */
    private fun readerStamping(root: File) {
        reader = thread(isDaemon = true) {
            val random = Random(24)
            var files = emptyList<File>()
            var listedAt = 0L
            while (stamping.get()) {
                if (System.nanoTime() - listedAt > 100_000_000L) {
                    files = File(root, "blobs").walkTopDown().filter { it.isFile }.toList()
                    listedAt = System.nanoTime()
                }
                if (files.isNotEmpty()) files[random.nextInt(files.size)].setLastModified(System.currentTimeMillis())
            }
        }
    }

    private fun newestPainted(rig: FaultRig): Boolean =
        rig.conversations.state(agentId).value.items.any { it is SystemNotification && it.raw.contains("title: Report ${BigProject.TURNS}\n") }

    @Test
    fun `a Project over a full blob store opens, and refreshes, without the sort failing`() = runBlocking<Unit> {
        val root = folder.newFolder("phone")
        fillWithAnotherChat(root, count = OTHER_BLOBS, bytes = OTHER_BLOB_BYTES)
        val rig = FaultRig(server.baseUrl, root, readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, blobDiskBytes = OTHER_BLOBS.toLong() * OTHER_BLOB_BYTES).also { it.now = now; rigs += it }
        readerStamping(root)
        val conversations = rig.conversations
        val errors = CopyOnWriteArrayList<String>()
        val watcher = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            conversations.state(agentId).collect { s -> listOfNotNull(s.transcriptError, s.error, s.recordFallback?.reason).forEach { if (errors.lastOrNull() != it) errors += it } }
        }

        val started = System.nanoTime()
        conversations.attach(agentId)
        rig.awaitUntil(120_000) { newestPainted(rig) }
        val paintMs = (System.nanoTime() - started) / 1_000_000
        // The Retry of Bennett's notice, three times over, each while the blobs of the last read are still being read and written behind the screen.
        repeat(3) {
            delay(1_500)
            conversations.reload(agentId)
            conversations.awaitLoad(agentId)
        }
        rig.awaitUntil(120_000) { conversations.state(agentId).value.let { s -> !s.isLoading && !s.isLoadingOlder && s.traceStatus.pending == 0 } }
        val fullMs = (System.nanoTime() - started) / 1_000_000
        watcher.cancel()

        val state = conversations.state(agentId).value
        val beta = conversations.loadDiagnostics(agentId)?.beta
        println("== full blob store: newest turn painted after $paintMs ms, settled after $fullMs ms (three Retries included)")
        println("   ${beta?.text}")
        println("   prompts=${state.items.count { it is UserMessage }} reports=${state.items.count { it is SystemNotification }} errors=$errors")
        assertWithMessage("words of failure the chat showed").that(errors).isEmpty()
        assertThat(state.recordFallback).isNull()
        assertThat(state.items.count { it is UserMessage }).isAtLeast(1)
        assertThat(beta!!.incomplete).isEqualTo(0)
        assertThat(state.traceStatus.failed).isEqualTo(0)
    }

    private companion object {
        /** Another chat's blobs filling the store to its budget, so every open writes past it. */
        const val OTHER_BLOBS = 4_200
        const val OTHER_BLOB_BYTES = 2_048
    }
}
