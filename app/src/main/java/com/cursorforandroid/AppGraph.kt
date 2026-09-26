package com.cursorforandroid

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.cursorforandroid.crash.Breadcrumbs
import com.cursorforandroid.crash.CrashContext
import com.cursorforandroid.crash.CrashLog
import com.cursorforandroid.crash.CrashReporting
import com.cursorforandroid.data.api.AccountApi
import com.cursorforandroid.data.api.AccountTranscriptionApi
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.AgentFilesApi
import com.cursorforandroid.data.api.AgentStoreApi
import com.cursorforandroid.data.api.BackgroundComposerApi
import com.cursorforandroid.data.api.BlobCache
import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.AccountBranch
import com.cursorforandroid.data.api.AgentStartApi
import com.cursorforandroid.data.api.ConnectRepositoryBranchesApi
import com.cursorforandroid.data.api.RepositoryBranchesApi
import com.cursorforandroid.data.api.ConnectAgentStartApi
import com.cursorforandroid.data.api.ApiThrottle
import com.cursorforandroid.data.api.ConnectJsonClient
import com.cursorforandroid.data.api.ConnectProjectCreationApi
import com.cursorforandroid.data.api.ConnectPromptUploadApi
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.CreatedPullRequest
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.CursorServerApi
import com.cursorforandroid.data.api.DashboardSlashCommandApi
import com.cursorforandroid.data.api.DesktopProbe
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.FollowupQueueApi
import com.cursorforandroid.data.api.GitHubApi
import com.cursorforandroid.data.api.GitHubSlashCommandApi
import com.cursorforandroid.data.api.GoalStateApi
import com.cursorforandroid.data.api.HeadlessConversationApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessTurn
import com.cursorforandroid.data.api.HeadlessTurnPage
import com.cursorforandroid.data.api.LivePoint
import com.cursorforandroid.data.api.LiveWatch
import com.cursorforandroid.data.api.InteractionApi
import com.cursorforandroid.data.api.MachineApi
import com.cursorforandroid.data.api.MachineLookupApi
import com.cursorforandroid.data.api.OriginApi
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.PresignedStoreRead
import com.cursorforandroid.data.api.PresignedStoreWrite
import com.cursorforandroid.data.api.ProjectActionsApi
import com.cursorforandroid.data.api.ProjectApi
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.PromptUploadApi
import com.cursorforandroid.data.api.PullRequestApi
import com.cursorforandroid.data.api.PullRequestCreationApi
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.api.RunControlApi
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.api.SteeringApi
import com.cursorforandroid.data.api.StoreReadTarget
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.api.TurnPlan
import com.cursorforandroid.data.api.WorkerLaunch
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.data.auth.CursorLogin
import com.cursorforandroid.data.auth.CursorLoginEndpoints
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.demo.DemoBackendFactory
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoPullRequests
import com.cursorforandroid.data.demo.DemoReview
import com.cursorforandroid.data.demo.DemoStores
import com.cursorforandroid.data.local.AppCaches
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.DiskSweep
import com.cursorforandroid.data.local.DraftFiles
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.GeneratedMediaStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.McpServerStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.SubagentActivity
import com.cursorforandroid.data.repo.TranscriptSearchIndex
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.data.repo.CapabilityGatedPullRequestSource
import com.cursorforandroid.data.repo.CatalogRepository
import com.cursorforandroid.data.repo.ChatLauncher
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.CursorPullRequestSource
import com.cursorforandroid.data.repo.ExtendedMode
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.data.repo.GeneratedImageStore
import com.cursorforandroid.data.repo.GitHubPullRequestSource
import com.cursorforandroid.data.repo.LiveRunHub
import com.cursorforandroid.data.repo.NewChatDrafts
import com.cursorforandroid.data.repo.Onboarding
import com.cursorforandroid.data.repo.PinRepository
import com.cursorforandroid.data.repo.ProjectEditor
import com.cursorforandroid.data.repo.AgentStoreRepository
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.PromptUploader
import com.cursorforandroid.data.repo.PullRequestRepository
import com.cursorforandroid.data.repo.PullRequestSource
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.RemoteRepository
import com.cursorforandroid.data.repo.ReviewRepository
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.data.repo.SlashCommandRepository
import com.cursorforandroid.data.repo.SteeringRepository
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.data.repo.WorkspaceRepository
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.data.update.UpdateCache
import com.cursorforandroid.data.update.UpdateManager
import com.cursorforandroid.data.update.WhatsNewRepository
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.AgentScope
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.DesktopPage
import com.cursorforandroid.domain.DiagnosticsInbox
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PendingWork
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.RefreshStats
import com.cursorforandroid.domain.SendDiagnostics
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptDiagnostics
import com.cursorforandroid.domain.TranscriptPresenters
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.domain.WorkspaceTree
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.share.ShareInbox
import com.cursorforandroid.ui.components.ComposerMediaPreviews
import com.cursorforandroid.ui.conversation.AttachmentImages
import com.cursorforandroid.ui.conversation.OutgoingSends
import com.cursorforandroid.ui.media.GallerySaver
import com.cursorforandroid.ui.media.MediaSaves
import com.cursorforandroid.ui.settings.DIAGNOSTICS_DIR
import com.cursorforandroid.update.AndroidUpdatePlatform
import com.cursorforandroid.update.allocatableBytes
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * Injectable for tests only: a stand-in for the account's backend, so a sign-in with a key can be driven end to
     * end — the sign-in screen, the first-run flow behind it — against a scripted `/v1/me` rather than the real host.
     */
    real: CursorBackend? = null,
    /**
     * Injectable for tests only: the installed version's release notes as a test has them — any version, notes
     * already on disk — so the What's new surfaces can be driven without GitHub.
     */
    private val releaseNotes: WhatsNewRepository? = null,
    /**
     * The version this build reports - in Settings, the User-Agent, the diagnostics and to the What's new lookup.
     * Injectable for tests only: the screenshot tests render a fixed version, so cutting a release re-records nothing.
     */
    val appVersion: String = BuildConfig.VERSION_NAME,
    /** Crashes kept on the device (see [CrashLog]); the application installs its handler before building the graph. */
    val crashLog: CrashLog = CrashLog(File(context.applicationContext.filesDir, CrashLog.DIRECTORY), appVersion),
    /**
     * Injectable for tests only: the account's follow-up queue and prompt-upload services, so a send that carries
     * files can be driven end to end — the composer, the bubble, the retry — against a scripted account (latency,
     * refusals, rate limits, slow uploads) with none of api2's real network.
     */
    followupQueue: FollowupQueueApi? = null,
    promptUploadApi: PromptUploadApi? = null,
    /** Injectable for tests only: the account's start, so a chat started on one of the user's machines can be driven from the composer against a scripted account. */
    agentStartApi: AgentStartApi? = null,
    /** Injectable for tests only: the account's branch lists, for the composer's branch picker against a scripted account. */
    repositoryBranchesApi: RepositoryBranchesApi? = null,
    /** Injectable for tests only: the account's transcription, so the composer's voice input can be driven against a scripted account. */
    transcriptionApi: TranscriptionApi? = null,
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

    /** The New Chat composer's unsent drafts on disk, so a process death does not lose what was typed. */
    private val lazyDrafts = lazy { DraftStore(app) }
    val drafts: DraftStore get() = lazyDrafts.value

    /** The directories unsent drafts are kept in, as a sign-out parks them (see [DraftFiles.park]). */
    private val draftRoots = listOf(
        DraftFiles.Root(FollowUpStore.ROOT, entryWise = true),
        DraftFiles.Root(DraftStore.ROOT, entryWise = true),
        // 0.3.61's single New Chat draft, until a listing has brought it in.
        DraftFiles.Root(DraftStore.LEGACY_ROOT, entryWise = false),
    )

    /**
     * Who the drafts on disk belong to, as a sign-out parks them: the account signed in, else the one last cached; an
     * account nothing can name (a session restored offline with nothing cached) is [DraftFiles.UNATTRIBUTED]. Null for
     * the demo, whose drafts are nobody's.
     */
    private suspend fun draftOwner(): String? {
        val signedIn = session.state.value as? SessionState.SignedIn
        if (signedIn?.isDemo == true || session.isDemo) return null
        return DraftFiles.ownerKey(signedIn?.user) ?: DraftFiles.ownerKey(prefs.cachedUser.first()) ?: DraftFiles.UNATTRIBUTED
    }

    /**
     * The account signing in gets back what it left unsent on this device, and so do drafts no account could be
     * named for, which can only have been typed by whoever holds this device.
     */
    private suspend fun handBackDrafts() {
        val owner = DraftFiles.ownerKey(prefs.cachedUser.first())
        withContext(Dispatchers.IO) {
            if (owner != null) DraftFiles.unpark(app.filesDir, owner, draftRoots)
            DraftFiles.unpark(app.filesDir, DraftFiles.UNATTRIBUTED, draftRoots)
        }
    }

    /** The app has left the screen: every draft still waiting for its debounce is written now. */
    fun flushDrafts() {
        if (lazyFollowUps.isInitialized()) followUps.flushAll()
        if (lazyNewChatDrafts.isInitialized()) newChatDrafts.flush()
    }

    /** The account's API: one client, with the SSE stream sharing its dispatcher and connection pool. */
    private val realParts = lazy {
        val client = CursorApiFactory.okHttp { keyStore.apiKey() }
        CursorApiFactory.retrofit(client) to SseRunStreamer(CursorApiFactory.sseClient(client), { keyStore.apiKey() })
    }
    private val realBackend = real ?: CursorBackend(isDemo = false, parts = realParts)
    /** Seeded when the demo is entered, so a launch into a real account never pays for the dataset. */
    private val perfSeeds = BuildConfig.DEBUG && com.cursorforandroid.data.demo.DemoPerfSeeds.enabled(app.cacheDir)
    private val demoParts = lazy { DemoBackendFactory.create(perfSeeds = perfSeeds) }

    init {
        // A debug build measuring the transcript on a device: the `perf:` block after each presentation, in logcat.
        if (perfSeeds) com.cursorforandroid.domain.TranscriptPerf.logger = { android.util.Log.i("TranscriptPerf", it) }
    }
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

    /**
     * The first run's mode choice — SDK only or Extended mode — owed by a sign-in through the sign-in screen and
     * settled by the choice screen; see [Onboarding]. Cheap: it holds the preferences and the setting above.
     */
    val onboarding = Onboarding(prefs, extendedMode)

    /**
     * For api2 (the account's login and its Connect RPCs): no API-key interceptor, so only what each call sets goes
     * out. Its dispatcher lets every lane of the shared throttle through at once, and the session exchanges that go
     * around it: at OkHttp's five per host a figure's presign queued there behind the record's blobs and the open
     * chats' streams, where no call timeout runs yet.
     */
    private val lazyAccountClient = lazy { CursorApiFactory.loginClient().also { it.dispatcher.maxRequestsPerHost = ApiThrottle.ON_THE_WIRE + 2 } }
    private val lazyAccountRpc = lazy { ConnectJsonClient(lazyAccountClient.value, CursorLoginEndpoints.API_URL) }

    /** How long the account's calls are still held off by a pause the server asked for (a `429`, see `ApiThrottle`); 0 when none, or before any call. */
    fun accountPauseMillis(): Long {
        if (!lazyAccountRpc.isInitialized()) return 0L
        val until = lazyAccountRpc.value.throttle.pausedUntil() ?: return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }
    /** The account session the account-level RPCs take, derived from the stored key when needed and kept in memory only; none while Extended mode is off. */
    private val lazySessionTokens = lazy { SessionTokenProvider(lazyAccountClient.value, { keyStore.apiKey() }, sessionAllowed = { extendedMode.isEnabled() }) }
    /** The account's agent list, pins, archive, rename, pull request statuses and sources: what the desktop Agents window and the iOS app show. */
    private val lazyAccountAgents = lazy { BackgroundComposerApi(lazyAccountRpc.value, lazySessionTokens.value) }
    private val lazyAccountPullRequests = lazy { CursorPullRequestSource(lazyAccountAgents.value) }
    private val lazyAccountSlashCommands = lazy { DashboardSlashCommandApi(lazyAccountRpc.value, lazySessionTokens.value) }
    /** The account's Projects: who belongs to whom, and the coordinator's actions. */
    private val lazyProjectApi = lazy { ProjectApi(lazyAccountRpc.value, lazySessionTokens.value) }
    /** The agent's live VM: its workspace files and its branch diff (the panel's Files › Workspace and Changes). */
    private val lazyAgentFiles = lazy { AgentFilesApi(lazyAccountRpc.value, lazySessionTokens.value) }
    /** The machine's cursor-server, for a picture a tool call read outside the workspace (see [CursorServerApi]). */
    private val lazyCursorServer = lazy { CursorServerApi(lazyAccountRpc.value, lazySessionTokens.value, CursorApiFactory.cursorServerClient()) }
    /** The account's view of a pull request on any host it connects, and opening one from here. */
    private val lazyPullRequestApi = lazy { PullRequestApi(lazyAccountRpc.value, lazySessionTokens.value) }
    /** Where the agent's machine is, for its desktop. */
    private val lazyMachineApi = lazy { MachineApi(lazyAccountRpc.value, lazySessionTokens.value) }
    /** A chat's controls on the account: answering its question, its queue, steering and holding its run. */
    /** The account records' blobs, shared by the transcript's record reader and the goal strip's state read (see BlobCache). */
    private val lazyBlobCache = lazy { BlobCache(BlobCache.MEMORY_BLOBS_WITH_DISK, BlobCache.MEMORY_BYTES_WITH_DISK, disk = caches.blobs) }
    private val lazySteeringApi = lazy { SteeringApi(lazyAccountRpc.value, lazySessionTokens.value, blobs = lazyBlobCache.value) }
    /**
     * The account's transcription of a dictated clip, with the desktop's sixty-second limit on the call rather than
     * the account client's forty-five: a five-minute clip goes up whole and is transcribed before the answer starts.
     */
    private val lazyTranscription = lazy {
        transcriptionApi ?: run {
            val client = lazyAccountClient.value.newBuilder().callTimeout(TRANSCRIPTION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            AccountTranscriptionApi(ConnectJsonClient(client, CursorLoginEndpoints.API_URL, throttle = lazyAccountRpc.value.throttle), lazySessionTokens.value)
        }
    }
    val transcription: TranscriptionApi get() = lazyTranscription.value

    /**
     * Whether the composers offer the microphone: wherever a dictation can be transcribed. That is through the account
     * (`api2`), whose session is refused while Extended mode is off, so the mic is there in Extended mode and gone in
     * Default mode rather than failing at every tap; never in the demo, which has no account to ask.
     */
    val voiceInput: Flow<Boolean> = combine(extendedMode.enabled, prefs.demoMode) { extended, demo -> extended && !demo }
        .distinctUntilChanged()
    /**
     * The account's own transcript of a chat (`FetchBackgroundComposer`), on the account client with the transcript's
     * call timeout: a page of a coordinator's record is hundreds of kilobytes to megabytes of payloads, and the
     * account client's forty-five seconds — right for its small RPCs — cut such a page off on a slow connection,
     * which read as the record refusing and left the chat to the documented endpoints. The throttle is shared, so
     * the pause a refusal asks for holds here too.
     */
    private val lazyHeadlessTranscript = lazy {
        // Same dispatcher as the account client's, whose per-host limit already lets the blob lane through.
        val client = lazyAccountClient.value.newBuilder().callTimeout(RECORD_CALL_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES).build()
        HeadlessConversationApi(ConnectJsonClient(client, CursorLoginEndpoints.API_URL, throttle = lazyAccountRpc.value.throttle), lazySessionTokens.value, blobs = lazyBlobCache.value)
    }

    /**
     * GitHub's REST API, anonymous: what stands in for the account service while Extended mode is off, for the
     * repositories it hosts — pull request states and the `.cursor/` skills and commands in a repository's tree.
     */
    private val lazyGitHub = lazy { GitHubApi(CursorApiFactory.gitHubClient()) }
    private val lazyGitHubPullRequests = lazy { GitHubPullRequestSource(lazyGitHub.value) }
    private val lazyGitHubSlashCommands = lazy { GitHubSlashCommandApi(lazyGitHub.value) }

    /**
     * Origin's documented REST API, for Origin-hosted repositories and pull requests. It takes a user access token
     * the app has no documented way to mint (the CLI's exchange is not in the reference), so the provider answers
     * null and every Origin read degrades to "open in browser" until one exists.
     */
    private val lazyOrigin = lazy { OriginApi(CursorApiFactory.originClient(), tokenProvider = { null }) }

    /**
     * The panel's reads of a pull request, a repository's files and the agent's token usage: the documented and
     * public hosts in default mode, the account's SCM view standing in behind the `scmPullRequests` capability.
     */
    private val lazyReviews = lazy {
        ReviewRepository(
            gitHub = { lazyGitHub.value },
            origin = { lazyOrigin.value },
            usageApi = { agentId -> ReviewRepository.usageOf(session.current.api.usage(agentId)) },
            isDemo = { session.isDemo },
            demo = DemoReview,
            scm = { lazyPullRequestApi.value },
            creation = { accountPullRequestCreation },
            capabilities = capabilities,
        )
    }
    val reviews: ReviewRepository get() = lazyReviews.value

    /** The agent's workspace tree, its files and its branch diff, behind the `workspaceFiles` and `diffDetails` capabilities. */
    private val lazyWorkspace = lazy { WorkspaceRepository(files = agentFiles, diffs = agentFiles, capabilities = capabilities, isDemo = { session.isDemo }) }
    val workspace: WorkspaceRepository get() = lazyWorkspace.value

    /** A file by the path the agent names it with: its workspace (Extended), else its repository at its branch. */
    private val lazyAgentFileReads = lazy {
        AgentFileRepository(
            workspace = workspace,
            repository = { repoUrl, ref, path -> reviews.contents(repoUrl, ref, path) },
            agent = { id -> agents.agent(id) },
            wakeMachine = { id -> steering.wake(id).getOrDefault(false) },
            cursorServer = lazyCursorServer.value,
        )
    }
    val agentFileReads: AgentFileRepository get() = lazyAgentFileReads.value

    /**
     * The panel's Remote section: a Remote Control chat's machine from the documented fleet endpoint in either mode,
     * and the agent's VM desktop behind the `remoteDesktop` capability.
     */
    private val lazyRemote = lazy {
        RemoteRepository(
            workers = { session.current.api.listWorkers(scope = "personal") },
            machines = accountMachines,
            probe = DesktopProbe(client = { lazyAccountClient.value }, origin = DesktopPage.ORIGIN),
            capabilities = capabilities,
            isDemo = { session.isDemo },
            appVersion = appVersion,
        )
    }
    val remote: RemoteRepository get() = lazyRemote.value

    // The repositories take their sources by value, so the account's and GitHub's are handed over behind these
    // shims: a graph in default mode never builds the api2 client, and one in Extended mode never builds GitHub's.
    private val accountAgents = object : PinsApi, ComposerLifecycleApi {
        override suspend fun list(): AccountList = lazyAccountAgents.value.list()
        override suspend fun listMore(cursor: String): AccountList = lazyAccountAgents.value.listMore(cursor)
        override suspend fun pin(ids: Collection<String>) = lazyAccountAgents.value.pin(ids)
        override suspend fun unpin(ids: Collection<String>) = lazyAccountAgents.value.unpin(ids)
        override suspend fun archive(id: String) = lazyAccountAgents.value.archive(id)
        override suspend fun unarchive(id: String) = lazyAccountAgents.value.unarchive(id)
        override suspend fun rename(id: String, name: String) = lazyAccountAgents.value.rename(id, name)
    }
    private val accountPullRequests = PullRequestSource { url -> lazyAccountPullRequests.value.lookup(url) }
    private val gitHubPullRequests = PullRequestSource { url -> lazyGitHubPullRequests.value.lookup(url) }
    private val projectAccount = object : ProjectLineageApi, ProjectActionsApi, AgentStoreApi {
        override suspend fun workersForManager(managerId: String): List<WorkerMembership> = lazyProjectApi.value.workersForManager(managerId)
        override suspend fun children(parentId: String): List<ComposerSnapshot> = lazyProjectApi.value.children(parentId)
        override suspend fun record(id: String): ComposerSnapshot? = lazyAccountAgents.value.record(id)
        override suspend fun scanRoots(maxPages: Int): RootScan = lazyAccountAgents.value.scanRoots(maxPages)
        override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan = lazyAccountAgents.value.scanRoots(maxPages, stopBelowActivityMillis)
        override suspend fun createWorker(managerId: String, launch: WorkerLaunch): ComposerSnapshot = lazyProjectApi.value.createWorker(managerId, launch)
        override suspend fun setWorkerManager(workerId: String, managerId: String, spawnKind: WorkerSpawnKind) = lazyProjectApi.value.setWorkerManager(workerId, managerId, spawnKind)
        override suspend fun clearWorkerManager(workerId: String) = lazyProjectApi.value.clearWorkerManager(workerId)
        override suspend fun reparent(agentId: String, parentId: String, subagentType: String?) = lazyProjectApi.value.reparent(agentId, parentId, subagentType)
        override suspend fun updateAppearance(projectId: String, appearance: ProjectAppearance): ProjectAppearance? = lazyProjectApi.value.updateAppearance(projectId, appearance)
        override suspend fun startSideChat(parentId: String, name: String?): ComposerSnapshot = lazyProjectApi.value.startSideChat(parentId, name)
        override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome = lazyProjectApi.value.steer(agentId, text, expectedRunId)
        override suspend fun pause(agentId: String, runId: String?) = lazyProjectApi.value.pause(agentId, runId)
        override suspend fun resume(agentId: String) = lazyProjectApi.value.resume(agentId)
        override suspend fun storeFor(sourceId: String): String? = lazyProjectApi.value.storeFor(sourceId)
        override suspend fun stores(): List<AgentStoreRef> = lazyProjectApi.value.stores()
        override suspend fun entries(storeId: String, relativePath: String): List<ContextEntry> = lazyProjectApi.value.entries(storeId, relativePath)
        override suspend fun readFile(storeId: String, relativePath: String): String = lazyProjectApi.value.readFile(storeId, relativePath)
        override suspend fun presignRead(target: StoreReadTarget, relativePath: String): PresignedStoreRead? = lazyProjectApi.value.presignRead(target, relativePath)
        override suspend fun presignWrite(storeId: String, relativePath: String, sizeBytes: Long, sha256Hex: String): PresignedStoreWrite? = lazyProjectApi.value.presignWrite(storeId, relativePath, sizeBytes, sha256Hex)
    }
    private val agentFiles = object : WorkspaceFilesApi, DiffDetailsApi {
        override suspend fun listFiles(agentId: String): WorkspaceTree = lazyAgentFiles.value.listFiles(agentId)
        override suspend fun readFile(agentId: String, path: String): ByteArray = lazyAgentFiles.value.readFile(agentId, path)
        override suspend fun diffDetails(agentId: String): AgentDiff = lazyAgentFiles.value.diffDetails(agentId)
    }
    private val accountPullRequestCreation = object : PullRequestCreationApi {
        override suspend fun makePullRequest(agentId: String, branchName: String?): CreatedPullRequest = lazyPullRequestApi.value.makePullRequest(agentId, branchName)
        override suspend fun openPullRequest(agentId: String, title: String?, body: String?, baseBranch: String?, draft: Boolean): CreatedPullRequest =
            lazyPullRequestApi.value.openPullRequest(agentId, title, body, baseBranch, draft)
    }
    private val accountMachines = MachineLookupApi { agentId -> lazyMachineApi.value.machine(agentId) }
    private val steeringAccount = object : InteractionApi, FollowupQueueApi, RunControlApi {
        override suspend fun answerQuestion(agentId: String, toolCallId: String, answers: List<ToolPayload.Question.Answer>): InteractionResolution = lazySteeringApi.value.answerQuestion(agentId, toolCallId, answers)
        override suspend fun addFollowup(agentId: String, followup: AccountFollowup, synchronous: Boolean): String? = lazySteeringApi.value.addFollowup(agentId, followup, synchronous)
        override suspend fun listPending(agentId: String): List<PendingFollowup> = lazySteeringApi.value.listPending(agentId)
        override suspend fun updatePending(agentId: String, followupId: String, text: String) = lazySteeringApi.value.updatePending(agentId, followupId, text)
        override suspend fun deletePending(agentId: String, followupId: String) = lazySteeringApi.value.deletePending(agentId, followupId)
        override suspend fun reorderPending(agentId: String, followupId: String, targetFollowupId: String, insertAfter: Boolean) = lazySteeringApi.value.reorderPending(agentId, followupId, targetFollowupId, insertAfter)
        override suspend fun submitPendingNow(agentId: String, followupId: String) = lazySteeringApi.value.submitPendingNow(agentId, followupId)
        override suspend fun markEditing(agentId: String, followupId: String, editing: Boolean) = lazySteeringApi.value.markEditing(agentId, followupId, editing)
        override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome = lazySteeringApi.value.steer(agentId, text, expectedRunId)
        override suspend fun promoteFollowup(agentId: String, followupId: String, expectedRunId: String?): SteerOutcome = lazySteeringApi.value.promoteFollowup(agentId, followupId, expectedRunId)
        override suspend fun pause(agentId: String, runId: String?) = lazySteeringApi.value.pause(agentId, runId)
        override suspend fun resume(agentId: String) = lazySteeringApi.value.resume(agentId)
        override suspend fun cancelToolCall(agentId: String, toolCallId: String): Boolean = lazySteeringApi.value.cancelToolCall(agentId, toolCallId)
        override suspend fun wake(agentId: String): Boolean = lazySteeringApi.value.wake(agentId)
    }
    private val accountGoals = GoalStateApi { agentId -> lazySteeringApi.value.goal(agentId) }
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

    /**
     * A prompt's files of any type, staged the way the desktop stages them (`PresignPromptUpload`, the parts `PUT`,
     * `CompletePromptUpload`) and referenced from the account's follow-up or start (Extended mode, `promptFiles`).
     */
    private val lazyPromptUploadApi = lazy { promptUploadApi ?: ConnectPromptUploadApi(lazyAccountRpc.value, lazySessionTokens.value) }
    private val lazyPromptUploads = lazy { PromptUploader(lazyPromptUploadApi.value, lazyAccountClient.value) }
    val promptUploads: PromptUploader get() = lazyPromptUploads.value

    /** The composers' attached files going up the moment they are attached, so a send waits on no upload (see [AttachmentUploads]). */
    private val lazyAttachmentUploads = lazy { AttachmentUploads(uploader = { promptUploads }) }
    val attachmentUploads: AttachmentUploads get() = lazyAttachmentUploads.value

    /** A new chat started on the account service, for the first prompt that carries files (see [ConnectAgentStartApi]). */
    private val lazyAgentStart: Lazy<AgentStartApi> = lazy {
        agentStartApi ?: ConnectAgentStartApi(lazyAccountRpc.value, lazySessionTokens.value, noRepoEnvironment = { lazyProjectCreation.value.noRepoEnvironmentPublicId() })
    }

    private val lazyRepositoryBranches: Lazy<RepositoryBranchesApi> = lazy { repositoryBranchesApi ?: ConnectRepositoryBranchesApi(lazyAccountRpc.value, lazySessionTokens.value) }

    /**
     * [repoUrl]'s branches as the account lists them (`GetRepositoryBranches`, Extended mode) for the composer's branch
     * picker; nothing in the default mode, the demo, or when the account cannot say.
     */
    suspend fun accountBranches(repoUrl: String): List<AccountBranch> {
        if (session.isDemo || !capabilities().accountSession) return emptyList()
        return runCatching { lazyRepositoryBranches.value.branches(repoUrl) }.getOrDefault(emptyList())
    }

    /** What the last refresh cost, stage by stage, across the list, the account round, the Projects and the badges (see [RefreshStats]). */
    val refreshStats = RefreshStats()

    /**
     * The list's work in flight — its pages, its passes by id, the account's list read — shared by the list and the
     * account layer (see [PendingWork]): what the sidebar's one loading row stands for, and what the diagnostics name.
     */
    val pendingWork = PendingWork()

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
            stats = refreshStats,
            pending = pendingWork,
            // A pinned chat the public API will not give (Extended mode): stood in from its account record.
            recordOf = { id -> if (!session.isDemo && capabilities().accountSession) lazyAccountAgents.value.record(id) else null },
            start = { lazyAgentStart.value },
            uploads = { promptUploads },
        ).also { repo ->
            // The account layer — the pin repository's round: the account's list with its names, looks, sources,
            // lineage fields and running statuses, the pins, the pull request states, and off it the root discovery
            // and the memberships — is read ahead of every fetch's first publication, and the round follows the
            // fetch. It used to be cued by whatever first touched the pin repository: since 0.2.0 built the graph
            // lazily that was a pin toggle, Settings, or the sidebar scrolled to its end, so on an ordinary launch
            // with Extended mode on the account's list was never read, and everything drawn from it (Project icons,
            // the workers' places, the running set) came and went with the user's scrolling. Resolved at call time,
            // so building the list does not build the pins; the read itself asks the capabilities, and with
            // Extended mode off it returns at once.
            repo.accountPrime = { pins.primeForFetch() }
            // The account's list paged alongside the public one, once per page the reader asks for (see loadMore).
            repo.accountPage = { pins.loadMore() }
        }
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
            stats = refreshStats,
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
                // Each Project's memberships follow the list read, the way the Agents Window polls them; off the
                // round, so the pins do not wait on a Project with many workers.
                // The root registry is filled from the account list — to the page older than every Project it
                // knows, or the whole list on a deep refresh and a few times an hour — the memberships read after;
                // the Projects group is drawn from the registry, not from the pages the sidebar holds.
                projects.scheduleRootDiscovery(list.composers.filter { it.scope == AgentScope.PROJECT_ROOT }.map { it.id }, deep = agents.lastRefreshDepth == RefreshDepth.Deep)
            },
            capabilities = capabilities,
            stats = refreshStats,
            pending = pendingWork,
        )
    }
    val pins: PinRepository get() = lazyPins.value

    /**
     * Cursor Projects: the lineage that keeps a Project's workers, side chats and subagents inside the Project and
     * out of the chat list — from the account in Extended mode, from the public record of a parent the list names but
     * lacks in either — and, in Extended mode, what a Project's view shows and does.
     */
    private val lazyProjects = lazy { ProjectRepository(session, agents, projectAccount, actions = projectAccount, store = projectAccount, capabilities = capabilities, stats = refreshStats) }
    val projects: ProjectRepository get() = lazyProjects.value

    /** Creating a Project and editing its name and look, from the sidebar, the panel and the Project's own view (Extended mode). */
    private val lazyProjectCreation = lazy { ConnectProjectCreationApi(lazyAccountRpc.value, lazySessionTokens.value) }
    private val lazyProjectEditor = lazy { ProjectEditor(session, agents, projects, creation = { lazyProjectCreation.value }, capabilities = capabilities) }
    val projectEditor: ProjectEditor get() = lazyProjectEditor.value

    /**
     * The panel's Context tabs: the Project's Agent Store (its notes, its files) and the user's own, read through the
     * same account endpoints as the Project section, behind the `projects` capability; the demo's stores stand in.
     */
    private val lazyAgentStores = lazy { AgentStoreRepository(api = projectAccount, capabilities = capabilities, isDemo = { session.isDemo }, demo = DemoStores) }
    val agentStores: AgentStoreRepository get() = lazyAgentStores.value

    private val lazyCatalog = lazy {
        CatalogRepository(session, caches.catalog, throttlePausedUntil = { if (lazyAccountRpc.isInitialized()) lazyAccountRpc.value.throttle.pausedUntil() else null })
    }
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
        LiveRunHub(session, agents, images = GeneratedImageStore { agentId, callId, bytes, mimeType -> generatedMedia.save(agentId, callId, bytes, mimeType) }, parking = caches.liveRuns)
    }
    val liveRuns: LiveRunHub get() = lazyLiveRuns.value

    /** Where each cloud subagent a transcript's rows stand for is, live, off the list and the hub's shared streams. */
    private val lazySubagentActivity = lazy { SubagentActivity(agents, liveRuns, catalog.models) }
    val subagentActivity: SubagentActivity get() = lazySubagentActivity.value

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
            onOpened = { agentId ->
                LiveNotifications.cancelFinished(app, agentId)
                if (lazyLiveSync.isInitialized()) lazyLiveSync.value.opened(agentId)
            },
            record = accountTranscript,
            capabilities = capabilities,
            images = GeneratedImageStore { agentId, callId, bytes, mimeType -> generatedMedia.save(agentId, callId, bytes, mimeType) },
            // A Remote Control chat's machine says which chat it is busy with (`GET /v0/private-workers`, `activeBcId`).
            machineBusy = { agent -> remote.machineStatus(agent)?.getOrNull()?.let { it.activeAgentId == agent.id } },
            composerStatus = { id -> lazyAccountAgents.value.status(id) },
        )
    }

    private val accountTranscript = object : ConversationRecordApi {
        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage = lazyHeadlessTranscript.value.fetch(agentId, startIndex, limit)
        override suspend fun state(agentId: String): RecordState = lazyHeadlessTranscript.value.state(agentId)
        override suspend fun turns(agentId: String, from: Int, limit: Int, state: RecordState?): HeadlessTurnPage? = lazyHeadlessTranscript.value.turns(agentId, from, limit, state)
        override suspend fun readTurns(agentId: String, from: Int, limit: Int, state: RecordState?, plan: TurnPlan, held: Map<Int, String>, patient: Boolean): HeadlessTurnPage? = lazyHeadlessTranscript.value.readTurns(agentId, from, limit, state, plan, held, patient)
        override fun blobCounts(agentId: String) = if (lazyHeadlessTranscript.isInitialized()) lazyHeadlessTranscript.value.blobCounts(agentId) else null
        override suspend fun watch(agentId: String, since: LivePoint?, resume: Boolean): LiveWatch = lazyHeadlessTranscript.value.watch(agentId, since, resume)
        override suspend fun heldTurns(agentId: String, turns: Map<Int, String>): List<HeadlessTurn> = lazyHeadlessTranscript.value.heldTurns(agentId, turns)
        override val readsTurns: Boolean get() = true
    }
    val conversations: ConversationRepository get() = lazyConversations.value

    /** Background live sync (Settings › Experimental › Keep chats live): started by [LiveSyncBinding]. */
    private val lazyLiveSync = lazy {
        LiveSync(
            target = object : LiveSync.Target {
                override fun hold(agentId: String) = conversations.hold(agentId)
                override fun release(agentId: String) = conversations.release(agentId)
                override suspend fun settled(agentId: String) = conversations.settled(agentId)
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    val liveSync: LiveSync get() = lazyLiveSync.value
    /** Whether background live sync runs: the switch, outside the demo (which has no account to stream from). */
    val liveSyncEnabled: Flow<Boolean> get() = combine(prefs.liveSync, prefs.demoMode) { on, demo -> on && !demo }

    /** The search palette's reading of the transcripts kept on this device (Ctrl+F, see [TranscriptSearchIndex]). */
    private val lazyTranscriptSearch = lazy { TranscriptSearchIndex(caches.conversations, caches.traces) }
    val transcriptSearch: TranscriptSearchIndex get() = lazyTranscriptSearch.value

    /** Sees new chats' launches through once the composer has handed them over, so no screen has to stay for the answer. */
    private val lazyLauncher = lazy { ChatLauncher(conversations) }
    val launcher: ChatLauncher get() = lazyLauncher.value

    /** The new chats written and not sent: the sidebar's drafts, and the one the New Chat composer has open. */
    private val lazyNewChatDrafts = lazy { NewChatDrafts(drafts, agents, launcher) }
    val newChatDrafts: NewChatDrafts get() = lazyNewChatDrafts.value

    /**
     * The messages on their way out of each chat's composer, in a scope no screen owns: a send tapped just before the
     * chat was left finishes all the same, and its bubble keeps its status for the next visit (see [OutgoingSends]).
     */
    private val lazyOutgoing = lazy {
        OutgoingSends(
            conversations = conversations,
            uploads = attachmentUploads,
            steering = steering,
            followUps = followUps,
            mcpServers = { mcpServers.enabled() },
            capabilities = capabilities,
            isDemo = { session.isDemo },
        )
    }
    val outgoing: OutgoingSends get() = lazyOutgoing.value

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
            // A queued message with files goes out through the account's follow-up: `AddAsyncFollowupBackgroundComposer`
            // with them as `selected_documents[]` — or `selected_images[]` for an image — by the references their
            // uploads settled on when they were attached; a file without one is uploaded here (Extended mode).
            accountSend = { agentId, item ->
                if (!capabilities().promptFiles || session.isDemo) throw IllegalStateException(AgentRepository.FILES_NEED_EXTENDED)
                val uploaded = promptUploads.ensure(item.files.map { it.file })
                val followup = AccountFollowup(
                    text = item.previewText,
                    images = item.images.map { it.image },
                    files = uploaded,
                    mode = AgentMode.ofPlanMode(item.planMode),
                    modelId = item.modelId,
                    modelParams = item.modelParams,
                )
                steering.sendFollowup(agentId, followup).getOrThrow()
            },
            // A queued message the server refuses as busy against every word here goes to the account's queue
            // (Extended mode), which sends it when the agent is free — where the composer said it would go.
            accountQueue = { agentId, item ->
                val followup = AccountFollowup(
                    text = item.previewText,
                    images = item.images.map { it.image },
                    files = emptyList(),
                    mode = AgentMode.ofPlanMode(item.planMode),
                    modelId = item.modelId,
                    modelParams = item.modelParams,
                )
                FollowUpRepository.AccountHandoff(steering.sendFollowup(agentId, followup).getOrThrow(), followup.followupId)
            },
            accountQueueAvailable = { capabilities().accountQueue && !session.isDemo },
            store = followUpStore,
            persist = { !session.isDemo },
        )
    }
    val followUps: FollowUpRepository get() = lazyFollowUps.value

    /** The presented rows of the chats a reader flips between, kept across their screens (see [TranscriptPresenters]). */
    val presenters = TranscriptPresenters()

    /**
     * A chat's controls on the account (Extended mode): the question it is waiting on, the account's queue in place
     * of the device's, steering, pause and resume, one tool call's cancel. Each answers a named refusal with the mode off.
     */
    private val lazySteering = lazy {
        SteeringRepository(
            session = session,
            agents = agents,
            interactions = steeringAccount,
            queueApi = followupQueue ?: steeringAccount,
            runs = steeringAccount,
            goals = accountGoals,
            afterAction = { agentId -> conversations.revalidate(agentId) },
            // Every queue read goes to the transcript, and a message the transcript files under its run has the queue read again (see QueuePlacement).
            onQueueRead = { agentId, pending, readAt -> conversations.noteAccountQueue(agentId, pending, readAt) },
            onQueuedDeleted = { agentId, followupId -> conversations.queuedDeleted(agentId, followupId) },
            onQueuedEdited = { agentId, followupId, text -> conversations.queuedEdited(agentId, followupId, text) },
            placement = { agentId -> conversations.queuePlacement(agentId) },
            capabilities = capabilities,
        )
    }
    val steering: SteeringRepository get() = lazySteering.value

    /** Presigned URLs for `/opt/cursor/artifacts/…` references in replies, and the loader that draws them. */
    private val lazyArtifacts = lazy { ArtifactRepository(session) }
    val artifacts: ArtifactRepository get() = lazyArtifacts.value

    /** The files `/cursor/stores/…` paths in replies point at, read through the account's store reads (Extended mode). */
    private val lazyMediaClient = lazy { CursorApiFactory.mediaClient() }
    private val lazyStoreFiles = lazy {
        StoreFileRepository(
            api = { projectAccount },
            capabilities = capabilities,
            cache = caches.storeFiles,
            blobs = File(app.cacheDir, "cursor/storefiles/blobs"),
            http = lazyMediaClient.value,
        )
    }
    val storeFiles: StoreFileRepository get() = lazyStoreFiles.value

    private val lazyMedia = lazy { MediaLoader(app, lazyMediaClient.value, artifacts, stores = { storeFiles }, files = { agentFileReads }) }
    val media: MediaLoader get() = lazyMedia.value

    /** The media viewer's saves to the gallery: the process's, so a save runs on past the viewer's close and shows when it opens again. */
    private val lazyMediaSaves = lazy { MediaSaves(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), media, GallerySaver(app)) }
    val mediaSaves: MediaSaves get() = lazyMediaSaves.value

    private val lazyRunMonitor = lazy {
        RunMonitor(
            agents = agents,
            hub = liveRuns,
            runRecord = { agentId, runId -> session.current.api.getRun(agentId, runId) },
            notificationPrefs = prefs.projectNotifications,
        )
    }
    val runMonitor: RunMonitor get() = lazyRunMonitor.value

    /**
     * The GitHub releases of [BuildConfig.GITHUB_REPO], read anonymously: one client for the updater and the What's
     * new notes, so the rate limit GitHub answers one of them with is remembered for both.
     */
    private val lazyReleases = lazy {
        GitHubReleasesClient(
            CursorApiFactory.updateClient(),
            BuildConfig.GITHUB_REPO,
            apiBaseUrl = BuildConfig.UPDATE_API_BASE_URL,
            freeSpace = { allocatableBytes(app, it) },
        )
    }

    /**
     * In-app updates from the GitHub releases of [BuildConfig.GITHUB_REPO]. Device-level, not account-level: its
     * cache and downloads sit next to (not inside) [caches], so signing out leaves them alone.
     */
    private val lazyUpdates = lazy {
        UpdateManager(
            client = lazyReleases.value,
            prefs = prefs,
            cache = UpdateCache(JsonDiskCache(File(app.cacheDir, "update-check"))),
            platform = AndroidUpdatePlatform(app, installedVersionName = appVersion),
            downloadDir = File(app.cacheDir, "updates"),
            // The background service streaming a run is the one thing a silent self-update would cut off; a monitor
            // this process never built is holding no stream, and asking is not worth building one.
            agentsRunning = { lazyRunMonitor.isInitialized() && runMonitor.isRunning },
        )
    }
    val updates: UpdateManager get() = lazyUpdates.value

    /**
     * The installed version's release notes — the What's new page, its row in Settings and its card in the sidebar.
     * Device-level like the updater, with a cache of its own beside the updater's; signing out leaves it alone.
     */
    private val lazyWhatsNew = lazy {
        releaseNotes ?: WhatsNewRepository(
            client = lazyReleases.value,
            prefs = prefs,
            cache = JsonDiskCache(File(app.cacheDir, "whats-new")),
            installedVersionName = appVersion,
        )
    }
    val whatsNew: WhatsNewRepository get() = lazyWhatsNew.value

    private val swept = AtomicBoolean(false)

    /**
     * Deletes what an earlier process left in the cache directory and nothing will ever read again: media copies past
     * their budget, composer previews and staged prompts of sends that never finished, old diagnostics exports — and
     * the values of settings that no longer exist. Once per process; each sweep stands alone, so one directory that
     * cannot be listed leaves the others to theirs.
     */
    suspend fun sweepLeftovers() {
        if (!swept.compareAndSet(false, true)) return
        prefs.forgetRetiredSettings()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching { MediaLoader.sweepCopies(app.cacheDir, now) }
            runCatching { ComposerMediaPreviews.sweep(File(app.cacheDir, ComposerMediaPreviews.DIR), now) }
            runCatching { attachments.sweepStaging(now) }
            runCatching { DiskSweep.deleteOlderThan(File(app.cacheDir, DIAGNOSTICS_DIR), now - TimeUnit.DAYS.toMillis(1)) }
        }
    }

    init {
        session.onSignedIn = {
            // A sign-in through the sign-in screen owes the first-run choice; a restored session never does.
            onboarding.signedIn()
            // What this account left unsent here when it last signed out is its own again, before any screen reads it.
            handBackDrafts()
        }
        // Whether the user signs out or the key is rejected, nothing of the account stays readable: what it had
        // cached is wiped, and what it had typed and not sent is parked where only the same account's sign-in finds it.
        session.onSignedOut = {
            // Read before anything is reset: the account the drafts belong to — and what it typed a moment ago,
            // written before the saves are stopped.
            val owner = draftOwner()
            if (owner != null && lazyFollowUps.isInitialized()) followUps.saveAll()
            if (owner != null && lazyNewChatDrafts.isInitialized()) newChatDrafts.saveOpen()
            // The choice the account owed goes with it (its stored flag is among the session keys cleared below).
            onboarding.signedOut()
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
            if (lazyNewChatDrafts.isInitialized()) newChatDrafts.reset()
            // Sends and uploads on their way out for this account stop here, before the composer's stores are wiped.
            if (lazyOutgoing.isInitialized()) outgoing.resetAll()
            if (lazyAttachmentUploads.isInitialized()) attachmentUploads.resetAll()
            if (lazyPins.isInitialized()) pins.reset()
            if (lazyProjects.isInitialized()) projects.reset()
            if (lazySteering.isInitialized()) steering.reset()
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazySessionTokens.isInitialized()) lazySessionTokens.value.clear()
            // Signing out of one real account and into another keeps the same backend, so the list must be
            // reset explicitly or the previous account's agents would show.
            if (lazyAgents.isInitialized()) agents.reset()
            if (lazyCatalog.isInitialized()) catalog.reset()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            if (lazyReviews.isInitialized()) reviews.reset()
            if (lazyWorkspace.isInitialized()) workspace.reset()
            if (lazyAgentStores.isInitialized()) agentStores.reset()
            if (lazyRemote.isInitialized()) remote.reset()
            if (lazyArtifacts.isInitialized()) artifacts.resetAll()
            if (lazyStoreFiles.isInitialized()) storeFiles.resetAll()
            if (lazyTranscriptSearch.isInitialized()) transcriptSearch.clear()
            media.clearCaches()
            attachments.clear()
            generatedMedia.clear()
            if (owner == null) {
                // The demo's drafts are the demo's: nothing real was typed against an account.
                drafts.clear()
                followUpStore.clear()
            } else {
                drafts.whileStopped { followUpStore.whileStopped { withContext(Dispatchers.IO) { DraftFiles.park(app.filesDir, owner, draftRoots) } } }
            }
            caches.clear()
        }

        // Extended mode turned off (and, once, an upgraded install whose mode starts off): nothing the account
        // service produced stays. The session token first — nothing on api2 may be called from here — then what its
        // calls left in memory and on disk, and the pins become this device's own. Same rule as the sign-out above:
        // memory is reset only where this process built it, disk is wiped regardless.
        extendedMode.onDisabled = {
            if (lazySessionTokens.isInitialized()) lazySessionTokens.value.clear()
            if (lazyPins.isInitialized()) pins.reset()
            // What the account said about a chat's queue and controls is the account's, not this device's.
            if (lazySteering.isInitialized()) steering.reset()
            prefs.settlePinsLocally()
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            caches.pullRequests.removeAll()
            // The account records' blobs are the account's too.
            caches.blobs.clear()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            caches.slashCommands.removeAll()
            // The panel's account reads — a pull request the SCM service answered, a workspace listing, a diff — go too.
            if (lazyReviews.isInitialized()) reviews.reset()
            if (lazyWorkspace.isInitialized()) workspace.reset()
            if (lazyAgentStores.isInitialized()) agentStores.reset()
            if (lazyRemote.isInitialized()) remote.reset()
            if (lazyAgents.isInitialized()) agents.forgetAccountSources(prefs.localAgentState.first().launchedHereIds)
            session.forgetAccountProfile()
        }
        // Extended mode turned on: what the account adds is fetched now rather than at the next cue — the picture,
        // and the pins, whose first sync pushes this device's up before adopting the account's list.
        extendedMode.onEnabled = {
            if (lazyAccountPullRequests.isInitialized()) lazyAccountPullRequests.value.reset()
            if (lazyPullRequests.isInitialized()) pullRequests.reset()
            if (lazySlashCommands.isInitialized()) slashCommands.reset()
            // A pull request the browser stood in for can now be read through the account.
            if (lazyReviews.isInitialized()) reviews.reset()
            session.refreshAccountProfile()
            // Built if it was not yet: the account's list is read the moment the mode allows it.
            pins.reset()
            pins.sync()
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
            "newChatDrafts" to lazyNewChatDrafts,
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
            "origin" to lazyOrigin,
            "reviews" to lazyReviews,
            "agentFiles" to lazyAgentFiles,
            "pullRequestApi" to lazyPullRequestApi,
            "machineApi" to lazyMachineApi,
            "workspace" to lazyWorkspace,
            "agentStores" to lazyAgentStores,
            "remote" to lazyRemote,
            "agents" to lazyAgents,
            "pullRequests" to lazyPullRequests,
            "pins" to lazyPins,
            "projectApi" to lazyProjectApi,
            "projects" to lazyProjects,
            "steeringApi" to lazySteeringApi,
            "steering" to lazySteering,
            "catalog" to lazyCatalog,
            "slashCommands" to lazySlashCommands,
            "generatedMedia" to lazyGeneratedMedia,
            "liveRuns" to lazyLiveRuns,
            "subagentActivity" to lazySubagentActivity,
            "conversations" to lazyConversations,
            "launcher" to lazyLauncher,
            "outgoing" to lazyOutgoing,
            "attachmentUploads" to lazyAttachmentUploads,
            "followUps" to lazyFollowUps,
            "artifacts" to lazyArtifacts,
            "storeFiles" to lazyStoreFiles,
            "media" to lazyMedia,
            "runMonitor" to lazyRunMonitor,
            "releases" to lazyReleases,
            "updates" to lazyUpdates,
            "whatsNew" to lazyWhatsNew,
        )

    /** Which of [deferredParts] this process has actually built. */
    internal fun builtParts(): Set<String> = deferredParts.filterValues { it.isInitialized() }.keys

    /**
     * The redacted account of where every chat was placed and by what (see [ProjectDiagnostics]): Settings' debug sheet
     * exports it, so a Project's chat that still shows among the account's own can be traced to the signal that
     * missed. Ids are shortened to their tails; no names, prompts or tokens are in it.
     */
    suspend fun projectDiagnosticsReport(): String {
        val list = agents.state.value
        val allowed = capabilities()
        val syncs = if (lazyProjects.isInitialized()) projects.syncRecords() else emptyMap()
        return ProjectDiagnostics.render(
            ProjectDiagnostics.Input(
                appVersion = appVersion,
                nowIso = java.time.Instant.ofEpochMilli(AppClock.now()).toString(),
                extendedMode = extendedMode.enabled.first(),
                projectsCapability = allowed.projects,
                accountSession = allowed.accountSession,
                listFromCache = list.isFromCache,
                lastRefreshedIso = agents.lastRefreshedAt.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() },
                agents = list.agents,
                placementOf = agents::placementOf,
                rootSyncs = syncs.mapValues { (_, s) -> ProjectDiagnostics.RootSync(s.workersRead, s.childrenRead, s.workerCount, s.childCount, s.notice) },
                pinnedIds = prefs.localAgentState.first().pinnedIds,
                unresolvedPinned = agents.unresolvedPinned(),
                runningScan = agents.runningScan.value.takeIf { it.hasScanned },
                unresolvedRecords = agents.unresolvedRecords(),
                knownRoots = agents.knownRoots.value,
                unresolvedRoots = agents.unresolvedRoots(),
                rootScan = if (lazyProjects.isInitialized()) {
                    projects.lastRootScan.value?.let {
                        ProjectDiagnostics.RootScanSummary(it.status.name, it.rootsFound, it.pagesRead, it.records, it.complete, it.atMillis.takeIf { ms -> ms > 0 }?.let { ms -> java.time.Instant.ofEpochMilli(ms).toString() }, it.notice, it.attempts)
                    }
                } else null,
                memberCounts = if (lazyProjects.isInitialized()) projects.memberCounts.first() else emptyMap(),
                accountRound = if (lazyPins.isInitialized()) {
                    pins.state.value.let { ProjectDiagnostics.AccountRoundSummary(true, it.active, it.isSyncing, it.lastSyncedAtMillis?.takeIf { ms -> ms > 0 }?.let { ms -> java.time.Instant.ofEpochMilli(ms).toString() }, it.error) }
                } else null,
                rootFailures = agents.rootFailures(),
                notificationPrefs = prefs.projectNotifications.first(),
                managerCandidates = list.agents.mapNotNullTo(LinkedHashSet()) { row -> row.parent?.takeIf { it.kind == com.cursorforandroid.domain.AgentParentKind.PROJECT_WORKER }?.id },
                refresh = refreshStats.snapshot.value,
                loading = ProjectDiagnostics.LoadingSummary(
                    shown = pendingWork.state.value.shown,
                    work = pendingWork.describe(),
                    isRefreshing = list.isRefreshing,
                    isLoadingMore = list.isLoadingMore,
                    hasMore = list.hasMore,
                    loadMoreError = list.loadMoreError,
                    pagesLoaded = agents.pagesLoadedCount(),
                    hasNextCursor = agents.hasNextCursor(),
                ),
            ),
        )
    }

    /**
     * The redacted account of the last opened chat's transcript as this build reads it (see [TranscriptDiagnostics]):
     * item kinds, tool names with their argument key names, the payload read off each call, and the decision that
     * reads the chat as a coordinator's. No message, prompt or argument text is in it.
     */
    suspend fun transcriptDiagnosticsReport(forAgentId: String? = null): String {
        val agentId = forAgentId ?: if (lazyConversations.isInitialized()) conversations.lastOpenedAgentId.value else null
        val state = agentId?.let { conversations.state(it).value }
        return TranscriptDiagnostics.render(
            TranscriptDiagnostics.Input(
                appVersion = appVersion,
                nowIso = java.time.Instant.ofEpochMilli(AppClock.now()).toString(),
                extendedMode = extendedMode.enabled.first(),
                engine = extendedMode.engine(),
                agentId = agentId,
                agent = agentId?.let { agents.agent(it) },
                state = state?.let {
                    TranscriptDiagnostics.State(
                        items = it.items,
                        isLoading = it.isLoading,
                        isStreaming = it.isStreaming,
                        isReconnecting = it.isReconnecting,
                        runStatus = it.runStatus,
                        hasOlder = it.hasOlder,
                        transcriptUnavailable = it.transcriptUnavailable,
                        recordProjectMode = it.isProjectConversation,
                        error = it.error,
                    )
                },
                placement = agentId?.let { agents.placementOf(it) },
                load = agentId?.let { conversations.loadDiagnostics(it) },
                fileReads = agentId?.takeIf { lazyAgentFileReads.isInitialized() }?.let { id -> agentFileReads.attempts(id).map { it.text } }.orEmpty(),
                media = agentId?.takeIf { lazyMedia.isInitialized() }?.let { id -> lazyMedia.value.loads.lines(id) }.orEmpty(),
                throttle = lazyAccountRpc.takeIf { it.isInitialized() }?.value?.throttle?.describe(),
                perf = agentId?.let { com.cursorforandroid.domain.TranscriptPerf.sessionOrNull(it)?.snapshot() },
                send = agentId?.let { sendDiagnostics(it) },
            ),
        )
    }

    /**
     * The `send:` block's input for one chat: the follow-up path's account of it (the send-or-queue decision, the
     * queue, the attempts) with the launch that started the chat from here, when this process did — so a chat whose
     * launch failed, or never reached the API, has a block even though nothing was ever queued for it.
     */
    private fun sendDiagnostics(agentId: String): SendDiagnostics? {
        val sends = if (lazyFollowUps.isInitialized()) followUps.sendDiagnostics(agentId) else null
        val launch = if (lazyAgents.isInitialized()) agents.launchDiagnostics(agentId) else null
        return when {
            sends != null -> sends.copy(launch = launch)
            launch != null -> SendDiagnostics(decision = null, decidedAtIso = null, queue = emptyList(), attempts = emptyList(), accepted = emptyList(), launch = launch)
            else -> null
        }
    }

    /**
     * Both exports above, written as one file into the Cursor for Android Project's context store
     * (`inbox/diagnostics/<timestamp>.txt`, see [DiagnosticsInbox]) through the account's store writes, so the
     * Project's workers read them from the store rather than from a paste. Extended mode: the store is found and
     * written through the same client the Context browser reads with. Returns the path written; throws with the
     * reason when it could not be — the mode off, no store listed, the account refusing, the storage refusing.
     */
    suspend fun sendDiagnosticsToProject(): String {
        if (session.isDemo) throw java.io.IOException(DiagnosticsInbox.DEMO_HAS_NO_ACCOUNT)
        val now = AppClock.now()
        val crashes = withContext(Dispatchers.IO) { crashLog.reports().takeIf { it.isNotEmpty() }?.let { crashLog.export() } }
        val text = DiagnosticsInbox.compose(appVersion, now, projectDiagnosticsReport(), transcriptDiagnosticsReport(), crashes)
        return storeFiles.writeText(DiagnosticsInbox.PROJECT_ID, DiagnosticsInbox.path(now), text)
    }

    /**
     * The system asking for memory back ([level] as `onTrimMemory` gives it): what is built gives up what it can read
     * again — the chats nobody shows, holds or streams, the runs nobody follows, decoded images. A hidden UI trims
     * gently; memory running low, or the app in the background, trims hard. Nothing is built to be trimmed.
     */
    fun trimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val hard = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        @Suppress("DEPRECATION")
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !hard) return
        val chats = if (lazyConversations.isInitialized()) conversations.trimMemory(hard) else 0
        val runs = if (lazyLiveRuns.isInitialized()) liveRuns.trimMemory() else 0
        if (lazyMedia.isInitialized()) media.trimMemory(hard)
        Breadcrumbs.add("trim memory $level: ${if (hard) "hard" else "gentle"}, $chats chats and $runs runs let go")
    }

    /**
     * The app's account of the moment, for a crash report (see [CrashLog.install]): the build and device, background
     * live sync, the chats in memory and the run streams, memory and threads by pool, and the last things done. Reads
     * only what is already built — nothing is created to describe it.
     */
    fun crashContext(): String = buildString {
        appendLine("version: $appVersion (${BuildConfig.VERSION_CODE}) android=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("process up: ${(android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000} s")
        val sync = if (lazyLiveSync.isInitialized()) liveSync else null
        appendLine("live sync: ${sync?.mode ?: "not started"} held=${sync?.heldIds?.value?.size ?: 0}")
        if (lazyAgents.isInitialized()) appendLine("agents listed: ${agents.state.value.agents.size} running=${agents.state.value.agents.count { it.isRunning }}")
        if (lazyConversations.isInitialized()) appendLine("conversations: ${conversations.stats()}")
        if (lazyLiveRuns.isInitialized()) appendLine("run streams: ${liveRuns.stats()}")
        append(CrashContext.runtime(app.getSystemService(android.app.ActivityManager::class.java)))
        val recent = Breadcrumbs.lines()
        if (recent.isNotEmpty()) {
            appendLine("recent:")
            recent.forEach { appendLine("  $it") }
        }
    }

    suspend fun signOut() = session.signOut()
}

/** How long one read of the account's record may take, headers to the last byte: the pages are large and the connection may be slow. */
private const val RECORD_CALL_TIMEOUT_MINUTES = 5L
private const val TRANSCRIPTION_CALL_TIMEOUT_SECONDS = 60L
