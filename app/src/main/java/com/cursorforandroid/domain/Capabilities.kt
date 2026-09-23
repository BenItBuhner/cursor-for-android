package com.cursorforandroid.domain

/**
 * Which of the app's features may call Cursor's undocumented `api2.cursor.sh` endpoints — the `aiserver.v1` RPCs
 * behind cursor.com and the first-party apps, and the session exchange that feeds them. None of them is part of the
 * documented Cloud Agents API, so all of them follow one setting, Extended mode, which is off by default; each flag
 * names the surface it stands for so a call site says what it needs rather than testing the setting itself.
 *
 * With every flag off the app speaks to `api.cursor.com` (v1 plus the two legacy v0 endpoints), to `cursor.com` /
 * `api2.cursor.sh` only for the browser sign-in Cursor's own SDK and CLI perform, and to GitHub's REST API for what
 * a GitHub-hosted repository can say about itself.
 */
data class Capabilities(
    /** `POST /auth/exchange_user_api_key`: the account session every other flag's call is authenticated with. */
    val accountSession: Boolean,
    /** `DashboardService/GetMe`: the profile picture (and the team name). Off: `/v1/me` and the initials. */
    val accountProfile: Boolean,
    /** `BackgroundComposerService/{List,Pin,Unpin}BackgroundComposers`. Off: pins are kept on this device only. */
    val pinSync: Boolean,
    /** `ArchiveBackgroundComposer` / `RenameBackgroundComposer`. Off: the public archive only, and no rename. */
    val accountLifecycle: Boolean,
    /** `DashboardService/Get{Repo,BackgroundComposer}SlashCommands` and `GetGlobalCommands`. Off: the built-ins plus what the repository itself holds. */
    val accountSlashCommands: Boolean,
    /** `GetPullRequestMergeStatus` and the list's `prStatus`. Off: GitHub's REST API for GitHub-hosted repositories, nothing for the rest. */
    val accountPullRequests: Boolean,
    /**
     * Cursor Projects on the account service: the lineage reads (`ListWorkersForManager`, `ListBackgroundComposerChildren`,
     * the list's workers and subagents), the coordinator's actions (`CreateProjectWorker`, `SetWorkerManager`,
     * `ClearWorkerManager`, `ReparentBackgroundComposer`, `UpdateProjectAppearance`, `StartSideChatBackgroundComposer`)
     * and the Project's shared context (`ListAgentStore*`, `ReadAgentStoreFile`). Off: a Project is told apart only
     * by what its own transcript says, and its view offers no action.
     */
    val projects: Boolean,
    /**
     * Steering a running chat (`InjectBackgroundComposerContext`), holding it (`PauseBackgroundComposer` /
     * `ResumeBackgroundComposer`), stopping one of its tool calls (`CancelBackgroundComposerToolCall`) and waking its
     * machine (`WakeBackgroundComposer`). Off: the public cancel only.
     */
    val steering: Boolean,
    /** The agent's live workspace: `ListWorkspaceFiles` and `ReadBinaryFile`. Off: only the files the stream carried, and the repository's tree. */
    val workspaceFiles: Boolean,
    /** The branch's diff against its base before a pull request exists: `GetBackgroundComposerDiffDetails`. Off: the edits the stream carried. */
    val diffDetails: Boolean,
    /**
     * A pull request on any host Cursor connects, read through the account (`SCMService/GetPullRequest`,
     * `GetPullRequestDiff`, `GetDetailedPullRequestStatus`, `GetPullRequestDiscussions`) and opened from here
     * (`MakePRBackgroundComposer` / `OpenPRBackgroundComposer`). Off: GitHub's REST API for GitHub-hosted repositories, the browser for the rest.
     */
    val scmPullRequests: Boolean,
    /** The agent's VM desktop over noVNC: `GetMachine` for the pod and its ticket. Off: nothing; the machine's own state stays public. */
    val remoteDesktop: Boolean,
    /** Answering an agent's `ask_question` from here (`SubmitInteractionResponseBackgroundComposer`). Off: the question is read-only, answered on cursor.com. */
    val interactions: Boolean,
    /**
     * The account's follow-up queue (`AddAsyncFollowupBackgroundComposer`, `ListPendingFollowups`, `UpdatePendingFollowup`,
     * `DeletePendingFollowup`, `ReorderPendingFollowup`, `SubmitPendingFollowupNow`, `MarkFollowupEditing`): the queue
     * the desktop, the web and the iOS app share. Off: follow-ups sent mid-turn wait on this device and go out when the turn ends.
     */
    val accountQueue: Boolean,
    /** Ask and Debug modes for a follow-up, which only the account's follow-up RPC can carry (`agent.v1.AgentMode`). Off: agent and plan. */
    val agentModes: Boolean,
    /**
     * The account's own copy of a chat's transcript, tool calls included (`BackgroundComposerService/StreamConversation`
     * for the state and `GetBlobForAgentKV` for the turns' blobs), for the turns whose documented event log has
     * expired and which this device never saw. Off: those turns keep their text alone. In Extended mode this follows
     * the [TranscriptEngine]: on for Beta, off for Stable.
     */
    val accountTranscript: Boolean = false,
    /**
     * The goal the account keeps on a chat (`StreamConversation`'s initial state, the `goal_state` of the
     * conversation's `ConversationStateStructure`): its status, its objective and the active time it has accrued, as
     * Cursor's own clients read them. Off: the goal as the chat's own transcript tells it — the agent's `CreateGoal` /
     * `UpdateGoal` calls on the documented stream and the "Goal continued" turns. The same record read as
     * [accountTranscript], so it follows the [TranscriptEngine] the same way.
     */
    val accountGoal: Boolean = false,
    /**
     * Files of any type attached to a prompt, the way the desktop Agents Window attaches them: uploaded through the
     * account's `PresignPromptUpload` / `CompletePromptUpload` and referenced from the prompt as `selected_documents[]`
     * on `AddAsyncFollowupBackgroundComposer` and `StartBackgroundComposerFromSnapshot`. Off: the documented
     * `prompt.images[]` alone — the picker offers images only.
     */
    val promptFiles: Boolean = false,
    /**
     * A new chat on one of the user's machines, started the way the desktop starts one: `StartBackgroundComposerFromSnapshot`
     * with `use_private_worker`, the `repo=` / `name=` labels, `selected_private_worker_id` and
     * `private_worker_owner_filter`, which Cursor routes to the machine without asking its GitHub app about the
     * repository. Off: the documented `POST /v1/agents` with `env {type: machine}`, which does ask it.
     */
    val machineStart: Boolean = false,
) {
    /** True when any private surface is on: what the persistent indicator and the default-mode explanations go by. */
    val anyExtended: Boolean
        get() = accountSession || accountProfile || pinSync || accountLifecycle || accountSlashCommands || accountPullRequests || projects || steering ||
            workspaceFiles || diffDetails || scmPullRequests || remoteDesktop || interactions || accountQueue || agentModes || accountTranscript || accountGoal ||
            promptFiles || machineStart

    companion object {
        /** The default: the documented API only. */
        val DOCUMENTED = Capabilities(
            accountSession = false,
            accountProfile = false,
            pinSync = false,
            accountLifecycle = false,
            accountSlashCommands = false,
            accountPullRequests = false,
            projects = false,
            steering = false,
            workspaceFiles = false,
            diffDetails = false,
            scmPullRequests = false,
            remoteDesktop = false,
            interactions = false,
            accountQueue = false,
            agentModes = false,
            accountTranscript = false,
            accountGoal = false,
            promptFiles = false,
            machineStart = false,
        )

        /** Extended mode: every private surface, exactly as the app used them before the setting existed. */
        val EXTENDED = Capabilities(
            accountSession = true,
            accountProfile = true,
            pinSync = true,
            accountLifecycle = true,
            accountSlashCommands = true,
            accountPullRequests = true,
            projects = true,
            steering = true,
            workspaceFiles = true,
            diffDetails = true,
            scmPullRequests = true,
            remoteDesktop = true,
            interactions = true,
            accountQueue = true,
            agentModes = true,
            accountTranscript = true,
            accountGoal = true,
            promptFiles = true,
            machineStart = true,
        )

        /** Extended mode's Stable transcript engine: every private surface but the record read and the goal it carries (see [TranscriptEngine]). */
        val EXTENDED_STABLE = EXTENDED.copy(accountTranscript = false, accountGoal = false)

        /**
         * What the mode and the transcript engine allow: nothing private with the mode off; with it on, the record
         * read only under the Beta engine. This is what the app reads (see `ExtendedMode.capabilities`).
         */
        fun of(extendedMode: Boolean, engine: TranscriptEngine): Capabilities = when {
            !extendedMode -> DOCUMENTED
            engine == TranscriptEngine.BETA -> EXTENDED
            else -> EXTENDED_STABLE
        }

        /** Every private surface at once, as before the transcript engine setting existed — the Beta engine's set; the tests' shorthand. */
        fun of(extendedMode: Boolean): Capabilities = of(extendedMode, TranscriptEngine.BETA)
    }
}
