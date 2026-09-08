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
import com.cursorforandroid.update.AndroidUpdatePlatform
import java.io.File

/** Hand-rolled dependency graph. Small enough that a DI framework would only add build time. */
class AppGraph(context: Context) {
    val keyStore = SecureKeyStore(context)
    val prefs = PreferencesStore(context)
    /** Disk copies of what the API last returned; the app opens on them and revalidates in the background. */
    val caches = AppCaches(JsonDiskCache(File(context.applicationContext.cacheDir, "cursor")))
    /** Images attached to prompts, kept on-device because the transcript API never returns them. */
    val attachments = AttachmentStore(context)
    /** MCP servers defined in the app; enabled ones are sent inline with every prompt. */
    val mcpServers = McpServerStore(keyStore)

    private val okHttp = CursorApiFactory.okHttp { keyStore.apiKey() }
    private val realBackend = CursorBackend(
        api = CursorApiFactory.retrofit(okHttp),
        streamer = SseRunStreamer(CursorApiFactory.sseClient(okHttp), { keyStore.apiKey() }),
        isDemo = false,
    )
    private val demoBackend = DemoBackendFactory.create().let { (api, streamer) -> CursorBackend(api, streamer, isDemo = true) }
    /** For api2 (the account's login and its Connect RPCs): no API-key interceptor, so only what each call sets goes out. */
    private val accountClient = CursorApiFactory.loginClient()
    private val accountRpc = ConnectJsonClient(accountClient, CursorLoginEndpoints.API_URL)
    /** The account session the account-level RPCs take, derived from the stored key when needed and kept in memory only. */
    val sessionTokens = SessionTokenProvider(accountClient, { keyStore.apiKey() })

    val session = SessionManager(
        keyStore,
        prefs,
        realBackend,
        demoBackend,
        browserLogin = CursorLogin(accountClient),
        // What the key is called on cursor.com/dashboard/api, so the user can tell this phone's key from others.
        mintedKeyName = "Cursor for Android (${Build.MODEL.ifBlank { "Android" }})",
        profile = AccountApi(accountRpc, sessionTokens),
    )
    val agents = AgentRepository(session, prefs, attachments, caches.agents)
    /** The account's agent list, pins and pull request statuses: what the desktop Agents window and the iOS app show. */
    private val accountAgents = BackgroundComposerApi(accountRpc, sessionTokens)
    private val accountPullRequests = CursorPullRequestSource(accountAgents)
    /**
     * Where the agents' pull requests stand: the account's word first (the public API names a PR but never says if it
     * is open, merged or closed), GitHub when the account has none.
     */
    val pullRequests = PullRequestRepository(
        gitHub = GitHubPullRequestSource(GitHubApiFactory.retrofit(GitHubApiFactory.okHttp { keyStore.gitHubToken() })),
        demo = DemoPullRequests,
        isDemo = { session.isDemo },
        readToken = { keyStore.gitHubToken() },
        writeToken = { keyStore.setGitHubToken(it) },
        cache = caches.pullRequests,
        account = accountPullRequests,
    )
    /** Pins shared with the desktop Agents window and the iOS app through the account; its list read also carries the PR states. */
    val pins = PinRepository(
        session = session,
        prefs = prefs,
        agents = agents,
        api = accountAgents,
        onList = { list -> pullRequests.seed(list.pullRequests) },
    )
    val catalog = CatalogRepository(session, caches.catalog)
    /** One shared live stream per run, consumed by both the conversation screen and the live notification. */
    val liveRuns = LiveRunHub(session, agents)
    val conversations = ConversationRepository(
        session = session,
        agents = agents,
        prefs = prefs,
        hub = liveRuns,
        attachments = attachments,
        cache = caches.conversations,
        traceCache = caches.traces,
        isForeground = { runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(true) },
    )
    /** Sees new chats' launches through once the composer has handed them over, so no screen has to stay for the answer. */
    val launcher = ChatLauncher(conversations)
    /** Presigned URLs for `/opt/cursor/artifacts/…` references in replies, and the loader that draws them. */
    val artifacts = ArtifactRepository(session)
    val media = MediaLoader(context, CursorApiFactory.mediaClient(), artifacts)
    val runMonitor = RunMonitor(
        agents = agents,
        hub = liveRuns,
        runRecord = { agentId, runId -> session.current.api.getRun(agentId, runId) },
    )
    /**
     * In-app updates from the GitHub releases of [BuildConfig.GITHUB_REPO]. Device-level, not account-level: its
     * cache and downloads sit next to (not inside) [caches], so signing out leaves them alone.
     */
    val updates = UpdateManager(
        client = GitHubReleasesClient(CursorApiFactory.updateClient(), BuildConfig.GITHUB_REPO, apiBaseUrl = BuildConfig.UPDATE_API_BASE_URL),
        prefs = prefs,
        cache = UpdateCache(JsonDiskCache(File(context.applicationContext.cacheDir, "update-check"))),
        platform = AndroidUpdatePlatform(context),
        downloadDir = File(context.applicationContext.cacheDir, "updates"),
        // The background service streaming a run is the one thing a silent self-update would cut off.
        agentsRunning = { runMonitor.isRunning },
    )

    init {
        // Whether the user signs out or the key is rejected, nothing of the account stays on disk.
        session.onSignedOut = {
            runMonitor.stop()
            liveRuns.resetAll()
            conversations.resetAll()
            pins.reset()
            accountPullRequests.reset()
            sessionTokens.clear()
            // Signing out of one real account and into another keeps the same backend, so the list must be
            // reset explicitly or the previous account's agents would show.
            agents.reset()
            catalog.reset()
            pullRequests.reset()
            artifacts.resetAll()
            media.clearCaches()
            attachments.clear()
            caches.clear()
        }
    }

    suspend fun signOut() = session.signOut()
}
