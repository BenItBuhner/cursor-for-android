package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's v0.4.26 steer, filmed: a message queued on the account mid-turn, its up arrow tapped, and the row reading
 * "Steering…", then "Steered", a glyph tapped meanwhile refused in place, until the transcript files the message
 * among the running turn's rows and the card folds away as its words fly to the bubble.
 *
 * The run is real as far as this device can tell: a [FaultServer] speaking the account's routes over HTTP/2, the turn's
 * stream held open and its reply trickled at [TokensPerSecond] (four characters a token), the queue, the promote and
 * the transcript's filing all through the app's repositories on a [FaultRig]. Every frame they publish is recorded
 * with when it came, then drawn a 16 ms frame at a time on a held clock through the screen's own pieces — the
 * presented rows, [QueueDeliveries], [QueueStack] with [AccountQueueCard] off `controls.placed(queuePlacement)` —
 * each shown at the frame its moment falls in. Runs only with `ACCOUNT_STEER_FILM_DIR` set, each frame written there
 * as a PNG with a CSV of what the card said.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AccountSteerFilmTest {

    private companion object {
        const val AgentId = "bc-cold-start"
        const val Message = "Also check the release build, not just debug"
        const val TokensPerSecond = 50
        const val CharsPerToken = 4
        const val BeatMs = 40L
        const val FrameMs = 16L
        /** How long the screen shows a refused tap's words (`ConversationViewModel.REFUSAL_SHOWN_MS`). */
        const val RefusalShownMs = 3_000L
        const val Now = 1_800_000_000_000L
        const val Reply =
            "Both `ThemeStore` and `AccountStore` call `SettingsStore.load()`, and each opens `settings.pb` on the main " +
                "thread: two reads of the same 38 KB file before the first frame. I'll make `load()` read the file once into a " +
                "shared `Deferred` that both callers await, so the second call costs nothing, and move that read to " +
                "`Dispatchers.IO` behind the splash, since nothing draws before the theme is known anyway. " +
                "While that builds, the trace also shows `FontLoader` inflating all four weights up front; only regular " +
                "and medium are on the first screen, so the other two can wait for first use. The splash itself holds " +
                "for 180 ms after the first frame is ready, waiting on a remote-config fetch that nothing on the first " +
                "screen reads; I'll let it dismiss on the first frame and apply the config when it lands. That should " +
                "bring cold start under 1.2 s before any work on the fonts. "
        const val Thought = "Bennett wants the release build profiled too; R8 and the baseline profile change the picture there."
        const val Answer =
            "On the release build too, then. R8 inlines `load()` there and the baseline profile changes what is " +
                "compiled ahead of time, so I'll profile `benchmarkRelease` next to debug and report both cold starts. "
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val dir = System.getenv("ACCOUNT_STEER_FILM_DIR")?.let(::File)
    private var server: FaultServer? = null
    private var rig: FaultRig? = null

    /** One publish of the two flows the screen pairs, [atMs] after the recording started. */
    private class Shot(val atMs: Long, val controls: ConversationControls, val state: ConversationState)

    /** What the reader did, [atMs] after the recording started. */
    private class Moments(var queuedMs: Long = -1, var tapMs: Long = -1, var filedMs: Long = -1, var endMs: Long = -1)

    @Before
    fun setUp() {
        assumeTrue(dir != null)
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    @After
    fun tearDown() {
        rig?.let { it.steering.detach(AgentId); it.close() }
        server?.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** The chat: a finished turn, and a turn under way on the reader's prompt, its stream held open. */
    private fun install(server: FaultServer) {
        val first = Now - 180_000L
        val second = Now - 20_000L
        server.runs["run-1"] = RunDto(id = "run-1", agentId = AgentId, status = "FINISHED", createdAt = iso(first), updatedAt = iso(first + 50_000L), durationMs = 50_000L, result = null)
        server.logs["run-1"] = listOf(
            "status" to """{"runId":"run-1","status":"RUNNING"}""",
            "assistant" to """{"text":"Cold start is 1.9 s on the Pixel 7. Two thirds of it is the settings store reading its file twice on the main thread; the rest is font loading."}""",
            "result" to """{"runId":"run-1","status":"FINISHED","text":"","durationMs":50000}""",
        )
        server.runs["run-2"] = RunDto(id = "run-2", agentId = AgentId, status = "RUNNING", createdAt = iso(second), updatedAt = iso(second))
        server.logs["run-2"] = listOf(
            "status" to """{"runId":"run-2","status":"RUNNING"}""",
            "tool_call" to """{"callId":"c-1","name":"read","status":"completed","args":{"path":"app/src/main/java/app/SettingsStore.kt"},"result":{"success":{"content":"class SettingsStore"}}}""",
        )
        server.agents[AgentId] = AgentDto(id = AgentId, name = "Cold start profiling", status = "ACTIVE", createdAt = iso(first), updatedAt = iso(second), latestRunId = "run-2", url = "https://cursor.com/agents/$AgentId")
        server.v0[AgentId] = V0AgentDto(id = AgentId, name = "Cold start profiling", status = "RUNNING")
        server.transcripts[AgentId] = listOf(
            V0ConversationMessageDto("run-1-u", "user_message", "Profile the cold start on the Pixel 7 and tell me what's slow."),
            V0ConversationMessageDto("run-1-a0", "assistant_message", "Cold start is 1.9 s on the Pixel 7. Two thirds of it is the settings store reading its file twice on the main thread; the rest is font loading."),
            V0ConversationMessageDto("run-2-u", "user_message", "Fix the double disk read, then profile again."),
        )
    }

    /** The steer on the fault server, every publish of the two flows kept with when it came. */
    private fun record(moments: Moments): List<Shot> = runBlocking {
        val server = FaultServer(rttMillis = 120L..260L, http2 = true).start().also { server = it }
        server.liveRunStreams = true
        server.queueLagMs = 0L
        install(server)
        val rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE, queuePollMs = 1_500L, http2 = true)
            .also { it.now = Now; this@AccountSteerFilmTest.rig = it }
        rig.agents.refresh()
        rig.conversations.attach(AgentId)
        rig.steering.attach(AgentId)
        val state = { rig.conversations.state(AgentId).value }
        val controls = { rig.steering.state(AgentId).value }
        rig.awaitUntil(60_000) { state().let { !it.isLoading && it.isStreaming } && controls().isQueueAvailable }

        val shots = CopyOnWriteArrayList<Shot>()
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        val recorder = rig.scope.launch(Dispatchers.Default.limitedParallelism(1)) {
            combine(rig.steering.state(AgentId), rig.conversations.state(AgentId)) { c, s -> c to s }.collect { (c, s) -> shots += Shot(ms(), c, s) }
        }
        // The agent writing its reply, no faster than TokensPerSecond. It reads the steer at its next step: once the
        // transcript has the message it finishes the sentence it is on, thinks, and answers in a message of its own.
        val step = TokensPerSecond * CharsPerToken * BeatMs.toInt() / 1_000
        val writer = rig.scope.launch(Dispatchers.IO) {
            suspend fun write(event: String, text: String, until: () -> Boolean = { false }) {
                var at = 0
                var end = text.length
                while (isActive && at < end) {
                    if (end == text.length && until()) end = text.indexOf(". ", at).let { if (it < 0) text.length else it + 2 }
                    val chunk = text.substring(at, minOf(end, at + step))
                    at += chunk.length
                    server.appendRunEvents("run-2", listOf(event to """{"text":${kotlinx.serialization.json.JsonPrimitive(chunk)}}"""))
                    delay(BeatMs)
                }
            }
            write("assistant", Reply) { moments.filedMs >= 0 }
            while (moments.filedMs < 0) delay(BeatMs)
            write("thinking", Thought)
            write("assistant", Answer)
        }

        delay(1_200)
        // Sent from the composer mid-turn: staged unshown, filed with the account under an id minted here.
        val followupId = AccountFollowup.newId()
        val staged = rig.conversations.stageFollowUp(AgentId, Message, show = false)
        rig.conversations.sendStagedVia(AgentId, staged, followupId = followupId) {
            rig.steering.sendFollowup(AgentId, AccountFollowup(text = Message, followupId = followupId)).getOrThrow()
        }.getOrThrow()
        moments.queuedMs = ms()
        rig.awaitUntil(20_000) { controls().queue.any { it.id == followupId } }
        delay(1_600)
        // The up arrow: the account promotes it into the turn under way.
        moments.tapMs = ms()
        assertThat(rig.steering.promotePending(AgentId, followupId).getOrThrow()).isEqualTo(SteerOutcome.QUEUED)
        rig.awaitUntil(45_000) { state().items.any { it is UserMessage && it.text == Message } }
        moments.filedMs = ms()
        delay(3_600)
        moments.endMs = ms()
        writer.cancel()
        recorder.cancel()
        println("== recorded ${shots.size} shots over ${moments.endMs} ms: queued ${moments.queuedMs}, tap ${moments.tapMs}, filed ${moments.filedMs}")
        shots.toList()
    }

    private var shown by mutableStateOf<PresentedTranscript?>(null)
    private var controls by mutableStateOf(ConversationControls())
    private var refusedId by mutableStateOf<String?>(null)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val flights = QueueFlights()

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    SendMotionHost(motion) {
                        val sentFades = rememberSentFades(AgentId, animatorsEnabled = { true })
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                            val presented = shown ?: return@Column
                            val placed = remember(controls, presented) { controls.placed(presented.state.queuePlacement) }
                            val rows = SteeredCards.accountRows(placed.queue, emptyList())
                            val listState = rememberLazyListState()
                            val scroll = rememberTranscriptScroll(listState, AgentId)
                            val scope = rememberCoroutineScope()
                            val handoversBefore = flights.handovers
                            val newest = presented.rows.lastOrNull()?.key
                            LaunchedEffect(presented.rows.size, newest) {
                                snapshotFlow { scroll.isJumping }.first { !it }
                                if (flights.handovers != handoversBefore) scroll.settleToNewest(scope) else listState.requestScrollToItem(0)
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                sentFades.look(presented.userMessages)
                                CompositionLocalProvider(LocalSentFades provides sentFades) {
                                    LazyColumn(
                                        state = listState,
                                        reverseLayout = true,
                                        userScrollEnabled = false,
                                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("film-transcript"),
                                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(presented.rows.asReversed(), key = { it.key }) { TranscriptRowView(it) }
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 10.dp)) {
                                QueueDeliveries(
                                    flights = flights,
                                    rows = LinkedHashMap<String, String>().apply { rows.forEach { put(it.id, it.previewText) } },
                                    transcript = remember(presented) { presented.userMessages.mapTo(HashSet()) { it.id } },
                                    scrolledAway = { false },
                                )
                                QueueStack(
                                    keys = rows.map { "account:${it.id}" },
                                    stacked = true,
                                    onStackedChange = {},
                                    gapBelow = 4.dp,
                                    animate = { true },
                                ) { index, face ->
                                    AccountQueueCard(
                                        item = rows[index],
                                        position = index + 1,
                                        count = rows.size,
                                        inFlightIds = placed.inFlightQueueIds,
                                        onSteer = {},
                                        onRemove = {},
                                        onUpdate = { _, _ -> },
                                        onEditing = { _, _ -> },
                                        steers = true,
                                        onMove = { _, _ -> },
                                        flights = flights,
                                        face = face,
                                        refused = refusedId == rows[index].id,
                                        onRefused = { refusedId = it.id },
                                    )
                                }
                                ComposerBox(
                                    value = "",
                                    onValueChange = {},
                                    placeholder = "Follow up (queues on your account)…",
                                    onSend = {},
                                    isRunning = true,
                                    onStop = {},
                                    plusMenu = ComposerMenuActions(onPickMedia = {}),
                                    modelLabel = "Claude Fable 5.1",
                                    onModel = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun draw(file: File, touch: Pair<Float, Float>?, touchAlpha: Float) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        if (touch != null && touchAlpha > 0f) {
            // Where the finger went down, as Android's "show taps" draws it.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; alpha = (touchAlpha * 110).toInt() }
            Canvas(bitmap).drawCircle(touch.first, touch.second, 18f * root.resources.displayMetrics.density, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun centerOf(description: String): Pair<Float, Float>? =
        compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.let { it.center.x to it.center.y }

    @Test
    fun steer() {
        val moments = Moments()
        val shots = record(moments)
        val presenter = TranscriptPresenter()
        var last: PresentedTranscript? = null
        val presentations = HashMap<ConversationState, PresentedTranscript>()
        fun present(state: ConversationState): PresentedTranscript = presentations.getOrPut(state) {
            PresentedTranscript(state, presenter.present(state.items, coordinatorMode = false, runActive = state.runStatus?.isActive == true || state.isStreaming), last).also { last = it }
        }

        val from = (moments.queuedMs - 900).coerceAtLeast(0)
        val first = shots.last { it.atMs <= from }
        compose.runOnUiThread { shown = present(first.state); controls = first.controls }
        show()
        repeat(30) { compose.mainClock.advanceTimeBy(FrameMs); compose.waitForIdle() }

        val out = File(dir, "account-steer").apply { deleteRecursively(); mkdirs() }
        val log = StringBuilder("frame,ms,rows,steer,note,refused,bubble\n")
        var at = shots.indexOf(first)
        var touch: Pair<Float, Float>? = null
        var touchedAt = -1L
        var refusalAt = -1L
        var refusedTapped = false
        val phases = LinkedHashSet<SteerPhase?>()
        var frame = 0
        var t = from
        while (t <= moments.endMs) {
            var next: Shot? = null
            while (at + 1 < shots.size && shots[at + 1].atMs <= t) next = shots[++at]
            if (next != null) {
                val chosen = next
                val presented = present(chosen.state)
                compose.runOnUiThread { shown = presented; controls = chosen.controls }
            }
            if (touchedAt < 0 && t >= moments.tapMs) {
                touch = centerOf(QueueGlyphs.upArrow(true))
                touchedAt = t
            }
            // A glyph tapped while the steer is out: refused, the row saying why for the screen's while.
            if (!refusedTapped && touchedAt >= 0 && t >= touchedAt + 450) {
                refusedTapped = true
                val pencil = compose.onAllNodes(hasContentDescription("Edit queued follow-up"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                if (pencil) {
                    touch = centerOf("Edit queued follow-up")
                    touchedAt = t
                    compose.onNode(hasContentDescription("Edit queued follow-up"), useUnmergedTree = true).performClick()
                    refusalAt = t
                }
            }
            if (refusalAt >= 0 && t >= refusalAt + RefusalShownMs) compose.runOnUiThread { refusedId = null }
            compose.mainClock.advanceTimeBy(FrameMs)
            compose.waitForIdle()
            val row = controls.placed(shown!!.state.queuePlacement).queue.singleOrNull { QueuePlacement.textKey(it.text) == QueuePlacement.textKey(Message) }
            if (row != null) phases += row.steer
            if (t >= moments.tapMs && row != null) assertWithMessage("frame $frame: the steered row reads ${row.note}").that(row.note).isNull()
            val bubble = shown!!.userMessages.any { it.text == Message }
            log.append(frame).append(',').append(t).append(',').append(controls.placed(shown!!.state.queuePlacement).queue.size).append(',')
                .append(row?.steer ?: "").append(',').append(row?.note ?: "").append(',').append(refusedId != null).append(',').append(bubble).append('\n')
            val since = t - touchedAt
            draw(File(out, "f_%04d.png".format(frame)), touch, if (touchedAt >= 0 && since < 360) 1f - since / 360f else 0f)
            frame++
            t += FrameMs
        }
        File(dir, "account-steer.csv").writeText(log.toString())
        println("== filmed $frame frames; phases on the card: $phases; refused tap at $refusalAt ms")
        assertThat(phases).containsAtLeast(null, SteerPhase.STEERED)
        assertThat(shown!!.userMessages.count { it.text == Message }).isEqualTo(1)
    }
}
