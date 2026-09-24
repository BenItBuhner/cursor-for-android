package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the traces of one agent keep, how they are laid out, and what a full store lets go of. */
class TraceCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File

    private fun disk(): JsonDiskCache {
        if (!::root.isInitialized) root = folder.newFolder("traces")
        return JsonDiskCache(root, dispatcher = Dispatchers.Unconfined)
    }

    private fun cache(maxBytesPerAgent: Long = 32L shl 20, maxRunsPerAgent: Int = 400) = TraceCache(
        disk(),
        maxBytesPerAgent = maxBytesPerAgent,
        maxRunsPerAgent = maxRunsPerAgent,
    )

    private fun trace(runId: String, at: Long, reply: String = "Done.") = CachedTrace(
        runId = runId,
        createdAtMillis = at,
        items = listOf(AssistantMessage("$runId-a", reply)),
    )

    /** The conversation pages up to eight pages of a hundred runs; every run it can reach keeps its trace. */
    @Test
    fun `every run the conversation can page to keeps its trace`() = runBlocking<Unit> {
        val traces = cache()

        traces.put("bc-1", (1..400).map { trace("run-$it", at = it.toLong()) })

        val kept = traces.read("bc-1")
        assertThat(kept).hasSize(400)
        assertThat(kept.keys).contains("run-1")
        assertThat(kept.keys).contains("run-400")
    }

    /** Past the run bound the oldest go, so the store cannot grow without end on a chat that never stops. */
    @Test
    fun `runs older than the paging bound are let go of`() = runBlocking<Unit> {
        val traces = cache()

        traces.put("bc-1", (1..420).map { trace("run-$it", at = it.toLong()) })

        val kept = traces.read("bc-1")
        assertThat(kept).hasSize(400)
        assertThat(kept.keys).doesNotContain("run-1")
        assertThat(kept.keys).doesNotContain("run-20")
        assertThat(kept.keys).contains("run-21")
        assertThat(kept.keys).contains("run-420")
    }

    /**
     * A trace is as long as the run's log, so four hundred of them is not a bounded store. The newest that fit the
     * budget are kept, and the files of the ones let go of are gone with them.
     */
    @Test
    fun `traces past the byte budget are dropped oldest first`() = runBlocking<Unit> {
        val traces = cache(maxBytesPerAgent = 4_000)

        traces.put("bc-1", (1..20).map { trace("run-$it", at = it.toLong(), reply = "x".repeat(1_000)) })

        val kept = traces.read("bc-1")
        assertThat(kept.size).isAtLeast(1)
        assertThat(kept.size).isLessThan(20)
        assertThat(kept.keys).contains("run-20")
        assertThat(kept.keys).doesNotContain("run-1")
        assertThat(traces.runIds("bc-1")).isEqualTo(kept.keys)
        assertThat(File(root, "bc-1").listFiles()!!.count { it.name.startsWith("run-") }).isEqualTo(kept.size)
    }

    /** One run whose trace is bigger than the whole budget is still kept: the run just watched is always there. */
    @Test
    fun `a single trace larger than the budget is still kept`() = runBlocking<Unit> {
        val traces = cache(maxBytesPerAgent = 100)

        traces.put("bc-1", listOf(trace("run-1", at = 1L, reply = "y".repeat(5_000))))

        assertThat(traces.read("bc-1").keys).containsExactly("run-1")
    }

    /**
     * The budget is proportioned to what a long chat carries: hundreds of tool calls with their clipped payloads
     * — the very thing the 2 MiB budget of 0.3.2 dropped down to a handful of runs, which is how a chat lost its
     * tool calls across a restart.
     */
    @Test
    fun `hundreds of payload-carrying tool calls fit the default budget`() = runBlocking<Unit> {
        val traces = cache()
        val payload = ToolPayload.FileContent("app/src/File.kt", "x".repeat(30_000), ToolPayload.FileContent.Kind.Read)
        fun run(n: Int) = CachedTrace(
            "run-$n", n.toLong(),
            listOf(ActivityGroup("g-$n", (1..10).map { ToolCall("c-$n-$it", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "File.kt", payload = payload) })),
        )

        traces.put("bc-1", (1..40).map(::run))

        assertThat(traces.runIds("bc-1")).hasSize(40)
    }

    /**
     * A run is its own file: the window a chat opens on is read one file per run, and a run whose file will not
     * decode — half-written, or written by a build with another shape — costs that run alone.
     */
    @Test
    fun `a window is read run by run and a bad file costs only its run`() = runBlocking<Unit> {
        val traces = cache()
        traces.put("bc-1", (1..5).map { trace("run-$it", at = it.toLong()) })
        File(root, "bc-1/run-3.json").writeText("{ not json")

        val window = traces.read("bc-1", listOf("run-5", "run-4", "run-3"))

        assertThat(window.keys).containsExactly("run-5", "run-4")
        assertThat(traces.read("bc-1").keys).containsExactly("run-1", "run-2", "run-4", "run-5")
    }

    @Serializable
    private data class LegacyEnvelope(val version: Int, val savedAtMillis: Long, val value: LegacyTraces)

    @Serializable
    private data class LegacyTraces(val agentId: String, val runs: List<CachedTrace>)

    /** The whole-file store of 0.3.2 and before is moved into the per-run layout on first touch, nothing lost. */
    @Test
    fun `the whole-file store of earlier builds migrates run by run`() = runBlocking<Unit> {
        val traces = cache()
        val legacy = LegacyEnvelope(3, 1L, LegacyTraces("bc-1", listOf(trace("run-1", 1L, "One"), trace("run-2", 2L, "Two"))))
        File(root, "bc-1.json").apply { parentFile!!.mkdirs(); writeText(CursorJson.encodeToString(legacy)) }

        val read = traces.read("bc-1", listOf("run-1", "run-2"))

        assertThat(read.keys).containsExactly("run-1", "run-2")
        assertThat((read.getValue("run-2").items.single() as AssistantMessage).markdown).isEqualTo("Two")
        assertThat(File(root, "bc-1.json").exists()).isFalse()
        assertThat(File(root, "bc-1/run-1.json").isFile).isTrue()
        // Adding a run keeps what was migrated.
        traces.put("bc-1", listOf(trace("run-3", 3L, "Three")))
        assertThat(traces.runIds("bc-1")).containsExactly("run-1", "run-2", "run-3")
    }

    /** Agents beyond the bound go whole, least recently written first. */
    @Test
    fun `agents beyond the bound are let go of`() = runBlocking<Unit> {
        val traces = TraceCache(disk(), maxAgents = 2)
        traces.put("bc-old", listOf(trace("run-1", 1L)))
        File(root, "bc-old").setLastModified(1_000L)
        traces.put("bc-mid", listOf(trace("run-1", 1L)))
        File(root, "bc-mid").setLastModified(2_000L)
        traces.put("bc-new", listOf(trace("run-1", 1L)))

        assertThat(File(root, "bc-old").exists()).isFalse()
        assertThat(traces.runIds("bc-mid")).containsExactly("run-1")
        assertThat(traces.runIds("bc-new")).containsExactly("run-1")
    }

    private fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Marks an agent's traces as last shown (and last written) at [at], the way a read stamps them. */
    private suspend fun open(traces: TraceCache, agentId: String, at: Long) {
        assertThat(traces.read(agentId, listOf("run-1"))).isNotEmpty()
        File(root, "$agentId/.opened").setLastModified(at)
        File(root, agentId).setLastModified(at)
    }

    /**
     * The run monitor writes the trace of every run it sees finish, opened here or not. Past the store's budget those
     * go first, even when a chat that was opened is older, and the store ends up within the budget.
     */
    @Test
    fun `past the store budget the traces nobody opened go before an older chat that was opened`() = runBlocking<Unit> {
        val traces = TraceCache(disk(), maxTotalBytes = 8_000)
        val big = "x".repeat(2_000)
        traces.put("bc-read", listOf(trace("run-1", 1L, big)))
        open(traces, "bc-read", at = 1_000L)
        traces.put("bc-seen-1", listOf(trace("run-1", 1L, big)))
        File(root, "bc-seen-1").setLastModified(5_000L)
        traces.put("bc-seen-2", listOf(trace("run-1", 1L, big)))
        File(root, "bc-seen-2").setLastModified(6_000L)
        assertThat(File(root, "bc-seen-1").exists()).isTrue()

        traces.put("bc-new", listOf(trace("run-1", 1L, big)))

        assertThat(File(root, "bc-seen-1").exists()).isFalse()
        assertThat(traces.runIds("bc-read")).containsExactly("run-1")
        assertThat(traces.runIds("bc-new")).containsExactly("run-1")
        assertThat(bytesOnDisk()).isAtMost(8_000L)
    }

    /** When the chats opened here are all there is, the one opened longest ago goes, never the one just written. */
    @Test
    fun `among opened chats the least recently opened goes first`() = runBlocking<Unit> {
        val traces = TraceCache(disk(), maxTotalBytes = 6_000)
        val big = "x".repeat(2_000)
        traces.put("bc-a", listOf(trace("run-1", 1L, big)))
        open(traces, "bc-a", at = 1_000L)
        traces.put("bc-b", listOf(trace("run-1", 1L, big)))
        open(traces, "bc-b", at = 2_000L)
        // Opened again later: now the most recently used of the two.
        open(traces, "bc-a", at = 3_000L)

        traces.put("bc-c", listOf(trace("run-1", 1L, big)))

        assertThat(File(root, "bc-b").exists()).isFalse()
        assertThat(traces.runIds("bc-a")).containsExactly("run-1")
        assertThat(traces.runIds("bc-c")).containsExactly("run-1")
    }

    /**
     * A new process does not trust a total it has not measured: a store an earlier one left over the budget is held
     * to it on the first write, and a whole-file store no build ever migrated (nothing else deletes it) goes first.
     */
    @Test
    fun `the first write of a process trims a store an earlier one left over budget, unmigrated files first`() = runBlocking<Unit> {
        val big = "x".repeat(2_000)
        val earlier = TraceCache(disk())
        listOf("bc-1", "bc-2", "bc-3").forEachIndexed { i, id ->
            earlier.put(id, listOf(trace("run-1", 1L, big)))
            File(root, id).setLastModified(10_000L + i)
        }
        File(root, "bc-legacy.json").apply { writeText("y".repeat(3_000)); setLastModified(20_000L) }

        val traces = TraceCache(disk(), maxTotalBytes = 8_000)
        traces.put("bc-4", listOf(trace("run-1", 1L, big)))

        assertThat(File(root, "bc-legacy.json").exists()).isFalse()
        assertThat(traces.runIds("bc-4")).containsExactly("run-1")
        assertThat(bytesOnDisk()).isAtMost(8_000L)
        // The least recently written of the unopened ones went before the newer.
        assertThat(File(root, "bc-3").exists()).isTrue()
    }

    /**
     * A tool call is read off its JSON while it is built and carries only what a row shows, so what the disk keeps is
     * that and nothing else: no whole files, no whole command output.
     */
    @Test
    fun `a tool call reaches the disk as the row it renders`() = runBlocking<Unit> {
        val traces = cache()
        val group = ActivityGroup(
            id = "group-1",
            steps = listOf(
                ThinkingBlock("Looking at the file."),
                ToolCall(
                    callId = "call-1",
                    name = "run_terminal_cmd",
                    kind = ToolKind.Shell,
                    status = ToolCall.STATUS_COMPLETED,
                    summary = "ls",
                    detail = "ls -la",
                    output = "total 8\nREADME.md",
                    exitCode = 0,
                ),
            ),
        )

        traces.put("bc-1", listOf(CachedTrace("run-1", 1L, listOf(group))))

        val call = (traces.read("bc-1").getValue("run-1").items.single() as ActivityGroup).calls.single()
        assertThat(call.summary).isEqualTo("ls")
        assertThat(call.detail).isEqualTo("ls -la")
        assertThat(call.output).isEqualTo("total 8\nREADME.md")
        assertThat(call.exitCode).isEqualTo(0)
    }
}
