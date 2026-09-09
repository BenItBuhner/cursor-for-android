package com.cursorforandroid

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.data.api.AccountApi
import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.GitHubApiFactory
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.auth.CursorLogin
import com.cursorforandroid.data.auth.CursorLoginEndpoints
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoPullRequests
import com.cursorforandroid.data.local.AppCaches
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.McpServerStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.data.repo.ChatLauncher
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.CursorPullRequestSource
import com.cursorforandroid.data.repo.GitHubPullRequestSource
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.PinRepository
import com.cursorforandroid.data.repo.PullRequestRepository
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.data.update.UpdateCache
import com.cursorforandroid.data.update.UpdateManager
import com.cursorforandroid.ui.conversation.AttachmentImages
import com.cursorforandroid.update.AndroidUpdatePlatform
import com.cursorforandroid.update.allocatableBytes
import java.io.File

/**
 * Hand-rolled dependency graph. Small enough that a DI framework would only add build time.
 *
 * Constructing it is what `Application.onCreate` does, so it builds only what the first frame needs: the
 * preferences, the key store, the cache layout and the session. Everything past that is deferred, because
 * together it is far more than a launch can afford on the main thread — five OkHttp clients and their Retrofit
 * services, the demo dataset, Coil's image loader — and none of it is on the way to the first frame. Each
 * deferred part is held as a [Lazy] rather than a `by lazy` property so that [onSignedOut] can tell the ones this
 * process actually built from the ones it never touched.
 */
class AppGraph(
    context: Context,
    /** Injectable for tests only: Robolectric has no Android Keystore, so the real one reports itself unavailable. */
    val keyStore: SecureKeyStore = SecureKeyStore(context),
    /**
     * Injectable for tests only: a stand-in for the demo backend, so a view model can be driven from a scripted API
     * (a refused delete, a list endpoint that fails) without any of the account's real network.
     */
    demo: CursorBackend? = null,
) {
    private val app = context.applicationContext

    val prefs = PreferencesStore(context)
    /**
     * Disk copies of what the API last returned; the app opens on them and revalidates in the background. Eager
     * because it is only file paths until something reads or writes, and the sign-out wipe goes through it.
     */
    val caches = AppCaches(JsonDiskCache(File(app.cacheDir, "cursor")))
    /** MCP servers defined in the app; enabled ones are sent inline with every prompt. */
    val mcpServers = McpServerStore(keyStore)

    /** Images attached to prompts, kept on-device because the transcript API never returns them. */
    private val lazyAttachments = lazy { AttachmentStore(app) }
    val attachments: AttachmentStore get() = lazyAttachments.value

    /** The New Chat composer's unsent draft, so a process death does not lose what was typed. */
    private val lazyDrafts = lazy { DraftStore(app) }
    val drafts: DraftStore get() = lazyDrafts.value

    /** The account's API: one client, with the SSE stream sharing its dispatcher and connection pool. */
    private val realParts = lazy {
        val client = CursorApiFactory.okHttp { keyStore.apiKey() }
        CursorApiFactory.retrofit(client) to SseRunStreamer(CursorApiFactory.sseClient(client), { keyStore.apiKey() })
    }
    private val realBackend = CursorBackend(isDemo = false, parts = realParts)
    /** Seeded when the demo is entered, so a launch into a real account never pays for the dataset. */
    private val demoParts = lazy { DemoBackendFactory.create() }
    private val demoBackend = demo ?: CursorBackend(isDemo = true, parts = demoParts)

    /** For api2 (the account's login and its Connect RPCs): no API-key interceptor, so only what each call sets goes out. */
    private val lazyAccountClient = lazy { CursorApiFactory.loginClient() }
    private val lazyAccountRpc = lazy { ConnectJsonClient(lazyAccountClient.value, CursorLoginEndpoints.API_URL) }
    /** The account session the account-level RPCs take, derived from the stored key when needed and kept in memory only. */
    private val lazySessionTokens = lazy { SessionTokenProvider(lazyAccountClient.value, { keyStore.apiKey() }) }
    /** The account's agent list, pins and pull request statuses: what the desktop Agents window and the iOS app show. */
    private val lazyAccountAgents = lazy { BackgroundComposerApi(lazyAccountRpc.value, lazySessionTokens.value) }
    private val lazyAccountPullRequests = lazy { CursorPullRequestSource(lazyAccountAgents.value) }

    val session = SessionManager(
        keyStore,
        prefs,
        realBackend,
        demoBackend,
        browserLogin = lazy { CursorLogin(lazyAccountClient.value) },
        // What the key is called on cursor.com/dashboard/api, so the user can tell this phone's key from others.
        mintedKeyName = "Cursor for Android (${Build.MODEL.ifBlank { "Android" }})",
        profile = lazy { AccountApi(lazyAccountRpc.value, lazySessionTokens.value) },
    )

    private val lazyAgents = lazy { AgentRepository(session, prefs, attachments, caches.agents) }
    val agents: AgentRepository get() = lazyAgents.value

    /**
     * Where the agents' pull requests stand: the account's word first (the public API names a PR but never says if it
     * is open, merged or closed), GitHub when the account has none.
     */
    private val lazyPullRequests = lazy {
        PullRequestRepository(
            gitHub = GitHubPullRequestSource(GitHubApiFactory.retrofit(GitHubApiFactory.okHttp { keyStore.gitHubToken() })),
            demo = DemoPullRequests,
            isDemo = { session.isDemo },
            readToken = { keyStore.gitHubToken() },
            writeToken = { keyStore.setGitHubToken(it) },
            cache = caches.pullRequests,
            account = lazyAccountPullRequests.value,
        )
    }
    val pullRequests: PullRequestRepository get() = lazyPullRequests.value

    /** Pins shared with the desktop Agents window and the iOS app through the account; its list read also carries the PR states. */
    private val lazyPins = lazy {
        PinRepository(
            session = session,
            prefs = prefs,
            agents = agents,
            api = lazyAccountAgents.value,
            onList = { list -> pullRequests.seed(list.pullRequests) },
        )
    }
    val pins: PinRepository get() = lazyPins.value

    private val lazyCatalog = lazy { CatalogRepository(session, caches.catalog) }
    val catalog: CatalogRepository get() = lazyCatalog.value

    /** One shared live stream per run, consumed by both the conversation screen and the live notification. */
    private val lazyLiveRuns = lazy { LiveRunHub(session, agents) }
    val liveRuns: LiveRunHub get() = lazyLiveRuns.value

    private val lazyConversations = lazy {
        ConversationRepository(
            session = session,
            agents = agents,
            prefs = prefs,
            hub = liveRuns,
            attachments = attachments,
            cache = caches.conversations,
            traceCache = caches.traces,
            isForeground = { runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(true) },
        )
    }
    val conversations: ConversationRepository get() = lazyConversations.value

    /** Sees new chats' launches through once the composer has handed them over, so no screen has to stay for the answer. */
    private val lazyLauncher = lazy { ChatLauncher(conversations) }
    val launcher: ChatLauncher get() = lazyLauncher.value

    /** Presigned URLs for `/opt/cursor/artifacts/…` references in replies, and the loader that draws them. */
    private val lazyArtifacts = lazy { ArtifactRepository(session) }
    val artifacts: ArtifactRepository get() = lazyArtifacts.value

    private val lazyMedia = lazy { MediaLoader(app, CursorApiFactory.mediaClient(), artifacts) }
    val media: MediaLoader get() = lazyMedia.value

    private val lazyRunMonitor = lazy {
        RunMonitor(
            agents = agents,
            hub = liveRuns,
            runRecord = { agentId, runId -> session.current.api.getRun(agentId, runId) },
        )
    }
    val runMonitor: RunMonitor get() = lazyRunMonitor.value

    /**
     * In-app updates from the GitHub releases of [BuildConfig.GITHUB_REPO]. Device-level, not account-level: its
     * cache and downloads sit next to (not inside) [caches], so signing out leaves them alone.
     */
    private val lazyUpdates = lazy {
        UpdateManager(
            client = GitHubReleasesClient(
                CursorApiFactory.updateClient(),
                BuildConfig.GITHUB_REPO,
                apiBaseUrl = BuildConfig.UPDATE_API_BASE_URL,
                freeSpace = { allocatableBytes(app, it) },
            ),
            prefs = prefs,
            cache = UpdateCache(JsonDiskCache(File(app.cacheDir, "update-check"))),
            platform = AndroidUpdatePlatform(app),
            downloadDir = File(app.cacheDir, "updates"),
            // The background service streaming a run is the one thing a silent self-update would cut off; a monitor
            // this process never built is holding no stream, and asking is not worth building one.
            agentsRunning = { lazyRunMonitor.isInitialized() && runMonitor.isRunning },
        )
    }
    val updates: UpdateManager get() = lazyUpdates.value

    init {
        // Whether the user signs out or the key is rejected, nothing of the account stays on disk.
        session.onSignedOut = {
            // Cancelling a write does not stop it: the caches are closed first so nothing this account still has in
            // flight can land after the wipe below re-creates the directories it deleted.
            caches.invalidate()
            AttachmentImages.clear()
            // Resetting is only ever about what is in memory, so a part this process never built has nothing to
            // reset and is left unbuilt. The wipes further down are the opposite case: what an earlier process
            // wrote is on disk whether or not this one ever looked at it, so those are forced.
            if (lazyRunMonitor.isInitialized()) runMonitor.stop()
            if (lazyLiveRuns.isInitialized()) liveRuns.resetAll()
            if (lazyConversations.isInitialized()) conversations.resetAll()
            if (lazyPins.isInitialized()) pins.reset()
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazySessionTokens.isInitialized()) lazySessionTokens.value.clear()
            // Signing out of one real account and into another keeps the same backend, so the list must be
            // reset explicitly or the previous account's agents would show.
            if (lazyAgents.isInitialized()) agents.reset()
            if (lazyCatalog.isInitialized()) catalog.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            if (lazyArtifacts.isInitialized()) artifacts.resetAll()
            media.clearCaches()
            attachments.clear()
            drafts.clear()
            caches.clear()
        }
    }

    /**
     * The deferred parts of the graph by name, for tests: a launch that builds the demo dataset or an HTTP client
     * is the cold start this arrangement exists to prevent, and a sign-out that skips a disk wipe because nothing
     * had touched the store is the way it could go wrong.
     */
    internal val deferredParts: Map<String, Lazy<*>>
        get() = mapOf(
            "attachments" to lazyAttachments,
            "drafts" to lazyDrafts,
            "realBackend" to realParts,
            "demoBackend" to demoParts,
            "accountClient" to lazyAccountClient,
            "accountRpc" to lazyAccountRpc,
            "sessionTokens" to lazySessionTokens,
            "accountAgents" to lazyAccountAgents,
            "accountPullRequests" to lazyAccountPullRequests,
            "agents" to lazyAgents,
            "pullRequests" to lazyPullRequests,
            "pins" to lazyPins,
            "catalog" to lazyCatalog,
            "liveRuns" to lazyLiveRuns,
            "conversations" to lazyConversations,
            "launcher" to lazyLauncher,
            "artifacts" to lazyArtifacts,
            "media" to lazyMedia,
            "runMonitor" to lazyRunMonitor,
            "updates" to lazyUpdates,
        )

    /** Which of [deferredParts] this process has actually built. */
    internal fun builtParts(): Set<String> = deferredParts.filterValues { it.isInitialized() }.keys

    suspend fun signOut() = session.signOut()
}
