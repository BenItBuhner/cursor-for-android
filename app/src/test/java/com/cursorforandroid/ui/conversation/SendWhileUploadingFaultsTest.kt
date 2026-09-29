package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConnectPromptUploadApi
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.api.SteeringApi
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Send tapped while a file is still going up (COMP-7), against the account served over a real socket at 300–900 ms a
 * round trip: the presign, the part's `PUT` and its completion, and the message's `AddAsyncFollowupBackgroundComposer`.
 *
 * Into a bubble (the chat idle), the tap is taken: the message is the bubble's from then on, and it goes out exactly
 * once, after its file is up, the upload not redone and nothing written twice unless Retry is tapped. The composer's
 * hint is collected throughout, as the screen collects it: the send used to be refused with "…The message goes out once
 * the files are up." and nothing ever sent it. For a queue (a turn under way), the tap is refused until the file is up,
 * the composer kept as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendWhileUploadingFaultsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val server = FaultServer(rttMillis = 300L..900L).start()
    private lateinit var graph: AppGraph
    private val stores = ArrayList<ViewModelStore>()
    private val collectors = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() = runBlocking {
        val key = { "fault-key" }
        val client = CursorApiFactory.okHttp(key).newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val streamer = SseRunStreamer(CursorApiFactory.sseClient(client), key, urlFor = { agentId, runId -> "${server.baseUrl}v1/agents/$agentId/runs/$runId/stream" })
        val accountClient = CursorApiFactory.loginClient().newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val rpc = ConnectJsonClient(accountClient, server.baseUrl)
        val tokens = SessionTokenProvider(accountClient, key, apiUrl = server.baseUrl)
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            real = CursorBackend(CursorApiFactory.retrofit(client, server.baseUrl), streamer, isDemo = false),
            followupQueue = SteeringApi(rpc, tokens),
            promptUploadApi = ConnectPromptUploadApi(rpc, tokens),
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.extendedMode.acknowledge()
        check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
        check(graph.extendedMode.setEngine(TranscriptEngine.STABLE)) { "The transcript engine could not be set." }
    }

    @After
    fun tearDown() {
        collectors.coroutineContext[Job]?.cancel()
        stores.forEach { it.clear() }
        runBlocking { graph.outgoing.resetAll() }
        graph.attachmentUploads.resetAll()
        server.close()
    }

    /** The chat's composer as the screen holds it: its catalogue read, Extended mode known, the upload hint collected. */
    private fun open(): ConversationViewModel = runBlocking {
        graph.agents.refresh()
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, ConversationViewModel.Factory(graph, AGENT))[ConversationViewModel::class.java]
        withTimeout(15_000) {
            vm.modelPicker.first { !it.isLoading }
            vm.capabilities.first { it.promptFiles }
        }
        collectors.launch { vm.uploadHint.collect {} }
        vm
    }

    private fun file(name: String) = PendingFile.of(PromptFile(ByteArray(4_096) { 5 }, name, "application/pdf"))

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; requests=${server.seen.groupingBy { it.route }.eachCount()}", e)
        }
    }

    private fun pendingBubbles(): List<UserMessage> = graph.conversations.state(AGENT).value.items.filterIsInstance<UserMessage>().filter { it.isPending }

    /** Every write the account and the storage saw, by route: what a resend would show. */
    private fun writes(): Map<Route, Int> = WRITES.associateWith { server.requests(it).size }.filterValues { it > 0 }

    private fun ConversationViewModel.composerIsEmpty() = draftText.value.isEmpty() && pendingFiles.value.isEmpty() && pendingAttachments.value.isEmpty()

    @Test
    fun `into a bubble, a send tapped mid-upload goes out once the file is up, exactly once`() = runBlocking<Unit> {
        server.addIdleAgent(AGENT, "Green screen", "run-0")
        val vm = open()
        val putHeld = Fault.Held()
        server.script(Route.UploadPut, putHeld)
        val spec = file("spec.pdf")
        vm.addFiles(listOf(spec))
        vm.setDraft("Read the spec")
        awaitUntil("the part on its way") { server.requests(Route.UploadPut).isNotEmpty() }
        withTimeout(10_000) { vm.uploadHint.first { it != null } }

        val sent = vm.submit()

        assertWithMessage("toast=${vm.toastMessage.value}").that(sent).isEqualTo(ConversationViewModel.Sent.Bubble("Read the spec"))
        assertThat(vm.toastMessage.value).isNull()
        assertThat(vm.composerIsEmpty()).isTrue()
        val bubble = pendingBubbles().singleOrNull() ?: run {
            awaitUntil("the bubble") { pendingBubbles().isNotEmpty() }
            pendingBubbles().single()
        }
        // The message waits for its file, on the bubble: the account is not asked while the part is held.
        delay(1_500)
        assertThat(server.queueAdds).isEmpty()
        assertThat(vm.outgoingStatuses.value[bubble.id]).isInstanceOf(OutgoingStatus.Uploading::class.java)

        putHeld.release()
        awaitUntil("the message filed") { server.queueAdds.isNotEmpty() && vm.outgoingStatuses.value[bubble.id] == null }
        val filed = server.queueAdds.single()
        assertThat(filed.toString()).contains("Read the spec")
        assertThat(filed.toString()).contains(server.uploadedParts.single())
        // Watched well past the account's slowest round trip: nothing goes up or out a second time on its own.
        delay(3_000)
        assertThat(writes()).containsExactly(Route.UploadPresign, 1, Route.UploadPut, 1, Route.UploadComplete, 1, Route.QueueAdd, 1)
        assertThat(server.runs.values.count { it.id != "run-0" }).isEqualTo(1)
        assertThat(vm.composerIsEmpty()).isTrue()
    }

    @Test
    fun `into a bubble, a refused send mid-upload is written again only when Retry is tapped`() = runBlocking<Unit> {
        server.addIdleAgent(AGENT, "Green screen", "run-0")
        val vm = open()
        val putHeld = Fault.Held()
        server.script(Route.UploadPut, putHeld)
        server.script(Route.QueueAdd, Fault.Status(503, "unavailable", "The account service is unavailable right now."))
        vm.addFiles(listOf(file("trace.har")))
        vm.setDraft("Find the 500")
        awaitUntil("the part on its way") { server.requests(Route.UploadPut).isNotEmpty() }
        withTimeout(10_000) { vm.uploadHint.first { it != null } }

        assertThat(vm.submit()).isEqualTo(ConversationViewModel.Sent.Bubble("Find the 500"))
        awaitUntil("the bubble") { pendingBubbles().isNotEmpty() }
        val bubble = pendingBubbles().single()
        putHeld.release()
        awaitUntil("the refusal on the bubble") { vm.outgoingStatuses.value[bubble.id] is OutgoingStatus.Failed }
        delay(4_000)
        assertThat(writes()).containsExactly(Route.UploadPresign, 1, Route.UploadPut, 1, Route.UploadComplete, 1, Route.QueueAdd, 1)
        assertThat(vm.outgoingStatuses.value[bubble.id]).isInstanceOf(OutgoingStatus.Failed::class.java)

        vm.retryOutgoing(bubble.id)
        awaitUntil("filed on Retry") { vm.outgoingStatuses.value[bubble.id] == null }
        delay(3_000)
        // The retry sends the file's reference: the upload is not redone.
        assertThat(writes()).containsExactly(Route.UploadPresign, 1, Route.UploadPut, 1, Route.UploadComplete, 1, Route.QueueAdd, 2)
        assertThat(server.queueAdds.map { it["followupId"].toString() }.distinct()).hasSize(1)
    }

    @Test
    fun `for the queue, a send tapped mid-upload is refused until the file is up, the composer kept`() = runBlocking<Unit> {
        server.addRunningAgent(AGENT, "Green screen", "run-live")
        val vm = open()
        awaitUntil("the chat on its turn") { graph.conversations.state(AGENT).value.runStatus?.isActive == true && graph.followUps.decide(AGENT).busy }
        val putHeld = Fault.Held()
        server.script(Route.UploadPut, putHeld)
        val spec = file("spec.pdf")
        vm.addFiles(listOf(spec))
        vm.setDraft("When you are done, read the spec")
        awaitUntil("the part on its way") { server.requests(Route.UploadPut).isNotEmpty() }

        assertThat(vm.submit()).isNull()

        assertThat(vm.toastMessage.value).isNull()
        assertThat(vm.draftText.value).isEqualTo("When you are done, read the spec")
        assertThat(vm.pendingFiles.value.map { it.id }).containsExactly(spec.id)
        assertThat(graph.followUps.state(AGENT).value.queue).isEmpty()
        delay(1_000)
        assertThat(server.queueAdds).isEmpty()

        putHeld.release()
        withTimeout(15_000) { vm.fileUploads.first { it[spec.id]?.done == true } }
        assertThat(vm.submit()).isEqualTo(ConversationViewModel.Sent.Queued("When you are done, read the spec"))
        assertThat(vm.composerIsEmpty()).isTrue()
        awaitUntil("queued on the account") { server.queueAdds.isNotEmpty() && !vm.isSending.value }
        delay(2_000)
        assertThat(server.queueAdds).hasSize(1)
        assertThat(server.queueAdds.single().toString()).contains(server.uploadedParts.single())
    }

    private companion object {
        const val AGENT = "bc-send-uploading"
        val WRITES = listOf(Route.UploadPresign, Route.UploadPut, Route.UploadComplete, Route.UploadAbort, Route.QueueAdd, Route.CreateRun)
    }
}
