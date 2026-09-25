package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.FollowupQueueApi
import com.cursorforandroid.data.api.PresignedPromptUpload
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PromptUploadCompletion
import com.cursorforandroid.data.api.PromptUploadPart
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.ConversationControls
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A message with files, sent from the chat composer, against an account that answers in 300–900 ms and takes its
 * uploads slowly (Bennett: "the file attachment or attachments will still sit in that chat composer for a while… at
 * least a couple seconds or even longer. Sometimes it is instantaneous"). The rule pinned here: the tap empties the
 * composer — text and every chip — before anything is awaited; from then on the message is the transcript's, its
 * bubble carrying the attachments and the send's progress; a failure of an upload or the send shows on that bubble
 * with Retry and Edit, never a toast and a vanished message; and a draft begun meanwhile is its own.
 *
 * The account here is [AccountService] (the follow-up queue, scripted per call) and [SlowStorage] (the presigned
 * `PUT`s, held or slowed per file), wired into the real graph through its test seams; the run request is the
 * fake API's. Latency is real time on a real dispatcher — `Main` is the unconfined test dispatcher, whose `delay`
 * is virtual — so the waits below are the composer's actual waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OutgoingSendTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val api = FakeCursorApi()
    private val storage = SlowStorage()
    private val account = AccountService(api)
    private lateinit var graph: AppGraph
    private lateinit var uploads: AttachmentUploads
    /** Every view model opened by a test, cleared with it, so no composer's scope outlives the test that made it. */
    private val stores = ArrayList<ViewModelStore>()

    @Before
    fun setUp() = runBlocking {
        storage.start()
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            real = CursorBackend(api, FakeRunStreamer(), isDemo = false),
            followupQueue = account,
            promptUploadApi = storage.api,
        )
        graph.session.signIn("key_abc").getOrThrow()
        graph.extendedMode.acknowledge()
        check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
        // The composer's chat load under Beta would read the account's record over the graph's own api2 client,
        // which no fake here stands in for; the sends under test do not depend on the engine.
        check(graph.extendedMode.setEngine(TranscriptEngine.STABLE)) { "The transcript engine could not be set." }
        api.addIdleAgent(AGENT, "Green screen", "run-0")
        graph.agents.refresh()
        uploads = graph.attachmentUploads
    }

    @After
    fun tearDown() {
        // Nothing of a test's sends, uploads or composers goes on into the next test: the composers are cleared,
        // the sends and uploads cancelled, the storage's held requests released before it closes.
        stores.forEach { it.clear() }
        account.gate?.countDown()
        runBlocking { graph.outgoing.resetAll() }
        graph.attachmentUploads.resetAll()
        storage.release()
        storage.shutdown()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The account, scripted.

    /** `AddAsyncFollowupBackgroundComposer` as the test needs it: 300–900 ms a call, refusing or queueing on cue. */
    class AccountService(private val api: FakeCursorApi) : FollowupQueueApi {
        val sent = CopyOnWriteArrayList<AccountFollowup>()
        val calls = AtomicInteger()
        @Volatile var latencyMs: LongRange = 300L..900L
        /** Thrown by the next call, once. */
        @Volatile var failNext: Throwable? = null
        /** The next call answers with no run: the account queued the message behind a turn. */
        @Volatile var queueNext = false
        /** Holds every call until released. */
        @Volatile var gate: CountDownLatch? = null
        val pending = CopyOnWriteArrayList<PendingFollowup>()

        override suspend fun addFollowup(agentId: String, followup: AccountFollowup, synchronous: Boolean): String? {
            calls.incrementAndGet()
            withContext(Dispatchers.IO) {
                gate?.await(20, TimeUnit.SECONDS)
                delay(Random.nextLong(latencyMs.first, latencyMs.last + 1))
            }
            failNext?.let { failNext = null; throw it }
            sent += followup
            if (queueNext) {
                queueNext = false
                pending += PendingFollowup(followup.followupId, followup.text)
                return null
            }
            val runId = "run-account-${calls.get()}"
            val now = "2026-09-20T23:0${(calls.get() % 10)}:00.000Z"
            api.runs[runId] = RunDto(id = runId, agentId = agentId, status = "RUNNING", createdAt = now, updatedAt = now)
            return runId
        }

        override suspend fun listPending(agentId: String): List<PendingFollowup> = pending.toList()
        override suspend fun updatePending(agentId: String, followupId: String, text: String) = Unit
        override suspend fun deletePending(agentId: String, followupId: String) = Unit
        override suspend fun reorderPending(agentId: String, followupId: String, targetFollowupId: String, insertAfter: Boolean) = Unit
        override suspend fun submitPendingNow(agentId: String, followupId: String) = Unit
        override suspend fun markEditing(agentId: String, followupId: String, editing: Boolean) = Unit
    }

    /** The presigned storage: each file's `PUT` is held on its gate and then takes [latencyMs]; the presign itself can be refused per file. */
    class SlowStorage {
        private val server = MockWebServer()
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        val puts = CopyOnWriteArrayList<String>()
        @Volatile var latencyMs: Long = 300
        @Volatile var failPresignOf: String? = null

        fun start() = server.start()
        fun shutdown() = server.shutdown()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val name = request.path.orEmpty().substringAfterLast('/')
                    puts += name
                    gates.getOrPut(name) { CountDownLatch(0) }.await(20, TimeUnit.SECONDS)
                    Thread.sleep(latencyMs)
                    return MockResponse().setResponseCode(200)
                }
            }
        }

        fun hold(name: String) { gates[name] = CountDownLatch(1) }
        fun release(name: String? = null) { if (name == null) gates.values.forEach { it.countDown() } else gates[name]?.countDown() }

        val presigns = CopyOnWriteArrayList<String>()
        val completes = CopyOnWriteArrayList<String>()
        val api: PromptUploadApi = object : PromptUploadApi {
            override suspend fun presign(filename: String, mimeType: String, contentLengthBytes: Long, teamId: Int?): PresignedPromptUpload {
                presigns += filename
                if (filename == failPresignOf) throw ConnectRpcException(503, "unavailable", "Storage is unavailable right now.")
                val url = server.url("/put/$filename").toString()
                return PresignedPromptUpload("up-$filename", "s3-$filename", listOf(PromptUploadPart(1, url, 0L, contentLengthBytes)), contentLengthBytes, null)
            }
            override suspend fun complete(uploadId: String, s3UploadId: String): PromptUploadCompletion { completes += uploadId; return PromptUploadCompletion.COMPLETED }
            override suspend fun abort(uploadId: String, s3UploadId: String) = Unit
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun file(name: String, size: Int = 2_048) = PendingFile.of(PromptFile(ByteArray(size) { 3 }, name, "application/pdf"))
    private fun image() = PendingAttachment.of(PromptImage(ByteArray(64) { 9 }, "image/png"), id = "img-${Random.nextInt()}")

    /** A composer open on the chat: its catalogue read, and Extended mode known to it — a file attached before that is refused. */
    private fun open(): ConversationViewModel = runBlocking { openIn(ViewModelStore()) }

    /** A composer in [store], as the screen holds one; cleared with the test. */
    private suspend fun openIn(store: ViewModelStore): ConversationViewModel {
        stores += store
        return ViewModelProvider(store, ConversationViewModel.Factory(graph, AGENT))[ConversationViewModel::class.java].also { it.ready() }
    }

    private suspend fun ConversationViewModel.ready() = withTimeout(10_000) {
        modelPicker.first { !it.isLoading }
        capabilities.first { it.promptFiles }
    }

    private fun pendingBubbles(): List<UserMessage> = graph.conversations.state(AGENT).value.items.filterIsInstance<UserMessage>().filter { it.isPending }

    private suspend fun <T> await(what: String, timeoutMs: Long = 15_000, condition: () -> T?): T = withTimeout(timeoutMs) {
        while (true) {
            condition()?.let { return@withTimeout it }
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable: $what")
    }

    /** The composer's chip state for [id], as the screen reads it — collected, since the flow only runs while something collects it. */
    private suspend fun awaitChip(vm: ConversationViewModel, id: String, condition: (com.cursorforandroid.ui.components.FileUploadState?) -> Boolean) {
        try {
            withTimeout(15_000) { vm.fileUploads.first { condition(it[id]) } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "chip $id never met the condition: composer files=${vm.pendingFiles.value.map { it.id }} chip=${vm.fileUploads.value[id]} " +
                    "upload=${uploads.states.value[id]} toast=${vm.toastMessage.value} capabilities=${vm.capabilities.value.promptFiles} demo=${graph.session.isDemo} presigns=${storage.presigns} puts=${storage.puts}",
                e,
            )
        }
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        await(what, timeoutMs) { if (condition()) Unit else null }
    }

    private suspend fun awaitStatus(vm: ConversationViewModel, id: String, timeoutMs: Long = 15_000, condition: (OutgoingStatus?) -> Boolean): OutgoingStatus? =
        withTimeout(timeoutMs) { vm.outgoingStatuses.first { condition(it[id]) } }[id]

    private fun ConversationViewModel.composerIsEmpty(): Boolean = draftText.value.isEmpty() && pendingAttachments.value.isEmpty() && pendingFiles.value.isEmpty()

    /**
     * The upload is held when send is tapped. The composer is empty the moment `send()` returns — no frame later,
     * nothing awaited — with the draft on disk cleared too; the message's bubble is up with its text and its file and
     * says the file is uploading; the account is not asked until the file is up; and once it is, the message is filed
     * and the bubble is no longer pending. The chip never came back to the composer.
     */
    @Test
    fun `the tap empties the composer at once, and the upload and the send finish on the bubble`() = runBlocking<Unit> {
        val vm = open()
        val spec = file("spec.pdf")
        storage.hold("spec.pdf")
        vm.addFiles(listOf(spec))
        vm.addAttachments(listOf(image()))
        vm.setDraft("Stitch the image into the green screen")
        awaitChip(vm, spec.id) { it?.isUploading == true }

        // Into a bubble at the foot of the transcript now: what it says is what the composer's text travels into.
        assertThat(vm.send()).isEqualTo("Stitch the image into the green screen")

        // Same frame: nothing has been awaited yet.
        assertThat(vm.composerIsEmpty()).isTrue()
        assertThat(graph.followUps.state(AGENT).value.draft.isEmpty).isTrue()

        val bubble = await("the bubble") { pendingBubbles().singleOrNull { it.text == "Stitch the image into the green screen" } }
        assertThat(bubble.attachments).hasSize(2)
        assertThat(bubble.attachments.count { it.isFile }).isEqualTo(1)
        val uploading = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Uploading } as OutgoingStatus.Uploading
        assertThat(uploading.total).isEqualTo(1)
        assertThat(uploading.done).isEqualTo(0)
        withTimeout(15_000) { vm.isSending.first { it } }
        // The account has not been asked: the request waits for the file, on the bubble, not in the composer.
        delay(400)
        assertThat(account.calls.get()).isEqualTo(0)
        assertThat(vm.composerIsEmpty()).isTrue()

        storage.release("spec.pdf")
        awaitStatus(vm, bubble.id) { it == null }
        withTimeout(15_000) { vm.isSending.first { !it } }
        assertThat(account.sent.single().files.single().uploadId).isEqualTo("up-spec.pdf")
        assertThat(account.sent.single().images).hasSize(1)
        awaitUntil("filed") { pendingBubbles().none { it.id == bubble.id } }
        assertThat(vm.composerIsEmpty()).isTrue()
        // The message's uploads are let go once it is out; nothing lingers under the chip's id.
        assertThat(uploads.states.value).doesNotContainKey(spec.id)
    }

    /**
     * A refused upload — the storage would not presign — shows on the bubble with the reason; the message is not
     * back in the composer and not gone. Retry puts the file up and sends; the bubble is filed.
     */
    @Test
    fun `a failed upload shows on the bubble, and Retry puts it up and sends`() = runBlocking<Unit> {
        val vm = open()
        val trace = file("trace.har")
        storage.failPresignOf = "trace.har"
        vm.addFiles(listOf(trace))
        awaitChip(vm, trace.id) { it?.failed == true }
        vm.setDraft("Find the 500")
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()

        val bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        val failed = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("trace.har")
        assertThat(account.calls.get()).isEqualTo(0)
        withTimeout(15_000) { vm.isSending.first { !it } }
        assertThat(vm.composerIsEmpty()).isTrue()
        assertThat(pendingBubbles().map { it.id }).containsExactly(bubble.id)

        storage.failPresignOf = null
        vm.retryOutgoing(bubble.id)
        awaitStatus(vm, bubble.id) { it == null }
        assertThat(account.sent.single().files.single().uploadId).isEqualTo("up-trace.har")
        awaitUntil("filed") { pendingBubbles().none { it.id == bubble.id } }
    }

    /**
     * The account refuses the send: the bubble says so, with the reason, and offers Retry and Edit. Edit hands the
     * draft back to the composer — text and chip, the chip at rest since its upload is up — and takes the bubble down.
     * Retry, on another failure, sends the same message again and it is filed.
     */
    @Test
    fun `a refused send shows on the bubble, Edit hands the draft back to the composer, Retry sends it again`() = runBlocking<Unit> {
        val vm = open()
        val spec = file("spec.pdf")
        vm.addFiles(listOf(spec))
        awaitChip(vm, spec.id) { it?.done == true }
        account.failNext = ConnectRpcException(503, "unavailable", "The account service is unavailable right now.")
        vm.setDraft("Read the spec")
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()

        var bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        var failed = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("unavailable")
        assertThat(pendingBubbles().map { it.id }).containsExactly(bubble.id)

        // Edit: the draft is the composer's again, the bubble gone.
        vm.editOutgoing(bubble.id)
        assertThat(vm.draftText.value).isEqualTo("Read the spec")
        assertThat(vm.pendingFiles.value.map { it.id }).containsExactly(spec.id)
        awaitChip(vm, spec.id) { it?.done == true }
        assertThat(vm.outgoingStatuses.value).isEmpty()
        awaitUntil("bubble down") { pendingBubbles().isEmpty() }

        // Sent again, refused again: Retry on the bubble gets it through.
        account.failNext = ConnectRpcException(503, "unavailable", "The account service is unavailable right now.")
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()
        bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        failed = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("unavailable")
        vm.retryOutgoing(bubble.id)
        awaitStatus(vm, bubble.id) { it == null }
        assertThat(account.sent.single().text).isEqualTo("Read the spec")
        assertThat(account.sent.single().files.single().uploadId).isEqualTo("up-spec.pdf")
        // One upload for the whole story: the retry sent the reference, it did not upload again.
        assertThat(storage.puts).containsExactly("spec.pdf")
        awaitUntil("filed") { pendingBubbles().isEmpty() }
    }

    /** Rate limited: the bubble carries the account's word, the composer is still empty, and Retry goes through. */
    @Test
    fun `rate limited, the bubble says so and Retry goes through`() = runBlocking<Unit> {
        val vm = open()
        vm.addFiles(listOf(file("notes.md")))
        account.failNext = ConnectRpcException(429, "resource_exhausted", "Too many requests. Try again in a moment.", retryAfterMillis = 500)
        vm.setDraft("Summarise the notes")
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()

        val bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        val failed = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("Too many requests")
        assertThat(vm.composerIsEmpty()).isTrue()

        vm.retryOutgoing(bubble.id)
        awaitStatus(vm, bubble.id) { it == null }
        assertThat(account.sent.single().text).isEqualTo("Summarise the notes")
        awaitUntil("filed") { pendingBubbles().isEmpty() }
    }

    /**
     * The first message's file is still going up when a second draft is begun and sent. The composer shows only the
     * new draft's chip, tracked under its own id — queued behind the first message's file, since uploads go one at a
     * time in the order attached — and taking it off cancels its upload alone while the first message's goes on;
     * the second message's request waits its turn behind the first, so the account sees them in the order sent.
     */
    @Test
    fun `a draft begun while the previous message is still uploading is its own, and sends behind it`() = runBlocking<Unit> {
        val vm = open()
        val big = file("big.mov", size = 8_192)
        storage.hold("big.mov")
        vm.addFiles(listOf(big))
        vm.setDraft("First, the recording")
        awaitChip(vm, big.id) { it?.isUploading == true }
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()
        val first = await("first bubble") { pendingBubbles().singleOrNull() }

        // A new draft, its own chips, its own uploads.
        val notes = file("notes.md")
        vm.addFiles(listOf(notes))
        vm.setDraft("Second")
        assertThat(vm.pendingFiles.value.map { it.id }).containsExactly(notes.id)
        // Its own upload, in the queue behind the first message's; the composer knows nothing of that one any more.
        awaitChip(vm, notes.id) { it?.isUploading == true }
        withTimeout(15_000) { vm.fileUploads.first { big.id !in it } }
        assertThat(uploads.states.value[big.id]).isInstanceOf(AttachmentUploads.Status.Uploading::class.java)
        // Taking the new chip off cancels its upload and nothing else.
        vm.removeFile(notes)
        assertThat(vm.pendingFiles.value).isEmpty()
        assertThat(uploads.states.value).doesNotContainKey(notes.id)
        assertThat(uploads.states.value[big.id]).isInstanceOf(AttachmentUploads.Status.Uploading::class.java)

        // The second message goes out while the first is still uploading — and waits its turn.
        val spec = file("spec.pdf")
        vm.addFiles(listOf(spec))
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()
        val second = await("second bubble") { pendingBubbles().singleOrNull { it.id != first.id } }
        awaitStatus(vm, second.id) { it != null }
        delay(600)
        assertThat(account.calls.get()).isEqualTo(0)
        assertThat(awaitStatus(vm, first.id) { it is OutgoingStatus.Uploading }).isInstanceOf(OutgoingStatus.Uploading::class.java)

        storage.release("big.mov")
        awaitStatus(vm, second.id) { it == null }
        assertThat(account.sent.map { it.text }).containsExactly("First, the recording", "Second").inOrder()
        assertThat(account.sent[1].files.single().uploadId).isEqualTo("up-spec.pdf")
        awaitUntil("both filed") { pendingBubbles().isEmpty() }
        assertThat(vm.composerIsEmpty()).isTrue()
    }

    /** The account's queue as the card above the composer draws it, against the transcript's own frame. */
    private fun card(): ConversationControls = graph.steering.state(AGENT).value.placed(graph.conversations.state(AGENT).value.queuePlacement)

    /** Every pending bubble shown for [text] while [block] runs, sampled on every frame of the transcript. */
    private suspend fun bubblesDuring(text: String, block: suspend () -> Unit): List<String> {
        val seen = CopyOnWriteArrayList<String>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            graph.conversations.state(AGENT).collect { s -> s.items.filterIsInstance<UserMessage>().filter { it.isPending && it.text == text }.forEach { seen += it.id } }
        }
        try { block() } finally { watcher.cancel() }
        return seen
    }

    private suspend fun openMidTurn(): ConversationViewModel {
        api.addRunningAgent(AGENT, "Green screen", "run-live")
        graph.agents.refresh()
        val vm = open()
        awaitUntil("the chat on its turn") { graph.conversations.state(AGENT).value.runStatus?.isActive == true && graph.followUps.decide(AGENT).busy }
        return vm
    }

    /**
     * The agent is mid-turn, so the message goes to the account's queue — straight onto the card at the tap, in
     * flight until the account holds it, and never as a bubble in the transcript first (Bennett, 0.4.1: the send
     * played into a bubble and a round trip later popped onto the card). Its file goes up meanwhile, on the row.
     */
    @Test
    fun `mid-turn, the message is on the card from the tap and never a bubble first`() = runBlocking<Unit> {
        val vm = openMidTurn()
        val spec = file("spec.pdf")
        vm.addFiles(listOf(spec))
        awaitChip(vm, spec.id) { it?.done == true }
        account.queueNext = true
        account.gate = CountDownLatch(1)
        vm.setDraft("When you are done, read the spec")
        val bubbles = bubblesDuring("When you are done, read the spec") {
            // Mid-turn nothing travels into a bubble.
            assertThat(vm.send()).isNull()
            assertThat(vm.composerIsEmpty()).isTrue()
            // On the card before the account has been answered, held in flight: no action on an id it does not know yet.
            val row = await("on the card") { card().queue.singleOrNull { it.text == "When you are done, read the spec" } }
            assertThat(card().inFlightQueueIds).contains(row.id)
            assertThat(row.files.map { it.name }).containsExactly("spec.pdf")
            assertThat(account.sent).isEmpty()
            account.gate!!.countDown()
            await("queued on the account") { account.sent.singleOrNull() }
            withTimeout(15_000) { vm.isSending.first { !it } }
            awaitUntil("the row's actions back") { card().inFlightQueueIds.isEmpty() }
            assertThat(card().queue.map { it.id }).containsExactly(row.id)
            assertThat(account.sent.single().followupId).isEqualTo(row.id)
            // The account's own list names it: one row, the list's.
            graph.steering.refreshQueue(AGENT)
            assertThat(card().queue.map { it.text }).containsExactly("When you are done, read the spec")
        }
        assertWithMessage("pending bubbles shown for a queued message").that(bubbles).isEmpty()
        assertThat(account.sent.single().files.single().uploadId).isEqualTo("up-spec.pdf")
    }

    /**
     * Mid-turn, and the account refuses the message: it leaves the card — which would go on promising a message the
     * account never took — for a bubble with the reason, Retry and Edit. Retry sends it from there.
     */
    @Test
    fun `mid-turn, a refused message leaves the card for a bubble with its Retry`() = runBlocking<Unit> {
        val vm = openMidTurn()
        account.failNext = ConnectRpcException(500, "internal", "Something went wrong on the account service.")
        account.gate = CountDownLatch(1)
        vm.setDraft("And the trace")
        assertThat(vm.send()).isNull()
        assertThat(vm.composerIsEmpty()).isTrue()
        await("on the card") { card().queue.singleOrNull { it.text == "And the trace" } }
        assertThat(pendingBubbles()).isEmpty()
        account.gate!!.countDown()
        account.gate = null
        val refused = await("the bubble") { pendingBubbles().singleOrNull() }
        assertThat(refused.text).isEqualTo("And the trace")
        val failed = awaitStatus(vm, refused.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("Something went wrong")
        assertThat(card().queue).isEmpty()

        account.queueNext = true
        vm.retryOutgoing(refused.id)
        awaitStatus(vm, refused.id) { it == null }
        assertThat(account.sent.map { it.text }).containsExactly("And the trace")
        awaitUntil("bubble down for the card") { pendingBubbles().isEmpty() && card().queue.any { it.text == "And the trace" } }
    }

    /**
     * The turn ends while the message is on its way: queued at the tap, but the account starts its run at once. The
     * message leaves the card for the transcript, filed under that run — never on both, never lost.
     */
    @Test
    fun `a turn that ends mid-send files the queued message under the run the account started`() = runBlocking<Unit> {
        val vm = openMidTurn()
        account.gate = CountDownLatch(1)
        vm.setDraft("Then ship it")
        assertThat(vm.send()).isNull()
        await("on the card") { card().queue.singleOrNull { it.text == "Then ship it" } }
        // The turn ends on the server and the chat sees it end, the request still out.
        api.runs["run-live"] = api.runs.getValue("run-live").copy(status = "FINISHED")
        api.v0[AGENT] = api.v0.getValue(AGENT).copy(status = "FINISHED")
        graph.conversations.reload(AGENT)
        awaitUntil("the chat sees the turn end") { graph.conversations.state(AGENT).value.runStatus?.isActive == false }
        account.gate!!.countDown()
        account.gate = null
        awaitUntil("filed under the account's run") {
            graph.conversations.state(AGENT).value.items.any { it is UserMessage && it.text == "Then ship it" && !it.isPending }
        }
        withTimeout(15_000) { vm.isSending.first { !it } }
        assertThat(card().queue.none { it.text == "Then ship it" }).isTrue()
        assertThat(graph.conversations.state(AGENT).value.activeRunId).isEqualTo("run-account-1")
    }

    /**
     * No files, no mode: the documented run request. The composer is empty at the tap; a refusal shows on the bubble
     * and Retry files it; refused as busy — the server still winding the last turn down — the message goes to the
     * account's queue rather than sitting failed.
     */
    @Test
    fun `the documented send clears the composer at the tap, shows a refusal on the bubble, and queues when busy`() = runBlocking<Unit> {
        val vm = open()
        api.failNextCreateRun = FakeCursorApi.httpError(500, "internal", "Something went wrong.")
        vm.setDraft("  Plain text  ")
        assertThat(vm.send()).isEqualTo("Plain text")
        assertThat(vm.composerIsEmpty()).isTrue()
        val bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        val failed = awaitStatus(vm, bubble.id) { it is OutgoingStatus.Failed } as OutgoingStatus.Failed
        assertThat(failed.message).contains("Something went wrong")
        assertThat(vm.composerIsEmpty()).isTrue()
        vm.retryOutgoing(bubble.id)
        awaitStatus(vm, bubble.id) { it == null }
        assertThat(api.runRequests.map { it.prompt.text }).containsExactly("Plain text", "Plain text")
        awaitUntil("filed") { pendingBubbles().isEmpty() }

        // Busy against every word here: the message moves to the account's queue, and the bubble does not stay failed.
        api.busyCreateRun = true
        account.queueNext = true
        vm.setDraft("While you are at it")
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()
        await("queued on the account") { account.sent.singleOrNull { it.text == "While you are at it" } }
        withTimeout(15_000) { vm.isSending.first { !it } }
        assertThat(vm.outgoingStatuses.value).isEmpty()
        awaitUntil("bubble down") { pendingBubbles().isEmpty() }
    }

    /**
     * The chat is left the moment after send, its file still going up. The send is the graph's, not the screen's: it
     * goes on all the same and the next visit finds the message filed. Left with a refusal instead, the next visit
     * finds the bubble with its failure — and Retry from there files it. Nothing is lost to a screen being closed.
     */
    @Test
    fun `a send outlives the chat being left, and a failure waits for the next visit with its Retry`() = runBlocking<Unit> {
        val firstVisit = ViewModelStore()
        val vm = openIn(firstVisit)
        val big = file("big.mov", size = 8_192)
        storage.hold("big.mov")
        vm.addFiles(listOf(big))
        vm.setDraft("Left the chat on this")
        awaitChip(vm, big.id) { it?.isUploading == true }
        vm.send()
        assertThat(vm.composerIsEmpty()).isTrue()
        val first = await("the bubble") { pendingBubbles().singleOrNull() }
        // The chat is left: the view model is cleared, its scope with it.
        firstVisit.clear()
        storage.release("big.mov")
        await("sent after the chat was left") { account.sent.singleOrNull { it.text == "Left the chat on this" } }
        awaitUntil("filed") { pendingBubbles().none { it.id == first.id } }

        // A refusal that lands after the chat is left waits on the bubble for the next visit.
        val secondVisit = ViewModelStore()
        val vm2 = openIn(secondVisit)
        account.gate = CountDownLatch(1)
        account.failNext = ConnectRpcException(503, "unavailable", "The account service is unavailable right now.")
        vm2.addFiles(listOf(file("spec.pdf")))
        vm2.setDraft("And the spec")
        vm2.send()
        // A bubble, or — the chat still showing the first message's run under way — the queue's card row.
        await("the message shown") { pendingBubbles().singleOrNull() ?: card().queue.singleOrNull() }
        secondVisit.clear()
        account.gate!!.countDown()
        account.gate = null
        // Either way the refusal lands on a bubble.
        val second = await("the bubble") { pendingBubbles().singleOrNull() }
        val sends = graph.outgoing.forAgent(AGENT)
        await("failed after the chat was left") { (sends.statuses.value[second.id] as? OutgoingStatus.Failed) }

        val vm3 = openIn(ViewModelStore())
        assertThat(vm3.outgoingStatuses.value[second.id]).isInstanceOf(OutgoingStatus.Failed::class.java)
        assertThat(pendingBubbles().map { it.id }).containsExactly(second.id)
        vm3.retryOutgoing(second.id)
        awaitStatus(vm3, second.id) { it == null }
        assertThat(account.sent.map { it.text }).containsExactly("Left the chat on this", "And the spec").inOrder()
        awaitUntil("filed") { pendingBubbles().isEmpty() }
    }

    /**
     * Signed out with a message's file still going up: the upload and the send stop with the account — nothing of it
     * reaches the account service after — and the composer keeps nothing of the message's chips or status.
     */
    @Test
    fun `a sign-out stops a send under way, upload and all`() = runBlocking<Unit> {
        val vm = open()
        val big = file("big.mov", size = 8_192)
        storage.hold("big.mov")
        vm.addFiles(listOf(big))
        vm.setDraft("Gone with the account")
        awaitChip(vm, big.id) { it?.isUploading == true }
        vm.send()
        val bubble = await("the bubble") { pendingBubbles().singleOrNull() }
        awaitStatus(vm, bubble.id) { it is OutgoingStatus.Uploading }

        graph.session.signOut()
        storage.release("big.mov")

        withTimeout(15_000) { vm.isSending.first { !it } }
        assertThat(vm.outgoingStatuses.value).isEmpty()
        assertThat(uploads.states.value).isEmpty()
        // Watched for a while: the account is never asked for the message of an account that is gone.
        delay(1_500)
        assertThat(account.calls.get()).isEqualTo(0)
    }

    private companion object {
        const val AGENT = "bc-attach"
    }
}
