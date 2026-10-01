package com.cursorforandroid.ui.scale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseFrame
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.conversation.LocalOpenStretches
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OpenStretches
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptItemSpacing
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.conversation.rowMotion
import com.cursorforandroid.ui.conversation.transcriptContentType
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * A Project coordinator's transcript with [STRETCHES] × [PER_STRETCH] cloud workers, all working: eight turns of
 * the coordinator's, each starting sixteen workers, each a stretch; the first seven closed, their lines counting the
 * workers and saying where the newest stands, the last open, its rows below it in the list as the conversation lists
 * them ([OpenStretches], [rowMotion]) as far as the screen reaches. Drawn by the app's own rows over the app's
 * [SubagentActivity][com.cursorforandroid.data.repo.SubagentActivity] for the workers the fake server holds.
 */
internal object SubagentStretchScene {
    const val STRETCHES = 8
    const val PER_STRETCH = 16
    const val WORKERS = STRETCHES * PER_STRETCH

    /** [stretches] turns of the coordinator's, each starting [perStretch] workers. */
    class Layout(val stretches: Int, val perStretch: Int) {
        val workers = (0 until stretches * perStretch).map { "bc-sub-%03d".format(it) }

        fun run(worker: Int) = "run-${workers[worker]}"
    }

    val standard = Layout(STRETCHES, PER_STRETCH)

    /** Five hundred workers: five turns of a hundred, every stretch's line on the screen with the last one's rows. */
    val fiveHundred = Layout(5, 100)

    val workers = standard.workers

    fun run(worker: Int) = standard.run(worker)

    /** The workers in the fake server, each with a run under way; before the graph reads the list. */
    fun install(api: FakeCursorApi, now: Long, layout: Layout = standard) {
        layout.workers.forEachIndexed { i, id -> api.addRunningAgent(id, "Worker $i", "run-$id", createdAt = BigProject.iso(now - 30_000L - i)) }
    }

    fun transcript(layout: Layout = standard): List<TimelineItem> = buildList {
        val workers = layout.workers
        repeat(layout.stretches) { s ->
            add(UserMessage("u$s", "Batch ${s + 1}."))
            add(
                ActivityGroup(
                    "g$s",
                    (0 until layout.perStretch).map { i ->
                        val n = s * layout.perStretch + i
                        ToolCall("t$n", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "Worker $n", payload = ToolPayload.Subagent("Worker $n", agentId = workers[n], isBackground = true))
                    },
                ),
            )
            add(RunFooter("f$s", "run-coordinator-$s", RunStatus.FINISHED, 38_000, emptyList()))
        }
    }

    /** The transcript in a list as the conversation screen draws it, [source] standing for its subagents' activity. */
    @OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(graph: AppGraph, source: (String) -> Flow<SubagentChild?>, meter: ScaleMeter? = null, layout: Layout = standard) {
        if (meter != null) {
            val root = currentComposer.composition
            remember(root) { root.observe(meter.recompositions) }
        }
        val rows = remember { TranscriptRows.of(transcript(layout), coordinatorMode = true) }
        val index = remember(rows) { SubagentRows.index(rows) }
        val openKey = remember(rows) { rows.filterIsInstance<TranscriptRow.Stretch>().last().key }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                val agentList by graph.agents.state.collectAsState()
                val agentsById = remember(agentList.agents) { agentList.agents.associateBy { it.id } }
                val controls = remember(agentsById) {
                    TranscriptControls(
                        onOpenAgent = {},
                        agentById = { id -> agentsById[id] },
                        coordinatorMode = true,
                        models = LiveModelCatalog.models,
                        subagents = index,
                        placement = SubagentPlacement.Cloud,
                        subagentActivity = source,
                    )
                }
                val open = remember { OpenStretches(mapOf(openKey to true)) }
                LaunchedEffect(open) { open.run() }
                val listed: List<TranscriptRow> by remember(open) { derivedStateOf { open.listed(rows, emptyMap()).rows } }
                CompositionLocalProvider(LocalTranscriptControls provides controls, LocalOpenStretches provides open) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("transcript"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(TranscriptItemSpacing),
                    ) {
                        items(listed, key = { it.key }, contentType = ::transcriptContentType) { row -> TranscriptRowView(row, rowMotion(row, open)) }
                    }
                }
            }
        }
    }

    /** Each stretch's line on screen as it reads, top to bottom: its words, in order. */
    fun lines(compose: ComposeTestRule): List<String> = compose.onAllNodes(hasTestTag("stretch")).fetchSemanticsNodes().map { node ->
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }).joinToString(" ")
    }

    /** Each subagent's row on screen as it reads ("Subagent Worker 115, Reading src/slice115/Part3.kt"), top to bottom. */
    fun rows(compose: ComposeTestRule): List<String> = compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes().map { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    }
}

/**
 * What a working subagent's run says, event by event, over and over, [ROUND] events a round: a step announced (every
 * other round, and by one worker in four only, so the rest are read by their action), a stretch of thinking, a file
 * read, and a reply a word at a time. The same for the same worker at the same count, wherever it is replayed.
 */
internal object WorkerScript {
    const val ROUND = 200
    private const val THINKING = 30

    fun event(worker: Int, tick: Long): RunStreamEvent {
        val k = tick + worker * 37L
        val round = k / ROUND
        val at = (k % ROUND).toInt()
        return when {
            at == 0 && worker % 4 == 0 && round % 2 == 0L -> call("step-$worker-$round", "update_current_step", "completed", """{"current_step":"Slice $worker, pass ${round / 2 + 1}"}""", """{"success":{}}""")
            at <= THINKING -> RunStreamEvent.Thinking("weighing slice $worker option $at. ")
            at == THINKING + 1 -> call("read-$worker-$round", "read_file", "running", """{"target_file":"src/slice$worker/Part$round.kt"}""", null)
            at == THINKING + 2 -> call("read-$worker-$round", "read_file", "completed", """{"target_file":"src/slice$worker/Part$round.kt"}""", """{"success":{"contents":"line","totalLines":1}}""")
            else -> RunStreamEvent.Assistant(if (at == THINKING + 3) "Part $round of slice $worker: " else "w${at - THINKING - 3} ")
        }
    }

    private fun call(id: String, name: String, status: String, args: String, result: String?): RunStreamEvent =
        SseParser.toEvent(SseFrame("tool_call", null, callData(id, name, status, args, result)))!!

    /** [event] as the API writes it: its SSE `event:` name and `data:`. */
    fun frame(worker: Int, tick: Long): Pair<String, String> {
        val k = tick + worker * 37L
        val round = k / ROUND
        val at = (k % ROUND).toInt()
        return when {
            at == 0 && worker % 4 == 0 && round % 2 == 0L -> "tool_call" to callData("step-$worker-$round", "update_current_step", "completed", """{"current_step":"Slice $worker, pass ${round / 2 + 1}"}""", """{"success":{}}""")
            at <= THINKING -> "thinking" to text("weighing slice $worker option $at. ")
            at == THINKING + 1 -> "tool_call" to callData("read-$worker-$round", "read_file", "running", """{"target_file":"src/slice$worker/Part$round.kt"}""", null)
            at == THINKING + 2 -> "tool_call" to callData("read-$worker-$round", "read_file", "completed", """{"target_file":"src/slice$worker/Part$round.kt"}""", """{"success":{"contents":"line","totalLines":1}}""")
            else -> "assistant" to text(if (at == THINKING + 3) "Part $round of slice $worker: " else "w${at - THINKING - 3} ")
        }
    }

    private fun text(value: String) = """{"text":"$value"}"""

    private fun callData(id: String, name: String, status: String, args: String, result: String?) =
        """{"callId":"$id","name":"$name","status":"$status","args":$args${if (result != null) ""","result":$result""" else ""}}"""
}
