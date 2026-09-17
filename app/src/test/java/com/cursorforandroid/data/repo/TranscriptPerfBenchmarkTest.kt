package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.MarkdownParser
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The long-chat reproduction, measured rather than eyeballed. Two chats in the shape Bennett's account has them:
 *
 *  - A Project coordinator's (Extended mode, the account's record as the transcript): 440 turns of which 37 are his
 *    own prompts and the rest turns Cursor injected — subagent completions under both instruction templates, a
 *    subscribed pull request's changes — some 200 `SendMessage` updates (whole, and in `is_streaming` pieces), over
 *    two thousand tool calls with 40 000-character payloads, the newest turn under way on a live stream.
 *  - An ordinary chat (default mode): 300 turns, twelve tool calls a turn with 40 000-character payloads on the run
 *    logs, the newest turn under way.
 *
 * Measured from `attach`: the first content, the newest page whole, what the screen's presentation of every
 * publication costs (the legacy path the screen ran until 0.3.31 — `present` + `lift` + `TranscriptRows.of` on the
 * whole transcript on every publication — against the incremental presenter), the markdown of the newest page parsed
 * once and again, a burst of live deltas, the window widened to the whole chat (time, publications, heap), an idle
 * stretch (publications and network calls), and a restart from disk. Every number is printed as a `BENCH` line and
 * the `perf:` block of the diagnostics is printed with it; the assertions hold the shape (what is on screen) and a
 * few bounds loose enough for CI.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptPerfBenchmarkTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"))
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        cache = ConversationCache(disk.child("conversations"))
        traces = TraceCache(disk.child("traces"))
        AppClock.nowMillis = { now }
        MarkdownCache.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(hub: LiveRunHub, record: ConversationRecordApi? = null, capabilities: Capabilities = Capabilities.DOCUMENTED) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { capabilities },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 60_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }

    // -- the coordinator's record ----------------------------------------------------------------------------------

    /**
     * The account's record of a coordinator's chat, generated by index so the fake holds nothing: per turn its kind
     * (the user's prompt, a subagent's completion, a pull request's change), the steps of that kind, the payloads
     * of [payloadChars]. The newest turn is under way with [liveSteps] of its steps so far.
     */
    class CoordinatorRecord(val turns: Int, val payloadChars: Int, val firstTurnAt: Long, @Volatile var liveSteps: Int = 6) : ConversationRecordApi {
        enum class Kind(val steps: Int) { User(28), GitHub(3), Subagent(14), SubagentUpdate(18) }

        val kinds: List<Kind> = (1..turns).map { t -> kindOf(t) }
        private val starts = IntArray(turns + 1).also { s -> for (t in 0 until turns) s[t + 1] = s[t] + kinds[t].steps }
        val total: Int get() = starts[turns - 1] + minOf(liveSteps, kinds[turns - 1].steps)
        val calls = AtomicInteger()
        val stateCalls = AtomicInteger()
        @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val toolCalls: Int = kinds.sumOf { toolCallsOf(it) }
        val updates: Int = kinds.count { it == Kind.User || it == Kind.SubagentUpdate }
        val injected: Int = kinds.count { it != Kind.User }

        private fun kindOf(t: Int): Kind = when {
            t % 12 == 1 -> Kind.User
            t % 7 == 0 -> Kind.GitHub
            t % 2 == 0 -> Kind.SubagentUpdate
            else -> Kind.Subagent
        }

        private fun toolCallsOf(kind: Kind): Int = when (kind) {
            Kind.User -> 11
            Kind.GitHub -> 0
            Kind.Subagent -> 5
            Kind.SubagentUpdate -> 6
        }

        /** A handful of distinct payloads, made once: the fake's own cost stays out of what is measured. */
        private val payloads: List<String> = (0 until 8).map { seed ->
            buildString(payloadChars) {
                var i = 0
                while (length < payloadChars) { append("line ").append(seed).append('-').append(i++).append(": val x = compute(y) // some code that the tool returned\n") }
                setLength(payloadChars)
            }
        }

        private fun payload(seed: Int): String = payloads[Math.floorMod(seed, payloads.size)]

        /** Bytes the record served so far, as JSON would carry them (the payloads plus a little per step). */
        val bytesServed = java.util.concurrent.atomic.AtomicLong()
        /** Time spent inside the fake, to be taken off the open's numbers. */
        val nanosServing = java.util.concurrent.atomic.AtomicLong()

        private fun update(t: Int): String = """
            |**Turn $t update.** The render shard for R6 landed and the slice cutter is on its second pass.
            |
            |- Kitchen v2: PR opened, CI green, awaiting the merge train.
            |- Backyard v2: rendering, 60% of frames, ETA an hour.
            |- Limb passes: the hand worker reports `arm_rig_v3` needs a re-weight; queued.
            |
            |```kotlin
            |val slices = scene.cut(everyFrames = 24).reorder(byMotion = true)
            |```
            |
            |Next: land the kitchen PR, then re-run the limb pass with the new weights.
        """.trimMargin()

        private fun sendMessageArgs(t: Int): String = buildJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(update(t))) }) }.toString()

        private fun injectedSubagent(t: Int): String {
            val detail = buildString {
                append("The shard landed: #").append(100 + t).append(" merged, frames 1-240 rendered at 24fps, the reorder table written to `renders/slices.json`.\n\n")
                append("Checks:\n- CI green on the merge commit\n- Renders match the reference within 2% SSIM\n- No dropped frames in the motion pass\n\n")
                repeat(6) { append("Frame batch ").append(it).append(": rendered, checked, uploaded to the artifact store with its manifest. ") }
            }
            val query = if (t % 2 == 0) {
                "The beginning of the above subagent result is already visible to the user. Perform any follow-up actions (if needed). DO NOT regurgitate or reiterate its result unless asked. If you respond, end your response with a brief third-person confirmation of what was done and link it with the `[label](id)` syntax. Don't repeat the same confirmation every time."
            } else {
                "Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. If you mention an agent or subagent in your response, link it with the `[Name](id)` Don't use generic label such as `[agent]`, `[worker]`, or `[subagent]`. Don't repeat the same confirmation every time."
            }
            return "<timestamp>Saturday, Sep 12, 2026, 11:05 PM (UTC)</timestamp>\n<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n<task>\nkind: subagent\nstatus: success\ntask_id: bc-${worker(t)}\ntitle: Render shard R6-${t}of2 &amp; slice cut\ntool_call_id: toolu_01CR$t\nagent_id: bc-${worker(t)}\ndetail: This is the last output of the subagent:\n\n$detail\n\nAgent ID: bc-${worker(t)} (can be used with the `resume` parameter to send a follow-up)\n</task>\n</system_notification>\n<user_query>$query</user_query>"
        }

        private fun injectedGitHub(t: Int): String =
            "<system_notification source=\"github\" pr=\"https://github.com/BenItBuhner/revenue-scaling-pipeline/pull/${100 + t}\" action=\"${if (t % 14 == 0) "merged" else "synchronize"}\" sender=\"cursor[bot]\">A subscribed pull request changed. Use the linked PR for details if needed.</system_notification>"

        private fun worker(t: Int): String = "%08d-1a2d-5218-8e2e-7464ea74f671".format(t)

        private fun call(id: String, name: String, args: String) = HeadlessStep(toolCall = HeadlessToolCall(id, name, kotlinx.serialization.json.Json.parseToJsonElement(args), source = "id=toolCallId name=name args=json"))
        private fun result(id: String, result: String) = HeadlessStep(toolResult = HeadlessToolResult(id, kotlinx.serialization.json.Json.parseToJsonElement(result)))
        private fun result(id: String, result: kotlinx.serialization.json.JsonElement) = HeadlessStep(toolResult = HeadlessToolResult(id, result))
        private fun piece(id: String, text: String, last: Boolean) = HeadlessStep(toolCall = HeadlessToolCall(id, "SendMessage", null, rawArgs = text, isStreaming = true, isLastMessage = last, source = "id=toolCallId name=name args=piece"))

        /** Step [k] of turn [t] (1-based turn, 0-based step). */
        fun step(t: Int, k: Int): HeadlessStep {
            val kind = kinds[t - 1]
            return when (kind) {
                Kind.User -> when {
                    k == 0 -> HeadlessStep(userMessage = "Prompt $t: make the phone renders look like the reference shots, fast dynamic movement, and cut every scene into slices we can reorder. Keep the shard workers busy and report as each lands.", projectMode = true)
                    k == 1 -> HeadlessStep(text = "Bennett wants the render pipeline redone around reorderable slices; the shard workers can take that in parallel.")
                    k == 2 -> call("toolu_create_$t", "createAgent", """{"title":"Render kitchen v2 shard R6-${t}of2","message":"Render the shard and open a PR.","repo":"BenItBuhner/revenue-scaling-pipeline"}""")
                    k == 3 -> result("toolu_create_$t", """{"agentId":"bc-${worker(t)}","toolCallId":"toolu_create_$t"}""")
                    k in 4..19 && k % 2 == 0 -> call("toolu_read_${t}_$k", "read_file", """{"path":"app/src/render/Shard$k.kt"}""")
                    k in 4..19 -> result("toolu_read_${t}_${k - 1}", buildJsonObject { put("content", JsonPrimitive(payload(t * 100 + k))); put("path", JsonPrimitive("app/src/render/Shard${k - 1}.kt")) })
                    k == 20 -> call("toolu_cmd_$t", "run_terminal_cmd", """{"command":"./gradlew :render:test --tests '*Shard*'"}""")
                    k == 21 -> result("toolu_cmd_$t", buildJsonObject { put("output", JsonPrimitive(payload(t))); put("exitCode", JsonPrimitive(0)) })
                    k == 22 -> call("toolu_sendagent_$t", "sendToAgent", """{"agentId":"bc-${worker(t)}","message":"Start with the kitchen shard.","delivery":"queue"}""")
                    k == 23 -> result("toolu_sendagent_$t", """{"success":{"workerBcId":"bc-${worker(t)}","deliveredAs":"queue"}}""")
                    k == 24 -> call("toolu_send_$t", "SendMessage", sendMessageArgs(t))
                    k == 25 -> result("toolu_send_$t", """{"success":{"timestamp":"2026-09-12T23:05:00Z","messageId":"m$t"}}""")
                    k == 26 -> HeadlessStep(text = "")
                    else -> HeadlessStep()
                }
                Kind.GitHub -> when (k) {
                    0 -> HeadlessStep(userMessage = injectedGitHub(t), projectMode = true)
                    1 -> HeadlessStep(text = "Noted; nothing to do for #${100 + t} until CI reports.")
                    else -> HeadlessStep()
                }
                Kind.Subagent, Kind.SubagentUpdate -> when {
                    k == 0 -> HeadlessStep(userMessage = injectedSubagent(t), projectMode = true)
                    k == 1 -> call("toolu_status_$t", "getAgentStatus", "{}")
                    k == 2 -> result("toolu_status_$t", """{"success":{"workers":[{"bcId":"bc-${worker(t)}","name":"Render shard","lifecycle":"RUNNING"},{"bcId":"bc-${worker(t + 1)}","name":"Slice cutter","lifecycle":"FINISHED"}]}}""")
                    k == 3 -> call("toolu_transcript_$t", "readAgentTranscript", """{"agentId":"bc-${worker(t)}"}""")
                    k == 4 -> result("toolu_transcript_$t", buildJsonObject { put("success", buildJsonObject { put("transcript", JsonPrimitive(payload(t))) }) })
                    k in 5..10 && k % 2 == 1 -> call("toolu_readpr_${t}_$k", "read_file", """{"path":"renders/slices-$t-$k.json"}""")
                    k in 5..10 -> result("toolu_readpr_${t}_${k - 1}", buildJsonObject { put("content", JsonPrimitive(payload(t + k))); put("path", JsonPrimitive("renders/slices-$t-${k - 1}.json")) })
                    k == 11 -> HeadlessStep(text = "Logged; [Render shard](bc-${worker(t)}) is on its next shard and the slice cutter has the reorder table.")
                    kind == Kind.SubagentUpdate && k in 12..14 -> {
                        val whole = sendMessageArgs(t)
                        val cut = when (k) { 12 -> whole.length / 3; 13 -> whole.length * 2 / 3; else -> whole.length }
                        piece("toolu_send_$t", whole.substring(0, cut), last = k == 14)
                    }
                    kind == Kind.SubagentUpdate && k == 15 -> result("toolu_send_$t", """{"success":{"timestamp":"2026-09-12T23:05:00Z","messageId":"m$t"}}""")
                    else -> HeadlessStep()
                }
            }
        }

        private fun locate(index: Int): Pair<Int, Int> {
            var lo = 0
            var hi = turns - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= index) lo = mid else hi = mid - 1
            }
            return (lo + 1) to (index - starts[lo])
        }

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            calls.incrementAndGet()
            gate?.await()
            val started = System.nanoTime()
            val end = minOf(total, startIndex + limit)
            val steps = if (startIndex >= end) emptyList() else (startIndex until end).map { i -> val (t, k) = locate(i); step(t, k) }
            bytesServed.addAndGet(steps.sumOf { step -> 160L + (step.userMessage?.length ?: 0) + (step.text?.length ?: 0) + (step.toolCall?.rawArgs?.length ?: 0) + (step.toolCall?.args?.toString()?.length ?: 0) + (step.toolResult?.result?.let { r -> (r as? kotlinx.serialization.json.JsonObject)?.let { o -> o.values.sumOf { v -> ((v as? JsonPrimitive)?.content?.length ?: v.toString().length).toLong() } } ?: r.toString().length.toLong() } ?: 0L) })
            nanosServing.addAndGet(System.nanoTime() - started)
            return HeadlessPage(steps, startIndex, total)
        }

        override suspend fun state(agentId: String): RecordState {
            stateCalls.incrementAndGet()
            gate?.await()
            val timings = (0 until turns).map { t -> if (t == turns - 1) TurnTiming(null, null) else TurnTiming(30_000L, firstTurnAt + t * 3_600_000L + 30_000L) }
            return RecordState(turns, timings, pendingToolCalls = 1, isRootProject = true, numPriorInteractionUpdates = total.toLong(), rewindEpoch = 0L)
        }
    }

    // -- measurement -------------------------------------------------------------------------------------------------

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

    private inner class Cost(var nanos: Long = 0L, var bytes: Long = 0L, var runs: Int = 0, var maxNanos: Long = 0L) {
        fun add(nanos: Long, bytes: Long) { this.nanos += nanos; this.bytes += bytes; runs++; maxNanos = maxOf(maxNanos, nanos) }
        val ms: Double get() = nanos / 1e6
        val maxMs: Double get() = maxNanos / 1e6
        val mb: Double get() = bytes / 1048576.0
        override fun toString() = "runs=$runs total=${fmt(ms)}ms avg=${fmt(if (runs == 0) 0.0 else ms / runs)}ms max=${fmt(maxMs)}ms alloc=${fmt(mb)}MB"
    }

    /**
     * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, through reflection: the test compiles against the
     * Android SDK, which has no `java.lang.management`, and runs on a JVM, which does.
     */
    private object Allocations {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val method = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE) }.getOrNull()

        fun ofCurrentThread(): Long = runCatching { method?.invoke(bean, Thread.currentThread().id) as? Long }.getOrNull() ?: 0L
    }

    private inline fun <T> measured(cost: Cost, block: () -> T): T {
        val bytes0 = Allocations.ofCurrentThread()
        val t0 = System.nanoTime()
        val result = block()
        cost.add(System.nanoTime() - t0, Allocations.ofCurrentThread() - bytes0)
        return result
    }

    /** What the screen ran on every publication until 0.3.31, on the main thread: the whole transcript re-presented and re-cut into rows. */
    private fun legacyPresent(items: List<TimelineItem>, coordinatorMode: Boolean, runActive: Boolean): List<TranscriptRow> {
        val mode = coordinatorMode || CoordinatorTranscript.hasCoordinatorContent(items)
        val presented = GoalTranscript.lift(CoordinatorTranscript.present(items, mode))
        val rows = TranscriptRows.of(presented, mode, runActive)
        // The summaries the visible stretches compute on composition, and the goal strip's derivation.
        rows.takeLast(VISIBLE_ROWS).forEach { row -> (row as? TranscriptRow.Stretch)?.summary; (row as? TranscriptRow.Events)?.summary }
        GoalTranscript.derive(items)
        return rows
    }

    /** The markdown the newest [VISIBLE_ROWS] rows draw on the first frame: prompts, the coordinator's messages, replies. */
    private fun visibleMarkdown(rows: List<TranscriptRow>): List<String> = rows.takeLast(VISIBLE_ROWS).mapNotNull { row ->
        when (row) {
            is TranscriptRow.Message -> (row.call.payload as? ToolPayload.CoordinatorMessage)?.message
            is TranscriptRow.Item -> (row.item as? UserMessage)?.text ?: (row.item as? AssistantMessage)?.markdown
            else -> null
        }
    }.filter { it.isNotBlank() }

    /** Tracks every publication of a chat: when it came and what presenting it cost, both ways. */
    private inner class Publications(private val conversations: ConversationRepository, private val agentId: String, private val coordinatorMode: Boolean) {
        val legacy = Cost()
        val incremental = Cost()
        val stamps = CopyOnWriteArrayList<Long>()
        val itemCounts = CopyOnWriteArrayList<Int>()
        private val presenter = TranscriptPresenter()
        @Volatile var lastRows: List<TranscriptRow> = emptyList()
        @Volatile var lastLegacyRows: List<TranscriptRow> = emptyList()
        private var job: Job? = null

        fun start() {
            job = scope.launch {
                var n = 0
                conversations.state(agentId).collect { s ->
                    stamps += System.nanoTime()
                    itemCounts += s.items.size
                    val active = s.runStatus?.isActive == true || s.isStreaming
                    // The two paths alternate their order so neither warms the caches for the other.
                    if (n++ % 2 == 0) {
                        lastLegacyRows = measured(legacy) { legacyPresent(s.items, coordinatorMode, active) }
                        lastRows = measured(incremental) { presenter.present(s.items, coordinatorMode || s.isProjectConversation, active).rows }
                    } else {
                        lastRows = measured(incremental) { presenter.present(s.items, coordinatorMode || s.isProjectConversation, active).rows }
                        lastLegacyRows = measured(legacy) { legacyPresent(s.items, coordinatorMode, active) }
                    }
                }
            }
        }

        fun reset() { legacy.runs = 0; legacy.nanos = 0; legacy.bytes = 0; legacy.maxNanos = 0; incremental.runs = 0; incremental.nanos = 0; incremental.bytes = 0; incremental.maxNanos = 0; stamps.clear(); itemCounts.clear() }
        fun stop() { job?.cancel() }
    }

    private fun heapMb(): Double {
        repeat(3) { System.gc(); Thread.sleep(50) }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    private fun ConversationState.toolCalls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }

    /** The characters the window holds in memory: every payload, output, message and prompt of its items. */
    private fun ConversationState.residentChars(): Long = items.sumOf { item ->
        when (item) {
            is UserMessage -> item.text.length.toLong()
            is AssistantMessage -> item.markdown.length.toLong()
            is SystemNotification -> item.raw.length.toLong() + (item.body?.length ?: 0)
            is ActivityGroup -> item.steps.sumOf { step ->
                when (step) {
                    is com.cursorforandroid.domain.ThinkingBlock -> step.text.length.toLong()
                    is com.cursorforandroid.domain.ToolCall -> (step.output?.length ?: 0).toLong() + (step.detail?.length ?: 0) + when (val p = step.payload) {
                        is ToolPayload.FileContent -> p.content.length
                        is ToolPayload.FileDiff -> p.diff.length
                        is ToolPayload.CoordinatorMessage -> p.message.length
                        else -> 0
                    }
                }
            }
            else -> 0L
        }
    }
    private fun ConversationState.prompts() = items.count { it is UserMessage || it is SystemNotification }

    private fun report(scenario: String, metric: String, value: Any) = println("BENCH $scenario | $metric | $value")

    /** Runs both presentation paths a few hundred times on a small transcript, so the timings below are the code's, not the JIT's. */
    private fun warmUp() {
        val presenter = TranscriptPresenter()
        val items = (1..12).flatMap { t ->
            listOf(
                UserMessage("u$t", "Prompt $t with **bold** and `code`", now),
                ActivityGroup("g$t", listOf(com.cursorforandroid.domain.ThinkingBlock("thinking $t"), com.cursorforandroid.domain.ToolCall("c$t", "read_file", com.cursorforandroid.domain.ToolKind.Read, "completed", "File$t.kt"))),
                AssistantMessage("a$t", "Reply $t\n\n- one\n- two"),
            )
        }
        val injected = (1..12).flatMap { t ->
            listOf(
                SystemNotification("n$t", SystemNotification.Kind.Subagent, "Subagent completed", "Shard $t", "The shard landed.", raw = "<system_notification/>", timestampMillis = now + t * 1000L),
                ActivityGroup("cg$t", listOf(com.cursorforandroid.domain.ToolCall("s$t", "SendMessage", com.cursorforandroid.domain.ToolKind.Coordinator, "completed", "", payload = ToolPayload.CoordinatorMessage("Update $t with `code`")))),
                AssistantMessage("ca$t", "Logged."),
                com.cursorforandroid.domain.RunFooter("f$t", "run$t", RunStatus.FINISHED, 12_000L, emptyList()),
            )
        }
        val coordinatorPresenter = TranscriptPresenter()
        repeat(300) {
            legacyPresent(items, coordinatorMode = false, runActive = it % 2 == 0)
            presenter.present(items, coordinatorMode = false, runActive = it % 2 == 0)
            legacyPresent(injected, coordinatorMode = true, runActive = it % 2 == 0)
            coordinatorPresenter.present(injected, coordinatorMode = true, runActive = it % 2 == 0)
        }
        MarkdownCache.clear()
    }

    // -- the coordinator's chat --------------------------------------------------------------------------------------

    private val coordinatorId = "bc-coordinator-perf"
    private val coordinatorTurns = 440

    private suspend fun seedCoordinator(record: CoordinatorRecord) {
        val turns = Array(coordinatorTurns) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(coordinatorId, "Revenue Scaling Pipeline", *turns, firstRunAt = Instant.ofEpochMilli(record.firstTurnAt).toString())
        val live = "run-$coordinatorTurns"
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[coordinatorId] = api.agents.getValue(coordinatorId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[coordinatorId] = api.transcripts.getValue(coordinatorId).dropLast(1)
        agents.refresh()
        // Every finished run's log has expired: the record is the transcript.
        for (i in 1 until coordinatorTurns) {
            streamer.emit("run-$i", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit("run-$i", RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Reading the shard reports."))
    }

    @Test
    fun `coordinator chat - open, live deltas, widen to every turn, idle, restart`() = runBlocking<Unit> {
        val record = CoordinatorRecord(coordinatorTurns, payloadChars = 40_000, firstTurnAt = now - coordinatorTurns * 3_600_000L)
        report("coordinator", "shape", "turns=${record.turns} injected=${record.injected} updates=${record.updates} toolCalls=${record.toolCalls} steps=${record.total} payloadChars=${record.payloadChars}")
        assertThat(record.turns).isAtLeast(400)
        assertThat(record.updates).isAtLeast(150)
        assertThat(record.toolCalls).isAtLeast(2_000)
        seedCoordinator(record)
        warmUp()
        val heap0 = heapMb()

        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        val publications = Publications(conversations, coordinatorId, coordinatorMode = true).also { it.start() }
        val opened = System.nanoTime()
        conversations.attach(coordinatorId)
        awaitUntil { conversations.state(coordinatorId).value.items.isNotEmpty() }
        val firstContentMs = (System.nanoTime() - opened) / 1_000_000
        awaitUntil { val s = conversations.state(coordinatorId).value; !s.isLoading && s.isStreaming && s.traceStatus.pending == 0 && s.items.isNotEmpty() }
        val wholeMs = (System.nanoTime() - opened) / 1_000_000
        delay(300)
        val open = TranscriptPerf.session(coordinatorId).snapshot()
        report("coordinator", "open.firstContentMs", firstContentMs)
        report("coordinator", "open.newestPageWholeMs", wholeMs)
        report("coordinator", "open.publications", publications.stamps.size)
        report("coordinator", "open.network", "${open.network} recordBytes=${record.bytesServed.get() / 1024}KB fakeServingMs=${record.nanosServing.get() / 1_000_000}")
        report("coordinator", "open.turns", "built=${open.turnsBuilt} (${"%.1f".format(open.turnBuildMs)}ms) reused=${open.turnsReused}")
        val servedAtOpen = record.bytesServed.get()
        report("coordinator", "open.presenter.legacy", publications.legacy)
        report("coordinator", "open.presenter.incremental", publications.incremental)
        val state = conversations.state(coordinatorId).value
        report("coordinator", "open.items", "items=${state.items.size} prompts=${state.prompts()} toolCalls=${state.toolCalls().size}")
        // The newest page as the screen shows it: the coordinator's updates as messages, the silent turns as one stretch.
        val rows = publications.lastRows
        val legacyRows = publications.lastLegacyRows
        assertThat(rows.map { it.key }).isEqualTo(legacyRows.map { it.key })
        assertThat(rows.count { it is TranscriptRow.Message }).isAtLeast(1)
        assertThat((rows.last() as? TranscriptRow.Stretch)?.live).isTrue()
        assertThat(state.items.filterIsInstance<NoticeCard>()).isEmpty()

        // The markdown the first frame draws: parsed once (the cost), then again as a scroll away and back would.
        val visible = visibleMarkdown(rows)
        val firstParse = Cost()
        measured(firstParse) { visible.forEach { MarkdownCache.parse(it) } }
        val secondParse = Cost()
        measured(secondParse) { visible.forEach { MarkdownCache.parse(it) } }
        val rawParse = Cost()
        measured(rawParse) { visible.forEach { MarkdownParser.parse(it) } }
        report("coordinator", "markdown.visibleMessages", "${visible.size} messages, ${visible.sumOf { it.length }} chars")
        report("coordinator", "markdown.firstParse", firstParse)
        report("coordinator", "markdown.cachedParse", secondParse)
        report("coordinator", "markdown.uncachedReparse", rawParse)

        // A burst of live deltas: the coordinator writing its next update, tool calls landing between the words.
        publications.reset()
        val live = "run-$coordinatorTurns"
        val burstStarted = System.nanoTime()
        repeat(200) { i ->
            streamer.emit(live, RunStreamEvent.Assistant("word $i "))
            if (i % 10 == 9) {
                val id = "$live-c${i / 10}"
                streamer.emit(live, RunStreamEvent.ToolCall(SseToolCallDto(id, "read_file", "running", buildJsonObject { put("path", JsonPrimitive("a/b/File$i.kt")) })))
                streamer.emit(live, RunStreamEvent.ToolCall(SseToolCallDto(id, "read_file", "completed", buildJsonObject { put("path", JsonPrimitive("a/b/File$i.kt")) }, buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(40_000))) }) })))
            }
            delay(2)
        }
        awaitUntil { conversations.state(coordinatorId).value.items.filterIsInstance<AssistantMessage>().any { it.markdown.contains("word 199") } }
        val burstMs = (System.nanoTime() - burstStarted) / 1_000_000
        delay(300)
        report("coordinator", "burst.deltas", "200 text deltas + 20 tool events in ${burstMs}ms")
        val burstSnapshot = TranscriptPerf.session(coordinatorId).snapshot()
        report("coordinator", "burst.publications", "${publications.stamps.size} itemsRebuilt=${"%.1f".format(burstSnapshot.publishBuildMs - open.publishBuildMs)}ms")
        report("coordinator", "burst.presenter.legacy", publications.legacy)
        report("coordinator", "burst.presenter.incremental", publications.incremental)
        assertThat(publications.stamps.size).isAtMost(60)

        // The reader scrolls to the top: every turn of the chat, a page at a time.
        publications.reset()
        val widenStarted = System.nanoTime()
        var pages = 0
        while (conversations.state(coordinatorId).value.hasOlder && pages < 200) {
            awaitUntil { !conversations.state(coordinatorId).value.isLoadingOlder }
            val before = conversations.state(coordinatorId).value.items.size
            conversations.loadOlder(coordinatorId)
            awaitUntil { val s = conversations.state(coordinatorId).value; (s.items.size > before && !s.isLoadingOlder) || !s.hasOlder }
            pages++
        }
        val widenMs = (System.nanoTime() - widenStarted) / 1_000_000
        delay(500)
        val whole = conversations.state(coordinatorId).value
        val heap1 = heapMb()
        report("coordinator", "widen.pages", pages)
        report("coordinator", "widen.totalMs", "$widenMs (fakeServingMs=${record.nanosServing.get() / 1_000_000} recordBytes=${(record.bytesServed.get() - servedAtOpen) / 1024}KB)")
        val widened = TranscriptPerf.session(coordinatorId).snapshot()
        report("coordinator", "widen.turns", "built=${widened.turnsBuilt} (${"%.1f".format(widened.turnBuildMs)}ms) reused=${widened.turnsReused} network=${widened.network}")
        report("coordinator", "widen.publications", "${publications.stamps.size} itemsRebuilt=${"%.1f".format(widened.publishBuildMs)}ms (max ${"%.1f".format(widened.publishBuildMaxMs)}ms)")
        report("coordinator", "widen.presenter.legacy", publications.legacy)
        report("coordinator", "widen.presenter.incremental", publications.incremental)
        report("coordinator", "widen.items", "items=${whole.items.size} prompts=${whole.prompts()} toolCalls=${whole.toolCalls().size} rows=${publications.lastRows.size}")
        report("coordinator", "widen.heapMb", "before=${"%.1f".format(heap0)} after=${"%.1f".format(heap1)} delta=${"%.1f".format(heap1 - heap0)} residentChars=${whole.residentChars() / 1_000_000}M")
        val wholeRows = publications.lastRows
        val messages = wholeRows.count { it is TranscriptRow.Message }
        report("coordinator", "widen.rows", "rows=${wholeRows.size} messages=$messages stretches=${wholeRows.count { it is TranscriptRow.Stretch }} eventGroups=${wholeRows.filterIsInstance<TranscriptRow.Stretch>().sumOf { s -> s.entries.count { it is TranscriptRow.Entry.Events } }}")
        assertThat(whole.hasOlder).isFalse()
        assertThat(messages).isAtLeast(150)
        assertThat(wholeRows.map { it.key }).isEqualTo(publications.lastLegacyRows.map { it.key })
        assertThat(whole.toolCalls().size).isAtLeast(2_000)
        val diagnostics = conversations.loadDiagnostics(coordinatorId)!!
        assertThat(diagnostics.source).isEqualTo("record")

        // Idle: nothing happens for a while; how much does the chat cost meanwhile?
        publications.reset()
        val idleNet = TranscriptPerf.session(coordinatorId).snapshot().networkTotal
        delay(IDLE_MS)
        val idleSnapshot = TranscriptPerf.session(coordinatorId).snapshot()
        report("coordinator", "idle.publications", "${publications.stamps.size} in ${IDLE_MS / 1000}s")
        report("coordinator", "idle.network", "${idleSnapshot.networkTotal - idleNet} calls in ${IDLE_MS / 1000}s")
        println(TranscriptPerf.session(coordinatorId).snapshot().render())
        publications.stop()

        // A restart: the newest window from disk before the network answers.
        conversations.detach(coordinatorId)
        awaitUntil { !conversations.state(coordinatorId).value.isStreaming }
        record.gate = kotlinx.coroutines.CompletableDeferred()
        api.runsGate = kotlinx.coroutines.CompletableDeferred()
        val next = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        val restartPublications = Publications(next, coordinatorId, coordinatorMode = true).also { it.start() }
        val restarted = System.nanoTime()
        next.attach(coordinatorId)
        awaitUntil(10_000) { next.state(coordinatorId).value.items.isNotEmpty() }
        val restartFirstMs = (System.nanoTime() - restarted) / 1_000_000
        awaitUntil(20_000) { next.state(coordinatorId).value.prompts() >= 30 }
        val restartWindowMs = (System.nanoTime() - restarted) / 1_000_000
        delay(300)
        val restartSnapshot = TranscriptPerf.session(coordinatorId).snapshot()
        report("coordinator", "restart.firstContentMs", restartFirstMs)
        report("coordinator", "restart.windowFromDiskMs", "$restartWindowMs (prompts=${next.state(coordinatorId).value.prompts()})")
        report("coordinator", "restart.diskReads", "conversation=${restartSnapshot.conversationReads} traceFiles=${restartSnapshot.traceReads} (${"%.1f".format(restartSnapshot.traceReadMs)}ms)")
        report("coordinator", "restart.presenter.legacy", restartPublications.legacy)
        report("coordinator", "restart.presenter.incremental", restartPublications.incremental)
        record.gate!!.complete(Unit)
        api.runsGate!!.complete(Unit)
        awaitUntil { !next.state(coordinatorId).value.isLoading && next.state(coordinatorId).value.isStreaming }
        delay(300)
        println(TranscriptPerf.session(coordinatorId).snapshot().render())
        restartPublications.stop()
    }

    // -- the ordinary chat -------------------------------------------------------------------------------------------

    private val chatId = "bc-ordinary-perf"
    private val chatRuns = 300
    private val callsPerRun = 12
    private val payloadChars = 40_000

    private fun readCall(runId: String, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(payloadChars))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    private suspend fun seedChat() {
        val turns = Array(chatRuns) { Triple("run-${it + 1}", "Prompt ${it + 1}: please refactor the ${it + 1}th module and explain the change in a short paragraph with a list of the files touched.", "Reply ${it + 1}") }
        api.addFinishedAgent(chatId, "Long chat", *turns, firstRunAt = Instant.ofEpochMilli(now - chatRuns * 3_600_000L).toString())
        val live = "run-$chatRuns"
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[chatId] = api.agents.getValue(chatId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[chatId] = api.transcripts.getValue(chatId).dropLast(1)
        agents.refresh()
        for (i in 1 until chatRuns) {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $i: reading the module before changing it."))
            repeat(callsPerRun) { n -> streamer.emit(runId, readCall(runId, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i\n\nRefactored the module.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper\n\n```kotlin\nval x = 1\n```"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Working on the newest turn."))
        streamer.emit(live, readCall(live, 1))
    }

    @Test
    fun `ordinary chat - open, live deltas, widen to every turn, restart from disk`() = runBlocking<Unit> {
        seedChat()
        warmUp()
        val heap0 = heapMb()
        val conversations = repository(hub())
        val publications = Publications(conversations, chatId, coordinatorMode = false).also { it.start() }
        val opened = System.nanoTime()
        conversations.attach(chatId)
        awaitUntil { conversations.state(chatId).value.items.isNotEmpty() }
        val firstContentMs = (System.nanoTime() - opened) / 1_000_000
        awaitUntil { val s = conversations.state(chatId).value; !s.isLoading && s.isStreaming && s.traceStatus.pending == 0 && s.toolCalls().count { !it.isRunning } >= 9 * callsPerRun }
        val wholeMs = (System.nanoTime() - opened) / 1_000_000
        delay(300)
        val open = TranscriptPerf.session(chatId).snapshot()
        report("ordinary", "open.firstContentMs", firstContentMs)
        report("ordinary", "open.newestPageWholeMs", wholeMs)
        report("ordinary", "open.publications", publications.stamps.size)
        report("ordinary", "open.network", open.network)
        report("ordinary", "open.presenter.legacy", publications.legacy)
        report("ordinary", "open.presenter.incremental", publications.incremental)
        val rows = publications.lastRows
        assertThat(rows.map { it.key }).isEqualTo(publications.lastLegacyRows.map { it.key })
        assertThat((rows.last() as? TranscriptRow.Stretch)?.live).isTrue()
        val visible = visibleMarkdown(rows)
        val firstParse = Cost()
        measured(firstParse) { visible.forEach { MarkdownCache.parse(it) } }
        val secondParse = Cost()
        measured(secondParse) { visible.forEach { MarkdownCache.parse(it) } }
        report("ordinary", "markdown.visibleMessages", "${visible.size} messages, ${visible.sumOf { it.length }} chars")
        report("ordinary", "markdown.firstParse", firstParse)
        report("ordinary", "markdown.cachedParse", secondParse)

        publications.reset()
        val live = "run-$chatRuns"
        val burstStarted = System.nanoTime()
        repeat(200) { i ->
            streamer.emit(live, RunStreamEvent.Assistant("word $i "))
            delay(2)
        }
        awaitUntil { conversations.state(chatId).value.items.filterIsInstance<AssistantMessage>().any { it.markdown.contains("word 199") } }
        val burstMs = (System.nanoTime() - burstStarted) / 1_000_000
        delay(300)
        report("ordinary", "burst.deltas", "200 text deltas in ${burstMs}ms")
        report("ordinary", "burst.publications", publications.stamps.size)
        report("ordinary", "burst.presenter.legacy", publications.legacy)
        report("ordinary", "burst.presenter.incremental", publications.incremental)

        publications.reset()
        val widenStarted = System.nanoTime()
        var pages = 0
        while (conversations.state(chatId).value.hasOlder && pages < 200) {
            awaitUntil { !conversations.state(chatId).value.isLoadingOlder }
            val before = conversations.state(chatId).value.items.count { it is UserMessage }
            conversations.loadOlder(chatId)
            awaitUntil { val s = conversations.state(chatId).value; (s.items.count { it is UserMessage } > before && !s.isLoadingOlder) || !s.hasOlder }
            pages++
        }
        awaitUntil(120_000) { conversations.state(chatId).value.traceStatus.pending == 0 }
        val widenMs = (System.nanoTime() - widenStarted) / 1_000_000
        delay(500)
        val whole = conversations.state(chatId).value
        val heap1 = heapMb()
        report("ordinary", "widen.pages", pages)
        report("ordinary", "widen.totalMs", widenMs)
        report("ordinary", "widen.publications", "${publications.stamps.size} itemsRebuilt=${"%.1f".format(TranscriptPerf.session(chatId).snapshot().publishBuildMs)}ms (max ${"%.1f".format(TranscriptPerf.session(chatId).snapshot().publishBuildMaxMs)}ms)")
        report("ordinary", "widen.presenter.legacy", publications.legacy)
        report("ordinary", "widen.presenter.incremental", publications.incremental)
        report("ordinary", "widen.items", "items=${whole.items.size} prompts=${whole.prompts()} toolCalls=${whole.toolCalls().size} rows=${publications.lastRows.size}")
        report("ordinary", "widen.heapMb", "before=${"%.1f".format(heap0)} after=${"%.1f".format(heap1)} delta=${"%.1f".format(heap1 - heap0)} residentChars=${whole.residentChars() / 1_000_000}M")
        assertThat(whole.prompts()).isEqualTo(chatRuns)
        assertThat(whole.toolCalls().size).isAtLeast((chatRuns - 1) * callsPerRun)
        assertThat(publications.lastRows.map { it.key }).isEqualTo(publications.lastLegacyRows.map { it.key })
        println(TranscriptPerf.session(chatId).snapshot().render())
        publications.stop()

        // A restart on what the disk kept: the widest window it reopens on, before the network answers.
        conversations.detach(chatId)
        awaitUntil { !conversations.state(chatId).value.isStreaming }
        awaitUntil { traces.runIds(chatId).size >= 30 }
        api.runsGate = kotlinx.coroutines.CompletableDeferred()
        api.conversationGate = kotlinx.coroutines.CompletableDeferred()
        val next = repository(hub())
        val restartPublications = Publications(next, chatId, coordinatorMode = false).also { it.start() }
        val restarted = System.nanoTime()
        next.attach(chatId)
        awaitUntil(10_000) { next.state(chatId).value.items.isNotEmpty() }
        val restartFirstMs = (System.nanoTime() - restarted) / 1_000_000
        awaitUntil(20_000) { next.state(chatId).value.toolCalls().size >= 29 * callsPerRun }
        val restartWindowMs = (System.nanoTime() - restarted) / 1_000_000
        delay(300)
        val restartSnapshot = TranscriptPerf.session(chatId).snapshot()
        report("ordinary", "restart.firstContentMs", restartFirstMs)
        report("ordinary", "restart.windowFromDiskMs", "$restartWindowMs (prompts=${next.state(chatId).value.prompts()} toolCalls=${next.state(chatId).value.toolCalls().size})")
        report("ordinary", "restart.diskReads", "conversation=${restartSnapshot.conversationReads} traceFiles=${restartSnapshot.traceReads} (${"%.1f".format(restartSnapshot.traceReadMs)}ms)")
        report("ordinary", "restart.presenter.legacy", restartPublications.legacy)
        report("ordinary", "restart.presenter.incremental", restartPublications.incremental)
        api.runsGate!!.complete(Unit)
        api.conversationGate!!.complete(Unit)
        awaitUntil { !next.state(chatId).value.isLoading }
        delay(300)
        println(TranscriptPerf.session(chatId).snapshot().render())
        restartPublications.stop()
    }

    private companion object {
        /** About a phone screen of rows: what the first frame composes. */
        const val VISIBLE_ROWS = 14
        const val IDLE_MS = 15_000L
    }
}
