package com.cursorforandroid.tools.transcriptverify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.auth.CursorLoginEndpoints
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The harness's two entry points, hosted by Robolectric because the app's stores take an Android `Context`:
 *
 *  - [live] runs [TranscriptVerifier] against a real account. It only runs when [TranscriptVerifyMain] invoked it
 *    (`tools/transcript-verify/run.sh --agent … --key-file …`); under the ordinary unit-test task it is skipped.
 *  - [replay] runs the same pipeline over [ReplayServer]. Invoked by `run.sh --replay` it prints the report like a
 *    live run; under the unit-test task it is the CI test — the same options every time, and the outcome asserted:
 *    the coordinator's four messages on screen (three from the record in its three shapes, one from the live
 *    stream's fragments), every turn paired with its run across nine pages of the run list, the pairing agreeing
 *    with the turns' timestamps, the wall of injected turns folded, and a send accepted and answered.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptVerifyHarness {

    @Test
    fun live() = runBlocking<Unit> {
        val options = TranscriptVerifyOptions.fromProperties()
        assumeTrue("the live harness runs only through tools/transcript-verify/run.sh", options != null && options.live)
        options!!
        val agentId = requireNotNull(options.agentId) { "--agent" }
        val keyFile = requireNotNull(options.keyFile) { "--key-file" }
        // The key: read from the file the operator named, into the closure the app's clients read it from, and nowhere else.
        val key = keyFile.readText().trim()
        require(key.isNotEmpty()) { "The key file ${keyFile.path} is empty." }
        require(!key.contains('\n')) { "The key file ${keyFile.path} holds more than one line." }
        val keyProvider = { key }
        quietOkHttp()
        val ledger = CallLedger()
        val apiClient = CursorApiFactory.okHttp(keyProvider).newBuilder().addInterceptor(ledger).build()
        val api = CursorApiFactory.retrofit(apiClient)
        val streamer = SseRunStreamer(CursorApiFactory.sseClient(apiClient), keyProvider)
        val accountClient = CursorApiFactory.loginClient().newBuilder().addInterceptor(ledger).build()
        val rpc = ConnectJsonClient(accountClient, CursorLoginEndpoints.API_URL)
        val tokens = SessionTokenProvider(accountClient, keyProvider, sessionAllowed = { options.extended })
        val backend = VerifyBackend(
            api = api,
            streamer = streamer,
            record = if (options.extended) HeadlessConversationApi(rpc, tokens) else null,
            accountList = if (options.extended) ({ BackgroundComposerApi(rpc, tokens).list() }) else null,
            ledger = ledger,
            description = "backend=live (api.cursor.com; ${if (options.extended) "api2.cursor.sh through the account session" else "api2 not used"}) key=file:${keyFile.name} (${key.length} chars, never printed)",
        )
        val cacheDir = freshCacheDir()
        try {
            TranscriptVerifier(context(), backend, options, agentId, System.out, cacheDir).run()
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun replay() = runBlocking<Unit> {
        val invoked = TranscriptVerifyOptions.fromProperties()
        assumeTrue("replay runs under the unit tests, or through run.sh --replay", invoked == null || invoked.replay)
        val options = invoked ?: TranscriptVerifyOptions(replay = true, followSeconds = 30, send = "Where do we stand? (transcript-verify replay send)")
        quietOkHttp()
        val ledger = CallLedger()
        val report = StringBuilder()
        val out: Appendable = if (invoked != null) System.out else report
        val cacheDir = freshCacheDir()
        val (outcome, turnCount) = ReplayServer().start().use { server ->
            try {
                TranscriptVerifier(context(), server.backend(options.extended, ledger), options, server.agentId, out, cacheDir, firstLoadTimeoutMs = 90_000L, pageTimeoutMs = 30_000L, settleTimeoutMs = 60_000L, accountRoundMs = 3_000L).run() to server.turnCount
            } finally {
                cacheDir.deleteRecursively()
            }
        }
        if (invoked != null) return@runBlocking
        // The CI test: what the fixtures must come out as. The report is in the failure's words when they do not.
        val state = outcome.state!!
        assertThat(state.isLoading).isFalse()
        assertThat(state.isProjectConversation).isTrue()
        val messages = outcome.messageRows.map { (CoordinatorTranscript.reinterpret(it.call).payload as ToolPayload.CoordinatorMessage).message }
        assertWithReport(outcome.report, "the coordinator's four messages, three from the record and one from the live fragments") {
            assertThat(messages).hasSize(4)
            assertThat(messages[0]).startsWith("Shards are queued")
            assertThat(messages[1]).startsWith("All four shards rendered")
            assertThat(messages[2]).startsWith("The phone worker has the Fold8")
            assertThat(messages[3]).startsWith("All six paused workers are resumed")
        }
        val load = outcome.load!!
        assertWithReport(outcome.report, "the record is the source and the run list was paged to cover it") {
            assertThat(load.source).isEqualTo("record")
            assertThat(load.runsLoaded).isEqualTo(turnCount)
            assertThat(load.runsComplete).isTrue()
            assertThat(load.runs.size).isEqualTo(turnCount)
            assertThat(load.record!!.turnsLoaded).isEqualTo(turnCount)
        }
        assertWithReport(outcome.report, "every turn paired with its run, by position and by time alike") {
            assertThat(outcome.turnLines.none { it.contains("UNPAIRED") }).isTrue()
            assertThat(outcome.turnLines.none { it.contains("PAIR-MISMATCH") }).isTrue()
        }
        assertWithReport(outcome.report, "the injected turns fold into stretches, never as rows of their own") {
            assertThat(outcome.rows.none { it is TranscriptRow.Event || it is TranscriptRow.Events }).isTrue()
            assertThat(outcome.rows.filterIsInstance<TranscriptRow.Stretch>().any { it.eventCount == 148 }).isTrue()
            assertThat(outcome.rows.filterIsInstance<TranscriptRow.Stretch>().any { it.eventCount == 18 }).isTrue()
        }
        assertWithReport(outcome.report, "a send to the idle agent is accepted and its reply rendered from the live stream") {
            val send = outcome.sendResult!!
            assertThat(send.decision.busy).isFalse()
            assertThat(send.sent).isTrue()
            assertThat(send.runId).isNotNull()
            assertThat(send.replyStage).startsWith("body")
        }
        assertWithReport(outcome.report, "the ledger names every host and route the run used") {
            val calls = ledger.entries.map { "${it.method} ${it.url.substringBefore('?')}" }
            assertThat(calls.any { it.endsWith("/auth/exchange_user_api_key") }).isTrue()
            assertThat(calls.any { it.endsWith("/FetchBackgroundComposer") }).isTrue()
            assertThat(calls.any { it.endsWith("/GetLatestAgentConversationState") }).isTrue()
            assertThat(calls.any { it.endsWith("/ListBackgroundComposers") }).isTrue()
            assertThat(calls.count { it.startsWith("GET") && it.endsWith("/runs") }).isAtLeast(2)
            assertThat(calls.any { it.startsWith("POST") && it.endsWith("/runs") }).isTrue()
            assertThat(calls.any { it.endsWith("/stream") }).isTrue()
            assertThat(outcome.report).doesNotContain("replay-key")
        }
    }

    private inline fun assertWithReport(report: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$what — ${e.message}\n\n--- report ---\n$report", e)
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun freshCacheDir(): File = Files.createTempDirectory("transcript-verify").toFile()

    /** OkHttp's debug-build request log (the app's `HttpLoggingInterceptor`) goes to java.util.logging; the ledger is the report's account of the calls. */
    private fun quietOkHttp() {
        OKHTTP_LOGGER.level = Level.OFF
    }

    private companion object {
        /** Held strongly: java.util.logging keeps loggers weakly, and a level set on one nobody holds is forgotten with it. */
        val OKHTTP_LOGGER: Logger = Logger.getLogger("okhttp3.OkHttpClient")
    }
}
