package com.cursorforandroid.data.faults

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.BlobCache
import com.cursorforandroid.data.local.BlobDiskStore
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectApi
import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.ServerRetry
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.api.SteeringApi
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.PinRepository
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SteeringRepository
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.PendingWork
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.FollowUpComposerState
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The app's own stack — `CursorApiFactory.okHttp` with its retry interceptor, Retrofit, the SSE streamer, the
 * agent list, the hub, the transcript and the follow-up queue — pointed at a [FaultServer] (or at [baseUrl], for a
 * host that does not resolve). Only the timeouts differ from production: a read timeout of a few seconds rather
 * than sixty, so a test of silence takes seconds rather than minutes; the shape of what the client sees is the same.
 *
 * The clock is the app's own ([AppClock]), frozen at [now] and moved by the test; the hub and the queue take it.
 */
class FaultRig(
    baseUrl: String,
    root: File,
    /** The read timeout, and the write timeout with it: how long silence takes to become a failure here. */
    readTimeoutMs: Long = 3_000L,
    connectTimeoutMs: Long = 2_000L,
    /** How long the follow-up queue waits on a busy row before it settles the row against the record (production: 10 s). */
    idleSettleMs: Long = 4_000L,
    /** The first pause before a refused or failed send is tried again; doubles each time (production: 1 s). */
    retryBaseMs: Long = 300L,
    /** Reconnects a stream makes on its own before handing the failure to the hub (production: 4, at 1 + 2 + 4 + 8 s). */
    streamAttempts: Int = 2,
    /**
     * Extended mode: the account's record of a chat (`FetchBackgroundComposer`, served by the same [FaultServer] under
     * its Connect routes) is the transcript's source, as on Bennett's phone; off, the documented endpoints alone.
     */
    extended: Boolean = false,
    /**
     * The transcript engine in Extended mode (see `TranscriptEngine`): Beta reads the record, as every test written
     * before the setting existed expects; Stable is the app's default, the documented endpoints alone.
     */
    engine: TranscriptEngine = TranscriptEngine.BETA,
    /** How often the account's queue is read while a chat is attached (production: 10 s): the card's staleness. */
    queuePollMs: Long = 10_000L,
    /** The record's waits between attempts of a read the server failed, and between its passes (production's by default; see `ServerRetry.Waits`). */
    recordWaits: ServerRetry.Waits = ServerRetry.Waits(),
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var now: Long = 1_800_000_000_000L
    val context: Context = ApplicationProvider.getApplicationContext()
    private val key = { "fault-key" }

    val client: OkHttpClient = CursorApiFactory.okHttp(key).newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // The host answers at two addresses, as api.cursor.com does: a request that fails on one has another route
        // to try, which is when OkHttp's own retry of a request comes into play (see OneShotWritesInterceptor).
        .dns(object : Dns { override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).let { it + it } })
        .build()
    val api: CursorApi = CursorApiFactory.retrofit(client, baseUrl)
    val streamer = SseRunStreamer(
        CursorApiFactory.sseClient(client).newBuilder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build(),
        key,
        urlFor = { agentId, runId -> "${baseUrl}v1/agents/$agentId/runs/$runId/stream" },
        maxAttempts = streamAttempts,
    )
    val prefs = PreferencesStore(context)
    val backend = CursorBackend(api, streamer, isDemo = false)
    val session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
    private val disk = JsonDiskCache(File(root, "cache").apply { mkdirs() }, dispatcher = Dispatchers.Unconfined)
    val attachments = AttachmentStore(context)
    /** The list's work in flight, shared by the list and the account layer (see `PendingWork`): what the sidebar's one loading row stands for. */
    val pending = PendingWork()
    val agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10, capabilities = { capabilities }, recordOf = { id -> if (this.capabilities.accountSession) accountAgents.record(id) else null }, pending = pending)
    val hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 500, releaseGraceMs = 200, reconnectBaseMs = 200, reconnectMaxMs = 800, scope = scope)
    val conversationCache = ConversationCache(disk.child("conversations"))
    val traces = TraceCache(JsonDiskCache(File(root, "traces").apply { mkdirs() }, nowProvider = { now }, dispatcher = Dispatchers.Unconfined))
    /**
     * The account service's client, as the app builds it (`CursorApiFactory.loginClient`: no retry interceptor, no
     * one-shot writes — its RPCs are reads and idempotent writes over one host), on this rig's timeouts and DNS.
     */
    val accountClient: OkHttpClient = CursorApiFactory.loginClient().newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .dns(object : Dns { override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).let { it + it } })
        .build()
        // As `AppGraph` widens the record's client: the blobs are read several at a time.
        .also { it.dispatcher.maxRequestsPerHost = maxOf(it.dispatcher.maxRequestsPerHost, HeadlessConversationApi.BLOB_PARALLELISM + 2) }
    private val accountRpc = ConnectJsonClient(accountClient, baseUrl)
    private val sessionTokens = SessionTokenProvider(accountClient, key, apiUrl = baseUrl, now = { now })
    /** The account's list, pins and records (`BackgroundComposerService`), over [accountClient] on the same host. */
    val accountAgents: BackgroundComposerApi = BackgroundComposerApi(accountRpc, sessionTokens)
    /** The Projects' memberships, over the same host. */
    val projectApi: ProjectApi = ProjectApi(accountRpc, sessionTokens)
    /**
     * The sidebar's account layer, wired as `AppGraph` wires it: the account round after every fetch, the account's
     * list read ahead of each fetch's first page and alongside each page the reader asks for, the root discovery and
     * the memberships off every list read. Short retry delays, so a test of a round that fails sees the next.
     */
    /** The lineage API as the graph wires it: the memberships from [projectApi], the records and the discovery scan from [accountAgents]. */
    private val lineageApi = object : ProjectLineageApi by projectApi {
        override suspend fun record(id: String): ComposerSnapshot? = accountAgents.record(id)
        override suspend fun scanRoots(maxPages: Int): RootScan = accountAgents.scanRoots(maxPages)
        override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan = accountAgents.scanRoots(maxPages, stopBelowActivityMillis)
    }
    val projects: ProjectRepository = ProjectRepository(session, agents, lineageApi, actions = projectApi, store = projectApi, scope = scope, pollIntervalMs = 60_000, capabilities = { capabilities }, retryDelaysMs = listOf(500L, 500L, 500L)).also { it.watchList() }
    val pins: PinRepository = PinRepository(
        session, prefs, agents, accountAgents, scope = scope, capabilities = { capabilities }, retryDelaysMs = listOf(500L, 1_000L, 2_000L), pending = pending,
        onList = { list, token ->
            agents.applySources(list.sources, token)
            projects.scheduleRootDiscovery(list.composers.filter { it.scope == AgentScope.PROJECT_ROOT }.map { it.id }, deep = agents.lastRefreshDepth == RefreshDepth.Deep)
        },
    ).also { pins ->
        agents.accountPrime = { pins.primeForFetch() }
        agents.accountPage = { pins.loadMore() }
    }
    /**
     * The record's blobs as the app keeps them: a small memory tier in front of a disk store under [root], so a rig
     * made again on the same [root] is a new process on the same phone (see `AppGraph`).
     */
    val blobs = BlobCache(BlobCache.MEMORY_BLOBS_WITH_DISK, BlobCache.MEMORY_BYTES_WITH_DISK, disk = BlobDiskStore(JsonDiskCache(File(root, "blobs"), dispatcher = Dispatchers.IO)))
    /** The account service's record of a chat, over [accountClient] on the same host (Extended mode); null with the mode off. */
    val record: ConversationRecordApi? = if (extended) HeadlessConversationApi(accountRpc, sessionTokens, blobs = blobs, waits = recordWaits) else null
    /** What the private surfaces may do; a test that switches the engine mid-run sets this, and the next load reads it (as the app's `ExtendedMode` would). */
    @Volatile var capabilities: Capabilities = Capabilities.of(extended, engine)
    val conversations = ConversationRepository(session, agents, prefs, hub, attachments, conversationCache, traces, isForeground = { true }, prefetchLimit = 0, scope = scope, record = record, capabilities = { capabilities }, retryPassDelaysMs = recordWaits.passes, composerStatus = { id -> accountAgents.status(id) })
    /** The account's controls on a chat — its queue above all — over the same host, wired as the app wires them (see AppGraph). */
    val steeringApi = SteeringApi(accountRpc, sessionTokens, blobs = blobs)
    val steering = SteeringRepository(
        session, agents,
        interactions = steeringApi, queueApi = steeringApi, runs = steeringApi, goals = steeringApi,
        afterAction = { agentId -> conversations.revalidate(agentId) },
        onQueueRead = { agentId, pending, readAt -> conversations.noteAccountQueue(agentId, pending, readAt) },
        onQueuedDeleted = { agentId, followupId -> conversations.queuedDeleted(agentId, followupId) },
        onQueuedEdited = { agentId, followupId, text -> conversations.queuedEdited(agentId, followupId, text) },
        placement = { agentId -> conversations.queuePlacement(agentId) },
        scope = scope,
        pollIntervalMs = queuePollMs,
        capabilities = { capabilities },
    )
    val followUpStore = FollowUpStore(context)
    val followUps = FollowUpRepository(
        conversations, agents, hub,
        mcpServers = { emptyList() },
        // A message the server refuses as busy goes to the account's queue in Extended mode, as in the app (see AppGraph).
        accountQueue = { agentId, item ->
            val followup = AccountFollowup(text = item.previewText, images = item.images.map { it.image })
            FollowUpRepository.AccountHandoff(steering.sendFollowup(agentId, followup).getOrThrow(), followup.followupId)
        },
        accountQueueAvailable = { extended },
        store = followUpStore,
        persist = { true },
        scope = scope,
        draftSaveDelayMs = 10,
        idleSettleMs = idleSettleMs,
        retryBaseMs = retryBaseMs,
    )

    init {
        AppClock.nowMillis = { now }
    }

    /** Polls [condition] every 25 ms until it holds or [timeoutMs] is up — a wait on an outcome, never a sleep standing in for one. */
    suspend fun awaitUntil(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(25)
    }

    /**
     * Holds [invariant] for [windowMs], checked every 100 ms: the one place a wait is a duration rather than an
     * outcome, and only ever for asserting that something does *not* happen within a bound.
     */
    suspend fun watch(windowMs: Long, invariant: () -> Unit) {
        val until = System.nanoTime() + windowMs * 1_000_000
        while (System.nanoTime() < until) {
            invariant()
            delay(100)
        }
        invariant()
    }

    /** Every value the queue's state took for [agentId] from now on, in order: what a card would have shown. */
    fun recordQueue(agentId: String): QueueRecording = QueueRecording(followUps.state(agentId), scope)

    override fun close() {
        scope.cancel()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        accountClient.dispatcher.executorService.shutdownNow()
        accountClient.connectionPool.evictAll()
        AppClock.nowMillis = System::currentTimeMillis
    }
}

/**
 * The follow-up card's states over time, per message: the visible ones — queued, sending, held (waiting on a
 * busy agent), failed (a reason on the card), gone (sent or removed) — as a reader would see them one after
 * another. [assertNoFlap] is the invariant Bennett's 0.3.33 screenshot broke: once a message has read "sending",
 * it never reads plainly "queued" again; it is sent, failed with a reason, or held with one.
 */
class QueueRecording(flow: StateFlow<FollowUpComposerState>, scope: CoroutineScope) {
    enum class Card { QUEUED, SENDING, HELD, FAILED, CONFIRM, STEERED }

    private val frames = CopyOnWriteArrayList<Map<String, Card>>()
    // Subscribed before the caller's next line runs (undispatched): the first frame recorded is the queue as it stands,
    // not as the dispatcher has moved it a few milliseconds later under a loaded test run.
    private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { state -> frames += state.queue.associate { it.id to it.card() } } }

    private fun QueuedFollowUp.card(): Card = when {
        error != null -> Card.FAILED
        needsConfirmation -> Card.CONFIRM
        isSteered -> Card.STEERED
        // As the card reads it: a held message in flight still reads held, never "sending" (see QueuedFollowUps).
        isHeld -> Card.HELD
        isSending -> Card.SENDING
        else -> Card.QUEUED
    }

    /** The distinct successive states one message went through, gone states dropped. */
    fun history(id: String): List<Card> {
        val out = ArrayList<Card>()
        frames.forEach { frame -> frame[id]?.let { if (out.lastOrNull() != it) out += it } }
        return out
    }

    fun stop() = job.cancel()

    /**
     * The card read [expected] in that order, as far as a reader could tell. The recorder collects the same
     * conflated [StateFlow] a screen does, so two frames published within one beat reach it as the second alone
     * (a fork-starved CI run caught "sending" without the "queued" before it): what it saw is [expected] with
     * frames dropped — nothing [expected] does not name, nothing out of its order — and every state the card
     * rests in until something outside moves it (held for its pause, failed for the user's retry, awaiting the
     * user's word) was seen, those being the ones a reader has the time to see.
     */
    fun assertHistory(id: String, vararg expected: Card) {
        val seen = history(id)
        if (seen.isEmpty()) throw AssertionError("nothing of the card was seen; expected ${expected.toList()}")
        var next = 0
        for (card in seen) {
            val at = (next until expected.size).firstOrNull { expected[it] == card }
                ?: throw AssertionError("the card read $card, which ${expected.toList()} does not have after position $next: saw $seen")
            next = at + 1
        }
        val resting = expected.filter { it == Card.HELD || it == Card.FAILED || it == Card.CONFIRM }
        val missed = resting.filterNot { it in seen }
        if (missed.isNotEmpty()) throw AssertionError("states the card rests in were never seen: $missed; expected ${expected.toList()}, saw $seen")
    }

    /**
     * No message reads plainly "queued" after it has read "sending": a refusal parks it as held or failed, never
     * back at the start of the line as if nothing had happened. The one way back to "queued" is the user's retry of
     * a failed card, which clears its reason a moment before the dispatcher claims it again. And no message reads
     * "sending" more than [maxSendingEpisodes] times: every attempt is a visible episode, and the attempts are
     * bounded by the backoff.
     */
    fun assertNoFlap(id: String, maxSendingEpisodes: Int = 8) {
        val history = history(id)
        history.zipWithNext().forEachIndexed { i, (from, to) ->
            val sentBefore = history.take(i + 1).contains(Card.SENDING)
            check(!(to == Card.QUEUED && sentBefore && from != Card.FAILED)) { "the card went back to plain 'queued' after 'sending': $history" }
        }
        val episodes = history.count { it == Card.SENDING }
        check(episodes <= maxSendingEpisodes) { "$episodes sending episodes, more than the backoff allows: $history" }
    }
}
