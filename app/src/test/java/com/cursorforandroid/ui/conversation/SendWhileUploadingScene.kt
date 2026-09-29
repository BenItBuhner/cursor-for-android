package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant

/**
 * The chat screen in Extended mode with a file attached whose upload is held until [finishUpload]: the chat idle (a
 * send goes into a bubble) or mid-turn (a send goes to the account's queue). The upload's presign waits on a gate and
 * is then refused as unimplemented, so the file is carried inline and nothing leaves the process. The account's queue
 * is [OutgoingSendTest.AccountService], at 300–900 ms a call.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class SendWhileUploadingScene(
    private val compose: AndroidComposeTestRule<*, ComponentActivity>,
    private val appVersion: String = "0.0.0-test",
) : ExternalResource() {
    val api = FakeCursorApi()
    val streamer = FakeRunStreamer()
    val account = OutgoingSendTest.AccountService(api)
    private val gate = CompletableDeferred<Unit>()
    val presigns = java.util.concurrent.atomic.AtomicInteger()
    lateinit var graph: AppGraph
    val file: PendingFile = PendingFile.of(PromptFile(ByteArray(48_000) { 4 }, "crash-report.pdf", "application/pdf"), id = "file-crash-report")

    private val uploads = object : PromptUploadApi {
        override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
            presigns.incrementAndGet()
            gate.await()
            throw ConnectRpcException(501, "unimplemented", "Prompt uploads are not on for this account.")
        }
        override suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion = PromptUploadCompletion.COMPLETED
        override suspend fun abort(uploadId: String, s3UploadId: String) = Unit
    }

    override fun before() {
        AppClock.nowMillis = { Instant.parse(STARTED_AT).toEpochMilli() + 3 * 60_000L }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            appVersion = appVersion,
            real = CursorBackend(api, streamer, isDemo = false),
            followupQueue = account,
            promptUploadApi = uploads,
        )
    }

    override fun after() {
        // Not graph.outgoing.resetAll(): blocking this thread on it would hold the main looper its sends run on.
        gate.complete(Unit)
        graph.attachmentUploads.resetAll()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** Signs in with Extended mode on and seeds the chat — mid-turn when [running] — then shows its screen. */
    fun show(running: Boolean, mode: ThemeMode = ThemeMode.Dark) {
        runBlocking {
            graph.session.signIn("key_abc").getOrThrow()
            graph.extendedMode.acknowledge()
            check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
            check(graph.extendedMode.setEngine(TranscriptEngine.STABLE)) { "The transcript engine could not be set." }
            if (running) {
                api.addRunningAgent(AGENT, "Crash on launch", RUN, createdAt = STARTED_AT)
                api.transcripts[AGENT] = listOf(V0ConversationMessageDto("$RUN-u", "user_message", PROMPT))
                streamer.emit(RUN, RunStreamEvent.Status(RUN, RunStatus.RUNNING))
                streamer.emit(RUN, RunStreamEvent.Assistant("Reading the startup path first. "))
            } else {
                api.addIdleAgent(AGENT, "Crash on launch", RUN, createdAt = STARTED_AT, result = REPLY)
                api.transcripts[AGENT] = listOf(
                    V0ConversationMessageDto("$RUN-u", "user_message", PROMPT),
                    V0ConversationMessageDto("$RUN-a", "assistant_message", REPLY),
                )
                // The finished run's log is served whole, so its activity is read and no "Loading the activity" row shows.
                streamer.emit(RUN, RunStreamEvent.Assistant(REPLY))
                streamer.emit(RUN, RunStreamEvent.Result(RUN, RunStatus.FINISHED, REPLY, 65_000, null))
                streamer.emit(RUN, RunStreamEvent.Done)
            }
            graph.agents.refresh()
        }
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, AGENT, onBack = {})
                }
            }
        }
        val placeholder = if (running) "Follow up (queues on your account)…" else "Follow up…"
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(placeholder)).fetchSemanticsNodes().isNotEmpty() }
        if (running) {
            compose.waitUntil(30_000) { graph.followUps.decide(AGENT).busy }
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Reading the startup path first.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.waitUntil(15_000) { viewModel.capabilities.value.promptFiles }
        if (!running) {
            waitFor(30_000, describe = { "the finished turn's activity" }) {
                graph.conversations.state(AGENT).value.traceStatus.pending == 0 &&
                    compose.onAllNodes(hasText("Loading the activity", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    /**
     * Waits for [condition] in real time, the main looper's clock moved on meanwhile: the sends' pauses run on it, and
     * the paused looper does not advance it on its own.
     */
    fun waitFor(timeoutMs: Long = 15_000, describe: () -> String = { "" }, condition: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.nanoTime() > until) throw AssertionError("timed out: ${describe()}")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
        }
    }

    /** The screen's own view model (see ConversationScreen's `viewModel(key = …)`). */
    val viewModel: ConversationViewModel
        get() = ViewModelProvider(compose.activity, ConversationViewModel.Factory(graph, AGENT))["conversation-$AGENT", ConversationViewModel::class.java]

    /** [text] typed and [file] attached, its upload held: the footer says "Uploading…". */
    fun attachUploading(text: String) {
        compose.runOnIdle {
            viewModel.setDraft(text)
            viewModel.addFiles(listOf(file))
        }
        waitFor(describe = { "the upload hint" }) { compose.onAllNodes(hasText(UPLOADING)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The held upload lets go: the file is up and the footer's hint is gone. */
    fun finishUpload() {
        gate.complete(Unit)
        waitFor(describe = { "the upload done" }) { compose.onAllNodes(hasText(UPLOADING)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    val sendButton: SemanticsNodeInteraction get() = compose.onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(COMPOSER)))
    val field: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER)))

    companion object {
        const val AGENT = "bc-send-uploading"
        const val RUN = "run-send-uploading-1"
        const val STARTED_AT = "2025-01-15T13:57:00.000Z"
        const val COMPOSER = "follow-up-composer"
        const val UPLOADING = "Uploading…"
        const val PROMPT = "The app crashes on launch on Android 15. Find out why and fix it."
        const val REPLY = "Fixed: the splash theme referenced a color missing on API 35."
    }
}
