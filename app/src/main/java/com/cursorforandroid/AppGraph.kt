package com.cursorforandroid

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.crash.CrashReporting
import com.cursorforandroid.data.api.AccountApi
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.DashboardSlashCommandApi
import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.GitHubSlashCommandApi
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.auth.CursorLogin
import com.cursorforandroid.data.auth.CursorLoginEndpoints
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoPullRequests
import com.cursorforandroid.data.local.AppCaches
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.GeneratedMediaStore
import com.cursorforandroid.data.local.McpServerStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.data.repo.CapabilityGatedPullRequestSource
import com.cursorforandroid.data.repo.ChatLauncher
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.CursorPullRequestSource
import com.cursorforandroid.data.repo.ExtendedMode
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.data.repo.GeneratedImageStore
import com.cursorforandroid.data.repo.GitHubPullRequestSource
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.PinRepository
import com.cursorforandroid.data.repo.PullRequestRepository
import com.cursorforandroid.data.repo.PullRequestSource
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.share.ShareInbox
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.data.update.UpdateCache
import com.cursorforandroid.data.update.UpdateManager
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.ui.conversation.AttachmentImages
import com.cursorforandroid.update.AndroidUpdatePlatform
import com.cursorforandroid.update.allocatableBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    /** Opt-in crash reports; inert until the setting is on, and in a build with no DSN. Eager: it is only two strings. */
    val crashReporting = CrashReporting(app)
    /**
     * Disk copies of what the API last returned; the app opens on them and revalidates in the background. Eager
     * because it is only file paths until something reads or writes, and the sign-out wipe goes through it.
     */
    val caches = AppCaches(JsonDiskCache(File(app.cacheDir, "cursor")))
    /** Follow-ups typed or queued but not yet sent, with their images, per chat. Eager for the same reason. */
    private val followUpStore = FollowUpStore(app)
    /**
     * Text and images arriving from the system share sheet, drafted into a composer once a destination is picked.
     * Eager because every launch offers the activity's intent to it, so deferring it would only defer it by a frame.
     */
    val share = ShareInbox(app)
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

    /**
     * Whether the private `api2` surfaces below — the account session, `GetMe`, the account's pins, archive and rename,
     * its slash-command lists and its pull request states — may be used at all. Off by default: then the app speaks
     * only the documented API, and GitHub's for what a GitHub-hosted repository can say about itself. Every part
     * that could reach `api2` reads [Capabilities] from here before it does, and the session provider refuses to
     * hand out a token while the mode is off, whatever a caller forgot to check. Cheap: it holds the preferences.
     */
    val extendedMode = ExtendedMode(
        prefs,
        // Whether this install was signed in before the setting existed (see ExtendedMode.migrateInstall): an account
        // the preferences still describe, or a stored key. The preferences answer first, so the key store — opened
        // off the main thread — is only consulted when they do not; the demo has no account to have used.
        hadAccount = {
            !prefs.demoMode.first() &&
                (prefs.cachedUser.first() != null || prefs.credentialInfo.first() != null || withContext(Dispatchers.IO) { !keyStore.apiKey().isNullOrBlank() })
        },
    )
    private val capabilities: suspend () -> Capabilities = { extendedMode.capabilities() }

    /** For api2 (the account's login and its Connect RPCs): no API-key interceptor, so only what each call sets goes out. */
    private val lazyAccountClient = lazy { CursorApiFactory.loginClient() }
    private val lazyAccountRpc = lazy { ConnectJsonClient(lazyAccountClient.value, CursorLoginEndpoints.API_URL) }
    /** The account session the account-level RPCs take, derived from the stored key when needed and kept in memory only; none while Extended mode is off. */
    private val lazySessionTokens = lazy { SessionTokenProvider(lazyAccountClient.value, { keyStore.apiKey() }, sessionAllowed = { extendedMode.isEnabled() }) }
    /** The account's agent list, pins, archive, rename, pull request statuses and sources: what the desktop Agents window and the iOS app show. */
    private val lazyAccountAgents = lazy { BackgroundComposerApi(lazyAccountRpc.value, lazySessionTokens.value) }
    private val lazyAccountPullRequests = lazy { CursorPullRequestSource(lazyAccountAgents.value) }
    private val lazyAccountSlashCommands = lazy { DashboardSlashCommandApi(lazyAccountRpc.value, lazySessionTokens.value) }

    /**
     * GitHub's REST API, anonymous: what stands in for the account service while Extended mode is off, for the
     * repositories it hosts — pull request states and the `.cursor/` skills and commands in a repository's tree.
     */
    private val lazyGitHub = lazy { GitHubApi(CursorApiFactory.gitHubClient()) }
    private val lazyGitHubPullRequests = lazy { GitHubPullRequestSource(lazyGitHub.value) }
    private val lazyGitHubSlashCommands = lazy { GitHubSlashCommandApi(lazyGitHub.value) }

    // The repositories take their sources by value, so the account's and GitHub's are handed over behind these
    // shims: a graph in default mode never builds the api2 client, and one in Extended mode never builds GitHub's.
    private val accountAgents = object : PinsApi, ComposerLifecycleApi {
        override suspend fun list(): AccountList = lazyAccountAgents.value.list()
        override suspend fun pin(ids: Collection<String>) = lazyAccountAgents.value.pin(ids)
        override suspend fun unpin(ids: Collection<String>) = lazyAccountAgents.value.unpin(ids)
        override suspend fun archive(id: String) = lazyAccountAgents.value.archive(id)
        override suspend fun unarchive(id: String) = lazyAccountAgents.value.unarchive(id)
        override suspend fun rename(id: String, name: String) = lazyAccountAgents.value.rename(id, name)
    }
    private val accountPullRequests = PullRequestSource { url -> lazyAccountPullRequests.value.lookup(url) }
    private val gitHubPullRequests = PullRequestSource { url -> lazyGitHubPullRequests.value.lookup(url) }
    private val accountSlashCommands = object : SlashCommandApi {
        override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog = lazyAccountSlashCommands.value.forRepository(repoUrl, ref)
        override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog = lazyAccountSlashCommands.value.forAgent(agentId, repoUrl, ref)
        override suspend fun global(): List<SlashCommand> = lazyAccountSlashCommands.value.global()
    }
    private val gitHubSlashCommands = object : SlashCommandApi {
        override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog = lazyGitHubSlashCommands.value.forRepository(repoUrl, ref)
        override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog = lazyGitHubSlashCommands.value.forAgent(agentId, repoUrl, ref)
        override suspend fun global(): List<SlashCommand> = lazyGitHubSlashCommands.value.global()
    }

    val session = SessionManager(
        keyStore,
        prefs,
        realBackend,
        demoBackend,
        browserLogin = lazy { CursorLogin(lazyAccountClient.value) },
        // What the key is called on cursor.com/dashboard/api, so the user can tell this phone's key from others.
        mintedKeyName = "Cursor for Android (${Build.MODEL.ifBlank { "Android" }})",
        profile = lazy { AccountApi(lazyAccountRpc.value, lazySessionTokens.value) },
        capabilities = capabilities,
    )

    private val lazyAgents = lazy {
        AgentRepository(
            session,
            prefs,
            attachments,
            caches.agents,
            demoSources = DemoData.sources,
            demoComposers = DemoData.composers,
            account = accountAgents,
            capabilities = capabilities,
        )
    }
    val agents: AgentRepository get() = lazyAgents.value

    /**
     * Where the agents' pull requests stand: the public API names a PR but never says if it is open, merged or closed.
     * On the account's word in Extended mode; on GitHub's, for the repositories it hosts, otherwise.
     */
    private val lazyPullRequests = lazy {
        PullRequestRepository(
            account = CapabilityGatedPullRequestSource(capabilities, account = accountPullRequests, gitHub = gitHubPullRequests),
            demo = DemoPullRequests,
            isDemo = { session.isDemo },
            cache = caches.pullRequests,
        )
    }
    val pullRequests: PullRequestRepository get() = lazyPullRequests.value

    /**
     * Pins shared with the desktop Agents window and the iOS app through the account (Extended mode; otherwise they
     * are this device's); its list read also carries the PR states and where each chat was started from (the Source filter).
     */
    private val lazyPins = lazy {
        PinRepository(
            session = session,
            prefs = prefs,
            agents = agents,
            api = accountAgents,
            onList = { list, agentsToken ->
                agents.applySources(list.sources, agentsToken)
                pullRequests.seed(list.pullRequests)
            },
            capabilities = capabilities,
        )
    }
    val pins: PinRepository get() = lazyPins.value

    private val lazyCatalog = lazy { CatalogRepository(session, caches.catalog) }
    val catalog: CatalogRepository get() = lazyCatalog.value

    /**
     * The composers' `/` catalogs — `/goal`, the built-in skills, and the project, plugin and synced ones the account
     * lists per repository or agent (Extended mode), or the project ones a GitHub-hosted repository's tree shows.
     */
    private val lazySlashCommands = lazy {
        SlashCommandRepository(session, accountSlashCommands, caches.slashCommands, repoContents = gitHubSlashCommands, capabilities = capabilities)
    }
    val slashCommands: SlashCommandRepository get() = lazySlashCommands.value

    /** Images agents generate, kept on-device the moment the stream delivers them: nothing serves them again. */
    private val lazyGeneratedMedia = lazy { GeneratedMediaStore(app) }
    val generatedMedia: GeneratedMediaStore get() = lazyGeneratedMedia.value

    /** One shared live stream per run, consumed by both the conversation screen and the live notification. */
    private val lazyLiveRuns = lazy {
        LiveRunHub(session, agents, images = GeneratedImageStore { agentId, callId, bytes, mimeType -> generatedMedia.save(agentId, callId, bytes, mimeType) })
    }
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
            onOpened = { agentId -> LiveNotifications.cancelFinished(app, agentId) },
        )
    }
    val conversations: ConversationRepository get() = lazyConversations.value

    /** Sees new chats' launches through once the composer has handed them over, so no screen has to stay for the answer. */
    private val lazyLauncher = lazy { ChatLauncher(conversations) }
    val launcher: ChatLauncher get() = lazyLauncher.value

    /**
     * Each chat's unsent follow-ups: the composer's draft, and the queue of messages sent while the agent was still on
     * its previous turn, which go out by themselves once it is free. Kept on disk so leaving the chat loses nothing.
     */
    private val lazyFollowUps = lazy {
        FollowUpRepository(
            conversations = conversations,
            agents = agents,
            hub = liveRuns,
            mcpServers = { mcpServers.enabled() },
            store = followUpStore,
            persist = { !session.isDemo },
        )
    }
    val followUps: FollowUpRepository get() = lazyFollowUps.value

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
            share.clear()
            // Resetting is only ever about what is in memory, so a part this process never built has nothing to
            // reset and is left unbuilt. The wipes further down are the opposite case: what an earlier process
            // wrote is on disk whether or not this one ever looked at it, so those are forced.
            if (lazyRunMonitor.isInitialized()) runMonitor.stop()
            if (lazyLiveRuns.isInitialized()) liveRuns.resetAll()
            if (lazyConversations.isInitialized()) conversations.resetAll()
            if (lazyFollowUps.isInitialized()) followUps.resetAll()
            if (lazyPins.isInitialized()) pins.reset()
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazySessionTokens.isInitialized()) lazySessionTokens.value.clear()
            // Signing out of one real account and into another keeps the same backend, so the list must be
            // reset explicitly or the previous account's agents would show.
            if (lazyAgents.isInitialized()) agents.reset()
            if (lazyCatalog.isInitialized()) catalog.reset()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            if (lazyArtifacts.isInitialized()) artifacts.resetAll()
            media.clearCaches()
            attachments.clear()
            generatedMedia.clear()
            drafts.clear()
            followUpStore.clear()
            caches.clear()
        }

        // Extended mode turned off (and, once, an upgraded install whose mode starts off): nothing the account
        // service produced stays. The session token first — nothing on api2 may be called from here — then what its
        // calls left in memory and on disk, and the pins become this device's own. Same rule as the sign-out above:
        // memory is reset only where this process built it, disk is wiped regardless.
        extendedMode.onDisabled = {
            if (lazySessionTokens.isInitialized()) lazySessionTokens.value.clear()
            if (lazyPins.isInitialized()) pins.reset()
            prefs.settlePinsLocally()
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            caches.pullRequests.removeAll()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            caches.slashCommands.removeAll()
            if (lazyAgents.isInitialized()) agents.forgetAccountSources(prefs.localAgentState.first().launchedHereIds)
            session.forgetAccountProfile()
        }
        // Extended mode turned on: what the account adds is fetched now rather than at the next cue — the picture,
        // and the pins, whose first sync pushes this device's up before adopting the account's list.
        extendedMode.onEnabled = {
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            session.refreshAccountProfile()
            if (lazyPins.isInitialized()) {
                pins.reset()
                pins.sync()
            }
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
            "accountSlashCommands" to lazyAccountSlashCommands,
            "gitHub" to lazyGitHub,
            "gitHubPullRequests" to lazyGitHubPullRequests,
            "gitHubSlashCommands" to lazyGitHubSlashCommands,
            "agents" to lazyAgents,
            "pullRequests" to lazyPullRequests,
            "pins" to lazyPins,
            "catalog" to lazyCatalog,
            "slashCommands" to lazySlashCommands,
            "generatedMedia" to lazyGeneratedMedia,
            "liveRuns" to lazyLiveRuns,
            "conversations" to lazyConversations,
            "launcher" to lazyLauncher,
            "followUps" to lazyFollowUps,
            "artifacts" to lazyArtifacts,
            "media" to lazyMedia,
            "runMonitor" to lazyRunMonitor,
            "updates" to lazyUpdates,
        )

    /** Which of [deferredParts] this process has actually built. */
    internal fun builtParts(): Set<String> = deferredParts.filterValues { it.isInitialized() }.keys

    suspend fun signOut() = session.signOut()
}
